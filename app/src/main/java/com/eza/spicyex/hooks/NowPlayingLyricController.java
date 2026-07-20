package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LiveLyricCardView;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsDisplayMode;
import com.eza.spicyex.lyrics.LyricsFetchErrors;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsLocalRomanizer;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.SpicyTextDetection;
import com.eza.spicyex.lyrics.SyllableSegment;

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
    private final SharedPreferences preferences;
    private boolean preferencesRegistered;
    private boolean preferenceRefreshPosted;
    private final Runnable preferenceRefreshRunnable = this::refreshPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> schedulePreferenceRefresh();

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
    private long lastCardTapMs;
    private long lastFrameMs;
    private long nextFetchAllowedMs;
    private boolean running;
    private SpotifyTrack throttledTrack;
    private long throttledTrackAtMs;
    private boolean throttledTrackInitialized;
    private boolean frameErrorLogged;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            try {
                onFrame(frameTimeNanos / 1_000_000L);
            } catch (Throwable t) {
                if (!frameErrorLogged) {
                    frameErrorLogged = true;
                    de.robv.android.xposed.XposedBridge.log(NativeSpicyLyricsHook.TAG + " live card frame error: " + android.util.Log.getStackTraceString(t));
                }
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    NowPlayingLyricController(NativeSpicyLyricsHook hook, Activity activity, LiveLyricCardView card) {
        this.hook = hook;
        this.activity = activity;
        this.card = card;
        this.config = SpotifyPlusConfig.from(activity);
        this.preferences = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        refreshConfig();
        this.card.setOnClickListener(v -> handleCardTap());
    }

    private boolean refreshConfig() {
        LyricsRenderConfig next = LyricsRenderConfig.read(activity, config);
        boolean changed = renderConfig == null
                || !renderConfig.liveCardSecondaryMode.equals(next.liveCardSecondaryMode)
                || renderConfig.liveCardShowTransliteration != next.liveCardShowTransliteration
                || renderConfig.liveCardShowTranslation != next.liveCardShowTranslation
                || !renderConfig.liveCardWeight.equals(next.liveCardWeight)
                || !renderConfig.lyricsFont.equals(next.lyricsFont)
                || !renderConfig.liveCardTextSizeMode.equals(next.liveCardTextSizeMode)
                || renderConfig.liveCardMinimalAnimation != next.liveCardMinimalAnimation
                || !renderConfig.liveCardAnimationMode.equals(next.liveCardAnimationMode)
                || !renderConfig.liveCardGlowMode.equals(next.liveCardGlowMode)
                || !renderConfig.liveCardLineSyncFillMode.equals(next.liveCardLineSyncFillMode)
                || !renderConfig.liveCardTransitionMode.equals(next.liveCardTransitionMode)
                || !renderConfig.liveCardOverflowMode.equals(next.liveCardOverflowMode)
                || !renderConfig.liveCardScrollScope.equals(next.liveCardScrollScope)
                || renderConfig.adaptiveSectioningEnabled != next.adaptiveSectioningEnabled
                || renderConfig.attachTransliterationToWords != next.attachTransliterationToWords
                || !renderConfig.defaultJapaneseReadingMode.equals(next.defaultJapaneseReadingMode)
                || !renderConfig.defaultChineseMode.equals(next.defaultChineseMode)
                || !renderConfig.defaultKoreanMode.equals(next.defaultKoreanMode)
                || !renderConfig.koreanMode.equals(next.koreanMode)
                || !renderConfig.defaultCyrillicMode.equals(next.defaultCyrillicMode)
                || renderConfig.chineseTones != next.chineseTones
                || renderConfig.cyrillicKeepSigns != next.cyrillicKeepSigns
                || renderConfig.translationEnabled != next.translationEnabled
                || !renderConfig.translationTarget.equals(next.translationTarget)
                || renderConfig.translationBright != next.translationBright;
        renderConfig = next;
        if (changed) card.applyConfig(renderConfig);
        return changed;
    }

    void start() {
        if (running) return;
        running = true;
        registerPreferenceListener();
        Choreographer.getInstance().postFrameCallback(frame);
    }

    void stop() {
        running = false;
        unregisterPreferenceListener();
        Choreographer.getInstance().removeFrameCallback(frame);
        handler.removeCallbacksAndMessages(null);
    }

    private void onFrame(long nowMs) {
        float deltaSeconds = lastFrameMs <= 0L ? (1f / 60f)
                : Math.max(1f / 120f, Math.min(1f / 15f, (nowMs - lastFrameMs) / 1000f));
        lastFrameMs = nowMs;
        SpotifyTrack track = currentTrackThrottled(nowMs);
        if (track == null) return;
        String id = NativeLyricsUtils.trackIdFromUri(track.uri);

        // On track change, drop the previous song's line immediately so a no-lyric next song can't
        // show a stale lyric while (or instead of) loading.
        if (!id.equals(currentId)) {
            currentId = id;
            card.clear();
            lastIdx = Integer.MIN_VALUE;
            placeholderShown = false;
            nextFetchAllowedMs = 0L;
            document = null;
            loadedId = "";
        }

        // Track-change / fetch check is throttled — only the gradient needs per-frame work.
        if (nowMs - lastTrackCheckMs > 400) {
            lastTrackCheckMs = nowMs;
            if (!id.isEmpty() && nowMs >= nextFetchAllowedMs
                    && !id.equals(loadedId) && !id.equals(loadingId) && !id.equals(failedId)) {
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
        if (isUnsyncedDocument(document)) {
            if (!placeholderShown) { card.setInterlude(renderConfig.interludeNoteIcon); placeholderShown = true; }
            return;
        }

        List<AppliedLine> lines = document.appliedLines;
        if (lines == null || lines.isEmpty()) return;
        long pos = renderConfig.adjustedPositionMs(
                hook.readBestMeasuredProgressMs(track, hook.isPlayerActuallyPlaying()));
        int idx = LyricTimeline.findPrimaryActiveRow(lines, pos);
        if (idx < 0 || idx >= lines.size()) {
            if (lastIdx != -1) { card.clear(); lastIdx = -1; }
            return;
        }
        AppliedLine cur = lines.get(idx);
        boolean lineChanged = idx != lastIdx;
        if (idx != lastIdx) {
            lastIdx = idx;
        }
        card.renderLine(activity, cur, renderConfig, pos, deltaSeconds,
                document,
                lineChanged);
    }

    private void handleCardTap() {
        refreshConfig();
        String mode = config.get(com.eza.spicyex.Settings.LIVE_CARD_TAP_MODE);
        if ("Off".equals(mode)) return;
        long now = SystemClock.uptimeMillis();
        if ("Single tap".equals(mode)) {
            hook.launchNativeLyricsFullscreen(activity);
            return;
        }
        if (now - lastCardTapMs <= 340L) {
            lastCardTapMs = 0L;
            hook.launchNativeLyricsFullscreen(activity);
        } else {
            lastCardTapMs = now;
        }
    }

    private void refreshPreferences() {
        preferenceRefreshPosted = false;
        if (running && refreshConfig()) lastIdx = Integer.MIN_VALUE;
    }

    private void schedulePreferenceRefresh() {
        if (!running || preferenceRefreshPosted) return;
        preferenceRefreshPosted = true;
        handler.post(preferenceRefreshRunnable);
    }

    private void registerPreferenceListener() {
        if (preferencesRegistered) return;
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        preferencesRegistered = true;
    }

    private void unregisterPreferenceListener() {
        if (!preferencesRegistered) return;
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        preferencesRegistered = false;
        preferenceRefreshPosted = false;
        handler.removeCallbacks(preferenceRefreshRunnable);
    }

    private void fetch(SpotifyTrack track, final String id) {
        loadingId = id;
        final int gen = ++fetchGen;
        final LyricsRenderConfig fetchConfig = renderConfig;
        final RomanizationOptions fetchOptions = romanizationOptions();
        try {
        hook.fetchLyrics(track, new NativeSpicyLyricsHook.LyricsResultCallback() {
            @Override
            public void onSuccess(LyricsDocument doc) {
                LyricsDocumentProcessor.applyProcessedCache(activity.getApplicationContext(), doc,
                        fetchOptions, NativeRuntime.GOOGLE_PROCESSING_VERSION);
                prepareLiveCardRomanization(doc, fetchConfig, fetchOptions);
                LyricTimeline.applySyncedRows(doc);
                handler.post(() -> {
                    if (gen != fetchGen) return;
                    // Clear loadingId even when stopped: leaving it set while paused (user in
                    // fullscreen/settings) permanently blocked re-fetch for this track, wedging
                    // the card empty until a track change.
                    loadingId = "";
                    if (!running) return;
                    refreshConfig();
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
                    if (LyricsFetchErrors.isDurableNoLyrics(error)) {
                        failedId = id; // durable no lyrics — keep the placeholder and avoid retry spam
                        return;
                    }
                    // Transient provider/network/native misses should retry, but not every frame.
                    if (id.equals(currentId)) {
                        nextFetchAllowedMs = System.nanoTime() / 1_000_000L + 5000L;
                    }
                });
            }
        });
        } catch (Throwable t) {
            // A synchronous fetch failure must not leave loadingId stuck (blocks all retries).
            loadingId = "";
            nextFetchAllowedMs = System.nanoTime() / 1_000_000L + 5000L;
        }
    }

    private SpotifyTrack currentTrackThrottled(long nowMs) {
        if (!throttledTrackInitialized || nowMs - throttledTrackAtMs >= 250L) {
            throttledTrack = hook.getCurrentTrackSafely();
            throttledTrackAtMs = nowMs;
            throttledTrackInitialized = true;
        }
        return throttledTrack;
    }

    private void prepareLiveCardRomanization(LyricsDocument doc) {
        prepareLiveCardRomanization(doc, renderConfig, romanizationOptions());
    }

    private void prepareLiveCardRomanization(LyricsDocument doc, LyricsRenderConfig config,
                                              RomanizationOptions opts) {
        if (config == null || !config.liveCardShowTransliteration || doc == null || doc.lines == null) return;
        String fullText = LyricsDocumentProcessor.collectText(doc);
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            boolean japanese = SpicyJapaneseChineseProcessor.canRomanizeJapanese(line.text);
            boolean missingJapaneseReading = japanese && (line.japaneseReading == null
                    || line.japaneseReading.furigana == null
                    || line.japaneseReading.furigana.isEmpty());
            boolean finalizedNonJapanesePlan = !japanese && line.readingRenderPlan != null
                    && !isBlank(line.readingRenderPlan.joinedDisplayText);
            if (finalizedNonJapanesePlan) continue;
            if (!missingJapaneseReading && !japanese && !isBlank(line.romanizedText)
                    && !SpicyTextDetection.hasRomanizableScript(line.romanizedText)) continue;
            String local = LyricsLocalRomanizer.romanizeLine(opts, doc, line, fullText);
            if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                line.romanizedText = line.readingRenderPlan == null ? local : "";
            }
        }
    }

    private RomanizationOptions romanizationOptions() {
        return new RomanizationOptions(renderConfig.defaultChineseMode, renderConfig.koreanMode,
                renderConfig.chineseTones, renderConfig.defaultCyrillicMode, renderConfig.cyrillicKeepSigns);
    }

    private boolean isUnsyncedDocument(LyricsDocument doc) {
        if (doc == null) return true;
        return !"Line".equalsIgnoreCase(doc.type) && !"Syllable".equalsIgnoreCase(doc.type);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
