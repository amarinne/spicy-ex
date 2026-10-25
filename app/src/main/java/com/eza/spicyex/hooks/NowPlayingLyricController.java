package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.ArtworkLyricsOverlayView;
import com.eza.spicyex.lyrics.LiveLyricCardView;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.providers.LyricsFetchErrors;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.language.LyricsLocalRomanizer;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsRenderMode;
import com.eza.spicyex.lyrics.LyricsRowViewFactory;
import com.eza.spicyex.lyrics.ResponseCreditOutro;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.LyricsShellLifecycle;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.language.SpicyTextDetection;
import com.eza.spicyex.xposed.XpLog;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the {@link LiveLyricCardView} that replaces Spotify's now-playing lyric snippet. Runs a
 * vsync loop (so the current line inherits the fullscreen engine's karaoke wash): polls position via
 * the hook, fetches lyrics on track change, swaps the 3 lines on active-line change, and washes the
 * current line's gradient each frame. Paused on activity pause, stopped on destroy.
 *
 * <p>The loop is demand-gated rather than free-running. A frame is drawn only while the player
 * advances and a mounted surface still projects something new: the card or the expanded artwork
 * overlay is on screen with a timed document that has not run past its last sung row, or a canvas
 * handoff is settling. Otherwise the loop drops to one re-check per
 * {@link #IDLE_FRAME_PROBE_MS}; that probe is what resumes it after a pause, a re-attach, or any
 * change that arrives without a callback.
 */
final class NowPlayingLyricController {
    private static final long CANVAS_TRANSFER_SETTLE_MS = 400L;
    /**
     * Idle re-check cadence. This controller has no playback-state callback, so an idle loop still
     * needs to look once in a while to notice that playback started. It is already faster than the
     * throttles the frame pass itself uses for the track query (400 ms) and the artwork target
     * check (250 ms), so nothing that was throttled gets slower.
     */
    private static final long IDLE_FRAME_PROBE_MS = 250L;
    /** No rendered position in the current frame pass. */
    static final long NO_POSITION_MS = Long.MIN_VALUE;
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
    private ChineseCharacterForm characterForm = ChineseCharacterForm.ORIGINAL;

    private LyricsDocument cardDocument;
    private LyricsDocument artworkDocument;
    private volatile String currentId = "";   // track currently on screen
    private String loadedId = "";
    private String loadingId = "";
    private String failedId = "";    // track known to have no lyrics — don't show stale / re-fetch
    private boolean placeholderShown;
    private int fetchGen;
    private int lastIdx = Integer.MIN_VALUE;
    private AppliedLine miniProjectedLine;
    private AppliedLine miniProjectedSource;
    private String miniProjectedBackgroundKey = "";
    private long lastTrackCheckMs;
    private long lastCardTapMs;
    private long lastFrameMs;
    /** A vsync callback is queued. */
    private boolean framePosted;
    /** The last demand decision: true while frames are actually needed. */
    private boolean frameLoopContinuous;
    /** Playback state read once per frame pass; reused by the demand decision. */
    private boolean framePlaying;
    /** Position rendered by the current frame pass, or {@link #NO_POSITION_MS} before it. */
    private long framePositionMs = NO_POSITION_MS;
    /** Mounted document {@link #sungEndMs} was derived from, and that end. */
    private LyricsDocument sungEndDocument;
    private long sungEndMs;
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
    /** First publication after an activity resume replaces stale surface state in full. */
    private volatile boolean authoritativeReplayRequired;
    private final AtomicLong projectionRevision = new AtomicLong();
    private NowPlayingArtworkTargetResolver.Kind lastStableArtworkTarget =
            NowPlayingArtworkTargetResolver.Kind.NONE;
    private final LyricsSessionManager.Listener sessionListener = new LyricsSessionManager.Listener() {
        @Override public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
            if (!running || snapshot == null) return;
            if (snapshot.generation != observedGeneration) observedGeneration = snapshot.generation;
            if ("no_lyrics".equals(snapshot.status) && cardDocument == null) {
                String id = NativeLyricsUtils.trackIdFromUri(snapshot.trackUri);
                if (id.equals(currentId)) {
                    loadingId = "";
                    failedId = id;
                }
            }
            requestFrame();
        }

        @Override public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                                LyricsDocument nextDocument) {
            if (!running || snapshot == null || snapshot.track == null) return;
            if (nextDocument == null) {
                retireSessionDocument(snapshot);
                return;
            }
            acceptSessionDocument(snapshot, nextDocument);
        }
    };

    private void retireSessionDocument(LyricsSessionManager.Snapshot snapshot) {
        String id = NativeLyricsUtils.trackIdFromUri(snapshot.trackUri);
        if (!id.equals(currentId)) return;
        projectionRevision.incrementAndGet();
        loadedId = "";
        cardDocument = null;
        artworkDocument = null;
        card.clear();
        artworkOverlay.clearDocument();
        lastIdx = Integer.MIN_VALUE;
        clearMiniProjection();
        placeholderShown = false;
        failedId = "no_lyrics".equals(snapshot.status) ? id : "";
        requestFrame();
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (!running) return;
            try {
                onFrame(frameTimeNanos / 1_000_000L);
            } catch (Throwable t) {
                if (!frameErrorLogged) {
                    frameErrorLogged = true;
                    XpLog.log(NativeSpicyLyricsHook.TAG + " live card frame error: " + android.util.Log.getStackTraceString(t));
                }
            }
            // Queued or dropped from here: the pass above is what produced the state this reads.
            updateFrameDemand();
        }
    };

    /**
     * The idle loop. It runs only while no frames are queued, and re-arms itself so a missed
     * transition cannot strand the card: playback start, view re-attach, and a deferred handoff all
     * arrive through here. It is removed as soon as demand opens again.
     */
    private final Runnable idleFrameProbe = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            requestFrame();
            handler.postDelayed(this, IDLE_FRAME_PROBE_MS);
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
        this.preferences = activity.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
        refreshConfig();
        this.card.setOnClickListener(v -> handleCardTap());
        this.artworkOverlay.setActions(this::closeArtwork, this::expandArtwork);
    }

    private boolean refreshConfig() {
        LyricsRenderConfig next = LyricsRenderConfig.read(activity, config);
        ChineseCharacterForm nextCharacterForm = currentCharacterForm();
        boolean characterFormChanged = characterForm != nextCharacterForm;
        characterForm = nextCharacterForm;
        // F11: one shared definition of "what changed". The hand-rolled comparison drifted
        // and missed live-card size, Apple, blur, and bounce-scope fields, leaving mounted
        // content stale until an unrelated change.
        boolean changed = renderConfig == null || renderConfig.diff(next).hasChanges;
        renderConfig = next;
        if (changed) {
            card.applyConfig(renderConfig);
            artworkOverlay.applyConfig(renderConfig);
        }
        if (characterFormChanged) {
            if (applyCharacterForm(cardDocument, characterForm)) card.invalidateMountedContent();
            if (applyCharacterForm(artworkDocument, characterForm)) artworkOverlay.invalidateMountedContent();
            lastIdx = Integer.MIN_VALUE;
            clearMiniProjection();
        }
        return changed || characterFormChanged;
    }

    private ChineseCharacterForm currentCharacterForm() {
        return ChineseCharacterForm.fromSetting(config.get(Settings.CHINESE_CHARACTER_FORM));
    }

    static boolean applyCharacterForm(LyricsDocument document, ChineseCharacterForm form) {
        return LyricTimeline.setChineseCharacterForm(document, form);
    }

    void start() {
        if (running) return;
        running = true;
        // Fullscreen pauses this controller but leaves the injected card mounted. Spotify can
        // detach/rebind parts of the Now Playing hierarchy while the fullscreen activity owns the
        // window, and preference changes made there arrive while our listener is unregistered.
        // Rebuild the retained row from the shared document on resume so secondary text cannot
        // disappear merely because the active line index stayed unchanged across the handoff.
        refreshConfig();
        card.invalidateMountedContent();
        lastFrameMs = 0L;
        throttledTrackInitialized = false;
        authoritativeReplayRequired = true;
        synchronizeTrackBeforeSubscription();
        registerPreferenceListener();
        sessionSubscription = hook.subscribeLyricsSession(sessionListener);
        frameLoopContinuous = false;
        requestFrame();
    }

    /** Asks for one frame; a no-op while one is already queued or the controller is stopped. */
    private void requestFrame() {
        if (!running || framePosted) return;
        // Only an idle loop restarts the delta clock. Resetting it from inside a running loop
        // would report a 0 delta and advance every spring by a fixed 1/60 s per real frame.
        if (!frameLoopContinuous) lastFrameMs = 0L;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void cancelPendingFrame() {
        if (!framePosted) return;
        framePosted = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    /** Keeps the loop open while the pass has work, and parks it on the idle probe when it does not. */
    private void updateFrameDemand() {
        if (!running) return;
        boolean cardShown = card.isShown();
        boolean artworkShown = artworkOverlay.isShown();
        boolean open = frameDemandOpen(
                pendingCanvasTransfer || (artworkShown && artworkTargetRefreshesRemaining > 0),
                framePlaying,
                cardShown || artworkShown,
                timedSurfaceLive(cardShown, artworkShown),
                framePositionMs,
                sungEndMs());
        if (!open) {
            if (frameLoopContinuous) {
                frameLoopContinuous = false;
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LIVE_CARD_FRAME_LOOP_IDLED);
                NativeSpicyLyricsHook.dbg("frameDemand", "live card frame loop idle");
            }
            // A pass can request a frame before it decides the work is done (a tap, a target
            // invalidation, a track change). The gate is the authority: drop it.
            cancelPendingFrame();
            handler.removeCallbacks(idleFrameProbe);
            handler.postDelayed(idleFrameProbe, IDLE_FRAME_PROBE_MS);
            return;
        }
        if (!frameLoopContinuous) {
            frameLoopContinuous = true;
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LIVE_CARD_FRAME_LOOP_ENTERED);
            NativeSpicyLyricsHook.dbg("frameDemand", "live card frame loop continuous");
        }
        handler.removeCallbacks(idleFrameProbe);
        requestFrame();
    }

    /**
     * The one rule for "the pass that just ran needs another frame". Callers only request a frame
     * on a state change; this decides whether the loop stays open afterwards.
     *
     * <p>Playback has to be advancing first: when it is not, the hook stops extrapolating the
     * position, so a repeated frame redraws the same pixels. The projection is unsettled while the
     * playhead is still inside the document's sung span — the wash, the line sync fill and the
     * overflow glide all read the position every frame there — and until the credit-outro hold
     * window opens, because that is where the last row is cleared. Past it the card holds a settled
     * frame: the outro itself is static text on a wall-clock window, so the idle probe presents and
     * retires it.
     *
     * @param forcedWork a canvas handoff settling, or a bounded artwork target refresh burst
     * @param playing    playback is advancing
     * @param surfaceShown the card or the artwork overlay is attached and visible
     * @param timedSurface that on-screen surface has a document whose rows carry timing
     * @param positionMs the position the last pass rendered, or {@link #NO_POSITION_MS}
     * @param sungEndMs  end of the last sung row of the mounted document
     */
    static boolean frameDemandOpen(
            boolean forcedWork,
            boolean playing,
            boolean surfaceShown,
            boolean timedSurface,
            long positionMs,
            long sungEndMs
    ) {
        if (forcedWork) return true;
        if (!playing || !surfaceShown) return false;
        if (!timedSurface) return false;
        if (positionMs == NO_POSITION_MS || sungEndMs <= 0L) return false;
        return positionMs < sungEndMs + ResponseCreditOutro.HOLD_MS;
    }

    private boolean timedSurfaceLive(boolean cardShown, boolean artworkShown) {
        if (cardShown && cardDocument != null && !isUnsyncedDocument(cardDocument)) return true;
        return artworkShown && artworkDocument != null;
    }

    /**
     * End of the last sung row, cached per mounted document. Both the credit-outro clock and the
     * frame gate need it, and deriving it walks every row.
     */
    private long sungEndMs() {
        LyricsDocument document = cardDocument != null ? cardDocument : artworkDocument;
        if (document == sungEndDocument) return sungEndMs;
        sungEndDocument = document;
        sungEndMs = ResponseCreditOutro.lastVocalEndMs(document);
        return sungEndMs;
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
        clearMiniProjection();
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
        frameLoopContinuous = false;
        cancelPendingFrame();
        handler.removeCallbacks(idleFrameProbe);
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
        framePositionMs = NO_POSITION_MS;
        framePlaying = hook.isPlayerActuallyPlaying();
        SpotifyTrack track = currentTrackThrottled(nowMs);
        if (track == null) return;
        String id = NativeLyricsUtils.trackIdFromUri(track.uri);

        evaluatePendingCanvasTransfer(id, nowMs);

        // On track change, drop the previous song's line immediately so a no-lyric next song can't
        // show a stale lyric while (or instead of) loading.
        if (!id.equals(currentId)) {
            boolean artworkWasVisible = artworkOverlay.isOverlayVisible();
            if (artworkWasVisible && !LyricsShellChromeConfig.read(config).stayInLyrics) {
                closeArtwork();
            }
            currentId = id;
            projectionRevision.incrementAndGet();
            artworkTargetHost.invalidate();
            artworkTargetRefreshesRemaining = artworkWasVisible ? 3 : 0;
            card.clearAnimated();
            lastIdx = Integer.MIN_VALUE;
            clearMiniProjection();
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
                hook.readBestMeasuredProgressMs(track, framePlaying));
        framePositionMs = pos;
        if (artworkDocument != null) artworkOverlay.renderFrame(pos, deltaSeconds);
        if (card.renderCreditOutro(cardDocument, pos)) {
            lastIdx = Integer.MIN_VALUE;
            return;
        }

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
            if (lastIdx != -1) { card.clearAnimated(); lastIdx = -1; }
            return;
        }
        AppliedLine cur = lines.get(idx);
        AppliedLine miniLine = projectMiniLine(cur, pos);
        boolean lineChanged = idx != lastIdx;
        if (idx != lastIdx) {
            lastIdx = idx;
        }
        card.renderLine(activity, miniLine, renderConfig, pos, deltaSeconds,
                cardDocument,
                this::segmentRomanizedText,
                lineChanged);
    }

    /**
     * The fullscreen surface mounts lead and background rows separately. The compact card has
     * room for one row, so keep any simultaneously active parenthetical/background lyric in that
     * row instead of silently dropping it. The projection deliberately has no word list: its
     * karaoke fill is therefore driven by the projected line's own start/end window.
     */
    private AppliedLine projectMiniLine(AppliedLine lead, long positionMs) {
        if (lead == null || lead.sourceLine == null) return lead;
        StringBuilder text = new StringBuilder(lead.text == null ? "" : lead.text);
        StringBuilder romanized = new StringBuilder(LyricsRowViewFactory.displayReading(lead));
        StringBuilder translated = new StringBuilder(lead.translatedText == null ? "" : lead.translatedText);
        StringBuilder key = new StringBuilder();
        for (AppliedLine background : activeBackgroundRows(cardDocument, lead, positionMs)) {
            if (key.length() > 0) key.append('');
            key.append(background.startMs).append(':').append(background.endMs).append(':').append(background.text);
            appendMiniPart(text, background.text);
            appendMiniPart(romanized, LyricsRowViewFactory.displayReading(background));
            appendMiniPart(translated, background.translatedText);
        }
        String backgroundKey = key.toString();
        if (backgroundKey.isEmpty()) return lead;
        if (miniProjectedSource == lead && backgroundKey.equals(miniProjectedBackgroundKey)) {
            return miniProjectedLine;
        }
        AppliedLine projected = new AppliedLine();
        projected.text = text.toString();
        projected.romanizedText = romanized.toString();
        projected.translatedText = translated.toString();
        projected.startMs = lead.startMs;
        projected.endMs = Math.max(projected.startMs + 1, lead.endMs);
        projected.totalMs = projected.endMs - projected.startMs;
        projected.oppositeAligned = lead.oppositeAligned;
        miniProjectedSource = lead;
        miniProjectedBackgroundKey = backgroundKey;
        miniProjectedLine = projected;
        return projected;
    }

    static List<AppliedLine> activeBackgroundRows(LyricsDocument document, AppliedLine lead, long positionMs) {
        java.util.ArrayList<AppliedLine> rows = new java.util.ArrayList<>();
        if (document == null || lead == null || lead.sourceLine == null) return rows;
        for (AppliedLine row : document.appliedLines) {
            if (row != null && row.bgLine && row.sourceLine == lead.sourceLine
                    && LyricTimeline.isRowActiveAt(row, positionMs) && !isBlank(row.text)) rows.add(row);
        }
        return rows;
    }

    private static void appendMiniPart(StringBuilder target, String value) {
        if (isBlank(value)) return;
        if (target.length() > 0) target.append('\n');
        target.append(value);
    }

    private void clearMiniProjection() {
        miniProjectedLine = null;
        miniProjectedSource = null;
        miniProjectedBackgroundKey = "";
    }

    private void handleCardTap() {
        refreshConfig();
        requestFrame();
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
            requestFrame();
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
        requestFrame();
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
        requestFrame();
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
                        LyricsShellChromeConfig.read(config).stayInLyrics,
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
        if (!running) return;
        if (refreshConfig()) lastIdx = Integer.MIN_VALUE;
        requestFrame();
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
        final boolean replayRequired = authoritativeReplayRequired;
        LyricsRenderConfig fetchConfig = renderConfig;
        RomanizationOptions fetchOptions = romanizationOptions();
        ChineseCharacterForm fetchCharacterForm = currentCharacterForm();
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                if (isProjectionStale(id, generation, revision)) return;
                // Detached: the worker must never mutate the session's document or the mounted
                // documents. Both are read on the main thread while this runs.
                LyricsDocument nextCardDocument = LyricsDocument.copyOf(doc);
                if (nextCardDocument == null) return;
                nextCardDocument.chineseCharacterForm = fetchCharacterForm;
                LyricsDocumentProcessor.applyProcessedCachePreservingAi(
                        activity.getApplicationContext(), nextCardDocument,
                        fetchOptions, NativeRuntime.GOOGLE_PROCESSING_VERSION);
                if (isProjectionStale(id, generation, revision)) return;
                // Same as fullscreen: the composed document already carries span readings from the
                // session's Sound artifact, so only derive when it does not.
                if (LyricsDocumentProcessor.needsSurfaceLocalRomanization(nextCardDocument)) {
                    prepareSurfaceRomanization(nextCardDocument, fetchConfig, fetchOptions,
                            fetchConfig != null && (fetchConfig.transliterationEnabled
                                    || fetchConfig.liveCardShowTransliteration));
                }
                if (isProjectionStale(id, generation, revision)) return;
                LyricsDocument nextArtworkDocument = LyricsDocument.copyOf(nextCardDocument);
                if (nextArtworkDocument == null) return;
                if (isProjectionStale(id, generation, revision)) return;
                LyricTimeline.applySyncedRows(nextCardDocument);
                if (isProjectionStale(id, generation, revision)) return;
                LyricTimeline.applySyncedRows(nextArtworkDocument);
                if (isProjectionStale(id, generation, revision)) return;
                // The guarded merge, applied-row refresh, and invalidation run together on the
                // main thread, like fullscreen's commitAndRenderDocument: a worker that passed
                // an earlier guard can no longer overwrite a newer publication and then discard
                // its own invalidation as stale.
                handler.post(() -> {
                    if (isProjectionStale(id, generation, revision)) return;
                    ChineseCharacterForm currentForm = currentCharacterForm();
                    applyCharacterForm(nextCardDocument, currentForm);
                    applyCharacterForm(nextArtworkDocument, currentForm);
                    if (tryMergeMountedProjection(id, nextCardDocument, replayRequired)) return;
                    LyricPipelineMetrics.increment(
                            LyricPipelineMetrics.Counter.DOCUMENT_REBUILD);
                    commitProjectedDocument(id, generation, revision, nextCardDocument,
                            nextArtworkDocument, replayRequired);
                });
            } catch (Throwable t) {
                handler.post(() -> {
                    if (!running || projectionRevision.get() != revision) return;
                    loadingId = "";
                    nextFetchAllowedMs = System.nanoTime() / 1_000_000L + 5000L;
                });
            }
        });
    }

    /**
     * F5: absorbs a prepared publication into the mounted documents. Main thread only: the
     * caller holds a fresh staleness check, so the merge, row refresh, and invalidation land
     * as one atomic step that no racing worker can interleave.
     *
     * @return true when the mounted documents absorbed the publication and no replacement
     *         is required.
     */
    private boolean tryMergeMountedProjection(String id, LyricsDocument nextCardDocument,
                                              boolean replayRequired) {
        // A derived-layer completion republishes the whole document over an unchanged
        // canonical base. Absorb it into the documents already mounted instead of building
        // two fresh ones and replanning every row: this surface publishes several times per
        // track as the lanes settle, and the artwork overlay keeps its document identity.
        LyricsDocument mountedCard = cardDocument;
        LyricsDocument mountedArtwork = artworkDocument;
        if ((mountedCard != null && mountedCard.chineseCharacterForm != nextCardDocument.chineseCharacterForm)
                || (mountedArtwork != null
                && mountedArtwork.chineseCharacterForm != nextCardDocument.chineseCharacterForm)) {
            return false;
        }
        LyricsDocumentProcessor.DerivedMergeResult cardMerge =
                NowPlayingSessionGuard.mayMergeMountedProjection(
                        replayRequired,
                        id.equals(loadedId),
                        mountedCard != null,
                        mountedArtwork != null)
                        ? LyricsDocumentProcessor.mergeDerivedPublication(mountedCard,
                                nextCardDocument)
                        : LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE;
        if (cardMerge == LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE) {
            return false;
        }
        boolean cardChanged = cardMerge == LyricsDocumentProcessor.DerivedMergeResult.CHANGED;
        boolean artworkChanged =
                LyricsDocumentProcessor.mergeDerivedPublication(mountedArtwork, nextCardDocument)
                        == LyricsDocumentProcessor.DerivedMergeResult.CHANGED;
        boolean cardProjectionChanged = LyricTimeline.refreshChineseCharacterProjection(mountedCard);
        boolean artworkProjectionChanged = LyricTimeline.refreshChineseCharacterProjection(mountedArtwork);
        boolean cardRowsChanged = LyricTimeline.refreshAppliedDerivedText(mountedCard) || cardProjectionChanged;
        boolean artworkRowsChanged =
                LyricTimeline.refreshAppliedDerivedText(mountedArtwork) || artworkProjectionChanged;
        if (cardChanged || artworkChanged || cardRowsChanged || artworkRowsChanged) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LAYER_LOCAL_UPDATE);
            if (cardChanged || cardRowsChanged) card.invalidateMountedContent();
            if (artworkChanged || artworkRowsChanged) {
                artworkOverlay.invalidateMountedContent();
            }
            lastIdx = Integer.MIN_VALUE;
            clearMiniProjection();
        }
        if (cardChanged || artworkChanged || cardRowsChanged || artworkRowsChanged) requestFrame();
        return true;
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
            LyricsDocument nextArtworkDocument,
            boolean authoritativeReplay
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
            requestFrame(); // renders the placeholder
            return;
        }
        currentId = id;
        failedId = "";
        cardDocument = nextCardDocument;
        artworkDocument = nextArtworkDocument;
        if (authoritativeReplay) authoritativeReplayRequired = false;
        artworkOverlay.setDocument(artworkDocument, renderConfig);
        loadedId = id;
        lastIdx = Integer.MIN_VALUE;
        clearMiniProjection();
        requestFrame();
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

    private String segmentRomanizedText(AppliedLine line,
                                        com.eza.spicyex.lyrics.SyllableSegment segment,
                                        String fullText) {
        // LyricsRowViewFactory calls this only after no timed reading unit matched this segment.
        // Synthetic sentence words do not share the source plan's span IDs, so the plan can exist
        // while this exact display segment still needs the local per-segment fallback.
        return LyricsLocalRomanizer.romanizeDisplaySegment(
                romanizationOptions(), cardDocument, line, segment, fullText);
    }

    private boolean isUnsyncedDocument(LyricsDocument doc) {
        return LyricsRenderMode.isStatic(doc);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
