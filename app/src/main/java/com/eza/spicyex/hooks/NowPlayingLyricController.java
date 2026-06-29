package com.eza.spicyex.hooks;

import android.app.Activity;
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
import com.eza.spicyex.lyrics.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessingSession;
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
    private final LyricsSecondaryProcessingSession secondaryProcessingSession;

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
    private long lastConfigCheckMs;
    private long lastProcessedCacheCheckMs;
    private long lastCardTapMs;
    private long lastFrameMs;
    private long nextFetchAllowedMs;
    private boolean secondaryProcessingActive;
    private String secondaryProcessingId = "";
    private LyricsDocument secondaryProcessingDocument;
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
        LyricsSecondaryProcessor secondaryProcessor = new LyricsSecondaryProcessor(
                activity,
                NativeRuntime.HTTP,
                NativeRuntime.PROCESSOR,
                NativeRuntime.GOOGLE_WORKERS,
                handler,
                NativeRuntime.GOOGLE_PROCESSING_VERSION);
        this.secondaryProcessingSession = new LyricsSecondaryProcessingSession(
                activity,
                config,
                secondaryProcessor,
                NativeRuntime.GOOGLE_PROCESSING_VERSION,
                NativeSpicyLyricsHook.TAG);
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
                || renderConfig.attachTransliterationToWords != next.attachTransliterationToWords
                || !renderConfig.defaultJapaneseReadingMode.equals(next.defaultJapaneseReadingMode)
                || !renderConfig.defaultChineseMode.equals(next.defaultChineseMode)
                || !renderConfig.defaultKoreanMode.equals(next.defaultKoreanMode)
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
        Choreographer.getInstance().postFrameCallback(frame);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        handler.removeCallbacksAndMessages(null);
    }

    private void onFrame(long nowMs) {
        float deltaSeconds = lastFrameMs <= 0L ? (1f / 60f)
                : Math.max(1f / 120f, Math.min(1f / 15f, (nowMs - lastFrameMs) / 1000f));
        lastFrameMs = nowMs;
        SpotifyTrack track = hook.getCurrentTrackSafely();
        if (track == null) return;
        String id = NativeLyricsUtils.trackIdFromUri(track.uri);

        if (nowMs - lastConfigCheckMs > 750) {
            lastConfigCheckMs = nowMs;
            if (refreshConfig()) lastIdx = Integer.MIN_VALUE;
        }

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
            secondaryProcessingActive = false;
            secondaryProcessingId = "";
            secondaryProcessingDocument = null;
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
        refreshProcessedSecondaryRows(nowMs);
        startSecondaryProcessingIfNeeded(id);

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
                this::segmentRomanizedText,
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
                            romanizationOptions(),
                            NativeRuntime.GOOGLE_PROCESSING_VERSION);
                    prepareLiveCardRomanization(doc);
                    LyricTimeline.applySyncedRows(doc); // build appliedLines from doc.lines
                    loadingId = "";
                    if (doc.appliedLines == null || doc.appliedLines.isEmpty()) {
                        failedId = id; // track has no usable lyrics — don't display or re-fetch
                        return;
                    }
                    document = doc;
                    loadedId = id;
                    lastIdx = Integer.MIN_VALUE; // force a line refresh
                    startSecondaryProcessingIfNeeded(id);
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
    }

    private void refreshProcessedSecondaryRows(long nowMs) {
        if (!renderConfig.liveCardShowTransliteration && !renderConfig.liveCardShowTranslation) return;
        if (document == null || nowMs - lastProcessedCacheCheckMs < 2500L) return;
        lastProcessedCacheCheckMs = nowMs;
        String before = secondarySignature(document);
        LyricsDocumentProcessor.applyProcessedCache(activity, document, romanizationOptions(),
                NativeRuntime.GOOGLE_PROCESSING_VERSION);
        prepareLiveCardRomanization(document);
        if (!before.equals(secondarySignature(document))) {
            LyricTimeline.applySyncedRows(document);
        }
    }

    private void startSecondaryProcessingIfNeeded(String id) {
        LyricsDocument snapshot = document;
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty() || renderConfig == null) return;
        boolean wantsRomanization = renderConfig.liveCardShowTransliteration && snapshot.romanizationPending;
        boolean wantsTranslation = renderConfig.liveCardShowTranslation && snapshot.translationPending;
        if (!wantsRomanization && !wantsTranslation) return;
        if (secondaryProcessingActive && snapshot == secondaryProcessingDocument && id.equals(secondaryProcessingId)) return;
        secondaryProcessingActive = true;
        secondaryProcessingId = id;
        secondaryProcessingDocument = snapshot;
        boolean started = secondaryProcessingSession.start(id, fetchGen, snapshot,
                renderConfig.liveCardShowTransliteration,
                romanizationOptions(),
                this::isCurrentProcessingResult,
                new LyricsSecondaryProcessingSession.Callback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void rerender(LyricsDocument callbackSnapshot, String message) {
                        applySecondaryProcessingUpdate(callbackSnapshot);
                    }

                    @Override
                    public void progress(LyricsDocument callbackSnapshot, String message) {
                    }

                    @Override
                    public void complete(LyricsDocument callbackSnapshot, String message, int changed) {
                        if (callbackSnapshot == secondaryProcessingDocument) {
                            secondaryProcessingActive = false;
                            secondaryProcessingId = "";
                            secondaryProcessingDocument = null;
                        }
                        applySecondaryProcessingUpdate(callbackSnapshot);
                    }
                });
        if (!started) {
            secondaryProcessingActive = false;
            secondaryProcessingId = "";
            secondaryProcessingDocument = null;
        }
    }

    private boolean isCurrentProcessingResult(String id, int generation, LyricsDocument snapshot) {
        if (!running || document != snapshot) return false;
        if (generation > 0 && generation != fetchGen) return false;
        return isBlank(id) || id.equals(currentId);
    }

    private void applySecondaryProcessingUpdate(LyricsDocument snapshot) {
        if (!running || document != snapshot) return;
        prepareLiveCardRomanization(snapshot);
        LyricTimeline.applySyncedRows(snapshot);
        lastIdx = Integer.MIN_VALUE;
    }

    private String secondarySignature(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return "";
        StringBuilder builder = new StringBuilder();
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            builder.append(line.startMs)
                    .append('|').append(line.endMs)
                    .append('|').append(line.romanizedText)
                    .append('|').append(line.translatedText)
                    .append('|').append(japaneseReadingSignature(line.japaneseReading))
                    .append('\n');
        }
        return builder.toString();
    }

    private String japaneseReadingSignature(SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        if (reading == null) return "";
        StringBuilder builder = new StringBuilder();
        builder.append(reading.sourceText).append('|').append(reading.romaji);
        if (reading.furigana == null) return builder.toString();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : reading.furigana) {
            if (segment == null) continue;
            builder.append('|')
                    .append(segment.start)
                    .append('-')
                    .append(segment.end)
                    .append(':')
                    .append(segment.reading);
        }
        return builder.toString();
    }

    private void prepareLiveCardRomanization(LyricsDocument doc) {
        if (!renderConfig.liveCardShowTransliteration || doc == null || doc.lines == null) return;
        String fullText = LyricsDocumentProcessor.collectText(doc);
        RomanizationOptions opts = romanizationOptions();
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            boolean japanese = SpicyJapaneseChineseProcessor.canRomanizeJapanese(line.text);
            boolean missingJapaneseReading = japanese && (line.japaneseReading == null
                    || line.japaneseReading.furigana == null
                    || line.japaneseReading.furigana.isEmpty());
            if (!missingJapaneseReading && !japanese && !isBlank(line.romanizedText)
                    && !SpicyTextDetection.hasRomanizableScript(line.romanizedText)) continue;
            String local = LyricsLocalRomanizer.romanizeLine(opts, doc, line, fullText);
            if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                line.romanizedText = local;
            }
        }
    }

    private RomanizationOptions romanizationOptions() {
        return new RomanizationOptions(renderConfig.defaultChineseMode, renderConfig.defaultKoreanMode,
                renderConfig.chineseTones, renderConfig.defaultCyrillicMode, renderConfig.cyrillicKeepSigns);
    }

    private String segmentRomanizedText(AppliedLine line, SyllableSegment seg, String fullText) {
        if (seg == null || isBlank(seg.text)) return "";
        if (!isBlank(seg.romanizedText)) return seg.romanizedText;
        if (LyricsDisplayMode.isJapaneseLine(line)) return "";
        LyricsLine source = line == null ? null : line.sourceLine;
        String local = LyricsLocalRomanizer.romanizeText(romanizationOptions(), document, seg.text,
                fullText, source == null ? "" : source.chineseMode);
        if (!isBlank(local) && !local.equals(seg.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
            seg.romanizedText = local;
            return local;
        }
        return "";
    }

    private boolean isUnsyncedDocument(LyricsDocument doc) {
        if (doc == null) return true;
        return !"Line".equalsIgnoreCase(doc.type) && !"Syllable".equalsIgnoreCase(doc.type);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
