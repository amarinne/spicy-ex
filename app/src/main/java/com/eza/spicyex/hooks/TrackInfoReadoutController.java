package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.ArtGestureArbiter;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.lyrics.PanelMediaMode;
import com.eza.spicyex.lyrics.SpotifyArtworkCache;
import com.eza.spicyex.ui.ActionIconDrawable;

/**
 * Owns the fullscreen track-info readout (spec E′): a transparent anchoring plane with a standing
 * album-art item plus title/artist, positioned off/top/bottom by
 * {@link Settings#TRACK_INFO_POSITION}. No scrim bar, border, or outline — only slight static
 * edge gradients for readability. Both positions float over the full-height lyric scroll, exactly
 * alike; passed (dimmed) lines travel behind them.
 *
 * <p>The art is the media control surface: 1 tap reveals the overlay play button (2nd tap
 * confirms), double-tap toggles immediately, horizontal drag slides the whole art and commits
 * prev/next past ~32dp (spring-back otherwise). Gesture arbitration lives in the pure
 * {@link ArtGestureArbiter}; this class only applies its outputs to views and the host transport.
 *
 * <p>Drag performance (perf gate): the artwork bitmap is rounded once per track change (static),
 * so a drag frame is one texture move — no outline masks, no layout, no lyric work. Translation
 * updates are coalesced to one per vsync.
 */
final class TrackInfoReadoutController {
    private static final int ART_BOTTOM_DP = 96;
    private static final int ART_TOP_PORTRAIT_DP = 72;
    private static final int ART_TOP_LANDSCAPE_DP = 54;
    private static final int COMMIT_THRESHOLD_DP = 32;
    private static final long OVERLAY_HIDE_DELAY_MS = 1800L;
    private static final long OVERLAY_FLASH_MS = 400L;
    private static final long REVEAL_SPRING_MS = 260L;
    private static final int SETTLE_ANIM_MS = 180;
    private static final int EDGE_GRADIENT_DP = 150;
    private static final int CLUSTER_GAP_DP = 8;
    private static final long ART_RETRY_WINDOW_MS = 10_000L;
    private static final long ART_RETRY_GAP_MS = 1_000L;
    private static final int ACTION_PREV_ID = 0x00C0FFEE;
    private static final int ACTION_NEXT_ID = 0x00C0FFEF;
    /** Minimum width/height ratio for the landscape side-art mode (PR9's proven gate). */
    static final float SIDE_ASPECT_MIN = 1.2f;
    /** Side panel takes this fraction of the screen width. */
    private static final float SIDE_PANEL_FRACTION = 0.4f;
    private static final int SIDE_ART_MARGIN_DP = 24;
    private static final int SIDE_FADE_DP = 60;

    /** Side dock engages on wide landscape only, and never when the readout is Off. */
    static boolean sideModeEngaged(boolean landscape, float aspect, String mode) {
        if (!landscape || aspect < SIDE_ASPECT_MIN) return false;
        return "Top".equals(mode) || "Bottom".equals(mode);
    }

    /** {bottom art, top portrait art} for the art-size setting; landscape top stays 54dp. */
    static int[] readoutArtSizes(String value) {
        if ("Small".equals(value)) return new int[]{72, 48};
        if ("Large".equals(value)) return new int[]{120, 96};
        return new int[]{96, 72};
    }

    /**
     * Tall/narrow top-dock fit decision from real overlay pixels (pure, unit-tested).
     * Narrow when the single band (side padding + art + minimum text + control cluster)
     * cannot fit, or the overlay is portrait-tall (aspect &lt; 0.9). Rotation remounts,
     * so this pins the contract for verification; no per-frame relayout reads it.
     */
    static boolean narrowTopDock(int overlayWpx, int overlayHpx, int artWpx, int minTextWpx,
            int clusterWpx, int sidePadPx) {
        if (overlayWpx <= 0 || overlayHpx <= 0) return false;
        int required = sidePadPx * 2 + artWpx + minTextWpx + clusterWpx;
        if (overlayWpx < required) return true;
        return ((float) overlayWpx / (float) overlayHpx) < 0.9f;
    }

    private final Activity activity;
    private final LyricsHost host;
    private final SpotifyPlusConfig config;
    private final LyricsJumpToCurrentController jumpController;
    private LyricsSkipGapController skipGapController;
    private final Runnable onRevealChrome;
    private final boolean landscape;
    /** When true the adaptive two-column shell owns landscape art; all overlays stand down. */
    private final boolean twoColumn;
    private final int artTopDp;
    private final int sideArtDp;
    private final float aspect;

    private final FrameLayout topBox;
    private final ImageView topArt;
    private final TextView topTitle;
    private final TextView topArtist;
    private final FrameLayout bottomBox;
    private final ArtTouchFrame bottomArtFrame;
    private final ImageView bottomArt;
    private final View bottomOverlayScrim;
    private final ImageButton bottomOverlayButton;
    private final TextView bottomTitle;
    private final TextView bottomArtist;
    private final LinearLayout bottomRow;
    private final ArtTouchFrame topArtFrame;
    private final View topOverlayScrim;
    private final ImageButton topOverlayButton;
    private final FrameLayout sideBox;
    private final ArtTouchFrame sideArtFrame;
    private final ImageView sideArt;
    private final View sideOverlayScrim;
    private final ImageButton sideOverlayButton;
    private final TextView sideTitle;
    private final TextView sideArtist;

    private final ArtGestureArbiter arbiter;
    private final ActionIconDrawable playIcon;
    private final Drawable pauseIcon;
    private final Runnable hideOverlayRunnable = this::hideOverlays;
    private final Runnable dragApplyRunnable = new Runnable() {
        @Override
        public void run() {
            dragFrameScheduled = false;
            // Whole pixels only: fractional offsets shimmer under a finger hold.
            if (pendingDragFrame != null) pendingDragFrame.setTranslationX(Math.round(pendingDragDx));
        }
    };

    private ArtTouchFrame activeFrame;
    private float dragBoundPx;
    private float downRawX;
    private float downRawY;
    private boolean cancelArmed;
    private ArtTouchFrame pendingDragFrame;
    private float pendingDragDx;
    private boolean dragFrameScheduled;
    private String lastUri = "";
    private LinearLayout topRow;
    private FrameLayout.LayoutParams topRowLp;
    private View topGradientView;
    private int topInsetPx;
    private boolean lastPlaying = true;
    private String lastTitle = "";
    private String lastArtist = "";
    private String lastContentDescription = "";
    private Bitmap currentArtwork;
    private Bitmap sideArtwork;
    private int bottomArtDpF = ART_BOTTOM_DP;
    private int topArtDpF = ART_TOP_PORTRAIT_DP;
    private long trackChangeMs;
    private long lastArtAttemptMs;
    private boolean artMissing = true;
    private SpotifyTrack lastTrack;
    private boolean artworkEnabled;
    private String lastMode;
    /** Panel media controls mode (Off | Single tap | Double tap), shared with the panel art. */
    private String panelMediaMode = PanelMediaMode.SINGLE_TAP;

    private TrackInfoReadoutController(Activity activity, LyricsHost host, SpotifyPlusConfig config,
            LyricsJumpToCurrentController jumpController,
            FrameLayout topBox, ImageView topArt, TextView topTitle, TextView topArtist,
            FrameLayout bottomBox, ArtTouchFrame bottomArtFrame, ImageView bottomArt,
            View bottomOverlayScrim, ImageButton bottomOverlayButton,
            TextView bottomTitle, TextView bottomArtist, LinearLayout bottomRow,
            ArtTouchFrame topArtFrame, View topOverlayScrim, ImageButton topOverlayButton,
            FrameLayout sideBox, ArtTouchFrame sideArtFrame, ImageView sideArt,
            View sideOverlayScrim, ImageButton sideOverlayButton,
            TextView sideTitle, TextView sideArtist, int sideArtDp, float aspect,
            Runnable onRevealChrome, boolean landscape, int artTopDp, boolean twoColumn) {
        this.activity = activity;
        this.host = host;
        this.config = config;
        this.jumpController = jumpController;
        this.topBox = topBox;
        this.topArt = topArt;
        this.topTitle = topTitle;
        this.topArtist = topArtist;
        this.bottomBox = bottomBox;
        this.bottomArtFrame = bottomArtFrame;
        this.bottomArt = bottomArt;
        this.bottomOverlayScrim = bottomOverlayScrim;
        this.bottomOverlayButton = bottomOverlayButton;
        this.bottomTitle = bottomTitle;
        this.bottomArtist = bottomArtist;
        this.bottomRow = bottomRow;
        this.topArtFrame = topArtFrame;
        this.topOverlayScrim = topOverlayScrim;
        this.topOverlayButton = topOverlayButton;
        this.sideBox = sideBox;
        this.sideArtFrame = sideArtFrame;
        this.sideArt = sideArt;
        this.sideOverlayScrim = sideOverlayScrim;
        this.sideOverlayButton = sideOverlayButton;
        this.sideTitle = sideTitle;
        this.sideArtist = sideArtist;
        this.sideArtDp = sideArtDp;
        this.aspect = aspect;
        this.onRevealChrome = onRevealChrome;
        this.landscape = landscape;
        this.twoColumn = twoColumn;
        this.artTopDp = artTopDp;
        float density = activity.getResources().getDisplayMetrics().density;
        this.playIcon = new ActionIconDrawable(ActionIconDrawable.Kind.PLAY,
                Color.rgb(232, 232, 238), density);
        this.pauseIcon = new PauseBarsDrawable(Color.rgb(232, 232, 238));
        this.panelMediaMode = readPanelMediaMode(config);
        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(activity);
        this.arbiter = new ArtGestureArbiter(vc.getScaledTouchSlop(),
                android.view.ViewConfiguration.getDoubleTapTimeout(), dp(COMMIT_THRESHOLD_DP),
                android.os.SystemClock::elapsedRealtime);
    }

    static TrackInfoReadoutController attach(Activity activity, FrameLayout shellRoot,
            LyricsJumpToCurrentController jumpController, LyricsTextFactory textFactory,
            LyricsHost host, SpotifyPlusConfig config, Runnable onRevealChrome, boolean twoColumn) {
        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int artTop = landscape ? ART_TOP_LANDSCAPE_DP : ART_TOP_PORTRAIT_DP;

        // Top plane: floats below the chrome row exactly like the bottom plane floats above
        // the nav inset. Gradient starts at the screen edge, not at the widget.
        FrameLayout topBox = new FrameLayout(activity);
        topBox.setVisibility(View.GONE);
        topBox.setClipChildren(false);
        GradientDrawable topGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x57000000, Color.TRANSPARENT});
        View topGradientView = new View(activity);
        topGradientView.setBackground(topGradient);
        int topInset = topSystemPadding(activity);
        topBox.addView(topGradientView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, topInset + dp(44 + 8 + artTop + 48),
                Gravity.TOP));
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setClipChildren(false);
        topRow.setPadding(dp(20), dp(8), dp(20), 0);
        TrackInfoReadoutController[] holder = new TrackInfoReadoutController[1];
        ArtTouchFrame topArtFrame = new ArtTouchFrame(activity, () -> {
            if (holder[0] != null) holder[0].toggleFromAccessibility();
        });
        topArtFrame.setClipChildren(false);
        ImageView topArt = new ImageView(activity);
        topArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        styleArt(topArt, 0);
        topArtFrame.addView(topArt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View topScrim = roundedScrim(activity, 0);
        topArtFrame.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageButton topOverlay = overlayButton(activity);
        topOverlay.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].onOverlayButtonClicked(topArtFrame);
        });
        topArtFrame.addView(topOverlay, overlayButtonLp());
        topRow.addView(topArtFrame, new LinearLayout.LayoutParams(dp(artTop), dp(artTop)));
        LinearLayout topText = new LinearLayout(activity);
        topText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams topTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topTextLp.setMarginStart(dp(12));
        TextView topTitle = textFactory.createText(activity, "", 15, Color.WHITE,
                textFactory.resolveTypeface(true));
        topTitle.setGravity(Gravity.START);
        setupMarquee(topTitle);
        TextView topArtist = textFactory.createText(activity, "", 12, Color.rgb(190, 190, 190),
                textFactory.resolveTypeface(false));
        topArtist.setGravity(Gravity.START);
        topArtist.setMaxLines(1);
        topArtist.setEllipsize(TextUtils.TruncateAt.END);
        topText.addView(topTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topText.addView(topArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topRow.addView(topText, topTextLp);
        FrameLayout.LayoutParams topRowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topRowLp.topMargin = topInset + dp(44 + 8);
        topBox.addView(topRow, topRowLp);
        FrameLayout.LayoutParams topBoxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        shellRoot.addView(topBox, topBoxLp);

        // Bottom plane: transparent, readability gradient only.
        int bottomInset = dp(landscape ? 16 : 20);
        FrameLayout bottomBox = new FrameLayout(activity);
        bottomBox.setVisibility(View.GONE);
        bottomBox.setClipChildren(false);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0x61000000});
        View gradientView = new View(activity);
        gradientView.setBackground(gradient);
        bottomBox.addView(gradientView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(EDGE_GRADIENT_DP), Gravity.BOTTOM));
        LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setClipChildren(false);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ART_BOTTOM_DP), Gravity.BOTTOM);
        rowLp.bottomMargin = bottomInset;
        bottomBox.addView(bottomRow, rowLp);

        ArtTouchFrame bottomArtFrame = new ArtTouchFrame(activity, () -> {
            if (holder[0] != null) holder[0].toggleFromAccessibility();
        });
        bottomArtFrame.setClipChildren(false);
        LinearLayout.LayoutParams bottomArtLp = new LinearLayout.LayoutParams(
                dp(ART_BOTTOM_DP), dp(ART_BOTTOM_DP));
        bottomArtLp.setMarginStart(dp(landscape ? 24 : 16));
        ImageView bottomArt = new ImageView(activity);
        bottomArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        styleArt(bottomArt, 0);
        bottomArtFrame.addView(bottomArt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottomScrim = roundedScrim(activity, 0);
        bottomArtFrame.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageButton bottomOverlay = overlayButton(activity);
        bottomOverlay.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].onOverlayButtonClicked(bottomArtFrame);
        });
        bottomArtFrame.addView(bottomOverlay, overlayButtonLp());
        bottomRow.addView(bottomArtFrame, bottomArtLp);
        LinearLayout bottomText = new LinearLayout(activity);
        bottomText.setOrientation(LinearLayout.VERTICAL);
        bottomText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bottomTextLp.setMarginStart(dp(12));
        bottomTextLp.setMarginEnd(dp(16));
        TextView bottomTitle = textFactory.createText(activity, "", 15, Color.WHITE,
                textFactory.resolveTypeface(true));
        bottomTitle.setGravity(Gravity.START);
        setupMarquee(bottomTitle);
        TextView bottomArtist = textFactory.createText(activity, "", 12, Color.rgb(190, 190, 190),
                textFactory.resolveTypeface(false));
        bottomArtist.setGravity(Gravity.START);
        bottomArtist.setMaxLines(1);
        bottomArtist.setEllipsize(TextUtils.TruncateAt.END);
        bottomText.addView(bottomTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomText.addView(bottomArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomRow.addView(bottomText, bottomTextLp);
        shellRoot.addView(bottomBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bottomInset + dp(EDGE_GRADIENT_DP),
                Gravity.BOTTOM));

        // Side dock: its own landscape mode. Left art panel over a horizontal readability
        // gradient; lyrics flow full-width behind. Square art per current art direction.
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float aspect = metrics.widthPixels / (float) Math.max(1, metrics.heightPixels);
        float density = activity.getResources().getDisplayMetrics().density;
        int screenWdp = (int) (metrics.widthPixels / Math.max(0.5f, density));
        int sideArtDp = Math.max(96, (int) (screenWdp * SIDE_PANEL_FRACTION) - 2 * SIDE_ART_MARGIN_DP);
        int sideTopInset = topSystemPadding(activity);
        FrameLayout sideBox = new FrameLayout(activity);
        sideBox.setVisibility(View.GONE);
        sideBox.setClipChildren(false);
        GradientDrawable sideGradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x61000000, Color.TRANSPARENT});
        View sideGradientView = new View(activity);
        sideGradientView.setBackground(sideGradient);
        sideBox.addView(sideGradientView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ArtTouchFrame sideArtFrame = new ArtTouchFrame(activity, () -> {
            if (holder[0] != null) holder[0].toggleFromAccessibility();
        });
        sideArtFrame.setClipChildren(false);
        ImageView sideArt = new ImageView(activity);
        sideArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        styleArt(sideArt, 0);
        sideArtFrame.addView(sideArt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sideScrim = roundedScrim(activity, 0);
        sideArtFrame.addView(sideScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageButton sideOverlay = overlayButton(activity);
        sideOverlay.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].onOverlayButtonClicked(sideArtFrame);
        });
        sideArtFrame.addView(sideOverlay, overlayButtonLp());
        FrameLayout.LayoutParams sideArtLp = new FrameLayout.LayoutParams(
                dp(sideArtDp), dp(sideArtDp), Gravity.TOP | Gravity.START);
        sideArtLp.setMarginStart(dp(SIDE_ART_MARGIN_DP));
        sideArtLp.topMargin = sideTopInset + dp(16);
        sideBox.addView(sideArtFrame, sideArtLp);
        LinearLayout sideText = new LinearLayout(activity);
        sideText.setOrientation(LinearLayout.VERTICAL);
        TextView sideTitle = textFactory.createText(activity, "", 15, Color.WHITE,
                textFactory.resolveTypeface(true));
        sideTitle.setGravity(Gravity.START);
        setupMarquee(sideTitle);
        TextView sideArtist = textFactory.createText(activity, "", 12, Color.rgb(190, 190, 190),
                textFactory.resolveTypeface(false));
        sideArtist.setGravity(Gravity.START);
        sideArtist.setMaxLines(1);
        sideArtist.setEllipsize(TextUtils.TruncateAt.END);
        sideText.addView(sideTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sideText.addView(sideArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams sideTextLp = new FrameLayout.LayoutParams(
                dp(sideArtDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        sideTextLp.setMarginStart(dp(SIDE_ART_MARGIN_DP));
        sideTextLp.topMargin = sideTopInset + dp(16 + sideArtDp + 8);
        sideBox.addView(sideText, sideTextLp);
        FrameLayout.LayoutParams sideBoxLp = new FrameLayout.LayoutParams(
                dp(sideArtDp + 2 * SIDE_ART_MARGIN_DP + SIDE_FADE_DP),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        shellRoot.addView(sideBox, sideBoxLp);

        TrackInfoReadoutController controller = new TrackInfoReadoutController(activity, host,
                config, jumpController, topBox, topArt, topTitle, topArtist,
                bottomBox, bottomArtFrame, bottomArt, bottomScrim, bottomOverlay,
                bottomTitle, bottomArtist, bottomRow, topArtFrame, topScrim, topOverlay,
                sideBox, sideArtFrame, sideArt, sideScrim, sideOverlay,
                sideTitle, sideArtist, sideArtDp, aspect,
                onRevealChrome, landscape, artTop, twoColumn);
        holder[0] = controller;
        controller.topRow = topRow;
        controller.topRowLp = topRowLp;
        controller.topGradientView = topGradientView;
        controller.topInsetPx = topInset;
        controller.layoutTopRow();
        controller.installAccessibility(bottomArtFrame);
        controller.installAccessibility(topArtFrame);
        controller.installAccessibility(sideArtFrame);
        controller.installTouch(bottomArtFrame, () -> controller.bottomArtDpF);
        controller.installTouch(topArtFrame, () -> controller.topArtDpF);
        controller.installTouch(sideArtFrame, () -> controller.sideArtDp);
        controller.setMode(controller.currentMode());
        return controller;
    }

    // -- mode ---------------------------------------------------------------

    private String currentMode() {
        try {
            return config.get(Settings.TRACK_INFO_POSITION);
        } catch (Throwable ignored) {
            return "Off";
        }
    }

    /** Re-reads settings (call at mount and from the preference listener). */
    void onPreferenceChanged() {
        panelMediaMode = readPanelMediaMode(config);
        if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) hideOverlays();
        setMode(currentMode());
    }

    private static String readPanelMediaMode(SpotifyPlusConfig config) {
        try {
            return config.get(Settings.PANEL_MEDIA_CONTROLS);
        } catch (Throwable ignored) {
            return PanelMediaMode.SINGLE_TAP;
        }
    }

    private void setMode(String mode) {
        if (mode == null) mode = "Off";
        // Two-column owns landscape art itself; every overlay stands down while engaged.
        boolean side = !twoColumn && sideModeEngaged(landscape, aspect, mode);
        boolean top = !twoColumn && !side && "Top".equals(mode);
        boolean bottom = !twoColumn && !side && "Bottom".equals(mode);
        boolean enabled = top || bottom || side;
        boolean wasEnabled = artworkEnabled;
        boolean modeChanged = lastMode == null || !mode.equals(lastMode);
        lastMode = mode;
        artworkEnabled = enabled;
        topBox.setVisibility(top ? View.VISIBLE : View.GONE);
        bottomBox.setVisibility(bottom ? View.VISIBLE : View.GONE);
        sideBox.setVisibility(side ? View.VISIBLE : View.GONE);
        applyTextSize();
        applyTextOverflow();
        applyArtSize();
        layoutTopRow();
        if (modeChanged) {
            resetVisuals();
        }
        if (!enabled) {
            if (wasEnabled || modeChanged) {
                clearArtwork();
                artMissing = true;
            }
        } else if ((!wasEnabled || modeChanged) && lastTrack != null) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastArtAttemptMs > ART_RETRY_GAP_MS) {
                trackChangeMs = now;
                attemptArtwork(lastTrack);
            }
        }
    }

    /**
     * Top readout owns the top-left corner (where Back used to be): art starts at the
     * top inset with no chrome gap, text reserves the right control rail. Called at
     * mount, mode change, and art-size change only — never per frame.
     */
    private void layoutTopRow() {
        if (topRow == null || topRowLp == null) return;
        int sidePad;
        try {
            sidePad = sideSystemPadding(activity);
        } catch (Throwable ignored) {
            sidePad = dp(20);
        }
        topRowLp.topMargin = topInsetPx;
        topRowLp.setMarginStart(0);
        topRowLp.setMarginEnd(0);
        topRow.setLayoutParams(topRowLp);
        // 44dp rail + 8dp gap kept clear at the right edge.
        topRow.setPadding(sidePad, dp(8), sidePad + dp(44 + 8), 0);
        if (topGradientView != null) {
            ViewGroup.LayoutParams glp = topGradientView.getLayoutParams();
            int wantH = topInsetPx + dp(topArtDpF + 64);
            if (glp != null && glp.height != wantH) {
                glp.height = wantH;
                topGradientView.setLayoutParams(glp);
            }
        }
    }

    /** Clears every readout image and recycles owned bitmaps (views first, then recycle). */
    private void clearArtwork() {
        topArt.setImageBitmap(null);
        bottomArt.setImageBitmap(null);
        sideArt.setImageBitmap(null);
        if (currentArtwork != null) {
            try {
                currentArtwork.recycle();
            } catch (Throwable ignored) {
            }
            currentArtwork = null;
        }
        if (sideArtwork != null) {
            try {
                sideArtwork.recycle();
            } catch (Throwable ignored) {
            }
            sideArtwork = null;
        }
    }

    private String currentArtSize() {
        try {
            return config.get(Settings.TRACK_INFO_ART_SIZE);
        } catch (Throwable ignored) {
            return "Normal";
        }
    }

    /** Applies the art-size setting to live layout params (no remount). */
    /** Optional sibling chip stacked above jump-to-current; mirrors its bottom margin. */
    void setSkipGapController(LyricsSkipGapController skipGapController) {
        this.skipGapController = skipGapController;
    }

    private void applyArtSize() {
        int[] sizes = readoutArtSizes(currentArtSize());
        bottomArtDpF = sizes[0];
        topArtDpF = landscape ? ART_TOP_LANDSCAPE_DP : sizes[1];
        setSquareLp(bottomArtFrame, dp(bottomArtDpF));
        setSquareLp(topArtFrame, dp(topArtDpF));
        ViewGroup.LayoutParams rowLp = bottomRow.getLayoutParams();
        if (rowLp != null) {
            rowLp.height = dp(bottomArtDpF);
            bottomRow.setLayoutParams(rowLp);
        }
        boolean bottom = bottomBox.getVisibility() == View.VISIBLE;
        int jumpMarginDp = bottom
                ? (landscape ? 16 : 20) + bottomArtDpF + CLUSTER_GAP_DP : 24;
        jumpController.setBottomMarginDp(jumpMarginDp);
        if (skipGapController != null) skipGapController.setBottomMarginDp(jumpMarginDp);
    }

    private static void setSquareLp(View view, int sizePx) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        if (lp.width != sizePx || lp.height != sizePx) {
            lp.width = sizePx;
            lp.height = sizePx;
            view.setLayoutParams(lp);
        }
    }

    private void applyTextSize() {
        String value = "Normal";
        try {
            value = config.get(Settings.TRACK_INFO_TEXT_SIZE);
        } catch (Throwable ignored) {
        }
        float titleSp = 15f;
        float artistSp = 12f;
        if ("Small".equals(value)) {
            titleSp = 13f;
            artistSp = 11f;
        } else if ("Large".equals(value)) {
            titleSp = 18f;
            artistSp = 14f;
        } else if ("XLarge".equals(value)) {
            titleSp = 22f;
            artistSp = 16f;
        } else if ("Custom".equals(value)) {
            int multiplierX100 = 100;
            try {
                multiplierX100 = config.get(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM);
            } catch (Throwable ignored) {
            }
            float scale = Math.max(50, Math.min(200, multiplierX100)) / 100f;
            titleSp = 15f * scale;
            artistSp = 12f * scale;
        }
        topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        bottomTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        sideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        topArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
        bottomArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
        sideArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
    }

    /** Coerces the overflow setting to Clip/Wrap/Scroll (unknown values fall back to Wrap). */
    static String normalizeOverflow(String value) {
        if ("Clip".equals(value) || "Scroll".equals(value)) return value;
        return "Wrap";
    }

    private String currentOverflow() {
        try {
            return normalizeOverflow(config.get(Settings.TRACK_INFO_TEXT_OVERFLOW));
        } catch (Throwable ignored) {
            return "Wrap";
        }
    }

    /** Applies Clip/Wrap/Scroll to every readout title/artist (live, no remount). */
    private void applyTextOverflow() {
        String mode = currentOverflow();
        applyOverflowMode(topTitle, mode);
        applyOverflowMode(bottomTitle, mode);
        applyOverflowMode(sideTitle, mode);
        applyOverflowMode(topArtist, mode);
        applyOverflowMode(bottomArtist, mode);
        applyOverflowMode(sideArtist, mode);
    }

    private static void applyOverflowMode(TextView view, String mode) {
        if (view == null) return;
        if ("Scroll".equals(mode)) {
            setupMarquee(view);
        } else if ("Clip".equals(mode)) {
            view.setSingleLine(true);
            view.setMaxLines(1);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setHorizontalFadingEdgeEnabled(false);
            view.setSelected(false);
        } else {
            view.setSingleLine(false);
            view.setMaxLines(2);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setHorizontalFadingEdgeEnabled(false);
            view.setSelected(false);
        }
    }

    // -- per-frame updates (called from the shell's updateState) --------------

    /** Updates texts/artwork on track change (with throttled retry on miss); cheap otherwise. */
    void onTrackChanged(SpotifyTrack track) {
        String uri = track == null || track.uri == null ? "" : track.uri;
        long now = android.os.SystemClock.elapsedRealtime();
        lastTrack = track;
        if (!uri.equals(lastUri)) {
            lastUri = uri;
            trackChangeMs = now;
            resetVisuals();
            if (artworkEnabled) attemptArtwork(track);
        } else if (artworkEnabled && artMissing && now - trackChangeMs < ART_RETRY_WINDOW_MS
                && now - lastArtAttemptMs > ART_RETRY_GAP_MS) {
            attemptArtwork(track);
        }
        String title = track == null ? "Waiting for Spotify track…" : emptyFallback(track.title);
        String artist = track == null ? "" : emptyFallback(track.artist);
        if (!title.equals(lastTitle)) {
            lastTitle = title;
            topTitle.setText(title);
            bottomTitle.setText(title);
            sideTitle.setText(title);
        }
        if (!artist.equals(lastArtist)) {
            lastArtist = artist;
            topArtist.setText(artist);
            bottomArtist.setText(artist);
            sideArtist.setText(artist);
        }
        updateContentDescriptions();
    }

    void onPlayingChanged(boolean playing) {
        if (playing == lastPlaying) return;
        lastPlaying = playing;
        applyOverlayIcon();
        updateContentDescriptions();
    }

    void teardown() {
        arbiter.reset();
        cancelArmed = false;
        activeFrame = null;
        cancelDragApply();
        bottomArtFrame.removeCallbacks(hideOverlayRunnable);
        topArtFrame.removeCallbacks(hideOverlayRunnable);
        sideArtFrame.removeCallbacks(hideOverlayRunnable);
        cancelFrameAnimation(topArtFrame);
        cancelFrameAnimation(bottomArtFrame);
        cancelFrameAnimation(sideArtFrame);
        hideOverlays();
        topArtFrame.setTranslationX(0f);
        bottomArtFrame.setTranslationX(0f);
        sideArtFrame.setTranslationX(0f);
        clearArtwork();
        artMissing = true;
    }

    // -- artwork ------------------------------------------------------------------

    private void attemptArtwork(SpotifyTrack track) {
        lastArtAttemptMs = android.os.SystemClock.elapsedRealtime();
        // Snapshot only the active surfaces: an inactive miss must not cause retry churn,
        // and Off snapshots nothing at all.
        boolean needSmall = topBox.getVisibility() == View.VISIBLE
                || bottomBox.getVisibility() == View.VISIBLE;
        boolean needSide = sideBox.getVisibility() == View.VISIBLE;
        Bitmap rounded = currentArtwork;
        Bitmap sideRounded = sideArtwork;
        if (track != null && needSmall) {
            try {
                Bitmap raw = SpotifyArtworkCache.snapshot(track.imageId, track.uri);
                if (currentArtwork != null) {
                    try {
                        currentArtwork.recycle();
                    } catch (Throwable ignored) {
                    }
                    currentArtwork = null;
                }
                if (raw != null) {
                    int sizePx = dp(bottomArtDpF);
                    rounded = roundBitmap(raw, sizePx, 0);
                    raw.recycle();
                } else {
                    rounded = null;
                }
            } catch (Throwable ignored) {
                rounded = null;
            }
        }
        if (track != null && needSide) {
            try {
                Bitmap large = SpotifyArtworkCache.snapshotLarge(track.imageId, track.uri,
                        dp(sideArtDp));
                if (sideArtwork != null) {
                    try {
                        sideArtwork.recycle();
                    } catch (Throwable ignored) {
                    }
                    sideArtwork = null;
                }
                if (large != null) {
                    sideRounded = roundBitmap(large, large.getWidth(), 0);
                    large.recycle();
                } else {
                    sideRounded = null;
                }
            } catch (Throwable ignored) {
                sideRounded = null;
            }
        }
        currentArtwork = rounded;
        sideArtwork = sideRounded;
        // Detach views before publishing: a recycled bitmap must never stay referenced.
        topArt.setImageBitmap(null);
        bottomArt.setImageBitmap(null);
        sideArt.setImageBitmap(null);
        if (needSmall) {
            topArt.setImageBitmap(rounded);
            bottomArt.setImageBitmap(rounded);
        }
        if (needSide) {
            sideArt.setImageBitmap(sideRounded);
        }
        artMissing = (needSmall && rounded == null) || (needSide && sideRounded == null);
    }

    /** Rounds once per track change so drag frames never pay for an outline mask. */
    private static Bitmap roundBitmap(Bitmap src, int sizePx, float radiusPx) {
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF rect = new RectF(0, 0, sizePx, sizePx);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, null, rect, paint);
        return out;
    }

    // -- touch ----------------------------------------------------------------

    private void installTouch(final ArtTouchFrame frame, final SizeProvider sizes) {
        frame.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() > 1) {
                arbiter.onCancel();
                resetVisuals();
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                frame.animate().cancel();
                if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) {
                    arbiter.reset();
                    cancelArmed = false;
                    activeFrame = null;
                    if (onRevealChrome != null) onRevealChrome.run();
                    return true;
                }
                activeFrame = frame;
                dragBoundPx = dp(sizes.artDp());
                // Stable coordinates: getX/getY run in the frame's local space, which
                // translates with the drag and feeds back into the next delta. Raw
                // screen coordinates stay fixed while the frame moves.
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                long now = android.os.SystemClock.elapsedRealtime();
                if (overlayVisible(frame) && !arbiter.isDoubleTapCandidate(now)) {
                    // Tap around the button dismisses the overlay; it must not toggle.
                    arbiter.reset();
                    cancelArmed = true;
                } else {
                    arbiter.onDown(now);
                }
                if (onRevealChrome != null) onRevealChrome.run();
                return true;
            }
            if (activeFrame != frame) return true;
            if (action == MotionEvent.ACTION_MOVE) {
                ArtGestureArbiter.Output out =
                        arbiter.onMove(event.getRawX() - downRawX, event.getRawY() - downRawY);
                if (out == ArtGestureArbiter.Output.DRAG_UPDATE) {
                    cancelArmed = false;
                    hideOverlays();
                    scheduleDragApply(frame, clampedDrag(arbiter.dragDxPx()));
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                cancelDragApply();
                if (cancelArmed) {
                    cancelArmed = false;
                    hideOverlays();
                    activeFrame = null;
                    return true;
                }
                handleUp(arbiter.onUp(), frame, sizes.artDp());
                activeFrame = null;
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                arbiter.onCancel();
                cancelArmed = false;
                resetVisuals();
                activeFrame = null;
                return true;
            }
            return true;
        });
    }

    private void handleUp(ArtGestureArbiter.Output out, ArtTouchFrame frame, int artDp) {
        switch (out) {
            case REVEAL:
                if (PanelMediaMode.revealOnSingleTap(panelMediaMode)) showOverlay(frame);
                else springBack(frame);
                break;
            case TOGGLE:
                if (toggleTransport()) flashIconOnly(frame);
                break;
            case COMMIT_NEXT:
                if (!commitTrack(true)) springBack(frame);
                else followThrough(frame, -artDp);
                break;
            case COMMIT_PREV:
                if (!commitTrack(false)) springBack(frame);
                else followThrough(frame, artDp);
                break;
            case SPRING_BACK:
                springBack(frame);
                break;
            default:
                break;
        }
    }

    private float clampedDrag(float dx) {
        float ax = Math.abs(dx);
        if (ax <= dragBoundPx) return dx;
        return Math.signum(dx) * (dragBoundPx + (ax - dragBoundPx) * 0.3f);
    }

    /** One translationX write per vsync no matter how fast MOVE events arrive. */
    private void scheduleDragApply(ArtTouchFrame frame, float dx) {
        pendingDragFrame = frame;
        pendingDragDx = dx;
        if (!dragFrameScheduled) {
            dragFrameScheduled = true;
            frame.postOnAnimation(dragApplyRunnable);
        }
    }

    private void cancelDragApply() {
        dragFrameScheduled = false;
        pendingDragFrame = null;
        topArtFrame.removeCallbacks(dragApplyRunnable);
        bottomArtFrame.removeCallbacks(dragApplyRunnable);
        sideArtFrame.removeCallbacks(dragApplyRunnable);
    }

    private void springBack(ArtTouchFrame frame) {
        frame.animate().cancel();
        frame.animate().translationX(0f).setDuration(SETTLE_ANIM_MS).start();
    }

    private void followThrough(ArtTouchFrame frame, int targetDp) {
        frame.animate().cancel();
        frame.animate().translationX(dp(targetDp)).setDuration(160L)
                .withEndAction(() -> frame.animate().translationX(0f).setDuration(120L).start())
                .start();
    }

    private boolean commitTrack(boolean next) {
        try {
            return next ? host.skipToNextTrack() : host.skipToPreviousTrack();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean toggleTransport() {
        try {
            return host.togglePlayPause();
        } catch (Throwable ignored) {
            return false;
        }
        // No optimistic lastPlaying flip; onPlayingChanged() applies observed state.
    }

    void toggleFromAccessibility() {
        toggleTransport();
    }

    // -- overlay ----------------------------------------------------------------

    private void showOverlay(ArtTouchFrame frame) {
        applyOverlayIcon();
        for (ArtTouchFrame f : new ArtTouchFrame[]{topArtFrame, bottomArtFrame, sideArtFrame}) {
            boolean active = f == frame;
            f.removeCallbacks(hideOverlayRunnable);
            if (active) {
                overlayScrimFor(f).setVisibility(View.VISIBLE);
                ImageButton button = overlayButtonFor(f);
                button.setVisibility(View.VISIBLE);
                overlayScrimFor(f).setAlpha(0f);
                overlayScrimFor(f).animate().alpha(1f).setDuration(150L).start();
                // PR9-style reveal: button pops in with a short overshoot spring.
                button.setAlpha(0f);
                button.setScaleX(0.6f);
                button.setScaleY(0.6f);
                button.animate().alpha(1f).setDuration(150L).start();
                button.animate().scaleX(1f).scaleY(1f).setDuration(REVEAL_SPRING_MS)
                        .setInterpolator(
                                new android.view.animation.OvershootInterpolator(2.0f))
                        .start();
                f.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS);
            } else {
                overlayScrimFor(f).setVisibility(View.GONE);
                overlayButtonFor(f).setVisibility(View.GONE);
            }
        }
    }

    private void hideOverlays() {
        topArtFrame.removeCallbacks(hideOverlayRunnable);
        bottomArtFrame.removeCallbacks(hideOverlayRunnable);
        sideArtFrame.removeCallbacks(hideOverlayRunnable);
        for (ArtTouchFrame f : new ArtTouchFrame[]{topArtFrame, bottomArtFrame, sideArtFrame}) {
            overlayScrimFor(f).animate().cancel();
            overlayButtonFor(f).animate().cancel();
            overlayScrimFor(f).setVisibility(View.GONE);
            overlayButtonFor(f).setVisibility(View.GONE);
        }
    }

    private void applyOverlayIcon() {
        Drawable icon = lastPlaying ? pauseIcon : playIcon;
        topOverlayButton.setImageDrawable(icon);
        bottomOverlayButton.setImageDrawable(icon);
        sideOverlayButton.setImageDrawable(icon);
    }

    private View overlayScrimFor(ArtTouchFrame frame) {
        if (frame == topArtFrame) return topOverlayScrim;
        return frame == sideArtFrame ? sideOverlayScrim : bottomOverlayScrim;
    }

    private ImageButton overlayButtonFor(ArtTouchFrame frame) {
        if (frame == topArtFrame) return topOverlayButton;
        return frame == sideArtFrame ? sideOverlayButton : bottomOverlayButton;
    }

    private void resetVisuals() {
        arbiter.reset();
        cancelArmed = false;
        activeFrame = null;
        cancelDragApply();
        cancelFrameAnimation(topArtFrame);
        cancelFrameAnimation(bottomArtFrame);
        cancelFrameAnimation(sideArtFrame);
        topArtFrame.setTranslationX(0f);
        bottomArtFrame.setTranslationX(0f);
        sideArtFrame.setTranslationX(0f);
        hideOverlays();
    }

    private static void cancelFrameAnimation(ArtTouchFrame frame) {
        if (frame == null) return;
        try {
            frame.animate().cancel();
            frame.animate().withEndAction(null);
        } catch (Throwable ignored) {
        }
    }

    /** Screen-space hit test covering the art frames (all three surfaces). */
    boolean containsArtTouch(float rawX, float rawY) {
        if (!artworkEnabled) return false;
        return hitsFrame(topBox, topArtFrame, rawX, rawY)
                || hitsFrame(bottomBox, bottomArtFrame, rawX, rawY)
                || hitsFrame(sideBox, sideArtFrame, rawX, rawY);
    }

    private static boolean hitsFrame(View box, ArtTouchFrame frame, float rawX, float rawY) {
        if (box == null || frame == null) return false;
        if (box.getVisibility() != View.VISIBLE) return false;
        if (frame.getWidth() <= 0 || frame.getHeight() <= 0) return false;
        try {
            int[] loc = new int[2];
            frame.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX < loc[0] + frame.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + frame.getHeight();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Outside-art ACTION_DOWN: drop stale gesture/visual state without consuming. */
    void onOutsideDown() {
        resetVisuals();
    }

    // -- accessibility ------------------------------------------------------------

    private void installAccessibility(ArtTouchFrame frame) {
        frame.setClickable(true);
        frame.setFocusable(true);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        frame.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View hostView,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(hostView, info);
                info.setClassName("android.widget.ImageButton");
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        lastPlaying ? "Pause" : "Play"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_PREV_ID, "Previous track"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_NEXT_ID, "Next track"));
            }

            @Override
            public boolean performAccessibilityAction(View hostView, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    return toggleTransport();
                }
                if (action == ACTION_PREV_ID) {
                    return commitTrack(false);
                }
                if (action == ACTION_NEXT_ID) {
                    return commitTrack(true);
                }
                return super.performAccessibilityAction(hostView, action, args);
            }
        });
    }

    private void updateContentDescriptions() {
        String state = lastPlaying ? "Playing" : "Paused";
        String desc = lastTitle.isEmpty() ? state : lastTitle + " — " + lastArtist + ". " + state;
        if (desc.equals(lastContentDescription)) return;
        lastContentDescription = desc;
        topArtFrame.setContentDescription(desc);
        bottomArtFrame.setContentDescription(desc);
        sideArtFrame.setContentDescription(desc);
    }

    // -- view helpers ---------------------------------------------------------------

    private static void styleArt(ImageView art, int radiusDp) {
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF3A3F55);
        placeholder.setCornerRadius(dp(radiusDp));
        art.setBackground(placeholder);
    }

    /** Overlay scrim with the art's shape (rounded readout, square side panel). */
    private static View roundedScrim(Context context, int radiusDp) {
        GradientDrawable scrim = new GradientDrawable();
        scrim.setColor(0x73000000);
        scrim.setCornerRadius(dp(radiusDp));
        View view = new View(context);
        view.setBackground(scrim);
        view.setVisibility(View.GONE);
        return view;
    }

    private static ImageButton overlayButton(Context context) {
        // 48dp centered target: taps on it toggle, taps around it fall through to the frame.
        ImageButton button = new ImageButton(context);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setVisibility(View.GONE);
        button.setFocusable(false);
        button.setClickable(true);
        return button;
    }

    private static FrameLayout.LayoutParams overlayButtonLp() {
        int size = dp(48);
        return new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
    }

    private static void setupMarquee(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1);
        view.setHorizontalFadingEdgeEnabled(true);
        view.setSelected(true);
    }

    private static String emptyFallback(String value) {
        return value == null || value.isEmpty() ? "Unknown" : value;
    }

    /** Tap on the overlay button itself: toggle and keep the new state visible briefly. */
    void onOverlayButtonClicked(ArtTouchFrame frame) {
        if (toggleTransport()) showOverlay(frame);
    }

    /** Double-tap feedback: brief icon pulse with no scrim. */
    private void flashIconOnly(ArtTouchFrame frame) {
        applyOverlayIcon();
        View scrim = overlayScrimFor(frame);
        ImageButton button = overlayButtonFor(frame);
        scrim.animate().cancel();
        scrim.setVisibility(View.GONE);
        button.setVisibility(View.VISIBLE);
        button.animate().cancel();
        button.setAlpha(0f);
        button.setScaleX(1f);
        button.setScaleY(1f);
        button.animate().alpha(1f).setDuration(150L).start();
        frame.removeCallbacks(hideOverlayRunnable);
        frame.postDelayed(hideOverlayRunnable, OVERLAY_FLASH_MS);
    }

    private boolean overlayVisible(ArtTouchFrame frame) {
        return overlayScrimFor(frame).getVisibility() == View.VISIBLE;
    }

    /** Live art size for touch clamping (art size setting applies without remount). */
    interface SizeProvider {
        int artDp();
    }

    /** TalkBack activation path, separate from raw-touch gestures (which TalkBack must not fire). */
    private static final class ArtTouchFrame extends FrameLayout {
        private final Runnable onPerformClick;

        ArtTouchFrame(Context context, Runnable onPerformClick) {
            super(context);
            this.onPerformClick = onPerformClick;
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (onPerformClick != null) onPerformClick.run();
            return true;
        }
    }

    /** Deterministic pause bars on the same 24-unit grid as the Lucide glyphs. */
    static final class PauseBarsDrawable extends Drawable {
        private final int color;

        PauseBarsDrawable(int color) {
            this.color = color;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            float barW = 5f * s;
            float barH = 14f * s;
            float top = b.centerY() - barH / 2f;
            float r = 1.6f * s;
            canvas.drawRoundRect(b.centerX() - barW - 1.5f * s, top,
                    b.centerX() - 1.5f * s, top + barH, r, r, paint);
            canvas.drawRoundRect(b.centerX() + 1.5f * s, top,
                    b.centerX() + barW + 1.5f * s, top + barH, r, r, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
