package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.emptyFallback;
import static com.eza.spicyex.hooks.NativeLyricsUtils.formatMs;
import static com.eza.spicyex.hooks.NativeLyricsUtils.hasJapaneseReading;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;
import static com.eza.spicyex.hooks.NativeLyricsUtils.setTextIfChanged;
import static com.eza.spicyex.hooks.NativeLyricsUtils.shortTrackId;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sourceProviderLabel;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.trackIdFromUri;
import static com.eza.spicyex.hooks.NativeRuntime.AI_WORKERS;
import static com.eza.spicyex.hooks.NativeRuntime.GOOGLE_PROCESSING_VERSION;
import static com.eza.spicyex.hooks.NativeRuntime.HTTP;
import static com.eza.spicyex.hooks.NativeRuntime.LYRIC_ESTIMATED_ROW_HEIGHT_DP;
import static com.eza.spicyex.hooks.NativeRuntime.LYRIC_FULL_RENDER_THRESHOLD;
import static com.eza.spicyex.hooks.NativeRuntime.LYRIC_WINDOW_AFTER_ACTIVE;
import static com.eza.spicyex.hooks.NativeRuntime.LYRIC_WINDOW_BEFORE_ACTIVE;
import static com.eza.spicyex.hooks.NativeRuntime.MEANING_WORKERS;
import static com.eza.spicyex.hooks.NativeRuntime.SOUND_PROCESSOR;
import static com.eza.spicyex.hooks.NativeRuntime.SOUND_WORKERS;
import static com.eza.spicyex.hooks.NativeRuntime.SCROLL_SETTLE_REMEASURE_DELAY_MS;
import static com.eza.spicyex.hooks.NativeSpicyLyricsHook.TAG;
import static com.eza.spicyex.hooks.NativeSpicyLyricsHook.dbg;
import static com.eza.spicyex.hooks.NativeSpicyLyricsHook.dbgEnter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.CurrentLyricState;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.VsyncFrameScheduler;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.ArtGestureArbiter;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ChipSpinnerDrawable;
import com.eza.spicyex.lyrics.FrameStyleBatcher;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.lyrics.LyricCascadeProfile;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsAmbientController;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsFrameRenderer;
import com.eza.spicyex.lyrics.LyricsLineViewState;
import com.eza.spicyex.lyrics.LyricsLineVisualController;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsLocalRomanizer;
import com.eza.spicyex.lyrics.LyricsPlaybackClock;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsRenderMode;
import com.eza.spicyex.lyrics.LyricsLocalReprocessController;
import com.eza.spicyex.lyrics.LyricsRowMountController;
import com.eza.spicyex.lyrics.LyricsRowViewFactory;
import com.eza.spicyex.lyrics.LyricsScrollController;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.LyricsSecondaryRowUpdater;
import com.eza.spicyex.lyrics.LyricsShellLifecycle;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.LyricsShellSettings;
import com.eza.spicyex.lyrics.PanelMediaMode;
import com.eza.spicyex.lyrics.SkipGapPolicy;
import com.eza.spicyex.lyrics.LyricsSpaceView;
import com.eza.spicyex.lyrics.LyricsSurfaceRowPlanner;
import com.eza.spicyex.lyrics.LyricsTapSeekHandler;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.lyrics.LyricsToggleSpinnerController;
import com.eza.spicyex.lyrics.LyricsTransliterationSession;
import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.SpicyProcessing;
import com.eza.spicyex.lyrics.SpicyTextDetection;
import com.eza.spicyex.lyrics.SpotifyArtworkCache;
import com.eza.spicyex.lyrics.Spring;
import com.eza.spicyex.lyrics.SyllableSegment;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.eza.spicyex.xposed.XpLog;

import com.eza.spicyex.hooks.NativeSpicyLyricsHook.LyricsResultCallback;

final class NativeSpicyShellViewImpl extends FrameLayout {
    private final LyricsHost host;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable languageModelReadyListener =
            () -> handler.post(this::reprocessForInstalledLanguageModels);
    private final TextView title;
    private final TextView subtitle;
    /** Two-column panel album line (null in portrait, where the readout owns song info). */
    private final TextView albumLine;
    private final TextView progress;
    private final TextView status;
    private final ImageButton romanToggle;
    private final ImageButton translationToggle;
    private final ImageButton likeButton;
    private String likedMode;
    private Boolean lastLikedSaved;
    private com.eza.spicyex.ui.ActionIconDrawable.Kind lastLikedKind;
    private String pendingLikedUri = "";
    private final LyricsJumpToCurrentController jumpToCurrentController;
    private final LyricsSkipGapController skipGapController;
    /** Track URI + gap start the skip acknowledged; the gap must not re-fire while landing. */
    private String skipAckUri = "";
    private long skipAckGapStartMs = -1;
    private long lastSkipSeenPosMs = -1;
    /** Apple-owned row-scroll cascade (LINE_SLIDE_ANIMATION under Apple Music). */
    private boolean slideAnimationEnabled;
    /** Set at the one real "new document" assignment site; consumed (and cleared) the next time
     *  renderDocument() mounts that document's initial row window, so the LOAD_LIFT_ANIMATION
     *  reveal plays once per freshly loaded document, never on a same-document re-render (e.g. a
     *  preference change via rerenderKeepingPosition). */
    private boolean pendingLoadEntrance;
    /** Row the pending reveal staggers outward from - the row playback is really on, which is not
     *  necessarily row 0 (resuming, a late fetch, a source swap all open mid-song). */
    private int loadEntranceAnchor;
    private int loadEntranceAttempts;
    private static final int LOAD_ENTRANCE_MAX_ATTEMPTS = 20;
    private boolean applyingLyricScroll;
    /** ScrollView drives smoothScrollTo() from its own computeScroll() over the following frames,
     *  so those scroll callbacks can't be bracketed by applyingLyricScroll the way a direct
     *  scrollTo() is. This covers them for long enough to finish. */
    private long programmaticScrollUntilMs;
    private static final long SMOOTH_SCROLL_GUARD_MS = 500L;
    // Apple Music's line advance: one spring shared by every row, released row by row behind the
    // focused line. Damping 0.72 overshoots by ~4% of the move - a few pixels on a line advance -
    // so each row visibly settles into place rather than merely stopping.
    private static final float ROW_CASCADE_STAGGER_SEC = 0.045f;
    private static final float ROW_CASCADE_MAX_DELAY_SEC = 0.32f;
    private static final float ROW_CASCADE_FREQUENCY_HZ = 1.7f;
    private static final float ROW_CASCADE_DAMPING = 0.72f;
    private static final float ROW_CASCADE_MAX_OFFSET_PX = 900f;
    /** Slower and more heavily damped than the per-row cascade spring - this one is carrying the
     *  whole visible column, so a lively wobble that looks great on a single line would look like
     *  the screen itself overshooting. */
    private static final float SCROLL_SPRING_FREQUENCY_HZ = 1.3f;
    /** Used for a hop of roughly one row; blended toward the frequency above as the jump grows
     *  (see scrollSpringFrequency). A line-to-line advance has to keep up with the song. */
    private static final float SCROLL_SPRING_NEAR_FREQUENCY_HZ = 1.55f;
    private static final float SCROLL_SPRING_DAMPING = 0.9f;
    // Returning to the playing line after reading ahead. Livelier than an ordinary advance on
    // purpose: this one is a deliberate request, and Apple answers it with motion that clearly
    // travels rather than a polite ease. Lower damping leaves a touch of overshoot at the end.
    private static final float RETURN_SPRING_FREQUENCY_HZ = 1.30f;
    private static final float RETURN_SPRING_DAMPING = 0.90f;
    /** Launch speed given per pixel of distance, so a longer return leaves faster. */
    private static final float RETURN_LAUNCH_VELOCITY_PER_PX = 1.05f;
    private static final float RETURN_MAX_LAUNCH_VELOCITY_PX_PER_SEC = 2600f;
    /** Set while a return is being scheduled, consumed by scrollToActiveTarget. */
    private boolean returnToCurrentPending;
    private static final long ROW_CASCADE_MAX_LIFETIME_MS = 1600L;
    private final Map<AppliedLine, RowCascade> rowCascades = new WeakHashMap<>();
    /** Load reveal, driven off the same vsync tick as everything else rather than by a per-row
     *  ViewPropertyAnimator. The reveal and the renderer both have an opinion about a row's alpha,
     *  so the reveal publishes a 0..1 factor the renderer multiplies in (see
     *  {@link LyricsLineViewState#setEntranceProgress}) instead of writing View.alpha behind its
     *  back. Everything else it owns - the row's scale and its direct children's translation - is
     *  untouched by the renderer, so those compose with the Apple slide's own row translation. */
    private final Map<AppliedLine, LoadEntrance> loadEntrances = new WeakHashMap<>();

    private static final class LoadEntrance {
        final float travelPx;
        /** Time scale: 1 at 100% cascade speed, smaller is faster. */
        final float timeScale;
        float delayRemaining;
        float elapsed;

        LoadEntrance(float travelPx, float delaySeconds, float timeScale) {
            this.travelPx = travelPx;
            this.timeScale = Math.max(0.05f, timeScale);
            this.delayRemaining = Math.max(0f, delaySeconds);
        }
    }

    // Load reveal: each row rises into place on an underdamped spring (a soft landing with a hint
    // of settle, like the line slide) while it fades in and pulls into focus from a blur. It does
    // not scale: growing from small reads as a zoom, not as the lyrics arriving. The fade
    // and blur finish well before the motion does, so the text is legible while it is still
    // arriving instead of the whole reveal reading as one long dissolve.
    private static final float LOAD_REVEAL_FREQUENCY_HZ = 1.25f;
    private static final float LOAD_REVEAL_DAMPING = 0.78f;
    private static final float LOAD_REVEAL_DURATION_SEC = 1.05f;
    private static final float LOAD_REVEAL_FADE_SEC = 0.5f;
    private static final int LOAD_REVEAL_BLUR_DP = 7;
    private static final float LOAD_REVEAL_STAGGER_SEC = 0.055f;
    private static final float LOAD_REVEAL_MAX_DELAY_SEC = 0.38f;

    private static final class RowCascade {
        final Spring spring;
        long startedAtMs;
        float delayRemaining;
        /** Reflow springs keep following the row's layout: see followReflowLayout(). */
        boolean followLayout;
        float layoutTop = Float.NaN;

        RowCascade(float startOffset, LyricCascadeProfile profile) {
            spring = new Spring(startOffset, profile.frequencyHz, profile.damping);
            spring.setGoal(0f);
            delayRemaining = profile.delaySeconds;
            startedAtMs = SystemClock.uptimeMillis();
        }

        /** Folds another wave's displacement into this still-settling row instead of restarting
         *  its spring. Also resets the max-lifetime clock: the extra distance this adds needs its
         *  own budget to decay, or the hard cutoff below can clip it mid-motion into a visible pop. */
        void bump(float delta) {
            spring.nudgePosition(delta);
            startedAtMs = SystemClock.uptimeMillis();
        }
    }
    private TrackInfoReadoutController trackInfoController;
    private final LyricsAmbientController ambientController;
    private final ScrollView lyricsScroll;
    private final FrameLayout lyricsFrame;
    private final LinearLayout lyricsColumn;
    private final LinearLayout mountedRowsHost;
    private final LyricsSpaceView topStaticSpacer;
    private final LyricsSpaceView topVirtualSpacer;
    private final LyricsSpaceView bottomVirtualSpacer;
    private final TextView sourceFooter;
    private final SpotifyPlusConfig config;
    private final AiSettings aiSettings;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsFrameRenderer frameRenderer;
    private final LyricsLineVisualController lineVisualController;
    private LyricsScrollController scrollController;
    private final LyricsTextFactory textFactory;
    private final LyricsRowViewFactory rowViewFactory;
    private final LyricsSecondaryProcessor secondaryProcessor;
    private final LyricsSecondaryRowUpdater secondaryRowUpdater;
    private final LyricsLocalReprocessController localReprocessController;
    private final LyricsShellLifecycle shellLifecycle;
    private final LyricsSettingsDialogController settingsDialogController;
    private final LyricsFollowState followState = new LyricsFollowState();
    private final LyricsShellEmptyStateController emptyStateController;
    /** Lazily built: the long-press-to-share preview is opened far less often than the screen itself. */
    private LyricsShareCardController shareCardController;
    private LyricsRowMountController rowMountController;
    private LinearLayout contentColumn;
    /** Non-null only in the adaptive two-column landscape mode; owns header/lyrics/status. */
    private LinearLayout landscapeRightColumn;
    /** Non-null only in the adaptive two-column landscape mode; art + song info column. */
    private LinearLayout landscapeLeftColumn;
    /** Square frame hosting the panel art plus its play/pause overlay. */
    private FrameLayout columnArtFrame;
    /** Non-null only in the adaptive two-column landscape mode; left square artwork panel. */
    private ImageView columnArt;
    private View columnScrim;
    private ImageButton columnOverlayButton;
    private android.graphics.drawable.Drawable columnPlayIcon;
    private android.graphics.drawable.Drawable columnPauseIcon;
    private Boolean lastColumnOverlayPlaying;
    private final Runnable hideColumnOverlayRunnable = this::hideColumnOverlay;
    private String panelMediaMode = PanelMediaMode.SINGLE_TAP;
    private ArtGestureArbiter columnArtArbiter;
    private float columnArtDownRawX;
    private float columnArtDownRawY;
    private float columnArtDragBoundPx;
    /** Slow second tap while the overlay is up dismisses it without toggling (readout parity). */
    private boolean columnCancelArmed;
    private Bitmap columnArtwork;
    private String lastColumnArtImageId = "";
    /** Image id actually on screen; lags the track while the new cover is still fetching. */
    private String displayedColumnArtImageId = "";
    private long lastColumnArtAttemptMs;
    private long columnArtRetryStartMs;
    /** Construction-time two-column decision (rotation remounts, same as the readout). */
    private final boolean twoColumn;
    private ViewGroup chromeHeader;
    private LyricsShellChromeController.ChromeViews chromeViews;
    private final Runnable hideChromeRunnable = this::hideChrome;
    private boolean scrollInProgress;
    private boolean scrollSettleScheduled;
    private long lastScrollEventMs;
    /** Drives the ScrollView's own position for a far jump (resume-after-scroll, seek) as one
     *  physical motion instead of an eased ValueAnimator running alongside the independent
     *  per-row cascade spring - two differently-timed animations of the same rows read as
     *  disjointed ("따로따로 움직이는 느낌"); one spring owning the actual scroll position doesn't. */
    private com.eza.spicyex.lyrics.Spring scrollSpring;
    private final Runnable scrollSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                scrollSettleScheduled = false;
                scrollInProgress = false;
                return;
            }
            long remaining = SCROLL_SETTLE_REMEASURE_DELAY_MS
                    - (SystemClock.elapsedRealtime() - lastScrollEventMs);
            if (remaining > 0) {
                handler.postDelayed(this, remaining);
                return;
            }
            scrollSettleScheduled = false;
            scrollInProgress = false;
            remeasureMountedRows();
            renderWindowForActive(currentWindowAnchor());
            // Blur springs are renderer-owned and may still be releasing after the scroll
            // callback. Keep vsync alive until the rows have actually returned to their target;
            // otherwise paused playback could stop the scheduler with a few rows still blurred.
            frameScheduler.setContinuous(true);
            frameScheduler.requestFrame();
        }
    };
    private String lastUri = "";
    private String loadingTrackId = "";
    private LyricsDocument document;
    private boolean running;
    private boolean chromeRevealAnimating;
    private boolean scrollWindowRenderScheduled;
    private boolean resetScrollForNextDocument;
    private boolean showTranslation;

    private void hideChrome() {
        if (running && chromeHeader != null && !"Always on".equals(fullscreenControlsMode())) {
            chromeRevealAnimating = false;
            chromeHeader.animate().alpha(0f).setDuration(240L).start();
        }
    }
    private LyricsTransliterationSession transliterationSession;
    private LyricsSessionManager.SessionSubscription sessionSubscription;
    private LyricsSessionManager.LyricsRequest lyricRequest;
    private final LyricsSurfaceDocumentGate documentGate = new LyricsSurfaceDocumentGate();
    private final LyricsSessionManager.Listener sessionListener = new LyricsSessionManager.Listener() {
        @Override public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {}

        @Override public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                                LyricsDocument nextDocument) {
            if (!running || snapshot == null || nextDocument == null) return;
            prepareAndScheduleDocument(snapshot.trackUri, nextDocument);
        }
    };
    private LyricsRenderConfig renderConfig;
    private final SharedPreferences preferences;
    private boolean preferencesRegistered;
    private boolean preferenceRefreshPosted;
    private final Runnable preferenceRefreshRunnable = this::refreshPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> schedulePreferenceRefresh();

    private void refreshPreferences() {
        preferenceRefreshPosted = false;
        if (!running) return;
        likedMode = config.get(Settings.LIKED_SONGS_BUTTON);
        refreshLikedButton(currentTrackThrottled());
        panelMediaMode = config.get(Settings.PANEL_MEDIA_CONTROLS);
        if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) hideColumnOverlay();
        applyRenderConfigChanges("preference changed", false);
        ambientController.applySettings(renderConfig.backgroundStyle, renderConfig.forceDarkBackground,
                renderConfig.extraDarkBackground);
        if (trackInfoController != null) trackInfoController.onPreferenceChanged();
        if (chromeViews != null) {
            LyricsShellChromeController.applyTopMode(chromeViews, isTopReadout(),
                    chromeButtonDp(), isLandscape());
            applyBackVisibility();
        }
        revealChrome();
    }

    /** Top readout owns the top corners: art top-left, controls top-right, no Back. */
    private boolean isTopReadout() {
        try {
            return "Top".equals(config.get(Settings.TRACK_INFO_POSITION));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Two-column owns the left edge with its panel art; Back stays gone while engaged
     * (applyTopMode would otherwise restore it on every preference change). */
    private void applyBackVisibility() {
        if (twoColumn && chromeViews != null && chromeViews.back != null) {
            chromeViews.back.setVisibility(GONE);
        }
    }
    private long lastKeepAliveArmMs;
    // Unsynced (plain) lyrics: no per-line timing, so don't auto-follow or karaoke-wash — render every
    // line uniformly bright + readable and let the user scroll freely (a "static screen").
    private boolean staticDoc;
    private final LyricsPlaybackClock playbackClock;
    private SpotifyTrack throttledTrack;
    private long throttledTrackAtMs;
    private final ChipSpinnerDrawable romanSpinner;
    private final ChipSpinnerDrawable translationSpinner;
    private final LyricsToggleSpinnerController toggleSpinnerController;
    private final com.eza.spicyex.lyrics.ai.AiRequestFeedbackState soundAiFeedback =
            new com.eza.spicyex.lyrics.ai.AiRequestFeedbackState();
    private final com.eza.spicyex.lyrics.ai.AiRequestFeedbackState meaningAiFeedback =
            new com.eza.spicyex.lyrics.ai.AiRequestFeedbackState();
    private final GlyphIconDrawable romanGlyph = new GlyphIconDrawable(
            "A", android.graphics.Typeface.DEFAULT_BOLD);
    private int lyricsTopInsetPx;
    /** Last anchor fraction actually applied - lets applyRenderConfigChanges() tell a real change
     *  (drag the layout editor's focus handle) from a no-op re-apply (any other setting changing)
     *  so it only forces an immediate re-scroll when the anchor itself moved. NaN so the very
     *  first call always counts as a change and seeds this properly. */
    private float lastAppliedAnchorFraction = Float.NaN;
    private long lastLyricPositionMs = -1;
    private long lastDisplayedProgressSecond = Long.MIN_VALUE;
    private String lastDisplayedTitle = "";
    private String lastDisplayedArtist = "";
    private String lastDisplayedAlbum = "";
    private boolean autoResumeFollow;
    private static final long IDLE_FRAME_PROBE_MS = 250L;
    private static final int SETTLE_FRAMES_BEFORE_IDLE = 15;
    private int visuallySettledFrames;
    private final Runnable idleFrameProbe = new Runnable() {
        @Override
        public void run() {
            if (!running || frameScheduler.isContinuous()) return;
            frameScheduler.requestFrame();
            handler.postDelayed(this, IDLE_FRAME_PROBE_MS);
        }
    };


    boolean consumeBack() {
        return consumeShareSheetBack();
    }

    /** Back closes the lyric share sheet first, like any other sheet over the lyrics. */
    private boolean consumeShareSheetBack() {
        if (shareCardController == null || !shareCardController.isShowing()) return false;
        // The line picker first, then the sheet.
        if (!shareCardController.closePickerIfOpen()) shareCardController.dismiss();
        return true;
    }
    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Minimum width/height ratio for the adaptive two-column landscape mode (PR9's gate). */
    static final float TWO_COLUMN_ASPECT_MIN = 1.2f;

    /** Two-column engages only when the adaptive setting is on, the screen reports landscape,
     * and the aspect is genuinely wide — a separate mode from the Off/Top/Bottom readout,
     * whose overlays stand down while it is engaged. */
    static boolean twoColumnEngaged(boolean landscape, float aspect, boolean adaptive) {
        if (!adaptive || !landscape) return false;
        return aspect >= TWO_COLUMN_ASPECT_MIN;
    }

    private static float screenAspect(android.content.res.Resources res) {
        android.util.DisplayMetrics metrics = res.getDisplayMetrics();
        return metrics.widthPixels / (float) Math.max(1, metrics.heightPixels);
    }

    private LinearLayout rowContainer() {
        return landscapeRightColumn != null ? landscapeRightColumn : contentColumn;
    }

    private int chromeButtonDp() {
        // R4 acceptance: 44dp minimum touch targets in every orientation.
        return 44;
    }

    private int lyricsTopPaddingDp() {
        return isLandscape() ? 10 : 22;
    }

    private int lyricsBottomPaddingDp() {
        return isLandscape() ? 86 : 118;
    }

    // Start the first lyric line near screen center (the active line is kept centered as the song
    // plays, so the opening line should begin centered too, not pinned to the top). The top pad is
    // ~0.44 of the viewport — far larger than the status-bar/cutout inset, so the safe-area concern
    // is subsumed. Falls back to screen height before the scroll view is laid out.
    private void applyLyricsScrollPadding() {
        if (lyricsScroll == null) return;
        int safeTop = lyricsTopInsetPx + dp(lyricsTopPaddingDp());
        if (scrollController != null) {
            scrollController.applyCenterPadding(
                    safeTop,
                    dp(lyricsBottomPaddingDp()),
                    getResources().getDisplayMetrics().heightPixels,
                    dp(56));
            return;
        }
        int viewport = lyricsScroll.getHeight();
        if (viewport <= 0) viewport = getResources().getDisplayMetrics().heightPixels;
        int center = Math.max(0, viewport / 2 - dp(56));
        lyricsScroll.setPadding(0, Math.max(safeTop, center), 0, Math.max(dp(lyricsBottomPaddingDp()), center));
    }

    private int computeSafeTopInset(WindowInsets insets) {
        if (insets == null) return lyricsTopInsetPx;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                return bars.top;
            }
            int top = insets.getSystemWindowInsetTop();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
            }
            return top;
        } catch (Throwable t) {
            return lyricsTopInsetPx;
        }
    }

    /** Lyrics frozen under the share sheet: no per-frame lyric work, so no row re-blurs. */
    private boolean lyricsFrozen;
    /** The share sheet hides everything: nothing but it is drawn, the background is paused. */
    private boolean lyricsCovered;

    private final VsyncFrameScheduler frameScheduler = new VsyncFrameScheduler(deltaTimeSeconds -> {
        if (!running) return;
        // The share sheet is up: the lyrics hold still under it (see onShareSheet).
        if (lyricsFrozen) return;
        float dt = deltaTimeSeconds <= 0d ? (1f / 60f) : (float) Math.max(0.001d, Math.min(0.08d, deltaTimeSeconds));
        // Order matters: the reveal publishes this frame's alpha factor, then updateState() runs
        // the renderer, which reads it. Stepping it after would show every row one frame stale.
        stepLoadEntrance(dt);
        stepRowCascade(dt);
        stepScrollSpring(dt);
        updateState(dt);
    });

    NativeSpicyShellViewImpl(LyricsHost host, Activity activity) {
        super(activity);
        this.host = host;
        this.activity = activity;
        this.romanSpinner = new ChipSpinnerDrawable(activity);
        this.translationSpinner = new ChipSpinnerDrawable(activity);
        this.toggleSpinnerController = new LyricsToggleSpinnerController(romanSpinner, translationSpinner);
        this.playbackClock = new LyricsPlaybackClock(host::readBestMeasuredProgressMs);
        this.config = SpotifyPlusConfig.from(activity);
        // Construction-time layout decision: rotation remounts the shell, and the adaptive
        // toggle takes effect on the next open (same contract as PR9's landscape layout).
        this.twoColumn = twoColumnEngaged(
                activity.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                screenAspect(activity.getResources()),
                config.get(Settings.ADAPTIVE_LANDSCAPE_LAYOUT));
        this.aiSettings = new AiSettings(activity);
        this.styleBatcher = new FrameStyleBatcher(activity);
        this.frameRenderer = new LyricsFrameRenderer(activity, styleBatcher);
        this.lineVisualController = new LyricsLineVisualController(styleBatcher);
        this.textFactory = new LyricsTextFactory(activity, config);
        this.rowViewFactory = new LyricsRowViewFactory(activity, textFactory);
        this.secondaryProcessor = new LyricsSecondaryProcessor(activity, HTTP, SOUND_PROCESSOR, SOUND_WORKERS,
                MEANING_WORKERS, AI_WORKERS, handler, GOOGLE_PROCESSING_VERSION);
        this.localReprocessController = new LyricsLocalReprocessController(secondaryProcessor);
        this.ambientController = new LyricsAmbientController(activity, HTTP, config);
        this.settingsDialogController = new LyricsSettingsDialogController(
                activity, frameScheduler, ambientController, host, this::onSettingsClosed, TAG);
        this.emptyStateController = new LyricsShellEmptyStateController(activity, config, textFactory);
        this.shellLifecycle = new LyricsShellLifecycle(activity, () -> {
            if (consumeShareSheetBack()) return;
            host.markExplicitLyricsExit(activity);
            activity.finish();
        });
        SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        preferences = prefs;
        renderConfig = LyricsRenderConfig.read(activity, config);
        // SettingsStore normally attaches this context when the settings panel is opened, but
        // lyrics can be mounted first (or restored from a warm Spotify process). Attach it here as
        // well so post-install model packs are visible to the tokenizer/detector on every entry
        // path, not only after the user has visited Settings.
        com.eza.spicyex.lyrics.LanguageModelPack.attachContext(activity);
        com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor.attachContext(activity);
        autoResumeFollow = config.get(Settings.AUTO_RESUME_FOLLOW);
        slideAnimationEnabled = readSlideEnabled();
        transliterationSession = new LyricsTransliterationSession(
                config.get(Settings.NATIVE_SPICY_ROMANIZATION),
                renderConfig,
                config.get(Settings.LAST_JAPANESE_CYCLE_MODE),
                config.get(Settings.LAST_CHINESE_CYCLE_MODE),
                config.get(Settings.LAST_KOREAN_CYCLE_MODE),
                config.get(Settings.LAST_CYRILLIC_CYCLE_MODE));
        showTranslation = config.get(Settings.NATIVE_SPICY_TRANSLATION);
        // Seed with a status-bar-height estimate; the WindowInsets listener refines it with the
        // real safe-area top (status bar + display cutout) once insets dispatch on attach.
        lyricsTopInsetPx = topSystemPadding(activity);

        setBackground(ambientController.pageBackground());
        setClickable(true);
        setFocusable(true);
        ambientController.attachAnimatedLayer(this, renderConfig.backgroundStyle,
                renderConfig.forceDarkBackground, renderConfig.extraDarkBackground);

        contentColumn = new LinearLayout(activity);
        contentColumn.setOrientation(twoColumn ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        contentColumn.setGravity(twoColumn ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        contentColumn.setClipChildren(false);
        contentColumn.setClipToPadding(false);
        contentColumn.setPadding(sideSystemPadding(activity), 0, sideSystemPadding(activity), dp(isLandscape() ? 16 : 10));
        addView(contentColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (twoColumn) {
            landscapeLeftColumn = new LinearLayout(activity);
            landscapeLeftColumn.setOrientation(LinearLayout.VERTICAL);
            // START keeps the art frame's left edge flush with the song-info text below
            // it; CENTER_VERTICAL centers the fitted stack in the column.
            landscapeLeftColumn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            landscapeLeftColumn.setClipChildren(false);
            landscapeLeftColumn.setClipToPadding(false);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f);
            // Equal screen-edge gutters: 40dp above the art matches the 24dp column
            // gutter + 16dp landscape content padding below the song info. This screen
            // is fully ours, so insets stay out of it — the chrome floats above.
            leftLp.setMargins(0, dp(40), dp(20), dp(24));
            leftLp.gravity = Gravity.CENTER_VERTICAL;
            contentColumn.addView(landscapeLeftColumn, leftLp);
            // Square art at full column width when it fits, flush left with the song
            // info. On short screens it shrinks so title/artist/album always have
            // measured room — the whole stack fits inside the column, nothing is
            // pushed off the bottom edge.
            columnArtFrame = new FrameLayout(activity) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    int side = width;
                    if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                        int reserve = dp(8);
                        if (title != null && title.getVisibility() != GONE) {
                            title.measure(widthMeasureSpec,
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            reserve += title.getMeasuredHeight();
                        }
                        if (subtitle != null && subtitle.getVisibility() != GONE) {
                            subtitle.measure(widthMeasureSpec,
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            reserve += subtitle.getMeasuredHeight();
                        }
                        if (albumLine != null && albumLine.getVisibility() != GONE) {
                            albumLine.measure(widthMeasureSpec,
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            reserve += albumLine.getMeasuredHeight();
                        }
                        side = Math.min(width, Math.max(0,
                                MeasureSpec.getSize(heightMeasureSpec) - reserve));
                    }
                    int squareSpec = MeasureSpec.makeMeasureSpec(Math.max(0, side),
                            MeasureSpec.EXACTLY);
                    super.onMeasure(squareSpec, squareSpec);
                }
            };
            columnArt = new ImageView(activity);
            columnArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            columnArt.setVisibility(GONE);
            columnArt.setClipToOutline(true);
            columnArt.setElevation(dp(16));
            final int columnArtRadiusPx = dp(20);
            columnArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), columnArtRadiusPx);
                }
            });
            columnArtFrame.addView(columnArt, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            columnScrim = new View(activity);
            android.graphics.drawable.GradientDrawable columnScrimBg =
                    new android.graphics.drawable.GradientDrawable();
            columnScrimBg.setColor(0x73000000);
            columnScrimBg.setCornerRadius(columnArtRadiusPx);
            columnScrim.setBackground(columnScrimBg);
            columnScrim.setVisibility(GONE);
            // No click listener: the scrim must stay non-clickable so taps fall through
            // to the art touch handler below. A clickable scrim would swallow the second
            // tap (hiding the overlay instead of toggling) and break Single-tap mode.
            columnArtFrame.addView(columnScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            float columnDensity = activity.getResources().getDisplayMetrics().density;
            columnPlayIcon = new com.eza.spicyex.ui.ActionIconDrawable(
                    com.eza.spicyex.ui.ActionIconDrawable.Kind.PLAY,
                    Color.rgb(232, 232, 238), columnDensity);
            columnPauseIcon = new TrackInfoReadoutController.PauseBarsDrawable(
                    Color.rgb(232, 232, 238));
            columnOverlayButton = new ImageButton(activity);
            columnOverlayButton.setBackgroundColor(Color.TRANSPARENT);
            columnOverlayButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            columnOverlayButton.setVisibility(GONE);
            columnOverlayButton.setFocusable(false);
            columnOverlayButton.setOnClickListener(v -> {
                host.togglePlayPause();
                updateColumnOverlayIcon(host.isPlayerActuallyPlaying());
                scheduleColumnOverlayHide();
                revealChrome();
            });
            int columnOverlaySize = dp(56);
            columnArtFrame.addView(columnOverlayButton, new FrameLayout.LayoutParams(
                    columnOverlaySize, columnOverlaySize, Gravity.CENTER));
            columnArt.setClickable(true);
            columnArt.setFocusable(true);
            columnArt.setOnClickListener(v -> {
                if (PanelMediaMode.revealOnSingleTap(panelMediaMode)) {
                    showColumnOverlay();
                } else if (PanelMediaMode.DOUBLE_TAP.equals(panelMediaMode)) {
                    host.togglePlayPause();
                    flashColumnIcon();
                }
                revealChrome();
            });
            panelMediaMode = config.get(Settings.PANEL_MEDIA_CONTROLS);
            android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(activity);
            columnArtArbiter = new ArtGestureArbiter(vc.getScaledTouchSlop(),
                    android.view.ViewConfiguration.getDoubleTapTimeout(), dp(32),
                    android.os.SystemClock::elapsedRealtime);
            // Listener lives on the art itself (it fills the frame, so frame-level
            // touches would never fire); drags translate the whole frame so the
            // overlay travels with the cover. Click stays for TalkBack/keyboard.
            columnArt.setOnTouchListener((v, event) -> {
                if (event.getPointerCount() > 1) {
                    columnArtArbiter.onCancel();
                    columnCancelArmed = false;
                    columnArtFrame.setTranslationX(0f);
                    return true;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    // Chrome reveal is not a media control; it always applies.
                    revealChrome();
                    if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) return true;
                    columnArtFrame.animate().cancel();
                    columnArtDownRawX = event.getRawX();
                    columnArtDownRawY = event.getRawY();
                    columnArtDragBoundPx = columnArtFrame.getWidth();
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (columnOverlayVisible() && !columnArtArbiter.isDoubleTapCandidate(now)) {
                        // Tap around the button dismisses the overlay; it must not toggle.
                        columnArtArbiter.reset();
                        columnCancelArmed = true;
                    } else {
                        columnArtArbiter.onDown(now);
                    }
                    return true;
                }
                if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    ArtGestureArbiter.Output out = columnArtArbiter.onMove(
                            event.getRawX() - columnArtDownRawX,
                            event.getRawY() - columnArtDownRawY);
                    if (out == ArtGestureArbiter.Output.DRAG_UPDATE) {
                        columnCancelArmed = false;
                        hideColumnOverlay();
                        columnArtFrame.setTranslationX(clampedColumnDrag(columnArtArbiter.dragDxPx()));
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    if (columnCancelArmed) {
                        columnCancelArmed = false;
                        hideColumnOverlay();
                        return true;
                    }
                    handleColumnArtUp(columnArtArbiter.onUp());
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    columnArtArbiter.onCancel();
                    columnCancelArmed = false;
                    columnArtFrame.setTranslationX(0f);
                    return true;
                }
                return true;
            });
            LinearLayout.LayoutParams artFrameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            artFrameLp.gravity = Gravity.START;
            landscapeLeftColumn.addView(columnArtFrame, artFrameLp);
            landscapeRightColumn = new LinearLayout(activity);
            landscapeRightColumn.setOrientation(LinearLayout.VERTICAL);
            landscapeRightColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            landscapeRightColumn.setClipChildren(false);
            landscapeRightColumn.setClipToPadding(false);
            contentColumn.addView(landscapeRightColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
        }

        int chromeButton = chromeButtonDp();
        likedMode = config.get(Settings.LIKED_SONGS_BUTTON);
        LyricsShellChromeController.ChromeViews chrome = LyricsShellChromeController.attach(
                activity,
                this,
                textFactory,
                romanGlyph,
                romanSpinner,
                translationSpinner,
                chromeButton,
                isLandscape(),
                isTopReadout(),
                () -> {
                    host.markExplicitLyricsExit(activity);
                    activity.finish();
                },
                () -> cycleTransliterationMode(prefs),
                () -> {
                    if (renderConfig != null && !renderConfig.translationEnabled) return;
                    boolean wasVisible = showTranslation();
                    boolean hasDisplayedMeaning = hasLayerOutput(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING);
                    boolean requestedOutput = shouldGenerateAi(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING);
                    boolean requestStarted = !requestedOutput || requestAiLayerWithFeedback(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING);
                    boolean keepVisible = transliterationSession.keepVisibleForRequestedOutput(
                            requestedOutput, wasVisible, hasDisplayedMeaning);
                    if (TranslationVisibilityPolicy.onTap(requestedOutput, requestStarted,
                            keepVisible) != TranslationVisibilityPolicy.TapAction.TOGGLE) {
                        return;
                    }
                    showTranslation = !showTranslation;
                    markTranslationToggled();
                    prefs.edit().putBoolean(Settings.NATIVE_SPICY_TRANSLATION.key, showTranslation).apply();
                    updateToggleVisuals();
                    // In place: a full renderDocument() rebuilt every row object and reset the
                    // active line, so the reflow springs found nothing to animate and the follow
                    // scroll jumped - the flash when translations appeared or left.
                    hideThenRebuild(showTranslation ? java.util.Collections.emptyList()
                            : mountedTranslationViews(), this::rebuildSecondaryRowsInPlace);
                },
                () -> settingsDialogController.show(),
                com.eza.spicyex.ui.ActionIconDrawable.likedSongsKind(likedMode),
                this::onLikeTapped);
        chromeHeader = chrome.header;
        chromeViews = chrome;
        applyBackVisibility();
        romanToggle = chrome.romanToggle;
        translationToggle = chrome.translationToggle;
        likeButton = chrome.likeButton;
        refreshLikedButton(null);
        romanToggle.setOnClickListener(v -> {
            if (SoundToggleRouter.forGesture(false)
                    == SoundToggleRouter.Action.CYCLE_LOCAL_MODE) cycleTransliterationMode(prefs);
        });
        romanToggle.setOnLongClickListener(v -> {
            if (SoundToggleRouter.forGesture(true)
                    == SoundToggleRouter.Action.OPEN_AI_PANEL) {
                openAiLayerPanel(com.eza.spicyex.lyrics.session.LayerKind.SOUND);
            }
            return true;
        });
        translationToggle.setOnLongClickListener(v -> {
            openAiLayerPanel(com.eza.spicyex.lyrics.session.LayerKind.MEANING);
            return true;
        });
        updateToggleVisuals();

        title = textFactory.createText(activity, "Waiting for Spotify track…", twoColumn ? 20 : 18, Color.WHITE, textFactory.resolveTypeface(true));
        title.setVisibility(GONE);
        title.setGravity(twoColumn ? Gravity.START : Gravity.CENTER);
        title.setMaxLines(1);
        title.setAlpha(0.92f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Panel song info sits flush against the art above it.
        titleLp.topMargin = dp(twoColumn ? 8 : 0);
        if (twoColumn) {
            setupPanelMarquee(title);
            landscapeLeftColumn.addView(title, titleLp);
        } else {
            rowContainer().addView(title, titleLp);
        }

        subtitle = textFactory.createText(activity, "Open playback, then fullscreen lyrics", twoColumn ? 15 : 13, Color.rgb(190, 190, 190), textFactory.resolveTypeface(false));
        subtitle.setVisibility(GONE);
        subtitle.setGravity(twoColumn ? Gravity.START : Gravity.CENTER);
        subtitle.setMaxLines(1);
        subtitle.setAlpha(0.72f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(0);
        if (twoColumn) {
            setupPanelMarquee(subtitle);
            landscapeLeftColumn.addView(subtitle, subtitleLp);
            // These views are legacy-GONE everywhere else (the readout owns song info in
            // portrait); the panel is their only owner, so it must show them itself.
            title.setVisibility(VISIBLE);
            subtitle.setVisibility(VISIBLE);
        } else {
            rowContainer().addView(subtitle, subtitleLp);
        }

        if (twoColumn) {
            albumLine = textFactory.createText(activity, "", 14, Color.rgb(150, 150, 150), textFactory.resolveTypeface(false));
            albumLine.setVisibility(VISIBLE);
            albumLine.setGravity(Gravity.START);
            albumLine.setMaxLines(1);
            albumLine.setAlpha(0.6f);
            setupPanelMarquee(albumLine);
            LinearLayout.LayoutParams albumLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            albumLp.topMargin = dp(0);
            landscapeLeftColumn.addView(albumLine, albumLp);
        } else {
            albumLine = null;
        }

        lyricsScroll = new com.eza.spicyex.lyrics.ElasticScrollView(activity);
        lyricsScroll.setFillViewport(false);
        lyricsScroll.setClipToPadding(false);
        lyricsScroll.setClipChildren(false);
        applyLyricsScrollPadding();
        lyricsScroll.setVerticalFadingEdgeEnabled(false);
        lyricsScroll.setFadingEdgeLength(0);
        lyricsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.getViewTreeObserver().addOnPreDrawListener(() -> {
            holdScrollAnchor();
            return true;
        });
        LyricsTapSeekHandler tapSeekHandler = new LyricsTapSeekHandler(
                activity,
                config,
                followState::holdUntil,
                followState::setTouching,
                this::seekNearestLineAt,
                this::shareLyricLineAt);
        lyricsScroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                // A finger on the list ends any programmatic-scroll grace period immediately, so
                // the user's own motion is never mistaken for the shell's.
                programmaticScrollUntilMs = 0;
                revealChrome();
            }
            trackPressedLyric(event);
            int action = event.getActionMasked();
            if (action != android.view.MotionEvent.ACTION_DOWN && tapSeekHandler.longPressFired()
                    && shareCardController != null && shareCardController.isShowing()) {
                // The finger that opened the share sheet is still down: its moves pull the card a
                // little (the sheet's rubber band) instead of scrolling the lyrics underneath.
                shareCardController.heldDrag(event);
                tapSeekHandler.onTouch(view, event);
                return true;
            }
            return tapSeekHandler.onTouch(view, event);
        });
        lyricsFrame = new FrameLayout(activity);
        lyricsColumn = new LinearLayout(activity);
        lyricsColumn.setOrientation(LinearLayout.VERTICAL);
        lyricsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsColumn.setClipChildren(false);
        lyricsColumn.setClipToPadding(false);

        topStaticSpacer = new LyricsSpaceView(activity, dp(96));
        topVirtualSpacer = new LyricsSpaceView(activity, 0);
        mountedRowsHost = new LinearLayout(activity);
        mountedRowsHost.setOrientation(LinearLayout.VERTICAL);
        mountedRowsHost.setGravity(Gravity.CENTER_HORIZONTAL);
        mountedRowsHost.setClipChildren(false);
        mountedRowsHost.setClipToPadding(false);
        secondaryRowUpdater = new LyricsSecondaryRowUpdater(mountedRowsHost, lineVisualController::invalidate);
        bottomVirtualSpacer = new LyricsSpaceView(activity, 0);
        sourceFooter = textFactory.createText(activity, "", 12, Color.rgb(125, 125, 125), textFactory.resolveTypeface(false));
        sourceFooter.setGravity(Gravity.CENTER);
        sourceFooter.setAlpha(0.56f);
        sourceFooter.setPadding(dp(16), dp(38), dp(16), dp(180));
        rowMountController = new LyricsRowMountController(
                mountedRowsHost,
                topVirtualSpacer,
                bottomVirtualSpacer,
                LYRIC_FULL_RENDER_THRESHOLD,
                LYRIC_WINDOW_BEFORE_ACTIVE,
                LYRIC_WINDOW_AFTER_ACTIVE,
                NativeRuntime.LYRIC_WINDOW_EDGE_BUFFER);
        scrollController = new LyricsScrollController(lyricsScroll, lyricsColumn, topStaticSpacer);
        scrollController.setAnchorFraction(resolveFocusAnchorFraction());
        applyLyricsScrollPadding();

        ensureLyricsColumnScaffold();
        lyricsScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!running) return;
            if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
            // The shell's own scrolls come through here too. Treating them as user motion made
            // every lyric advance mark a manual scroll (pushing the auto-resume cooldown back) and
            // schedule a full remeasure + window render ~500ms later, so follow kept stuttering on
            // work the user never asked for.
            boolean programmatic = applyingLyricScroll
                    || SystemClock.elapsedRealtime() < programmaticScrollUntilMs;
            if (!programmatic && scrollY != oldScrollY) {
                // The user has taken the list over. Anything still driving the scroll position or
                // the rows' offsets is now fighting their finger.
                clearRowCascade();
                scrollSpring = null;
                clearScrollSubpixel();
            }
            frameScheduler.setContinuous(true);
            frameScheduler.requestFrame();
            scheduleScrollWindowRender();
            if (programmatic) return;
            scrollInProgress = true;
            scheduleScrollSettleRemeasure();
        });
        lyricsScroll.addView(lyricsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsFrame.addView(lyricsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        jumpToCurrentController = LyricsJumpToCurrentController.attach(
                activity,
                lyricsFrame,
                textFactory,
                this::resumeFollowCurrentLine);
        skipGapController = LyricsSkipGapController.attach(
                activity,
                lyricsFrame,
                this::skipCurrentGap);
        trackInfoController = TrackInfoReadoutController.attach(
                activity,
                this,
                jumpToCurrentController,
                textFactory,
                host,
                config,
                this::revealChrome,
                twoColumn);
        trackInfoController.setSkipGapController(skipGapController);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = 0;
        rowContainer().addView(lyricsFrame, scrollLp);

        progress = textFactory.createText(activity, "--:--", 13, Color.rgb(210, 210, 210), textFactory.resolveTypeface(true));
        progress.setFontFeatureSettings("tnum");
        progress.setVisibility(GONE);
        progress.setGravity(Gravity.CENTER);
        progress.setAlpha(0.72f);
        rowContainer().addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = textFactory.createText(activity, "Spicy native renderer", 12, Color.rgb(160, 160, 160), textFactory.resolveTypeface(false));
        status.setVisibility(GONE);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        status.setAlpha(0.62f);
        statusLp.topMargin = dp(0);
        rowContainer().addView(status, statusLp);

        // Refine the lyric top inset from real window insets (status bar + cutout) once they
        // dispatch on attach. Returned unconsumed so nothing else is starved of insets.
        setOnApplyWindowInsetsListener((v, insets) -> {
            int top = computeSafeTopInset(insets);
            if (top > 0 && top != lyricsTopInsetPx) {
                lyricsTopInsetPx = top;
                applyLyricsScrollPadding();
            }
            return insets;
        });
    }

    void start() {
        dbgEnter("NativeSpicyShellView.start");
        if (running) return;
        running = true;
        com.eza.spicyex.lyrics.LanguageModelPack.setReadyListener(languageModelReadyListener);
        ambientController.start();
        revealChrome();
        documentGate.start();
        registerPreferenceListener();
        sessionSubscription = host.subscribeLyricsSession(sessionListener);
        shellLifecycle.start();
        playbackClock.reset("");
        updateState(1f / 60f);
        frameScheduler.start();
    }

    void stop() {
        dbgEnter("NativeSpicyShellView.stop");
        running = false;
        com.eza.spicyex.lyrics.LanguageModelPack.clearReadyListener(languageModelReadyListener);
        documentGate.stop();
        if (lyricRequest != null) lyricRequest.close();
        lyricRequest = null;
        if (sessionSubscription != null) sessionSubscription.close();
        sessionSubscription = null;
        unregisterPreferenceListener();
        toggleSpinnerController.reset();
        shellLifecycle.stop();
        frameScheduler.stop();
        clearRowCascade();
        clearLoadEntrance();
        scrollSpring = null;
        returnToCurrentPending = false;
        clearScrollSubpixel();
        ambientController.stop();
        handler.removeCallbacks(idleFrameProbe);
        visuallySettledFrames = 0;
        playbackClock.reset("");
        handler.removeCallbacks(scrollSettleRunnable);
        scrollSettleScheduled = false;
        scrollInProgress = false;
        handler.removeCallbacksAndMessages(null);
        chromeRevealAnimating = false;
        if (chromeHeader != null) chromeHeader.animate().cancel();
        if (trackInfoController != null) trackInfoController.teardown();
        hideColumnOverlay();
        if (columnArtFrame != null) {
            columnArtFrame.animate().cancel();
            columnArtFrame.setTranslationX(0f);
        }
        clearColumnArtwork();
        lastColumnArtImageId = "";
        displayedColumnArtImageId = "";
        clearPendingStyleWrites();
    }

    private void revealChrome() {
        if (chromeHeader == null) return;
        handler.removeCallbacks(hideChromeRunnable);
        chromeHeader.setVisibility(View.VISIBLE);
        if (chromeHeader.getAlpha() < 0.99f && !chromeRevealAnimating) {
            chromeRevealAnimating = true;
            chromeHeader.animate().cancel();
            chromeHeader.animate().alpha(1f).setDuration(100L)
                    .withEndAction(() -> chromeRevealAnimating = false).start();
        } else {
            chromeHeader.setAlpha(1f);
            chromeRevealAnimating = false;
        }
        long delay;
        switch (fullscreenControlsMode()) {
            case "5 seconds": delay = 5000L; break;
            case "10 seconds": delay = 10000L; break;
            case "30 seconds": delay = 30000L; break;
            default: return;
        }
        handler.postDelayed(hideChromeRunnable, delay);
    }

    private String fullscreenControlsMode() {
        return new com.eza.spicyex.lyrics.LyricsShellSettings(activity, config)
                .fullscreenControlsMode();
    }

    // The reflective player-state walk in host.getCurrentTrackSafely() is too expensive for every
    // vsync frame; refresh at ~4Hz. Position interpolation reads live player state through
    // PlaybackClock separately, so the only cost is up to 250ms of track-change latency.
    private SpotifyTrack currentTrackThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (throttledTrack == null || now - throttledTrackAtMs >= 250) {
            throttledTrack = host.getCurrentTrackSafely();
            throttledTrackAtMs = now;
        }
        return throttledTrack;
    }

    /** Feeds the two-column artwork panel from the shared cache; snapshots are
     * caller-owned copies. The previous cover stays on screen until the new one
     * arrives (no clear-and-gap on song change); a cover that never arrives gives
     * up after ~10s rather than showing the wrong track forever. */
    private static final long COLUMN_ART_RETRY_WINDOW_MS = 10_000L;

    private void updateColumnArt(SpotifyTrack track, long nowMs) {
        if (!twoColumn || columnArt == null) return;
        String imageId = track == null ? "" : safe(track.imageId);
        String uri = track == null ? "" : safe(track.uri);
        if (!imageId.equals(lastColumnArtImageId)) {
            lastColumnArtImageId = imageId;
            lastColumnArtAttemptMs = 0;
            columnArtRetryStartMs = nowMs;
            hideColumnOverlay();
            if (columnArtFrame != null) {
                columnArtFrame.animate().cancel();
                columnArtFrame.setTranslationX(0f);
            }
            if (columnArtArbiter != null) columnArtArbiter.reset();
            columnCancelArmed = false;
            if (imageId.isEmpty()) {
                displayedColumnArtImageId = "";
                clearColumnArtwork();
                columnArt.setVisibility(GONE);
            }
        }
        if (imageId.isEmpty() || imageId.equals(displayedColumnArtImageId)) return;
        if (nowMs - columnArtRetryStartMs > COLUMN_ART_RETRY_WINDOW_MS) {
            displayedColumnArtImageId = imageId;
            clearColumnArtwork();
            columnArt.setVisibility(GONE);
            return;
        }
        if (nowMs - lastColumnArtAttemptMs < 1000) return;
        lastColumnArtAttemptMs = nowMs;
        Bitmap art = null;
        try {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int panelPx = (int) (metrics.widthPixels * 0.8f / 1.95f);
            art = SpotifyArtworkCache.snapshotLarge(imageId, uri, panelPx);
        } catch (Throwable ignored) {
        }
        if (art == null) return;
        displayedColumnArtImageId = imageId;
        clearColumnArtwork();
        columnArtwork = art;
        columnArt.setImageBitmap(art);
        columnArt.setVisibility(VISIBLE);
        columnArt.invalidateOutline();
        columnArt.setContentDescription(
                emptyFallback(track.title, "Unknown title") + " — " + emptyFallback(track.artist, "Unknown artist"));
    }

    private void clearColumnArtwork() {
        if (columnArt != null) columnArt.setImageBitmap(null);
        if (columnArtwork != null) {
            try {
                columnArtwork.recycle();
            } catch (Throwable ignored) {
            }
            columnArtwork = null;
        }
    }

    /** Single-line marquee for the narrow two-column song-info texts. */
    private static void setupPanelMarquee(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1);
        view.setHorizontalFadingEdgeEnabled(true);
        view.setSelected(true);
    }

    /** Panel art gestures share the readout's arbitration: single tap reveals the
     * play/pause overlay in Single-tap mode (a slow second tap on the art dismisses it,
     * a quick second tap or a button tap toggles), double-tap toggles immediately with a brief
     * icon pulse, horizontal drag past ~32dp commits prev/next with follow-through,
     * release inside snaps back. Off disables gestures and swipe entirely. */
    private void handleColumnArtUp(ArtGestureArbiter.Output out) {
        if (columnArtFrame == null) return;
        switch (out) {
            case REVEAL:
                if (PanelMediaMode.revealOnSingleTap(panelMediaMode)) showColumnOverlay();
                else springBackColumnArt();
                break;
            case TOGGLE:
                columnArtFrame.setTranslationX(0f);
                if (toggleColumnTransport()) flashColumnIcon();
                break;
            case COMMIT_NEXT:
                if (!commitColumnTrack(true)) {
                    springBackColumnArt();
                } else {
                    columnArtFrame.animate().cancel();
                    columnArtFrame.animate().translationX(-columnArtFrame.getWidth())
                            .setDuration(160L)
                            .withEndAction(() -> columnArtFrame.animate().translationX(0f)
                                    .setDuration(120L).start())
                            .start();
                }
                break;
            case COMMIT_PREV:
                if (!commitColumnTrack(false)) {
                    springBackColumnArt();
                } else {
                    columnArtFrame.animate().cancel();
                    columnArtFrame.animate().translationX(columnArtFrame.getWidth())
                            .setDuration(160L)
                            .withEndAction(() -> columnArtFrame.animate().translationX(0f)
                                    .setDuration(120L).start())
                            .start();
                }
                break;
            case SPRING_BACK:
            default:
                springBackColumnArt();
                break;
        }
    }

    private void springBackColumnArt() {
        if (columnArtFrame == null) return;
        columnArtFrame.animate().cancel();
        columnArtFrame.animate().translationX(0f).setDuration(180L).start();
    }

    private void showColumnOverlay() {
        if (columnScrim == null || columnOverlayButton == null) return;
        updateColumnOverlayIcon(host.isPlayerActuallyPlaying());
        columnScrim.setVisibility(VISIBLE);
        columnOverlayButton.setVisibility(VISIBLE);
        columnScrim.animate().cancel();
        columnOverlayButton.animate().cancel();
        columnScrim.setAlpha(0f);
        columnScrim.animate().alpha(1f).setDuration(150L).start();
        columnOverlayButton.setAlpha(0f);
        columnOverlayButton.setScaleX(0.6f);
        columnOverlayButton.setScaleY(0.6f);
        columnOverlayButton.animate().alpha(1f).setDuration(150L).start();
        columnOverlayButton.animate().scaleX(1f).scaleY(1f).setDuration(260L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.0f))
                .start();
        scheduleColumnOverlayHide();
    }

    private void scheduleColumnOverlayHide() {
        handler.removeCallbacks(hideColumnOverlayRunnable);
        handler.postDelayed(hideColumnOverlayRunnable, 1800L);
    }

    /** Brief play/pause icon pulse with no scrim (double-tap feedback). */
    private void flashColumnIcon() {
        if (columnOverlayButton == null) return;
        updateColumnOverlayIcon(host.isPlayerActuallyPlaying());
        if (columnScrim != null) {
            columnScrim.animate().cancel();
            columnScrim.setVisibility(GONE);
        }
        columnOverlayButton.setVisibility(VISIBLE);
        columnOverlayButton.animate().cancel();
        columnOverlayButton.setAlpha(0f);
        columnOverlayButton.setScaleX(1f);
        columnOverlayButton.setScaleY(1f);
        columnOverlayButton.animate().alpha(1f).setDuration(150L).start();
        handler.removeCallbacks(hideColumnOverlayRunnable);
        handler.postDelayed(hideColumnOverlayRunnable, 400L);
    }

    private void hideColumnOverlay() {
        if (columnArtFrame != null) handler.removeCallbacks(hideColumnOverlayRunnable);
        if (columnScrim != null) {
            columnScrim.animate().cancel();
            columnScrim.setVisibility(GONE);
        }
        if (columnOverlayButton != null) {
            columnOverlayButton.animate().cancel();
            columnOverlayButton.setVisibility(GONE);
        }
    }

    private void updateColumnOverlayIcon(boolean playing) {
        if (columnOverlayButton == null) return;
        if (Boolean.valueOf(playing).equals(lastColumnOverlayPlaying)) return;
        lastColumnOverlayPlaying = playing;
        columnOverlayButton.setImageDrawable(playing ? columnPauseIcon : columnPlayIcon);
    }

    private boolean columnOverlayVisible() {
        return columnScrim != null && columnScrim.getVisibility() == VISIBLE;
    }

    private boolean toggleColumnTransport() {
        try {
            return host.togglePlayPause();
        } catch (Throwable ignored) {
            return false;
        }
        // No optimistic icon flip; updateState() applies observed playing state.
    }

    private boolean commitColumnTrack(boolean next) {
        try {
            return next ? host.skipToNextTrack() : host.skipToPreviousTrack();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float clampedColumnDrag(float dx) {
        float bound = Math.max(1f, columnArtDragBoundPx);
        float ax = Math.abs(dx);
        if (ax <= bound) return dx;
        return Math.signum(dx) * (bound + (ax - bound) * 0.3f);
    }

    // Matches AdMuteController's own detection - Spotify's ad tracks use this URI scheme.
    private static boolean isAdTrack(SpotifyTrack track) {
        return track != null && track.uri != null && track.uri.startsWith("spotify:ad:");
    }

    private void updateState(float deltaSeconds) {
        SpotifyTrack track = currentTrackThrottled();
        boolean playingNow = host.isPlayerActuallyPlaying();
        ambientController.setPlaying(playingNow);
        updateJumpToCurrentVisibility();
        updateToggleSpinners();
        // Ad break: Spotify models it as an ordinary track under a spotify:ad: URI. It has its
        // own title/artwork (whatever the ad creative provides), which is worth showing rather
        // than hiding, so it flows through the normal per-track update below like any other
        // track; only the seek/skip-gap chip is suppressed, since seeking within an ad doesn't
        // mean anything (AdMuteController separately mutes its AudioTrack using the same signal).
        boolean adTrack = isAdTrack(track);
        if (adTrack) skipGapController.update(false);
        if (track == null) {
            setTextIfChanged(title, "Waiting for Spotify track…");
            setTextIfChanged(subtitle, "Player state hook has not emitted yet");
            setTextIfChanged(progress, "--:--");
            setTextIfChanged(status, "Native Spicy renderer mounted. Waiting for player state.");
            skipGapController.update(false);
            updateFrameDemand(false, false);
            return;
        }

        // Keep the lyric screen alive across track changes while it's actually on screen — the
        // mount-time keep window otherwise lapses after a few seconds. Throttled to ~1s; the
        // window auto-expires once the shell stops (teardown), so explicit exits still close it.
        long armNow = SystemClock.elapsedRealtime();
        if (armNow - lastKeepAliveArmMs > 1000) {
            lastKeepAliveArmMs = armNow;
            host.markLyricsKeepAlive(activity);
        }

        String uri = safe(track.uri);
        if (!uri.equals(lastUri)) {
            lastUri = uri;
            playbackClock.reset(uri);
            clearRowCascade();
            cancelLoadEntranceAnimation();
            lastDisplayedProgressSecond = Long.MIN_VALUE;
            lastDisplayedTitle = "";
            lastDisplayedArtist = "";
            lastDisplayedAlbum = "";
            followState.resetActive();
            lastLyricPositionMs = -1;
            resetScrollForNextDocument = true;
            document = null;
            String id = trackIdFromUri(uri);
            ambientController.updateForTrack(track, () -> running);
            XpLog.log(TAG + " active track uri=" + uri + " title=\"" + safe(track.title) + "\"");
            if (adTrack) {
                // Ads carry no lyrics: no loading skeleton, just an empty lyrics area. Title and
                // artwork update below via the per-frame path.
                loadingTrackId = "";
                rowMountController.reset();
                followState.resetActive();
                emptyStateController.showAdState(lyricsScroll, lyricsColumn);
            } else {
                showLoading("Loading lyrics…");
                loadLyrics(track, id);
            }
        }
        long pos = playbackClock.getPosition(track, playingNow);
        if (adTrack) updateAdCard(track, pos);

        String trackTitle = emptyFallback(track.title, "Unknown title");
        String trackArtist = emptyFallback(track.artist, "Unknown artist");
        String trackAlbum = emptyFallback(track.album, "Unknown album");
        if (!trackTitle.equals(lastDisplayedTitle)) {
            lastDisplayedTitle = trackTitle;
            setTextIfChanged(title, trackTitle);
        }
        if (!trackArtist.equals(lastDisplayedArtist) || !trackAlbum.equals(lastDisplayedAlbum)) {
            lastDisplayedArtist = trackArtist;
            lastDisplayedAlbum = trackAlbum;
            if (twoColumn && albumLine != null) {
                setTextIfChanged(subtitle, trackArtist);
                setTextIfChanged(albumLine, trackAlbum);
            } else {
                setTextIfChanged(subtitle, trackArtist + " • " + trackAlbum);
            }
        }
        if (trackInfoController != null) {
            trackInfoController.onTrackChanged(track);
            trackInfoController.onPlayingChanged(playingNow);
        }
        updateColumnArt(track, SystemClock.elapsedRealtime());
        if (twoColumn && columnOverlayButton != null
                && columnOverlayButton.getVisibility() == VISIBLE) {
            updateColumnOverlayIcon(playingNow);
        }
        updateLikedButton(track);
        long displayedSecond = Math.max(0L, pos) / 1000L;
        if (displayedSecond != lastDisplayedProgressSecond) {
            lastDisplayedProgressSecond = displayedSecond;
            setTextIfChanged(progress, formatMs(pos));
        }

        boolean rendererPending = false;
        if (document != null) {
            if (staticDoc) {
                // Reassert static styling after remounts and late secondary-text updates. Static
                // rows have synthetic layout timings, never a karaoke-active row.
                skipGapController.update(false);
                frameRenderer.applyStatic(document, rowMountController.mountedIndices(), mountedRowsHost);
            } else {
                long lyricPos = adjustedLyricPositionMs(pos);
                int nextActive = LyricTimeline.findPrimaryActiveRow(document.appliedLines, lyricPos);
                boolean drasticSeek = lastLyricPositionMs >= 0 && Math.abs(lyricPos - lastLyricPositionMs) > 1000;
                lastLyricPositionMs = lyricPos;
                if (!adTrack) updateSkipGap(track, uri, lyricPos, playingNow);
                maybeAutoResumeFollow(nextActive, track, lyricPos);
                if (nextActive != followState.activeIndex() || drasticSeek) {
                    setActiveLine(nextActive, lyricPos, track, drasticSeek);
                }
                boolean userScrollHeld = followState.isHoldingNow();
                // Always, not only while held: rows outside it skip per-syllable animation work.
                long visibleRange = scrollController != null
                        ? scrollController.visibleLineRange(rowHeightPrefix(), document.appliedLines.size())
                        : LyricsScrollController.ALL_LINES;
                int visibleStart = LyricsScrollController.rangeStart(visibleRange);
                int visibleEnd = LyricsScrollController.rangeEnd(visibleRange);
                frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                        renderConfig, lyricPos, nextActive, deltaSeconds, userScrollHeld,
                        visibleStart, visibleEnd);
                // Asked with the same viewport the frame pass just used, so a row the pass culled
                // cannot keep the scheduler rendering a paused player.
                rendererPending = frameRenderer.hasPendingAnimation(document,
                        rowMountController.mountedIndices(), mountedRowsHost,
                        nextActive, visibleStart, visibleEnd);
            }
            if (status.getVisibility() == View.VISIBLE) {
                String processingStatus = "";
                if (document.processingPending) {
                    processingStatus = document.romanizationPending && document.translationPending ? " [P:R+Tr]"
                            : document.romanizationPending ? " [P:R]"
                            : document.translationPending ? " [P:Tr]"
                            : " [P:...]";
                }
                setTextIfChanged(status, (playingNow ? "Playing" : "Paused")
                        + processingStatus
                        + " • " + document.fetchSource
                        + " • " + document.provider
                        + " • " + document.type
                        + " • " + document.appliedLines.size() + " rows");
            }
        } else if (!loadingTrackId.isEmpty() && status.getVisibility() == View.VISIBLE) {
            setTextIfChanged(status, (playingNow ? "Playing" : "Paused") + " • fetching lyrics for " + shortTrackId(uri));
        }
        updateFrameDemand(playingNow, rendererPending);
    }

    /** "1 of 3 · 0:23" under the ad card: where this ad sits in the break and how long it has left. */
    private void updateAdCard(SpotifyTrack track, long positionMs) {
        StringBuilder text = new StringBuilder();
        AdBreakInfo info = AdBreakInfo.current(track.uri);
        if (info != null && info.known()) {
            text.append(com.eza.spicyex.UiLanguage.strings(activity, config.get(Settings.UI_LANGUAGE))
                    .get("lyrics_ad_position", "%1$d of %2$d")
                    .replace("%1$d", String.valueOf(info.index))
                    .replace("%2$d", String.valueOf(info.count)));
        }
        if (track.duration > 0 && positionMs >= 0) {
            long left = Math.max(0L, (track.duration - positionMs + 999L) / 1000L);
            if (text.length() > 0) text.append("  ·  ");
            text.append(left / 60).append(':').append(left % 60 < 10 ? "0" : "").append(left % 60);
        }
        emptyStateController.updateAdProgress(text.toString());
    }

    /**
     * @param rendererPending rows the frame pass just drew that still have a spring to drain;
     *                         computed by the caller so it sees the same viewport as the pass
     */
    private void updateFrameDemand(boolean playingNow, boolean rendererPending) {
        boolean processing = document != null && document.processingPending;
        boolean continuous = (playingNow && document != null && !staticDoc)
                || !loadingTrackId.isEmpty()
                || processing
                || localReprocessController.isProcessing()
                || !rowCascades.isEmpty()
                || !loadEntrances.isEmpty()
                // Both of these are stepped from the vsync callback itself, so dropping out of
                // continuous mode while either is live freezes the motion part-way.
                || scrollSpring != null
                || scrollInProgress
                || rendererPending;
        if (continuous) {
            visuallySettledFrames = 0;
            handler.removeCallbacks(idleFrameProbe);
            frameScheduler.setContinuous(true);
            return;
        }
        if (++visuallySettledFrames < SETTLE_FRAMES_BEFORE_IDLE) return;
        if (frameScheduler.isContinuous()) {
            frameScheduler.setContinuous(false);
            handler.removeCallbacks(idleFrameProbe);
            handler.postDelayed(idleFrameProbe, IDLE_FRAME_PROBE_MS);
        }
    }

    private RomanizationOptions romanizationOptions() {
        LyricsRenderConfig cfg = renderConfig == null ? LyricsRenderConfig.read(activity, config) : renderConfig;
        return new RomanizationOptions(chineseMode(), koreanMode(), cfg.chineseTones, cyrillicMode(), cfg.cyrillicKeepSigns);
    }

    private boolean showRomanization() {
        return renderConfig != null && renderConfig.transliterationEnabled
                && transliterationSession != null && transliterationSession.showRomanization();
    }

    private boolean showTranslation() {
        return renderConfig != null && renderConfig.translationEnabled && showTranslation;
    }

    private String japaneseReadingMode() {
        return transliterationSession == null ? "" : transliterationSession.japaneseReadingMode();
    }

    private String chineseMode() {
        return transliterationSession == null ? "" : transliterationSession.chineseMode();
    }

    private String koreanMode() {
        return transliterationSession == null ? "" : transliterationSession.koreanMode();
    }

    private String cyrillicMode() {
        return transliterationSession == null ? "" : transliterationSession.cyrillicMode();
    }

    private void applyRenderConfigChanges(String reason, boolean fromPanelClose) {
        autoResumeFollow = config.get(Settings.AUTO_RESUME_FOLLOW);
        slideAnimationEnabled = readSlideEnabled();
        if (scrollController != null) {
            float nextAnchor = resolveFocusAnchorFraction();
            boolean anchorChanged = nextAnchor != lastAppliedAnchorFraction;
            lastAppliedAnchorFraction = nextAnchor;
            scrollController.setAnchorFraction(nextAnchor);
            applyLyricsScrollPadding();
            // A plain setAnchorFraction() only changes where the NEXT active-line change lands -
            // dragging the layout editor's focus handle otherwise moves the visible handle while
            // the real active line just sits wherever it already was, only catching up once
            // playback naturally advances to another line. Re-scroll to the current line now so
            // the handle and the real position never visibly disagree.
            if (anchorChanged) rescrollActiveRowToAnchor();
        }
        LyricsRenderConfig next = LyricsRenderConfig.read(activity, config);
        LyricsRenderConfig.Diff diff = renderConfig == null ? null : renderConfig.diff(next);
        if (diff == null || !diff.hasChanges) {
            renderConfig = next;
            return;
        }

        renderConfig = next;
        if (diff.needsLocalReprocess || diff.needsTranslationReprocess || diff.needsToggleOnly) {
            updateToggleVisuals();
        }
        if (diff.japaneseModeConfigChanged || diff.chineseModeConfigChanged
                || diff.koreanModeConfigChanged || diff.cyrillicModeConfigChanged) {
            transliterationSession.applyConfig(next);
        }
        if (diff.japaneseModeConfigChanged || diff.chineseModeConfigChanged
                || diff.koreanModeConfigChanged || diff.cyrillicModeConfigChanged) {
            updateToggleVisuals();
        }
        if (diff.needsBackgroundToggle) {
            ambientController.applySettings(next.backgroundStyle, next.forceDarkBackground,
                    next.extraDarkBackground);
            SpotifyTrack track = host.getCurrentTrackSafely();
            if (track != null) ambientController.updateForTrack(track, () -> running);
        }
        if (diff.needsTranslationReprocess) {
            reprocessTranslationForConfig(reason);
        }
        if (diff.needsLocalReprocess) {
            reprocessLocalModeOnly(reason);
        }
        if (diff.needsRowRemount || (fromPanelClose && diff.hasChanges && !diff.onlyTimingChanged)) {
            clearRenderedLineViews();
            renderWindowForActive(followState.activeIndex() >= 0 ? followState.activeIndex() : currentWindowAnchor());
        } else if (diff.needsToggleOnly) {
            updateToggleVisuals();
        }
    }

    private void loadLyrics(SpotifyTrack track, String id) {
        dbg("NativeSpicyShellView.loadLyrics", "id=" + safe(id) + " track=" + (track == null ? "null" : safe(track.uri)));
        if (lyricRequest != null) lyricRequest.close();
        loadingTrackId = id;
        ++NativeSpicyLyricsHook.fetchGeneration;
        lyricRequest = host.fetchLyrics(track, new LyricsResultCallback() {
            @Override
            public void onSuccess(LyricsDocument doc) {
                if (!running || doc == null) return;
                // One-shot delivery covers the synchronous existing-document case. The observer
                // remains authoritative for later processing upgrades; the shared gate makes a
                // following observer delivery supersede this candidate without a double render.
                prepareAndScheduleDocument(track == null ? "" : track.uri, doc);
            }

            @Override
            public void onError(String error) {
                handler.post(() -> {
                    if (!running) return;
                    SpotifyTrack current = host.getCurrentTrackSafely();
                    String currentId = current == null ? "" : trackIdFromUri(current.uri);
                    if (!id.equals(currentId)) return;
                    // A document may already be on screen (cache-first instant render) while
                    // the rest of the chain was probing for an upgrade. A terminal fallback
                    // failure must not replace rendered lyrics with an error screen.
                    if (document != null && !document.lines.isEmpty()) {
                        setTextIfChanged(status, "Lyrics refresh failed: " + safe(error));
                        return;
                    }
                    showError(error);
                });
            }
        });
    }

    private void prepareAndScheduleDocument(String trackUri, LyricsDocument doc) {
        String id = trackIdFromUri(trackUri);
        LyricsSurfaceDocumentGate.Candidate candidate = documentGate.offer(id);
        ++NativeSpicyLyricsHook.fetchGeneration;
        RomanizationOptions loadOptions = romanizationOptions();
        boolean loadRomanization = showRomanization();
        LyricsDocumentProcessor.applyProcessedCachePreservingAi(activity.getApplicationContext(), doc,
                loadOptions, GOOGLE_PROCESSING_VERSION);
        // The session's Sound artifact already carries span readings and the composer applies them.
        // Only derive here for a document published before the Sound lane produced anything.
        if (LyricsDocumentProcessor.needsSurfaceLocalRomanization(doc)) {
            populateLocalSegmentRomanization(doc, loadRomanization, loadOptions);
        }
        LyricTimeline.applySyncedRows(doc);
        handler.post(() -> {
            SpotifyTrack current = host.getCurrentTrackSafely();
            String currentId = current == null ? "" : trackIdFromUri(current.uri);
            if (!running || !documentGate.accepts(candidate, currentId)) {
                if (running && !id.equals(currentId)) {
                    XpLog.log(TAG + " stale lyrics ignored id=" + id + " current=" + currentId);
                }
                return;
            }
            // A derived-layer completion republishes the whole document. When the canonical base
            // is unchanged, absorb only the new reading/translation text into the document already
            // on screen: swapping the object would rebuild the timeline and reset the active row
            // and scroll position mid-song.
            LyricsDocument mounted = document;
            LyricsDocumentProcessor.DerivedMergeResult merge =
                    LyricsDocumentProcessor.mergeDerivedPublication(mounted, doc);
            if (merge != LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE) {
                loadingTrackId = "";
                if (merge == LyricsDocumentProcessor.DerivedMergeResult.CHANGED) {
                    LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LAYER_LOCAL_UPDATE);
                    refreshSecondaryRows("");
                }
                observeAiRequestFeedback(mounted);
                // Provenance and failure state can change without changing displayed lyric text.
                // Refresh controls after every same-base publication so a failed paid request is
                // never hidden merely because Google/deterministic fallback stayed on screen.
                updateToggleVisuals();
                return;
            }
            cancelLoadEntranceAnimation();
            document = doc;
            pendingLoadEntrance = true;
            loadingTrackId = "";
            observeAiRequestFeedback(document);
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DOCUMENT_REBUILD);
            renderDocument(false);
            XpLog.log(TAG + " lyrics loaded source=" + doc.fetchSource + " provider="
                    + doc.provider + " type=" + doc.type + " lines=" + doc.lines.size());
        });
    }

    private boolean isCurrentProcessingResult(String id, int generation, LyricsDocument snapshot) {
        if (!running || document != snapshot) return false;
        if (generation > 0 && generation < NativeSpicyLyricsHook.fetchGeneration) return false;
        return isBlank(id) || id.equals(trackIdFromUri(lastUri));
    }

    private void populateLocalSegmentRomanization(LyricsDocument doc) {
        populateLocalSegmentRomanization(doc, showRomanization(), romanizationOptions());
    }

    private void populateLocalSegmentRomanization(LyricsDocument doc, boolean enabled,
                                                   RomanizationOptions options) {
        if (!enabled || doc == null || doc.lines == null || doc.lines.isEmpty()) return;
        String fullText = LyricsDocumentProcessor.collectText(doc);
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            LyricsLocalRomanizer.populateLocalSegmentRomanization(options, doc, line, fullText);
        }
    }

    private void rerenderKeepingPosition(String message) {
        if (document == null) return;
        SpotifyTrack current = host.getCurrentTrackSafely();
        long pos = current == null ? -1 : playbackClock.getPosition(current, host.isPlayerActuallyPlaying());
        long lyricPos = pos < 0 ? pos : adjustedLyricPositionMs(pos);
        int previousActive = followState.activeIndex();
        rebuildWithReflow(this::renderDocument);
        // renderDocument() resets the active line; without restoring it the next scroll counted
        // as a first activation and jumped instead of gliding.
        if (previousActive >= 0 && followState.activeIndex() < 0) followState.setActiveIndex(previousActive);
        if (current != null) setActiveLine(LyricTimeline.findPrimaryActiveRow(document.appliedLines, lyricPos), lyricPos, current);
        if (!isBlank(message)) status.setText(message);
    }

    private void showLoading(String message) {
        rowMountController.reset();
        followState.resetActive();
        emptyStateController.showLoading(lyricsScroll, lyricsColumn, message);
    }

    private void showError(String error) {
        document = null;
        loadingTrackId = "";
        rowMountController.reset();
        followState.resetActive();
        emptyStateController.showError(lyricsColumn, error);
        status.setText("Lyrics error: " + safe(error));
    }

    private void renderDocument() {
        renderDocument(true);
    }

    private void renderDocument(boolean prepareDocument) {
        dbg("NativeSpicyShellView.renderDocument", "doc=" + (document == null ? "null" : document.fetchSource + "/" + document.type + "/" + document.lines.size()));
        updateToggleVisuals();
        ensureLyricsColumnScaffold();
        clearRenderedLineViews();
        followState.resetActive();
        if (document == null || document.lines.isEmpty()) {
            showError("Empty lyrics response");
            return;
        }
        staticDoc = LyricsRenderMode.isStatic(document);
        if (prepareDocument) {
            populateLocalSegmentRomanization(document);
            LyricTimeline.applySyncedRows(document);
        }
        if (document.appliedLines.isEmpty()) {
            showError("Empty applied lyrics rows");
            return;
        }
        sourceFooter.setText(!isBlank(document.songWriters)
                ? "Written by " + document.songWriters
                : "lyrics provided by " + sourceProviderLabel(document.provider));
        rowMountController.markDirty();
        // A fresh document doesn't necessarily start at line 0: playback can already be mid-song
        // (resuming, a late-arriving fetch, a source swap). Anchoring the very first render at the
        // top regardless meant the load-lift reveal below always animated the topmost rows, then a
        // moment later the follow-tick's own setActiveLine() re-anchored the window to the real
        // position with no reveal at all - the rows the user actually sees just popped in. Anchor
        // to wherever playback already is so the reveal targets the rows that are really shown.
        int initialAnchor = 0;
        if (!staticDoc) {
            SpotifyTrack currentForAnchor = host.getCurrentTrackSafely();
            long anchorPos = currentForAnchor == null ? -1
                    : playbackClock.getPosition(currentForAnchor, host.isPlayerActuallyPlaying());
            if (anchorPos >= 0) {
                int anchorActive = LyricTimeline.findPrimaryActiveRow(
                        document.appliedLines, adjustedLyricPositionMs(anchorPos));
                if (anchorActive >= 0) initialAnchor = anchorActive;
            }
        }
        renderWindowForActive(initialAnchor);
        loadEntranceAnchor = initialAnchor;
        if (pendingLoadEntrance) {
            pendingLoadEntrance = false;
            if (config != null && Boolean.TRUE.equals(config.get(Settings.LOAD_LIFT_ANIMATION))
                    && "Apple Music".equals(config.get(Settings.ANIMATION_STYLE))) {
                // Hide now, synchronously, before this mount is ever measured or drawn. The reveal
                // itself can only start once the rows have a height, i.e. one layout pass later -
                // by which point they have already been painted at full brightness for a frame,
                // and the fade then started from a flash.
                loadEntranceAttempts = 0;
                hideRowsForPendingEntrance();
                lyricsFrame.post(this::startLoadEntranceAnimation);
            }
        }
        if (resetScrollForNextDocument) {
            resetScrollForNextDocument = false;
            // A cache hit can replace the short loading state before ScrollView gets a layout pass
            // that clamps the previous song's scrollY. Reset after mounting the new document so
            // its opening row starts from the center-padding position even during lyric pre-roll.
            // Only when the document really does open at its first row: when playback is already
            // mid-song, scrolling to the top here just to have the first follow tick snap back
            // down to the anchor put a visible lurch right under the load reveal.
            scrollSpring = null;
            if (initialAnchor <= 0) lyricsScroll.scrollTo(0, 0);
        }
    }

    /** Rows rise from just behind their own resting position and fade in, staggered slightly by
     *  distance from the row playback is actually on. Each row's start offset is a fraction of its
     *  own measured height rather than one flat pixel value shared by every row - a fixed offset
     *  reads as the whole column pinned to some arbitrary edge, while a per-row one reads as each
     *  line arriving from just behind where it already is.
     *
     *  <p>The reveal is registered here and driven by {@link #stepLoadEntrance} on the shared vsync
     *  tick. It deliberately does not animate View.alpha: the renderer rewrites every row's alpha
     *  each frame from its own opacity springs, so a ViewPropertyAnimator on the same property gets
     *  stomped mid-flight (the load flicker), and ending the fade at a flat 1 would then snap back
     *  down to the row's real dimmed opacity. Publishing a 0..1 factor the renderer multiplies in
     *  makes the reveal a fade toward each row's natural brightness instead. */
    private void startLoadEntranceAnimation() {
        if (document == null) return;
        // A cache hit can mount rows before the first measure pass. Retry on the next frame
        // instead of silently skipping the reveal because every row is still height zero.
        boolean hasMeasuredRow = false;
        for (int i : rowMountController.mountedIndices()) {
            AppliedLine line = i >= 0 && i < document.appliedLines.size()
                    ? document.appliedLines.get(i) : null;
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row != null && row.getHeight() > 0) {
                hasMeasuredRow = true;
                break;
            }
        }
        if (!hasMeasuredRow) {
            // Bounded: the rows are being held invisible until this succeeds, so a document that
            // never measures has to fall back to simply showing them rather than waiting forever.
            if (++loadEntranceAttempts > LOAD_ENTRANCE_MAX_ATTEMPTS) {
                revealRowsAfterFailedEntrance();
                return;
            }
            hideRowsForPendingEntrance();
            lyricsFrame.postOnAnimation(this::startLoadEntranceAnimation);
            return;
        }
        clearRowCascade();
        clearLoadEntrance();
        // Stagger outward from the row playback is on rather than top-down: that row is where the
        // eye already is, so it arriving first reads as the screen settling around it. A top-down
        // order instead makes the focused line the last thing to appear.
        int focus = followState.activeIndex() >= 0 ? followState.activeIndex() : loadEntranceAnchor;
        // Park the column on that row now, while everything is still transparent. The first follow
        // tick would otherwise do it a frame or two into the fade, which reads as the lyrics
        // sliding into position as they appear instead of simply appearing where they belong.
        placeScrollAtRowInstantly(focus);
        float timeScale = 1f / cascadeSpeedMultiplier();
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line == null) continue;
            View row = rowMountController.attachedRowView(line);
            if (row == null || row.getHeight() <= 0) continue;
            // Proportional to the row within a tight band: every row travels a similar, clearly
            // visible distance regardless of whether it wraps to two lines.
            float travel = Math.max(dp(18), Math.min(dp(30), row.getHeight() * 0.4f));
            float distance = focus < 0 ? i : cascadeDistance(line, i, focus);
            // Outward from the focused row with a shrinking gap, like the line slide's stagger.
            float delay = Math.min(LOAD_REVEAL_MAX_DELAY_SEC, LOAD_REVEAL_STAGGER_SEC
                    * (1f - (float) Math.pow(0.88f, distance)) / (1f - 0.88f)) * timeScale;
            loadEntrances.put(line, new LoadEntrance(travel, delay, timeScale));
            LyricsLineViewState.setEntranceProgress(line, 0f, dp(LOAD_REVEAL_BLUR_DP));
            applyLoadEntranceFrame(row, 0f, travel);
            row.setHasTransientState(true);
        }
        if (!loadEntrances.isEmpty()) {
            frameScheduler.setContinuous(true);
            frameScheduler.requestFrame();
        }
    }

    /** Drops every mounted row to fully transparent ahead of a reveal that hasn't been able to
     *  start yet, so nothing is ever painted at full brightness first. */
    private void hideRowsForPendingEntrance() {
        if (document == null || document.appliedLines == null) return;
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line == null) continue;
            View row = rowMountController.attachedRowView(line);
            if (row == null) continue;
            LyricsLineViewState.setEntranceProgress(line, 0f);
            // Direct write as well: the renderer only picks the factor up on its next pass, and
            // this can run between two of them.
            row.setAlpha(0f);
        }
    }

    private void revealRowsAfterFailedEntrance() {
        if (document == null || document.appliedLines == null) return;
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line == null) continue;
            LyricsLineViewState.setEntranceProgress(line, 1f);
            lineVisualController.invalidate(line);
        }
        frameScheduler.requestFrame();
    }

    /** Puts the anchor row on its focus point with no animation, for use before anything is
     *  visible. No-op unless that row is mounted and measured. */
    private void placeScrollAtRowInstantly(int index) {
        if (document == null || scrollController == null || lyricsScroll == null) return;
        if (index <= 0 || index >= document.appliedLines.size()) return;
        View row = rowMountController.attachedRowView(document.appliedLines.get(index));
        if (row == null || row.getHeight() <= 0 || lyricsScroll.getHeight() <= 0) return;
        int target = Math.max(0, scrollController.centeredScrollTarget(row, dp(56)));
        if (Math.abs(target - lyricsScroll.getScrollY()) <= 2) return;
        scrollSpring = null;
        applyingLyricScroll = true;
        lyricsScroll.scrollTo(0, target);
        applyingLyricScroll = false;
    }

    /** Writes one reveal frame. Owns only a property the frame renderer never touches: the row's
     *  direct children's translation (the Apple slide owns the row's own).
     *  {@code progress} may overshoot 1 slightly: that is the spring settling. */
    private void applyLoadEntranceFrame(View row, float progress, float travelPx) {
        if (row == null) return;
        float childOffset = travelPx * (1f - progress);
        ViewGroup rowGroup = row instanceof ViewGroup ? (ViewGroup) row : null;
        int rowChildCount = rowGroup == null ? 0 : rowGroup.getChildCount();
        for (int childIndex = 0; childIndex < rowChildCount; childIndex++) {
            rowGroup.getChildAt(childIndex).setTranslationY(childOffset);
        }
    }

    private void stepLoadEntrance(float deltaSeconds) {
        if (loadEntrances.isEmpty()) return;
        Iterator<Map.Entry<AppliedLine, LoadEntrance>> it = loadEntrances.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<AppliedLine, LoadEntrance> entry = it.next();
            AppliedLine line = entry.getKey();
            LoadEntrance entrance = entry.getValue();
            View row = rowMountController.attachedRowView(line);
            if (row == null) {
                // Scrolled out of the mounted window mid-reveal. Finish it on paper so the row is
                // never remounted still invisible or still offset.
                finishLoadEntrance(line, LyricsLineViewState.rowView(line), entrance);
                it.remove();
                continue;
            }
            float step = deltaSeconds;
            if (entrance.delayRemaining > 0f) {
                entrance.delayRemaining -= deltaSeconds;
                if (entrance.delayRemaining > 0f) continue;
                // Carry the leftover into the first real step, so a stagger shorter than one frame
                // still orders the rows instead of rounding up to the next frame boundary.
                step = Math.min(deltaSeconds, -entrance.delayRemaining);
                entrance.delayRemaining = 0f;
            }
            entrance.elapsed += step;
            float t = entrance.elapsed / entrance.timeScale;
            if (t >= LOAD_REVEAL_DURATION_SEC) {
                finishLoadEntrance(line, row, entrance);
                it.remove();
                continue;
            }
            float fadeT = Math.min(1f, t / LOAD_REVEAL_FADE_SEC);
            float fade = 1f - (1f - fadeT) * (1f - fadeT) * (1f - fadeT);
            LyricsLineViewState.setEntranceProgress(line, fade, (1f - fade) * dp(LOAD_REVEAL_BLUR_DP));
            applyLoadEntranceFrame(row, loadRevealSpring(t), entrance.travelPx);
        }
    }

    /** Step response of the reveal spring at {@code t} seconds: 0 at rest below, 1 in place. */
    private static float loadRevealSpring(float t) {
        double omega = 2d * Math.PI * LOAD_REVEAL_FREQUENCY_HZ;
        double zeta = LOAD_REVEAL_DAMPING;
        double omegaD = omega * Math.sqrt(1d - zeta * zeta);
        double envelope = Math.exp(-zeta * omega * t);
        return (float) (1d - envelope * (Math.cos(omegaD * t)
                + zeta * omega / omegaD * Math.sin(omegaD * t)));
    }

    private void finishLoadEntrance(AppliedLine line, View row, LoadEntrance entrance) {
        LyricsLineViewState.setEntranceProgress(line, 1f);
        applyLoadEntranceFrame(row, 1f, entrance == null ? 0f : entrance.travelPx);
        if (row != null) row.setHasTransientState(false);
    }

    private void clearLoadEntrance() {
        if (loadEntrances.isEmpty()) return;
        for (Map.Entry<AppliedLine, LoadEntrance> entry : loadEntrances.entrySet()) {
            finishLoadEntrance(entry.getKey(), LyricsLineViewState.rowView(entry.getKey()),
                    entry.getValue());
        }
        loadEntrances.clear();
    }

    private void ensureLyricsColumnScaffold() {
        if (topStaticSpacer.getParent() == lyricsColumn) return;
        lyricsColumn.removeAllViews();
        lyricsColumn.addView(topStaticSpacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(topVirtualSpacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(mountedRowsHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(bottomVirtualSpacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(sourceFooter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void renderWindowForActive(int active) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        ensureLyricsColumnScaffold();
        int anchor = currentWindowAnchor();
        if (active >= 0 && !followState.isHoldingNow()) anchor = active;
        boolean rendered = rowMountController.renderWindow(
                document.appliedLines,
                anchor,
                followState.activeIndex(),
                this::ensureRowView,
                this::onNewRowMounted,
                this::onRowUnmounted,
                this::styleLine,
                this::rowHeightForIndex);
        if (staticDoc) {
            // Render-window reuse normally skips visual work. Static rows must still reset from
            // any stale timed state before they are shown again.
            frameRenderer.applyStatic(document, rowMountController.mountedIndices(), mountedRowsHost);
        } else if (rendered) {
            flushStyleBatch();
        }
    }

    private View ensureRowView(AppliedLine line) {
        return rowMountController.rowViewOrBuild(line, this::buildLyricRow);
    }

    private void onNewRowMounted(AppliedLine line) {
        lineVisualController.invalidate(line);
        remeasureLine(line);
        View row = rowMountController.attachedRowView(line);
        if (row == null) return;
        
        LoadEntrance load = loadEntrances.get(line);
        if (load != null) {
            // Part of an active reveal: start hidden so it doesn't flash.
            applyLoadEntranceFrame(row, 0f, load.travelPx);
            return;
        }

        // A scroll can remount rows mid-cascade: join in place from the cascade's current
        // offset instead of snapping to zero. Anything without a live cascade must land flat -
        // a row that was unmounted while displaced would otherwise reappear still offset.
        RowCascade cascade = rowCascades.get(line);
        row.setTranslationY(cascade == null ? 0f : cascade.spring.position());
    }

    private int currentWindowAnchor() {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return 0;
        if (followState.isHoldingNow()) return currentViewportAnchor();
        return followState.activeIndex() >= 0 ? followState.activeIndex() : currentViewportAnchor();
    }

    private int currentViewportAnchor() {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return 0;
        return scrollController == null
                ? 0
                : scrollController.viewportAnchor(rowHeightPrefix(), document.appliedLines.size());
    }

    private void updateVirtualSpacerHeights() {
        rowMountController.updateSpacerHeights(
                document == null ? null : document.appliedLines,
                this::rowHeightForIndex);
    }

    private int[] rowHeightPrefix() {
        return rowMountController.rowHeightPrefix(
                document == null ? null : document.appliedLines,
                this::rowHeightForIndex);
    }

    private void invalidateRowHeightPrefix() {
        rowMountController.invalidateRowHeightPrefix();
    }

    private int rowHeightForIndex(int index) {
        int estimate = (int) (dp(LYRIC_ESTIMATED_ROW_HEIGHT_DP) * renderConfig.lineSpacingMultiplier * renderConfig.lyricsTextSizeMultiplier);
        return rowMountController.rowHeightForIndex(
                document == null ? null : document.appliedLines,
                index,
                estimate,
                dp(18),
                showRomanization(),
                showTranslation());
    }

    private void remeasureMountedRows() {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        boolean changed = rowMountController.remeasureMountedRows(document.appliedLines, this::remeasureLine);
        if (changed) updateVirtualSpacerHeights();
    }

    private boolean remeasureLine(AppliedLine line) {
        return rowMountController.remeasureLine(line);
    }

    private void scheduleScrollWindowRender() {
        if (scrollWindowRenderScheduled) return;
        scrollWindowRenderScheduled = true;
        Runnable work = () -> {
            scrollWindowRenderScheduled = false;
            if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
            int anchor = currentViewportAnchor();
            if (shouldRemountWindowForViewport(anchor)) renderWindowForActive(anchor);
        };
        if (Build.VERSION.SDK_INT >= 16) lyricsScroll.postOnAnimation(work);
        else lyricsScroll.post(work);
    }

    private boolean shouldRemountWindowForViewport(int anchor) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return false;
        return rowMountController.shouldRemountWindowForViewport(
                document.appliedLines, anchor, NativeRuntime.LYRIC_WINDOW_EDGE_BUFFER);
    }

    private void scheduleScrollSettleRemeasure() {
        if (!running) return;
        lastScrollEventMs = SystemClock.elapsedRealtime();
        if (scrollSettleScheduled) return;
        scrollSettleScheduled = true;
        handler.postDelayed(scrollSettleRunnable, SCROLL_SETTLE_REMEASURE_DELAY_MS);
    }

    private LinearLayout buildLyricRow(AppliedLine line) {
        LyricsSurfaceRowPlanner.RowPlan rowPlan = LyricsSurfaceRowPlanner.plan(
                line,
                document,
                LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(
                        renderConfig, showRomanization(), showTranslation(), japaneseReadingMode()));
        return rowViewFactory.build(rowPlan.line, rowPlan.options,
                this::segmentRomanizedText, () -> {
            invalidateRowHeightPrefix();
            updateVirtualSpacerHeights();
        });
    }

    private String segmentRomanizedText(AppliedLine line, SyllableSegment segment,
                                         String fullText) {
        return LyricsLocalRomanizer.romanizeDisplaySegment(
                romanizationOptions(), document, line, segment, fullText);
    }

    private boolean isJapaneseLine(AppliedLine line) {
        return com.eza.spicyex.lyrics.LyricsDisplayMode.isJapaneseLine(line);
    }

    // -- translation / reading reflow ------------------------------------------------------

    private static final long REFLOW_SETTLE_MS = 450L;
    private static final float REFLOW_STAGGER_SEC = 0.03f;
    private static final float REFLOW_MAX_DELAY_SEC = 0.18f;
    private static final int SECONDARY_REVEAL_RISE_DP = 8;
    private static final int SECONDARY_REVEAL_BLUR_DP = 6;
    private static final long SECONDARY_REVEAL_MS = 560L;
    private static final long SECONDARY_HIDE_MS = 160L;
    private static final android.animation.TimeInterpolator SECONDARY_REVEAL_EASE =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    /**
     * Runs a rebuild that adds, removes or changes translation/reading rows without the column
     * teleporting. Every mounted row slides from where it was drawn to where it now sits, on the
     * same spring as a line advance (a FLIP: measure, rebuild, offset each row back to its old
     * place, let it spring home), and translation/reading text that was not on screen before
     * fades, rises and sharpens into place instead of popping in.
     */
    private void rebuildWithReflow(Runnable rebuild) {
        if (document == null || lyricsScroll == null || !lyricsScroll.isLaidOut()
                || !loadEntrances.isEmpty()) {
            rebuild.run();
            styleRowsNow();
            return;
        }
        // Keyed by row index, not row object: a full re-render rebuilds every AppliedLine, and an
        // identity-keyed map then matched nothing - no row got a spring and all of them jumped.
        // Layout positions in scroll-content coordinates: independent of the scroll (a fling in
        // progress keeps carrying the rows) and of any cascade translation already running on them
        // (that motion carries on; only the layout change is added on top of it).
        Map<Integer, Float> origins = new java.util.HashMap<>();
        Map<Integer, java.util.Set<String>> shown = new java.util.HashMap<>();
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row == null || row.getHeight() <= 0) continue;
            origins.put(i, contentTop(row));
            java.util.Set<String> signatures = new java.util.HashSet<>();
            for (View view : LyricsLineViewState.secondaryViews(line)) signatures.add(viewSignature(view));
            shown.put(i, signatures);
        }
        // A reflow still settling from a previous toggle must not also react to this rebuild's
        // layout change: it and this rebuild's own anchoring would both correct the scroll, and the
        // column jumped by twice the change.
        reflowTrackUntil = 0L;
        reflowTops.clear();
        reflowAnchorLine = null;
        int rowCount = document.appliedLines.size();
        int focus = followState.activeIndex();
        // The anchor is the line at the screen's focus position - the one being sung while the view
        // follows the song, otherwise (reading ahead, dragging) whichever line sits at the focus
        // point. Its layout position before the rebuild lets the scroll follow it exactly, so the
        // space for translations opens and closes around the focus, not from the top down.
        int anchorIndex = focus;
        View focusRowBefore = anchorIndex >= 0 && anchorIndex < rowCount
                ? rowMountController.attachedRowView(document.appliedLines.get(anchorIndex)) : null;
        if (focusRowBefore == null || followState.isHoldingNow() || !isRowOnScreen(focusRowBefore)) {
            int atFocus = nearestAppliedLineIndexAt(lyricsScroll.getHeight() * resolveFocusAnchorFraction());
            if (atFocus >= 0) {
                anchorIndex = atFocus;
                focusRowBefore = rowMountController.attachedRowView(document.appliedLines.get(anchorIndex));
            }
        }
        final int anchor = anchorIndex;
        float focusTopBefore = focusRowBefore == null || focusRowBefore.getHeight() <= 0
                ? Float.NaN : contentTop(focusRowBefore);
        LyricsDocument before = document;
        // Until the one-shot below has anchored, the regular scroll anchor must stay out: with no
        // cascade running it held the sung line through the same layout change, and the reflow's
        // own anchoring then moved the column a second time - the line jumped by the full change.
        reflowPending = true;
        rebuild.run();
        styleRowsNow();
        // Running cascades (a line advance, an earlier reflow) are kept: the reflow below adds its
        // own displacement to them instead of cutting them off mid-motion.
        if (document != before || origins.isEmpty() || document.appliedLines.size() != rowCount) {
            reflowPending = false;
            clearRowCascade();
            return;
        }
        lyricsScroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        android.view.ViewTreeObserver observer = lyricsScroll.getViewTreeObserver();
                        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                        reflowPending = false;
                        if (document == before) {
                            int anchored = anchorScrollToFocus(anchor, focusTopBefore);
                            applyReflow(origins, shown, focus, anchored);
                            startReflowTracking(anchor);
                        }
                        return true;
                    }
                });
    }

    /**
     * Gives freshly rebuilt rows their dim, blur and scale right away. A rebuild replaces the row
     * views, and the new ones carry no style until the renderer's next vsync pass; when the
     * rebuild lands between frames the traversal in between drew them once at full brightness
     * and sharp - the single flash as a translation's space opened up.
     */
    private void styleRowsNow() {
        try {
            if (document == null || renderConfig == null
                    || document.appliedLines == null || document.appliedLines.isEmpty()) return;
            if (staticDoc) {
                frameRenderer.applyStatic(document, rowMountController.mountedIndices(), mountedRowsHost);
                return;
            }
            SpotifyTrack track = host.getCurrentTrackSafely();
            if (track == null) return;
            long lyricPos = adjustedLyricPositionMs(
                    playbackClock.getPosition(track, host.isPlayerActuallyPlaying()));
            frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                    renderConfig, lyricPos, followState.activeIndex(), 0.001f,
                    followState.isHoldingNow());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps the line being sung where it is on screen across a translation/reading rebuild:
     * rows above it gaining or losing text move its layout position, and the scroll follows by the
     * same amount before the first frame is drawn. The FLIP pass that runs next measures screen
     * positions, so the focused row gets (almost) no offset and keeps its own ongoing motion, and
     * only the rows around it part or close. An in-flight follow glide is shifted along with it.
     */
    /** @return the scroll change it applied (0 when none), for the reflow to compensate. */
    private int anchorScrollToFocus(int focus, float focusTopBefore) {
        if (Float.isNaN(focusTopBefore) || lyricsScroll == null || document == null
                || focus < 0 || focus >= document.appliedLines.size()) return 0;
        View row = rowMountController.attachedRowView(document.appliedLines.get(focus));
        if (row == null || row.getHeight() <= 0) return 0;
        int shift = Math.round(contentTop(row) - focusTopBefore);
        if (shift == 0) return 0;
        // The end-of-content limit still reflects the old layout; near the end of a song it
        // clamped this scroll, and the whole column slid by the part that was cut off.
        updateScrollEndLimit();
        int beforeScroll = lyricsScroll.getScrollY();
        applyingLyricScroll = true;
        lyricsScroll.scrollTo(0, Math.max(0, beforeScroll + shift));
        applyingLyricScroll = false;
        int applied = lyricsScroll.getScrollY() - beforeScroll;
        if (scrollSpring != null) scrollSpring.shift(applied);
        return applied;
    }

    private boolean isRowOnScreen(View row) {
        if (row == null || lyricsScroll == null || row.getHeight() <= 0) return false;
        float top = contentTop(row) + row.getTranslationY() - lyricsScroll.getScrollY();
        return top + row.getHeight() > 0 && top < lyricsScroll.getHeight();
    }

    private void applyReflow(Map<Integer, Float> origins,
                             Map<Integer, java.util.Set<String>> shown, int focus, int anchoredScroll) {
        float speedMul = cascadeSpeedMultiplier();
        float frequency = ROW_CASCADE_FREQUENCY_HZ * speedMul * elasticFrequencyMultiplier();
        float damping = elasticDamping(ROW_CASCADE_DAMPING);
        float stagger = REFLOW_STAGGER_SEC / speedMul;
        float maxDelay = REFLOW_MAX_DELAY_SEC / speedMul;
        int viewport = lyricsScroll.getHeight();
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row == null) continue;
            float rows = focus < 0 ? 0f : cascadeDistance(line, i, focus);
            float delay = Math.min(maxDelay, rows * stagger);
            Float from = origins.get(i);
            // Always, not only with the Apple slide on: making room for a translation or reading
            // is layout moving under the reader, and springing it reads as the lines parting
            // rather than the column jumping.
            RowCascade existing = rowCascades.get(line);
            if (from != null && row.getHeight() > 0) {
                // Undo this row's layout move on screen for now (and the anchor scroll, which moved
                // every row), then let it spring home.
                float top = contentTop(row);
                float offset = (from - top) + anchoredScroll;
                if (Math.abs(offset) < viewport) {
                    if (existing != null) {
                        if (Math.abs(offset) >= 0.5f) existing.bump(offset);
                        existing.followLayout = true;
                        existing.layoutTop = top;
                        row.setTranslationY(existing.spring.position());
                    } else if (Math.abs(offset) >= 0.5f) {
                        RowCascade cascade = new RowCascade(offset, LyricCascadeProfile.forRow(
                                rows, frequency, damping, stagger, maxDelay));
                        cascade.followLayout = true;
                        cascade.layoutTop = top;
                        rowCascades.put(line, cascade);
                        row.setTranslationY(offset);
                    }
                }
            } else if (existing != null) {
                // A remounted row starts with no translation; put it back where its motion is.
                row.setTranslationY(existing.spring.position());
            }
            java.util.Set<String> before = shown.get(i);
            for (View view : LyricsLineViewState.secondaryViews(line)) {
                if (before == null || !before.contains(viewSignature(view))) {
                    revealSecondaryView(view, delay);
                }
            }
        }
        if (reflowLayoutListener == null) {
            reflowLayoutListener = () -> {
                followReflowLayout();
                return true;
            };
            lyricsScroll.getViewTreeObserver().addOnPreDrawListener(reflowLayoutListener);
        }
        frameScheduler.requestFrame();
    }

    private android.view.ViewTreeObserver.OnPreDrawListener reflowLayoutListener;

    /**
     * A rebuilt row rarely lands in one layout pass: spacer heights, wrapping and secondary text
     * settle over the next few frames. Each of those moves would show as a jump under a spring
     * that only knew the first pass's position, which is the stutter a translation toggle had.
     * Runs before every draw and folds any further layout move of a reflowing row into its spring,
     * so the row keeps gliding from where it is actually drawn.
     */
    // After a reflow the layout keeps settling for a few frames: rebuilt rows re-measure, readings
    // and translations finish laying out, and the spacers standing in for unmounted rows are
    // re-estimated. Each of those passes moved everything under the reader - the column wobbled
    // and could drift far from the line being sung. For a short while after the reflow every
    // pass is absorbed: the anchor line is held in place through the scroll, and any other row
    // that moved relative to it takes that movement into its spring (getting one if it had none).
    private static final long REFLOW_TRACK_MS = 1200L;
    private long reflowTrackUntil;
    private AppliedLine reflowAnchorLine;
    private float reflowAnchorTop = Float.NaN;
    private final Map<AppliedLine, Float> reflowTops = new java.util.IdentityHashMap<>();

    private void startReflowTracking(int anchorIndex) {
        if (document == null || lyricsScroll == null) return;
        reflowTops.clear();
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row != null) reflowTops.put(line, contentTop(row));
        }
        reflowAnchorLine = anchorIndex >= 0 && anchorIndex < document.appliedLines.size()
                ? document.appliedLines.get(anchorIndex) : null;
        View anchorRow = reflowAnchorLine == null ? null : rowMountController.attachedRowView(reflowAnchorLine);
        reflowAnchorTop = anchorRow == null ? Float.NaN : contentTop(anchorRow);
        reflowTrackUntil = SystemClock.uptimeMillis() + REFLOW_TRACK_MS;
        reflowActiveIndex = followState.activeIndex();
    }

    /**
     * Tracking only covers the rebuild's own layout settling. A line advance while it runs moves
     * the mounted window and its spacers - the normal scroll anchoring handles that; following
     * it here as "layout settling" threw the column a whole screen away from the sung line.
     */
    private int reflowActiveIndex = -1;
    private boolean reflowPending;

    private boolean reflowTracking() {
        if (reflowTrackUntil == 0L) return false;
        if (SystemClock.uptimeMillis() >= reflowTrackUntil || document == null
                || followState.activeIndex() != reflowActiveIndex) {
            reflowTrackUntil = 0L;
            return false;
        }
        return true;
    }

    private void followReflowLayout() {
        if (!reflowTracking()) {
            if (!reflowTops.isEmpty()) reflowTops.clear();
            reflowAnchorLine = null;
            return;
        }
        // 1. The anchor line stays where it is on screen: follow its layout move with the scroll.
        int applied = 0;
        View anchorRow = reflowAnchorLine == null ? null : rowMountController.attachedRowView(reflowAnchorLine);
        if (anchorRow != null && !Float.isNaN(reflowAnchorTop)) {
            float top = contentTop(anchorRow);
            int shift = Math.round(top - reflowAnchorTop);
            if (shift != 0 && !followState.isHoldingNow()) {
                updateScrollEndLimit();
                int beforeScroll = lyricsScroll.getScrollY();
                applyingLyricScroll = true;
                lyricsScroll.scrollTo(0, Math.max(0, beforeScroll + shift));
                applyingLyricScroll = false;
                applied = lyricsScroll.getScrollY() - beforeScroll;
                if (scrollSpring != null) scrollSpring.shift(applied);
            }
            reflowAnchorTop = top;
        }
        // 2. Every other row: whatever it moved beyond that goes into its spring.
        float speedMul = cascadeSpeedMultiplier();
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row == null) continue;
            float top = contentTop(row);
            Float last = reflowTops.put(line, top);
            if (last == null || line == reflowAnchorLine) continue;
            float moved = (top - last) - applied;
            if (Math.abs(moved) <= 0.5f) continue;
            RowCascade cascade = rowCascades.get(line);
            if (cascade == null) {
                cascade = new RowCascade(-moved, LyricCascadeProfile.forRow(0f,
                        ROW_CASCADE_FREQUENCY_HZ * speedMul * elasticFrequencyMultiplier(),
                        elasticDamping(ROW_CASCADE_DAMPING), 0f, 0f));
                cascade.followLayout = true;
                rowCascades.put(line, cascade);
            } else {
                cascade.bump(-moved);
                cascade.followLayout = true;
            }
            row.setTranslationY(cascade.spring.position());
        }
        frameScheduler.requestFrame();
    }

    /** A row's layout position in scroll-content coordinates: moves with layout, not scrolling. */
    /** Row the viewport is pinned to across layout changes, and where it sat last frame. */
    private AppliedLine scrollAnchorLine;
    private float scrollAnchorTop = Float.NaN;

    /**
     * Scroll anchoring, as browsers do it: when a layout pass moves the row the column is focused
     * on - rows above it re-measuring as readings and translations arrive, a wrap re-plan, the
     * virtual spacers above the mount window being replaced by real rows - the scroll position
     * moves by the same amount before that frame is drawn, so the focused row stays exactly where
     * it was on screen. Without this each of those passes showed as the lyrics jumping, then the
     * follow logic scrolling back, several times while a song loaded. Deliberate scrolls change
     * scrollY, not the row's layout position, so they are never counteracted.
     */
    /** Tells the scroll view where the content ends: the last lyric line resting on the focus
     *  position, not the source credit and bottom padding below it scrolling on past. */
    private void updateScrollEndLimit() {
        if (!(lyricsScroll instanceof com.eza.spicyex.lyrics.ElasticScrollView)) return;
        com.eza.spicyex.lyrics.ElasticScrollView scroll =
                (com.eza.spicyex.lyrics.ElasticScrollView) lyricsScroll;
        int limit = Integer.MAX_VALUE;
        if (document != null && document.appliedLines != null && !document.appliedLines.isEmpty()
                && scrollController != null) {
            View last = rowMountController.attachedRowView(
                    document.appliedLines.get(document.appliedLines.size() - 1));
            if (last != null && last.getHeight() > 0) {
                limit = Math.max(0, scrollController.centeredScrollTarget(last, dp(56)));
            }
        }
        scroll.setScrollEndLimit(limit);
    }


    private void markTranslationToggled() {
        secondaryRowUpdater.markTranslationToggled();
    }

    private void holdScrollAnchor() {
        updateScrollEndLimit();
        // Runs after layout, before the draw: rows just (re)built get their scale pivot before
        // they are ever drawn - otherwise their first frame shrinks toward the centre and the
        // next snaps back to the edge (the sideways jitter when translations/readings toggle).
        if (document != null && document.appliedLines != null) {
            for (int i : rowMountController.mountedIndices()) {
                if (i >= 0 && i < document.appliedLines.size()) {
                    LyricsLineViewState.ensureScalePivots(document.appliedLines.get(i));
                }
            }
        }
        AppliedLine line = null;
        View row = null;
        if (running && document != null && document.appliedLines != null
                && !followState.isHoldingNow() && rowCascades.isEmpty() && !reflowTracking()
                && !reflowPending) {
            int index = followState.activeIndex() >= 0 ? followState.activeIndex() : loadEntranceAnchor;
            if (index >= 0 && index < document.appliedLines.size()) {
                line = document.appliedLines.get(index);
                row = rowMountController.attachedRowView(line);
            }
        }
        if (row == null || row.getHeight() <= 0) {
            scrollAnchorLine = null;
            scrollAnchorTop = Float.NaN;
            return;
        }
        float top = contentTop(row);
        if (line == scrollAnchorLine && !Float.isNaN(scrollAnchorTop)) {
            int shift = Math.round(top - scrollAnchorTop);
            if (shift != 0) {
                int before = lyricsScroll.getScrollY();
                applyingLyricScroll = true;
                lyricsScroll.scrollTo(0, Math.max(0, before + shift));
                applyingLyricScroll = false;
                if (scrollSpring != null) scrollSpring.shift(lyricsScroll.getScrollY() - before);
            }
        }
        scrollAnchorLine = line;
        scrollAnchorTop = top;
    }

    private float contentTop(View row) {
        View host = (View) row.getParent();
        return row.getTop() + (host == null ? 0 : host.getTop());
    }

    /** What a translation/reading view is showing, to tell a new one from one that was there. */
    private static String viewSignature(View view) {
        if (view instanceof TextView) return String.valueOf(((TextView) view).getText());
        StringBuilder out = new StringBuilder(view.getClass().getSimpleName());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.append('|').append(viewSignature(group.getChildAt(i)));
            }
        }
        return out.toString();
    }

    private void revealSecondaryView(View view, float delaySeconds) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(-dp(SECONDARY_REVEAL_RISE_DP));
        boolean blur = Build.VERSION.SDK_INT >= 31;
        float blurPx = dp(SECONDARY_REVEAL_BLUR_DP);
        if (blur) setBlur(view, blurPx);
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.round(delaySeconds * 1000f))
                .setDuration(SECONDARY_REVEAL_MS)
                .setInterpolator(SECONDARY_REVEAL_EASE)
                .setUpdateListener(blur
                        ? animation -> setBlur(view, blurPx * (1f - animation.getAnimatedFraction()))
                        : null)
                .withEndAction(() -> {
                    if (blur) setBlur(view, 0f);
                })
                .start();
    }

    private static void setBlur(View view, float radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return;
        view.setRenderEffect(radiusPx < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(
                radiusPx, radiusPx, android.graphics.Shader.TileMode.DECAL));
    }

    /** Fades the given rows out before a rebuild removes them, so hiding translations reads as
     *  them leaving rather than vanishing. */
    private Runnable pendingSecondaryRebuild;
    private List<View> pendingLeavingViews = java.util.Collections.emptyList();

    private void hideThenRebuild(List<View> leaving, Runnable rebuild) {
        // A toggle while the previous one is still fading out replaces it: one rebuild for the
        // latest state, and anything that was fading out comes back (it may be staying after all;
        // if not, the rebuild removes it).
        if (pendingSecondaryRebuild != null) {
            handler.removeCallbacks(pendingSecondaryRebuild);
            pendingSecondaryRebuild = null;
            for (View view : pendingLeavingViews) {
                view.animate().cancel();
                view.animate().alpha(1f).translationY(0f).setStartDelay(0L)
                        .setDuration(SECONDARY_HIDE_MS).setInterpolator(SECONDARY_REVEAL_EASE)
                        .setUpdateListener(null).start();
            }
            pendingLeavingViews = java.util.Collections.emptyList();
        }
        if (leaving.isEmpty()) {
            rebuildWithReflow(rebuild);
            return;
        }
        LyricsDocument before = document;
        for (View view : leaving) {
            view.animate().cancel();
            view.animate().alpha(0f).translationY(-dp(SECONDARY_REVEAL_RISE_DP) / 2f)
                    .setStartDelay(0L).setDuration(SECONDARY_HIDE_MS)
                    .setInterpolator(SECONDARY_REVEAL_EASE).setUpdateListener(null).start();
        }
        pendingLeavingViews = leaving;
        pendingSecondaryRebuild = () -> {
            pendingSecondaryRebuild = null;
            pendingLeavingViews = java.util.Collections.emptyList();
            if (!running) return;
            if (document == before) rebuildWithReflow(rebuild);
            else rebuild.run();
        };
        handler.postDelayed(pendingSecondaryRebuild, SECONDARY_HIDE_MS);
    }

    private List<View> mountedTranslationViews() {
        List<View> views = new java.util.ArrayList<>();
        if (document == null) return views;
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            View view = LyricsLineViewState.translationView(document.appliedLines.get(i));
            if (view != null && view.isAttachedToWindow()) views.add(view);
        }
        return views;
    }

    private void refreshSecondaryRows(String message) {
        LyricsDocument snapshot = document;
        if (snapshot == null || snapshot.appliedLines == null || snapshot.appliedLines.isEmpty()) {
            if (!isBlank(message)) setTextIfChanged(status, message);
            return;
        }
        rebuildWithReflow(this::rebuildSecondaryRowsInPlace);
        if (!isBlank(message)) setTextIfChanged(status, message);
    }

    /** Adds, removes or updates translation/reading rows on the rows already mounted. */
    private void rebuildSecondaryRowsInPlace() {
        LyricsDocument snapshot = document;
        if (snapshot == null || snapshot.appliedLines == null || snapshot.appliedLines.isEmpty()) return;
        boolean structureChanged = secondaryRowUpdater.refresh(snapshot, showRomanization(),
                showTranslation(), japaneseReadingMode());
        if (structureChanged) {
            invalidateRowHeightPrefix();
            rowMountController.markDirty();
            renderWindowForActive(currentWindowAnchor());
        }
    }

    private void clearRenderedLineViews() {
        if (document != null && document.appliedLines != null) {
            for (AppliedLine line : document.appliedLines) {
                secondaryRowUpdater.clear(line);
            }
        }
        rowMountController.reset();
    }

    private void flushStyleBatch() {
        styleBatcher.flush();
    }

    private void clearPendingStyleWrites() {
        styleBatcher.clearPendingWrites();
    }

    private void seekNearestLineAt(float yInScroll) {
        if (staticDoc) return; // unsynced lyrics have no real per-line timing → tapping must not seek
        int bestIndex = nearestAppliedLineIndexAt(yInScroll);
        if (bestIndex >= 0) seekToLine(document.appliedLines.get(bestIndex), bestIndex);
    }

    /** Shared by tap-to-seek and long-press-to-share: which mounted lyric row sits closest to a
     *  scroll-view touch Y. Unlike {@link #seekNearestLineAt}, sharing works on unsynced (static)
     *  documents too, so this carries no {@code staticDoc} guard of its own. */
    private int nearestAppliedLineIndexAt(float yInScroll) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return -1;
        int contentY = scrollController == null ? Math.round(yInScroll) : scrollController.contentYForTouch(yInScroll);
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        // Compare in scroll-content (lyricsColumn) coordinates, mirroring the auto-scroll fix
        // in setActiveLine: row.getTop() is relative to mountedRowsHost, which sits below the
        // static + virtual spacers. Only mounted rows have valid coordinates — unmounted rows
        // keep a cached rowView with stale layout from an earlier window placement.
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (line == null || line.dotLine) continue;
            View row = rowMountController.attachedRowView(line);
            if (row == null) continue;
            int center = scrollController == null ? 0 : scrollController.rowCenterInContent(row);
            int distance = Math.abs(contentY - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /** Long-press-to-share: quotes the nearest lyric row, or falls back to a plain track card
     *  when there's no usable line under the touch (no document, or an empty/dot row). */
    /** The mounted lyric row actually under a scroll-view touch Y (with a little slack), or -1. */
    private int appliedLineIndexUnder(float yInScroll) {
        if (document == null || document.appliedLines == null || scrollController == null) return -1;
        int contentY = scrollController.contentYForTouch(yInScroll);
        int slack = dp(8);
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row == null || row.getHeight() <= 0) continue;
            int center = scrollController.rowCenterInContent(row);
            int half = row.getHeight() / 2 + slack;
            if (contentY >= center - half && contentY <= center + half) return i;
        }
        return -1;
    }

    /** The lyric row under a finger that may be about to long-press it (share), and where. */
    private View pressedLyricRow;
    private float pressedLyricDownY;
    private final Runnable shrinkPressedLyric = () -> {
        View row = pressedLyricRow;
        if (row == null || !row.isAttachedToWindow()) return;
        // Held down, the line sinks a little, as in Apple Music, until the sheet opens. (No
        // cancel(): a new scale animation replaces only the scale, not the row's other motion.)
        row.animate().scaleX(0.94f).scaleY(0.94f)
                .setDuration(Math.max(160, android.view.ViewConfiguration.getLongPressTimeout() - 60))
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    };

    /**
     * Apple-Music-style press feedback for long-press-to-share: a line held (not scrolled) shrinks
     * slightly, and springs back when released, scrolled, or when the share sheet opens.
     */
    private void trackPressedLyric(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                releasePressedLyric();
                if (config == null || !Boolean.TRUE.equals(config.get(Settings.LONG_PRESS_SHARE))) return;
                int index = appliedLineIndexUnder(event.getY());
                if (index < 0 || document == null) return;
                AppliedLine line = document.appliedLines.get(index);
                if (line == null || line.dotLine || line.text == null || line.text.trim().isEmpty()) return;
                View row = rowMountController.attachedRowView(line);
                if (row == null || row.getWidth() <= 0) return;
                row.setPivotX(row.getWidth() / 2f);
                row.setPivotY(row.getHeight() / 2f);
                pressedLyricRow = row;
                pressedLyricDownY = event.getY();
                // A beat later, so a flick that starts on a line does not pulse it.
                row.postDelayed(shrinkPressedLyric, 90);
                break;
            }
            case android.view.MotionEvent.ACTION_MOVE:
                if (pressedLyricRow != null && Math.abs(event.getY() - pressedLyricDownY) >= dp(10)) {
                    releasePressedLyric();
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                releasePressedLyric();
                break;
            default:
                break;
        }
    }

    private void releasePressedLyric() {
        View row = pressedLyricRow;
        pressedLyricRow = null;
        if (row == null) return;
        row.removeCallbacks(shrinkPressedLyric);
        row.animate().scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
    }

    /**
     * The share sheet over the lyrics. Long-pressing while the lyrics were blurred and moving was
     * heavy: every blurred row kept re-rendering its blur under the sheet as it opened. The
     * lyrics now hold still from the press until the sheet closes, and once the sheet's backdrop
     * is opaque neither they nor the animated background are drawn at all.
     */
    private void onShareSheet(boolean showing, boolean covering) {
        lyricsFrozen = showing;
        if (covering != lyricsCovered) {
            lyricsCovered = covering;
            if (covering) ambientController.pauseAnimation();
            else ambientController.resumeAnimation();
            invalidate();
        }
    }

    @Override
    protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
        if (lyricsCovered && shareCardController != null && child != shareCardController.overlayView()) {
            return false;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private void shareLyricLineAt(float yInScroll) {
        releasePressedLyric();
        if (config == null || !Boolean.TRUE.equals(config.get(Settings.LONG_PRESS_SHARE))) return;
        SpotifyTrack track = currentTrackThrottled();
        if (track == null) return;
        // Only a press on a lyric line opens the sheet. The nearest-row lookup used to pick a line
        // however far away the touch was, so holding the empty space below the last line (or the
        // credits) opened it too.
        if (document != null && document.appliedLines != null && !document.appliedLines.isEmpty()
                && appliedLineIndexUnder(yInScroll) < 0) {
            return;
        }
        // Don't share during ads - only share actual songs
        if (isAdTrack(track)) return;
        if (shareCardController == null) {
            shareCardController = new LyricsShareCardController(activity);
            shareCardController.setBackgroundSnapshot(
                    (w, h) -> ambientController.snapshotBackground(w, h));
            shareCardController.setSheetListener(this::onShareSheet);
        }
        Bitmap art = SpotifyArtworkCache.snapshotLarge(track.imageId, track.uri, dp(420));
        int index = appliedLineIndexUnder(yInScroll);
        AppliedLine line = (document != null && index >= 0 && index < document.appliedLines.size())
                ? document.appliedLines.get(index) : null;
        if (line != null && line.text != null && !line.text.trim().isEmpty()) {
            shareCardController.showForLine(this, document, track, art, index,
                    rowMountController.attachedRowView(line));
        } else {
            shareCardController.showTrackCard(this, track, art);
        }
    }

    private void seekToLine(AppliedLine line, int index) {
        if (line == null || line.startMs < 0) return;
        skipAckGapStartMs = -1; // a manual tap-seek revokes the skip acknowledgement
        long target = renderConfig == null ? Math.max(0, line.startMs) : renderConfig.playbackPositionForLyricMs(line.startMs);
        followState.clearHold();
        boolean ok = host.seekSpotifyTo(target);
        if (ok) {
            playbackClock.forcePosition(target, host.isPlayerActuallyPlaying());
            long lyricTarget = adjustedLyricPositionMs(target);
            setActiveLine(index, lyricTarget, host.getCurrentTrackSafely(), true);
            frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                    renderConfig, lyricTarget, index, 1f / 60f, false, 0, Integer.MAX_VALUE);
            XpLog.log(TAG + " seek line index=" + index + " ms=" + target + " lyricMs=" + lyricTarget);
        } else {
            followState.holdUntil(SystemClock.elapsedRealtime() + 2500);
            XpLog.log(TAG + " seek line failed index=" + index + " ms=" + target);
        }
    }

    private void setActiveLine(int index, long positionMs, SpotifyTrack track) {
        setActiveLine(index, positionMs, track, false);
    }

    private void setActiveLine(int index, long positionMs, SpotifyTrack track, boolean instantScroll) {
        int old = followState.activeIndex();
        boolean placeFirstActiveInstantly = LyricsScrollController.shouldScrollInstantly(
                instantScroll, old);
        if (document != null && index >= 0) {
            boolean activeVisible = rowMountController.containsIndex(index);
            if (!activeVisible || followState.isHoldingNow()) {
                renderWindowForActive(index);
                if (!activeVisible) old = -1;
            }
        }
        followState.setActiveIndex(index);
        updateRomanizationGlyph();
        styleLine(old, false);
        styleLine(index, true);
        flushStyleBatch();
        if (document == null || index < 0 || index >= document.appliedLines.size()) return;

        AppliedLine line = document.appliedLines.get(index);
        String currentReading = line.readingRenderPlan == null ? "" : line.readingRenderPlan.joinedDisplayText;
        CurrentLyricState.updateLine(track, document.provider, document.language, line.dotLine ? "" : line.text, line.dotLine ? "" : currentReading, line.dotLine ? "" : line.translatedText, positionMs, index, host.isPlayerActuallyPlaying(), "active");

        if (followState.isHoldingNow()) return;
        View row = rowMountController.attachedRowView(line);
        if (row == null) return;
        scrollActiveRowWhenLaidOut(index, line, row, 0, placeFirstActiveInstantly);
    }

    /** Re-runs the scroll-to-active-row step for whichever line is active right now, without
     *  waiting for the active index to actually change - see applyRenderConfigChanges(). */
    private void rescrollActiveRowToAnchor() {
        if (document == null || followState.isHoldingNow()) return;
        int index = followState.activeIndex();
        if (index < 0 || index >= document.appliedLines.size()) return;
        AppliedLine line = document.appliedLines.get(index);
        if (line == null) return;
        View row = rowMountController.attachedRowView(line);
        if (row == null) return;
        scrollActiveRowWhenLaidOut(index, line, row, 0, false);
    }

    private void scrollActiveRowWhenLaidOut(int index, AppliedLine line, View row, int attempt, boolean instantScroll) {
        if (!running || followState.isHoldingNow()) return;
        if (document == null || line == null || row == null || lyricsScroll == null) return;
        if (index != followState.activeIndex() || row.getParent() != mountedRowsHost) return;

        if ((row.getHeight() <= 0 || lyricsScroll.getHeight() <= 0 || row.isLayoutRequested())
                && attempt < 3) {
            final boolean[] retried = {false};
            View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (retried[0]) return;
                    retried[0] = true;
                    row.removeOnLayoutChangeListener(this);
                    lyricsScroll.post(() -> scrollActiveRowWhenLaidOut(index, line, row, attempt + 1, instantScroll));
                }
            };
            row.addOnLayoutChangeListener(listener);
            lyricsScroll.postDelayed(() -> {
                if (retried[0]) return;
                retried[0] = true;
                row.removeOnLayoutChangeListener(listener);
                scrollActiveRowWhenLaidOut(index, line, row, attempt + 1, instantScroll);
            }, 80);
            return;
        }

        // post(), deliberately NOT postOnAnimation(). This block reads the row's real position out
        // of the view tree (centeredScrollTarget -> offsetDescendantRectToMyCoords) to decide where
        // to scroll. A plain post runs after the current frame's traversal, so any layout requested
        // earlier in the frame - a window remount, a re-styled active line, a secondary text
        // arriving - has already been performed and those coordinates are current.
        // postOnAnimation runs BEFORE the traversal instead, so it would read pre-layout
        // coordinates, jump the scroll to a target computed from them, and then have the layout
        // move the rows out from under both that scroll and the cascade's compensating
        // translations. The column lands a few pixels off for exactly one frame and is corrected on
        // the next, which is seen as the lyrics flickering every time a line advances.
        lyricsScroll.post(() -> {
            if (!running || followState.isHoldingNow()) return;
            if (index != followState.activeIndex() || row.getParent() != mountedRowsHost) return;
            if (remeasureLine(line)) {
                updateVirtualSpacerHeights();
                if (attempt < 3) {
                    lyricsScroll.post(() -> scrollActiveRowWhenLaidOut(index, line, row, attempt + 1, instantScroll));
                    return;
                }
            }
            int target = scrollController == null ? 0 : scrollController.centeredScrollTarget(row, dp(56));
            scrollToActiveTarget(Math.max(0, target), instantScroll);
        });
    }

    /** Apple-owned slide: on only while the Animation style is Apple Music and its row is on. */
    private boolean readSlideEnabled() {
        if (config == null) return false;
        return "Apple Music".equals(config.get(Settings.ANIMATION_STYLE))
                && Boolean.TRUE.equals(config.get(Settings.LINE_SLIDE_ANIMATION));
    }

    /** Resolves Settings#LYRICS_FOCUS_POSITION to an anchor fraction for scrollController.
     *  "Auto" preserves the pre-existing behavior (raised only while the Apple-style line-slide
     *  animation is on); Top/Center/Bottom pin it explicitly. Raised/lowered anchors overlap the
     *  chrome or strand space at the opposite edge in landscape, so landscape always centers
     *  regardless of the setting. */
    private float resolveFocusAnchorFraction() {
        String pos = config == null ? "Auto" : config.get(Settings.LYRICS_FOCUS_POSITION);
        float fraction;
        if ("Top".equals(pos)) {
            fraction = LyricsScrollController.RAISED_ANCHOR_FRACTION;
        } else if ("Bottom".equals(pos)) {
            fraction = LyricsScrollController.LOWERED_ANCHOR_FRACTION;
        } else if ("Center".equals(pos)) {
            fraction = LyricsScrollController.CENTER_ANCHOR_FRACTION;
        } else if ("Custom".equals(pos)) {
            int percent = config == null ? Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT.defaultValue
                    : config.get(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT);
            fraction = Math.max(0f, Math.min(1f, percent / 100f));
        } else {
            fraction = slideAnimationEnabled
                    ? LyricsScrollController.RAISED_ANCHOR_FRACTION
                    : LyricsScrollController.CENTER_ANCHOR_FRACTION;
        }
        if (isLandscape()) fraction = LyricsScrollController.CENTER_ANCHOR_FRACTION;
        return fraction;
    }

    private void scrollToActiveTarget(int target, boolean instant) {
        if (lyricsScroll == null) return;
        int oldScroll = lyricsScroll.getScrollY();
        int delta = target - oldScroll;
        if (instant || Math.abs(delta) <= 2) {
            boolean moved = Math.abs(delta) > 2;
            returnToCurrentPending = false;
            scrollSpring = null;
            clearScrollSubpixel();
            applyingLyricScroll = true;
            lyricsScroll.scrollTo(0, target);
            applyingLyricScroll = false;
            if (moved) clearRowCascade();
            return;
        }
        if (slideAnimationEnabled && Math.abs(delta) <= glideCapPx()) {
            // The ordinary line-to-line advance with the Apple slide on: jump the scroll position
            // instantly and let the per-row cascade alone carry the motion. Running a second
            // animation on the scroll position at the same time here fights the cascade instead of
            // complementing it - two independently-timed animations driving the same rows reads
            // as choppy/disjointed rather than smooth, and at this distance the cascade alone was
            // already the "spring" motion, not something that also needed a scroll under it.
            // Hand the cascade the delta the ScrollView ACTUALLY moved, not the requested one:
            returnToCurrentPending = false;
            scrollSpring = null;
            clearScrollSubpixel();
            // Prime the row offsets before moving the ScrollView. Applying the compensation after
            // scrollTo() leaves one traversal frame where the column has jumped and the rows have
            // not caught up yet, which is the visible Apple-slide twitch at each lyric boundary.
            startRowCascade(target - oldScroll);
            applyingLyricScroll = true;
            lyricsScroll.scrollTo(0, target);
            applyingLyricScroll = false;
            return;
        }
        // Everything else - the Apple slide turned off, or a jump too far for the cascade to
        // plausibly cover (resuming after reading ahead, a seek) - is carried by one spring that
        // owns the real scroll position.
        //
        // This is also why ScrollView's own smoothScrollTo() is no longer used for the
        // slide-disabled path. It collapses to an instant scrollBy() whenever it is called within
        // ANIMATED_SCROLL_GAP (250ms) of the previous one, so on any song whose lines land closer
        // together than that - and on the burst of advances right after a seek - it silently
        // stopped animating and hard-cut between lines. It also restarts its own interpolation from
        // scratch on every call, where retargeting an in-flight spring keeps the existing velocity
        // and absorbs a second jump arriving mid-glide instead of snapping.
        // Reflow springs (a translation/reading appearing) are layout motion independent of the
        // scroll; they carry on under the glide rather than snapping.
        clearRowCascadeExceptReflow();
        if (scrollSpring != null) {
            scrollSpring.setGoal(target);
            return;
        }
        // A return to the playing line after the user has scrolled away is the one jump that is a
        // deliberate, user-asked-for move rather than the screen quietly keeping up with the song,
        // so it gets its own, livelier profile, launched with real speed.
        boolean returning = returnToCurrentPending;
        returnToCurrentPending = false;
        // Bound how far the glide actually travels. Resuming follow after reading ahead, or a
        // seek across the song, can be thousands of pixels away; springing the whole way makes
        // the column blur past at a speed that reads as a glitch rather than a scroll. Jumping
        // the excess first and springing a fixed, viewport-relative remainder gives every jump
        // the same legible arrival no matter how far it started.
        int start = oldScroll;
        float maxTravel = springTravelCapPx();
        if (Math.abs(target - start) > maxTravel) {
            start = target + Math.round(Math.signum(start - target) * maxTravel);
            applyingLyricScroll = true;
            lyricsScroll.scrollTo(0, Math.max(0, start));
            applyingLyricScroll = false;
            start = lyricsScroll.getScrollY();
        }
        clearScrollSubpixel();
        float frequency = returning
                ? RETURN_SPRING_FREQUENCY_HZ * cascadeSpeedMultiplier() * elasticFrequencyMultiplier()
                : scrollSpringFrequency(target - start);
        float damping = returning
                ? elasticDamping(RETURN_SPRING_DAMPING)
                : scrollSpringDamping();
        scrollSpring = new com.eza.spicyex.lyrics.Spring(start, frequency, damping);
        scrollSpring.setGoal(target);
        if (returning) {
            // Leaving with real speed rather than from a standstill is what makes the return read
            // as the column being thrown back to the song instead of easing there: the motion is
            // quickest at the start, where the distance is, and arrives with almost none left.
            scrollSpring.setVelocity(Math.signum(target - start) * Math.min(
                    Math.abs(target - start) * RETURN_LAUNCH_VELOCITY_PER_PX,
                    RETURN_MAX_LAUNCH_VELOCITY_PX_PER_SEC));
        }
    }

    /** Stiffer for a short hop, softer for a long one. A single frequency either made adjacent
     *  lines feel like the column was lagging behind the song, or sent a cross-song jump flying. */
    private float scrollSpringFrequency(float distancePx) {
        int viewport = lyricsScroll == null ? 0 : lyricsScroll.getHeight();
        float span = viewport > 0 ? viewport : ROW_CASCADE_MAX_OFFSET_PX;
        float reach = Math.min(1f, Math.abs(distancePx) / span);
        float hz = SCROLL_SPRING_NEAR_FREQUENCY_HZ
                + (SCROLL_SPRING_FREQUENCY_HZ - SCROLL_SPRING_NEAR_FREQUENCY_HZ) * reach;
        return hz * cascadeSpeedMultiplier() * elasticFrequencyMultiplier();
    }

    /** With the Apple slide on, the scroll spring is the same family of motion as the row cascade
     *  and may overshoot a little. With it off the user has opted out of that character, so the
     *  glide is critically damped and simply arrives. */
    private float scrollSpringDamping() {
        return slideAnimationEnabled ? elasticDamping(SCROLL_SPRING_DAMPING) : 1f;
    }

    private float cascadeSpeedMultiplier() {
        float speedPct = config != null ? (float) config.get(Settings.APPLE_CASCADE_SPEED) : 100f;
        return Math.max(0.5f, Math.min(2f, speedPct / 100f));
    }

    /** Maps the editor's strength control to elasticity (damping), not travel speed. */
    private float elasticDamping(float baseDamping) {
        float strengthPct = config != null ? (float) config.get(Settings.APPLE_SPRING_STRENGTH) : 100f;
        // Strength > 100% reduces damping for more bounce; < 100% increases it toward 1.0 (dead).
        float elasticity = (strengthPct - 100f) / 100f;
        float adjusted = baseDamping - elasticity * 0.25f;
        return Math.max(0.45f, Math.min(0.95f, adjusted));
    }

    /** Compensates for the "slower" feel of low-damping bouncy springs by slightly raising 
     *  the base frequency as strength increases. */
    private float elasticFrequencyMultiplier() {
        float strengthPct = config != null ? (float) config.get(Settings.APPLE_SPRING_STRENGTH) : 100f;
        if (strengthPct <= 100f) return 1f;
        float extra = (strengthPct - 100f) / 100f;
        // Raised from 0.15 to 0.28 to keep the "snappiness" even when damping is very low.
        return 1f + extra * 0.28f;
    }

    /** How much of a far jump the scroll spring actually animates, in px. */
    private float springTravelCapPx() {
        int viewport = lyricsScroll == null ? 0 : lyricsScroll.getHeight();
        if (viewport <= 0) return ROW_CASCADE_MAX_OFFSET_PX;
        return viewport * 1.15f;
    }

    private void stepScrollSpring(float deltaSeconds) {
        if (scrollSpring == null || lyricsScroll == null) return;
        if (followState.isHoldingNow()) {
            // A finger landed on the list mid-glide without moving it yet, so the scroll listener
            // never fired. Drop the spring rather than scrolling out from under the touch.
            scrollSpring = null;
            clearScrollSubpixel();
            return;
        }
        float value = scrollSpring.step(Math.max(0.001f, Math.min(0.05f, deltaSeconds)));
        applyScrollPosition(value);
        if (scrollSpring.isAtRest(1f, 4f)) {
            scrollSpring = null;
            applyScrollPosition(Math.round(value));
        }
    }

    /**
     * Writes a fractional scroll position: whole pixels to the ScrollView, the leftover fraction to
     * the content's own translation.
     *
     * <p>{@code scrollTo} takes an int, so a spring crawling the last few pixels home was being
     * quantised to whole-pixel steps - it would sit still for two or three frames, jump a pixel,
     * sit still again. That stair-stepping is most of what reads as the glide not being smooth,
     * and it is worst exactly where the eye is most likely to be watching: the slow settle at the
     * end. Carrying the remainder on the column's translation gives the motion real sub-pixel
     * resolution without fighting the ScrollView for ownership of the scroll position.
     */
    private void applyScrollPosition(float value) {
        if (lyricsScroll == null) return;
        int whole = (int) Math.floor(value);
        applyingLyricScroll = true;
        lyricsScroll.scrollTo(0, Math.max(0, whole));
        applyingLyricScroll = false;
        if (lyricsColumn == null) return;
        // Only meaningful while the ScrollView actually honoured the requested position; at the
        // ends of the song it clamps, and carrying a remainder there would drift the content off
        // its own edge.
        float fraction = lyricsScroll.getScrollY() == whole ? value - whole : 0f;
        if (Math.abs(lyricsColumn.getTranslationY() + fraction) > 0.01f) {
            lyricsColumn.setTranslationY(-fraction);
        }
    }

    /** Puts the column back on whole pixels; anything that takes the scroll position over from the
     *  spring must call this or the leftover fraction stays applied forever. */
    private void clearScrollSubpixel() {
        if (lyricsColumn != null && lyricsColumn.getTranslationY() != 0f) {
            lyricsColumn.setTranslationY(0f);
        }
    }

    private float glideCapPx() {
        boolean apple = renderConfig != null && renderConfig.appleStyle;
        if (!apple) return ROW_CASCADE_MAX_OFFSET_PX;
        if (lyricsScroll != null && lyricsScroll.getHeight() > 0) {
            return lyricsScroll.getHeight() * (isLandscape() ? 0.6f : 0.45f);
        }
        return ROW_CASCADE_MAX_OFFSET_PX;
    }

    private void startRowCascade(float scrollDelta) {
        if (document == null || document.appliedLines == null || Math.abs(scrollDelta) < 0.5f) return;
        if (Math.abs(scrollDelta) > glideCapPx()) return;
        int activeIndex = followState.activeIndex();
        float speedMul = cascadeSpeedMultiplier();
        float stagger = ROW_CASCADE_STAGGER_SEC / speedMul;
        float maxDelay = ROW_CASCADE_MAX_DELAY_SEC / speedMul;
        float frequency = ROW_CASCADE_FREQUENCY_HZ * speedMul * elasticFrequencyMultiplier();
        float damping = elasticDamping(ROW_CASCADE_DAMPING);
        // Rows the move leaves behind wait their turn; the focus and everything ahead of it leave
        // together. Scrolling forward that is the rows below the focus, scrolling back the rows
        // above it.
        float direction = Math.signum(scrollDelta);
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = rowMountController.attachedRowView(line);
            if (row == null) continue;
            float trailing = direction * signedCascadeDistance(line, i, activeIndex);
            // Every row starts displaced by the FULL scroll delta: the ScrollView has already
            // carried the content the other way by exactly this much, so this is what leaves the
            // column visually untouched at t=0. Any per-row amplitude is a hard jump of the
            // difference on the very frame the scroll lands.
            float initialOffset = scrollDelta;
            // A lyric can advance again before the previous cascade has settled. Folding the new
            // displacement into the row's running spring keeps its velocity and phase, where a new
            // spring would restart it from a standstill and hitch.
            RowCascade existing = rowCascades.get(line);
            if (existing != null) {
                existing.bump(initialOffset);
                // Re-assert the position on this frame too. The bumped row is skipped by
                // stepRowCascade() while it is still inside its stagger delay, so without this the
                // View keeps last frame's translation and the scroll jump shows through on it.
                row.setTranslationY(existing.spring.position());
                continue;
            }
            rowCascades.put(line, new RowCascade(initialOffset, LyricCascadeProfile.forRow(
                    trailing, frequency, damping, stagger, maxDelay)));
            row.setTranslationY(initialOffset);
        }
    }

    /**
     * Background/dual-vocal rows are inserted directly after their lead row in appliedLines.
     * Using raw list indices therefore gives the lower row a different delay, so the upper and
     * lower voices visibly arrive apart. Measure rows by their source lyric line instead: a lead
     * and its paired background row share one cascade phase, while adjacent source lines remain
     * one step apart.
     */
    private float cascadeDistance(AppliedLine line, int rowIndex, int activeIndex) {
        return Math.abs(signedCascadeDistance(line, rowIndex, activeIndex));
    }

    /** Source lines from the focused one to this row; positive below it, negative above. */
    private float signedCascadeDistance(AppliedLine line, int rowIndex, int activeIndex) {
        if (activeIndex < 0 || document == null || document.appliedLines == null
                || activeIndex >= document.appliedLines.size()) {
            return 0f;
        }
        AppliedLine active = document.appliedLines.get(activeIndex);
        if (line != null && active != null && line.sourceLine != null
                && line.sourceLine == active.sourceLine) {
            return 0f;
        }
        if (line != null && active != null && line.sourceLine != null
                && active.sourceLine != null && document.lines != null) {
            int lineIndex = document.lines.indexOf(line.sourceLine);
            int activeLineIndex = document.lines.indexOf(active.sourceLine);
            if (lineIndex >= 0 && activeLineIndex >= 0) {
                return lineIndex - activeLineIndex;
            }
        }
        return rowIndex - activeIndex;
    }

    private void stepRowCascade(float deltaSeconds) {
        if (rowCascades.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        Iterator<Map.Entry<AppliedLine, RowCascade>> it = rowCascades.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<AppliedLine, RowCascade> entry = it.next();
            View row = rowMountController.attachedRowView(entry.getKey());
            RowCascade cascade = entry.getValue();
            if (row == null) {
                // Unmounted mid-cascade. Zero the detached View as well, or it comes back into the
                // window still carrying whatever offset it was at when it left.
                View detached = LyricsLineViewState.rowView(entry.getKey());
                if (detached != null) detached.setTranslationY(0f);
                it.remove();
                continue;
            }
            if (now - cascade.startedAtMs > ROW_CASCADE_MAX_LIFETIME_MS) {
                // Safety valve for a spring that somehow never settles. Collapse what's left of
                // the offset over a few frames instead of zeroing it outright: a hard reset from a
                // still-visible displacement is exactly the pop this cutoff exists to prevent.
                float remaining = cascade.spring.position() * 0.72f;
                cascade.spring.snap(0f);
                cascade.spring.nudgePosition(remaining);
                row.setTranslationY(remaining);
                if (Math.abs(remaining) <= 0.5f) {
                    row.setTranslationY(0f);
                    it.remove();
                }
                continue;
            }
            float step = deltaSeconds;
            if (cascade.delayRemaining > 0f) {
                cascade.delayRemaining -= deltaSeconds;
                if (cascade.delayRemaining > 0f) continue;
                // Spend only the part of this frame that falls past the delay. Without this the
                // stagger is rounded up to a whole frame, so on a device rendering at 30fps - where
                // one frame is longer than the entire stagger between neighbouring rows - every row
                // in the wave started on the same frame anyway and the cascade collapsed into the
                // rigid block slide it exists to avoid.
                step = Math.min(deltaSeconds, -cascade.delayRemaining);
                cascade.delayRemaining = 0f;
            }
            float value = cascade.spring.step(Math.max(0.001f, Math.min(0.05f, step)));
            row.setTranslationY(value);
            // A reflowing row is kept a little past rest: its layout can still settle, and that
            // late move has to be caught by followReflowLayout() rather than show as a jump.
            if (cascade.spring.isAtRest(0.5f, 2f)
                    && (!cascade.followLayout || now - cascade.startedAtMs > REFLOW_SETTLE_MS)) {
                row.setTranslationY(0f);
                it.remove();
            }
        }
    }

    private void clearRowCascadeExceptReflow() {
        if (rowCascades.isEmpty()) return;
        java.util.Iterator<Map.Entry<AppliedLine, RowCascade>> it = rowCascades.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<AppliedLine, RowCascade> entry = it.next();
            if (entry.getValue().followLayout) continue;
            View row = LyricsLineViewState.rowView(entry.getKey());
            if (row != null) row.setTranslationY(0f);
            it.remove();
        }
    }

    private void clearRowCascade() {
        if (rowCascades.isEmpty()) return;
        for (AppliedLine line : rowCascades.keySet()) {
            // rowView(), not attachedRowView(): a row that left the window mid-cascade still needs
            // its translation cleared, or it reappears offset when it scrolls back in.
            View row = LyricsLineViewState.rowView(line);
            if (row != null) row.setTranslationY(0f);
        }
        rowCascades.clear();
    }

    /** Stops a stale document's load reveal before its row views are reused for a new document. */
    private void cancelLoadEntranceAnimation() {
        clearLoadEntrance();
        if (document == null || document.appliedLines == null) return;
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = line == null ? null : rowMountController.attachedRowView(line);
            if (row == null) continue;
            resetRowTransientTransform(line, row);
        }
    }

    /** Puts one row back to its untransformed resting state. Reached both when a reveal or cascade
     *  is abandoned and when a row leaves the mounted window mid-motion - a row that is unmounted
     *  while still displaced keeps that displacement on its View, and silently reappears offset (or
     *  invisible) the next time it scrolls back in. */
    private void resetRowTransientTransform(AppliedLine line, View row) {
        LyricsLineViewState.setEntranceProgress(line, 1f);
        if (row == null) return;
        row.animate().cancel();
        row.setTranslationY(0f);
        // Alpha is deliberately left alone: the frame renderer owns it outright and rewrites it the
        // first time the row is rendered again. Forcing it to 1 here would show one fully-bright
        // frame before the renderer dims the row back to its real opacity.
        row.setScaleX(1f);
        row.setScaleY(1f);
        row.setHasTransientState(false);
        ViewGroup rowGroup = row instanceof ViewGroup ? (ViewGroup) row : null;
        int rowChildCount = rowGroup != null ? rowGroup.getChildCount() : 0;
        for (int childIndex = 0; childIndex < rowChildCount; childIndex++) {
            View child = rowGroup.getChildAt(childIndex);
            child.animate().cancel();
            child.setTranslationY(0f);
            child.setAlpha(1f);
            if (Build.VERSION.SDK_INT >= 31) child.setRenderEffect(null);
        }
    }

    /** A row leaving the mounted window drops out of every per-frame loop that would otherwise
     *  finish its motion, so its transform has to be settled here rather than left behind. */
    private void onRowUnmounted(AppliedLine line) {
        if (line == null) return;
        rowCascades.remove(line);
        loadEntrances.remove(line);
        resetRowTransientTransform(line, LyricsLineViewState.rowView(line));
        lineVisualController.invalidate(line);
    }

    private void maybeAutoResumeFollow(int activeIndex, SpotifyTrack track, long lyricPos) {
        if (!autoResumeFollow) return;
        if (!followState.canAutoResumeNow(750)) return;
        if (document == null || document.appliedLines == null || activeIndex < 0 || activeIndex >= document.appliedLines.size()) return;
        AppliedLine line = document.appliedLines.get(activeIndex);
        View row = rowMountController.attachedRowView(line);
        if (row == null || scrollController == null || !scrollController.isRowVisible(row, dp(5))) return;
        followState.clearHold();
        returnToCurrentPending = true;
        setActiveLine(activeIndex, lyricPos, track);
        updateJumpToCurrentVisibility();
    }

    private void styleLine(int index, boolean active) {
        lineVisualController.style(document == null ? null : document.appliedLines, index);
    }

    private void resumeFollowCurrentLine() {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        SpotifyTrack track = host.getCurrentTrackSafely();
        long pos = track == null ? -1 : playbackClock.getPosition(track, host.isPlayerActuallyPlaying());
        long lyricPos = pos >= 0 ? adjustedLyricPositionMs(pos) : pos;
        int index = lyricPos >= 0 ? LyricTimeline.findPrimaryActiveRow(document.appliedLines, lyricPos) : followState.activeIndex();
        if (index < 0 || index >= document.appliedLines.size()) return;
        followState.clearHold();
        returnToCurrentPending = true;
        // Deliberately not resetActive(): that sets the active index to the "nothing has ever been
        // active" sentinel, which shouldScrollInstantly() reads as a fresh document and answers by
        // snapping. Tapping the follow chip then teleported the column instead of travelling back
        // to it. Keeping the real previous index lets the jump run through the scroll spring, whose
        // travel is capped so even a jump from the far end of the song arrives legibly.
        renderWindowForActive(index);
        setActiveLine(index, Math.max(0, lyricPos), track);
        frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                renderConfig, Math.max(0, lyricPos), index, 1f / 60f, false);
        updateJumpToCurrentVisibility();
    }

    private long adjustedLyricPositionMs(long playbackPositionMs) {
        return renderConfig == null ? Math.max(0L, playbackPositionMs) : renderConfig.adjustedPositionMs(playbackPositionMs);
    }

    // Re-read renderer settings immediately after the in-Spotify panel closes (the periodic poll
    // would also catch them, but this resumes the paused background without delay).
    private void onSettingsClosed() {
        try {
            applyRenderConfigChanges("settings closed", true);
            ambientController.applySettings(renderConfig.backgroundStyle, renderConfig.forceDarkBackground,
                    renderConfig.extraDarkBackground);
        } catch (Throwable t) {
            XpLog.log(TAG + " onSettingsClosed failed: " + t);
        }
    }

    private void schedulePreferenceRefresh() {
        if (!running || preferenceRefreshPosted) return;
        frameScheduler.requestFrame();
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

    // Spin the chip rings while their work is outstanding: initial fetch, or background
    // romanization/translation enhancement. Reads only in-memory state, so it's cheap per frame.
    private void updateToggleSpinners() {
        boolean loading = !loadingTrackId.isEmpty();
        boolean romanPending = showRomanization()
                && romanToggle.getVisibility() == View.VISIBLE
                && (loading || localReprocessController.isProcessing()
                        || (document != null && document.romanizationPending));
        boolean translationPending = showTranslation()
                && translationToggle.getVisibility() == View.VISIBLE
                && (loading || (document != null && document.translationPending));
        boolean romanAiPending = soundAiFeedback.isPending(
                document != null && document.readingAiPending)
                && romanToggle.getVisibility() == View.VISIBLE;
        boolean translationAiPending = meaningAiFeedback.isPending(
                document != null && document.translationAiPending)
                && translationToggle.getVisibility() == View.VISIBLE;
        // A settled failure tints the affected sparkle red until a retry (running) or success
        // replaces it. Gated on chip visibility like the other states; details stay available via
        // the review panel, not a persistent notice.
        boolean romanAiFailed = !romanAiPending
                && !aiFailureToken(com.eza.spicyex.lyrics.session.LayerKind.SOUND).isEmpty()
                && romanToggle.getVisibility() == View.VISIBLE;
        boolean translationAiFailed = !translationAiPending
                && !aiFailureToken(com.eza.spicyex.lyrics.session.LayerKind.MEANING).isEmpty()
                && translationToggle.getVisibility() == View.VISIBLE;
        toggleSpinnerController.update(renderConfig.toggleSpinnerEnabled, romanPending,
                translationPending,
                hasAiLayerOutput(com.eza.spicyex.lyrics.session.LayerKind.SOUND)
                        && showRomanization(),
                hasAiLayerOutput(com.eza.spicyex.lyrics.session.LayerKind.MEANING)
                        && showTranslation(),
                romanAiPending, translationAiPending, romanAiFailed, translationAiFailed);
    }

    /** Desktop's primary-click policy, applied before the normal visibility toggle. */
    private boolean shouldGenerateAi(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (document == null || isLayerBusy(layer)) return false;
        AiSettings settings = aiSettings;
        if (!settings.generateThenToggle() || !settings.canRequest()) return false;
        return !hasAiLayerOutput(layer);
    }

    /** Desktop parity: secondary click opens review when AI output exists, otherwise the composer. */
    private void openAiLayerPanel(com.eza.spicyex.lyrics.session.LayerKind layer) {
        String failureToken = aiFailureToken(layer);
        boolean hasAi = hasAiLayerOutput(layer);
        com.eza.spicyex.lyrics.ai.AiRequestLiveState.Snapshot monitor =
                aiRequestMonitor(layer);
        // A settled failure is not a run in progress, whatever the document's pending flag still
        // says. Letting "busy" win routed a terminal truncation to the running-status dialog, which
        // has no retry and no failed-attempt payload — the two things that failure needs.
        boolean running = isLayerBusy(layer) && !monitor.current.isFailure();
        com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy.Destination destination =
                com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy.destination(
                        running, failureToken, hasAi);
        if (destination == com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy.Destination.RUNNING_STATUS) {
            showLayerRunningStatus(layer, monitor);
            return;
        }
        if (document == null) return;
        if (destination == com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy.Destination.FAILURE) {
            com.eza.spicyex.ui.PanelDialog failed = new com.eza.spicyex.ui.PanelDialog(activity,
                    aiLayerLabel(layer));
            failed.paragraph(aiFailureInlineText(layer, failureToken));
            appendAttemptMonitor(failed, monitor.current,
                    uiText("lyrics_ai_failed_attempt_payload", "Failed attempt payload"));
            appendAttemptMonitor(failed, monitor.previousFailure,
                    uiText("lyrics_ai_previous_failed_attempt", "Previous failed attempt"));
            if (hasAi) failed.paragraph(reviewText(layer));
            failed.primary("delivery_unknown".equals(failureToken)
                            ? uiText("lyrics_ai_retry_anyway", "Retry anyway")
                            : uiText("lyrics_ai_retry", "Retry"),
                    () -> requestAiRetry(layer, failureToken));
            failed.secondary(hasAi ? restoreBaselineLabel(layer)
                            : uiText("lyrics_ai_cancel", "Cancel"),
                    hasAi ? () -> host.restoreLyricsLayer(layer) : null);
            failed.show();
            return;
        }
        if (destination == com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy.Destination.REVIEW) {
            com.eza.spicyex.ui.PanelDialog review = new com.eza.spicyex.ui.PanelDialog(activity,
                    aiLayerLabel(layer))
                    .closeIcon(uiText("lyrics_ai_close", "Close"));
            String lead = reviewLeadText(layer);
            if (!lead.isEmpty()) review.paragraph(lead);
            appendModelReasoning(review, layer, settledAttempt(monitor));
            String output = reviewOutputText(layer);
            if (!output.isEmpty()) review.paragraph(output);
            appendAttemptMonitor(review, monitor.previousFailure,
                    uiText("lyrics_ai_previous_failed_attempt", "Previous failed attempt"));
            review.primary(uiText("lyrics_ai_refine_again", "Refine again…"),
                    () -> openAiComposer(layer));
            review.secondary(restoreBaselineLabel(layer),
                    () -> host.restoreLyricsLayer(layer));
            review.show();
            return;
        }
        openAiComposer(layer);
    }

    private boolean isLayerBusy(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (!loadingTrackId.isEmpty()) return true;
        if (layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND) {
            return localReprocessController.isProcessing()
                    || document != null && document.romanizationPending;
        }
        return document != null && document.translationPending;
    }

    private void showLayerRunningStatus(com.eza.spicyex.lyrics.session.LayerKind layer,
                                        com.eza.spicyex.lyrics.ai.AiRequestLiveState.Snapshot initial) {
        boolean aiRunning = document != null && (layer
                == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                ? document.readingAiPending : document.translationAiPending);
        String message;
        if (aiRunning) {
            message = layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                    ? uiText("lyrics_ai_pronunciation_running",
                    "AI pronunciation request is running for this song.")
                    : uiText("lyrics_ai_translation_running",
                    "AI translation request is running for this song.");
        } else if (!loadingTrackId.isEmpty()) {
            message = uiText("lyrics_ai_song_loading",
                    "Lyrics for the current song are loading. AI controls will be available when ready.");
        } else {
            message = layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                    ? uiText("lyrics_ai_pronunciation_processing",
                    "Pronunciation is still processing for this song.")
                    : uiText("lyrics_ai_translation_processing",
                    "Translation is still processing for this song.");
        }
        final com.eza.spicyex.ui.PanelDialog status = new com.eza.spicyex.ui.PanelDialog(activity,
                uiText("lyrics_ai_current_status", "Current status"));
        status.paragraph(message);
        final android.widget.TextView payload = aiRunning ? status.readOnlyBlock(
                monitorText(initial == null ? null : initial.current)) : null;
        appendAttemptMonitor(status, initial == null ? null : initial.previousFailure,
                uiText("lyrics_ai_previous_failed_attempt", "Previous failed attempt"));
        final Runnable[] refresh = new Runnable[1];
        // The payload is written once per attempt and then sits still for the length of the call.
        // Re-setting identical text four times a second scrolled the block back to the top and
        // dropped any selection, which made the one thing this dialog exists to show unreadable.
        final String[] rendered = { payload == null ? null : payload.getText().toString() };
        refresh[0] = () -> {
            if (payload == null) return;
            com.eza.spicyex.lyrics.ai.AiRequestLiveState.Snapshot latest =
                    aiRequestMonitor(layer);
            String next = monitorText(latest.current);
            if (!next.equals(rendered[0])) {
                rendered[0] = next;
                payload.setText(next);
            }
            if (status.isShowing() && isLayerBusy(layer)) {
                handler.postDelayed(refresh[0], 250L);
            }
        };
        status.onDismiss(() -> handler.removeCallbacks(refresh[0]));
        status.secondary(uiText("lyrics_ai_close", "Close"), null);
        status.show();
        handler.post(refresh[0]);
    }

    private com.eza.spicyex.lyrics.ai.AiRequestLiveState.Snapshot aiRequestMonitor(
            com.eza.spicyex.lyrics.session.LayerKind layer) {
        String digest = document == null ? ""
                : LyricsDocumentProcessor.canonicalBaseOf(document).digest;
        return com.eza.spicyex.lyrics.ai.AiRequestLiveState.snapshot(layer, digest);
    }

    private String monitorText(com.eza.spicyex.lyrics.ai.AiRequestLiveState.Attempt attempt) {
        if (attempt == null || !attempt.hasPayload()) {
            return uiText("lyrics_ai_preparing_payload", "Preparing request payload…");
        }
        return attempt.payload;
    }

    private void appendAttemptMonitor(com.eza.spicyex.ui.PanelDialog dialog,
                                      com.eza.spicyex.lyrics.ai.AiRequestLiveState.Attempt attempt,
                                      String heading) {
        if (dialog == null || attempt == null || !attempt.isFailure()) return;
        StringBuilder summary = new StringBuilder(heading);
        if (!attempt.failureToken.isEmpty()) summary.append(" · ").append(attempt.failureToken);
        if (attempt.httpStatus > 0) summary.append(" · HTTP ").append(attempt.httpStatus);
        if (!attempt.failureDetail.isEmpty()) summary.append(" · ").append(attempt.failureDetail);
        dialog.paragraph(summary.toString());
        if (attempt.hasPayload()) dialog.readOnlyBlock(attempt.payload);
        appendReasoningTrace(dialog, attempt);
    }

    /**
     * The run whose reasoning the review panel should show.
     *
     * <p>The settled copy first: by the time a review panel can open, the run that produced the
     * output on screen has finished, and a later run may already have reset {@code current} to a
     * preparing attempt with nothing in it yet.
     */
    private com.eza.spicyex.lyrics.ai.AiRequestLiveState.Attempt settledAttempt(
            com.eza.spicyex.lyrics.ai.AiRequestLiveState.Snapshot monitor) {
        if (monitor == null) return null;
        return monitor.lastSettled.hasReasoning() ? monitor.lastSettled : monitor.current;
    }

    /**
     * The model's thinking for one attempt, folded away.
     *
     * <p>Collapsed rather than shown, and absent entirely when the wire carried no trace. A trace
     * is longer than everything else in this dialog put together and is read only when an answer
     * looks wrong, so opening the panel on it would bury the output the panel exists to review.
     */
    private void appendReasoningTrace(com.eza.spicyex.ui.PanelDialog dialog,
                                      com.eza.spicyex.lyrics.ai.AiRequestLiveState.Attempt attempt) {
        if (dialog == null || attempt == null || !attempt.hasReasoning()) return;
        dialog.collapsible(uiText("lyrics_ai_reasoning_trace", "Reasoning trace"),
                attempt.reasoning);
    }

    /** Makes the model row itself the disclosure control for a successful run's reasoning. */
    private void appendModelReasoning(com.eza.spicyex.ui.PanelDialog dialog,
                                      com.eza.spicyex.lyrics.session.LayerKind layer,
                                      com.eza.spicyex.lyrics.ai.AiRequestLiveState.Attempt attempt) {
        if (dialog == null) return;
        String model = aiModel(layer);
        if (model.isEmpty()) return;
        String label = uiFormat("lyrics_ai_model_used", "Model: %1$s", model);
        if (attempt != null && attempt.hasReasoning()) {
            dialog.collapsible(label, attempt.reasoning);
        } else {
            dialog.paragraph(label);
        }
    }

    private void openAiComposer(com.eza.spicyex.lyrics.session.LayerKind layer) {
        com.eza.spicyex.lyrics.ai.AiSettings settings =
                new com.eza.spicyex.lyrics.ai.AiSettings(activity);
        if (!settings.canRequest()) return;
        com.eza.spicyex.ui.PanelDialog composer = new com.eza.spicyex.ui.PanelDialog(activity,
                aiLayerLabel(layer)).closeIcon(uiText("lyrics_ai_close", "Close"));
        composer.paragraph(layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                ? uiText("lyrics_ai_translation_prompt_help",
                "Choose a preset or edit a custom prompt describing what the model should preserve, fix, or emphasize.")
                : uiText("lyrics_ai_pronunciation_prompt_help",
                "Choose a preset or edit a custom prompt for pronunciation, dialect, spelling, or mixed-language guidance."));

        String activePrompt = settings.instructions(layer);
        String matchingPreset = com.eza.spicyex.lyrics.ai.AiPresets.matchingName(layer, activePrompt);
        String customPrompt = settings.customInstructions(layer);
        if (customPrompt.isEmpty() && matchingPreset == null && !activePrompt.isEmpty()) {
            customPrompt = activePrompt;
        }
        String[] presets = com.eza.spicyex.lyrics.ai.AiPresets.names(layer);
        final String customLabel = uiText("lyrics_ai_custom_prompt", "Custom");
        final String[] selected = new String[]{matchingPreset != null
                ? matchingPreset : (!customPrompt.isEmpty() ? customLabel : presets[0])};
        final String[] savedCustom = new String[]{customPrompt};
        final android.widget.EditText[] field = new android.widget.EditText[1];
        final android.widget.TextView[] selectedView = new android.widget.TextView[1];
        final android.view.View[] editAction = new android.view.View[1];
        final android.view.View[] saveAction = new android.view.View[1];

        selectedView[0] = composer.selector(
                uiText("lyrics_ai_prompt_preset", "Prompt preset"), selected[0],
                uiText("lyrics_ai_choose_prompt_preset", "Choose prompt preset"),
                () -> showAiPresetPicker(composer.selectorAnchor(selectedView[0]),
                        layer, selected[0], customLabel, picked -> {
                    selected[0] = picked;
                    selectedView[0].setText(picked);
                    boolean custom = customLabel.equals(picked);
                    if (custom) field[0].setText(savedCustom[0]);
                    field[0].setVisibility(custom ? VISIBLE : GONE);
                    editAction[0].setVisibility(custom ? GONE : VISIBLE);
                    saveAction[0].setVisibility(custom ? VISIBLE : GONE);
                }));
        field[0] = composer.multilineField(savedCustom[0]);
        editAction[0] = composer.selectorAction(selectedView[0],
                com.eza.spicyex.ui.ActionIconDrawable.Kind.EDIT,
                uiText("lyrics_ai_edit_prompt", "Edit prompt"), () -> {
                    String base = customLabel.equals(selected[0])
                            ? savedCustom[0]
                            : com.eza.spicyex.lyrics.ai.AiPresets.instructions(layer, selected[0]);
                    selected[0] = customLabel;
                    selectedView[0].setText(customLabel);
                    field[0].setText(base);
                    field[0].setVisibility(VISIBLE);
                    editAction[0].setVisibility(GONE);
                    saveAction[0].setVisibility(VISIBLE);
                    field[0].requestFocus();
                });
        saveAction[0] = composer.selectorAction(selectedView[0],
                com.eza.spicyex.ui.ActionIconDrawable.Kind.SAVE,
                uiText("lyrics_ai_save_custom_prompt", "Save custom prompt"), () -> {
                    savedCustom[0] = field[0].getText().toString();
                    settings.setCustomInstructions(layer, savedCustom[0]);
                    settings.setInstructions(layer, savedCustom[0]);
                    android.widget.Toast.makeText(activity,
                            uiText("lyrics_ai_custom_prompt_saved", "Custom prompt saved"),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
        boolean customSelected = customLabel.equals(selected[0]);
        field[0].setVisibility(customSelected ? VISIBLE : GONE);
        editAction[0].setVisibility(customSelected ? GONE : VISIBLE);
        saveAction[0].setVisibility(customSelected ? VISIBLE : GONE);

        // Three Meaning flows share one stored key, so the composer edits the stored value
        // directly instead of a boolean that can only express two of them. Reading goes through
        // the store's schema coercion, so legacy installs keep their migrated choice.
        final boolean meaningLayer = layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING;
        final com.eza.spicyex.SettingsStore flowStore =
                meaningLayer ? new com.eza.spicyex.SettingsStore(activity) : null;
        final java.util.List<String> flowValues = meaningLayer
                ? Settings.AI_TRANSLATION_PIPELINE.allowedValues
                : java.util.Collections.<String>emptyList();
        final String[] flowValue = {meaningLayer
                ? flowStore.get(Settings.AI_TRANSLATION_PIPELINE) : ""};
        final android.widget.TextView[] flowView = new android.widget.TextView[1];
        if (meaningLayer) {
            flowView[0] = composer.selector(
                    uiText("settings_label_ai_translation_pipeline", "AI translation flow"),
                    aiPipelineLabel(flowValue[0]),
                    uiText("settings_label_ai_translation_pipeline", "AI translation flow"),
                    () -> showAiPipelinePicker(composer.selectorAnchor(flowView[0]),
                            flowValues, flowValue[0], picked -> {
                                flowValue[0] = picked;
                                flowView[0].setText(aiPipelineLabel(picked));
                            }));
        }

        composer.primary(uiText("lyrics_ai_run", "Run AI"), () -> {
            String prompt;
            if (customLabel.equals(selected[0])) {
                prompt = field[0].getText().toString();
                settings.setCustomInstructions(layer, prompt);
            } else {
                prompt = com.eza.spicyex.lyrics.ai.AiPresets.instructions(layer, selected[0]);
            }
            settings.setInstructions(layer, prompt);
            if (meaningLayer) {
                flowStore.put(Settings.AI_TRANSLATION_PIPELINE, flowValue[0]);
            }
            requestAiLayerWithFeedback(layer);
        });
        composer.show();
    }

    /**
     * Current UI strings, read live through the process-wide language owner.
     *
     * <p>Deliberately not a field: a cached copy froze the language at shell construction, so a
     * language change made in the settings panel never reached this surface until a remount.
     */
    private SettingsUiStrings uiStrings() {
        return com.eza.spicyex.UiLanguage.strings(activity, config.get(Settings.UI_LANGUAGE));
    }

    private String aiPipelineLabel(String value) {
        return uiStrings().option((Settings.StringSetting) Settings.AI_TRANSLATION_PIPELINE, value);
    }

    private void showAiPipelinePicker(android.view.View anchor,
                                      java.util.List<String> values, String selected,
                                      java.util.function.Consumer<String> onPick) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String value : values) labels.add(aiPipelineLabel(value));
        com.eza.spicyex.ui.PanelPickerPopup.show(activity, anchor, labels,
                aiPipelineLabel(selected), pickedLabel -> {
                    for (String value : values) {
                        if (aiPipelineLabel(value).equals(pickedLabel)) {
                            onPick.accept(value);
                            return;
                        }
                    }
                });
    }

    private void showAiPresetPicker(android.view.View anchor,
                                    com.eza.spicyex.lyrics.session.LayerKind layer,
                                    String selected, String customLabel,
                                    java.util.function.Consumer<String> onPick) {
        java.util.List<String> choices = new java.util.ArrayList<>();
        java.util.Collections.addAll(choices,
                com.eza.spicyex.lyrics.ai.AiPresets.names(layer));
        choices.add(customLabel);
        com.eza.spicyex.ui.PanelPickerPopup.show(activity, anchor, choices, selected, onPick);
    }

    private String aiFailureToken(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (document == null) return "";
        return safe(layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                ? document.translationAiFailureToken : document.readingAiFailureToken);
    }

    private String aiLayerLabel(com.eza.spicyex.lyrics.session.LayerKind layer) {
        return layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                ? uiText("lyrics_ai_translation", "AI translation")
                : uiText("lyrics_ai_pronunciation", "AI pronunciation");
    }

    /** An AI authority flag without any displayed row is stale state, not accepted output. */
    private boolean hasAiLayerOutput(com.eza.spicyex.lyrics.session.LayerKind layer) {
        boolean marked = layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                ? document != null && document.translationFromAi
                : document != null && document.readingFromAi;
        return marked && hasLayerOutput(layer);
    }

    private boolean hasLayerOutput(com.eza.spicyex.lyrics.session.LayerKind layer) {
        return layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                ? LyricsDocumentProcessor.hasDisplayedMeaning(document)
                : LyricsDocumentProcessor.hasDisplayedSound(document);
    }

    private String aiFailureInlineText(com.eza.spicyex.lyrics.session.LayerKind layer,
                                       String token) {
        String label = aiLayerLabel(layer);
        if ("delivery_unknown".equals(token)) {
            return uiFormat("lyrics_ai_delivery_unknown_inline",
                    "%1$s status unknown. The request may have been billed. Tap to review.", label);
        }
        return uiFormat("lyrics_ai_failure_inline", "%1$s failed: %2$s. Tap to retry.",
                label, aiFailureReason(token));
    }

    private String aiFailureReason(String token) {
        switch (safe(token)) {
            case "no_credential": return uiText("lyrics_ai_failure_no_credential", "No API key");
            case "baseline_unavailable": return uiText("lyrics_ai_failure_baseline", "Baseline unavailable");
            case "model_unavailable": return uiText("lyrics_ai_failure_model", "Model unavailable");
            case "auth_rejected": return uiText("lyrics_ai_failure_auth", "API key rejected");
            case "quota_exhausted": return uiText("lyrics_ai_failure_quota", "Quota exhausted");
            case "rate_limited": return uiText("lyrics_ai_failure_rate_limit", "Rate limited");
            case "protocol_invalid": return uiText("lyrics_ai_failure_protocol", "Invalid provider response");
            case "request_rejected": return uiText("lyrics_ai_failure_request", "Request rejected");
            case "provider_refused": return uiText("lyrics_ai_failure_refused", "Provider refused the request");
            case "storage_full": return uiText("lyrics_ai_failure_storage_full", "Paid AI storage is full");
            case "storage_unavailable": return uiText("lyrics_ai_failure_storage_unavailable", "Paid AI storage is unavailable");
            case "truncated": return uiText("lyrics_ai_failure_truncated", "Response truncated");
            case "oversized": return uiText("lyrics_ai_failure_oversized", "Lyrics or response too large");
            case "runtime_unavailable": return uiText("lyrics_ai_failure_runtime", "AI runtime unavailable");
            default: return uiText("lyrics_ai_failure_generic", "Provider unavailable");
        }
    }

    private void requestAiRetry(com.eza.spicyex.lyrics.session.LayerKind layer, String token) {
        if (!"delivery_unknown".equals(token)) {
            requestAiLayerWithFeedback(layer);
            return;
        }
        com.eza.spicyex.ui.PanelDialog warning = new com.eza.spicyex.ui.PanelDialog(activity,
                uiText("lyrics_ai_retry_warning_title", "Retry may duplicate a billed request"));
        warning.paragraph(uiText("lyrics_ai_retry_warning",
                "The previous request did not confirm delivery. It may already have been billed. Retry only if you accept that risk."));
        warning.primary(uiText("lyrics_ai_retry_anyway", "Retry anyway"),
                () -> requestAiLayerWithFeedback(layer));
        warning.secondary(uiText("lyrics_ai_cancel", "Cancel"), null);
        warning.show();
    }

    private boolean requestAiLayerWithFeedback(
            com.eza.spicyex.lyrics.session.LayerKind layer) {
        TranslationVisibilityPolicy.RevealAction reveal =
                TranslationVisibilityPolicy.revealFor(layer, showRomanization(), showTranslation());
        if (reveal == TranslationVisibilityPolicy.RevealAction.REVEAL_ROMANIZATION) {
            transliterationSession.setShowRomanization(true);
            preferences.edit()
                    .putBoolean(Settings.NATIVE_SPICY_ROMANIZATION.key, true)
                    .apply();
            refreshSecondaryRows("");
        } else if (reveal == TranslationVisibilityPolicy.RevealAction.REVEAL_TRANSLATION) {
            showTranslation = true;
            preferences.edit()
                    .putBoolean(Settings.NATIVE_SPICY_TRANSLATION.key, true)
                    .apply();
            refreshSecondaryRows("");
        }
        com.eza.spicyex.lyrics.ai.AiRequestStartResult result =
                host.requestAiLyricsLayer(layer);
        String label = aiLayerLabel(layer);
        if (!result.started()) {
            android.widget.Toast.makeText(activity,
                    aiRequestRefusalMessage(result, label),
                    android.widget.Toast.LENGTH_LONG).show();
            updateToggleVisuals();
            return false;
        }
        aiFeedback(layer).started();
        updateToggleVisuals();
        android.widget.Toast.makeText(activity,
                layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                        ? uiText("lyrics_ai_pronunciation_running",
                        "AI pronunciation request is running for this song.")
                        : uiText("lyrics_ai_translation_running",
                        "AI translation request is running for this song."),
                android.widget.Toast.LENGTH_SHORT).show();
        return true;
    }

    private String aiRequestRefusalMessage(
            com.eza.spicyex.lyrics.ai.AiRequestStartResult result, String label) {
        switch (result) {
            case NOT_CONFIGURED:
                return uiFormat("lyrics_ai_request_not_configured",
                        "%1$s request cannot start. Complete AI setup first.", label);
            case ALREADY_IN_FLIGHT:
                return uiFormat("lyrics_ai_request_already_running",
                        "%1$s request is already running for this song.", label);
            case NOTHING_TO_DO:
                return uiFormat("lyrics_ai_request_nothing_to_do",
                        "%1$s request found no new work for this song.", label);
            default:
                return uiFormat("lyrics_ai_request_lyrics_unavailable",
                        "%1$s request cannot start because lyrics are not ready.", label);
        }
    }

    private com.eza.spicyex.lyrics.ai.AiRequestFeedbackState aiFeedback(
            com.eza.spicyex.lyrics.session.LayerKind layer) {
        return layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                ? soundAiFeedback : meaningAiFeedback;
    }

    private void observeAiRequestFeedback(LyricsDocument value) {
        if (value == null) return;
        observeAiRequestFeedback(
                com.eza.spicyex.lyrics.session.LayerKind.SOUND,
                value.readingAiPending,
                value.readingFromAi && LyricsDocumentProcessor.hasDisplayedSound(value),
                safe(value.readingAiFailureToken));
        observeAiRequestFeedback(
                com.eza.spicyex.lyrics.session.LayerKind.MEANING,
                value.translationAiPending,
                value.translationFromAi && LyricsDocumentProcessor.hasDisplayedMeaning(value),
                safe(value.translationAiFailureToken));
    }

    private void observeAiRequestFeedback(
            com.eza.spicyex.lyrics.session.LayerKind layer,
            boolean pending,
            boolean hasAiOutput,
            String failureToken) {
        com.eza.spicyex.lyrics.ai.AiRequestFeedbackState.Outcome outcome =
                aiFeedback(layer).observe(pending, hasAiOutput, failureToken);
        if (outcome == com.eza.spicyex.lyrics.ai.AiRequestFeedbackState.Outcome.FAILED) {
            android.widget.Toast.makeText(activity,
                    aiFailureInlineText(layer, failureToken),
                    android.widget.Toast.LENGTH_LONG).show();
        } else if (outcome
                == com.eza.spicyex.lyrics.ai.AiRequestFeedbackState.Outcome.NO_OUTPUT) {
            android.widget.Toast.makeText(activity,
                    uiFormat("lyrics_ai_request_no_output",
                            "%1$s finished without usable output. Tap for status or retry.",
                            aiLayerLabel(layer)),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String uiText(String name, String fallback) {
        return uiStrings().get(name, fallback);
    }

    private String uiFormat(String name, String fallback, Object... args) {
        return uiStrings().format(name, fallback, args);
    }

    private String restoreBaselineLabel(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                && document != null && document.translationAiRefinedFromGoogle) {
            return uiText("lyrics_ai_restore_google", "Restore Google Translate");
        }
        return uiText("lyrics_ai_restore_baseline", "Restore baseline");
    }

    private String reviewText(com.eza.spicyex.lyrics.session.LayerKind layer) {
        StringBuilder text = new StringBuilder();
        appendReviewSection(text, reviewLeadText(layer));
        String model = aiModel(layer);
        if (!model.isEmpty()) {
            appendReviewSection(text,
                    uiFormat("lyrics_ai_model_used", "Model: %1$s", model));
        }
        appendReviewSection(text, reviewOutputText(layer));
        return text.toString();
    }

    private String reviewLeadText(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (document == null || layer != com.eza.spicyex.lyrics.session.LayerKind.MEANING) return "";
        return document.translationAiRefinedFromGoogle
                ? uiText("lyrics_ai_refined_google", "AI refined the Google Translate version.")
                : uiText("lyrics_ai_from_source", "AI translated from the original lyrics.");
    }

    private String aiModel(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (document == null) return "";
        String model = layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                ? document.readingAiModel : document.translationAiModel;
        return model == null ? "" : model.trim();
    }

    private String reviewOutputText(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (document == null || document.lines == null) return "";
        StringBuilder text = new StringBuilder();
        for (com.eza.spicyex.lyrics.LyricsLine line : document.lines) {
            if (line == null || line.text == null || line.text.trim().isEmpty()) continue;
            String output = layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                    ? line.translatedText
                    : line.readingRenderPlan != null
                    ? line.readingRenderPlan.joinedDisplayText : line.romanizedText;
            if (output == null || output.trim().isEmpty()) continue;
            text.append(line.text).append("\n→ ").append(output).append("\n\n");
        }
        return text.toString().trim();
    }

    private void appendReviewSection(StringBuilder text, String section) {
        if (text == null || section == null || section.isEmpty()) return;
        if (text.length() > 0) text.append("\n\n");
        text.append(section);
    }

    @Override
    protected void onDetachedFromWindow() {
        toggleSpinnerController.reset();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                    && trackInfoController != null
                    && !trackInfoController.containsArtTouch(ev.getRawX(), ev.getRawY())) {
                trackInfoController.onOutsideDown();
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(ev);
    }

    private void updateJumpToCurrentVisibility() {
        boolean show = document != null && followState.activeIndex() >= 0 && followState.isHoldingNow();
        jumpToCurrentController.update(show);
    }

    /**
     * Drives the intro/outro skip affordance (synced docs only; callers hide it elsewhere).
     * Off hides everything; On demand shows the chip while an unacknowledged gap is active;
     * Auto seeks past the gap with no button. The sticky ack (track URI + gap start) survives
     * the seek landing — including Spotify Connect delays — and clears on track change, a
     * manual seek-back, or leaving the gap.
     */
    private void updateSkipGap(SpotifyTrack track, String uri, long lyricPos, boolean playingNow) {
        String mode = config == null ? "Off" : config.get(Settings.AUTO_SKIP_INTRO_OUTRO);
        if ("Off".equals(mode)) {
            skipAckGapStartMs = -1;
            skipGapController.update(false);
            lastSkipSeenPosMs = lyricPos;
            return;
        }
        if (!uri.equals(skipAckUri)) {
            skipAckUri = uri;
            skipAckGapStartMs = -1;
        }
        if (lastSkipSeenPosMs >= 0 && lyricPos < lastSkipSeenPosMs - 2000) {
            skipAckGapStartMs = -1; // user scrubbed back into (or past) the gap
        }
        lastSkipSeenPosMs = lyricPos;
        SkipGapPolicy.SkipTarget target = document == null || document.appliedLines == null ? null
                : SkipGapPolicy.skipTarget(document.appliedLines, lyricPos);
        boolean acked = target != null && skipAckGapStartMs >= 0
                && target.gapStartMs == skipAckGapStartMs;
        if ("Auto".equals(mode)) {
            skipGapController.update(false);
            if (target != null && !acked && playingNow) performSkipSeek(target, uri);
            return;
        }
        skipGapController.update(target != null && !acked);
    }

    /** On-demand chip tap: seek past the currently active gap, if it is still there. */
    private void skipCurrentGap() {
        if (document == null || document.appliedLines == null || lastSkipSeenPosMs < 0) return;
        String uri = lastUri;
        SkipGapPolicy.SkipTarget target =
                SkipGapPolicy.skipTarget(document.appliedLines, lastSkipSeenPosMs);
        if (target == null || target.gapStartMs == skipAckGapStartMs) return;
        performSkipSeek(target, uri);
    }

    private void performSkipSeek(SkipGapPolicy.SkipTarget target, String uri) {
        long playbackMs = renderConfig == null
                ? Math.max(0, target.targetMs)
                : renderConfig.playbackPositionForLyricMs(target.targetMs);
        boolean ok = host.seekSpotifyTo(playbackMs);
        if (ok) {
            playbackClock.forcePosition(playbackMs, host.isPlayerActuallyPlaying());
            skipAckUri = uri;
            skipAckGapStartMs = target.gapStartMs;
            lastSkipSeenPosMs = target.targetMs;
            XpLog.log(TAG + " skip gap startMs=" + target.gapStartMs + " targetMs=" + target.targetMs);
        }
        skipGapController.update(false);
    }

    private void cycleTransliterationMode(SharedPreferences prefs) {
        if (!SoundModeCyclePolicy.mayCycle(renderConfig == null
                || renderConfig.transliterationEnabled)) return;
        if (SoundModeCyclePolicy.clearsStaleAiReading(document)) {
            LyricsDocumentProcessor.resetSoundLayer(activity.getApplicationContext(), document);
            updateToggleVisuals();
        }
        LyricsTransliterationSession.CycleResult result =
                transliterationSession.cycle(activeLineHasJapanese(), activeLineHasChinese(),
                        activeLineHasKorean(), activeLineHasCyrillic());
        prefs.edit()
                .putBoolean(Settings.NATIVE_SPICY_ROMANIZATION.key, result.showRomanization)
                .putString(Settings.LAST_JAPANESE_CYCLE_MODE.key, transliterationSession.japaneseReadingMode())
                .putString(Settings.LAST_CHINESE_CYCLE_MODE.key, LyricsShellSettings.normalizeChineseMode(transliterationSession.chineseMode()))
                .putString(Settings.LAST_KOREAN_CYCLE_MODE.key, transliterationSession.koreanMode())
                .putString(Settings.LAST_CYRILLIC_CYCLE_MODE.key, transliterationSession.cyrillicMode())
                .apply();
        reprocessLocalModeOnly(result.reason);
    }

    private void reprocessLocalModeOnly(String reason) {
        LyricsDocument snapshot = document;
        boolean started = localReprocessController.request(
                snapshot,
                showRomanization(),
                romanizationOptions(),
                reason,
                this::isCurrentProcessingResult,
                new LyricsLocalReprocessController.Callback() {
                    @Override
                    public void complete(String completedReason, int changed) {
                        // Always re-render: the chip also toggles reading visibility on and off,
                        // which changes no text at all. A repaint gated on changed text would
                        // leave that case showing the previous state.
                        rerenderKeepingPosition(completedReason + " ready");
                        // Only a genuine mode change is worth the session's time. This surface owns
                        // its own per-span reading projection and re-derives locally for immediate
                        // feedback; telling the session moves now-playing and the HyperGlow bridge
                        // to the same mode instead of leaving them on the previous one until the
                        // next track. A visibility toggle is fullscreen-only, so it stays local.
                        if (changed > 0) {
                            host.refreshLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind.SOUND);
                        }
                        XpLog.log(TAG + " local mode reprocess complete changed=" + changed + " reason=" + completedReason);
                    }

                    @Override
                    public void repeat(String repeatReason) {
                        reprocessLocalModeOnly(repeatReason);
                    }
                });
        if (!started) {
            updateToggleVisuals();
            renderDocument();
        }
    }

    /**
     * A downloaded language pack changes the tokenizer and dictionary underneath an already
     * mounted document. A plain redraw used to reuse its old, often empty, Sound projection, so
     * installing the pack looked successful in Settings while furigana/reading never appeared
     * until the next track or a manual toggle. Invalidate only the local Sound projection and run
     * the normal serialized local pipeline again; translations and canonical lyrics stay intact.
     */
    private void reprocessForInstalledLanguageModels() {
        if (!running || document == null || document.lines == null || document.lines.isEmpty()) return;
        if (!showRomanization()) {
            // The pack supplies data, not an implicit visibility preference.
            updateToggleVisuals();
            return;
        }
        LyricsDocumentProcessor.resetSoundLayer(activity.getApplicationContext(), document);
        reprocessLocalModeOnly("language models installed");
    }

    private void reprocessTranslationForConfig(String reason) {
        LyricsDocument snapshot = document;
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty()) return;

        // Drop the stale translations on this surface immediately so the change is visible, then
        // hand the actual work to the session. The renderer never starts a provider run: that is
        // what makes one settings change cost one run across fullscreen, now-playing, and the
        // HyperGlow bridge instead of one per surface.
        LyricsDocumentProcessor.resetMeaningLayer(activity.getApplicationContext(), snapshot);
        rerenderKeepingPosition(reason + " ready");
        if (snapshot.translationPending) {
            host.refreshLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind.MEANING);
        }
    }

    private boolean activeLineHasJapanese() {
        AppliedLine line = activeLine();
        return line == null ? documentHasJapanese()
                : "ja".equals(com.eza.spicyex.lyrics.ReadingLanguagePolicy.layoutLanguage(line));
    }

    private boolean activeLineHasChinese() {
        AppliedLine line = activeLine();
        return line == null ? documentHasChinese()
                : "zh".equals(com.eza.spicyex.lyrics.ReadingLanguagePolicy.layoutLanguage(line));
    }

    private boolean activeLineHasKorean() {
        AppliedLine line = activeLine();
        if (line != null && hasRomanizableScript(line.text)) return SpicyTextDetection.itemKoreanTest(line.text);
        return documentHasKorean();
    }

    private boolean activeLineHasCyrillic() {
        AppliedLine line = activeLine();
        if (line != null && hasRomanizableScript(line.text)) return SpicyTextDetection.itemCyrillicTest(line.text);
        return documentHasCyrillic();
    }

    private boolean hasRomanizableScript(String text) {
        return SpicyTextDetection.hasKana(text)
                || SpicyTextDetection.itemChineseTest(text)
                || SpicyTextDetection.itemKoreanTest(text)
                || SpicyTextDetection.itemCyrillicTest(text);
    }

    private AppliedLine activeLine() {
        if (document == null || document.appliedLines == null) return null;
        int index = followState.activeIndex();
        if (index < 0 || index >= document.appliedLines.size()) return null;
        AppliedLine line = document.appliedLines.get(index);
        return line == null || line.dotLine || line.bgLine ? null : line;
    }

    private boolean documentHasJapanese() { return documentHasReadingLanguage("ja"); }

    private boolean documentHasChinese() { return documentHasReadingLanguage("zh"); }

    private boolean documentHasReadingLanguage(String language) {
        if (document == null) return false;
        for (AppliedLine line : document.appliedLines) {
            if (language.equals(com.eza.spicyex.lyrics.ReadingLanguagePolicy.layoutLanguage(line))) return true;
        }
        return false;
    }

    private boolean documentHasKorean() {
        return document != null && SpicyTextDetection.detectPresentScripts(LyricsDocumentProcessor.collectText(document), document.language, "")
                .contains(SpicyTextDetection.Script.KOREAN);
    }

    private boolean documentHasCyrillic() {
        return document != null && SpicyTextDetection.detectPresentScripts(LyricsDocumentProcessor.collectText(document), document.language, "")
                .contains(SpicyTextDetection.Script.CYRILLIC);
    }

    private boolean documentHasRomanizableScript() {
        if (document == null) return false;
        List<SpicyTextDetection.Script> scripts = SpicyTextDetection.detectPresentScripts(
                LyricsDocumentProcessor.collectText(document), document.language, "");
        for (SpicyTextDetection.Script script : scripts) {
            switch (script) {
                case JAPANESE:
                    if (!"off".equals(renderConfig.japaneseModeConfig)) return true;
                    break;
                case CHINESE:
                    if (!"off".equals(renderConfig.chineseModeConfig)) return true;
                    break;
                case KOREAN:
                    if (!"Off".equals(renderConfig.koreanModeConfig)) return true;
                    break;
                case CYRILLIC:
                    if (!"Off".equals(renderConfig.cyrillicModeConfig)) return true;
                    break;
                default:
                    return true;
            }
        }
        return false;
    }

    private boolean documentHasTranslationCandidate() {
        if (document == null || document.lines == null) return false;
        for (LyricsLine line : document.lines) {
            if (line == null || isBlank(line.text) || line.interlude) continue;
            if (!isBlank(line.translatedText)) return true;
            if (SpicyProcessing.shouldTranslateLine(line.text, document.language, "en",
                    line.detection)) return true;
        }
        return false;
    }

    // Preserve script detection in the reading chip: あ / 拼·粤 / 한 / Я / Ω.
    private void updateRomanizationGlyph() {
        romanGlyph.setGlowing(showRomanization()
                && hasLayerOutput(com.eza.spicyex.lyrics.session.LayerKind.SOUND));
        if (!showRomanization()) {
            romanGlyph.setGlyph("A");
            return;
        }
        String source = "";
        if (document != null && followState.activeIndex() >= 0
                && followState.activeIndex() < document.appliedLines.size()) {
            AppliedLine line = document.appliedLines.get(followState.activeIndex());
            if (line != null && !line.dotLine && !isBlank(line.text)) source = line.text;
        }
        if (isBlank(source) && document != null) {
            source = LyricsDocumentProcessor.collectText(document);
        }
        romanGlyph.setGlyph(romanizationGlyphFor(source));
    }

    private String romanizationGlyphFor(String text) {
        String language = document == null ? "" : document.language;
        List<SpicyTextDetection.Script> scripts =
                SpicyTextDetection.detectPresentScripts(text, language, "");
        if (!scripts.isEmpty()) {
            switch (scripts.get(0)) {
                case JAPANESE: return "あ";
                case CHINESE:
                    return SpotifyPlusConfig.CHINESE_MODE_JYUTPING.equals(
                            LyricsShellSettings.normalizeChineseMode(chineseMode())) ? "粤" : "拼";
                case KOREAN: return "한";
                case CYRILLIC: return "Я";
                case GREEK: return "Ω";
            }
        }
        return "A";
    }

    private void onLikeTapped() {
        SpotifyTrack track = currentTrackThrottled();
        if (track == null || !SpotifyCollectionAction.isSong(track)
                || !SpotifyCollectionAction.enabled(likedMode)) {
            android.widget.Toast.makeText(activity,
                    uiText("lyrics_like_unavailable", "Liked Songs action unavailable"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.toggleSpotifySaved(likedMode, track)) {
            pendingLikedUri = safe(track.uri);
            applyLikedIconState(!track.saved);
        } else {
            android.widget.Toast.makeText(activity,
                    uiText("lyrics_like_unavailable", "Liked Songs action unavailable"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void updateLikedButton(SpotifyTrack track) {
        if (likeButton == null) return;
        likedMode = config.get(Settings.LIKED_SONGS_BUTTON);
        refreshLikedButton(track);
    }

    private void refreshLikedButton(SpotifyTrack track) {
        if (likeButton == null) return;
        com.eza.spicyex.ui.ActionIconDrawable.Kind kind =
                com.eza.spicyex.ui.ActionIconDrawable.likedSongsKind(likedMode);
        if (kind == null) {
            likeButton.setVisibility(View.GONE);
            lastLikedSaved = null;
            lastLikedKind = null;
            pendingLikedUri = "";
            return;
        }
        likeButton.setVisibility(View.VISIBLE);
        boolean saved = track != null && track.saved;
        if (track != null && !pendingLikedUri.isEmpty()) {
            if (!pendingLikedUri.equals(safe(track.uri))) {
                pendingLikedUri = "";
            } else if (lastLikedSaved != null && lastLikedSaved == track.saved) {
                pendingLikedUri = "";
            } else {
                return;
            }
        }
        applyLikedIconState(saved);
    }

    private void applyLikedIconState(boolean saved) {
        if (likeButton == null) return;
        com.eza.spicyex.ui.ActionIconDrawable.Kind kind =
                com.eza.spicyex.ui.ActionIconDrawable.likedSongsKind(likedMode);
        if (kind == null) {
            likeButton.setVisibility(View.GONE);
            lastLikedSaved = null;
            lastLikedKind = null;
            return;
        }
        if (lastLikedSaved != null && lastLikedSaved == saved && kind == lastLikedKind
                && likeButton.getVisibility() == View.VISIBLE) return;
        lastLikedSaved = saved;
        lastLikedKind = kind;
        float density = activity.getResources().getDisplayMetrics().density;
        boolean star = kind == com.eza.spicyex.ui.ActionIconDrawable.Kind.STAR;
        int savedColor = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);
        likeButton.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                kind, saved ? savedColor : Color.rgb(232, 232, 238), density, saved));
        likeButton.setContentDescription(saved
                ? uiText("lyrics_like_remove", "Remove from Liked Songs")
                : uiText("lyrics_like_add", "Add to Liked Songs"));
    }

    private void updateToggleVisuals() {
        boolean jp = documentHasJapanese();
        boolean cn = documentHasChinese();
        boolean romanizable = documentHasRomanizableScript();
        boolean aiSoundAvailable = aiSettings.soundLayerEnabled() && aiSettings.isConfigured();
        romanToggle.setVisibility(renderConfig.transliterationEnabled
                && (romanizable || aiSoundAvailable) ? View.VISIBLE : View.GONE);
        updateRomanizationGlyph();
        romanToggle.setContentDescription(jp ? "Toggle Japanese reading" : cn ? "Toggle Chinese transliteration" : "Toggle transliteration");
        textFactory.styleIconChip(romanToggle, showRomanization()
                && hasLayerOutput(com.eza.spicyex.lyrics.session.LayerKind.SOUND));
        translationToggle.setVisibility(renderConfig.translationEnabled && documentHasTranslationCandidate() ? View.VISIBLE : View.GONE);
        textFactory.styleIconChip(translationToggle, showTranslation()
                && hasLayerOutput(com.eza.spicyex.lyrics.session.LayerKind.MEANING));
        // A new track may settle while the frame scheduler sleeps. Sync authority badges here so
        // the previous track's AI mark never survives on an empty current layer.
        updateToggleSpinners();
    }
}
