package com.eza.spicyex.hooks;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LiveLyricCardView;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsLineAnimationState;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.RomanizationOptions;

import java.util.List;

/**
 * Drives the {@link LiveLyricCardView} that replaces Spotify's now-playing lyric snippet. Runs a
 * vsync loop (so the current line inherits the fullscreen engine's karaoke wash): polls position via
 * the hook, fetches lyrics on track change, swaps the 3 lines on active-line change, and washes the
 * current line's gradient each frame. Paused on activity pause, stopped on destroy.
 */
final class NowPlayingLyricController {
    private final NativeSpicyLyricsHook hook;
    private final Activity activity;
    private final LiveLyricCardView card;
    private final SpotifyPlusConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Inherited from the same shared Spotify-side prefs the fullscreen screen reads — no separate
    // per-component config. Refreshed on each fetch so panel/chip toggles take effect on next track.
    private LyricsRenderConfig renderConfig;

    private LyricsDocument document;
    private String currentId = "";   // track currently on screen
    private String loadedId = "";
    private String loadingId = "";
    private String failedId = "";    // track known to have no lyrics — don't show stale / re-fetch
    private boolean placeholderShown;
    private int fetchGen;
    private int lastIdx = Integer.MIN_VALUE;
    private long lastTrackCheckMs;
    private boolean running;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            try {
                onFrame(frameTimeNanos / 1_000_000L);
            } catch (Throwable ignored) {
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    NowPlayingLyricController(NativeSpicyLyricsHook hook, Activity activity, LiveLyricCardView card) {
        this.hook = hook;
        this.activity = activity;
        this.card = card;
        this.config = SpotifyPlusConfig.from(activity);
        refreshConfig();
    }

    private void refreshConfig() {
        renderConfig = LyricsRenderConfig.read(activity, config);
    }

    void start() {
        if (running) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        handler.removeCallbacksAndMessages(null);
    }

    private void onFrame(long nowMs) {
        SpotifyTrack track = hook.getCurrentTrackSafely();
        if (track == null) return;
        String id = NativeLyricsUtils.trackIdFromUri(track.uri);

        // On track change, drop the previous song's line immediately so a no-lyric next song can't
        // show a stale lyric while (or instead of) loading.
        if (!id.equals(currentId)) {
            currentId = id;
            card.clear();
            lastIdx = Integer.MIN_VALUE;
            placeholderShown = false;
        }

        // Track-change / fetch check is throttled — only the gradient needs per-frame work.
        if (nowMs - lastTrackCheckMs > 400) {
            lastTrackCheckMs = nowMs;
            if (!id.isEmpty() && !id.equals(loadedId) && !id.equals(loadingId) && !id.equals(failedId)) {
                fetch(track, id);
            }
        }
        // No lyrics for this track → show a ♪ placeholder (set once).
        if (id.equals(failedId)) {
            if (!placeholderShown) { card.setInterlude(true); placeholderShown = true; }
            return;
        }
        if (document == null || !id.equals(loadedId)) return; // not loaded for THIS track → stay cleared

        // Unsynced lyrics: no line tracks playback, so the live card can't karaoke-follow —
        // show the interlude indicator (set once) and leave reading to the fullscreen screen.
        if ("Static".equalsIgnoreCase(document.type)) {
            if (!placeholderShown) { card.setInterlude(renderConfig.interludeNoteIcon); placeholderShown = true; }
            return;
        }

        List<AppliedLine> lines = document.appliedLines;
        if (lines == null || lines.isEmpty()) return;
        long pos = hook.readBestMeasuredProgressMs(track, hook.isPlayerActuallyPlaying());
        int idx = LyricTimeline.findPrimaryActiveRow(lines, pos);
        if (idx < 0 || idx >= lines.size()) {
            if (lastIdx != -1) { card.clear(); lastIdx = -1; }
            return;
        }
        AppliedLine cur = lines.get(idx);
        if (idx != lastIdx) {
            lastIdx = idx;
            if (cur.dotLine) card.setInterlude(renderConfig.interludeNoteIcon);
            else card.setLine(cur.text);
        }
        if (!cur.dotLine) {
            LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                    cur, pos, renderConfig.spotlight, renderConfig.lineGradientEnabled);
            card.setGradient(lineState.gradient, lineState.glowTarget);
        }
    }

    private void fetch(SpotifyTrack track, final String id) {
        loadingId = id;
        final int gen = ++fetchGen;
        hook.fetchLyrics(activity, track, gen, new NativeSpicyLyricsHook.LyricsResultCallback() {
            @Override
            public void onSuccess(LyricsDocument doc) {
                handler.post(() -> {
                    if (!running || gen != fetchGen) return;
                    refreshConfig();
                    // Pull any cached readings/translation the fullscreen screen already produced for
                    // this track (cheap, cache-only — no network). Must run before applySyncedRows,
                    // which copies romanizedText/translatedText into the planned rows.
                    LyricsDocumentProcessor.applyProcessedCache(activity, doc,
                            new RomanizationOptions(renderConfig.defaultChineseMode, renderConfig.koreanMode,
                                    renderConfig.chineseTones, renderConfig.cyrillicMode, renderConfig.cyrillicKeepSigns),
                            NativeRuntime.GOOGLE_PROCESSING_VERSION);
                    LyricTimeline.applySyncedRows(doc); // build appliedLines from doc.lines
                    loadingId = "";
                    if (doc.appliedLines == null || doc.appliedLines.isEmpty()) {
                        failedId = id; // track has no usable lyrics — don't display or re-fetch
                        return;
                    }
                    document = doc;
                    loadedId = id;
                    lastIdx = Integer.MIN_VALUE; // force a line refresh
                });
            }

            @Override
            public void onError(String error) {
                handler.post(() -> {
                    if (gen != fetchGen) return;
                    loadingId = "";
                    failedId = id; // no lyrics / fetch failed — keep the card cleared, don't retry-spam
                });
            }
        });
    }

}
