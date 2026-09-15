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

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.CurrentLyricState;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.beautifullyrics.entities.VsyncFrameScheduler;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ChipSpinnerDrawable;
import com.eza.spicyex.lyrics.FrameStyleBatcher;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsAmbientController;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsFrameRenderer;
import com.eza.spicyex.lyrics.LyricsLineVisualController;
import com.eza.spicyex.lyrics.LyricsLineViewState;
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
    private final TextView title;
    private final TextView subtitle;
    private final android.widget.ImageView headerArt;
    private final View topFadeOverlay;
    private final TextView syncDebugOverlay;
    private long lastFrameWallClockMs;
    private long lastTracedTransitionDeltaMs;
    private long lastTracedTransitionAtMs;
    private long lastRawPositionSeenMs = Long.MIN_VALUE;
    private long lastRawPositionChangedAtMs;
    private boolean wasStalledLastFrame;
    private long forceContinuousUntilElapsedMs;
    private String pendingArtImageId = "";
    private String lastArtImageId = "";
    private String heldHeaderTitle;
    private String heldHeaderArtist;
    private String heldHeaderAlbum;
    private long headerHeldSinceElapsedMs;
    private boolean driftRed;
    private final TextView progress;
    private final TextView status;
    private final ImageButton playPauseButton;
    private final ImageButton romanToggle;
    private final ImageButton translationToggle;
    private Boolean playPauseIconShowsPlaying;
    private final ImageButton saveToggle;
    private int chromeIconColor;
    private float chromeIconDensity;
    private Boolean lastDisplayedSaved;
    private String pendingSavedUri = "";
    private final LyricsJumpToCurrentController jumpToCurrentController;
    private LyricsSkipController skipController;
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
    private final SettingsUiStrings uiStrings;
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
    private LyricsRowMountController rowMountController;
    private LinearLayout contentColumn;
    private LinearLayout landscapeRightColumn;
    private android.widget.ImageView landscapeArt;
    private View headerArtWrapper;
    private int appliedHeaderArtSizeDp = -1;
    private ViewGroup chromeHeader;
    private final Runnable hideChromeRunnable = this::hideChrome;
    private boolean scrollInProgress;
    private boolean scrollSettleScheduled;
    private long lastScrollEventMs;
    private boolean wasScrollHeldLastFrame;
    private long scrollHoldReleasedAtMs = Long.MIN_VALUE;
    private static final long SCROLL_HOLD_RENDER_GRACE_MS = 400;
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

    // revealChrome() was previously only wired to lyricsScroll's own touch listener, which only
    // sees touches that land on the scrollable lyrics content itself - a tap on the header row,
    // artwork, or any empty margin outside that one child never reached it, leaving the chrome
    // stuck hidden until a touch happened to land in exactly the right place. This class is
    // itself the screen's single top-level container (extends FrameLayout, hosts every child),
    // so intercepting here at dispatch time - purely observing, never consuming - reveals chrome
    // on any touch anywhere on the lyrics screen without changing how any child handles its own
    // gestures.
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) revealChrome();
        return super.dispatchTouchEvent(ev);
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
        applyRenderConfigChanges("preference changed", false);
        ambientController.applySettings(renderConfig.backgroundStyle, renderConfig.forceDarkBackground);
        revealChrome();
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
    private long lastLyricPositionMs = -1;
    private long introSkipTargetMs = -1;
    private long outroBoundaryMs = -1;
    private boolean autoSkippedIntro;
    private boolean autoSkippedOutro;
    private long lastDisplayedProgressSecond = Long.MIN_VALUE;
    private String lastDisplayedTitle = "";
    private String lastDisplayedArtist = "";
    private String lastDisplayedAlbum = "";
    private boolean autoResumeFollow;
    private boolean autoSkipIntroOutro;
    private boolean showSaveButton = true;
    private String saveButtonIcon = "Heart";
    private boolean beatReactiveBackground;
    private boolean adaptiveLandscapeLayout = true;
    private boolean slideAnimationEnabled;
    private static final float ROW_CASCADE_STAGGER_SEC = 0.06f;
    private static final float ROW_CASCADE_MAX_DELAY_SEC = 0.42f;
    private static final float ROW_CASCADE_FREQUENCY_HZ = 1.5f;
    private static final float ROW_CASCADE_DAMPING = 0.92f;
    private static final float ROW_CASCADE_MAX_OFFSET_PX = 900f;
    private static final long ROW_CASCADE_MAX_LIFETIME_MS = 2200L;
    private final Map<AppliedLine, RowCascade> rowCascades = new WeakHashMap<>();
    private boolean applyingLyricScroll;
    private boolean forcePlainScrollNext;

    private static final class RowCascade {
        final Spring spring;
        final long startedAtMs;
        float delayRemaining;

        RowCascade(float startOffset, float delaySeconds, float frequencyHz, float damping) {
            spring = new Spring(startOffset, frequencyHz, damping);
            spring.setGoal(0f);
            delayRemaining = delaySeconds;
            startedAtMs = SystemClock.uptimeMillis();
        }
    }

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

    // Below this width/height ratio a "landscape" screen is treated as still effectively square
    // (an unfolded foldable's outer/inner screens both report ORIENTATION_LANDSCAPE well before
    // they're actually wide enough for the side-by-side artwork layout to make sense) - stays on
    // the normal portrait-style stacked layout instead.
    private static final float LANDSCAPE_ASPECT_RATIO_MIN = 1.2f;

    private boolean isLandscape() {
        if (getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return false;
        }
        if (!adaptiveLandscapeLayout) return false;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float ratio = metrics.widthPixels / (float) Math.max(1, metrics.heightPixels);
        return ratio >= LANDSCAPE_ASPECT_RATIO_MIN;
    }

    private void applyStatusBarVisibility() {
        try {
            Window window = activity == null ? null : activity.getWindow();
            if (window == null) return;
            boolean hide = isLandscape();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller == null) return;
                if (hide) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.statusBars());
                } else {
                    controller.show(WindowInsets.Type.statusBars());
                }
            } else {
                View decor = window.getDecorView();
                int statusBarFlags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                int flags = decor.getSystemUiVisibility();
                decor.setSystemUiVisibility(hide ? (flags | statusBarFlags) : (flags & ~statusBarFlags));
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " applyStatusBarVisibility failed: " + t);
        }
    }

    private static final float HEADER_TITLE_ALPHA = 0.92f;
    private static final float HEADER_SUBTITLE_ALPHA = 0.72f;
    private static final long HEADER_TEXT_FADE_OUT_MS = 110L;
    private static final long HEADER_TEXT_FADE_IN_MS = 200L;
    private static final long HEADER_ART_FADE_MS = 260L;
    private static final long HEADER_HOLD_TIMEOUT_MS = 3000L;
    private static final long DRIFT_WARN_THRESHOLD_MS = 750L;
    private static final int PROGRESS_TEXT_COLOR = Color.rgb(210, 210, 210);
    private static final int DRIFT_TEXT_COLOR = Color.rgb(255, 90, 90);

    private void crossfadeHeaderText(TextView view, String newText, float restingAlpha) {
        if (view == null) return;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(HEADER_TEXT_FADE_OUT_MS).withEndAction(() -> {
            setTextIfChanged(view, newText);
            view.setSelected(false);
            view.setSelected(true);
            view.setAlpha(0f);
            view.animate().alpha(restingAlpha).setDuration(HEADER_TEXT_FADE_IN_MS).start();
        }).start();
    }

    private void crossfadeHeaderArt(android.widget.ImageView art, android.graphics.Bitmap bitmap) {
        if (art == null) return;
        art.animate().cancel();
        if (art.getVisibility() != VISIBLE || art.getAlpha() <= 0.01f) {
            art.setImageBitmap(bitmap);
            art.setAlpha(0f);
            art.setVisibility(VISIBLE);
            art.animate().alpha(1f).setDuration(HEADER_ART_FADE_MS).start();
            return;
        }
        art.animate().alpha(0f).setDuration(HEADER_ART_FADE_MS / 2).withEndAction(() -> {
            art.setImageBitmap(bitmap);
            art.animate().alpha(1f).setDuration(HEADER_ART_FADE_MS / 2).start();
        }).start();
    }

    private void flushHeldHeader() {
        String t = heldHeaderTitle;
        String a = heldHeaderArtist;
        String al = heldHeaderAlbum;
        heldHeaderTitle = null;
        heldHeaderArtist = null;
        heldHeaderAlbum = null;
        headerHeldSinceElapsedMs = 0;
        if (t != null && !t.equals(lastDisplayedTitle)) {
            lastDisplayedTitle = t;
            crossfadeHeaderText(title, t, HEADER_TITLE_ALPHA);
        }
        if (a != null && (!a.equals(lastDisplayedArtist) || (al != null && !al.equals(lastDisplayedAlbum)))) {
            lastDisplayedArtist = a;
            lastDisplayedAlbum = al == null ? "" : al;
            crossfadeHeaderText(subtitle, a, HEADER_SUBTITLE_ALPHA);
        }
    }

    private void updateDriftTint(SpotifyTrack track, boolean playingNow, long clockPosMs) {
        boolean drifted = false;
        if (playingNow && clockPosMs >= 0 && !host.isSeekOverrideActive()) {
            long raw = host.readBestMeasuredProgressMs(track, true);
            drifted = raw >= 0 && Math.abs(clockPosMs - raw) > DRIFT_WARN_THRESHOLD_MS;
        }
        if (drifted != driftRed) {
            driftRed = drifted;
            progress.setTextColor(drifted ? DRIFT_TEXT_COLOR : PROGRESS_TEXT_COLOR);
        }
    }

    private static final int SAVED_HEART_COLOR = Color.rgb(255, 55, 95);
    private static final int SAVED_STAR_COLOR = Color.rgb(255, 214, 10);

    private void applySaveIconState(boolean saved) {
        if (saveToggle == null) return;
        if (lastDisplayedSaved != null && lastDisplayedSaved == saved) return;
        lastDisplayedSaved = saved;
        boolean star = "Star".equals(saveButtonIcon);
        int savedColor = star ? SAVED_STAR_COLOR : SAVED_HEART_COLOR;
        saveToggle.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                star ? com.eza.spicyex.ui.ActionIconDrawable.Kind.STAR
                        : com.eza.spicyex.ui.ActionIconDrawable.Kind.HEART,
                saved ? savedColor : chromeIconColor, chromeIconDensity, saved));
        saveToggle.setContentDescription(saved ? "Remove from Liked Songs" : "Add to Liked Songs");
    }

    // Above this, currentAudioLevel() is treated as "genuine audio is playing right now" for the
    // clockPlayingNow rescue in updateState() - low enough to catch normal playback well under a
    // quiet passage's level, high enough that Visualizer noise floor at true silence won't trip it.
    private static final float AUDIO_ACTIVE_LEVEL_THRESHOLD = 0.04f;
    private boolean lastAudioRescueActive;
    private static final long SKIP_INTRO_MIN_GAP_MS = 6000L;
    private static final long SKIP_OUTRO_MIN_GAP_MS = 5000L;
    private static final long SKIP_OUTRO_MIN_REMAINING_MS = 4000L;
    private String lastAutoSeekKey = "";
    private long lastAutoSeekFromMs = Long.MIN_VALUE;
    // Sticky per-track/target ack for a manually-tapped Skip Intro/Next Track: unlike
    // claimAutoSeek's 5s window (fine for the one-shot auto path), a manual tap must stay hidden
    // for as long as it takes the seek to actually land - which can be much longer than 5s over a
    // Spotify Connect device - not just for a fixed grace period. Cleared implicitly the moment
    // the seek lands, because the outer lyricPos check that guards this whole block goes false.
    private String manuallySkippedKey = "";

    private boolean claimAutoSeek(String key, long fromMs) {
        if (key.equals(lastAutoSeekKey) && fromMs >= lastAutoSeekFromMs - 5000) return false;
        lastAutoSeekKey = key;
        lastAutoSeekFromMs = fromMs;
        return true;
    }

    private void updateSkipChip(long lyricPos, long durationMs, long rawPosMs) {
        // Unknown position clamps to 0 upstream (adjustedPositionMs), which would satisfy the
        // intro condition below forever - the pill shows and can never hide since the position
        // never advances. No known position, no chip.
        if (rawPosMs < 0) {
            if (skipController != null) skipController.hide();
            return;
        }
        if (host.canSeek() && introSkipTargetMs > SKIP_INTRO_MIN_GAP_MS
                && lyricPos < introSkipTargetMs - SKIP_INTRO_MIN_GAP_MS) {
            long seekTarget = renderConfig == null
                    ? introSkipTargetMs : renderConfig.playbackPositionForLyricMs(introSkipTargetMs);
            String seekKey = lastUri + "#intro@" + seekTarget;
            Runnable action = () -> host.seekSpotifyTo(Math.max(0, seekTarget));
            if (autoSkipIntroOutro) {
                if (!autoSkippedIntro && claimAutoSeek(seekKey, lyricPos)) {
                    autoSkippedIntro = true;
                    action.run();
                }
                if (skipController != null) skipController.hide();
            } else if (skipController != null) {
                // A tap already fired this exact seek but the playback-position poll can lag
                // behind it - possibly a long while over a Spotify Connect device - before it
                // reflects the new position. Without this the pill would hide on tap and
                // immediately reappear next poll since lyricPos still satisfies the outer
                // condition. Sticks until the seek actually lands (the outer condition then goes
                // false on its own), not just for a fixed grace period.
                if (seekKey.equals(manuallySkippedKey)) {
                    skipController.hide();
                } else {
                    skipController.show("Skip Intro", () -> {
                        manuallySkippedKey = seekKey;
                        action.run();
                    });
                }
            }
            return;
        }
        if (host.canSkipToNext() && outroBoundaryMs >= 0 && durationMs > 0
                && lyricPos > outroBoundaryMs + SKIP_OUTRO_MIN_GAP_MS
                && (durationMs - lyricPos) > SKIP_OUTRO_MIN_REMAINING_MS) {
            String seekKey = lastUri + "#outro";
            if (autoSkipIntroOutro) {
                if (!autoSkippedOutro && claimAutoSeek(seekKey, lyricPos)) {
                    autoSkippedOutro = true;
                    host.skipToNextTrack();
                }
                if (skipController != null) skipController.hide();
            } else if (skipController != null) {
                if (seekKey.equals(manuallySkippedKey)) {
                    skipController.hide();
                } else {
                    skipController.show("Next Track", () -> {
                        manuallySkippedKey = seekKey;
                        host.skipToNextTrack();
                    });
                }
            }
            return;
        }
        if (skipController != null) skipController.hide();
    }

    private void restoreStatusBar() {
        try {
            Window window = activity == null ? null : activity.getWindow();
            if (window == null) return;
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) controller.show(WindowInsets.Type.statusBars());
            } else {
                View decor = window.getDecorView();
                int statusBarFlags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~statusBarFlags);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " restoreStatusBar failed: " + t);
        }
    }

    private int chromeButtonDp() {
        // Landscape has more width to spend, not less - shrinking these below the portrait size
        // (as before) just made them harder to hit for no reason.
        return isLandscape() ? 44 : 40;
    }

    private int lyricsTopPaddingDp() {
        return isLandscape() ? 10 : 22;
    }

    private int lyricsBottomPaddingDp() {
        return isLandscape() ? 86 : 118;
    }

    private int headerArtSizeDp() {
        return isLandscape() ? 54 : config.get(Settings.HEADER_ART_SIZE_DP);
    }

    private int headerTitleSp() {
        return isLandscape() ? 24 : 32;
    }

    private int headerSubtitleSp() {
        return isLandscape() ? 15 : 21;
    }

    private LinearLayout rowContainer() {
        return landscapeRightColumn != null ? landscapeRightColumn : contentColumn;
    }

    private void applyContentColumnPadding() {
        if (contentColumn == null) return;
        int side = sideSystemPadding(activity);
        int bottom = dp(isLandscape() ? 6 : 10);
        if (contentColumn.getPaddingLeft() != side || contentColumn.getPaddingBottom() != bottom) {
            contentColumn.setPadding(side, 0, side, bottom);
        }
    }

    private static void setupHeaderMarquee(TextView view) {
        view.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1);
        view.setSingleLine(true);
        view.setHorizontalFadingEdgeEnabled(true);
        view.setSelected(true);
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

    private final VsyncFrameScheduler frameScheduler = new VsyncFrameScheduler(deltaTimeSeconds -> {
        if (!running) return;
        float dt = deltaTimeSeconds <= 0d ? (1f / 60f) : (float) Math.max(0.001d, Math.min(0.08d, deltaTimeSeconds));
        stepRowCascade(dt);
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
        // Read before any of the isLandscape()-dependent layout below runs (its first real use is
        // just a few lines down inside this same constructor).
        this.adaptiveLandscapeLayout = config.get(Settings.ADAPTIVE_LANDSCAPE_LAYOUT);
        this.uiStrings = new SettingsUiStrings(activity, config.get(Settings.UI_LANGUAGE));
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
            host.markExplicitLyricsExit(activity);
            activity.finish();
        });
        SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        preferences = prefs;
        renderConfig = LyricsRenderConfig.read(activity, config);
        autoResumeFollow = config.get(Settings.AUTO_RESUME_FOLLOW);
        autoSkipIntroOutro = config.get(Settings.AUTO_SKIP_INTRO_OUTRO);
        slideAnimationEnabled = config.get(Settings.LINE_SLIDE_ANIMATION);
        showSaveButton = config.get(Settings.SHOW_SAVE_BUTTON);
        saveButtonIcon = config.get(Settings.SAVE_BUTTON_ICON);
        beatReactiveBackground = config.get(Settings.BEAT_REACTIVE_BACKGROUND);
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
                renderConfig.forceDarkBackground);

        contentColumn = new LinearLayout(activity);
        contentColumn.setOrientation(isLandscape()
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        contentColumn.setGravity(isLandscape() ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        contentColumn.setClipChildren(false);
        contentColumn.setClipToPadding(false);
        applyContentColumnPadding();
        addView(contentColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (isLandscape()) {
            landscapeArt = new android.widget.ImageView(activity);
            landscapeArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            landscapeArt.setVisibility(GONE);
            landscapeArt.setClipToOutline(true);
            landscapeArt.setElevation(dp(16));
            landscapeArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
                }
            });
            landscapeArt.setClickable(true);
            LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f);
            artLp.setMargins(0, dp(32), dp(20), dp(32));
            artLp.gravity = Gravity.CENTER_VERTICAL;
            wrapArtworkWithTapReveal(landscapeArt, contentColumn, artLp, dp(20));
            landscapeRightColumn = new LinearLayout(activity);
            landscapeRightColumn.setOrientation(LinearLayout.VERTICAL);
            landscapeRightColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            landscapeRightColumn.setClipChildren(false);
            landscapeRightColumn.setClipToPadding(false);
            contentColumn.addView(landscapeRightColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
        }
        contentColumn.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                 oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)) applyContentColumnPadding();
        });

        int chromeButton = chromeButtonDp();
        LyricsShellChromeController.ChromeViews chrome = LyricsShellChromeController.attach(
                activity,
                this,
                textFactory,
                romanGlyph,
                romanSpinner,
                translationSpinner,
                chromeButton,
                isLandscape(),
                () -> {
                    host.markExplicitLyricsExit(activity);
                    activity.finish();
                },
                this::onPlayPauseButtonPressed,
                () -> cycleTransliterationMode(prefs),
                () -> {
                    if (renderConfig != null && !renderConfig.translationEnabled) return;
                    boolean wasVisible = showTranslation();
                    boolean hasDisplayedMeaning = hasLayerOutput(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING);
                    boolean requestedOutput = shouldGenerateAi(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING);
                    if (requestedOutput && !requestAiLayerWithFeedback(
                            com.eza.spicyex.lyrics.session.LayerKind.MEANING)) {
                        return;
                    }
                    if (transliterationSession.keepVisibleForRequestedOutput(
                            requestedOutput, wasVisible, hasDisplayedMeaning)) {
                        return;
                    }
                    showTranslation = !showTranslation;
                    prefs.edit().putBoolean(Settings.NATIVE_SPICY_TRANSLATION.key, showTranslation).apply();
                    updateToggleVisuals();
                    applyTranslationVisibilityToMountedRows();
                },
                () -> {
                    SpotifyTrack track = host.getCurrentTrackSafely();
                    if (track == null) return;
                    pendingSavedUri = safe(track.uri);
                    applySaveIconState(!track.saved);
                    host.toggleSavedTrack();
                },
                () -> settingsDialogController.show());
        chromeHeader = chrome.header;
        playPauseButton = chrome.playPauseButton;
        romanToggle = chrome.romanToggle;
        translationToggle = chrome.translationToggle;
        saveToggle = chrome.saveToggle;
        saveToggle.setVisibility(showSaveButton ? View.VISIBLE : GONE);
        chromeIconColor = chrome.iconColor;
        chromeIconDensity = chrome.density;
        romanToggle.setOnClickListener(v -> {
            // Sound tap belongs to the local reading pipeline. In cycle mode it must advance
            // Pinyin/Jyutping (or the equivalent local language modes), never start a paid AI run.
            // Automatic AI can still fill local-language gaps, and long press owns explicit AI.
            cycleTransliterationMode(prefs);
        });
        romanToggle.setOnLongClickListener(v -> {
            openAiLayerPanel(com.eza.spicyex.lyrics.session.LayerKind.SOUND);
            return true;
        });
        translationToggle.setOnLongClickListener(v -> {
            openAiLayerPanel(com.eza.spicyex.lyrics.session.LayerKind.MEANING);
            return true;
        });
        updateToggleVisuals();

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        headerArt = new android.widget.ImageView(activity);
        headerArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        headerArt.setVisibility(GONE);
        int headerArtSizePx = dp(headerArtSizeDp());
        int headerArtRadiusPx = dp(11);
        headerArt.setClipToOutline(true);
        headerArt.setElevation(dp(16));
        headerArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), headerArtRadiusPx);
            }
        });
        headerArt.setClickable(true);
        LinearLayout.LayoutParams headerArtLp = new LinearLayout.LayoutParams(headerArtSizePx, headerArtSizePx);
        headerArtLp.setMarginEnd(dp(12));
        headerArtWrapper = wrapArtworkWithTapReveal(headerArt, headerRow, headerArtLp, headerArtRadiusPx);
        appliedHeaderArtSizeDp = headerArtSizeDp();

        LinearLayout headerText = new LinearLayout(activity);
        headerText.setOrientation(LinearLayout.VERTICAL);

        title = textFactory.createText(activity, "Waiting for Spotify track…", headerTitleSp(), Color.WHITE, textFactory.resolveTypeface(true));
        title.setVisibility(GONE);
        title.setGravity(Gravity.START);
        title.setMaxLines(1);
        title.setAlpha(HEADER_TITLE_ALPHA);
        setupHeaderMarquee(title);
        headerText.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitle = textFactory.createText(activity, "Open playback, then fullscreen lyrics", headerSubtitleSp(), Color.rgb(190, 190, 190), textFactory.resolveTypeface(false));
        subtitle.setVisibility(GONE);
        subtitle.setGravity(Gravity.START);
        subtitle.setMaxLines(1);
        subtitle.setAlpha(HEADER_SUBTITLE_ALPHA);
        setupHeaderMarquee(subtitle);
        headerText.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        headerRow.addView(headerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (isLandscape()) headerRow.setPadding(dp(20), 0, 0, 0);
        LinearLayout.LayoutParams headerRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerRowLp.topMargin = topSystemPadding(activity) + dp(chromeButtonDp() + (isLandscape() ? 4 : 8));
        rowContainer().addView(headerRow, headerRowLp);

        lyricsScroll = new ScrollView(activity);
        lyricsScroll.setFillViewport(false);
        lyricsScroll.setClipToPadding(false);
        lyricsScroll.setClipChildren(false);
        applyLyricsScrollPadding();
        lyricsScroll.setVerticalFadingEdgeEnabled(false);
        lyricsScroll.setFadingEdgeLength(0);
        lyricsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        LyricsTapSeekHandler tapSeekHandler = new LyricsTapSeekHandler(
                activity,
                config,
                followState::holdUntil,
                followState::setTouching,
                this::seekNearestLineAt);
        lyricsScroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) revealChrome();
            return tapSeekHandler.onTouch(view, event);
        });
        lyricsFrame = new FrameLayout(activity);
        lyricsColumn = new LinearLayout(activity);
        lyricsColumn.setOrientation(LinearLayout.VERTICAL);
        lyricsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsColumn.setClipChildren(false);
        lyricsColumn.setClipToPadding(false);

        // Clears the button row + header art/title dock above the scrolling lyrics; shrunk from
        // 96 to 40 alongside HEADER_ART_SIZE_DP's new smaller default (132dp -> 72dp portrait).
        topStaticSpacer = new LyricsSpaceView(activity, dp(isLandscape() ? 48 : 40));
        topVirtualSpacer = new LyricsSpaceView(activity, 0);
        mountedRowsHost = new LinearLayout(activity);
        mountedRowsHost.setOrientation(LinearLayout.VERTICAL);
        mountedRowsHost.setGravity(Gravity.CENTER_HORIZONTAL);
        mountedRowsHost.setClipChildren(false);
        mountedRowsHost.setClipToPadding(false);
        mountedRowsHost.setLayoutTransition(rowExitLayoutTransition());
        secondaryRowUpdater = new LyricsSecondaryRowUpdater(mountedRowsHost, lineVisualController::invalidate,
                this::appendTranslationView, this::appendRomanView,
                rowViewFactory::removeTranslationView, rowViewFactory::removeRomanView);
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
        scrollController.setRaisedAnchor(slideAnimationEnabled);
        frameRenderer.setScrollController(scrollController);
        applyLyricsScrollPadding();

        ensureLyricsColumnScaffold();
        lyricsScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!running) return;
            if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
            if (!applyingLyricScroll && scrollY != oldScrollY && !rowCascades.isEmpty()) {
                clearRowCascade();
            }
            scrollInProgress = true;
            frameScheduler.setContinuous(true);
            frameScheduler.requestFrame();
            scheduleScrollWindowRender();
            scheduleScrollSettleRemeasure();
        });
        lyricsScroll.addView(lyricsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsFrame.addView(lyricsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        topFadeOverlay = new View(activity);
        topFadeOverlay.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.BLACK, Color.TRANSPARENT}));
        topFadeOverlay.setVisibility(GONE);
        FrameLayout.LayoutParams topFadeOverlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP);
        lyricsFrame.addView(topFadeOverlay, topFadeOverlayLp);
        lyricsFrame.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = Math.round((bottom - top) * 0.07f);
            ViewGroup.LayoutParams lp = topFadeOverlay.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                topFadeOverlay.setLayoutParams(lp);
            }
        });
        syncDebugOverlay = new TextView(activity);
        syncDebugOverlay.setTextSize(11);
        syncDebugOverlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        syncDebugOverlay.setTextColor(Color.WHITE);
        syncDebugOverlay.setBackgroundColor(0xAA000000);
        syncDebugOverlay.setPadding(dp(8), dp(4), dp(8), dp(4));
        syncDebugOverlay.setVisibility(GONE);
        syncDebugOverlay.setElevation(dp(32));
        FrameLayout.LayoutParams syncDebugOverlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lyricsFrame.addView(syncDebugOverlay, syncDebugOverlayLp);
        jumpToCurrentController = LyricsJumpToCurrentController.attach(
                activity,
                lyricsFrame,
                textFactory,
                this::resumeFollowCurrentLine);
        skipController = LyricsSkipController.attach(activity, lyricsFrame);
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
        // The lyrics animation pipeline runs entirely on Choreographer callbacks on this thread
        // (Spotify's main/UI thread), not a dedicated one - there's nothing else to give a
        // priority boost to. Nudging this thread's own scheduling priority up (still well below
        // any true realtime class) gives it a better chance of winning CPU contention against
        // other work sharing the device's cores while the lyrics screen is up - including our own
        // :gecko process tree - measured live as frequent, sometimes 500ms-1000ms+, frame stalls
        // (LyricsFrameTracer's jank log) that read on screen as lines snapping instead of easing.
        // Restored to default in stop() so it doesn't linger over Spotify's other UI work.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (Throwable ignored) {
        }
        applyStatusBarVisibility();
        revealChrome();
        documentGate.start();
        registerPreferenceListener();
        sessionSubscription = host.subscribeLyricsSession(sessionListener);
        shellLifecycle.start();
        playbackClock.reset("");
        updateState(1f / 60f);
        frameScheduler.start();
        // The audio-reactive Visualizer is real, continuous per-frame work (FFT/waveform capture)
        // for as long as it's attached - only worth paying for while this screen (the only reader
        // of currentAudioLevel()) is actually up AND Beat-reactive background is actually on to
        // consume it. Beat-reactive background now defaults off, so leaving this unconditional
        // would mean everyone pays that continuous capture cost for a level nobody reads -
        // updateState() already discards it when the setting is off (see beatReactiveBackground).
        host.setAudioReactiveListening(beatReactiveBackground);
    }

    void stop() {
        dbgEnter("NativeSpicyShellView.stop");
        running = false;
        host.setAudioReactiveListening(false);
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        } catch (Throwable ignored) {
        }
        restoreStatusBar();
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
        handler.removeCallbacks(idleFrameProbe);
        visuallySettledFrames = 0;
        playbackClock.reset("");
        handler.removeCallbacks(scrollSettleRunnable);
        scrollSettleScheduled = false;
        scrollInProgress = false;
        handler.removeCallbacksAndMessages(null);
        chromeRevealAnimating = false;
        if (chromeHeader != null) chromeHeader.animate().cancel();
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

    // Single tap must act immediately: flip the icon optimistically instead of waiting for the
    // async MediaSession callback that isPlayerActuallyPlaying() reads, so one press is never
    // silently swallowed while a stale playback state catches up.
    private void onPlayPauseButtonPressed() {
        boolean wasPlaying = host.isPlayerActuallyPlaying();
        host.togglePlayback();
        updatePlayPauseIcon(!wasPlaying);
        revealChrome();
    }

    private static void suppressElevationShadow(View view) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return;
        view.setOutlineAmbientShadowColor(Color.TRANSPARENT);
        view.setOutlineSpotShadowColor(Color.TRANSPARENT);
    }

    private static final long ARTWORK_REVEAL_HIDE_DELAY_MS = 1800L;
    private static final long ARTWORK_ICON_MORPH_OUT_MS = 110L;
    private static final long ARTWORK_ICON_MORPH_IN_DELAY_MS = 40L;
    private static final long ARTWORK_ICON_MORPH_IN_MS = 260L;
    private static final android.view.animation.Interpolator ARTWORK_ICON_MORPH_SPRING =
            new android.view.animation.OvershootInterpolator(2.6f);
    private static final long ARTWORK_FADE_MS = 150L;

    /**
     * Wraps an artwork ImageView in a FrameLayout so tapping it darkens it and reveals a
     * play/pause icon (matching the art's own rounded corners, at a fixed opaque darken so it
     * reads the same regardless of how bright the art underneath is); tapping that icon toggles
     * playback, morphs (cross-fades) to the opposite icon immediately rather than waiting on the
     * host to confirm, and either way the overlay auto-hides after a short delay instead of
     * instantly. Adds the wrapper to {@code parent} with {@code lp} in the art view's place.
     */
    private View wrapArtworkWithTapReveal(android.widget.ImageView art, ViewGroup parent,
                                           ViewGroup.LayoutParams lp, int cornerRadiusPx) {
        FrameLayout wrapper = new FrameLayout(activity);
        parent.addView(wrapper, lp);
        wrapper.addView(art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(activity);
        // Solid, opaque-enough to read the same over any artwork brightness (a translucent
        // scrim looked "inconsistent" because how dark it appeared depended on the art itself).
        scrim.setBackgroundColor(0xB3000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(GONE);
        // Match the art's own rounded-corner outline, otherwise the scrim's square edges poke
        // out past the art's rounded corners.
        scrim.setClipToOutline(true);
        scrim.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
            }
        });
        // art carries its own elevation (dp(16), for its drop shadow) and Android z-orders by
        // elevation before child order - without matching/exceeding it here the scrim and reveal
        // icon render underneath the artwork and are invisible despite being "on top" in the tree.
        scrim.setElevation(dp(17));
        // Elevation also casts a drop shadow following the outline; on a view this large and
        // opaque that shadow reads as a hard square bleeding past the rounded corners. It's
        // wanted for z-order only here, not a shadow, so zero the shadow colors out (API 28+;
        // minSdk is 27, so this is best-effort and simply does nothing pre-28).
        suppressElevationShadow(scrim);
        wrapper.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // art's and scrim's outline providers compute the round-rect from the view's own
        // getWidth()/getHeight() - unlike the default background-outline, a custom
        // ViewOutlineProvider is NOT automatically recomputed when the view resizes (e.g. the
        // artwork-size setting resizing headerArtWrapper, or the very first layout pass landing
        // after the outline was already queried at 0x0). Without this, clipping keeps using the
        // stale/zero bounds and the rounded corners silently stop applying.
        View.OnLayoutChangeListener invalidateOutlineOnResize = (v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                v.invalidateOutline();
            }
        };
        art.addOnLayoutChangeListener(invalidateOutlineOnResize);
        scrim.addOnLayoutChangeListener(invalidateOutlineOnResize);

        float density = activity.getResources().getDisplayMetrics().density;
        int iconSize = dp(56);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        // Two stacked icons cross-faded between, instead of swapping one ImageButton's drawable
        // outright, so play<->pause reads as a transition rather than an instant swap. Both need
        // their own elevation above art's dp(16) too - without it they render behind the artwork
        // (invisible) exactly like the scrim did before it got one.
        android.widget.ImageView playIcon = new android.widget.ImageView(activity);
        playIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        playIcon.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                com.eza.spicyex.ui.ActionIconDrawable.Kind.PLAY, Color.WHITE, density, true));
        playIcon.setElevation(dp(18));
        suppressElevationShadow(playIcon);
        android.widget.ImageView pauseIcon = new android.widget.ImageView(activity);
        pauseIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        pauseIcon.setImageDrawable(new com.eza.spicyex.ui.ActionIconDrawable(
                com.eza.spicyex.ui.ActionIconDrawable.Kind.PAUSE, Color.WHITE, density, true));
        pauseIcon.setElevation(dp(18));
        suppressElevationShadow(pauseIcon);
        ImageButton revealButton = new ImageButton(activity);
        revealButton.setBackgroundColor(Color.TRANSPARENT);
        revealButton.setAlpha(0f);
        revealButton.setVisibility(GONE);
        revealButton.setElevation(dp(19));
        wrapper.addView(playIcon, iconLp);
        wrapper.addView(pauseIcon, new FrameLayout.LayoutParams(iconLp));
        wrapper.addView(revealButton, new FrameLayout.LayoutParams(iconLp));

        // Local, optimistic playing state: flipped the instant the icon is tapped instead of
        // waiting for host.isPlayerActuallyPlaying() to catch up (that lag is what made a tap
        // look like it "didn't update"). updateState() below keeps it honest against reality.
        boolean[] assumedPlaying = {Boolean.TRUE.equals(playPauseIconShowsPlaying) || host.isPlayerActuallyPlaying()};
        // A YouTube-style morph (true path interpolation between the triangle and the two bars)
        // needs hand-authored matching-topology path data; short of that, this is a spring-in
        // pop rather than a flat dissolve - the outgoing icon drops back and fades quickly while
        // the incoming one overshoots past its resting scale and settles, the same overshoot
        // spring language the Skip Intro pill already uses elsewhere in this shell. No rotation:
        // an earlier version spun the icons opposite directions on swap, which read as busy/
        // gimmicky rather than fluid.
        Runnable setIcon = () -> {
            android.widget.ImageView incoming = assumedPlaying[0] ? pauseIcon : playIcon;
            android.widget.ImageView outgoing = assumedPlaying[0] ? playIcon : pauseIcon;
            outgoing.animate().cancel();
            incoming.animate().cancel();
            incoming.setScaleX(0.4f);
            incoming.setScaleY(0.4f);
            outgoing.animate().alpha(0f).scaleX(0.72f).scaleY(0.72f)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setDuration(ARTWORK_ICON_MORPH_OUT_MS).start();
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(ARTWORK_ICON_MORPH_SPRING)
                    .setStartDelay(ARTWORK_ICON_MORPH_IN_DELAY_MS)
                    .setDuration(ARTWORK_ICON_MORPH_IN_MS).start();
        };
        // Both must start GONE, not just alpha 0 - a VISIBLE view at alpha 0 still occupies
        // nothing visually, but leaving one of these at its default VISIBLE with alpha 1 (i.e.
        // playIcon when the track happens to be paused already) is exactly what made the icon
        // show up immediately on first mount instead of only after a tap.
        playIcon.setAlpha(assumedPlaying[0] ? 0f : 1f);
        pauseIcon.setAlpha(assumedPlaying[0] ? 1f : 0f);
        playIcon.setVisibility(GONE);
        pauseIcon.setVisibility(GONE);

        // Generation token: a hide() in flight when a new show() lands must not let its delayed
        // "now set GONE" step apply after the show already made the overlay visible again -
        // that race is what made the reveal look inconsistent under quick repeated taps.
        int[] generation = {0};
        Runnable[] hideRef = new Runnable[1];
        Runnable hide = () -> {
            wrapper.removeCallbacks(hideRef[0]);
            int myGen = ++generation[0];
            scrim.animate().cancel();
            revealButton.animate().cancel();
            playIcon.animate().cancel();
            pauseIcon.animate().cancel();
            scrim.animate().alpha(0f).setDuration(ARTWORK_FADE_MS)
                    .withEndAction(() -> { if (generation[0] == myGen) scrim.setVisibility(GONE); }).start();
            revealButton.animate().alpha(0f).setDuration(ARTWORK_FADE_MS)
                    .withEndAction(() -> { if (generation[0] == myGen) revealButton.setVisibility(GONE); }).start();
            // Whichever of these is currently showing (alpha 1) must fade with the scrim - this
            // was the actual bug behind "the icon stays floating on the image": hide() faded the
            // scrim and the (invisible) click target, but never the visible icon layers.
            playIcon.animate().alpha(0f).setDuration(ARTWORK_FADE_MS)
                    .withEndAction(() -> { if (generation[0] == myGen) playIcon.setVisibility(GONE); }).start();
            pauseIcon.animate().alpha(0f).setDuration(ARTWORK_FADE_MS)
                    .withEndAction(() -> { if (generation[0] == myGen) pauseIcon.setVisibility(GONE); }).start();
        };
        hideRef[0] = hide;
        Runnable scheduleHide = () -> {
            wrapper.removeCallbacks(hideRef[0]);
            wrapper.postDelayed(hideRef[0], ARTWORK_REVEAL_HIDE_DELAY_MS);
        };
        Runnable show = () -> {
            ++generation[0];
            scrim.animate().cancel();
            revealButton.animate().cancel();
            scrim.setVisibility(VISIBLE);
            revealButton.setVisibility(VISIBLE);
            playIcon.setVisibility(VISIBLE);
            pauseIcon.setVisibility(VISIBLE);
            scrim.animate().alpha(1f).setDuration(ARTWORK_FADE_MS).start();
            revealButton.animate().alpha(1f).setDuration(ARTWORK_FADE_MS).start();
            // Icon alpha/scale/rotation for this reveal is already handled by setIcon(), called
            // right before show() on every path that shows the overlay - both are made VISIBLE
            // here (rather than only whichever is currently at alpha 1) since the outgoing one
            // is mid-fade from a possible earlier hide() and still needs to render at all.
        };
        art.setOnClickListener(v -> {
            assumedPlaying[0] = Boolean.TRUE.equals(playPauseIconShowsPlaying) || host.isPlayerActuallyPlaying();
            setIcon.run();
            show.run();
            scheduleHide.run();
        });
        scrim.setOnClickListener(v -> hide.run());
        revealButton.setOnClickListener(v -> {
            assumedPlaying[0] = !assumedPlaying[0];
            setIcon.run();
            onPlayPauseButtonPressed();
            scheduleHide.run();
        });
        return wrapper;
    }

    private void updatePlayPauseIcon(boolean playing) {
        // playPauseIconShowsPlaying is read by the artwork reveal even though the header no
        // longer has its own button - it must keep tracking regardless of playPauseButton.
        if (Boolean.valueOf(playing).equals(playPauseIconShowsPlaying)) return;
        playPauseIconShowsPlaying = playing;
        if (playPauseButton == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        playPauseButton.setImageDrawable(new ActionIconDrawable(
                playing ? ActionIconDrawable.Kind.PAUSE : ActionIconDrawable.Kind.PLAY,
                Color.rgb(232, 232, 238), density, true));
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

    private void updateState(float deltaSeconds) {
        SpotifyTrack track = currentTrackThrottled();
        boolean playingNow = host.isPlayerActuallyPlaying();
        ambientController.setPlaying(playingNow);
        // Spotify retired the audio-features endpoint the old tempo-driven pulse relied on (see
        // the removed TrackTempoFetcher), so this live audio-level reading is the only working
        // signal left for the shader's beat reactivity - gate it on the setting directly instead
        // of leaving the setting controlling only the now-deleted dead tempo fetch, which made it
        // do nothing at all.
        ambientController.updateAudioLevel(beatReactiveBackground ? host.currentAudioLevel() : 0f);
        updatePlayPauseIcon(playingNow);
        updateJumpToCurrentVisibility();
        updateToggleSpinners();
        if (track == null) {
            setTextIfChanged(title, "Waiting for Spotify track…");
            setTextIfChanged(subtitle, "Player state hook has not emitted yet");
            setTextIfChanged(progress, "--:--");
            if (driftRed) {
                driftRed = false;
                progress.setTextColor(PROGRESS_TEXT_COLOR);
            }
            setTextIfChanged(status, "Native Spicy renderer mounted. Waiting for player state.");
            updateFrameDemand(false);
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
            rowViewFactory.setBgLineTextColor(ambientController.secondaryTextColor());
            lineVisualController.setBgLineTextColor(ambientController.secondaryTextColor());
            // Same class of bug as the header artwork fetch fixed earlier: title/subtitle are
            // core display, not something that should disappear just because the (now off-by-
            // default) Apple-style slide animation is off. Once slideAnimationEnabled defaulted
            // false, this made the header title/artist vanish for everyone who hadn't opted into
            // that animation - crossfadeHeaderText (the immediate-update path taken when the
            // slide/hold dance is off) only ever animates alpha, never visibility, so a view left
            // GONE here never came back.
            title.setVisibility(VISIBLE);
            subtitle.setVisibility(VISIBLE);
            topFadeOverlay.setVisibility(GONE);
            pendingArtImageId = "";
            heldHeaderTitle = null;
            heldHeaderArtist = null;
            heldHeaderAlbum = null;
            headerHeldSinceElapsedMs = 0;
            driftRed = false;
            progress.setTextColor(PROGRESS_TEXT_COLOR);
            // Deliberately NOT hiding headerArt/landscapeArt here: the new track's art can take a
            // moment to fetch (network round trip), and forcing the view GONE right at track
            // change meant it sat blank for that whole wait, then popped straight in once fetched
            // - no actual cross-blend from the previous cover, just a gap then a snap. Leaving the
            // previous track's art visible lets crossfadeHeaderArt's already-visible branch do a
            // real fade-out/fade-in between the two covers once the new one arrives.
            pendingSavedUri = "";
            lastDisplayedSaved = null;
            introSkipTargetMs = -1;
            outroBoundaryMs = -1;
            autoSkippedIntro = false;
            autoSkippedOutro = false;
            if (skipController != null) skipController.hide();
            XpLog.log(TAG + " active track uri=" + uri + " title=\"" + safe(track.title) + "\"");
            showLoading("Loading lyrics…");
            loadLyrics(track, id);
        }
        // Ground-truth rescue: a seek/skip a free account isn't allowed to make (most visibly
        // tapping a lyric line to jump to it) can leave Spotify's own reported PlaybackState stuck
        // - it stops advancing/being "playing" even though the track keeps audibly playing -
        // which otherwise freezes lyricPos forever since playbackClock only advances while its
        // "playing" flag is true. host.currentAudioLevel() (the real Visualizer-measured output
        // level, see AudioReactiveController) is independent ground truth: if there's genuine
        // audio energy right now, treat the clock as playing regardless of what Spotify's own
        // state claims. Deliberately narrow - only feeds the clock, not the play/pause icon or
        // "Playing"/"Paused" status text - so an unrelated false positive here can only unstick
        // lyric scrolling, never mislabel the UI.
        boolean audioRescueActive = !playingNow && host.currentAudioLevel() > AUDIO_ACTIVE_LEVEL_THRESHOLD;
        if (audioRescueActive != lastAudioRescueActive) {
            lastAudioRescueActive = audioRescueActive;
            XpLog.log(TAG + " clock audio-rescue " + (audioRescueActive ? "engaged" : "released")
                    + " level=" + host.currentAudioLevel());
        }
        boolean clockPlayingNow = playingNow || audioRescueActive;
        long pos = playbackClock.getPosition(track, clockPlayingNow);

        String trackTitle = emptyFallback(track.title, "Unknown title");
        String trackArtist = emptyFallback(track.artist, "Unknown artist");
        String trackAlbum = emptyFallback(track.album, "Unknown album");
        String currentImageId = safe(track.imageId);
        boolean artOutstanding = slideAnimationEnabled && !currentImageId.isEmpty()
                && !currentImageId.equals(lastArtImageId);
        if (artOutstanding) {
            heldHeaderTitle = trackTitle;
            heldHeaderArtist = trackArtist;
            heldHeaderAlbum = trackAlbum;
            if (headerHeldSinceElapsedMs == 0) headerHeldSinceElapsedMs = SystemClock.elapsedRealtime();
            else if (SystemClock.elapsedRealtime() - headerHeldSinceElapsedMs > HEADER_HOLD_TIMEOUT_MS) flushHeldHeader();
        } else {
            if (heldHeaderTitle != null || heldHeaderArtist != null) flushHeldHeader();
            if (!trackTitle.equals(lastDisplayedTitle)) {
                lastDisplayedTitle = trackTitle;
                crossfadeHeaderText(title, trackTitle, HEADER_TITLE_ALPHA);
            }
            if (!trackArtist.equals(lastDisplayedArtist) || !trackAlbum.equals(lastDisplayedAlbum)) {
                lastDisplayedArtist = trackArtist;
                lastDisplayedAlbum = trackAlbum;
                crossfadeHeaderText(subtitle, trackArtist, HEADER_SUBTITLE_ALPHA);
            }
        }
        if (!pendingSavedUri.isEmpty() && pendingSavedUri.equals(uri)) {
            if (lastDisplayedSaved != null && lastDisplayedSaved == track.saved) pendingSavedUri = "";
        } else {
            applySaveIconState(track.saved);
        }
        // Artwork must load regardless of the slide-animation setting - this used to be gated by
        // slideAnimationEnabled too, which happened to go unnoticed only because that setting
        // used to default on. With it now off by default, that coupling meant the artwork header
        // simply never fetched anything for anyone who hadn't opted into the slide animation:
        // header art is core display, not a decorative extra of that one animation style.
        if (!currentImageId.isEmpty() && !currentImageId.equals(pendingArtImageId)) {
            pendingArtImageId = currentImageId;
            ambientController.fetchHeaderArtwork(pendingArtImageId, new LyricsAmbientController.HeaderArtCallback() {
                @Override
                public void onArtwork(String imageId, android.graphics.Bitmap bitmap) {
                    if (!running || !imageId.equals(pendingArtImageId)) return;
                    lastArtImageId = imageId;
                    android.widget.ImageView art = landscapeArt != null ? landscapeArt : headerArt;
                    flushHeldHeader();
                    crossfadeHeaderArt(art, bitmap);
                }

                @Override
                public void onFailure(String imageId) {
                    if (!imageId.equals(pendingArtImageId)) return;
                    pendingArtImageId = "";
                    flushHeldHeader();
                }
            });
        }
        long displayedSecond = Math.max(0L, pos) / 1000L;
        if (displayedSecond != lastDisplayedProgressSecond) {
            lastDisplayedProgressSecond = displayedSecond;
            setTextIfChanged(progress, formatMs(pos));
            updateDriftTint(track, playingNow, pos);
        }

        if (document != null) {
            if (staticDoc) {
                // updateSkipChip (below, synced-only) is the only place that ever hides this
                // button once shown - if a document transitions from synced to static mid-song
                // (a fallback re-fetch, language change, etc.) while it happened to be visible,
                // nothing would call hide() again without this, leaving it stuck on screen
                // indefinitely (reported live).
                if (skipController != null) skipController.hide();
                // Reassert static styling after remounts and late secondary-text updates. Static
                // rows have synthetic layout timings, never a karaoke-active row.
                frameRenderer.applyStatic(document, rowMountController.mountedIndices(), mountedRowsHost);
            } else {
                long lyricPos = adjustedLyricPositionMs(pos);
                int nextActive = LyricTimeline.findPrimaryActiveRow(document.appliedLines, lyricPos);
                boolean drasticSeek = lastLyricPositionMs >= 0 && Math.abs(lyricPos - lastLyricPositionMs) > 1000;
                lastLyricPositionMs = lyricPos;
                maybeAutoResumeFollow(nextActive, track, lyricPos);
                if (nextActive != followState.activeIndex() || drasticSeek) {
                    int previousActiveIndex = followState.activeIndex();
                    CharSequence screenProgressText = progress == null ? null : progress.getText();
                    setActiveLine(nextActive, lyricPos, track, drasticSeek);
                    if (nextActive >= 0 && nextActive < document.appliedLines.size()) {
                        AppliedLine transitionLine = document.appliedLines.get(nextActive);
                        lastTracedTransitionDeltaMs = lyricPos - transitionLine.startMs;
                        lastTracedTransitionAtMs = SystemClock.elapsedRealtime();
                        com.eza.spicyex.lyrics.LyricsSyncTracer.logTransition(
                                nextActive, transitionLine, pos, lyricPos,
                                renderConfig == null ? 0 : renderConfig.syncOffsetMs,
                                previousActiveIndex,
                                screenProgressText == null ? "" : screenProgressText.toString());
                    }
                }
                updateSyncDebugOverlay(document.appliedLines.size(), playingNow, track);
                boolean rawScrollHeld = followState.isHoldingNow();
                if (wasScrollHeldLastFrame && !rawScrollHeld) {
                    scrollHoldReleasedAtMs = SystemClock.elapsedRealtime();
                }
                wasScrollHeldLastFrame = rawScrollHeld;
                // A manual hold suppresses blur/edge-melt entirely (see mobileLineBlurPx /
                // meltHeld below) so a bunch of lines can be sitting at blur=0 when the hold
                // ends. Auto-resume then kicks off an animated scroll-back (smoothScrollTo in
                // scrollActiveRowWhenLaidOut) that takes ~300ms; if blur/melt were unsuppressed
                // the instant the hold cleared, every one of those lines would pop its blur back
                // in at once while the view is also sliding, which read as multiple lines
                // flickering together (reported live). Keep the same suppression alive for a
                // short grace window after release so blur only starts fading back in once the
                // scroll-back has visually settled.
                boolean userScrollHeld = rawScrollHeld
                        || (SystemClock.elapsedRealtime() - scrollHoldReleasedAtMs) < SCROLL_HOLD_RENDER_GRACE_MS;
                // Buffered, not the exact viewport: see visibleLineRange's own comment - without
                // this, a line scrolling into view during a drag had received zero per-frame
                // updates while just off-screen, so it rendered its instantaneous "correct for
                // right now" state directly (a bright flash immediately snapping to dim) instead
                // of a smooth fade, confirmed live as the manual-scroll flicker.
                long visibleRange = userScrollHeld && scrollController != null
                        ? scrollController.visibleLineRange(rowHeightPrefix(), document.appliedLines.size(), 4)
                        : LyricsScrollController.ALL_LINES;
                frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                        renderConfig, lyricPos, nextActive, deltaSeconds, userScrollHeld,
                        LyricsScrollController.rangeStart(visibleRange),
                        LyricsScrollController.rangeEnd(visibleRange));
                updateSkipChip(lyricPos, track.duration, pos);
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
        if (document == null && skipController != null) skipController.hide();
        // clockPlayingNow (not raw playingNow): the audio-level rescue above only helps if the
        // render loop actually keeps ticking past forceContinuousUntilElapsedMs's few-second
        // grace window - otherwise, once Spotify's own reported state is stuck non-playing, the
        // frame scheduler drops to idle and nothing repaints at all, which is indistinguishable
        // from the clock itself being frozen (confirmed live: fixing the clock alone did not fix
        // the freeze, because nothing was calling it any more).
        updateFrameDemand(clockPlayingNow);
    }

    private void updateFrameDemand(boolean playingNow) {
        boolean processing = document != null && document.processingPending;
        boolean continuous = (playingNow && document != null && !staticDoc)
                || !loadingTrackId.isEmpty()
                || processing
                || localReprocessController.isProcessing()
                || scrollInProgress
                || !rowCascades.isEmpty()
                // A seek attempt (successful or not) can leave the backend's self-reported
                // PlaybackState briefly - sometimes not so briefly, when the seek silently failed
                // - showing not-playing even though real audio is still going. Trusting that
                // report here would drop the whole render loop into idle/probe mode, which reads
                // as "everything just stops updating" even though the song plays on. Stay
                // continuous for a few seconds after any seek regardless of what playingNow says,
                // long enough for the seek-verify check to run and correct the underlying state.
                || SystemClock.elapsedRealtime() < forceContinuousUntilElapsedMs;
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
        // Not part of LyricsRenderConfig/its diff machinery (that's for the render pipeline
        // proper); this is a plain view resize, so just compare-and-apply directly rather than
        // threading a new field through Diff for one dimension.
        if (!isLandscape() && headerArtWrapper != null) {
            int wantedSizeDp = headerArtSizeDp();
            if (wantedSizeDp != appliedHeaderArtSizeDp) {
                appliedHeaderArtSizeDp = wantedSizeDp;
                ViewGroup.LayoutParams lp = headerArtWrapper.getLayoutParams();
                if (lp != null) {
                    lp.width = dp(wantedSizeDp);
                    lp.height = dp(wantedSizeDp);
                    headerArtWrapper.setLayoutParams(lp);
                }
            }
        }
        autoResumeFollow = config.get(Settings.AUTO_RESUME_FOLLOW);
        autoSkipIntroOutro = config.get(Settings.AUTO_SKIP_INTRO_OUTRO);
        slideAnimationEnabled = config.get(Settings.LINE_SLIDE_ANIMATION);
        showSaveButton = config.get(Settings.SHOW_SAVE_BUTTON);
        String newSaveButtonIcon = config.get(Settings.SAVE_BUTTON_ICON);
        if (!newSaveButtonIcon.equals(saveButtonIcon)) {
            saveButtonIcon = newSaveButtonIcon;
            lastDisplayedSaved = null; // force applySaveIconState to redraw with the new icon
        }
        boolean wasBeatReactive = beatReactiveBackground;
        beatReactiveBackground = config.get(Settings.BEAT_REACTIVE_BACKGROUND);
        if (running && beatReactiveBackground != wasBeatReactive) {
            host.setAudioReactiveListening(beatReactiveBackground);
        }
        if (saveToggle != null) {
            saveToggle.setVisibility(showSaveButton ? View.VISIBLE : GONE);
            SpotifyTrack currentTrack = host.getCurrentTrackSafely();
            applySaveIconState(currentTrack != null && currentTrack.saved);
        }
        // The landscape/portrait chrome is otherwise built once at construction - this only takes
        // full effect the next time the fullscreen lyrics screen is (re)opened, same as any other
        // setting that reshapes the view tree rather than just a value read per frame.
        adaptiveLandscapeLayout = config.get(Settings.ADAPTIVE_LANDSCAPE_LAYOUT);
        if (scrollController != null) {
            scrollController.setRaisedAnchor(slideAnimationEnabled);
            applyLyricsScrollPadding();
        }
        topFadeOverlay.setVisibility(GONE);
        LyricsRenderConfig next = LyricsRenderConfig.read(activity, config);
        LyricsRenderConfig.Diff diff = renderConfig == null ? null : renderConfig.diff(next);
        if (diff == null || !diff.hasChanges) {
            renderConfig = next;
            return;
        }

        renderConfig = next;
        com.eza.spicyex.lyrics.LyricsSyllableViewState.setAppleMotion(next.appleStyle);
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
            ambientController.applySettings(next.backgroundStyle, next.forceDarkBackground);
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
            document = doc;
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
        renderDocument();
        if (current != null) setActiveLine(LyricTimeline.findPrimaryActiveRow(document.appliedLines, lyricPos), lyricPos, current);
        if (!isBlank(message)) status.setText(message);
    }

    private static final long TRACK_CHANGE_LYRICS_FADE_MS = 240L;
    private static final int ROW_EXIT_FADE_MS = 180;

    private void showLoading(String message) {
        if (mountedRowsHost.getChildCount() == 0) {
            rowMountController.reset();
            followState.resetActive();
            emptyStateController.showLoading(lyricsScroll, lyricsColumn, message);
            return;
        }
        lyricsColumn.animate().cancel();
        lyricsColumn.setPivotX(lyricsColumn.getWidth() / 2f);
        lyricsColumn.setPivotY(lyricsColumn.getHeight() / 2f);
        lyricsColumn.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        lyricsColumn.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(TRACK_CHANGE_LYRICS_FADE_MS)
                .withEndAction(() -> {
                    lyricsColumn.setLayerType(View.LAYER_TYPE_NONE, null);
                    lyricsColumn.setAlpha(1f);
                    lyricsColumn.setScaleX(1f);
                    lyricsColumn.setScaleY(1f);
                    rowMountController.reset();
                    followState.resetActive();
                    emptyStateController.showLoading(lyricsScroll, lyricsColumn, message);
                })
                .start();
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
        com.eza.spicyex.lyrics.LyricsSyncTracer.logDocumentLoaded(document);
        introSkipTargetMs = -1;
        outroBoundaryMs = -1;
        autoSkippedIntro = false;
        autoSkippedOutro = false;
        if (!staticDoc) {
            for (AppliedLine line : document.appliedLines) {
                if (line.dotLine || line.bgLine) continue;
                introSkipTargetMs = line.startMs;
                break;
            }
            for (int i = document.appliedLines.size() - 1; i >= 0; i--) {
                AppliedLine line = document.appliedLines.get(i);
                if (line.dotLine || line.bgLine) continue;
                outroBoundaryMs = line.endMs;
                break;
            }
        }
        if (skipController != null) skipController.hide();
        sourceFooter.setText(!isBlank(document.songWriters)
                ? "Written by " + document.songWriters
                : "lyrics provided by " + sourceProviderLabel(document.provider));
        rowMountController.markDirty();
        renderWindowForActive(0);
        if (resetScrollForNextDocument) {
            resetScrollForNextDocument = false;
            clearRowCascade();
            // A cache hit can replace the short loading state before ScrollView gets a layout pass
            // that clamps the previous song's scrollY. Reset after mounting the new document so
            // its opening row starts from the center-padding position even during lyric pre-roll.
            lyricsScroll.scrollTo(0, 0);
        }
    }

    private static LayoutTransition rowExitLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.setDuration(LayoutTransition.CHANGE_DISAPPEARING, ROW_EXIT_FADE_MS);
        transition.setDuration(LayoutTransition.DISAPPEARING, ROW_EXIT_FADE_MS);
        return transition;
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
                lineVisualController::invalidate,
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
        View row = rowMountController.attachedRowView(line);
        if (row != null) {
            // Scrolling to the active line can remount rows mid-cascade (window virtualization
            // reacts to the same scroll change that triggered the slide). Snapping straight to 0
            // here made a freshly (re)mounted row visibly jump out of step with its still-animating
            // neighbors - start it from the cascade's current offset instead so it joins in place.
            RowCascade cascade = rowCascades.get(line);
            row.setTranslationY(cascade != null ? cascade.spring.position() : 0f);
        }
        remeasureLine(line);
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

    private boolean appendTranslationView(AppliedLine line) {
        LyricsSurfaceRowPlanner.RowPlan rowPlan = LyricsSurfaceRowPlanner.plan(
                line, document,
                LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(
                        renderConfig, showRomanization(), showTranslation(), japaneseReadingMode()));
        return rowViewFactory.appendTranslationView(rowPlan.line, rowPlan.options);
    }

    private boolean appendRomanView(AppliedLine line) {
        LyricsSurfaceRowPlanner.RowPlan rowPlan = LyricsSurfaceRowPlanner.plan(
                line, document,
                LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(
                        renderConfig, showRomanization(), showTranslation(), japaneseReadingMode()));
        return rowViewFactory.appendRomanView(rowPlan.line, rowPlan.options);
    }

    /**
     * Adds or removes just the translation child view on every currently mounted row, instead of
     * tearing the whole document down and rebuilding it the way the global toggle used to: that
     * reset every line's syllable/word highlight animation back to its start, which is what made
     * the toggle look like the whole lyric screen "redrew itself" on every press.
     */
    private void applyTranslationVisibilityToMountedRows() {
        if (document == null || document.appliedLines == null) return;
        boolean show = showTranslation();
        for (int index : rowMountController.mountedIndices()) {
            if (index < 0 || index >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(index);
            if (line == null) continue;
            if (show) {
                appendTranslationView(line);
            } else {
                rowViewFactory.removeTranslationView(line);
            }
        }
        invalidateRowHeightPrefix();
        updateVirtualSpacerHeights();
    }

    private String segmentRomanizedText(AppliedLine line, SyllableSegment segment,
                                         String fullText) {
        return LyricsLocalRomanizer.romanizeDisplaySegment(
                romanizationOptions(), document, line, segment, fullText);
    }

    private boolean isJapaneseLine(AppliedLine line) {
        return hasJapaneseReading(line) || (line != null && SpicyTextDetection.hasKana(line.text));
    }

    private void refreshSecondaryRows(String message) {
        LyricsDocument snapshot = document;
        if (snapshot == null || snapshot.appliedLines == null || snapshot.appliedLines.isEmpty()) {
            if (!isBlank(message)) setTextIfChanged(status, message);
            return;
        }
        boolean structureChanged = secondaryRowUpdater.refresh(snapshot, showRomanization(), showTranslation(), japaneseReadingMode());
        if (structureChanged) {
            invalidateRowHeightPrefix();
            rowMountController.markDirty();
            renderWindowForActive(currentWindowAnchor());
        }
        if (!isBlank(message)) setTextIfChanged(status, message);
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
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        if (staticDoc) return; // unsynced lyrics have no real per-line timing → tapping must not seek
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
        if (bestIndex >= 0) seekToLine(document.appliedLines.get(bestIndex), bestIndex);
    }

    // seekSpotifyTo() returning true only means the MediaSession transport-control call didn't
    // throw - it is the backend's own self-reported acknowledgement, not confirmation the
    // underlying audio actually moved. The tap immediately forces our local position estimate and
    // the active line/scroll to the target on that acknowledgement alone; if the real backend
    // (this app's Gecko-based Connect player in particular) silently never performs the seek,
    // everything downstream - lyrics, active line, scroll - stays pinned to the tapped line
    // forever with no further updates, which reads as "playback gets tangled after tapping."
    // Verified live with the sync debug overlay: PHONE tracked the tapped line while SERVER (a
    // fresh, independent position sample) stayed elsewhere.
    // PlaybackBridge.forcePosition() (called from seekSpotifyTo on the same optimistic
    // acknowledgement) sets its own 1800ms override window during which
    // readBestMeasuredProgressMs() unconditionally returns that forced value, ignoring the real
    // backend-reported state entirely - by design, to smooth over the normal brief lag after a
    // genuinely successful seek before Spotify's own state catches up. The first version of this
    // check ran at 900ms, still deep inside that window, so it always read back our own write and
    // never actually verified anything (confirmed live: SERVER moved in lockstep with the tap).
    // Must run comfortably after the override expires so the read falls through to the real
    // player-state/track-position path instead.
    private static final long SEEK_VERIFY_DELAY_MS = 2200;
    private static final long SEEK_VERIFY_TOLERANCE_MS = 1500;
    private int pendingSeekVerifyToken;

    private static final long SEEK_FORCE_CONTINUOUS_MS = 4000;
    private static final long TAP_SEEK_DEBOUNCE_MS = 350;
    private int pendingTapSeekToken;

    private void seekToLine(AppliedLine line, int index) {
        if (line == null || line.startMs < 0) return;
        long target = renderConfig == null ? Math.max(0, line.startMs) : renderConfig.playbackPositionForLyricMs(line.startMs);
        followState.clearHold();
        forceContinuousUntilElapsedMs = SystemClock.elapsedRealtime() + SEEK_FORCE_CONTINUOUS_MS;
        playbackClock.forcePosition(target, host.isPlayerActuallyPlaying());
        long lyricTarget = adjustedLyricPositionMs(target);
        setActiveLine(index, lyricTarget, host.getCurrentTrackSafely(), false);
        frameRenderer.applySynced(document, rowMountController.mountedIndices(), mountedRowsHost,
                renderConfig, lyricTarget, index, 1f / 60f, false, 0, Integer.MAX_VALUE);
        // Tap bursts (and every tap's cloud round-trip on the Gecko path taking seconds) used to
        // stack up wire seeks that kept landing long after the finger stopped. Supersede: only the
        // latest tap within the window ever reaches the backend, once tapping actually stops.
        int token = ++pendingTapSeekToken;
        pendingSeekVerifyToken++;
        XpLog.log(TAG + " seek line queued index=" + index + " ms=" + target + " lyricMs=" + lyricTarget);
        handler.postDelayed(() -> {
            if (!running || token != pendingTapSeekToken) return;
            boolean ok = host.seekSpotifyTo(target);
            if (ok) {
                scheduleSeekVerification(target);
            } else {
                followState.holdUntil(SystemClock.elapsedRealtime() + 2500);
                XpLog.log(TAG + " seek line failed index=" + index + " ms=" + target);
            }
        }, TAP_SEEK_DEBOUNCE_MS);
    }

    private void scheduleSeekVerification(long target) {
        int token = ++pendingSeekVerifyToken;
        handler.postDelayed(() -> {
            if (!running || token != pendingSeekVerifyToken) return;
            SpotifyTrack track = host.getCurrentTrackSafely();
            if (track == null) return;
            long actual = host.readBestMeasuredProgressMs(track, host.isPlayerActuallyPlaying());
            if (Math.abs(actual - target) <= SEEK_VERIFY_TOLERANCE_MS) return;
            // The backend acknowledged the seek but never actually moved there. Drop the
            // optimistic hold/forced position so the very next frame re-syncs the active line and
            // scroll to wherever playback genuinely is, instead of staying stuck on the tapped
            // line indefinitely.
            XpLog.log(TAG + " seek verify failed: target=" + target + " actual=" + actual
                    + " - releasing optimistic state");
            followState.clearHold();
            playbackClock.reset(track.uri == null ? "" : track.uri);
        }, SEEK_VERIFY_DELAY_MS);
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
            int target = scrollController == null ? 0 : scrollController.centeredScrollTarget(row);
            scrollToActiveTarget(Math.max(0, target), instantScroll);
        });
    }

    private void scrollToActiveTarget(int target, boolean instant) {
        if (lyricsScroll == null) return;
        boolean plainScroll = forcePlainScrollNext;
        forcePlainScrollNext = false;
        int oldScroll = lyricsScroll.getScrollY();
        if (instant || Math.abs(target - oldScroll) <= 2) {
            boolean moved = Math.abs(target - oldScroll) > 2;
            applyingLyricScroll = true;
            lyricsScroll.scrollTo(0, target);
            applyingLyricScroll = false;
            if (moved) clearRowCascade();
            return;
        }
        if (!slideAnimationEnabled || plainScroll
                || Math.abs(target - oldScroll) > glideCapPx()) {
            clearRowCascade();
            lyricsScroll.smoothScrollTo(0, target);
            return;
        }
        applyingLyricScroll = true;
        lyricsScroll.scrollTo(0, target);
        applyingLyricScroll = false;
        startRowCascade(target - oldScroll);
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
        boolean apple = renderConfig != null && renderConfig.appleStyle;
        boolean landscape = isLandscape();
        int activeIndex = followState.activeIndex();
        float stagger = apple ? (landscape ? 0.07f : 0.05f) : ROW_CASCADE_STAGGER_SEC;
        float maxDelay = apple ? (landscape ? 0.40f : 0.30f) : ROW_CASCADE_MAX_DELAY_SEC;
        float frequency = apple ? (landscape ? 1.5f : 1.8f) : ROW_CASCADE_FREQUENCY_HZ;
        float damping = apple ? (landscape ? 0.80f : 0.88f) : ROW_CASCADE_DAMPING;
        for (int i : rowMountController.mountedIndices()) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            View row = rowMountController.attachedRowView(line);
            if (row == null) continue;
            float distance = activeIndex < 0 ? 0f : Math.abs(i - activeIndex);
            float delay = Math.min(maxDelay, distance * stagger);
            rowCascades.put(line, new RowCascade(scrollDelta, delay, frequency, damping));
            row.setTranslationY(scrollDelta);
        }
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
                it.remove();
                continue;
            }
            if (now - cascade.startedAtMs > ROW_CASCADE_MAX_LIFETIME_MS) {
                row.setTranslationY(0f);
                it.remove();
                continue;
            }
            if (cascade.delayRemaining > 0f) {
                cascade.delayRemaining -= deltaSeconds;
                continue;
            }
            float value = cascade.spring.step(Math.max(0.001f, Math.min(0.05f, deltaSeconds)));
            row.setTranslationY(value);
            if (cascade.spring.isAtRest(0.5f, 2f)) {
                row.setTranslationY(0f);
                it.remove();
            }
        }
    }

    private void clearRowCascade() {
        if (rowCascades.isEmpty()) return;
        for (AppliedLine line : rowCascades.keySet()) {
            View row = rowMountController.attachedRowView(line);
            if (row != null) row.setTranslationY(0f);
        }
        rowCascades.clear();
    }

    private void maybeAutoResumeFollow(int activeIndex, SpotifyTrack track, long lyricPos) {
        if (!autoResumeFollow) return;
        if (!followState.canAutoResumeNow()) return;
        if (document == null || document.appliedLines == null || activeIndex < 0 || activeIndex >= document.appliedLines.size()) return;
        AppliedLine line = document.appliedLines.get(activeIndex);
        View row = rowMountController.attachedRowView(line);
        boolean rowVisible = row != null && scrollController != null && scrollController.isRowVisible(row, dp(5));
        followState.clearHold();
        forcePlainScrollNext = !rowVisible;
        setActiveLine(activeIndex, lyricPos, track);
        updateJumpToCurrentVisibility();
    }

    // On-screen counterpart to LyricsSyncTracer's logcat output (Settings.LYRICS_SYNC_TRACER).
    // Two genuinely independent measurements, not the same internal variable printed twice:
    //   "server" - a BRAND NEW raw position sample taken right now via
    //     host.readBestMeasuredProgressMs(), bypassing playbackClock's smoothing/resync-interval
    //     logic entirely. This is what the actual song is doing this instant, straight from
    //     Spotify's own player state.
    //   "phone"  - the literal state already sitting in the views: progress.getText() (the
    //     already-rendered progress label) and followState.activeIndex() (the line the render
    //     pipeline last actually committed as active), not recomputed from anything.
    // If these two drift apart, that's a real, visible gap between what the song is doing and
    // what the screen is doing - the thing this whole overlay exists to catch.
    private static final long PLAYBACK_STALL_THRESHOLD_MS = 900;

    private void updateSyncDebugOverlay(int lineCount, boolean playingNow, SpotifyTrack track) {
        if (syncDebugOverlay == null) return;
        if (!com.eza.spicyex.lyrics.LyricsSyncTracer.enabled) {
            if (syncDebugOverlay.getVisibility() != GONE) syncDebugOverlay.setVisibility(GONE);
            lastRawPositionSeenMs = Long.MIN_VALUE;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long frameGapMs = lastFrameWallClockMs == 0 ? 0 : now - lastFrameWallClockMs;
        lastFrameWallClockMs = now;

        long serverRawMs = host.readBestMeasuredProgressMs(track, playingNow);
        long serverAdjustedMs = adjustedLyricPositionMs(Math.max(0L, serverRawMs));
        int serverIndex = document == null
                ? -1 : LyricTimeline.findPrimaryActiveRow(document.appliedLines, serverAdjustedMs);
        String serverText = serverIndex >= 0 && serverIndex < lineCount
                ? truncateForOverlay(document.appliedLines.get(serverIndex).text) : "";
        // A recent seek forces this same read to return its own optimistic write for ~1.8s (see
        // PlaybackBridge.forcePosition) - flagged here so "SERVER" is never silently mistaken for
        // independently-confirmed truth during that window; it's just showing our own guess back.
        boolean serverOverrideActive = host.isSeekOverrideActive();

        int phoneIndex = followState.activeIndex();
        boolean hasPhoneLine = phoneIndex >= 0 && phoneIndex < lineCount && document != null;
        String phoneText = hasPhoneLine ? truncateForOverlay(document.appliedLines.get(phoneIndex).text) : "";
        long phoneLineStartMs = hasPhoneLine ? document.appliedLines.get(phoneIndex).startMs : -1;
        String phoneProgressText = progress == null || progress.getText() == null
                ? "" : progress.getText().toString();

        // Stall/skip detection on the fresh server sample itself, independent of the render loop -
        // if it stops moving forward while the player is marked playing, that's a real playback
        // stall (buffering, an audio glitch), not a rendering problem.
        if (serverRawMs != lastRawPositionSeenMs) {
            lastRawPositionSeenMs = serverRawMs;
            lastRawPositionChangedAtMs = now;
        }
        long stalledForMs = lastRawPositionChangedAtMs == 0 ? 0 : now - lastRawPositionChangedAtMs;
        boolean stalled = playingNow && stalledForMs >= PLAYBACK_STALL_THRESHOLD_MS;
        if (stalled != wasStalledLastFrame) {
            wasStalledLastFrame = stalled;
            com.eza.spicyex.lyrics.LyricsSyncTracer.logStallChange(stalled, stalledForMs);
        }

        // Word-level readout for whichever line the server sample says is active right now.
        String wordLine = "word: (no per-word timing)";
        if (serverIndex >= 0 && serverIndex < lineCount && document != null) {
            AppliedLine active = document.appliedLines.get(serverIndex);
            if (active.words != null && !active.words.isEmpty()) {
                int activeWordIndex = -1;
                for (int w = 0; w < active.words.size(); w++) {
                    SyllableSegment seg = active.words.get(w);
                    if (seg != null && serverAdjustedMs >= seg.startMs && serverAdjustedMs < seg.endMs) {
                        activeWordIndex = w;
                        break;
                    }
                }
                StringBuilder sb = new StringBuilder();
                int rangeStart = Math.max(0, (activeWordIndex < 0 ? 0 : activeWordIndex) - 2);
                int rangeEnd = Math.min(active.words.size(), (activeWordIndex < 0 ? 0 : activeWordIndex) + 3);
                for (int w = rangeStart; w < rangeEnd; w++) {
                    SyllableSegment seg = active.words.get(w);
                    String t = seg == null || seg.text == null ? "" : seg.text;
                    sb.append(w == activeWordIndex ? "[" + t + "]" : t).append(' ');
                }
                String timing = activeWordIndex >= 0
                        ? " (" + active.words.get(activeWordIndex).startMs + "-"
                            + active.words.get(activeWordIndex).endMs + "ms)"
                        : " (between words)";
                wordLine = "word[" + activeWordIndex + "/" + active.words.size() + "]: "
                        + truncateForOverlay(sb.toString()) + timing;
            }
        }

        long sinceTransitionMs = lastTracedTransitionAtMs == 0 ? -1 : now - lastTracedTransitionAtMs;
        String stallLine = stalled
                ? ">>> PLAYBACK STALLED " + stalledForMs + "ms (server position frozen while playing) <<<\n"
                : "playing=" + playingNow + "  posFrozenFor=" + stalledForMs + "ms\n";
        syncDebugOverlay.setText(stallLine
                + "SERVER (fresh sample" + (serverOverrideActive ? ", UNVERIFIED-forced" : "") + "): "
                + serverRawMs + "ms -> line[" + serverIndex + "]: " + serverText + "\n"
                + wordLine + "\n"
                + "PHONE (on screen):     line[" + phoneIndex + "]: " + phoneText + "\n"
                + "  phoneLineStart=" + phoneLineStartMs + "ms  progressLabel=" + phoneProgressText + "\n"
                + "lastTransitionDelta=" + lastTracedTransitionDeltaMs + "ms  (" + sinceTransitionMs + "ms ago)\n"
                + "frameGap=" + frameGapMs + "ms");
        syncDebugOverlay.setTextColor(serverOverrideActive ? Color.rgb(255, 190, 60)
                : stalled || Math.abs(lastTracedTransitionDeltaMs) > 200
                || frameGapMs > 100 || serverIndex != phoneIndex
                ? Color.rgb(255, 90, 90) : Color.rgb(120, 255, 120));
        if (syncDebugOverlay.getVisibility() != VISIBLE) syncDebugOverlay.setVisibility(VISIBLE);
    }

    private String truncateForOverlay(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > 36 ? flat.substring(0, 36) + "…" : flat;
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
            ambientController.applySettings(renderConfig.backgroundStyle, renderConfig.forceDarkBackground);
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

    private String aiPipelineLabel(String value) {
        return uiStrings.option((Settings.StringSetting) Settings.AI_TRANSLATION_PIPELINE, value);
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
        if (layer == com.eza.spicyex.lyrics.session.LayerKind.SOUND
                && !showRomanization()) {
            transliterationSession.setShowRomanization(true);
            preferences.edit()
                    .putBoolean(Settings.NATIVE_SPICY_ROMANIZATION.key, true)
                    .apply();
            refreshSecondaryRows("");
        } else if (layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING
                && !showTranslation()) {
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
        return uiStrings.get(name, fallback);
    }

    private String uiFormat(String name, String fallback, Object... args) {
        return uiStrings.format(name, fallback, args);
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

    private void updateJumpToCurrentVisibility() {
        boolean show = document != null && followState.activeIndex() >= 0 && followState.isHoldingNow();
        jumpToCurrentController.update(show);
    }

    private void cycleTransliterationMode(SharedPreferences prefs) {
        if (renderConfig != null && !renderConfig.transliterationEnabled) return;
        // A local mode cycle replaces any previously accepted AI reading. Clear the old Sound
        // authority before repainting, otherwise the chip briefly shows the green AI marker while
        // the local Pinyin/Jyutping pass is still running.
        if (document != null && (document.readingFromAi || document.readingAiPending
                || !safe(document.readingAiFailureToken).isEmpty())) {
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
                        if (changed == 0 && document != null) {
                            // Pure visibility toggle (chip flips reading on/off, no text change):
                            // grow/shrink the already-mounted rows in place instead of tearing the
                            // whole document down and rebuilding it, which is what previously made
                            // this always read as a hard instant pop rather than an animation.
                            secondaryRowUpdater.applyVisibilityToggle(document, showRomanization(), showTranslation());
                            if (!isBlank(completedReason)) status.setText(completedReason + " ready");
                        } else {
                            rerenderKeepingPosition(completedReason + " ready");
                        }
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
        if (line != null && SpicyTextDetection.hasKana(line.text)) return true;
        // A kanji-only line carries no kana, so on its own it reads as Chinese. Let the document
        // decide instead: otherwise a Japanese song cycles the Chinese mode whenever the active
        // line happens to have no kana in it, and the Japanese cycle appears to do nothing.
        return documentHasJapanese();
    }

    private boolean activeLineHasChinese() {
        AppliedLine line = activeLine();
        if (line != null && hasRomanizableScript(line.text)) return SpicyTextDetection.itemChineseTest(line.text) && !SpicyTextDetection.hasKana(line.text);
        return documentHasChinese();
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

    private boolean documentHasJapanese() {
        return document != null && SpicyTextDetection.hasKana(LyricsDocumentProcessor.collectText(document));
    }

    private boolean documentHasChinese() {
        if (document == null || document.lines == null) return false;
        for (LyricsLine line : document.lines) {
            if (line != null && SpicyTextDetection.itemChineseTest(line.text) && !SpicyTextDetection.hasKana(line.text)) return true;
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
            if (SpicyProcessing.shouldTranslateLine(line.text, document.language, "en")) return true;
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
