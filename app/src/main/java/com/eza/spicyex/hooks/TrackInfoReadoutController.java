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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
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
import com.eza.spicyex.lyrics.cache.SpotifyArtworkCache;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.Motion;

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
 * <p>The adaptive two-column landscape layout gets a fourth placement: the same art, text,
 * gestures and overlay as a vertical column, which the shell hosts in its own left column beside
 * the lyrics (see {@link #columnView()}) rather than in the overlay. Every stored position except
 * Off shows it there; the floating placements stand down while it is on screen.
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

    /** Two-column base text sizes (title/artist/album) before the Track text size setting's
     *  own scaling - the column block reads bigger than a readout row, it has a whole column. */
    private static final int COLUMN_TITLE_SP = 20;
    private static final int COLUMN_ARTIST_SP = 15;
    private static final int COLUMN_ALBUM_SP = 14;
    /** A shrunk cover keeps at least this much width for the song info below it. */
    private static final int COLUMN_MIN_BLOCK_DP = 200;

    /** Two-column cover side: the fitted column size, capped by a Custom art size when set. The
     *  presets are readout sizes, far smaller than a column, so they keep the fitted cover. */
    static int columnCoverSidePx(int fitPx, String artSize, int customPx) {
        if (!"Custom".equals(artSize) || customPx <= 0) return fitPx;
        return Math.min(fitPx, customPx);
    }

    /** Width the cover and the song info share: the cover's own width, but never narrower than
     *  {@code minBlockPx} (or the fitted side, when that is smaller), so a shrunk cover does not
     *  squeeze the title to a few letters. The cover stays flush left inside it. */
    static int columnBlockWidthPx(int sidePx, int fitPx, int minBlockPx) {
        return Math.max(sidePx, Math.min(Math.max(fitPx, sidePx), minBlockPx));
    }

    /** Track text size scale for the two-column block, mirroring this class's own size modes
     *  ({@link #applyTextSize}) as one ratio off "Normal". The readout's presets are a per-field
     *  table (13/11/10 ... 22/16/14), not a multiplier, so only its title column scales
     *  proportionally - that is what this reuses, over the block's own
     *  {@link #COLUMN_TITLE_SP}/{@link #COLUMN_ARTIST_SP}/{@link #COLUMN_ALBUM_SP} bases.
     *  Pure, unit-tested. */
    static float columnTrackTextScale(String size, int customPercent, boolean adaptive,
            float adaptiveArtDp) {
        if (adaptive) {
            // Same clamp applyTextSize() uses when it scales text off the art size; 96dp is its
            // "Normal" preset, so the scale is 1.0 at the original art size.
            return Math.max(0.5f, Math.min(2f, adaptiveArtDp / 96f));
        }
        if ("Small".equals(size)) return 13f / 15f;
        if ("Large".equals(size)) return 18f / 15f;
        if ("XLarge".equals(size)) return 22f / 15f;
        if ("Custom".equals(size)) return Math.max(50, Math.min(400, customPercent)) / 100f;
        return 1f;
    }

    /** Side dock engages on wide landscape only, and never when the readout is Off. */
    static boolean sideModeEngaged(boolean landscape, float aspect, String mode) {
        if (!landscape || aspect < SIDE_ASPECT_MIN) return false;
        return "Top".equals(mode) || "Bottom".equals(mode);
    }

    /** Largest Custom art the readout placements show. The stored setting goes higher for the
     *  two-column cover, which has a whole column to grow into. */
    static final int READOUT_MAX_ART_DP = 160;

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
            int size = Math.max(24, Math.min(READOUT_MAX_ART_DP, customBottomDp));
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
    /** Room to keep free beside the top controls; null keeps the rail default. */
    private java.util.function.IntSupplier chromeReserve;
    private final Runnable onRevealChrome;
    private final boolean landscape;
    /** When true this screen is the adaptive two-column layout: the column placement below owns
     * the landscape art and every floating overlay stands down. */
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
    private boolean pipPresentation;
    /** Album-art choice for this PiP presentation; false keeps every shape lyrics-only. */
    private boolean pipAlbumArt;
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
    /** The two-column placement. Built only when {@link #twoColumn}, and hosted by the shell
     *  inside its own left column instead of the overlay (see {@link #columnView()}), so every
     *  column field below is null on every other layout. */
    private LinearLayout columnBox;
    private ArtTouchFrame columnArtFrame;
    private ImageView columnArt;
    private View columnOverlayScrim;
    private ImageButton columnOverlayButton;
    private LinearLayout columnText;
    private TextView columnTitle;
    private TextView columnArtist;
    private TextView columnAlbum;
    private Bitmap columnArtwork;
    /** Cover side in dp: what the column fits, capped by a Custom art size. Drives the artwork
     *  snapshot; seeded from a width estimate so the first track has a size before the first
     *  measure, then written by the cover's own onMeasure. */
    private int columnArtDpF = 1;
    /** Cover side fixed by the column box's first measure pass for its centred second pass;
     *  -1 outside it. Keeps a shrink from ratcheting on every re-layout. */
    private int columnArtLockedSide = -1;
    /** Largest cover the column fits, before any Custom art size caps it; -1 before a measure. */
    private int columnArtFitSide = -1;
    /** Art size and song-info layout the cover's measure reads, to relayout only on a change. */
    private String appliedColumnLayout = "";

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
    private static final java.util.concurrent.ScheduledThreadPoolExecutor ART_WORKER =
            new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    static { ART_WORKER.setRemoveOnCancelPolicy(true); }
    private final android.os.Handler artHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.concurrent.Future<?> artworkTask;
    private int artworkGeneration;
    private boolean artworkPending;
    private final java.util.Map<ImageView, Runnable> artTransitionEnds = new java.util.HashMap<>();
    private String displayedImageId = "";
    private Bitmap currentArtwork;
    private Bitmap sideArtwork;
    private int textAnimSeq;
    private ArtTouchFrame followThroughFrame;
    private int followThroughTargetDp;
    private final Runnable followThroughTimeoutRunnable = this::onFollowThroughTimeout;
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
        // Column placement: the adaptive two-column left column's art and song info. Same art
        // surface, gestures, overlay, radius and Track text settings as the other placements -
        // only the arrangement differs (a column, not a floating plane), so the shell hosts this
        // box inside its own left column instead of the overlay. It is built last, once the
        // controller exists, because the cover's measure and the column's centring both read
        // settings off it.
        if (twoColumn) {
            // The shell's left column takes 0.8 of the width against the lyrics column's 1.15;
            // that ratio is where this size estimate comes from, so the first cover (fetched
            // before the column has ever been measured) is close to the real one.
            controller.columnArtDpF =
                    Math.max(1, Math.round(metrics.widthPixels * 0.8f / 1.95f / density));
            LinearLayout columnText = new LinearLayout(activity);
            columnText.setOrientation(LinearLayout.VERTICAL);
            columnText.setGravity(Gravity.START);
            columnText.setClipChildren(false);
            columnText.setClipToPadding(false);
            TextView columnTitle = textFactory.createText(activity, "Waiting for Spotify track…",
                    COLUMN_TITLE_SP, Color.WHITE, textFactory.resolveTypeface(true));
            columnTitle.setGravity(Gravity.START);
            columnTitle.setAlpha(0.92f);
            setupMarquee(columnTitle);
            // Seeded with the same no-track lines the panel showed before its first track: the
            // shell's per-frame update stands down without one, so nothing else writes these.
            TextView columnArtist = textFactory.createText(activity,
                    "Player state hook has not emitted yet", COLUMN_ARTIST_SP,
                    Color.rgb(190, 190, 190), textFactory.resolveTypeface(false));
            columnArtist.setGravity(Gravity.START);
            columnArtist.setAlpha(0.72f);
            columnArtist.setMaxLines(1);
            columnArtist.setEllipsize(TextUtils.TruncateAt.END);
            TextView columnAlbum = textFactory.createText(activity, "", COLUMN_ALBUM_SP,
                    Color.rgb(150, 150, 150), textFactory.resolveTypeface(false));
            columnAlbum.setGravity(Gravity.START);
            columnAlbum.setAlpha(0.6f);
            columnAlbum.setMaxLines(1);
            columnAlbum.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams columnTitleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Panel song info sits flush against the art above it.
            columnTitleLp.topMargin = dp(8);
            columnText.addView(columnTitle, columnTitleLp);
            columnText.addView(columnArtist, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            columnText.addView(columnAlbum, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ArtTouchFrame columnArtFrame = new ArtTouchFrame(activity, () -> {
                if (holder[0] != null) holder[0].toggleFromAccessibility();
            }) {
                /** Square cover at full column width, shrinking so the song info below always has
                 *  measured room; a Custom art size caps it (columnCoverSidePx). The first
                 *  measure is the real fit, the second (centred) pass reuses its side. */
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    TrackInfoReadoutController owner = holder[0];
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    int side = width;
                    if (owner != null && owner.columnArtLockedSide >= 0) {
                        side = Math.min(width, owner.columnArtLockedSide);
                    } else if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                        int reserve = dp(8);
                        for (TextView tv : new TextView[]{columnTitle, columnArtist, columnAlbum}) {
                            if (tv.getVisibility() != View.GONE) {
                                tv.measure(widthMeasureSpec,
                                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                                reserve += tv.getMeasuredHeight();
                            }
                        }
                        side = Math.min(width, Math.max(0,
                                MeasureSpec.getSize(heightMeasureSpec) - reserve));
                        if (owner != null) {
                            owner.columnArtFitSide = side;
                            side = columnCoverSidePx(side, owner.columnArtSize(),
                                    dp(owner.currentCustomArtSizeDp()));
                            // The bitmap the next cover is snapshotted at follows the real fit.
                            owner.columnArtDpF = Math.max(1, Math.round(side / density));
                        }
                    }
                    int squareSpec = MeasureSpec.makeMeasureSpec(Math.max(0, side),
                            MeasureSpec.EXACTLY);
                    super.onMeasure(squareSpec, squareSpec);
                }
            };
            columnArtFrame.setClipChildren(false);
            ImageView columnArt = new ImageView(activity);
            columnArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // GONE until a cover lands: the column has no placeholder square behind a missing
            // cover, so the panel's empty state is the bare column, as it always was.
            columnArt.setVisibility(View.GONE);
            styleArt(columnArt, 0);
            columnArtFrame.addView(columnArt, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View columnScrim = roundedScrim(activity, 0);
            columnArtFrame.addView(columnScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageButton columnOverlay = overlayButton(activity);
            columnOverlay.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].onOverlayButtonClicked(columnArtFrame);
            });
            columnArtFrame.addView(columnOverlay, overlayButtonLp());
            final ArtTouchFrame columnFrame = columnArtFrame;
            LinearLayout columnBox = new LinearLayout(activity) {
                /** Centres the art-and-info block in the column: once the cover is sized by the
                 *  height rather than the width, the spare width is split evenly on both sides
                 *  (it all used to pile up on one side, leaving the cover hugging the edge), and
                 *  the text below spans exactly the cover's width. */
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    TrackInfoReadoutController owner = holder[0];
                    if (owner == null) {
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                        return;
                    }
                    // The cover is sized from the full column width every time. Measuring it with
                    // the previous pass's centring padding in place measured the song info
                    // narrower, it wrapped to more lines, the cover came out smaller, the padding
                    // grew - and every re-layout (a tap on the cover is one) shrank it again.
                    int content = View.MeasureSpec.getSize(widthMeasureSpec);
                    int heightMode = View.MeasureSpec.getMode(heightMeasureSpec);
                    int available = Math.max(0, View.MeasureSpec.getSize(heightMeasureSpec)
                            - getPaddingTop() - getPaddingBottom());
                    owner.columnArtLockedSide = -1;
                    columnFrame.measure(
                            View.MeasureSpec.makeMeasureSpec(content, View.MeasureSpec.EXACTLY),
                            heightMode == View.MeasureSpec.UNSPECIFIED ? heightMeasureSpec
                                    : View.MeasureSpec.makeMeasureSpec(available,
                                            View.MeasureSpec.AT_MOST));
                    int side = columnFrame.getMeasuredWidth();
                    int block = columnBlockWidthPx(side, owner.columnArtFitSide,
                            dp(COLUMN_MIN_BLOCK_DP));
                    int inset = side > 0 ? Math.max(0, (content - block) / 2) : 0;
                    if (inset != getPaddingLeft() || inset != getPaddingRight()) {
                        setPadding(inset, getPaddingTop(), inset, getPaddingBottom());
                    }
                    owner.columnArtLockedSide = side;
                    try {
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    } finally {
                        owner.columnArtLockedSide = -1;
                    }
                }
            };
            // START keeps the cover's left edge flush with the song-info text below it;
            // CENTER_VERTICAL centers the fitted stack in the column.
            columnBox.setOrientation(LinearLayout.VERTICAL);
            columnBox.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            columnBox.setVisibility(View.GONE);
            columnBox.setClipChildren(false);
            columnBox.setClipToPadding(false);
            LinearLayout.LayoutParams columnArtLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            columnArtLp.gravity = Gravity.START;
            columnBox.addView(columnArtFrame, columnArtLp);
            columnBox.addView(columnText, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            controller.columnBox = columnBox;
            controller.columnArtFrame = columnArtFrame;
            controller.columnArt = columnArt;
            controller.columnOverlayScrim = columnScrim;
            controller.columnOverlayButton = columnOverlay;
            controller.columnText = columnText;
            controller.columnTitle = columnTitle;
            controller.columnArtist = columnArtist;
            controller.columnAlbum = columnAlbum;
        }
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
        if (controller.columnArtFrame != null) {
            ArtTouchFrame columnFrame = controller.columnArtFrame;
            controller.installAccessibility(columnFrame);
            // The cover's live width, not a stored setting: the column sizes it, not the user.
            controller.installTouch(columnFrame,
                    () -> Math.max(1, Math.round(columnFrame.getWidth() / density)));
        }
        controller.setMode(controller.currentMode());
        return controller;
    }

    // -- mode ---------------------------------------------------------------

    /** The two-column placement's box, for the shell to add to its left column; null on every
     *  layout that is not the adaptive two-column one. Not a child of the overlay, so the shell
     *  owns where in its own hierarchy this lives. */
    View columnView() {
        return columnBox;
    }

    /** Largest cover the column fits, in px; -1 before its first measure. The layout editor's
     *  resize handle reads this as the ceiling for a Custom cover. */
    int columnFitSidePx() {
        return columnArtFitSide;
    }

    /** The art frame actually visible on screen right now, or null when the readout is Off.
     *  Header mode re-parents topRow (and topArtFrame within it) into the chrome header row -
     *  topBox itself goes GONE there, so that case is checked by mode rather than box
     *  visibility. Used by the layout editor to anchor its selection overlay on the real view. */
    View currentArtFrame() {
        if (columnBox != null && columnBox.getVisibility() == View.VISIBLE) return columnArtFrame;
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
        if (columnBox != null && columnBox.getVisibility() == View.VISIBLE) return columnText;
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
        boolean showTitle = !pipPresentation && readBool(Settings.TRACK_INFO_SHOW_TITLE);
        boolean showArtist = !pipPresentation && readBool(Settings.TRACK_INFO_SHOW_ARTIST);
        boolean showAlbum = !pipPresentation && readBool(Settings.TRACK_INFO_SHOW_ALBUM);
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
        if (columnTitle != null) columnTitle.setVisibility(titleVis);
        if (columnArtist != null) columnArtist.setVisibility(artistVis);
        if (columnAlbum != null) columnAlbum.setVisibility(albumVis);
        // The cover's measure reserves this block's height, so a field change has to re-run it.
        remeasureColumn();
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
        setCornerRadiusDp(columnArt, radius);
        setCornerRadiusDp(topOverlayScrim, radius);
        setCornerRadiusDp(bottomOverlayScrim, radius);
        setCornerRadiusDp(sideOverlayScrim, radius);
        setCornerRadiusDp(columnOverlayScrim, radius);
        if (lastTrack != null) attemptArtwork(lastTrack);
    }

    private static void setCornerRadiusDp(View view, int radiusDp) {
        if (view == null) return;
        Drawable bg = view.getBackground();
        if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setCornerRadius(dp(radiusDp));
            view.invalidateOutline(); // the art views clip to it
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

    /** PiP is lyrics-only by default. The optional artwork readout never includes metadata. */
    void setPipPresentation() {
        setPipPresentation(pipAlbumArtEnabled());
    }

    /** PiP presentation with an explicit album-art choice; the stored flag is read when the
     *  no-arg overload above is used. Follows the same lyrics-only default. */
    void setPipPresentation(boolean albumArt) {
        pipPresentation = true;
        pipAlbumArt = albumArt;
        applyFieldVisibility();
        setMode("Off");
    }

    /** Whether the PiP art column may show: the two-column layout plus the album-art toggle.
     *  Pure, unit-tested. Off is lyrics-only with no metadata and no blank column; on restores
     *  the landscape artwork column. Portrait uses the existing top artwork readout. */
    static boolean pipColumnAllowed(boolean twoColumn, boolean albumArt) {
        return twoColumn && albumArt;
    }

    /** PiP artwork uses the column or top readout according to its window shape. */
    static String pipEffectiveMode(boolean twoColumn, boolean albumArt) {
        return albumArt ? "Top" : "Off";
    }

    /** Live read of {@link Settings#PIP_ALBUM_ART}; false when unreadable, matching its default. */
    private boolean pipAlbumArtEnabled() {
        try {
            return Boolean.TRUE.equals(config.get(Settings.PIP_ALBUM_ART));
        } catch (Throwable ignored) {
            return Settings.PIP_ALBUM_ART.defaultValue;
        }
    }

    /** Re-reads settings (call at mount and from the preference listener). */
    /** How much room the Top readout leaves beside the top controls; the shell knows whether
     *  they stand as a rail or a row. */
    void setChromeReserve(java.util.function.IntSupplier reserve) {
        chromeReserve = reserve;
        layoutTopRow();
    }

    /** The controls' footprint changed (direction, or a button came or went). */
    void onChromeReserveChanged() {
        layoutTopRow();
    }

    void onPreferenceChanged() {
        applyBackgroundStyle();
        applyArtRadius();
        applyTextAlign();
        applyFieldVisibility();
        panelMediaMode = readPanelMediaMode(config);
        if (!PanelMediaMode.gesturesEnabled(panelMediaMode)) hideOverlays();
        if (pipPresentation) pipAlbumArt = pipAlbumArtEnabled();
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
        if (pipPresentation) mode = pipEffectiveMode(twoColumn, pipAlbumArt);
        if (mode == null) mode = "Off";
        // Two-column's left column is this readout's column placement: it shows for every stored
        // position except Off, and hiding it collapses that column (the lyrics take the width).
        // Every floating overlay stands down while two-column is engaged.
        boolean column = twoColumn && !"Off".equals(mode);
        boolean side = !twoColumn && sideModeEngaged(landscape, aspect, mode);
        boolean header = !twoColumn && !side && "Header".equals(mode);
        boolean top = !twoColumn && !side && !header && "Top".equals(mode);
        boolean bottom = !twoColumn && !side && !header && "Bottom".equals(mode);
        boolean enabled = top || bottom || side || header || column;
        applyHeaderPlacement(header);
        boolean wasEnabled = artworkEnabled;
        boolean firstMount = lastMode == null;
        boolean modeChanged = firstMount || !mode.equals(lastMode);
        lastMode = mode;
        artworkEnabled = enabled;
        // A plain cut, not a crossfade: showing the column reflows the whole screen (the cover is
        // re-fitted and centred), so a fade would only paper over the jump.
        if (columnBox != null) {
            int visibility = column ? View.VISIBLE : View.GONE;
            columnBox.setVisibility(visibility);
            // The shell's left column holds only this box and keeps its layout weight while
            // visible, so hide it too: Off gives the lyrics the whole width instead of a blank strip.
            if (columnBox.getParent() instanceof View) {
                ((View) columnBox.getParent()).setVisibility(visibility);
            }
        }
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
            cancelArtworkRequest();
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
        // Keep the artwork/readout clear of whichever edge owns the vertical top-control rail.
        // This is preference-driven, not orientation detection: portrait and landscape already
        // store independent CHROME_CLUSTER_POSITION values.
        boolean controlsLeft = false;
        try {
            controlsLeft = "Left".equals(config.get(Settings.CHROME_CLUSTER_POSITION));
        } catch (Throwable ignored) {
        }
        int railClearance = chromeReserve != null ? chromeReserve.getAsInt() : dp(44 + 8);
        topRow.setPadding(sidePad + (controlsLeft ? railClearance : 0), dp(8),
                sidePad + (controlsLeft ? 0 : railClearance), 0);
        if (topGradientView != null) {
            ViewGroup.LayoutParams glp = topGradientView.getLayoutParams();
            int wantH = topInsetPx + dp(topArtDpF + 64);
            if (glp != null && glp.height != wantH) {
                glp.height = wantH;
                topGradientView.setLayoutParams(glp);
            }
        }
    }

    /** Clears every readout image and drops references (views first, then drop). */
    private void clearArtwork() {
        cancelArtworkRequest();
        for (java.util.Map.Entry<ImageView, Runnable> end : artTransitionEnds.entrySet()) {
            end.getKey().removeCallbacks(end.getValue());
        }
        artTransitionEnds.clear();
        displayedImageId = "";
        topArt.setImageDrawable(null);
        bottomArt.setImageDrawable(null);
        sideArt.setImageDrawable(null);
        if (columnArt != null) {
            columnArt.setImageDrawable(null);
            // The column draws no placeholder, so a cleared cover leaves no square behind.
            columnArt.setVisibility(View.GONE);
        }
        currentArtwork = null;
        sideArtwork = null;
        columnArtwork = null;
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

    /** The art size that caps the two-column cover: only one chosen in landscape. A portrait
     *  Custom size (a small readout cover) read through to landscape and shrank the column cover
     *  to it; the column is a different placement, so it keeps its fitted size until the art
     *  size is changed in landscape itself. */
    private String columnArtSize() {
        try {
            return config.hasOwnLandscapeValue(Settings.TRACK_INFO_ART_SIZE)
                    || config.hasOwnLandscapeValue(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP)
                    ? currentArtSize() : Settings.TRACK_INFO_ART_SIZE.defaultValue;
        } catch (Throwable ignored) {
            return Settings.TRACK_INFO_ART_SIZE.defaultValue;
        }
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
        // In two-column there is no bottom dock to clear, so the chip margins above are already
        // at their non-dock value and unchanged; the art size only re-measures the cover.
        remeasureColumn();
    }

    /** Re-measures the column cover when the art size or the song info changed - both are inputs
     *  to its own measure (see the cover's onMeasure), and the column measures it with unchanged
     *  specs, so without its own layout request Android returns the cached size. */
    private void remeasureColumn() {
        if (columnArtFrame == null) return;
        String state = currentArtSize() + ":" + currentCustomArtSizeDp() + ":" + currentOverflow()
                + visibilityKey(columnTitle) + visibilityKey(columnArtist) + visibilityKey(columnAlbum)
                + textSizeKey(columnTitle) + textSizeKey(columnArtist) + textSizeKey(columnAlbum);
        if (state.equals(appliedColumnLayout)) return;
        appliedColumnLayout = state;
        columnArtFrame.requestLayout();
    }

    private static String visibilityKey(View view) {
        return view == null ? "-" : String.valueOf(view.getVisibility());
    }

    private static String textSizeKey(TextView view) {
        return view == null ? "-" : String.valueOf(view.getTextSize());
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
        if (columnTitle != null) {
            // The column block is its own size scale off its own bases, not the readout's table:
            // it has a whole column, so the same setting has to start from bigger text.
            float scale = currentColumnTrackTextScale();
            columnTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, COLUMN_TITLE_SP * scale);
            columnArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, COLUMN_ARTIST_SP * scale);
            columnAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, COLUMN_ALBUM_SP * scale);
        }
    }

    /** The column block's own Track text size scale. Adaptive reads the art size exactly as the
     *  readout's own adaptive mode does; a Custom cover size is the only art-size signal this
     *  block has (the presets leave the cover at whatever the column fits), so it stands in for
     *  everything else and keeps the scale at 1.0. The fitted cover is deliberately not used: it
     *  already depends on this block's measured height, so text sized off it would feed back
     *  into its own measure. */
    private float currentColumnTrackTextScale() {
        boolean adaptive;
        try {
            adaptive = config.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE);
        } catch (Throwable ignored) {
            adaptive = Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE.defaultValue;
        }
        if (adaptive) {
            float artDp = "Custom".equals(columnArtSize())
                    ? Math.min(READOUT_MAX_ART_DP, currentCustomArtSizeDp()) : 96f;
            return columnTrackTextScale(null, 0, true, artDp);
        }
        String size = Settings.TRACK_INFO_TEXT_SIZE.defaultValue;
        try {
            size = config.get(Settings.TRACK_INFO_TEXT_SIZE);
        } catch (Throwable ignored) {
        }
        int customPercent = Settings.TRACK_INFO_TEXT_SIZE_CUSTOM.defaultValue;
        try {
            customPercent = config.get(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM);
        } catch (Throwable ignored) {
        }
        return columnTrackTextScale(size, customPercent, false, 96f);
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

    /** Applies Clip/Wrap/Scroll to every readout title/artist (live, no remount). The column
     *  block's album line follows it too - that block's three texts have always been one unit. */
    private void applyTextOverflow() {
        String mode = currentOverflow();
        applyOverflowMode(topTitle, mode);
        applyOverflowMode(bottomTitle, mode);
        applyOverflowMode(sideTitle, mode);
        applyOverflowMode(topArtist, mode);
        applyOverflowMode(bottomArtist, mode);
        applyOverflowMode(sideArtist, mode);
        applyOverflowMode(columnTitle, mode);
        applyOverflowMode(columnArtist, mode);
        applyOverflowMode(columnAlbum, mode);
    }

    static void applyOverflowMode(TextView view, String mode) {
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
        String imageId = track == null || track.imageId == null ? "" : track.imageId;
        long now = android.os.SystemClock.elapsedRealtime();
        lastTrack = track;
        if (!uri.equals(lastUri)) {
            cancelArtworkRequest();
            lastUri = uri;
            trackChangeMs = now;
            resetVisuals();
            if (artworkEnabled) {
                if (imageId.isEmpty()) {
                    clearArtwork();
                    artMissing = false;
                } else if (imageId.equals(displayedImageId)) {
                    artMissing = false;
                    handleTrackChangeFollowThrough();
                } else {
                    attemptArtwork(track);
                }
            }
        } else if (artworkEnabled && artMissing && now - trackChangeMs < ART_RETRY_WINDOW_MS
                && now - lastArtAttemptMs > ART_RETRY_GAP_MS) {
            attemptArtwork(track);
        } else if (artworkEnabled && artMissing && now - trackChangeMs >= ART_RETRY_WINDOW_MS) {
            // The previous cover stays while the new one loads; once retries give up it would
            // otherwise keep showing the wrong song.
            clearArtwork();
            artMissing = false;
        }
        String title = track == null ? "Waiting for Spotify track…" : emptyFallback(track.title);
        String artist = track == null ? "" : emptyFallback(track.artist);
        String album = track == null ? "" : emptyFallback(track.album);
        updateTexts(title, artist, album);
        updateContentDescriptions();
    }

    private void updateTexts(String title, String artist, String album) {
        if (title.equals(lastTitle) && artist.equals(lastArtist) && album.equals(lastAlbum)) {
            return;
        }
        lastTitle = title;
        lastArtist = artist;
        lastAlbum = album;
        cancelTextAnimations();
        applyTextsDirectly(title, artist, album);
        resetTextAlphas();
    }

    private boolean isTitleView(TextView tv) {
        return tv == topTitle || tv == bottomTitle || tv == sideTitle;
    }

    private void applyTextsDirectly(String title, String artist, String album) {
        topTitle.setText(title);
        bottomTitle.setText(title);
        sideTitle.setText(title);
        topArtist.setText(artist);
        bottomArtist.setText(artist);
        sideArtist.setText(artist);
        if (topAlbum != null) topAlbum.setText(album);
        if (bottomAlbum != null) bottomAlbum.setText(album);
        if (sideAlbum != null) sideAlbum.setText(album);
        if (columnTitle != null) columnTitle.setText(title);
        if (columnArtist != null) columnArtist.setText(artist);
        if (columnAlbum != null) columnAlbum.setText(album);
    }

    private void cancelTextAnimations() {
        textAnimSeq++;
        for (TextView tv : new TextView[]{
                topTitle, topArtist, topAlbum, bottomTitle, bottomArtist, bottomAlbum,
                sideTitle, sideArtist, sideAlbum, columnTitle, columnArtist, columnAlbum
        }) {
            if (tv != null) {
                try {
                    tv.animate().cancel();
                    tv.animate().withEndAction(null);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void resetTextAlphas() {
        // The column block is deliberately absent: its three texts carry authored dimming
        // (title/artist/album at 0.92/0.72/0.6) that a blanket reset would flatten.
        for (TextView tv : new TextView[]{
                topTitle, topArtist, topAlbum, bottomTitle, bottomArtist, bottomAlbum,
                sideTitle, sideArtist, sideAlbum
        }) {
            if (tv != null) {
                tv.setAlpha(1f);
            }
        }
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
        Bitmap rounded = art == null ? null : roundBitmap(art, dp(bottomArtDpF), 0f);
        Bitmap sideRounded = art == null ? null : roundBitmap(art, dp(sideArtDp), 0f);
        Bitmap columnRounded = art == null || columnArt == null ? null
                : roundBitmap(art, dp(columnArtDpF), 0f);
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
        if (columnArtwork != null) {
            try {
                columnArtwork.recycle();
            } catch (Throwable ignored) {
            }
        }
        currentArtwork = rounded;
        sideArtwork = sideRounded;
        columnArtwork = columnRounded;
        fadeInArt(topArt, rounded);
        fadeInArt(bottomArt, rounded);
        fadeInArt(sideArt, sideRounded);
        if (columnArt != null) {
            columnArt.setVisibility(View.VISIBLE);
            fadeInArt(columnArt, columnRounded);
        }
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
        if (columnTitle != null) columnTitle.setText(lastTitle);
        if (columnArtist != null) columnArtist.setText("");
        if (columnAlbum != null) columnAlbum.setText("");
        clearArtwork();
        artMissing = true;
        updateContentDescriptions();
    }

    void teardown() {
        arbiter.reset();
        cancelArmed = false;
        activeFrame = null;
        cancelDragApply();
        cancelFollowThroughTimeout();
        followThroughFrame = null;
        cancelTextAnimations();
        // A cancelled fade skips its end action, which is where the newest text is applied.
        applyTextsDirectly(lastTitle, lastArtist, lastAlbum);
        resetTextAlphas();
        for (ArtTouchFrame frame : artFrames()) {
            frame.removeCallbacks(hideOverlayRunnable);
            cancelFrameAnimation(frame);
            frame.setTranslationX(0f);
        }
        hideOverlays();
        clearArtwork();
        artMissing = true;
    }

    /** Every art frame this readout owns, in draw order; the column cover is present only on the
     *  two-column layout. */
    private ArtTouchFrame[] artFrames() {
        if (columnArtFrame == null) {
            return new ArtTouchFrame[]{topArtFrame, bottomArtFrame, sideArtFrame};
        }
        return new ArtTouchFrame[]{topArtFrame, bottomArtFrame, sideArtFrame, columnArtFrame};
    }

    // -- artwork ------------------------------------------------------------------

    private void cancelArtworkRequest() {
        artworkGeneration++;
        if (artworkTask != null) artworkTask.cancel(false);
        artworkTask = null;
        artworkPending = false;
    }

    private void attemptArtwork(SpotifyTrack track) {
        if (artworkPending) return;
        lastArtAttemptMs = android.os.SystemClock.elapsedRealtime();
        boolean needSmall = topBox.getVisibility() == View.VISIBLE
                || bottomBox.getVisibility() == View.VISIBLE;
        boolean needSide = sideBox.getVisibility() == View.VISIBLE;
        boolean needColumn = columnBox != null && columnBox.getVisibility() == View.VISIBLE;
        if ((!needSmall && !needSide && !needColumn) || track == null) return;
        String imageId = track.imageId == null ? "" : track.imageId;
        String uri = track.uri == null ? "" : track.uri;
        int smallSize = dp(bottomArtDpF);
        int sideSize = dp(sideArtDp);
        // The column cover is the largest of them by far (it fills a whole column), and it is
        // snapshotted large so the cached bitmap is not upscaled into a visible blur.
        int columnSize = dp(columnArtDpF);
        float radiusPx = dp(artRadiusDp);
        int generation = ++artworkGeneration;
        artworkPending = true;
        artMissing = true;
        artworkTask = ART_WORKER.submit(() -> {
            Bitmap small = prepareArtwork(imageId, uri, smallSize, false, needSmall, radiusPx);
            Bitmap side = prepareArtwork(imageId, uri, sideSize, true, needSide, radiusPx);
            Bitmap column = prepareArtwork(imageId, uri, columnSize, true, needColumn, radiusPx);
            artHandler.post(() -> {
                if (generation != artworkGeneration || !uri.equals(lastUri) || !artworkEnabled) {
                    if (small != null) small.recycle();
                    if (side != null) side.recycle();
                    if (column != null) column.recycle();
                    return;
                }
                artworkPending = false;
                artworkTask = null;
                if (small != null) {
                    setArtTransition(topArt, currentArtwork, small);
                    setArtTransition(bottomArt, currentArtwork, small);
                    currentArtwork = small;
                }
                if (side != null) {
                    setArtTransition(sideArt, sideArtwork, side);
                    sideArtwork = side;
                }
                if (column != null) {
                    setArtTransition(columnArt, columnArtwork, column);
                    columnArtwork = column;
                    // No placeholder behind a missing cover here, so it only shows once it has one.
                    columnArt.setVisibility(View.VISIBLE);
                }
                artMissing = (needSmall && small == null) || (needSide && side == null)
                        || (needColumn && column == null);
                if (!artMissing) {
                    displayedImageId = imageId;
                    handleTrackChangeFollowThrough();
                    if (lastTrack != null) {
                        updateTexts(emptyFallback(lastTrack.title), emptyFallback(lastTrack.artist),
                                emptyFallback(lastTrack.album));
                    }
                }
            });
        });
    }

    private static Bitmap prepareArtwork(String imageId, String uri, int size, boolean large,
                                         boolean needed, float radiusPx) {
        if (!needed) return null;
        Bitmap raw = null;
        boolean fromNetworkCache = false;
        try {
            raw = large ? SpotifyArtworkCache.snapshotLarge(imageId, uri, size)
                    : SpotifyArtworkCache.snapshot(imageId, uri);
            if (raw == null && imageId != null && !imageId.isEmpty()) {
                raw = ART_NETWORK_CACHE.get(imageId);
                fromNetworkCache = raw != null;
                if (raw == null) fetchArtworkFromNetwork(imageId);
            }
            if (raw == null) return null;
            int targetPx = large ? (fromNetworkCache ? size : raw.getWidth()) : size;
            return roundBitmap(raw, targetPx, 0f);
        } catch (RuntimeException unavailable) {
            return null;
        } finally {
            if (raw != null && !fromNetworkCache) raw.recycle();
        }
    }

    private void setArtTransition(ImageView view, Bitmap oldBmp, Bitmap newBmp) {
        if (view == null || newBmp == null) return;
        Runnable previous = artTransitionEnds.remove(view);
        if (previous != null) view.removeCallbacks(previous);
        if (oldBmp != null && Motion.animationsEnabled()) {
            TransitionDrawable td = new TransitionDrawable(new Drawable[]{
                    new BitmapDrawable(activity.getResources(), oldBmp),
                    new BitmapDrawable(activity.getResources(), newBmp)
            });
            td.setCrossFadeEnabled(true);
            view.setImageDrawable(td);
            td.startTransition(Motion.dur(Motion.SWAP));
            Runnable finish = () -> {
                if (view.getDrawable() == td) view.setImageBitmap(newBmp);
                artTransitionEnds.remove(view);
            };
            artTransitionEnds.put(view, finish);
            view.postDelayed(finish, Motion.dur(Motion.SWAP));
        } else {
            view.setImageBitmap(newBmp);
        }
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

    /**
     * Scales the cover to its bitmap size. The corners are not baked in (radiusPx is 0 at every
     * call): the art views clip to their own rounded background instead (styleArt). A radius baked
     * at the bitmap's size scaled with it wherever the view was shown at another size - the PiP
     * window's cover column most of all - so the cover's corners no longer matched its
     * placeholder and touch scrim, which round at the view's real size.
     */
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
                if (frame == followThroughFrame) {
                    cancelFollowThroughTimeout();
                    followThroughFrame = null;
                }
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
        for (ArtTouchFrame frame : artFrames()) frame.removeCallbacks(dragApplyRunnable);
    }

    private void springBack(ArtTouchFrame frame) {
        if (frame == null) return;
        if (frame == followThroughFrame) {
            cancelFollowThroughTimeout();
            followThroughFrame = null;
        }
        frame.animate().cancel();
        frame.animate().translationX(0f).setDuration(SETTLE_ANIM_MS).start();
    }

    private void followThrough(ArtTouchFrame frame, int targetDp) {
        if (frame == null) return;
        cancelFollowThroughTimeout();
        followThroughFrame = frame;
        followThroughTargetDp = targetDp;
        frame.postDelayed(followThroughTimeoutRunnable, 400L);
        frame.animate().cancel();
        if (Motion.animationsEnabled()) {
            frame.animate().translationX(dp(targetDp)).setDuration(Motion.dur(Motion.EXIT)).start();
        } else {
            frame.setTranslationX(dp(targetDp));
        }
    }

    private void onFollowThroughTimeout() {
        if (followThroughFrame == null) return;
        ArtTouchFrame frame = followThroughFrame;
        followThroughFrame = null;
        frame.animate().cancel();
        if (Motion.animationsEnabled()) {
            frame.animate().translationX(0f).setDuration(Motion.dur(Motion.BASE)).start();
        } else {
            frame.setTranslationX(0f);
        }
    }

    private void cancelFollowThroughTimeout() {
        if (followThroughFrame != null) {
            followThroughFrame.removeCallbacks(followThroughTimeoutRunnable);
        }
    }

    private void handleTrackChangeFollowThrough() {
        if (followThroughFrame == null) return;
        ArtTouchFrame frame = followThroughFrame;
        int targetDp = followThroughTargetDp;
        cancelFollowThroughTimeout();
        followThroughFrame = null;
        frame.animate().cancel();
        if (Motion.animationsEnabled()) {
            frame.setTranslationX(dp(-targetDp));
            frame.animate().translationX(0f).setDuration(Motion.dur(Motion.BASE)).start();
        } else {
            frame.setTranslationX(0f);
        }
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
        for (ArtTouchFrame f : artFrames()) {
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
        for (ArtTouchFrame f : artFrames()) {
            f.removeCallbacks(hideOverlayRunnable);
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
        if (columnOverlayButton != null) columnOverlayButton.setImageDrawable(icon);
    }

    private View overlayScrimFor(ArtTouchFrame frame) {
        if (frame == topArtFrame) return topOverlayScrim;
        if (frame == sideArtFrame) return sideOverlayScrim;
        return frame == columnArtFrame ? columnOverlayScrim : bottomOverlayScrim;
    }

    private ImageButton overlayButtonFor(ArtTouchFrame frame) {
        if (frame == topArtFrame) return topOverlayButton;
        if (frame == sideArtFrame) return sideOverlayButton;
        return frame == columnArtFrame ? columnOverlayButton : bottomOverlayButton;
    }

    private void resetVisuals() {
        arbiter.reset();
        cancelArmed = false;
        activeFrame = null;
        cancelDragApply();
        for (ArtTouchFrame f : artFrames()) {
            if (f != followThroughFrame) {
                cancelFrameAnimation(f);
                f.setTranslationX(0f);
            }
        }
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

    /** Screen-space hit test covering the art frames (every surface). */
    boolean containsArtTouch(float rawX, float rawY) {
        if (!artworkEnabled) return false;
        // In Header mode topArtFrame lives inside headerRow, not topBox (which is GONE there,
        // genuinely empty) - testing topBox's visibility would always fail and make every touch
        // look "outside," triggering onOutsideDown()/resetVisuals() on every ACTION_DOWN even
        // directly over the visible artwork (see NativeSpicyShellViewImpl#dispatchTouchEvent).
        View topContainer = "Header".equals(lastMode) ? headerRow : topBox;
        return hitsFrame(topContainer, topArtFrame, rawX, rawY)
                || hitsFrame(bottomBox, bottomArtFrame, rawX, rawY)
                || hitsFrame(sideBox, sideArtFrame, rawX, rawY)
                || hitsFrame(columnBox, columnArtFrame, rawX, rawY);
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
        if (columnArtFrame != null) columnArtFrame.setContentDescription(desc);
    }

    // -- view helpers ---------------------------------------------------------------

    private static void styleArt(ImageView art, int radiusDp) {
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF3A3F55);
        placeholder.setCornerRadius(dp(radiusDp));
        art.setBackground(placeholder);
        // The cover is clipped by this rounded background (its outline), so image, placeholder
        // and scrim share one radius at whatever size the view ends up.
        art.setClipToOutline(true);
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

    /** TalkBack activation path, separate from raw-touch gestures (which TalkBack must not fire).
     *  Not final: the two-column cover subclasses it to own its measure. */
    private static class ArtTouchFrame extends FrameLayout {
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
