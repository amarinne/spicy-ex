package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

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
    // Was 20dp/16dp - close enough to the true screen edge to be an awkward one-handed reach.
    // Nudged up for a more comfortable tap target on the Bottom-position artwork.
    private static final int BOTTOM_ART_INSET_PORTRAIT_DP = 36;
    private static final int BOTTOM_ART_INSET_LANDSCAPE_DP = 28;
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

    // Remote (Spotify Connect) playback: Spotify's own MediaMetadata often carries only an art
    // URI with no embedded Bitmap in that mode, so SpotifyArtworkCache (which requires a real
    // Bitmap) never has anything to serve. This is a network fallback fetched straight from
    // Spotify's public image CDN by id, so the existing artMissing retry loop above (still on its
    // ART_RETRY_GAP_MS cadence) picks it up as soon as it lands - no extra re-render plumbing.
    private static final int ART_NETWORK_CACHE_LIMIT = 4;
    static final java.util.Map<String, Bitmap> ART_NETWORK_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Bitmap>(ART_NETWORK_CACHE_LIMIT, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
                            return size() > ART_NETWORK_CACHE_LIMIT;
                        }
                    });
    private static final java.util.Set<String> ART_NETWORK_FETCH_IN_FLIGHT =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            
    // Active listeners to notify whenever a network artwork download arrives successfully.
    static final java.util.List<Runnable> ART_NETWORK_LISTENERS = 
            new java.util.ArrayList<>();
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

    /** "Custom" variant driven by a continuous dp value (the layout editor's resize handle) -
     *  every other value delegates to the fixed-preset overload above. Unlike the fixed presets,
     *  Custom applies the exact same dp to every placement: it's driven by dragging the actual
     *  rendered frame's corner handle, so whatever size that handle is dragged to is what should
     *  come back on screen - a Top-mode frame quietly ending up smaller than what was dragged
     *  would defeat the point of a direct-manipulation control. */
    static int[] readoutArtSizes(String value, int customBottomDp) {
        if ("Custom".equals(value)) {
            int size = Math.max(24, customBottomDp);
            return new int[]{size, size};
        }
        return readoutArtSizes(value);
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
    /** Album line, appended below title/artist in each placement's text stack. Populated from
     *  {@link SpotifyTrack#album} - a field that had no display path on the lyrics screen at all
     *  before {@link Settings#TRACK_INFO_SHOW_ALBUM} - and shown/hidden per the three
     *  TRACK_INFO_SHOW_* settings alongside title/artist. */
    private TextView topAlbum;
    private TextView bottomAlbum;
    private TextView sideAlbum;

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
    /** Title+artist stacks, vertically aligned within their row by {@link #applyTextAlign()}. */
    private LinearLayout topText;
    private LinearLayout bottomText;
    private LinearLayout sideText;
    /** Opaque fill for the Solid background choice; matches the shell's darkest backdrop. */
    private static final int SOLID_BACKDROP = 0xFF0B0B0D;
    /** Chrome header row and the flexible title the readout replaces in "Header" mode. */
    private ViewGroup headerRow;
    private View headerTitle;
    private View topGradientView;
    private View bottomGradientView;
    private View sideGradientView;
    private int topInsetPx;
    private boolean lastPlaying = true;
    private String lastTitle = "";
    private String lastArtist = "";
    private String lastAlbum = "";
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
    /** Current readout art/scrim corner radius (dp); -1 forces the first applyArtRadius() to act. */
    private int artRadiusDp = -1;
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
            Runnable onRevealChrome, boolean landscape, int artTopDp, boolean twoColumn,
            ViewGroup headerRow, View headerTitle) {
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
        this.headerRow = headerRow;
        this.headerTitle = headerTitle;
        float density = activity.getResources().getDisplayMetrics().density;
        this.playIcon = new ActionIconDrawable(ActionIconDrawable.Kind.PLAY,
                Color.rgb(232, 232, 238), density, true);
        this.pauseIcon = new PauseBarsDrawable(Color.rgb(232, 232, 238));
        this.panelMediaMode = readPanelMediaMode(config);
        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(activity);
        this.arbiter = new ArtGestureArbiter(vc.getScaledTouchSlop(),
                android.view.ViewConfiguration.getDoubleTapTimeout(), dp(COMMIT_THRESHOLD_DP),
                android.os.SystemClock::elapsedRealtime);
    }

    static TrackInfoReadoutController attach(Activity activity, FrameLayout shellRoot,
            LyricsJumpToCurrentController jumpController, LyricsTextFactory textFactory,
            LyricsHost host, SpotifyPlusConfig config, Runnable onRevealChrome, boolean twoColumn,
            ViewGroup headerRow, View headerTitle) {
        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int artTop = landscape ? ART_TOP_LANDSCAPE_DP : ART_TOP_PORTRAIT_DP;

        // Top plane: floats below the chrome row exactly like the bottom plane floats above
        // the nav inset. Gradient starts at the screen edge, not at the widget.
        FrameLayout topBox = new FrameLayout(activity);
        topBox.setVisibility(View.GONE);
        topBox.setClipChildren(false);
        // Strengthened from a flat two-stop 0x57 (~34%) fade: that read as too weak to keep the
        // title/artist text legible over a bright or busy piece of artwork. A third stop gives a
        // darker plateau right behind the text before fading out, rather than a uniform ramp.
        GradientDrawable topGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x8A000000, Color.TRANSPARENT});
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
        // MATCH_PARENT (not WRAP_CONTENT) so applyTextAlign() can position the title/artist stack
        // anywhere within the row's full height, the same mechanism bottomText already uses.
        LinearLayout.LayoutParams topTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
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
        TextView topAlbum = textFactory.createText(activity, "", 11, Color.rgb(160, 160, 160),
                textFactory.resolveTypeface(false));
        topAlbum.setGravity(Gravity.START);
        topAlbum.setMaxLines(1);
        topAlbum.setEllipsize(TextUtils.TruncateAt.END);
        topText.addView(topTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topText.addView(topArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topText.addView(topAlbum, new LinearLayout.LayoutParams(
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

        // Bottom plane: transparent, readability gradient only. (Not a floating dock card - the
        // fullscreen chrome's "Header" mode below is the recommended Apple-Music-style layout,
        // matching the artwork-in-the-header-row approach; Bottom stays the plain original strip
        // as a secondary option.)
        int bottomInset = dp(landscape ? BOTTOM_ART_INSET_LANDSCAPE_DP : BOTTOM_ART_INSET_PORTRAIT_DP);
        FrameLayout bottomBox = new FrameLayout(activity);
        bottomBox.setVisibility(View.GONE);
        bottomBox.setClipChildren(false);
        // Strengthened from a flat two-stop 0x61 (~38%) fade for the same legibility reason as
        // the top gradient above.
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0x8A000000, 0xC2000000});
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
        // Gravity set by applyTextAlign() below, not hardcoded here.
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
        TextView bottomAlbum = textFactory.createText(activity, "", 11, Color.rgb(160, 160, 160),
                textFactory.resolveTypeface(false));
        bottomAlbum.setGravity(Gravity.START);
        bottomAlbum.setMaxLines(1);
        bottomAlbum.setEllipsize(TextUtils.TruncateAt.END);
        bottomText.addView(bottomTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomText.addView(bottomArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomText.addView(bottomAlbum, new LinearLayout.LayoutParams(
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
                new int[]{0xB3000000, 0x8A000000, Color.TRANSPARENT});
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
        TextView sideAlbum = textFactory.createText(activity, "", 11, Color.rgb(160, 160, 160),
                textFactory.resolveTypeface(false));
        sideAlbum.setGravity(Gravity.START);
        sideAlbum.setMaxLines(1);
        sideAlbum.setEllipsize(TextUtils.TruncateAt.END);
        sideText.addView(sideTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sideText.addView(sideArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sideText.addView(sideAlbum, new LinearLayout.LayoutParams(
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
                onRevealChrome, landscape, artTop, twoColumn, headerRow, headerTitle);
        holder[0] = controller;
        controller.topRow = topRow;
        controller.topRowLp = topRowLp;
        controller.topGradientView = topGradientView;
        controller.bottomGradientView = gradientView;
        controller.sideGradientView = sideGradientView;
        controller.topText = topText;
        controller.bottomText = bottomText;
        controller.sideText = sideText;
        controller.topAlbum = topAlbum;
        controller.bottomAlbum = bottomAlbum;
        controller.sideAlbum = sideAlbum;
        controller.applyBackgroundStyle();
        controller.applyArtRadius();
        controller.applyTextAlign();
        controller.applyFieldVisibility();
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

    /** The art frame actually visible on screen right now, or null when the readout is Off.
     *  Header mode re-parents topRow (and topArtFrame within it) into the chrome header row -
     *  topBox itself goes GONE there, so that case is checked by mode rather than box
     *  visibility. Used by the layout editor to anchor its selection overlay on the real view. */
    View currentArtFrame() {
        if ("Header".equals(lastMode)) return topArtFrame;
        if (sideBox.getVisibility() == View.VISIBLE) return sideArtFrame;
        if (topBox.getVisibility() == View.VISIBLE) return topArtFrame;
        if (bottomBox.getVisibility() == View.VISIBLE) return bottomArtFrame;
        return null;
    }

    /** The title/artist/album text stack actually visible on screen right now, mirroring
     *  {@link #currentArtFrame()} - used by the layout editor to give the text its own selection
     *  outline, separate from the artwork it used to be bundled with. */
    View currentTextFrame() {
        if ("Header".equals(lastMode)) return topText;
        if (sideBox.getVisibility() == View.VISIBLE) return sideText;
        if (topBox.getVisibility() == View.VISIBLE) return topText;
        if (bottomBox.getVisibility() == View.VISIBLE) return bottomText;
        return null;
    }

    /** Shows/hides title, artist, and album in every placement per
     *  {@link Settings#TRACK_INFO_SHOW_TITLE}/{@code _ARTIST}/{@code _ALBUM} - independent of
     *  position/size, so hiding a field doesn't need its own layout mode. */
    private void applyFieldVisibility() {
        boolean showTitle = readBool(Settings.TRACK_INFO_SHOW_TITLE);
        boolean showArtist = readBool(Settings.TRACK_INFO_SHOW_ARTIST);
        boolean showAlbum = readBool(Settings.TRACK_INFO_SHOW_ALBUM);
        int titleVis = showTitle ? View.VISIBLE : View.GONE;
        int artistVis = showArtist ? View.VISIBLE : View.GONE;
        int albumVis = showAlbum ? View.VISIBLE : View.GONE;
        topTitle.setVisibility(titleVis);
        bottomTitle.setVisibility(titleVis);
        sideTitle.setVisibility(titleVis);
        topArtist.setVisibility(artistVis);
        bottomArtist.setVisibility(artistVis);
        sideArtist.setVisibility(artistVis);
        if (topAlbum != null) topAlbum.setVisibility(albumVis);
        if (bottomAlbum != null) bottomAlbum.setVisibility(albumVis);
        if (sideAlbum != null) sideAlbum.setVisibility(albumVis);
    }

    private boolean readBool(Settings.Setting<Boolean> setting) {
        try {
            Boolean value = config.get(setting);
            return value == null ? setting.defaultValue : value;
        } catch (Throwable ignored) {
            return setting.defaultValue;
        }
    }

    String currentMode() {
        try {
            return config.get(Settings.TRACK_INFO_POSITION);
        } catch (Throwable ignored) {
            return "Off";
        }
    }

    /**
     * Applies the "Track info background" choice.
     *
     * <p>The dock floats over the lyrics, so by default only a short edge scrim separates them and
     * lyric lines run underneath the title. Solid fills the whole dock instead, so nothing reads
     * through it; None removes the separation entirely.
     */
    private void applyBackgroundStyle() {
        String style = config.get(Settings.TRACK_INFO_BACKGROUND);
        boolean solid = "Solid".equals(style);
        boolean none = "None".equals(style);
        int fill = solid ? SOLID_BACKDROP : Color.TRANSPARENT;
        topBox.setBackgroundColor(fill);
        bottomBox.setBackgroundColor(fill);
        sideBox.setBackgroundColor(fill);
        int scrim = solid || none ? View.GONE : View.VISIBLE;
        if (topGradientView != null) topGradientView.setVisibility(scrim);
        if (bottomGradientView != null) bottomGradientView.setVisibility(scrim);
        if (sideGradientView != null) sideGradientView.setVisibility(scrim);
    }

    /**
     * Applies the "Track info art corner radius" setting to every placement's art placeholder
     * and touch scrim, and re-rounds the already-loaded artwork bitmap so a live change doesn't
     * wait for the next track to take effect.
     */
    private void applyArtRadius() {
        int radius = 16;
        try {
            radius = config.get(Settings.TRACK_INFO_ART_RADIUS);
        } catch (Throwable ignored) {
        }
        if (radius == artRadiusDp) return;
        artRadiusDp = radius;
        setCornerRadiusDp(topArt, radius);
        setCornerRadiusDp(bottomArt, radius);
        setCornerRadiusDp(sideArt, radius);
        setCornerRadiusDp(topOverlayScrim, radius);
        setCornerRadiusDp(bottomOverlayScrim, radius);
        setCornerRadiusDp(sideOverlayScrim, radius);
        if (lastTrack != null) attemptArtwork(lastTrack);
    }

    private static void setCornerRadiusDp(View view, int radiusDp) {
        if (view == null) return;
        Drawable bg = view.getBackground();
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setCornerRadius(dp(radiusDp));
        }
    }

    /**
     * Vertically aligns the title/artist stack within its row for Top/Bottom/Header (Header
     * reuses topText unchanged, so it inherits Top's alignment automatically). Side stacks text
     * below the artwork rather than beside it, so this setting has no effect there.
     */
    private void applyTextAlign() {
        String align = "Center";
        try {
            align = config.get(Settings.TRACK_INFO_TEXT_ALIGN);
        } catch (Throwable ignored) {
        }
        int gravity = "Top".equals(align) ? Gravity.TOP
                : "Bottom".equals(align) ? Gravity.BOTTOM
                : Gravity.CENTER_VERTICAL;
        if (topText != null) topText.setGravity(gravity);
        if (bottomText != null) bottomText.setGravity(gravity);
    }

    /**
     * Moves the readout between the floating top dock and the chrome header row.
     *
     * <p>Top and Bottom float the readout over the lyrics, so lines run underneath it. In Header
     * mode the same art and title/artist take the chrome header's flexible slot instead: it sits
     * in the row's own layout, reveals and fades with the rest of the chrome, and never covers a
     * lyric line.
     */
    private void applyHeaderPlacement(boolean header) {
        if (topRow == null) return;
        ViewGroup target = header ? headerRow : topBox;
        if (target == null || topRow.getParent() == target) return;
        ViewGroup current = (ViewGroup) topRow.getParent();
        if (current != null) current.removeView(topRow);
        if (header) {
            int index = headerTitle == null ? -1 : headerRow.indexOfChild(headerTitle);
            topRow.setPadding(0, 0, dp(8), 0);
            headerRow.addView(topRow, index >= 0 ? index : headerRow.getChildCount(),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            topBox.addView(topRow, topRowLp);
            layoutTopRow();
        }
        if (headerTitle != null) headerTitle.setVisibility(header ? View.GONE : View.VISIBLE);
    }

    /** Re-reads settings (call at mount and from the preference listener). */
    void onPreferenceChanged() {
        applyBackgroundStyle();
        applyArtRadius();
        applyTextAlign();
        applyFieldVisibility();
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
        boolean header = !twoColumn && !side && "Header".equals(mode);
        boolean top = !twoColumn && !side && !header && "Top".equals(mode);
        boolean bottom = !twoColumn && !side && !header && "Bottom".equals(mode);
        boolean enabled = top || bottom || side || header;
        applyHeaderPlacement(header);
        boolean wasEnabled = artworkEnabled;
        boolean firstMount = lastMode == null;
        boolean modeChanged = firstMount || !mode.equals(lastMode);
        lastMode = mode;
        artworkEnabled = enabled;
        if (modeChanged) {
            if (firstMount) {
                // Snap on the very first apply - nothing to transition from yet.
                topBox.setVisibility(top ? View.VISIBLE : View.GONE);
                bottomBox.setVisibility(bottom ? View.VISIBLE : View.GONE);
                sideBox.setVisibility(side ? View.VISIBLE : View.GONE);
            } else {
                animateBoxVisibility(topBox, top);
                animateBoxVisibility(bottomBox, bottom);
                animateBoxVisibility(sideBox, side);
            }
        }
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

    /** Crossfades a readout box in or out on a genuine position-mode change, instead of the flat
     *  VISIBLE/GONE cut that made switching Top/Bottom/Header/Off feel like a jump cut. */
    private static void animateBoxVisibility(View box, boolean show) {
        if (box == null) return;
        box.animate().cancel();
        if (show) {
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            box.animate().alpha(1f).setDuration(220L).start();
        } else if (box.getVisibility() == View.VISIBLE) {
            box.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
                box.setVisibility(View.GONE);
                box.setAlpha(1f);
            }).start();
        } else {
            box.setVisibility(View.GONE);
        }
    }

    /**
     * Top readout owns the top-left corner (where Back used to be): art starts at the
     * top inset with no chrome gap, text reserves the right control rail. Called at
     * mount, mode change, and art-size change only — never per frame.
     */
    private void layoutTopRow() {
        if (topRow == null || topRowLp == null) return;
        // In "Header" mode the row is a child of the chrome LinearLayout and is laid out by it.
        if (topRow.getParent() != topBox) return;
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

    private int currentCustomArtSizeDp() {
        try {
            return config.get(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP);
        } catch (Throwable ignored) {
            return Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.defaultValue;
        }
    }

    private void applyArtSize() {
        int[] sizes = readoutArtSizes(currentArtSize(), currentCustomArtSizeDp());
        bottomArtDpF = sizes[0];
        // The compact fixed landscape top size only makes sense for the preset sizes - a
        // "Custom" size is a direct-manipulation drag result (see readoutArtSizes' own javadoc);
        // silently overriding it back to 54dp in landscape made the layout editor's resize
        // handle visibly do nothing for Top-position artwork whenever the device was rotated.
        topArtDpF = (landscape && !"Custom".equals(currentArtSize())) ? ART_TOP_LANDSCAPE_DP : sizes[1];
        setSquareLp(bottomArtFrame, dp(bottomArtDpF));
        setSquareLp(topArtFrame, dp(topArtDpF));
        ViewGroup.LayoutParams rowLp = bottomRow.getLayoutParams();
        if (rowLp != null) {
            rowLp.height = dp(bottomArtDpF);
            bottomRow.setLayoutParams(rowLp);
        }
        boolean bottom = bottomBox.getVisibility() == View.VISIBLE;
        int rowTopDp = (landscape ? BOTTOM_ART_INSET_LANDSCAPE_DP : BOTTOM_ART_INSET_PORTRAIT_DP)
                + bottomArtDpF;
        int jumpMarginDp = bottom ? rowTopDp + CLUSTER_GAP_DP : 24;
        // A solid dock is opaque over its whole height (the gradient band included), and the
        // chips are drawn in the same plane: sitting just above the artwork row put them inside
        // that fill, hidden behind it. Keep them above the dock's top edge instead.
        if (bottom && "Solid".equals(config.get(Settings.TRACK_INFO_BACKGROUND))) {
            jumpMarginDp = Math.max(rowTopDp, EDGE_GRADIENT_DP) + CLUSTER_GAP_DP;
        }
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
        boolean adaptive = false;
        try {
            adaptive = config.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE);
        } catch (Throwable ignored) {
        }
        float titleSp;
        float artistSp;
        float albumSp;
        if (adaptive) {
            // Scales off the same bottom-art dp readoutArtSizes() would resolve to right now -
            // independent of applyArtSize()'s own bottomArtDpF field, which setMode() hasn't
            // refreshed yet this pass (applyTextSize() runs before applyArtSize() there). 96dp is
            // the "Normal" preset's bottom size, so scale is 1.0 at the readout's original size.
            int[] sizes = readoutArtSizes(currentArtSize(), currentCustomArtSizeDp());
            float scale = Math.max(0.5f, Math.min(2f, sizes[0] / 96f));
            titleSp = 15f * scale;
            artistSp = 12f * scale;
            albumSp = 11f * scale;
        } else {
            String value = "Normal";
            try {
                value = config.get(Settings.TRACK_INFO_TEXT_SIZE);
            } catch (Throwable ignored) {
            }
            titleSp = 15f;
            artistSp = 12f;
            albumSp = 11f;
            if ("Small".equals(value)) {
                titleSp = 13f;
                artistSp = 11f;
                albumSp = 10f;
            } else if ("Large".equals(value)) {
                titleSp = 18f;
                artistSp = 14f;
                albumSp = 12f;
            } else if ("XLarge".equals(value)) {
                titleSp = 22f;
                artistSp = 16f;
                albumSp = 14f;
            } else if ("Custom".equals(value)) {
                int multiplierX100 = 100;
                try {
                    multiplierX100 = config.get(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM);
                } catch (Throwable ignored) {
                }
                float scale = Math.max(50, Math.min(400, multiplierX100)) / 100f;
                titleSp = 15f * scale;
                artistSp = 12f * scale;
                albumSp = 11f * scale;
            }
        }
        if (topTitle != null) topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        if (bottomTitle != null) bottomTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        if (sideTitle != null) sideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        if (topArtist != null) topArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
        if (bottomArtist != null) bottomArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
        if (sideArtist != null) sideArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
        if (topAlbum != null) topAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, albumSp);
        if (bottomAlbum != null) bottomAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, albumSp);
        if (sideAlbum != null) sideAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, albumSp);
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
        String album = track == null ? "" : emptyFallback(track.album);
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
        if (!album.equals(lastAlbum)) {
            lastAlbum = album;
            if (topAlbum != null) topAlbum.setText(album);
            if (bottomAlbum != null) bottomAlbum.setText(album);
            if (sideAlbum != null) sideAlbum.setText(album);
        }
        updateContentDescriptions();
    }

    void onPlayingChanged(boolean playing) {
        if (playing == lastPlaying) return;
        lastPlaying = playing;
        applyOverlayIcon();
        updateContentDescriptions();
    }

    /** Layout-editor Demo toggle only: paints a synthetic track directly into every enabled
     *  placement. Reuses {@link #onTrackChanged} for the text/state bookkeeping, then overwrites
     *  the artwork {@link #onTrackChanged} would have tried to find in the real
     *  {@link SpotifyArtworkCache} (which a demo track can never have an entry in) with the
     *  given bitmap instead. */
    void showDemoTrack(SpotifyTrack track, Bitmap art) {
        onTrackChanged(track);
        Bitmap rounded = art == null ? null : roundBitmap(art, dp(bottomArtDpF), dp(artRadiusDp));
        Bitmap sideRounded = art == null ? null : roundBitmap(art, dp(sideArtDp), dp(artRadiusDp));
        if (currentArtwork != null) {
            try {
                currentArtwork.recycle();
            } catch (Throwable ignored) {
            }
        }
        if (sideArtwork != null) {
            try {
                sideArtwork.recycle();
            } catch (Throwable ignored) {
            }
        }
        currentArtwork = rounded;
        sideArtwork = sideRounded;
        fadeInArt(topArt, rounded);
        fadeInArt(bottomArt, rounded);
        fadeInArt(sideArt, sideRounded);
        artMissing = art == null;
    }

    /** Restores the "no track" appearance after the Demo toggle turns off; the next real
     *  {@link #onTrackChanged} call (resumed per-frame updates) repaints everything normally. */
    void clearDemoArt() {
        lastTrack = null;
        lastUri = "";
        lastTitle = "Waiting for Spotify track…";
        lastArtist = "";
        lastAlbum = "";
        topTitle.setText(lastTitle);
        bottomTitle.setText(lastTitle);
        sideTitle.setText(lastTitle);
        topArtist.setText("");
        bottomArtist.setText("");
        sideArtist.setText("");
        if (topAlbum != null) topAlbum.setText("");
        if (bottomAlbum != null) bottomAlbum.setText("");
        if (sideAlbum != null) sideAlbum.setText("");
        clearArtwork();
        artMissing = true;
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
                boolean fromNetworkCache = false;
                if (raw == null && track.imageId != null && !track.imageId.isEmpty()) {
                    raw = ART_NETWORK_CACHE.get(track.imageId);
                    fromNetworkCache = raw != null;
                    if (raw == null) fetchArtworkFromNetwork(track.imageId);
                }
                if (currentArtwork != null) {
                    try {
                        currentArtwork.recycle();
                    } catch (Throwable ignored) {
                    }
                    currentArtwork = null;
                }
                if (raw != null) {
                    int sizePx = dp(bottomArtDpF);
                    rounded = roundBitmap(raw, sizePx, dp(artRadiusDp));
                    if (!fromNetworkCache) raw.recycle();
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
                boolean fromNetworkCache = false;
                if (large == null && track.imageId != null && !track.imageId.isEmpty()) {
                    large = ART_NETWORK_CACHE.get(track.imageId);
                    fromNetworkCache = large != null;
                    if (large == null) fetchArtworkFromNetwork(track.imageId);
                }
                if (sideArtwork != null) {
                    try {
                        sideArtwork.recycle();
                    } catch (Throwable ignored) {
                    }
                    sideArtwork = null;
                }
                if (large != null) {
                    int targetPx = fromNetworkCache ? dp(sideArtDp) : large.getWidth();
                    sideRounded = roundBitmap(large, targetPx, dp(artRadiusDp));
                    if (!fromNetworkCache) large.recycle();
                } else {
                    sideRounded = null;
                }
            } catch (Throwable ignored) {
                sideRounded = null;
            }
        }
        currentArtwork = rounded;
        sideArtwork = sideRounded;
        // Detach views before publishing: a recycled bitmap must never stay referenced. The old
        // bitmap is already recycled above, so it can't safely fade out too - only the new one
        // fades in, which is enough to soften the pop between covers on a track change.
        topArt.setImageBitmap(null);
        bottomArt.setImageBitmap(null);
        sideArt.setImageBitmap(null);
        if (needSmall) {
            fadeInArt(topArt, rounded);
            fadeInArt(bottomArt, rounded);
        }
        if (needSide) {
            fadeInArt(sideArt, sideRounded);
        }
        artMissing = (needSmall && rounded == null) || (needSide && sideRounded == null);
    }

    /** Fetches art straight from Spotify's public image CDN by id - the fallback for remote
     *  (Spotify Connect) playback, where SpotifyArtworkCache has nothing to serve. Dedupes
     *  concurrent requests per imageId; the existing artMissing retry loop (attemptArtwork, above)
     *  re-checks ART_NETWORK_CACHE on its own cadence, so a successful fetch just needs to land in
     *  the cache - no callback-driven re-render required here. */
    static void fetchArtworkFromNetwork(String imageId) {
        if (imageId == null || imageId.isEmpty()) return;
        if (ART_NETWORK_CACHE.containsKey(imageId)) return;
        if (!ART_NETWORK_FETCH_IN_FLIGHT.add(imageId)) return;
        
        String url = imageId.startsWith("http") ? imageId : "https://i.scdn.co/image/" + imageId;
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        NativeRuntime.HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ART_NETWORK_FETCH_IN_FLIGHT.remove(imageId);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        byte[] bytes = response.body().bytes();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bitmap != null) {
                            ART_NETWORK_CACHE.put(imageId, bitmap);
                            
                            // Notify all registered listening components on the Main/UI thread
                            // to immediately fetch the updated bitmap and refresh their canvas.
                            synchronized (ART_NETWORK_LISTENERS) {
                                for (Runnable listener : ART_NETWORK_LISTENERS) {
                                    if (listener != null) {
                                        listener.run();
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    ART_NETWORK_FETCH_IN_FLIGHT.remove(imageId);
                }
            }
        });
    }

    /** Release network artwork cache entries and trim memory. Called by the host
     *  on trim-memory events and when the readout is torn down. */
    static void trimMemory() {
        synchronized (ART_NETWORK_CACHE) {
            for (java.util.Map.Entry<String, Bitmap> entry : ART_NETWORK_CACHE.entrySet()) {
                try { if (entry.getValue() != null) entry.getValue().recycle(); } catch (Throwable ignored) {}
            }
            ART_NETWORK_CACHE.clear();
        }
        ART_NETWORK_FETCH_IN_FLIGHT.clear();
        com.eza.spicyex.lyrics.GlowFlexbox.clearBlurCache();
    }

    private static void fadeInArt(ImageView view, Bitmap bitmap) {
        view.animate().cancel();
        view.setImageBitmap(bitmap);
        if (bitmap == null) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180).start();
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
        // In Header mode topArtFrame lives inside headerRow, not topBox (which is GONE there,
        // genuinely empty) - testing topBox's visibility would always fail and make every touch
        // look "outside," triggering onOutsideDown()/resetVisuals() on every ACTION_DOWN even
        // directly over the visible artwork (see NativeSpicyShellViewImpl#dispatchTouchEvent).
        View topContainer = "Header".equals(lastMode) ? headerRow : topBox;
        return hitsFrame(topContainer, topArtFrame, rawX, rawY)
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
