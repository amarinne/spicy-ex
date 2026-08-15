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
import com.eza.spicyex.lyrics.ArtworkLyricsOverlayView;
import com.eza.spicyex.lyrics.LiveLyricCardView;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsFetchErrors;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsLocalRomanizer;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.LyricsShellLifecycle;
import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.SpicyTextDetection;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the {@link LiveLyricCardView} that replaces Spotify's now-playing lyric snippet. Runs a
 * vsync loop (so the current line inherits the fullscreen engine's karaoke wash): polls position via
 * the hook, fetches lyrics on track change, swaps the 3 lines on active-line change, and washes the
 * current line's gradient each frame. Paused on activity pause, stopped on destroy.
 */
final class NowPlayingLyricController {
    private static final long CANVAS_TRANSFER_SETTLE_MS = 400L;
    interface ArtworkTargetHost {
        NowPlayingArtworkTargetResolver.Resolution resolve(boolean applyBounds);
        void invalidate();
        void setInvalidationListener(TargetInvalidationListener listener);
    }

    interface TargetInvalidationListener {
        void onTargetInvalidated(NowPlayingArtworkTargetResolver.Kind kind);
    }

    private final NativeSpicyLyricsHook hook;
    private final Activity activity;
    private final LiveLyricCardView card;
    private final ArtworkLyricsOverlayView artworkOverlay;
    private final ArtworkTargetHost artworkTargetHost;
    private final LyricsShellLifecycle artworkBackLifecycle;
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

    private LyricsDocument cardDocument;
    private LyricsDocument artworkDocument;
    private volatile String currentId = "";   // track currently on screen
    private String loadedId = "";
    private String loadingId = "";
    private String failedId = "";    // track known to have no lyrics — don't show stale / re-fetch
    private boolean placeholderShown;
    private int fetchGen;
    private int lastIdx = Integer.MIN_VALUE;
    private long lastTrackCheckMs;
    private long lastCardTapMs;
    private long lastFrameMs;
    private long lastArtworkTargetCheckMs;
    private long nextFetchAllowedMs;
    private int artworkTargetRefreshesRemaining;
    private boolean artworkUnavailableGraceArmed;
    private int artworkUnavailableRetriesRemaining;
    private boolean pendingCanvasTransfer;
    private String pendingCanvasTrackId = "";
    private long pendingCanvasAtMs;
    private volatile boolean running;
    private LyricsSessionManager.SessionSubscription sessionSubscription;
    private LyricsSessionManager.LyricsRequest lyricRequest;
    private volatile int observedGeneration = -1;
    private SpotifyTrack throttledTrack;
    private long throttledTrackAtMs;
    private boolean throttledTrackInitialized;
    private boolean frameErrorLogged;
    private final AtomicLong projectionRevision = new AtomicLong();
    private NowPlayingArtworkTargetResolver.Kind lastStableArtworkTarget =
            NowPlayingArtworkTargetResolver.Kind.NONE;
    private final LyricsSessionManager.Listener sessionListener = new LyricsSessionManager.Listener() {
        @Override public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
            if (!running || snapshot == null) return;
            if (snapshot.generation != observedGeneration) observedGeneration = snapshot.generation;
        }

        @Override public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                                LyricsDocument nextDocument) {
            if (!running || snapshot == null || snapshot.track == null || nextDocument == null) return;
            acceptSessionDocument(snapshot, nextDocument);
        }
    };

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

    NowPlayingLyricController(
            NativeSpicyLyricsHook hook,
            Activity activity,
            LiveLyricCardView card,
            ArtworkLyricsOverlayView artworkOverlay,
            ArtworkTargetHost artworkTargetHost
    ) {
        this.hook = hook;
        this.activity = activity;
        this.card = card;
        this.artworkOverlay = artworkOverlay;
        this.artworkTargetHost = artworkTargetHost;
        this.artworkTargetHost.setInvalidationListener(this::onArtworkTargetInvalidated);
        this.artworkBackLifecycle = new LyricsShellLifecycle(activity, this::closeArtwork);
        this.config = SpotifyPlusConfig.from(activity);
        this.preferences = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        refreshConfig();
        this.card.setOnClickListener(v -> handleCardTap());
        this.artworkOverlay.setActions(this::closeArtwork, this::expandArtwork);
    }

    private boolean refreshConfig() {
        LyricsRenderConfig next = LyricsRenderConfig.read(activity, config);
        boolean changed = renderConfig == null
                || !renderConfig.liveCardSecondaryMode.equals(next.liveCardSecondaryMode)
                || renderConfig.liveCardShowTransliteration != next.liveCardShowTransliteration
                || renderConfig.liveCardShowTranslation != next.liveCardShowTranslation
                || renderConfig.lineSpacingMultiplier != next.lineSpacingMultiplier
                || !renderConfig.lyricWeight.equals(next.lyricWeight)
                || !renderConfig.lyricsTextSizeMode.equals(next.lyricsTextSizeMode)
                || renderConfig.lyricsTextSizeMultiplier != next.lyricsTextSizeMultiplier
                || renderConfig.transliterationEnabled != next.transliterationEnabled
                || renderConfig.interludeNoteIcon != next.interludeNoteIcon
                || !renderConfig.lineSyncFillMode.equals(next.lineSyncFillMode)
                || renderConfig.spotlight != next.spotlight
                || renderConfig.lineGradientEnabled != next.lineGradientEnabled
                || renderConfig.glowBlurEnabled != next.glowBlurEnabled
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
                || !renderConfig.translationBackend.equals(next.translationBackend)
                || !renderConfig.translationTarget.equals(next.translationTarget)
                || renderConfig.translationBright != next.translationBright;
        renderConfig = next;
        if (changed) {
            card.applyConfig(renderConfig);
            artworkOverlay.applyConfig(renderConfig);
        }
        return changed;
    }

    void start() {
        if (running) return;
        running = true;
        synchronizeTrackBeforeSubscription();
        registerPreferenceListener();
        sessionSubscription = hook.subscribeLyricsSession(sessionListener);
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void synchronizeTrackBeforeSubscription() {
        SpotifyTrack liveTrack = hook.getCurrentTrackSafely();
        String liveId = liveTrack == null ? "" : NativeLyricsUtils.trackIdFromUri(liveTrack.uri);
        if (liveId.equals(currentId)) return;
        currentId = liveId;
        projectionRevision.incrementAndGet();
        loadedId = "";
        loadingId = "";
        cardDocument = null;
        artworkDocument = null;
        card.clear();
        artworkOverlay.clearDocument();
        artworkTargetHost.invalidate();
        lastIdx = Integer.MIN_VALUE;
        placeholderShown = false;
    }

    void stop() {
        running = false;
        projectionRevision.incrementAndGet();
        fetchGen++;
        if (lyricRequest != null) lyricRequest.close();
        lyricRequest = null;
        loadingId = "";
        if (sessionSubscription != null) sessionSubscription.close();
        sessionSubscription = null;
        artworkBackLifecycle.stop();
        artworkOverlay.hideOverlay();
        clearPendingCanvasTransfer();
        unregisterPreferenceListener();
        Choreographer.getInstance().removeFrameCallback(frame);
        handler.removeCallbacksAndMessages(null);
    }

    boolean consumeArtworkBack() {
        if (!artworkOverlay.isOverlayVisible()) return false;
        closeArtwork();
        return true;
    }

    private void onFrame(long nowMs) {
        float deltaSeconds = lastFrameMs <= 0L ? (1f / 60f)
                : Math.max(1f / 120f, Math.min(1f / 15f, (nowMs - lastFrameMs) / 1000f));
        lastFrameMs = nowMs;
        SpotifyTrack track = currentTrackThrottled(nowMs);
        if (track == null) return;
        String id = NativeLyricsUtils.trackIdFromUri(track.uri);

        evaluatePendingCanvasTransfer(id, nowMs);

        // On track change, drop the previous song's line immediately so a no-lyric next song can't
        // show a stale lyric while (or instead of) loading.
        if (!id.equals(currentId)) {
            boolean artworkWasVisible = artworkOverlay.isOverlayVisible();
            if (artworkWasVisible && !config.get(com.eza.spicyex.Settings.STAY_IN_LYRICS)) {
                closeArtwork();
            }
            currentId = id;
            projectionRevision.incrementAndGet();
            artworkTargetHost.invalidate();
            artworkTargetRefreshesRemaining = artworkWasVisible ? 3 : 0;
            card.clear();
            lastIdx = Integer.MIN_VALUE;
            placeholderShown = false;
            nextFetchAllowedMs = 0L;
            cardDocument = null;
            artworkDocument = null;
            artworkOverlay.clearDocument();
            loadedId = "";
        }

        updateArtworkTarget(nowMs);

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
        if (cardDocument == null || !id.equals(loadedId)) return; // not loaded for THIS track → stay cleared

        long pos = renderConfig.adjustedPositionMs(
                hook.readBestMeasuredProgressMs(track, hook.isPlayerActuallyPlaying()));
        if (artworkDocument != null) artworkOverlay.renderFrame(pos, deltaSeconds);

        // Unsynced lyrics: no line tracks playback, so the live card can't karaoke-follow —
        // show the interlude indicator (set once) and leave reading to the fullscreen screen.
        if (isUnsyncedDocument(cardDocument)) {
            if (!placeholderShown) { card.setInterlude(renderConfig.interludeNoteIcon); placeholderShown = true; }
            return;
        }

        List<AppliedLine> lines = cardDocument.appliedLines;
        if (lines == null || lines.isEmpty()) return;
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
                cardDocument,
                lineChanged);
    }

    private void handleCardTap() {
        refreshConfig();
        String mode = config.get(com.eza.spicyex.Settings.LIVE_CARD_TAP_MODE);
        if ("Off".equals(mode)) return;
        long now = SystemClock.uptimeMillis();
        if ("Single tap".equals(mode)) {
            openConfiguredTapTarget();
            return;
        }
        if (now - lastCardTapMs <= 340L) {
            lastCardTapMs = 0L;
            openConfiguredTapTarget();
        } else {
            lastCardTapMs = now;
        }
    }

    private void openConfiguredTapTarget() {
        String target = config.get(com.eza.spicyex.Settings.LIVE_CARD_TAP_TARGET);
        NowPlayingArtworkTargetResolver.Resolution resolution = artworkTargetHost.resolve(true);
        NowPlayingArtworkTargetResolver.OpenAction action =
                NowPlayingArtworkTargetResolver.openAction(target, resolution);
        if (action == NowPlayingArtworkTargetResolver.OpenAction.ARTWORK) {
            clearPendingCanvasTransfer();
            lastStableArtworkTarget = NowPlayingArtworkTargetResolver.Kind.COVER;
            artworkUnavailableGraceArmed = false;
            artworkUnavailableRetriesRemaining = 0;
            artworkOverlay.setDocument(artworkDocument, renderConfig);
            artworkOverlay.showOverlay();
            artworkBackLifecycle.start();
        } else {
            hook.launchNativeLyricsFullscreen(activity);
        }
    }

    private void closeArtwork() {
        closeArtwork(true);
    }

    private void closeArtwork(boolean clearPendingTransfer) {
        artworkBackLifecycle.stop();
        artworkOverlay.hideOverlay();
        artworkUnavailableGraceArmed = false;
        artworkUnavailableRetriesRemaining = 0;
        if (clearPendingTransfer) clearPendingCanvasTransfer();
    }

    private void expandArtwork() {
        closeArtwork();
        hook.launchNativeLyricsFullscreen(activity);
    }

    private void onArtworkTargetInvalidated(NowPlayingArtworkTargetResolver.Kind kind) {
        if (!artworkOverlay.isOverlayVisible()) return;
        if (kind == NowPlayingArtworkTargetResolver.Kind.CANVAS) {
            armPendingCanvasTransfer();
            closeArtwork(false);
            return;
        }
        closeArtwork();
    }

    private void updateArtworkTarget(long nowMs) {
        if (!artworkOverlay.isOverlayVisible() || nowMs - lastArtworkTargetCheckMs < 250L) return;
        lastArtworkTargetCheckMs = nowMs;
        boolean boundedRefreshPending = artworkTargetRefreshesRemaining > 0;
        if (boundedRefreshPending) {
            artworkTargetHost.invalidate();
            artworkTargetRefreshesRemaining--;
        }
        NowPlayingArtworkTargetResolver.Resolution resolution = artworkTargetHost.resolve(true);
        NowPlayingArtworkTargetResolver.UnavailableGrace grace =
                NowPlayingArtworkTargetResolver.unavailableGrace(
                        lastStableArtworkTarget,
                        resolution.kind,
                        artworkUnavailableGraceArmed,
                        artworkUnavailableRetriesRemaining);
        artworkUnavailableGraceArmed = grace.armed;
        artworkUnavailableRetriesRemaining = grace.retriesRemaining;
        if (grace.defer) {
            artworkTargetHost.invalidate();
            return;
        }
        NowPlayingArtworkTargetResolver.TrackChangeAction action =
                NowPlayingArtworkTargetResolver.trackChangeAction(
                        true,
                        true,
                        lastStableArtworkTarget,
                        resolution.kind);
        if (resolution.kind == NowPlayingArtworkTargetResolver.Kind.COVER
                || resolution.kind == NowPlayingArtworkTargetResolver.Kind.CANVAS) {
            NowPlayingArtworkTargetResolver.Kind previous = lastStableArtworkTarget;
            if (action == NowPlayingArtworkTargetResolver.TrackChangeAction.TRANSFER_FULLSCREEN
                    && previous == NowPlayingArtworkTargetResolver.Kind.COVER) {
                armPendingCanvasTransfer();
                closeArtwork(false);
                lastStableArtworkTarget = NowPlayingArtworkTargetResolver.Kind.CANVAS;
                return;
            }
            lastStableArtworkTarget = resolution.kind;
        }
        if (action == NowPlayingArtworkTargetResolver.TrackChangeAction.CLOSE_ARTWORK) closeArtwork();
    }

    private void armPendingCanvasTransfer() {
        if (pendingCanvasTransfer) return;
        pendingCanvasTransfer = true;
        pendingCanvasTrackId = currentId;
        pendingCanvasAtMs = SystemClock.uptimeMillis();
    }

    private void evaluatePendingCanvasTransfer(String nextTrackId, long nowMs) {
        if (!pendingCanvasTransfer) return;
        boolean trackChanged = !pendingCanvasTrackId.equals(nextTrackId);
        NowPlayingArtworkTargetResolver.Kind target = NowPlayingArtworkTargetResolver.Kind.NONE;
        if (!trackChanged && nowMs - pendingCanvasAtMs >= CANVAS_TRANSFER_SETTLE_MS) {
            target = artworkTargetHost.resolve(false).kind;
        }
        NowPlayingArtworkTargetResolver.PendingCanvasAction action =
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, pendingCanvasTrackId, nextTrackId,
                        config.get(com.eza.spicyex.Settings.STAY_IN_LYRICS),
                        Math.max(0L, nowMs - pendingCanvasAtMs),
                        CANVAS_TRANSFER_SETTLE_MS, target);
        if (action == NowPlayingArtworkTargetResolver.PendingCanvasAction.WAIT) return;
        clearPendingCanvasTransfer();
        if (action == NowPlayingArtworkTargetResolver.PendingCanvasAction.LAUNCH_FULLSCREEN) {
            hook.launchNativeLyricsFullscreen(activity);
        }
    }

    private void clearPendingCanvasTransfer() {
        pendingCanvasTransfer = false;
        pendingCanvasTrackId = "";
        pendingCanvasAtMs = 0L;
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
        if (lyricRequest != null) lyricRequest.close();
        loadingId = id;
        final int gen = ++fetchGen;
        try {
        lyricRequest = hook.fetchLyrics(track, new NativeSpicyLyricsHook.LyricsResultCallback() {
            @Override
            public void onSuccess(LyricsDocument doc) {
                // SessionSubscription owns document delivery, including later processing upgrades.
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

    private void acceptSessionDocument(LyricsSessionManager.Snapshot snapshot, LyricsDocument doc) {
        String id = NativeLyricsUtils.trackIdFromUri(snapshot.trackUri);
        SpotifyTrack liveTrack = hook.getCurrentTrackSafely();
        String liveId = liveTrack == null ? "" : NativeLyricsUtils.trackIdFromUri(liveTrack.uri);
        if (!NowPlayingSessionGuard.matchesCurrentTrack(id, liveId, currentId)) return;
        int generation = snapshot.generation;
        long revision = projectionRevision.incrementAndGet();
        LyricsRenderConfig fetchConfig = renderConfig;
        RomanizationOptions fetchOptions = romanizationOptions();
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                if (isProjectionStale(id, generation, revision)) return;
                LyricsDocument nextCardDocument = doc;
                LyricsDocumentProcessor.applyProcessedCache(activity.getApplicationContext(), nextCardDocument,
                        fetchOptions, NativeRuntime.GOOGLE_PROCESSING_VERSION);
                if (isProjectionStale(id, generation, revision)) return;
                // Same as fullscreen: the composed document already carries span readings from the
                // session's Sound artifact, so only derive when it does not.
                if (!LyricsDocumentProcessor.hasSpanReadings(nextCardDocument)) {
                    prepareSurfaceRomanization(nextCardDocument, fetchConfig, fetchOptions,
                            fetchConfig != null && (fetchConfig.transliterationEnabled
                                    || fetchConfig.liveCardShowTransliteration));
                }
                if (isProjectionStale(id, generation, revision)) return;
                // A derived-layer completion republishes the whole document over an unchanged
                // canonical base. Absorb it into the documents already mounted instead of building
                // two fresh ones and replanning every row: this surface publishes several times per
                // track as the lanes settle, and the artwork overlay keeps its document identity.
                LyricsDocument mountedCard = cardDocument;
                LyricsDocument mountedArtwork = artworkDocument;
                if (id.equals(loadedId) && mountedCard != null && mountedArtwork != null
                        && LyricsDocumentProcessor.sameCanonicalBase(mountedCard, nextCardDocument)) {
                    boolean cardChanged = LyricsDocumentProcessor.mergeDerivedLayers(mountedCard, nextCardDocument);
                    boolean artworkChanged =
                            LyricsDocumentProcessor.mergeDerivedLayers(mountedArtwork, nextCardDocument);
                    boolean rowsChanged = LyricTimeline.refreshAppliedDerivedText(mountedCard);
                    rowsChanged |= LyricTimeline.refreshAppliedDerivedText(mountedArtwork);
                    if (cardChanged || artworkChanged || rowsChanged) {
                        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LAYER_LOCAL_UPDATE);
                        handler.post(() -> {
                            if (isProjectionStale(id, generation, revision)) return;
                            lastIdx = Integer.MIN_VALUE;
                        });
                    }
                    return;
                }
                LyricsDocument nextArtworkDocument = LyricsDocument.copyOf(nextCardDocument);
                if (nextArtworkDocument == null) return;
                if (isProjectionStale(id, generation, revision)) return;
                LyricTimeline.applySyncedRows(nextCardDocument);
                if (isProjectionStale(id, generation, revision)) return;
                LyricTimeline.applySyncedRows(nextArtworkDocument);
                if (isProjectionStale(id, generation, revision)) return;
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DOCUMENT_REBUILD);
                handler.post(() -> commitProjectedDocument(
                        id, generation, revision, nextCardDocument, nextArtworkDocument));
            } catch (Throwable t) {
                handler.post(() -> {
                    if (!running || projectionRevision.get() != revision) return;
                    loadingId = "";
                    nextFetchAllowedMs = System.nanoTime() / 1_000_000L + 5000L;
                });
            }
        });
    }

    private boolean isProjectionStale(String id, int generation, long revision) {
        return NowPlayingSessionGuard.projectionIsStale(
                running, revision, projectionRevision.get(), generation, observedGeneration,
                id, currentId);
    }

    private void commitProjectedDocument(
            String id,
            int generation,
            long revision,
            LyricsDocument nextCardDocument,
            LyricsDocument nextArtworkDocument
    ) {
        if (!running || revision != projectionRevision.get() || generation != observedGeneration) return;
        SpotifyTrack currentTrack = hook.getCurrentTrackSafely();
        String currentTrackId = currentTrack == null ? ""
                : NativeLyricsUtils.trackIdFromUri(currentTrack.uri);
        if (!NowPlayingSessionGuard.matchesCurrentTrack(id, currentTrackId, currentId)) return;
        loadingId = "";
        refreshConfig();
        if (nextCardDocument.appliedLines == null || nextCardDocument.appliedLines.isEmpty()) {
            failedId = id;
            return;
        }
        currentId = id;
        failedId = "";
        cardDocument = nextCardDocument;
        artworkDocument = nextArtworkDocument;
        artworkOverlay.setDocument(artworkDocument, renderConfig);
        loadedId = id;
        lastIdx = Integer.MIN_VALUE;
    }

    private SpotifyTrack currentTrackThrottled(long nowMs) {
        if (!throttledTrackInitialized || nowMs - throttledTrackAtMs >= 250L) {
            throttledTrack = hook.getCurrentTrackSafely();
            throttledTrackAtMs = nowMs;
            throttledTrackInitialized = true;
        }
        return throttledTrack;
    }

    private void prepareSurfaceRomanization(LyricsDocument doc, LyricsRenderConfig config,
                                             RomanizationOptions opts, boolean enabled) {
        if (config == null || !enabled || doc == null || doc.lines == null) return;
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
