package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.ui.UiLanguage;
import com.eza.spicyex.settings.SettingsWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Direct-manipulation layout editor: tap/drag the *real* rendered artwork, track text, lyrics
 * text, follow chip, skip chip, and top controls cluster directly on the live lyrics screen to
 * change their {@link Settings} - plus a Background element with no on-screen shape of its own.
 *
 * <p>Added as an overlay directly into {@code shellRoot} - the same {@link NativeSpicyShellViewImpl}
 * the real views already live in, not a separate window - so the selection outline and handles
 * share the exact same coordinate space as the real views with no cross-window alignment math.
 * Every write goes through {@link SettingsWriter}, the same path an ordinary settings row uses, so
 * the real screen underneath updates the moment a value changes.
 */
final class LyricsLayoutEditController {
    static final String OVERLAY_TAG = "spicyex.layout-editor-overlay";

    interface EditorHandle {
        /** @return true when the editor consumed the back action. */
        boolean onBackPressed();

        /**
         * Selects one element by its agent name (see {@link #AGENT_ELEMENTS}), exactly as a tap on
         * that element would, so an automated read can walk the same states a user reaches.
         *
         * @return false for a name that is not one of the elements.
         */
        boolean agentSelect(String name);

        /**
         * Selects one element by its agent name, optionally revealing its options panel.
         *
         * @return false for a name that is not one of the elements.
         */
        boolean agentSelect(String name, boolean revealPanel);

        /** The agent name of the selected element (e.g. "artwork", "skip"). */
        String selectedName();

        /** Whether the editor is currently in card mode. */
        boolean isCardMode();

        /** Closes the editor, same as the Done button. */
        void agentClose();

        /** Runs an editor action; the command channel requires confirmation for reset all. */
        boolean agentAction(String action, String argument);

        /** Adds the editor's own geometry and state to a probe reading. */
        void agentReport(LayoutProbeReport report);
    }

    /**
     * Agent-facing element names, in the order the editor's own naming already uses. The keys are
     * what {@code editor select} takes; the values are the internal elements they mean. Deliberately
     * a fixed list rather than {@code valueOf()} on the enum: a probe asking for a name the editor
     * does not have must get false, not an exception from a debug-only channel.
     */
    private static final java.util.Map<String, String> AGENT_ELEMENTS = agentElements();

    private static java.util.Map<String, String> agentElements() {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("artwork", "ARTWORK");
        names.put("track_text", "TRACK_TEXT");
        names.put("focus", "FOCUS");
        names.put("lyrics", "TEXT");
        names.put("background", "BACKGROUND");
        names.put("skip", "SKIP");
        names.put("follow", "FOLLOW");
        names.put("top_bar", "DOCK");
        names.put("card", "CARD");
        return java.util.Collections.unmodifiableMap(names);
    }
    private static final int HANDLE_SIZE_DP = 22;
    /** Extra grab margin around the corner grip's drawn bracket. */
    private static final int HANDLE_TOUCH_PAD_DP = 10;
    /** Touch box of the Apple-style resize grip; the element's corner sits GRIP_OUTSIDE_DP in
     *  from its bottom-right, so most of the box (and the drawn arc) lies inside the element. */
    private static final int GRIP_BOX_DP = 56;
    private static final int GRIP_OUTSIDE_DP = 12;
    private static final int TEXT_COLOR = 0xFFE8E8EE;
    private static final int ACCENT_COLOR = 0xFF1ED760;
    /** Outline color actually painted on an UNSELECTED capture. Every capturable element is
     *  outlined at all times so the editor reads as one visual language, but at full-strength
     *  gray, seven simultaneous boxes plus a per-lyric-row grid buried the one box the user
     *  was actually editing. Unselected outlines are therefore drawn as a faint hairline: still
     *  legible as "this is tappable", no longer competing with the selection. */
    private static final int GRAY_IDLE_COLOR = 0x3DFFFFFF;
    private static final int OUTLINE_SELECTED_DP = 2;
    /** Above every floating chip's elevation (8dp), so the editor always draws and hits first. */
    private static final int EDITOR_Z_DP = 24;
    private static final int OUTLINE_IDLE_DP = 1;
    /** Options-panel group card: see Session#beginGroup. */
    private static final int GROUP_RADIUS_DP = 14;
    private static final int GROUP_FILL_COLOR = 0x0FFFFFFF;
    private static final int GROUP_TITLE_SP = 12;
    private static final int GROUP_TITLE_COLOR = 0x8AFFFFFF;
    /** Interactive text in the panel. Chips and toggles are what the user aims at, so they read a
     *  step larger than the group titles above them rather than the same size. */
    private static final int CONTROL_TEXT_SP = 15;
    /** Minimum height of a tappable chip/toggle, so every control clears a comfortable target. */
    private static final int CONTROL_MIN_HEIGHT_DP = 44;

    // -- options panel (one floating window over the preview) ------------------------
    /** Clear space the panel keeps from every screen edge, and from the editor toolbar row. */
    private static final int PANEL_INSET_DP = 12;
    private static final int PANEL_GAP_DP = 8;
    private static final int PANEL_MAX_WIDTH_DP = 360;
    private static final int PANEL_CLOSE_BUTTON_DP = 44;
    /** The panel header: the close button's touch target plus its own top padding. */
    private static final int PANEL_HEADER_DP = 48;
    /** Share of the overlay height the panel may take, options list included. */
    private static final float PANEL_MAX_HEIGHT_SHARE = 0.6f;
    /** The card stage stays visible, so the panel takes at most half the overlay there. */
    private static final float PANEL_MAX_HEIGHT_SHARE_CARD = 0.5f;
    /** Fade/scale in and out. The panel never slides: it is a window, not a sheet. */
    private static final int PANEL_FADE_MS = 180;
    private static final float PANEL_HIDDEN_SCALE = 0.96f;

    private LyricsLayoutEditController() {
    }

    /** Bundles a floating chip's editor hooks: the real on-screen view to outline, and the pair
     *  of calls that force it visible for the duration of editing (and undo that afterward)
     *  without disturbing whatever made it visible on its own. */
    static final class EditableChip {
        final Supplier<View> view;
        final Runnable showForEditing;
        final Runnable restoreVisibility;

        EditableChip(Supplier<View> view, Runnable showForEditing, Runnable restoreVisibility) {
            this.view = view;
            this.showForEditing = showForEditing;
            this.restoreVisibility = restoreVisibility;
        }
    }

    /** Fluent request builder for {@link #show}. The plain static method grew past a readable
     *  positional-parameter count once the Follow chip, Track text, and Dock elements joined
     *  Artwork/Focus/Text/Background/Skip - this keeps the call site self-describing. */
    static final class Request {
        private Activity activity;
        private ViewGroup shellRoot;
        private Supplier<View> artFrameSupplier;
        private View focusArea;
        private Supplier<ViewGroup> mountedRowsHostSupplier;
        private Supplier<View> trackTextFrameSupplier;
        private Supplier<View> chromeClusterSupplier;
        private Supplier<View> backButtonSupplier;
        private boolean landscape;
        private boolean twoColumn;
        private java.util.function.IntSupplier artSizeMaxDp;
        private java.util.function.DoubleSupplier focusFraction;
        private Runnable applyPreferences;
        private Runnable onChromeReveal;
        private Runnable onClosed;
        private Runnable enableDemoData;
        private Runnable disableDemoData;
        private EditableChip skipChip;
        private EditableChip followChip;
        private boolean cardMode;

        Request activity(Activity value) { this.activity = value; return this; }
        Request shellRoot(ViewGroup value) { this.shellRoot = value; return this; }
        Request artFrameSupplier(Supplier<View> value) { this.artFrameSupplier = value; return this; }
        Request focusArea(View value) { this.focusArea = value; return this; }
        Request mountedRowsHostSupplier(Supplier<ViewGroup> value) { this.mountedRowsHostSupplier = value; return this; }
        Request trackTextFrameSupplier(Supplier<View> value) { this.trackTextFrameSupplier = value; return this; }
        Request chromeClusterSupplier(Supplier<View> value) { this.chromeClusterSupplier = value; return this; }
        Request backButtonSupplier(Supplier<View> value) { this.backButtonSupplier = value; return this; }
        Request landscape(boolean value) { this.landscape = value; return this; }
        /** Artwork is the two-column cover: it resizes about its column, not from a corner. */
        Request twoColumn(boolean value) { this.twoColumn = value; return this; }
        /** Largest artwork the current placement can show, in dp. */
        Request artSizeMaxDp(java.util.function.IntSupplier value) { this.artSizeMaxDp = value; return this; }
        /** The anchor fraction the lyrics actually scroll to, so the focus line is drawn there. */
        Request focusFraction(java.util.function.DoubleSupplier value) { this.focusFraction = value; return this; }
        Request applyPreferences(Runnable value) { this.applyPreferences = value; return this; }
        Request onChromeReveal(Runnable value) { this.onChromeReveal = value; return this; }
        Request onClosed(Runnable value) { this.onClosed = value; return this; }
        Request enableDemoData(Runnable value) { this.enableDemoData = value; return this; }
        Request disableDemoData(Runnable value) { this.disableDemoData = value; return this; }
        Request skipChip(EditableChip value) { this.skipChip = value; return this; }
        Request followChip(EditableChip value) { this.followChip = value; return this; }
        /** Open on the now-playing card instead of the lyrics screen. */
        Request cardMode(boolean value) { this.cardMode = value; return this; }

        EditorHandle show() {
            if (activity == null || shellRoot == null) return null;
            // A screen rebuild can leave the previous session's view attached for one traversal.
            // Never stack a second full-screen touch layer on top of it.
            if (shellRoot.findViewWithTag(OVERLAY_TAG) != null) return null;
            Session session = new Session(this);
            session.start();
            return session;
        }
    }

    /**
     * The screen edge the options panel is placed against until the user moves it. Kept as a plain
     * rule with no Android in it so the one decision that keeps the panel off the element it edits
     * can be unit-tested directly.
     */
    enum SheetPlacement {
        BOTTOM, TOP, START, END;

        /**
         * Picks the edge to place the panel on: the default one, unless the edited element sits on
         * the side the default would cover.
         *
         * @param overlayWidth  editor overlay width, in pixels.
         * @param overlayHeight editor overlay height, in pixels.
         * @param targetRect    the edited element's rect in overlay coordinates, or null when there
         *                      is nothing honest to measure.
         * @param sideSheet     the screen is wide enough for a side placement instead of a
         *                      bottom one.
         * @param cardMode      the now-playing card is being edited; its stage is laid out around
         *                      the default placement, so the panel stays there.
         * @return END on a wide screen, BOTTOM on a narrow one, unless the target argues otherwise.
         */
        static SheetPlacement choose(int overlayWidth, int overlayHeight, int[] targetRect,
                boolean sideSheet, boolean cardMode) {
            SheetPlacement fallback = sideSheet ? END : BOTTOM;
            if (cardMode || targetRect == null || targetRect.length < 4) return fallback;
            // A degenerate rect is as good as no reading at all: report the default rather than a
            // placement derived from a centre that is not really on screen.
            if (targetRect[2] <= targetRect[0] || targetRect[3] <= targetRect[1]) return fallback;
            if (sideSheet) {
                if (overlayWidth <= 0) return END;
                // Right half -> the panel belongs on the left, and the other way round. Comparing
                // the doubled centre (left + right) against the width keeps an element centred
                // exactly on the midline in the far half, on odd and even overlay widths alike.
                return targetRect[0] + targetRect[2] >= overlayWidth ? START : END;
            }
            if (overlayHeight <= 0) return BOTTOM;
            return targetRect[1] + targetRect[3] >= overlayHeight ? TOP : BOTTOM;
        }
    }

    public static class SlotRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public SlotRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }

        public long overlapArea(SlotRect other) {
            if (other == null) return 0;
            int ox = Math.max(0, Math.min(right, other.right) - Math.max(left, other.left));
            int oy = Math.max(0, Math.min(bottom, other.bottom) - Math.max(top, other.top));
            return (long) ox * (long) oy;
        }
    }

    public static final class Rect extends SlotRect {
        public Rect(int left, int top, int right, int bottom) {
            super(left, top, right, bottom);
        }
    }

    /**
     * Chooses the slot with the smallest total overlap area across all obstacle rects.
     * Candidate slots are evaluated in order; ties go to the earlier slot.
     */
    public static int chooseToolbarSlot(List<? extends SlotRect> slots, List<? extends SlotRect> obstacles) {
        if (slots == null || slots.isEmpty()) return -1;
        int bestIndex = 0;
        long minOverlap = Long.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            SlotRect slot = slots.get(i);
            long totalOverlap = 0;
            if (slot != null && obstacles != null) {
                for (int j = 0; j < obstacles.size(); j++) {
                    SlotRect obstacle = obstacles.get(j);
                    if (obstacle != null) {
                        totalOverlap += slot.overlapArea(obstacle);
                    }
                }
            }
            if (totalOverlap < minOverlap) {
                minOverlap = totalOverlap;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /** One editor invocation's mutable state - a plain instance instead of a pile of one-element
     *  arrays now that there's real state (selected element, snapshot, current drag) to carry. */
    private static final class Session implements EditorHandle {
        private static final Settings.Setting<?>[] TOUCHED_SETTINGS = {
                Settings.TRACK_INFO_POSITION, Settings.TRACK_INFO_ART_RADIUS,
                Settings.TRACK_INFO_ART_SIZE, Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP,
                Settings.TRACK_INFO_TEXT_ALIGN, Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                Settings.TRACK_INFO_SHOW_TITLE, Settings.TRACK_INFO_SHOW_ARTIST,
                Settings.TRACK_INFO_SHOW_ALBUM,
                Settings.LYRICS_FOCUS_POSITION, Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT,
                Settings.ENABLE_LINE_BLUR, Settings.LYRICS_BLUR_INTENSITY,
                Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                Settings.LYRICS_FONT, Settings.LYRICS_WEIGHT,
                Settings.LINE_SPACING, Settings.LINE_SPACING_CUSTOM,
                Settings.TRACK_INFO_TEXT_SIZE, Settings.TRACK_INFO_TEXT_SIZE_CUSTOM,
                Settings.BACKGROUND_STYLE,
                Settings.FORCE_DARK_BACKGROUND, Settings.EXTRA_DARK_BACKGROUND,
                Settings.SKIP_CHIP_POSITION, Settings.SKIP_CHIP_STYLE,
                Settings.FOLLOW_CHIP_POSITION, Settings.FOLLOW_CHIP_STYLE,
                Settings.BACKGROUND_RENDER_QUALITY,
                Settings.LIKED_SONGS_BUTTON, Settings.CHROME_CLUSTER_POSITION,
                Settings.CHROME_CLUSTER_LAYOUT,
                Settings.SHOW_FULLSCREEN_BACK_BUTTON, Settings.FULLSCREEN_CONTROLS,
                Settings.LYRICS_ADAPTIVE_TEXT_SIZE, Settings.INTERLUDE_ICON,
                Settings.TRACK_INFO_BACKGROUND, Settings.TRACK_INFO_TEXT_OVERFLOW,
                Settings.WORD_BOUNCE, Settings.WORD_BOUNCE_STYLE,
                Settings.ENABLE_GLOW_BLUR, Settings.LINE_SYNC_FILL,
                Settings.ANIMATION_STYLE, Settings.LOAD_LIFT_ANIMATION,
                Settings.APPLE_CASCADE_SPEED, Settings.APPLE_SPRING_STRENGTH,
                Settings.ADAPTIVE_SECTIONING, Settings.ADAPTIVE_LANDSCAPE_LAYOUT,
                Settings.PANEL_MEDIA_CONTROLS, Settings.LYRICS_FONT_CUSTOM_PATH,
                Settings.APPLE_FADE_PASSED_LINES, Settings.LINE_SLIDE_ANIMATION, Settings.APPLE_LIFT,
                Settings.LIVE_CARD_TEXT_SIZE, Settings.LIVE_CARD_TEXT_SIZE_CUSTOM, Settings.LIVE_CARD_WEIGHT,
                Settings.LIVE_CARD_SECONDARY_MODE, Settings.LIVE_CARD_ANIMATION, Settings.LIVE_CARD_GLOW,
                Settings.LIVE_CARD_LINE_SYNC_FILL, Settings.LIVE_CARD_OVERFLOW,
                Settings.LIVE_CARD_SCROLL_SCOPE, Settings.LIVE_CARD_TRANSITION
        };

        /** Which on-screen thing is selected. Artwork and its title/artist text used to be one
         *  bundled element with no outline of its own for the text half - they are now separate so
         *  each can be tapped and configured independently. */
        private enum Element { ARTWORK, TRACK_TEXT, FOCUS, TEXT, BACKGROUND, SKIP, FOLLOW, DOCK, CARD }


        private final Activity activity;
        private final ViewGroup shellRoot;
        private final Supplier<View> artFrameSupplier;
        private final View focusArea;
        private final Supplier<ViewGroup> mountedRowsHostSupplier;
        private final Supplier<View> trackTextFrameSupplier;
        private final Supplier<View> chromeClusterSupplier;
        private final Supplier<View> backButtonSupplier;
        private final SettingsStore store;
        private final SettingsWriter writer;
        private final SettingsUiStrings strings;
        private final Runnable applyPreferences;
        private final Runnable onChromeReveal;
        private final Runnable onClosed;
        private final Runnable enableDemoData;
        private final Runnable disableDemoData;
        private final EditableChip skipChip;
        private final EditableChip followChip;
        private final boolean startInCardMode;
        private final boolean landscape;
        private final boolean twoColumn;
        private final java.util.function.IntSupplier artSizeMaxDp;
        private final java.util.function.DoubleSupplier focusFraction;
        /** True while the focus line follows a finger; capture sync then leaves it alone. */
        private boolean draggingFocus;

        private final FrameLayout overlay;
        private final FrameLayout artLayer;
        private final FrameLayout trackTextLayer;
        private final FrameLayout skipLayer;
        private final FrameLayout followLayer;
        private final FrameLayout dockLayer;
        private final FrameLayout backLayer;
        private final LinearLayout optionsCard;
        private final MaxHeightScrollView optionsScroll;
        private final LinearLayout panelContainer;
        private final LinearLayout panelHeader;
        private final TextView sheetTitle;
        private FrameLayout.LayoutParams panelLp;
        /** Which edge the panel is placed against right now; see {@link SheetPlacement#choose}. */
        private SheetPlacement sheetPlacement = SheetPlacement.BOTTOM;
        /** Set once the user has dragged the panel: their position wins for the whole session. */
        private boolean panelMovedByUser;
        private boolean draggingPanel;

        private Element selected = Element.ARTWORK;
        private boolean panelVisible;
        /** Which presentation {@link #start()} picked: a bottom placement, or a side one on the
         *  trailing edge when the screen is wide enough. A field so a probe can report it. */
        private boolean sideSheet;
        private View artCapture;
        private View artHandle;
        private View trackTextCapture;
        private View focusHandle;
        private View textOutline;
        private View skipCapture;
        private View followCapture;
        private View dockCapture;
        /** The editor's own top-bar buttons and the card caption, kept only so a probe can read
         *  where they actually landed. Nothing else consults them. */
        private View toolbarDone;
        private View toolbarReset;
        private View toolbarDemo;
        private View cardCaption;
        private boolean demoActive;
        /** Outline -> real view it is tracing. Re-synced every frame by {@link #syncCaptures()}. */
        private final java.util.List<CaptureBinding> captureBindings = new java.util.ArrayList<>();
        private ViewTreeObserver.OnPreDrawListener captureSyncListener;
        /** Set while the corner handle is being dragged: the outline is then driven by the drag
         *  itself and must not be pulled back to the real artwork, which does not resize until the
         *  settings write propagates. */
        private boolean resizingArtwork;
        private boolean resizingTrackText;
        private View trackTextHandle;
        /** Live value shown next to whatever is being resized or pinched. */
        private TextView valueBubble;
        private boolean draggingArtwork;
        private boolean draggingFocusArea;
        private boolean draggingSlider;
        private int currentToolbarSlot = 0;

        private boolean isDragGestureActive() {
            return resizingArtwork
                    || resizingTrackText
                    || draggingFocus
                    || draggingArtwork
                    || draggingFocusArea
                    || draggingPanel
                    || draggingSlider;
        }

        /** One outline and the real on-screen view it traces. */
        private static final class CaptureBinding {
            final View capture;
            final Supplier<View> source;
            /** Corner grip pinned to the capture's bottom-right, or null. */
            final View handle;
            final int handleInsetPx;
            final Element handleOwner;

            CaptureBinding(View capture, Supplier<View> source, View handle, int handleInsetPx,
                           Element handleOwner) {
                this.capture = capture;
                this.source = source;
                this.handle = handle;
                this.handleInsetPx = handleInsetPx;
                this.handleOwner = handleOwner;
            }
        }

        private void bindCapture(View capture, Supplier<View> source) {
            bindCapture(capture, source, null, 0, null);
        }

        private void bindCapture(View capture, Supplier<View> source, View handle, int handleInsetPx,
                                 Element handleOwner) {
            if (capture == null || source == null) return;
            captureBindings.add(new CaptureBinding(capture, source, handle, handleInsetPx, handleOwner));
        }

        /**
         * Re-anchors every outline onto its real view's current on-screen rect.
         *
         * <p>Outlines used to be positioned once, when they were built, from a single
         * {@code relativePosition()} reading. Anything that moved the real view afterwards without
         * going through a rebuild - the ambient background recreating its layer, the chrome
         * auto-hide animating, window insets arriving late, the lyrics column re-laying out after a
         * font or spacing change, a rotation-adjacent resize - left the outline behind at the old
         * rect. The box then no longer sat on the thing it outlined, and tapping the visible
         * element missed it entirely.
         *
         * <p>Running from an {@code OnPreDrawListener} means this reads geometry after layout has
         * settled and before anything is painted, so a corrected outline is never drawn stale for
         * even one frame. Writes are gated on an actual change, so the {@code requestLayout()} a
         * margin change triggers cannot loop: once the outline agrees with its source, nothing is
         * written and the traversal ends.
         */
        private void syncCaptures() {
            if (captureBindings.isEmpty()) return;
            boolean obstacleRectChanged = false;
            for (int i = 0; i < captureBindings.size(); i++) {
                CaptureBinding binding = captureBindings.get(i);
                View capture = binding.capture;
                if (capture == null || capture.getParent() == null) continue;
                if (resizingArtwork && !twoColumn && capture == artCapture) continue;
                if (resizingTrackText && capture == trackTextCapture) continue;
                View source;
                try {
                    source = binding.source.get();
                } catch (Throwable ignored) {
                    continue;
                }
                if (source == null || !source.isAttachedToWindow()
                        || source.getWidth() <= 0 || source.getHeight() <= 0) {
                    // The real view is gone or not laid out - hide the orphaned outline rather
                    // than leaving it floating at its last known rect.
                    if (capture.getVisibility() != View.GONE) {
                        capture.setVisibility(View.GONE);
                        obstacleRectChanged = true;
                    }
                    continue;
                }
                if (capture.getVisibility() != View.VISIBLE) {
                    capture.setVisibility(View.VISIBLE);
                    obstacleRectChanged = true;
                }
                ViewGroup.LayoutParams raw = capture.getLayoutParams();
                if (!(raw instanceof FrameLayout.LayoutParams)) continue;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                int[] pos = relativePosition(source, shellRoot);
                boolean changed = lp.leftMargin != pos[0] || lp.topMargin != pos[1]
                        || lp.width != source.getWidth() || lp.height != source.getHeight();
                if (changed) {
                    lp.leftMargin = pos[0];
                    lp.topMargin = pos[1];
                    lp.width = source.getWidth();
                    lp.height = source.getHeight();
                    capture.setLayoutParams(lp);
                    obstacleRectChanged = true;
                }
                syncHandle(binding, lp);
            }
            // The focus line and the row outlines are placed from the lyrics frame, which moves
            // when the editor's top band lands after they are built. They were never re-synced,
            // so in landscape the line sat a band's height above the real anchor.
            if (!draggingFocus) repaintFocusHandle();
            syncRowOutlines();
            if (obstacleRectChanged) {
                evaluateToolbarPlacement();
            }
        }

        private void syncHandle(CaptureBinding binding, FrameLayout.LayoutParams captureLp) {
            View handle = binding.handle;
            if (handle == null || handle.getParent() == null) return;
            // A grip only means anything while its element is the selection; showing it always
            // put a bright handle on screen competing with whatever was being edited.
            int wanted = selected == binding.handleOwner ? View.VISIBLE : View.GONE;
            if (handle.getVisibility() != wanted) handle.setVisibility(wanted);
            if (wanted != View.VISIBLE) return;
            ViewGroup.LayoutParams raw = handle.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int left = captureLp.leftMargin + captureLp.width - binding.handleInsetPx;
            int top = captureLp.topMargin + captureLp.height - binding.handleInsetPx;
            if (lp.leftMargin == left && lp.topMargin == top) return;
            lp.leftMargin = left;
            lp.topMargin = top;
            handle.setLayoutParams(lp);
        }

        private void startCaptureSync() {
            if (captureSyncListener != null) return;
            captureSyncListener = () -> {
                try {
                    syncCaptures();
                } catch (Throwable ignored) {
                    // Geometry can be read mid-teardown; never take the editor down for it.
                }
                return true;
            };
            overlay.getViewTreeObserver().addOnPreDrawListener(captureSyncListener);
        }

        private void stopCaptureSync() {
            if (captureSyncListener == null) return;
            ViewTreeObserver observer = overlay.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(captureSyncListener);
            captureSyncListener = null;
            captureBindings.clear();
        }

        Session(Request request) {
            this.activity = request.activity;
            this.shellRoot = request.shellRoot;
            this.artFrameSupplier = request.artFrameSupplier;
            this.focusArea = request.focusArea;
            this.mountedRowsHostSupplier = request.mountedRowsHostSupplier;
            this.trackTextFrameSupplier = request.trackTextFrameSupplier;
            this.chromeClusterSupplier = request.chromeClusterSupplier;
            this.backButtonSupplier = request.backButtonSupplier;
            this.applyPreferences = request.applyPreferences;
            this.onChromeReveal = request.onChromeReveal;
            this.onClosed = request.onClosed;
            this.enableDemoData = request.enableDemoData;
            this.disableDemoData = request.disableDemoData;
            this.skipChip = request.skipChip;
            this.followChip = request.followChip;
            this.startInCardMode = request.cardMode;
            this.landscape = request.landscape;
            this.twoColumn = request.twoColumn;
            this.artSizeMaxDp = request.artSizeMaxDp;
            this.focusFraction = request.focusFraction;
            this.store = new SettingsStore(activity);
            this.writer = new SettingsWriter(store);
            this.strings = UiLanguage.strings(activity, store.get(Settings.UI_LANGUAGE));
            this.overlay = new FrameLayout(activity);
            this.artLayer = new FrameLayout(activity);
            this.trackTextLayer = new FrameLayout(activity);
            this.skipLayer = new FrameLayout(activity);
            this.followLayer = new FrameLayout(activity);
            this.dockLayer = new FrameLayout(activity);
            this.backLayer = new FrameLayout(activity);
            this.optionsCard = new LinearLayout(activity);
            this.optionsScroll = new MaxHeightScrollView(activity);
            this.panelContainer = new LinearLayout(activity);
            this.panelHeader = new LinearLayout(activity);
            this.sheetTitle = text("", 17, TEXT_COLOR, true);
            this.overlay.setTag(OVERLAY_TAG);
        }

        /** Writes through the settings store, then forces the real shell to re-apply - bypassing
         *  the async SharedPreferences-listener round trip, so overlay geometry read afterward
         *  sees the real view already caught up instead of one frame behind. The apply itself is
         *  always posted, never run inline from the touch callback that called put(): some
         *  position changes reparent a whole view subtree (entering/leaving Header mode moves the
         *  art+title row between the chrome header and the track-info box), and doing that
         *  synchronously while this same call stack is still mutating this overlay's own view
         *  tree (removeAllViews()/addView() in refreshArtwork()) can reenter Android's
         *  measure/layout pass mid-traversal and crash with a NPE deep in FrameLayout.onMeasure.
         *  Anything that needs the post-apply real geometry goes in afterApply, chained after. */
        private <T> void put(Settings.Setting<T> setting, T value) {
            put(setting, value, null);
        }

        private <T> void put(Settings.Setting<T> setting, T value, Runnable afterApply) {
            writer.put(setting, value);
            cardConfigDirty = true;
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                // applyPreferences() only *requests* a layout pass (requestLayout() never runs
                // synchronously) - reading the frame's geometry immediately after it, as
                // refreshArtwork() does, could still see pre-change values (wrong width/height/
                // position), landing the capture outline somewhere that doesn't overlap the real,
                // now-repositioned artwork - taps on the visible artwork then hit nothing. Wait
                // for an actual layout pass to land before running afterApply.
                if (afterApply != null) afterNextLayout(afterApply);
            });
        }

        /** Runs action after layout on this overlay has settled, or after a short timeout if the
         *  write didn't end up changing any layout (e.g. re-picking the same position) -
         *  onGlobalLayout would otherwise never fire and afterApply would never run at all.
         *
         *  <p>"Settled" means two animation frames pass with no further layout pass in between,
         *  not just the first pass after the write: some position changes (e.g. entering/leaving
         *  Header mode) animate the real view across several frames, and firing on that first,
         *  still-mid-transition pass used to read a stale intermediate rect - the capture outline
         *  then froze there while the real view kept animating to its final spot. */
        private void afterNextLayout(Runnable action) {
            ViewTreeObserver observer = overlay.getViewTreeObserver();
            boolean[] done = {false};
            int[] generation = {0};
            ViewTreeObserver.OnGlobalLayoutListener[] listenerRef =
                    new ViewTreeObserver.OnGlobalLayoutListener[1];
            listenerRef[0] = () -> {
                if (done[0]) return;
                int mine = ++generation[0];
                overlay.postOnAnimation(() -> overlay.postOnAnimation(() -> {
                    if (done[0] || generation[0] != mine) return;
                    done[0] = true;
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                    action.run();
                }));
            };
            if (observer.isAlive()) observer.addOnGlobalLayoutListener(listenerRef[0]);
            overlay.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                action.run();
            }, 150L);
        }

        void start() {
            overlay.setBackgroundColor(0x4D000000);
            // Keep the full-screen editor layer visually present without making its parent
            // consume taps. Otherwise taps on the real chrome buttons underneath (settings,
            // liked songs, translation, romanization) are swallowed after dock captures are
            // removed. Interactive editor children still handle their own touches.
            overlay.setClickable(false);
            overlay.setOnClickListener(null);
            // Two-column hosts Skip and Follow on this same shell with their own elevation. At
            // z 0 the overlay drew under them, and the real chip took the tap meant for its
            // editor outline instead of opening the Skip & Follow options.
            overlay.setTranslationZ(dp(EDITOR_Z_DP));

            // The broad lyrics plane is lowest. Concrete element captures are added above it so
            // selecting bottom artwork never depends on Android retrying an earlier sibling after
            // this full-screen layer declines the DOWN event.
            buildTextTapLayer();
            buildFocusHandle();

            artLayer.setClipChildren(false);
            overlay.addView(artLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            trackTextLayer.setClipChildren(false);
            overlay.addView(trackTextLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Added after the lyrics tap layer (not into artLayer, which refreshArtwork() clears
            // wholesale on every artwork change) so each chip's capture region wins overlapping
            // touches there instead of being swallowed as a lyrics tap or forwarded as a scroll.
            skipLayer.setClipChildren(false);
            overlay.addView(skipLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            followLayer.setClipChildren(false);
            overlay.addView(followLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dockLayer.setClipChildren(false);
            overlay.addView(dockLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            backLayer.setClipChildren(false);
            overlay.addView(backLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Auto-preview: opening the editor with nothing actually playing left it showing an
            // empty/error state, so every element had to be selected blind. Deferred to after the first
            // layout pass so the editor UI appears instantly instead of blocking on document render +
            // bitmap creation.
            if (!demoActive && enableDemoData != null) {
                demoActive = true;
                overlay.post(() -> overlay.post(() -> {
                    if (demoActive && overlay.getParent() != null && enableDemoData != null) {
                        enableDemoData.run();
                    }
                }));
            }

            optionsCard.setOrientation(LinearLayout.VERTICAL);
            optionsCard.setPadding(dp(14), dp(4), dp(14), dp(10));

            // The card can run long (Artwork/Background have half a dozen rows each), and in
            // landscape's shorter height a plain WRAP_CONTENT card would grow tall enough to run
            // off the screen, with no way to reach whatever scrolled past it. Wrapping it in a
            // scroll view - capped by MaxHeightScrollView to the panel's own height budget - keeps
            // it reachable by drag in both orientations instead.
            optionsScroll.setVerticalScrollBarEnabled(false);
            optionsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            optionsScroll.addView(optionsCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // The options live in one floating panel over the preview: rounded on every corner, on
            // its own surface, clear of the screen edges. Which edge it is placed against is decided
            // per selection in selectElement - this only fixes the default.
            android.content.res.Configuration screen = activity.getResources().getConfiguration();
            sideSheet = landscape || screen.screenWidthDp >= 600;
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(0xF21C1C22);
            panelBg.setStroke(dp(1), 0x24FFFFFF);
            panelBg.setCornerRadius(dp(20));
            panelContainer.setBackground(panelBg);
            panelContainer.setElevation(dp(16));
            // Swallow taps on the panel's own padding so they never fall through to the element
            // outlines underneath it.
            panelContainer.setClickable(true);
            panelContainer.setOrientation(LinearLayout.VERTICAL);

            panelContainer.removeAllViews();
            // Names what the panel is editing: selection is tap-driven on the real screen, so
            // without it the only cue is which outline happens to be green. The header row is also
            // the panel's only move handle; the close button is a child, so it keeps its own tap.
            sheetTitle.setSingleLine(true);
            sheetTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            panelHeader.setOrientation(LinearLayout.HORIZONTAL);
            panelHeader.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleLp.leftMargin = dp(16);
            titleLp.rightMargin = dp(8);
            panelHeader.addView(sheetTitle, titleLp);
            ImageView close = iconButton(ActionIconDrawable.Kind.CLOSE, 0xE6FFFFFF,
                    s("close", "Close"), () -> hidePanelSheet(true));
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    dp(PANEL_CLOSE_BUTTON_DP), dp(PANEL_CLOSE_BUTTON_DP));
            closeLp.rightMargin = dp(4);
            panelHeader.addView(close, closeLp);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_CLOSE_BUTTON_DP));
            headerLp.topMargin = dp(4);
            panelContainer.addView(panelHeader, headerLp);
            installPanelDrag();
            // Editor actions live in the top chrome, so the panel is only as tall as its options.
            panelContainer.addView(optionsScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Opens against the default edge; selectElement() re-picks it per selection from there.
            applySheetPlacement(sideSheet ? SheetPlacement.END : SheetPlacement.BOTTOM);
            overlay.addView(panelContainer, panelLp);
            panelVisible = false;
            panelContainer.setVisibility(View.INVISIBLE);

            refreshArtwork();
            refreshTrackText();
            selectElement(Element.ARTWORK, false);

            overlay.setAlpha(0f);
            shellRoot.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.animate().alpha(1f).setDuration(240).start();

            // Floating chips and the top controls cluster only reliably show under their own
            // real-state conditions (an active skip gap, follow state away from the current line,
            // the fullscreen auto-hide timer) - force everything visible for the duration of the
            // edit so there's always something here to select and position.
            if (onChromeReveal != null) onChromeReveal.run();
            if (skipChip != null && skipChip.showForEditing != null) skipChip.showForEditing.run();
            if (followChip != null && followChip.showForEditing != null) followChip.showForEditing.run();
            refreshSkipChip();
            refreshFollowChip();
            refreshDock();
            refreshBackButton();
            if (startInCardMode) {
                // Opened for the card: the lyrics-screen editor never shows, not even for the
                // frame before the card stage can be laid out.
                for (View view : lyricsModeViews()) {
                    if (view != null) view.setVisibility(View.GONE);
                }
            }
            afterNextLayout(() -> {
                refreshSkipChip();
                refreshFollowChip();
                refreshDock();
                refreshBackButton();
                if (startInCardMode) setCardMode(true);
            });
            startCaptureSync();
        }

        private void close() {
            LyricsLayoutEditorReopenPolicy.clear();
            stopCaptureSync();
            overlay.removeCallbacks(cardFrame);
            if (disableDemoData != null) {
                demoActive = false;
                disableDemoData.run();
            }
            if (skipChip != null && skipChip.restoreVisibility != null) skipChip.restoreVisibility.run();
            if (followChip != null && followChip.restoreVisibility != null) followChip.restoreVisibility.run();
            cleanupCustomFontsExcept(store.get(Settings.LYRICS_FONT_CUSTOM_PATH));
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
            if (onClosed != null) onClosed.run();
            // The overlay's own scrim sat over the real chrome row the whole time it was open;
            // make sure it (and the settings cog on it) is actually visible again afterward
            // rather than relying on whatever auto-hide state it happened to be in already.
            if (onChromeReveal != null) onChromeReveal.run();
        }

        @Override
        public boolean onBackPressed() {
            if (overlay.getParent() == null) return false;
            if (panelVisible || panelContainer.getVisibility() == View.VISIBLE) {
                hidePanelSheet(true);
            } else {
                // Every edit is persisted as it happens. Back only leaves the editor; it never
                // discards a long customization session.
                close();
            }
            return true;
        }

        // -- agent probe -------------------------------------------------------
        // Debug-only, driven by the file command channel; see AgentCommandChannel. Every method
        // here only reads geometry or drives the editor through its own existing entry points, so
        // nothing here can leave a state a tap could not reach.

        @Override
        public boolean agentSelect(String name) {
            return agentSelect(name, true);
        }

        @Override
        public boolean agentSelect(String name, boolean revealPanel) {
            if (name == null) return false;
            String element = AGENT_ELEMENTS.get(name.trim().toLowerCase(java.util.Locale.ROOT));
            if (element == null) return false;
            if (overlay.getParent() == null) return false;
            selectElement(Element.valueOf(element), revealPanel);
            return true;
        }

        @Override
        public String selectedName() {
            return agentNameFor(selected);
        }

        @Override
        public boolean isCardMode() {
            return cardMode;
        }

        @Override
        public void agentClose() {
            if (overlay.getParent() == null) return;
            close();
        }

        @Override
        public boolean agentAction(String action, String argument) {
            if (overlay.getParent() == null || action == null) return false;
            String value = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
            switch (action.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "back":
                    return value.isEmpty() && onBackPressed();
                case "demo":
                    if (cardMode || enableDemoData == null || disableDemoData == null) return false;
                    if (!"live".equals(value) && !"synthetic".equals(value)) return false;
                    if (demoActive != "synthetic".equals(value)) toggleDemo();
                    return true;
                case "tab":
                    if (cardMode) return false;
                    switch (value) {
                        case "style": selectTextTab(TAB_STYLE); return true;
                        case "animation": selectTextTab(TAB_ANIMATION); return true;
                        case "effects": selectTextTab(TAB_EFFECTS); return true;
                        default: return false;
                    }
                case "reset":
                    if (!"all".equals(value)) return false;
                    resetLayoutToDefaults();
                    return true;
                default: return false;
            }
        }

        @Override
        public void agentReport(LayoutProbeReport report) {
            if (report == null) return;
            report.flag("card_mode", cardMode);
            report.flag("side_sheet", sideSheet);
            report.flag("demo_active", demoActive);
            report.text("text_tab", textTab == TAB_ANIMATION ? "animation"
                    : textTab == TAB_EFFECTS ? "effects" : "style");
            report.text("selected", agentNameFor(selected));
            report.number("z.overlay", Math.round(overlay.getZ()));
            report.rect("toolbar.done", screenRectOf(toolbarDone));
            report.rect("toolbar.reset", screenRectOf(toolbarReset));
            report.rect("toolbar.demo", screenRectOf(toolbarDemo));
            report.rect("capture.artwork", screenRectOf(artCapture));
            report.rect("capture.track_text", screenRectOf(trackTextCapture));
            report.rect("capture.dock", screenRectOf(dockCapture));
            report.rect("capture.skip", screenRectOf(skipCapture));
            report.rect("capture.follow", screenRectOf(followCapture));
            // The drawn line is the center of the 44dp touch band, not its top edge.
            report.rect("focus_line", screenRectOf(focusHandle));
            // Only while it can actually be seen: INVISIBLE is how the panel is parked between
            // reveals, and then there is no panel on screen to argue about.
            if (panelContainer.getVisibility() == View.VISIBLE) {
                report.rect("sheet", screenRectOf(panelContainer));
            }
            report.rect("card", screenRectOf(cardCapture));
            report.rect("card_caption", screenRectOf(cardCaption));
        }

        /** The agent-facing name of an element, which is also what a probe reports as selected. */
        private static String agentNameFor(Element element) {
            for (java.util.Map.Entry<String, String> entry : AGENT_ELEMENTS.entrySet()) {
                if (entry.getValue().equals(element.name())) return entry.getKey();
            }
            return "";
        }

        // -- top bar --------------------------------------------------------

        private void onResetClicked() {
            PanelDialog confirm = new PanelDialog(activity,
                    s("reset_confirm_title", "Reset layout?"));
            confirm.paragraph(s("reset_confirm_message",
                    "Reset the layout editor changes to their defaults?"));
            confirm.primary(s("reset", "Reset"), this::resetLayoutToDefaults);
            confirm.secondary(s("cancel", "Cancel"), null);
            confirm.show();
        }

        private void resetLayoutToDefaults() {
            for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                restoreTyped(writer, setting, setting.defaultValue);
            }
            cardConfigDirty = true;
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                afterNextLayout(() -> {
                    refreshArtwork();
                    refreshTrackText();
                    repaintFocusHandle();
                    refreshSkipChip();
                    refreshFollowChip();
                    refreshDock();
                    evaluateToolbarPlacement();
                    selectElement(selected);
                });
            });
        }

        /** Small round icon button (matching the chrome cluster's own icon style). */
        private ImageView iconButton(ActionIconDrawable.Kind kind, int color, String description,
                Runnable onClick) {
            float density = activity.getResources().getDisplayMetrics().density;
            ImageView button = new ImageView(activity);
            button.setImageDrawable(new ActionIconDrawable(kind, color, density));
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(dp(12), dp(12), dp(12), dp(12));
            button.setBackground(NativeIconButtons.createRoundButtonBackground());
            button.setContentDescription(description);
            button.setClickable(true);
            button.setFocusable(true);
            NativeIconButtons.applyPressScale(button);
            button.setOnClickListener(v -> onClick.run());
            return button;
        }

        // -- artwork element --------------------------------------------------

        /** Rebuilds the capture outline + resize handle anchored to whichever art frame is
         *  currently visible - called at startup and again after anything that could change
         *  *which* real view is showing (a position change moves the artwork to a different
         *  frame object entirely: top/bottom/side are three separate views). */
        private void refreshArtwork() {
            artLayer.removeAllViews();
            dropBindingsIn(artLayer);
            View frame = artFrameSupplier == null ? null : artFrameSupplier.get();
            if (frame == null || frame.getVisibility() != View.VISIBLE
                    || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                artCapture = null;
                artHandle = null;
                int sizePx = dp(Math.min(TrackInfoReadoutController.READOUT_MAX_ART_DP,
                        safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP)));
                // Editor preview is intentionally top-mounted. It remains selectable even when
                // the saved track-info mode is Off; changing the mode in the panel still writes
                // the user's real preference, while this virtual frame never disappears.
                int[] pos = new int[] { dp(16), toolbarBottomPx() + dp(12) };
                View capture = new View(activity);
                GradientDrawable outline = new GradientDrawable();
                outline.setStroke(dp(2), ACCENT_COLOR);
                outline.setCornerRadius(dp(safeGet(Settings.TRACK_INFO_ART_RADIUS)));
                capture.setBackground(outline);
                makeSelectableTarget(capture, Element.ARTWORK);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP | Gravity.START);
                lp.leftMargin = pos[0];
                lp.topMargin = pos[1];
                artLayer.addView(capture, lp);
                artCapture = capture;
                // This is an editor-only virtual target for TRACK_INFO_POSITION=Off. Do not bind
                // it to a null source: capture sync quite correctly hides null-source bindings,
                // which used to make Artwork impossible to select in the Off state.
                paintCapture(artCapture, selected == Element.ARTWORK);
                evaluateToolbarPlacement();
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            int radiusDp = safeGet(Settings.TRACK_INFO_ART_RADIUS);

            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(radiusDp));
            capture.setBackground(outline);
            makeSelectableTarget(capture, Element.ARTWORK);
            FrameLayout.LayoutParams captureLp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            captureLp.leftMargin = pos[0];
            captureLp.topMargin = pos[1];
            artLayer.addView(capture, captureLp);
            artCapture = capture;
            installArtworkDrag(capture, captureLp);
            paintCapture(artCapture, selected == Element.ARTWORK);

            // An Apple-style curved corner grip - hugging the actual corner point rather than a
            // dot floating centered on top of it - covers a generous touch box for grabbability
            // while only painting the curved bracket itself.
            // Touch box extends a bit past the drawn bracket's vertex for grabbability
            // (HANDLE_SIZE_DP is the bracket's own arm length) - the vertex itself sits at the
            // real frame corner, with both arms drawn extending down-right from it.
            int handleSize = dp(GRIP_BOX_DP);
            int cornerOffset = handleSize - dp(GRIP_OUTSIDE_DP);
            View handle = new CornerGripView(activity, cornerOffset, dp(radiusDp));
            FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                    handleSize, handleSize, Gravity.TOP | Gravity.START);
            handleLp.leftMargin = pos[0] + frame.getWidth() - cornerOffset;
            handleLp.topMargin = pos[1] + frame.getHeight() - cornerOffset;
            handle.setVisibility(selected == Element.ARTWORK ? View.VISIBLE : View.GONE);
            artLayer.addView(handle, handleLp);
            artHandle = handle;
            bindCapture(capture, artFrameSupplier, handle, cornerOffset, Element.ARTWORK);
            installResizeDrag();
            evaluateToolbarPlacement();
        }

        /** Forgets bindings whose outline lived in a layer that was just cleared. */
        private void dropBindingsIn(ViewGroup layer) {
            for (int i = captureBindings.size() - 1; i >= 0; i--) {
                View capture = captureBindings.get(i).capture;
                if (capture == null || capture.getParent() == null || capture.getParent() == layer) {
                    captureBindings.remove(i);
                }
            }
        }

        /** Vertical drag on the artwork body itself live-translates it, snapping to Top or
         *  Bottom (whichever the release direction points at) once the drag clears touch slop -
         *  a real drag producing a preset result, not a tap-only chip. A drag that never clears
         *  slop is treated as a plain tap (select, don't move). */
        private void installArtworkDrag(View capture, FrameLayout.LayoutParams captureLp) {
            int slopPx = dp(4);
            float[] startY = new float[1];
            capture.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY[0] = event.getRawY();
                        draggingArtwork = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // The two-column cover has no Top/Bottom placement to drag between.
                        if (twoColumn) return true;
                        float dy = event.getRawY() - startY[0];
                        if (!draggingArtwork && Math.abs(dy) > slopPx) draggingArtwork = true;
                        if (draggingArtwork) capture.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = event.getRawY() - startY[0];
                        capture.setTranslationY(0f);
                        boolean wasDragging = draggingArtwork;
                        draggingArtwork = false;
                        if (wasDragging) {
                            put(Settings.TRACK_INFO_POSITION, dy < 0 ? "Top" : "Bottom", () -> {
                                refreshArtwork();
                                refreshTrackText();
                                selectElement(Element.ARTWORK, false);
                            });
                        } else {
                            selectElement(Element.ARTWORK);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }

        /** Drag on the bottom-right corner handle changes artwork size continuously - averaging
         *  both axes reads naturally for a corner handle (drag away from center to grow, toward
         *  it to shrink) regardless of whether the drag is mostly horizontal, vertical, or
         *  diagonal. Resizes the capture/outline immediately for responsive visual feedback (the
         *  real artwork follows a frame later via the normal preference-change pipeline once the
         *  write lands). */
        private void installResizeDrag() {
            int min = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.minValue;
            float density = Math.max(0.5f, activity.getResources().getDisplayMetrics().density);
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            int[] startSizeDp = new int[1];
            int[] maxDp = new int[1];
            int[] lastSizeDp = new int[1];
            // Nothing about this gesture is captured from the build closure any more. The old
            // version held the capture's and the handle's LayoutParams objects and the handle View
            // from whichever refreshArtwork() built them, and a concurrent rebuild - which
            // refreshArtwork() does with removeAllViews() + fresh Views - left the gesture writing
            // a detached, now-foreign LayoutParams instance onto a brand new capture. That both
            // crashed (a rebuild that bailed early nulled the views the drag still assumed) and
            // silently misplaced the outline, since the stale params carried the PREVIOUS layout's
            // margins. Re-reading the live views and their own params on every event means a
            // rebuild mid-drag is simply picked up, and a missing view ends the drag cleanly.
            View.OnTouchListener listener = (v, event) -> {
                try {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: {
                            startRawX[0] = event.getRawX();
                            startRawY[0] = event.getRawY();
                            maxDp[0] = Math.max(min, Math.min(
                                    Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.maxValue,
                                    artSizeMaxDp == null ? TrackInfoReadoutController.READOUT_MAX_ART_DP
                                            : artSizeMaxDp.getAsInt()));
                            startSizeDp[0] = currentArtSizeDp(min, maxDp[0], density);
                            lastSizeDp[0] = startSizeDp[0];
                            resizingArtwork = true;
                            return true;
                        }
                        case MotionEvent.ACTION_MOVE: {
                            if (!resizingArtwork) return true;
                            float dxDp = (event.getRawX() - startRawX[0]) / density;
                            float dyDp = (event.getRawY() - startRawY[0]) / density;
                            // The outline grows from its top-left, so moving the corner by the
                            // finger's diagonal travel keeps the grip under the finger. (An
                            // acceleration curve used to be applied here; it made the corner run
                            // ahead of the finger on anything but a tiny drag.)
                            float dragDp = (dxDp + dyDp) / 2f;
                            if (twoColumn) {
                                // The cover grows about the centre of its column, so its corner
                                // travels half the size change: double the drag to keep the grip
                                // under the finger. The real cover resizes live and the outline
                                // follows it through the capture sync.
                                int newSizeDp = clamp(Math.round(startSizeDp[0] + 2f * dragDp),
                                        min, maxDp[0]);
                                showValueBubble(newSizeDp + " dp", artCapture);
                                if (newSizeDp != lastSizeDp[0]) {
                                    lastSizeDp[0] = newSizeDp;
                                    // The largest size means "fill the column". Stored as the top
                                    // of the range, it still fills once the editor's band is gone
                                    // and the column has more room.
                                    int stored = newSizeDp >= maxDp[0]
                                            ? Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.maxValue : newSizeDp;
                                    writer.put(Settings.TRACK_INFO_ART_SIZE, "Custom");
                                    writer.put(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP, stored);
                                    if (applyPreferences != null) applyPreferences.run();
                                }
                                return true;
                            }
                            int newSizeDp = clamp(Math.round(startSizeDp[0] + dragDp), min, maxDp[0]);
                            applyResizePreview(dp(newSizeDp));
                            showValueBubble(newSizeDp + " dp", artCapture);
                            // Plain writes, not put(): the visual feedback here is entirely local,
                            // nothing reads the real frame back mid-drag, so there's no need to
                            // force an early re-apply - the real artwork frame catches up on its
                            // own via the normal preference-change pipeline.
                            writer.put(Settings.TRACK_INFO_ART_SIZE, "Custom");
                            writer.put(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP, newSizeDp);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            resizingArtwork = false;
                            hideValueBubble();
                            // Re-apply for real, then let the per-frame capture sync settle the
                            // outline onto wherever the artwork actually ended up - no rebuild
                            // needed, and no window where the outline shows the old size.
                            if (applyPreferences != null) overlay.post(applyPreferences);
                            selectElement(Element.ARTWORK, false);
                            return true;
                        }
                        default:
                            return false;
                    }
                } catch (Throwable t) {
                    resizingArtwork = false;
                    return true;
                }
            };
            View handle = artHandle;
            if (handle != null) handle.setOnTouchListener(listener);
        }

        /** Current artwork edge length in dp, preferring the live outline (so a drag resumed after
         *  another one continues from what is on screen) and falling back to the stored setting. */
        private int currentArtSizeDp(int min, int max, float density) {
            View capture = artCapture;
            if (capture != null && capture.getLayoutParams() != null
                    && capture.getLayoutParams().width > 0) {
                return clamp(Math.round(capture.getLayoutParams().width / density), min, max);
            }
            return clamp(safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP), min, max);
        }

        /** Resizes the outline (and its grip) in place for immediate feedback while dragging. */
        private void applyResizePreview(int newSizePx) {
            View capture = artCapture;
            if (capture == null || capture.getParent() == null) return;
            ViewGroup.LayoutParams raw = capture.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.width != newSizePx || lp.height != newSizePx) {
                lp.width = newSizePx;
                lp.height = newSizePx;
                capture.setLayoutParams(lp);
            }
            GradientDrawable updatedOutline = new GradientDrawable();
            updatedOutline.setStroke(dp(OUTLINE_SELECTED_DP), ACCENT_COLOR);
            updatedOutline.setCornerRadius(dp(safeGet(Settings.TRACK_INFO_ART_RADIUS)));
            capture.setBackground(updatedOutline);

            View handle = artHandle;
            if (handle == null || handle.getParent() == null) return;
            ViewGroup.LayoutParams rawHandle = handle.getLayoutParams();
            if (!(rawHandle instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams handleLp = (FrameLayout.LayoutParams) rawHandle;
            int cornerOffset = dp(GRIP_BOX_DP) - dp(GRIP_OUTSIDE_DP);
            handleLp.leftMargin = lp.leftMargin + newSizePx - cornerOffset;
            handleLp.topMargin = lp.topMargin + newSizePx - cornerOffset;
            handle.setLayoutParams(handleLp);
        }

        // -- track text element -------------------------------------------------

        /** Rebuilds the capture outline anchored to the real title/artist(/album) text stack -
         *  separate from the artwork image it used to be bundled with, so it has its own on-screen
         *  outline and can be tapped independently. Refreshed everywhere refreshArtwork() is,
         *  since a position write can move both frames together. */
        private void refreshTrackText() {
            trackTextLayer.removeAllViews();
            dropBindingsIn(trackTextLayer);
            View frame = trackTextFrameSupplier == null ? null : trackTextFrameSupplier.get();
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                // Keep a second selectable frame under the editor-only artwork preview when the
                // saved mode is Off. The real readout is intentionally not made visible merely
                // to support hit testing.
                int width = Math.max(dp(150), shellRoot.getWidth() - dp(32));
                int height = dp(64);
                View capture = new View(activity);
                GradientDrawable outline = new GradientDrawable();
                outline.setStroke(dp(2), ACCENT_COLOR);
                outline.setCornerRadius(dp(10));
                capture.setBackground(outline);
                makeSelectableTarget(capture, Element.TRACK_TEXT);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        width, height, Gravity.TOP | Gravity.START);
                lp.leftMargin = dp(16);
                lp.topMargin = toolbarBottomPx() + dp(12)
                        + dp(Math.min(TrackInfoReadoutController.READOUT_MAX_ART_DP,
                                safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP))) + dp(10);
                trackTextLayer.addView(capture, lp);
                trackTextCapture = capture;
                paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
                evaluateToolbarPlacement();
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(10));
            capture.setBackground(outline);
            makeSelectableTarget(capture, Element.TRACK_TEXT);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            trackTextLayer.addView(capture, lp);
            trackTextCapture = capture;
            int handleSize = dp(GRIP_BOX_DP);
            int cornerOffset = handleSize - dp(GRIP_OUTSIDE_DP);
            View handle = new CornerGripView(activity, cornerOffset, dp(10));
            FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                    handleSize, handleSize, Gravity.TOP | Gravity.START);
            handleLp.leftMargin = pos[0] + frame.getWidth() - cornerOffset;
            handleLp.topMargin = pos[1] + frame.getHeight() - cornerOffset;
            handle.setVisibility(selected == Element.TRACK_TEXT ? View.VISIBLE : View.GONE);
            trackTextLayer.addView(handle, handleLp);
            trackTextHandle = handle;
            bindCapture(capture, trackTextFrameSupplier, handle, cornerOffset, Element.TRACK_TEXT);
            installTrackTextResizeDrag(handle);
            paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
            evaluateToolbarPlacement();
        }

        /** Same corner-grip gesture as the artwork's, scaling the title/artist text instead. The
         *  real readout re-lays out from the preference write, and the outline follows it through
         *  the capture sync, so what is under the finger is always the real text size. */
        private void installTrackTextResizeDrag(View handle) {
            int min = Settings.TRACK_INFO_TEXT_SIZE_CUSTOM.minValue;
            int max = Settings.TRACK_INFO_TEXT_SIZE_CUSTOM.maxValue;
            float[] startRaw = new float[2];
            float[] startSize = new float[2];
            int[] startPercent = new int[1];
            int[] lastPercent = new int[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRaw[0] = event.getRawX();
                        startRaw[1] = event.getRawY();
                        View capture = trackTextCapture;
                        startSize[0] = capture == null ? 0f : capture.getWidth();
                        startSize[1] = capture == null ? 0f : capture.getHeight();
                        startPercent[0] = currentTrackTextSizePercent();
                        lastPercent[0] = startPercent[0];
                        resizingTrackText = true;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        // Text scales about its top-left, so the size that puts the corner under
                        // the finger is the start size times how far the corner moved relative
                        // to the block's own width and height.
                        float w = Math.max(dp(40), startSize[0]);
                        float h = Math.max(dp(20), startSize[1]);
                        float ratio = ((w + event.getRawX() - startRaw[0]) / w
                                + (h + event.getRawY() - startRaw[1]) / h) / 2f;
                        int percent = clamp(Math.round(startPercent[0] * Math.max(0.1f, ratio)),
                                min, max);
                        if (percent != lastPercent[0]) {
                            lastPercent[0] = percent;
                            writer.put(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE, false);
                            writer.put(Settings.TRACK_INFO_TEXT_SIZE, "Custom");
                            writer.put(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, percent);
                            if (applyPreferences != null) applyPreferences.run();
                        }
                        showValueBubble(percent + "%", trackTextCapture);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        resizingTrackText = false;
                        hideValueBubble();
                        selectElement(Element.TRACK_TEXT, false);
                        return true;
                    default:
                        return false;
                }
            });
        }

        private int currentTrackTextSizePercent() {
            String mode = store.get(Settings.TRACK_INFO_TEXT_SIZE);
            if ("Small".equals(mode)) return 85;
            if ("Large".equals(mode)) return 120;
            if ("XLarge".equals(mode)) return 145;
            if ("Custom".equals(mode)) return safeGet(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM);
            return 100;
        }

        // -- lyrics-text element -----------------------------------------------

        /** Transparent layer sized to focusArea's real on-screen rect. A tap that lands on a real,
         *  currently-mounted lyric line selects TEXT; a tap on blank space within that same rect
         *  (between/around lines) falls through to select BACKGROUND instead, matching how a tap
         *  outside every capturable element already does. A drag past touch slop forwards the
         *  gesture to the real {@code focusArea} (the frame wrapping the live lyrics ScrollView)
         *  so the actual lyrics keep scrolling while the editor is open - the overlay's own
         *  clickable background would otherwise swallow every touch over the lyrics before the
         *  real ScrollView ever saw them. The forward starts only once a drag is confirmed (not
         *  from the initial down) so a plain tap can never leak through as a real tap-seek on the
         *  row underneath.
         *
         *  <p>Sized to focusArea rather than the whole overlay because the artwork/track-text
         *  readouts float *over* the same lyrics region in Top/Bottom/Header placements - a
         *  full-bleed layer would sit above (and swallow touches meant for) those captures, which
         *  are added earlier and would otherwise lose that overlap by z-order alone. A touch
         *  landing on the live artwork or track-text rect is declined outright (returns false on
         *  ACTION_DOWN) so it falls through to whatever real view is underneath instead. */
        private View textTapLayer;

        private void buildTextTapLayer() {
            View layer = new View(activity);
            textTapLayer = layer;
            makeSelectableTarget(layer, Element.TEXT);
            int[] pos = relativePosition(focusArea, shellRoot);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), Math.max(0, focusArea.getHeight()),
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            overlay.addView(layer, lp);
            // Kept on focusArea's live rect too: this layer decides which taps reach the real
            // lyrics, so a stale rect here means taps landing on visible lyrics do nothing.
            bindCapture(layer, () -> focusArea);

            // Drawn separately from the touch layer above (which still spans the whole focusArea
            // rect, so drags/taps outside any real line keep working) - one outline per currently
            // mounted line, hugging just its text content, rather than a single box covering the
            // whole lyrics column including the blank centering padding above/below it. Not
            // clickable, so it never steals the touches `layer` handles underneath it.
            RowOutlinesView outlines = new RowOutlinesView(activity, mountedRowsHostSupplier,
                    artFrameSupplier, trackTextFrameSupplier, chromeClusterSupplier);
            FrameLayout.LayoutParams outlinesLp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), Math.max(0, focusArea.getHeight()),
                    Gravity.TOP | Gravity.START);
            outlinesLp.leftMargin = pos[0];
            outlinesLp.topMargin = pos[1];
            overlay.addView(outlines, outlinesLp);
            textOutline = outlines;
            outlines.setSelectedState(selected == Element.TEXT);

            int slopPx = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            boolean[] dragging = new boolean[1];
            MotionEvent[] pendingDown = new MotionEvent[1];
            int[] pinchSize = new int[1];
            android.view.ScaleGestureDetector pinchDetector = new android.view.ScaleGestureDetector(
                    activity, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                    pinchSize[0] = currentLyricsTextSizePercent();
                    return true;
                }

                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    pinchSize[0] = clamp(Math.round(pinchSize[0] * detector.getScaleFactor()),
                            Settings.LYRICS_TEXT_SIZE_CUSTOM.minValue,
                            Settings.LYRICS_TEXT_SIZE_CUSTOM.maxValue);
                    writer.put(Settings.LYRICS_TEXT_SIZE, "custom");
                    writer.put(Settings.LYRICS_TEXT_SIZE_CUSTOM, pinchSize[0]);
                    showValueBubble(pinchSize[0] + "%", null);
                    return true;
                }

                @Override
                public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                    hideValueBubble();
                    // The size just pinched lives on the Style tab: show it there.
                    textTab = TAB_STYLE;
                    if (selected == Element.TEXT) selectElement(Element.TEXT, false);
                }
            });
            layer.setOnTouchListener((v, event) -> {
                pinchDetector.onTouchEvent(event);
                if (event.getPointerCount() >= 2 || pinchDetector.isInProgress()) {
                    // A second finger just landed (or a pinch is already underway) - hand the
                    // whole gesture to the zoom detector above and don't also treat it as a
                    // single-finger tap/drag/select once the pointer count drops back to one on
                    // the way up.
                    recycleQuietly(pendingDown[0]);
                    pendingDown[0] = null;
                    dragging[0] = false;
                    draggingFocusArea = false;
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (withinCapturedFrame(event.getRawX(), event.getRawY())) return false;
                        startRawX[0] = event.getRawX();
                        startRawY[0] = event.getRawY();
                        dragging[0] = false;
                        draggingFocusArea = false;
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = MotionEvent.obtain(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - startRawX[0];
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.hypot(dx, dy) > slopPx) {
                            dragging[0] = true;
                            draggingFocusArea = true;
                            if (pendingDown[0] != null) forwardToFocusArea(pendingDown[0]);
                            recycleQuietly(pendingDown[0]);
                            pendingDown[0] = null;
                        }
                        if (dragging[0]) forwardToFocusArea(event);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging[0]) {
                            forwardToFocusArea(event);
                        } else if (hitsAnyRow(event.getRawX(), event.getRawY())) {
                            selectElement(Element.TEXT);
                        } else {
                            selectElement(Element.BACKGROUND);
                        }
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        draggingFocusArea = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) forwardToFocusArea(event);
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        draggingFocusArea = false;
                        return true;
                    default:
                        return false;
                }
            });
        }

        /** True when the point lands on a currently-mounted lyric line's real on-screen bounds -
         *  blank space between/around lines (still inside focusArea's rect) misses every child and
         *  returns false, so the caller can fall through to selecting Background instead. Only
         *  mounted rows (a virtualized window) have live views at any moment, same as any other
         *  on-screen hit test in this file. */
        private boolean hitsAnyRow(float rawX, float rawY) {
            ViewGroup host = mountedRowsHostSupplier == null ? null : mountedRowsHostSupplier.get();
            if (host == null) return false;
            for (int i = 0; i < host.getChildCount(); i++) {
                if (hitsRowContent(host.getChildAt(i), rawX, rawY)) return true;
            }
            return false;
        }

        /** Like {@link #hitsView} but insets the row's own line-spacing padding out of the test
         *  first - each mounted row carries its vertical gap to the next line as padding on
         *  itself (see LyricsRowViewFactory), so a plain full-bounds hit test made the padding
         *  strip between every pair of lines register as "hit a row" too, leaving no blank space
         *  anywhere in the visible list to tap through to Background. */
        private static boolean hitsRowContent(View row, float rawX, float rawY) {
            if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0) return false;
            int[] loc = new int[2];
            row.getLocationOnScreen(loc);
            float left = loc[0] + row.getPaddingLeft();
            float top = loc[1] + row.getPaddingTop();
            float right = loc[0] + row.getWidth() - row.getPaddingRight();
            float bottom = loc[1] + row.getHeight() - row.getPaddingBottom();
            if (right <= left || bottom <= top) {
                // No padding at all (or it consumed the whole row) - fall back to the full bounds
                // rather than reporting every tap on this row as a miss.
                return hitsView(row, rawX, rawY);
            }
            return rawX >= left && rawX < right && rawY >= top && rawY < bottom;
        }

        /** Re-targets a copy of {@code event} into focusArea's local coordinate space and
         *  dispatches it directly - focusArea and this overlay are siblings in shellRoot, not
         *  parent/child, so the real ScrollView inside it never receives these touches otherwise. */
        private void forwardToFocusArea(MotionEvent event) {
            int[] pos = relativePosition(focusArea, shellRoot);
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(-pos[0], -pos[1]);
            try {
                focusArea.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }

        private static void recycleQuietly(MotionEvent event) {
            if (event != null) event.recycle();
        }

        /** True when the point hits the live artwork capture outline/handle or the track-text
         *  capture outline - queried against their real current geometry (kept in sync by
         *  refreshArtwork()/refreshTrackText()) rather than a cached rect, so it never goes stale
         *  after a position/size change. */
        private boolean withinCapturedFrame(float rawX, float rawY) {
            // The editor's lyrics touch plane can cover the whole focus area. Keep real chrome
            // controls (settings long-press, liked songs, translation and reading buttons)
            // outside that plane so their click and long-click gestures reach the source views.
            // The options panel is another real interactive child below this touch plane; excluding
            // its current bounds is what lets its header and every option row receive the gesture.
            View chrome = chromeClusterSupplier == null ? null : chromeClusterSupplier.get();
            return hitsView(artCapture, rawX, rawY) || hitsView(artHandle, rawX, rawY)
                    || hitsView(trackTextCapture, rawX, rawY)
                    || hitsView(chrome, rawX, rawY)
                    || hitsView(panelContainer, rawX, rawY);
        }

        private static boolean hitsView(View view, float rawX, float rawY) {
            if (view == null || view.getVisibility() != View.VISIBLE) return false;
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX < loc[0] + view.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + view.getHeight();
        }

        // -- focus-point element ----------------------------------------------

        // The visible line is thin (2dp), but its touch target is a full 44dp-tall band centered
        // on it - a 2dp-tall touch target is nearly impossible to grab reliably on a real screen.
        private static final int FOCUS_TOUCH_HEIGHT_DP = 44;

        private void buildFocusHandle() {
            View line = new FocusLineView(activity);
            makeSelectableTarget(line, Element.FOCUS);
            int touchHeight = dp(FOCUS_TOUCH_HEIGHT_DP);
            // Width/left come from focusArea's own real rect, not the full screen - in two-column
            // landscape the lyrics text frame is only part of the width (the other column is
            // artwork), and a full-width line there reads as misaligned, floating over artwork it
            // has nothing to do with. repaintFocusHandle() re-reads both on every call, same as
            // the vertical position, so this still tracks correctly if that rect ever changes.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), touchHeight, Gravity.TOP | Gravity.START);
            overlay.addView(line, lp);
            focusHandle = line;
            repaintFocusHandle();

            float[] startRawY = new float[1];
            int[] startTopMargin = new int[1];
            boolean[] moved = new boolean[1];
            int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            line.setOnTouchListener((v, event) -> {
                int[] pos = relativePosition(focusArea, shellRoot);
                int areaHeight = Math.max(1, focusArea.getHeight());
                int half = touchHeight / 2;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTopMargin[0] = lp.topMargin;
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!moved[0] && Math.abs(event.getRawY() - startRawY[0]) < slop) return true;
                        moved[0] = true;
                        draggingFocus = true;
                        int newTop = clamp(startTopMargin[0]
                                + Math.round(event.getRawY() - startRawY[0]),
                                pos[1] - half, pos[1] + areaHeight - half);
                        lp.topMargin = newTop;
                        line.setLayoutParams(lp);
                        // Percent is measured from the visual line's center, not the touch band's
                        // top edge, so it matches what repaintFocusHandle() draws back later.
                        int percent = clamp(Math.round(100f * (newTop + half - pos[1]) / areaHeight), 0, 100);
                        // Plain writes: the line's own position is driven directly by the touch
                        // delta above, not read back from real state, so no forced re-apply needed.
                        writer.put(Settings.LYRICS_FOCUS_POSITION, "Custom");
                        writer.put(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT, percent);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        draggingFocus = false;
                        selectElement(Element.FOCUS, !moved[0]);
                        return true;
                    default:
                        return false;
                }
            });
        }

        private void repaintFocusHandle() {
            if (focusHandle == null || focusArea == null) return;
            int[] pos = relativePosition(focusArea, shellRoot);
            int areaHeight = Math.max(1, focusArea.getHeight());
            float fraction = currentFocusFraction();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusHandle.getLayoutParams();
            int width = Math.max(0, focusArea.getWidth());
            int top = pos[1] + Math.round(areaHeight * fraction) - lp.height / 2;
            // Runs from the per-frame capture sync: write only a real change, or the layout
            // request it triggers would never let the traversal settle.
            if (lp.width == width && lp.leftMargin == pos[0] && lp.topMargin == top) return;
            lp.width = width;
            lp.leftMargin = pos[0];
            lp.topMargin = top;
            focusHandle.setLayoutParams(lp);
        }

        /** Keeps the per-row outline layer on the lyrics frame's live rect, like the tap layer. */
        private void syncRowOutlines() {
            if (textOutline == null || focusArea == null) return;
            ViewGroup.LayoutParams raw = textOutline.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int[] pos = relativePosition(focusArea, shellRoot);
            int width = Math.max(0, focusArea.getWidth());
            int height = Math.max(0, focusArea.getHeight());
            if (lp.width == width && lp.height == height
                    && lp.leftMargin == pos[0] && lp.topMargin == pos[1]) return;
            lp.width = width;
            lp.height = height;
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            textOutline.setLayoutParams(lp);
        }

        private float currentFocusFraction() {
            // The runtime owns the mapping (Auto, orientation, slide animation); drawing a copy of
            // it here put the line somewhere the lyrics never scrolled to.
            if (focusFraction != null) {
                try {
                    return clamp01((float) focusFraction.getAsDouble());
                } catch (Throwable ignored) {
                }
            }
            String pos = store.get(Settings.LYRICS_FOCUS_POSITION);
            if ("Top".equals(pos)) return 0.28f;
            if ("Bottom".equals(pos)) return 0.72f;
            if ("Custom".equals(pos)) {
                return clamp(safeGet(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT), 0, 100) / 100f;
            }
            return 0.5f;
        }

        // -- skip / follow / dock chip elements ---------------------------------

        /** Rebuilds the skip chip's capture outline anchored to its real on-screen rect. The chip
         *  itself is forced visible for the duration of the edit by {@code skipChip.showForEditing}
         *  (called once from start()), since it normally only exists on screen during a real skip
         *  gap. */
        private void refreshSkipChip() {
            skipCapture = refreshChipCapture(skipLayer, skipCapture,
                    skipChip == null ? null : skipChip.view, Element.SKIP);
        }

        /** Same as {@link #refreshSkipChip()} for the "Follow lyrics" jump-to-current chip, which
         *  is forced visible by {@code followChip.showForEditing} since it normally only shows
         *  once the user has scrolled away from the active line. */
        private void refreshFollowChip() {
            followCapture = refreshChipCapture(followLayer, followCapture,
                    followChip == null ? null : followChip.view, Element.FOLLOW);
        }

        /** Same shape again for the top controls cluster ("dock") - always on screen already
         *  (barring the fullscreen auto-hide timer, which onChromeReveal keeps at bay for the
         *  duration of the edit), so no force-visible step is needed here. */
        private void refreshDock() {
            dockCapture = refreshChipCapture(dockLayer, dockCapture,
                    chromeClusterSupplier, Element.DOCK);
        }

        /** Overlay size, falling back to the display metrics before the first layout pass. */
        private int overlayWidthPx() {
            return overlay != null && overlay.getWidth() > 0 ? overlay.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
        }

        private int overlayHeightPx() {
            return overlay != null && overlay.getHeight() > 0 ? overlay.getHeight()
                    : activity.getResources().getDisplayMetrics().heightPixels;
        }

        private int navigationInsetPx() {
            try {
                if (overlay != null) {
                    android.view.WindowInsets insets = overlay.getRootWindowInsets();
                    if (insets != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            return insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                        } else {
                            return insets.getSystemWindowInsetBottom();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (id > 0) return activity.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private List<SlotRect> candidateSlots(int rowWidth, int rowHeight) {
            int overlayW = overlay != null && overlay.getWidth() > 0 ? overlay.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            int overlayH = overlay != null && overlay.getHeight() > 0 ? overlay.getHeight()
                    : activity.getResources().getDisplayMetrics().heightPixels;

            int topY = statusBarHeightPx() + dp(8);
            int bottomY = overlayH - navigationInsetPx() - dp(12) - rowHeight;
            int startX = dp(12);
            int centerX = Math.max(0, (overlayW - rowWidth) / 2);

            return Arrays.asList(
                    new SlotRect(startX, topY, startX + rowWidth, topY + rowHeight),
                    new SlotRect(centerX, topY, centerX + rowWidth, topY + rowHeight),
                    new SlotRect(startX, bottomY, startX + rowWidth, bottomY + rowHeight),
                    new SlotRect(centerX, bottomY, centerX + rowWidth, bottomY + rowHeight)
            );
        }

        private void applySlotToRow(View row, int slotIndex, int rowWidth, int rowHeight) {
            int overlayW = overlay != null && overlay.getWidth() > 0 ? overlay.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            int overlayH = overlay != null && overlay.getHeight() > 0 ? overlay.getHeight()
                    : activity.getResources().getDisplayMetrics().heightPixels;

            int topY = statusBarHeightPx() + dp(8);
            int bottomY = overlayH - navigationInsetPx() - dp(12) - rowHeight;
            int startX = dp(12);
            int centerX = Math.max(0, (overlayW - rowWidth) / 2);

            ViewGroup.LayoutParams raw = row.getLayoutParams();
            FrameLayout.LayoutParams lp = (raw instanceof FrameLayout.LayoutParams)
                    ? (FrameLayout.LayoutParams) raw
                    : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = (slotIndex == 0 || slotIndex == 2) ? startX : centerX;
            lp.topMargin = (slotIndex == 0 || slotIndex == 1) ? topY : bottomY;
            row.setLayoutParams(lp);
        }

        private List<SlotRect> collectObstacles() {
            List<SlotRect> obstacles = new ArrayList<>(5);
            addObstacle(obstacles, artFrameSupplier == null ? null : artFrameSupplier.get());
            addObstacle(obstacles, trackTextFrameSupplier == null ? null : trackTextFrameSupplier.get());
            addObstacle(obstacles, chromeClusterSupplier == null ? null : chromeClusterSupplier.get());
            addObstacle(obstacles, skipChip == null || skipChip.view == null ? null : skipChip.view.get());
            addObstacle(obstacles, followChip == null || followChip.view == null ? null : followChip.view.get());
            return obstacles;
        }

        private void addObstacle(List<SlotRect> list, View view) {
            if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return;
            try {
                if (!isShownInTree(view)) return;
            } catch (Throwable ignored) {
            }
            int[] pos = relativePosition(view, overlay != null ? overlay : shellRoot);
            list.add(new SlotRect(pos[0], pos[1], pos[0] + view.getWidth(), pos[1] + view.getHeight()));
        }

        private void evaluateToolbarPlacement() {
            if (cardMode) return;
            if (isDragGestureActive()) return;
            if (backLayer == null || backLayer.getChildCount() == 0) return;
            View row = backLayer.getChildAt(0);
            if (row == null) return;
            int rowWidth = row.getWidth() > 0 ? row.getWidth() : row.getMeasuredWidth();
            if (rowWidth <= 0) {
                row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                rowWidth = row.getMeasuredWidth();
            }
            int rowHeight = dp(44);

            List<SlotRect> slots = candidateSlots(rowWidth, rowHeight);
            int chosenSlot = chooseToolbarSlot(slots, collectObstacles());
            if (chosenSlot < 0 || chosenSlot == currentToolbarSlot) return;
            currentToolbarSlot = chosenSlot;
            applySlotToRow(row, currentToolbarSlot, rowWidth, rowHeight);
            if (panelContainer != null && panelContainer.getLayoutParams() != null) {
                applySheetPlacement(sheetPlacement);
            }
        }

        /** Compact editor toolbar. Lyrics and card editors have separate menu entrances, so this
         * row does not duplicate cross-navigation between them. */
        private void refreshBackButton() {
            backLayer.removeAllViews();
            dropBindingsIn(backLayer);
            // The buttons below are rebuilt from scratch, so the previous ones are detached views
            // now; a probe must never read a rect for a button that is no longer on screen.
            toolbarDone = null;
            toolbarReset = null;
            toolbarDemo = null;
            int buttonSize = dp(44);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView save = iconButton(ActionIconDrawable.Kind.CHECK, TEXT_COLOR,
                    s("done", "Done"), this::close);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(buttonSize, buttonSize);
            row.addView(save, saveLp);
            ImageView reset = iconButton(ActionIconDrawable.Kind.REFRESH, TEXT_COLOR,
                    s("reset", "Reset"), this::onResetClicked);
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(buttonSize, buttonSize);
            resetLp.leftMargin = dp(6);
            row.addView(reset, resetLp);

            if (!cardMode && enableDemoData != null && disableDemoData != null) {
                TextView demoPill = actionPill(demoActive ? s("demo_lyrics", "Demo") : s("live_lyrics", "Live"),
                        this::toggleDemo);
                demoPill.setSingleLine(true);
                demoPill.setPadding(dp(10), 0, dp(10), 0);
                LinearLayout.LayoutParams demoLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, buttonSize);
                demoLp.leftMargin = dp(6);
                row.addView(demoPill, demoLp);
                toolbarDemo = demoPill;
            }
            toolbarDone = save;
            toolbarReset = reset;

            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int rowWidth = row.getMeasuredWidth();
            int rowHeight = dp(44);

            int chosenSlot;
            if (cardMode) {
                chosenSlot = 0;
            } else {
                List<SlotRect> slots = candidateSlots(rowWidth, rowHeight);
                chosenSlot = chooseToolbarSlot(slots, collectObstacles());
                if (chosenSlot < 0) chosenSlot = 0;
            }
            currentToolbarSlot = chosenSlot;
            applySlotToRow(row, currentToolbarSlot, rowWidth, rowHeight);
            backLayer.addView(row);

            if (panelContainer != null && panelContainer.getLayoutParams() != null) {
                applySheetPlacement(sheetPlacement);
            }
        }

        /** Bottom edge of the editor toolbar row in overlay coordinates: its real edge once laid
         *  out, else where refreshBackButton() places it. What the editor draws itself stays below. */
        private int toolbarBottomPx() {
            View row = toolbarDone == null ? null : (View) toolbarDone.getParent();
            if (row != null && row.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
                int h = row.getHeight() > 0 ? row.getHeight() : dp(44);
                return lp.topMargin + h;
            }
            return statusBarHeightPx() + dp(8) + dp(44);
        }

        /** The editor toolbar row's real rect in overlay coordinates, or null when it has not been
         *  laid out yet. The row is the one thing the options panel must never end up on top of. */
        private SlotRect toolbarRowRect() {
            if (backLayer == null || backLayer.getChildCount() == 0) return null;
            View row = backLayer.getChildAt(0);
            if (row == null || row.getWidth() <= 0 || row.getHeight() == 0) return null;
            int[] pos = relativePosition(row, overlay);
            if (pos[1] <= 0) return null;
            return new SlotRect(pos[0], pos[1], pos[0] + row.getWidth(), pos[1] + row.getHeight());
        }

        /** Where a top-anchored panel starts: below the toolbar row when the row is in the top
         *  band (the panel must never cover it), else the plain screen inset. */
        private int panelTopInsetPx() {
            SlotRect row = toolbarRowRect();
            if (row == null) return statusBarHeightPx() + dp(8) + dp(44) + dp(PANEL_GAP_DP);
            return row.bottom < overlayHeightPx() / 2
                    ? row.bottom + dp(PANEL_GAP_DP) : dp(PANEL_INSET_DP);
        }

        /** Where a bottom-anchored panel stops: above the toolbar row when the row is in the
         *  bottom band, else the screen inset below the navigation bar. */
        private int panelBottomInsetPx() {
            SlotRect row = toolbarRowRect();
            if (row != null && row.top > overlayHeightPx() / 2) {
                // A bottom margin is a distance from the overlay's bottom edge, not a y position.
                return Math.max(0, overlayHeightPx() - row.top) + dp(PANEL_GAP_DP);
            }
            return navigationInsetPx() + dp(PANEL_INSET_DP);
        }

        /** Vertical space the panel may use: the overlay less its own insets, less the band the
         *  toolbar row occupies (it only ever sits at the top or the bottom). */
        private int panelFreeHeightPx() {
            int overlayH = overlayHeightPx();
            if (overlayH <= 0) return 0;
            int inset = dp(PANEL_INSET_DP);
            int free = overlayH - inset * 2;
            SlotRect row = toolbarRowRect();
            if (row != null) {
                if (row.bottom < overlayH / 2) {
                    free = Math.min(free, overlayH - row.bottom - dp(PANEL_GAP_DP) - inset);
                } else if (row.top > overlayH / 2) {
                    free = Math.min(free, row.top - dp(PANEL_GAP_DP) - inset);
                }
            }
            return Math.max(0, free);
        }

        private int statusBarHeightPx() {
            try {
                int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) return activity.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
            return 0;
        }

        // -- now-playing card mode ---------------------------------------------

        /** True while the editor shows the now-playing card instead of the lyrics screen. */
        private boolean cardMode;
        private FrameLayout cardLayer;
        private com.eza.spicyex.lyrics.LiveLyricCardView cardPreview;
        private View cardCapture;
        private com.eza.spicyex.lyrics.LyricsDocument cardDocument;
        private com.eza.spicyex.lyrics.LyricsRenderConfig cardConfig;
        private boolean cardConfigDirty = true;
        private long cardStartMs;
        private long cardLastFrameMs;
        private int cardLastIndex = -1;
        private final Runnable cardFrame = this::stepCardPreview;
        /** The card panel's tabs: Text (size, weight, secondary line, overflow) and Animation. */
        private int cardTab;

        /** Layers that belong to the lyrics-screen elements; hidden while editing the card. */
        private View[] lyricsModeViews() {
            return new View[]{artLayer, trackTextLayer, focusHandle, textTapLayer, textOutline,
                    skipLayer, followLayer, dockLayer};
        }

        private void setCardMode(boolean enabled) {
            if (enabled == cardMode && (!enabled || cardLayer != null)) return;
            cardMode = enabled;
            for (View view : lyricsModeViews()) {
                if (view != null) view.setVisibility(enabled ? View.GONE : View.VISIBLE);
            }
            overlay.setBackgroundColor(enabled ? 0xD9000000 : 0x4D000000);
            if (enabled) {
                buildCardPreview();
                cardLayer.setVisibility(View.VISIBLE);
                cardConfigDirty = true;
                cardStartMs = android.os.SystemClock.uptimeMillis();
                cardLastFrameMs = 0L;
                cardLastIndex = -1;
                overlay.removeCallbacks(cardFrame);
                overlay.postOnAnimation(cardFrame);
                selectElement(Element.CARD, false);
            } else {
                overlay.removeCallbacks(cardFrame);
                if (cardLayer != null) cardLayer.setVisibility(View.GONE);
                selectElement(Element.TEXT, false);
                afterNextLayout(this::refreshAllCaptures);
            }
            // The card stage stays visible: the panel's height cap is tighter here, which
            // applySheetPlacement reads off cardMode.
            applySheetPlacement(sheetPlacement);
            hidePanelSheet(false);
            refreshBackButton();
        }

        /** A stand-in for Spotify's now-playing card: same dark rounded surface, the real
         *  {@link com.eza.spicyex.lyrics.LiveLyricCardView} inside, driven by the demo lyrics. */
        private void buildCardPreview() {
            if (cardLayer != null) return;
            cardLayer = new FrameLayout(activity);
            cardLayer.setClipChildren(false);
            // Its own stage, opaque: the lyrics screen (and its editor) no longer show through,
            // dimmed, behind the card being edited.
            cardLayer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF2B2B31, 0xFF16161A, 0xFF0E0E10}));
            cardLayer.setClickable(true);
            FrameLayout card = new FrameLayout(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2A2A2E);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            makeSelectableTarget(card, Element.CARD);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            cardPreview = new com.eza.spicyex.lyrics.LiveLyricCardView(activity);
            card.addView(cardPreview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // In the top part of the stage, under the editor's buttons, where the options panel
            // (capped to half the height) never reaches it; beside the panel when wide.
            android.content.res.Configuration screen = activity.getResources().getConfiguration();
            boolean sideSheet = landscape || screen.screenWidthDp >= 600;
            int width = overlay.getWidth() > 0 ? overlay.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            // Keep the stage clear of the panel it shares the screen with, insets included.
            int sheetWidth = sideSheet
                    ? Math.min(dp(PANEL_MAX_WIDTH_DP), width - dp(PANEL_INSET_DP) * 2)
                        + dp(PANEL_INSET_DP) * 2 : 0;
            int room = width - sheetWidth;
            int side = Math.max(dp(16), (room - dp(560)) / 2);
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    sideSheet ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.TOP);
            cardLp.leftMargin = side;
            cardLp.rightMargin = side + sheetWidth;
            if (!sideSheet) cardLp.topMargin = dp(150);
            cardLayer.addView(card, cardLp);
            cardCapture = card;
            TextView caption = text(s("card_caption", "Now playing card"), 13, 0x99FFFFFF, true);
            FrameLayout.LayoutParams captionLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP);
            captionLp.leftMargin = side + dp(4);
            captionLp.topMargin = Math.max(sideSheet ? dp(72) : dp(122), toolbarBottomPx() + dp(10));
            cardLayer.addView(caption, captionLp);
            cardCaption = caption;
            // Above the lyrics-screen layers, below the editor's own buttons and the panel.
            overlay.addView(cardLayer, overlay.indexOfChild(backLayer), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cardDocument = DemoLyricsContent.demoDocument();
            com.eza.spicyex.lyrics.LyricTimeline.applySyncedRows(cardDocument);
        }

        private void stepCardPreview() {
            if (!cardMode || overlay.getParent() == null || cardPreview == null || cardDocument == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = cardLastFrameMs == 0L ? 1f / 60f : Math.min(0.08f, (now - cardLastFrameMs) / 1000f);
            cardLastFrameMs = now;
            try {
                if (cardConfigDirty || cardConfig == null) {
                    cardConfigDirty = false;
                    cardConfig = com.eza.spicyex.lyrics.LyricsRenderConfig.read(activity,
                            com.eza.spicyex.SpotifyPlusConfig.from(activity));
                    cardPreview.applyConfig(cardConfig);
                    cardPreview.invalidateMountedContent();
                    cardLastIndex = -1;
                }
                long pos = (now - cardStartMs) % Math.max(1L, cardDocument.durationMs);
                java.util.List<com.eza.spicyex.lyrics.AppliedLine> lines = cardDocument.appliedLines;
                int index = com.eza.spicyex.lyrics.LyricTimeline.findPrimaryActiveRow(lines, pos);
                if (index < 0 || index >= lines.size()) {
                    if (cardLastIndex != -1) {
                        cardPreview.clear();
                        cardLastIndex = -1;
                    }
                } else {
                    boolean changed = index != cardLastIndex;
                    cardLastIndex = index;
                    cardPreview.renderLine(activity, lines.get(index), cardConfig, pos, dt,
                            cardDocument, (line, segment, full) -> "", changed);
                }
            } catch (Throwable t) {
                com.eza.spicyex.xposed.XpLog.log("[SpicyLayoutEditor] card preview failed: " + t);
            }
            overlay.postOnAnimation(cardFrame);
        }

        private void buildCardOptions() {
            addOption(tabBar(new String[]{s("tab_text", "Text"), s("tab_animation", "Animation")},
                    cardTab, index -> {
                        cardTab = index;
                        selectElement(Element.CARD);
                    }), matchWrap(12));
            if (cardTab == 0) {
                beginGroup(strings.setting(Settings.LIVE_CARD_TEXT_SIZE));
                addOption(customSliderRow(Settings.LIVE_CARD_TEXT_SIZE,
                        Settings.LIVE_CARD_TEXT_SIZE_CUSTOM, "custom", this::markCardDirty), matchWrap(12));
                cardChips(Settings.LIVE_CARD_WEIGHT, new String[]{"Regular", "Medium", "Bold"});
                cardChips(Settings.LIVE_CARD_SECONDARY_MODE,
                        new String[]{"Main only", "Transliteration", "Translation", "Both"});
                cardChips(Settings.LIVE_CARD_OVERFLOW, new String[]{"Wrap", "Scroll with lyric", "Clip"});
                if ("Scroll with lyric".equals(store.get(Settings.LIVE_CARD_OVERFLOW))) {
                    cardChips(Settings.LIVE_CARD_SCROLL_SCOPE, new String[]{"Grouped", "Individual lines"});
                }
            } else {
                cardChips(Settings.LIVE_CARD_ANIMATION, new String[]{"Minimal", "Karaoke fill", "Spotlight word"});
                cardChips(Settings.LIVE_CARD_GLOW, new String[]{"Off", "Word only", "Subtle line"});
                cardChips(Settings.LIVE_CARD_LINE_SYNC_FILL,
                        new String[]{"Top to bottom", "Left to right (block)", "Left to right (sentence)"});
                cardChips(Settings.LIVE_CARD_TRANSITION, new String[]{"Fade up", "Crossfade", "None"});
            }
            endGroup();
        }

        private void cardChips(Settings.Setting<String> setting, String[] values) {
            endGroup();
            beginGroup(strings.setting(setting));
            addOption(chipRow(setting, values, () -> {
                markCardDirty();
                selectElement(Element.CARD, false);
            }), matchWrap(4));
        }

        private void markCardDirty() {
            cardConfigDirty = true;
        }

        /** Rounded text button in the editor's top bar. */
        private TextView actionPill(String label, Runnable onClick) {
            TextView pill = new TextView(activity);
            pill.setText(label);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(200, 34, 34, 38));
            bg.setStroke(dp(1), Color.argb(70, 255, 255, 255));
            bg.setCornerRadius(dp(22));
            pill.setBackground(bg);
            pill.setClickable(true);
            NativeIconButtons.applyPressScale(pill);
            pill.setOnClickListener(v -> onClick.run());
            return pill;
        }

        /** Switches the preview between the demo lyrics and whatever is really playing. */
        private void toggleDemo() {
            if (demoActive) {
                demoActive = false;
                disableDemoData.run();
            } else {
                demoActive = true;
                enableDemoData.run();
            }
            refreshBackButton();
            afterNextLayout(this::refreshAllCaptures);
        }

        private FrameLayout.LayoutParams editorActionLp(int left, int top, int size) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = left;
            lp.topMargin = top;
            return lp;
        }

        private View refreshChipCapture(FrameLayout layer, View existing, Supplier<View> supplier,
                Element element) {
            if (existing != null) layer.removeView(existing);
            dropBindingsIn(layer);
            View chip = supplier == null ? null : supplier.get();
            if (chip == null || chip.getWidth() <= 0 || chip.getHeight() <= 0) return null;
            int[] pos = relativePosition(chip, shellRoot);
            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(20));
            capture.setBackground(outline);
            makeSelectableTarget(capture, element);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    chip.getWidth(), chip.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            layer.addView(capture, lp);
            bindCapture(capture, supplier);
            paintCapture(capture, selected == element);
            return capture;
        }

        // -- options card -------------------------------------------------------

        /** Which element is selected is now purely tap-driven on the real on-screen regions - no
         *  interactive chip strip here any more, just a label naming the current selection for
         *  orientation. */
        private void selectElement(Element element) {
            selectElement(element, true);
        }

        private void selectElement(Element element, boolean revealPanel) {
            // Option callbacks re-select the current element to rebuild rows that depend on the
            // value just changed. That must read as the row updating in place: keep the list's
            // scroll position and leave the panel exactly where it is.
            int previousScrollY = optionsScroll.getScrollY();
            boolean rebuildOnly = selected == element && panelVisible;
            // Decide the edge before the rows are built: a selection change is the only thing that
            // may place the panel, and a rebuild of the same element must never re-place it. The
            // reveal itself stays at the end of this method, so a relocated panel only becomes
            // visible once it has the new element's options in it.
            SheetPlacement wanted = rebuildOnly || !revealPanel
                    ? sheetPlacement : placementForSelection(element);
            if (wanted != sheetPlacement) {
                if (panelMovedByUser) {
                    // The user owns the panel's position now; the edge is only a leftover of where
                    // it happened to be, and the panel is refilled in place.
                    sheetPlacement = wanted;
                } else {
                    // Close it outright before moving it, so the jump to the other edge is never
                    // seen dragging across the element the tap just opened it for.
                    hidePanelSheet(false);
                    applySheetPlacement(wanted);
                }
            }
            selected = element;
            sheetTitle.setText(labelFor(element));
            optionsCard.removeAllViews();
            endGroup(); // the cards just removed above are gone; never append into a stale one
            repaintAllCaptures();

            switch (element) {
                case ARTWORK:
                    buildArtworkOptions();
                    break;
                case TRACK_TEXT:
                    buildTrackTextOptions();
                    break;
                case FOCUS:
                    buildFocusOptions();
                    break;
                case TEXT:
                    buildTextOptions();
                    break;
                case BACKGROUND:
                    buildBackgroundOptions();
                    break;
                case SKIP:
                    buildSkipOptions();
                    break;
                case FOLLOW:
                    buildFollowOptions();
                    break;
                case DOCK:
                    buildDockOptions();
                    break;
                case CARD:
                    buildCardOptions();
                    break;
            }
            if (rebuildOnly) {
                optionsScroll.scrollTo(0, previousScrollY);
                optionsScroll.post(() -> optionsScroll.scrollTo(0, previousScrollY));
            } else {
                optionsScroll.scrollTo(0, 0);
            }
            // A tap on an element opens its panel; drags and resizes select without covering the
            // preview with it (they pass revealPanel = false). A panel relocated above was closed
            // and is re-revealed here, against its new edge and with the new element's options.
            if (revealPanel && !panelVisible) showPanelSheet(true);
        }

        /** The edge the panel should be placed on for a given element: away from where that element
         *  actually is, so editing it never means editing it from behind the panel. */
        private SheetPlacement placementForSelection(Element element) {
            return SheetPlacement.choose(overlay.getWidth(), overlay.getHeight(),
                    targetRectFor(element), sideSheet, cardMode);
        }

        /**
         * Where the selected element actually is on screen, in overlay coordinates, or null when
         * there is nothing honest to measure.
         *
         * <p>Read from the live source views rather than from the editor's own capture outlines: the
         * outlines are the editor's tracing of the real thing and can be mid-rebuild (or virtual,
         * for an element with no real view at all), while the sources are what the user sees. An
         * element with no on-screen shape of its own - Lyrics, Background, and the card - has no
         * single rect to avoid, so it reports null and keeps the default edge.
         */
        private int[] targetRectFor(Element element) {
            View source;
            switch (element) {
                case ARTWORK:
                    source = artFrameSupplier == null ? null : artFrameSupplier.get();
                    break;
                case TRACK_TEXT:
                    source = trackTextFrameSupplier == null ? null : trackTextFrameSupplier.get();
                    break;
                case FOCUS:
                    source = focusHandle;
                    break;
                case SKIP:
                    source = skipChip == null || skipChip.view == null ? null : skipChip.view.get();
                    break;
                case FOLLOW:
                    source = followChip == null || followChip.view == null ? null : followChip.view.get();
                    break;
                case DOCK:
                    source = chromeClusterSupplier == null ? null : chromeClusterSupplier.get();
                    break;
                default:
                    return null;
            }
            if (source == null || !source.isShown() || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }
            int[] pos = relativePosition(source, shellRoot);
            return new int[]{pos[0], pos[1], pos[0] + source.getWidth(), pos[1] + source.getHeight()};
        }

        /** Live readout (e.g. "120 dp", "115%") floating above what is being resized, or centered
         *  near the top for screen-wide gestures like the lyrics pinch. */
        private void showValueBubble(String value, View anchor) {
            if (valueBubble == null) {
                TextView bubble = new TextView(activity);
                bubble.setTextColor(Color.WHITE);
                bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                bubble.setPadding(dp(12), dp(6), dp(12), dp(6));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(220, 20, 20, 24));
                bg.setCornerRadius(dp(14));
                bubble.setBackground(bg);
                bubble.setElevation(dp(8));
                overlay.addView(bubble, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START));
                valueBubble = bubble;
            }
            TextView bubble = valueBubble;
            if (!value.contentEquals(bubble.getText())) bubble.setText(value);
            bubble.bringToFront();
            bubble.setVisibility(View.VISIBLE);
            bubble.setAlpha(1f);
            bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = bubble.getMeasuredWidth();
            int h = bubble.getMeasuredHeight();
            int x;
            int y;
            if (anchor != null && anchor.getParent() != null && anchor.getWidth() > 0) {
                FrameLayout.LayoutParams alp = anchor.getLayoutParams() instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) anchor.getLayoutParams() : null;
                int ax = alp != null ? alp.leftMargin : relativePosition(anchor, overlay)[0];
                int ay = alp != null ? alp.topMargin : relativePosition(anchor, overlay)[1];
                int aw = alp != null && alp.width > 0 ? alp.width : anchor.getWidth();
                x = ax + aw / 2 - w / 2;
                y = ay - h - dp(10);
                if (y < dp(12)) y = ay + dp(10);
            } else {
                x = overlay.getWidth() / 2 - w / 2;
                y = dp(96);
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
            int nx = clamp(x, dp(8), Math.max(dp(8), overlay.getWidth() - w - dp(8)));
            if (lp.leftMargin != nx || lp.topMargin != y) {
                lp.leftMargin = nx;
                lp.topMargin = y;
                bubble.setLayoutParams(lp);
            }
        }

        private void hideValueBubble() {
            if (valueBubble == null) return;
            TextView bubble = valueBubble;
            bubble.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
        }

        // -- options panel -------------------------------------------------------

        private void showPanelSheet(boolean animate) {
            panelVisible = true;
            // Stop any fade-out still in flight first: its end action would park the panel as
            // INVISIBLE a frame after this reveal.
            panelContainer.animate().cancel();
            // Re-place on every reveal: the panel's width comes from the live overlay, and the
            // list's height budget from the edge the toolbar row currently occupies.
            applySheetPlacement(sheetPlacement);
            if (panelContainer.getVisibility() != View.VISIBLE) {
                panelContainer.setVisibility(View.VISIBLE);
                panelContainer.setAlpha(animate ? 0f : 1f);
                panelContainer.setScaleX(animate ? PANEL_HIDDEN_SCALE : 1f);
                panelContainer.setScaleY(animate ? PANEL_HIDDEN_SCALE : 1f);
            }
            if (!animate) {
                panelContainer.setAlpha(1f);
                panelContainer.setScaleX(1f);
                panelContainer.setScaleY(1f);
                return;
            }
            // A window appears; it does not slide in from an edge.
            panelContainer.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(PANEL_FADE_MS).start();
        }

        private void hidePanelSheet(boolean animate) {
            panelVisible = false;
            panelContainer.animate().cancel();
            if (!animate) {
                panelContainer.setAlpha(1f);
                panelContainer.setScaleX(1f);
                panelContainer.setScaleY(1f);
                panelContainer.setVisibility(View.INVISIBLE);
                return;
            }
            panelContainer.animate().alpha(0f).scaleX(PANEL_HIDDEN_SCALE).scaleY(PANEL_HIDDEN_SCALE)
                    .setDuration(PANEL_FADE_MS)
                    .withEndAction(() -> panelContainer.setVisibility(View.INVISIBLE)).start();
        }

        /** The panel's whole height budget: the smaller of its share of the overlay and the space
         *  the toolbar row leaves free. In card mode the stage has to stay visible, so it takes
         *  at most half. */
        private int panelMaxHeightPx() {
            int overlayH = overlayHeightPx();
            if (overlayH <= 0) return 0;
            float share = cardMode ? PANEL_MAX_HEIGHT_SHARE_CARD : PANEL_MAX_HEIGHT_SHARE;
            return Math.max(0, Math.min(Math.round(overlayH * share), panelFreeHeightPx()));
        }

        /**
         * Places the panel against one edge, sized to its content and never wider than the overlay
         * leaves room for. Gravity and margins do the positioning, so a change of edge is a plain
         * layout change with no measurement involved and nothing to settle afterwards. Once the
         * user has dragged the panel, only its size is touched from here - their position stands
         * for the rest of the session.
         */
        private void applySheetPlacement(SheetPlacement placement) {
            sheetPlacement = placement;
            if (panelLp == null) panelLp = new FrameLayout.LayoutParams(0, 0);
            int inset = dp(PANEL_INSET_DP);
            panelLp.width = Math.max(0,
                    Math.min(dp(PANEL_MAX_WIDTH_DP), overlayWidthPx() - inset * 2));
            panelLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            // The list gets what is left of the panel's height budget: the header above it is a
            // fixed-height row, so the panel as a whole stays inside the cap.
            optionsScroll.maxHeightPx = Math.max(0, panelMaxHeightPx() - dp(PANEL_HEADER_DP));
            if (panelMovedByUser) {
                if (panelContainer.getHeight() > 0) {
                    // A rotation or unfold can leave the user's position off screen: keep where they
                    // put it, but keep it inside the overlay.
                    movePanelTo(panelLp.leftMargin, panelLp.topMargin);
                }
            } else {
                panelLp.leftMargin = 0;
                panelLp.rightMargin = 0;
                panelLp.topMargin = 0;
                panelLp.bottomMargin = 0;
                switch (placement) {
                    case TOP:
                        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                        panelLp.topMargin = panelTopInsetPx();
                        break;
                    case START:
                        panelLp.gravity = Gravity.BOTTOM | Gravity.START;
                        panelLp.leftMargin = inset;
                        panelLp.bottomMargin = panelBottomInsetPx();
                        break;
                    case END:
                        panelLp.gravity = Gravity.BOTTOM | Gravity.END;
                        panelLp.rightMargin = inset;
                        panelLp.bottomMargin = panelBottomInsetPx();
                        break;
                    default:
                        panelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                        panelLp.bottomMargin = panelBottomInsetPx();
                        break;
                }
            }
            if (panelContainer.getLayoutParams() != null) panelContainer.setLayoutParams(panelLp);
        }

        /**
         * The panel's header is its only move handle. A drag there walks the panel with the finger
         * and nothing else moves it: the options list keeps its own scrolling and every slider,
         * chip, tab and dropdown keeps its gesture, because none of them sits under this listener
         * and nothing intercepts their events on the way down.
         */
        private void installPanelDrag() {
            int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            float[] downRaw = new float[2];
            int[] startPos = new int[2];
            boolean[] moved = {false};
            panelHeader.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRaw[0] = event.getRawX();
                        downRaw[1] = event.getRawY();
                        int[] pos = relativePosition(panelContainer, overlay);
                        startPos[0] = pos[0];
                        startPos[1] = pos[1];
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRaw[0];
                        float dy = event.getRawY() - downRaw[1];
                        if (!moved[0] && Math.hypot(dx, dy) < slop) return true;
                        moved[0] = true;
                        draggingPanel = true;
                        movePanelTo(startPos[0] + Math.round(dx), startPos[1] + Math.round(dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // A tap that never moved the panel is not a placement.
                        if (moved[0]) panelMovedByUser = true;
                        draggingPanel = false;
                        return true;
                    default:
                        return false;
                }
            });
        }

        /** Puts the panel at an absolute overlay position, fully inside the overlay and clear of
         *  the editor toolbar row. Absolute margins (not translation) so the panel's own rect is
         *  honest for the hit test, the probe and the next drag alike. */
        private void movePanelTo(int left, int top) {
            if (panelLp == null) return;
            int inset = dp(PANEL_INSET_DP);
            int width = panelContainer.getWidth() > 0 ? panelContainer.getWidth() : panelLp.width;
            int height = panelContainer.getHeight() > 0 ? panelContainer.getHeight() : dp(160);
            int x = clamp(left, inset, Math.max(inset, overlayWidthPx() - inset - width));
            int y = clamp(top, inset, Math.max(inset, overlayHeightPx() - inset - height));
            y = clearOfToolbarRow(x, width, height, y);
            if (panelLp.gravity == (Gravity.TOP | Gravity.START)
                    && panelLp.leftMargin == x && panelLp.topMargin == y) {
                return;
            }
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.leftMargin = x;
            panelLp.topMargin = y;
            panelLp.rightMargin = 0;
            panelLp.bottomMargin = 0;
            panelContainer.setLayoutParams(panelLp);
        }

        /** Keeps a moved panel off the editor toolbar row, which is the one thing it must never
         *  cover: where the two overlap horizontally, the panel is pushed to whichever side of
         *  the row is nearer. */
        private int clearOfToolbarRow(int left, int width, int height, int top) {
            SlotRect row = toolbarRowRect();
            if (row == null) return top;
            if (left + width <= row.left || left >= row.right) return top;
            int inset = dp(PANEL_INSET_DP);
            int maxTop = Math.max(inset, overlayHeightPx() - inset - height);
            int above = row.top - dp(PANEL_GAP_DP) - height;
            int below = row.bottom + dp(PANEL_GAP_DP);
            // Only a side the panel fits on counts: a toolbar at the top edge leaves no room above.
            boolean aboveFits = above >= inset;
            boolean belowFits = below <= maxTop;
            if (aboveFits && belowFits) {
                return Math.abs(top - above) <= Math.abs(top - below) ? above : below;
            }
            if (aboveFits) return above;
            return Math.min(below, maxTop);
        }

        /** Recolors every currently-built capture outline for the current selection - green when
         *  it is the selection, gray otherwise - without rebuilding any of them. Cheap: just
         *  mutates each capture's existing GradientDrawable stroke color. */
        private void repaintAllCaptures() {
            paintCapture(artCapture, selected == Element.ARTWORK);
            paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
            if (focusHandle instanceof FocusLineView) {
                ((FocusLineView) focusHandle).setSelectedState(selected == Element.FOCUS);
            }
            if (textOutline instanceof RowOutlinesView) {
                ((RowOutlinesView) textOutline).setSelectedState(selected == Element.TEXT);
            }
            paintCapture(skipCapture, selected == Element.SKIP);
            paintCapture(followCapture, selected == Element.FOLLOW);
            paintCapture(dockCapture, selected == Element.DOCK);
        }

        /** Fully rebuilds all capture outlines (not just recolor) so they re-align with real
         *  views that may have shifted after ambient controller view recreation. */
        private void refreshAllCaptures() {
            refreshArtwork();
            refreshTrackText();
            refreshSkipChip();
            refreshFollowChip();
            refreshDock();
            refreshBackButton();
        }

        /** Recolors a capture's outline stroke: {@link #ACCENT_COLOR} when it is the current
         *  selection, {@link #GRAY_IDLE_COLOR} otherwise - every capturable element shows an outline at
         *  all times now, not just the one currently selected, so it reads as one consistent
         *  selected/unselected visual language across the whole editor. */
        private static void paintCapture(View capture, boolean selected) {
            if (capture == null) return;
            android.graphics.drawable.Drawable background = capture.getBackground();
            if (!(background instanceof GradientDrawable)) return;
            ((GradientDrawable) background).setStroke(
                    dp(selected ? OUTLINE_SELECTED_DP : OUTLINE_IDLE_DP),
                    selected ? ACCENT_COLOR : GRAY_IDLE_COLOR);
        }

        private String labelFor(Element element) {
            switch (element) {
                case ARTWORK: return s("element_artwork", "Artwork");
                case TRACK_TEXT: return s("element_track_text", "Track text");
                case FOCUS: return s("element_focus", "Focus");
                case TEXT: return s("element_lyrics", "Lyrics");
                case SKIP:
                case FOLLOW: return s("element_chips", "Skip & Follow");
                case DOCK: return s("element_top_bar", "Top bar");
                case CARD: return s("element_card", "Now playing card");
                default: return s("element_background", "Background");
            }
        }

        /** Gives every editor outline a stable accessibility name and click action. The physical
         * touch listeners still own drag/resize gestures; this click path is for TalkBack, switch
         * access and device automation that activates the currently focused target. */
        private void makeSelectableTarget(View target, Element element) {
            target.setContentDescription(labelFor(element));
            target.setFocusable(true);
            target.setClickable(true);
            target.setOnClickListener(v -> selectElement(element));
        }

        private void buildArtworkOptions() {
            if (twoColumn) {
                // Two-column's left column is the readout's own column placement, so the only
                // position choice that means anything there is whether it shows at all: the four
                // floating placements have nowhere to go. One on/off row, same label and chip
                // style as every other row, no new strings: On writes "Top" (any non-Off value
                // shows the column, so the first stored one is as good as any), Off clears it.
                beginGroup(strings.setting(Settings.TRACK_INFO_POSITION));
                addOption(toggleRow(strings.setting(Settings.TRACK_INFO_POSITION),
                        !"Off".equals(safeGetString(Settings.TRACK_INFO_POSITION)),
                        (toggle, on) -> put(Settings.TRACK_INFO_POSITION,
                                on ? "Top" : "Off", () -> {
                                    refreshArtwork();
                                    refreshTrackText();
                                })), matchWrap(0));

                endGroup();
            } else {
                beginGroup(strings.setting(Settings.TRACK_INFO_POSITION));
                addOption(chipRow(Settings.TRACK_INFO_POSITION,
                        new String[]{"Off", "Top", "Bottom", "Header"},
                        () -> {
                            refreshArtwork();
                            refreshTrackText();
                            selectElement(Element.ARTWORK);
                        }), matchWrap(12));

                addOption(text(s("artwork_hint", "Drag the handle at its corner to resize; "
                        + "drag the artwork itself up/down to reposition."), 12, 0x80FFFFFF, false), matchWrap(12));

                endGroup();
            }
            beginGroup(strings.setting(Settings.TRACK_INFO_ART_RADIUS));
            addOption(dragRow(
                    Settings.TRACK_INFO_ART_RADIUS.minValue, Settings.TRACK_INFO_ART_RADIUS.maxValue,
                    safeGet(Settings.TRACK_INFO_ART_RADIUS), "dp", Settings.TRACK_INFO_ART_RADIUS.defaultValue,
                    value -> {
                        writer.put(Settings.TRACK_INFO_ART_RADIUS, value);
                        refreshArtwork();
                    }),
                    matchWrap(0));

            endGroup();
            beginGroup(strings.setting(Settings.PANEL_MEDIA_CONTROLS));
            addOption(chipRow(Settings.PANEL_MEDIA_CONTROLS,
                    new String[]{"Off", "Single tap", "Double tap"}, null), matchWrap(12));
            addOption(toggleRow(Settings.ADAPTIVE_LANDSCAPE_LAYOUT,
                    strings.setting(Settings.ADAPTIVE_LANDSCAPE_LAYOUT), null), matchWrap(0));

            endGroup();
            buildTrackTextOptions();
        }

        private void buildTrackTextOptions() {
            beginGroup(s("fields", "Fields"));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_TITLE,
                    strings.setting(Settings.TRACK_INFO_SHOW_TITLE),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(6));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_ARTIST,
                    strings.setting(Settings.TRACK_INFO_SHOW_ARTIST),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(6));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_ALBUM,
                    strings.setting(Settings.TRACK_INFO_SHOW_ALBUM),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(12));

            endGroup();
            // Alignment stacks the text vertically beside readout art, and the background fills
            // the readout's dock band. Two-column has its cover above the text and no dock, so both
            // controls are omitted there instead of being offered as no-ops.
            if (!twoColumn) {
                beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_ALIGN));
                addOption(chipRow(Settings.TRACK_INFO_TEXT_ALIGN,
                        new String[]{"Top", "Center", "Bottom"}, null), matchWrap(12));

                endGroup();
            }
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_SIZE));
            addOption(toggleRow(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                    strings.setting(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(8));
            if (!Boolean.TRUE.equals(store.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE))) {
                addOption(customSliderRow(Settings.TRACK_INFO_TEXT_SIZE,
                        Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, "Custom", null), matchWrap(12));
            }

            endGroup();
            if (!twoColumn) {
                beginGroup(strings.setting(Settings.TRACK_INFO_BACKGROUND));
                addOption(chipRow(Settings.TRACK_INFO_BACKGROUND,
                        new String[]{"Gradient", "Solid", "None"}, null), matchWrap(12));

                endGroup();
            }
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_OVERFLOW));
            addOption(chipRow(Settings.TRACK_INFO_TEXT_OVERFLOW,
                    new String[]{"Clip", "Wrap", "Scroll"}, null), matchWrap(0));
        }

        private void buildFocusOptions() {
            beginGroup(strings.setting(Settings.LYRICS_FOCUS_POSITION));
            addOption(chipRow(Settings.LYRICS_FOCUS_POSITION,
                    new String[]{"Auto", "Top", "Center", "Bottom"},
                    this::repaintFocusHandle), matchWrap(12));
            addOption(text(s("focus_hint", "Or drag the line across the lyrics directly."), 12,
                    0x80FFFFFF, false), matchWrap(0));
        }

        private static final int FILE_PICKER_REQUEST_CODE = 10234;

        /**
         * The lyrics sheet is split into tabs - Style, Animation, Effects - instead of one scroll
         * of ten option cards, which was hard to read and to find anything in. The tab survives
         * the in-place rebuilds option changes trigger.
         */
        private int textTab;
        private static final int TAB_STYLE = 0;
        private static final int TAB_ANIMATION = 1;
        private static final int TAB_EFFECTS = 2;

        private void buildTextOptions() {
            addOption(textTabs(), matchWrap(12));
            switch (textTab) {
                case TAB_ANIMATION:
                    buildAnimationOptions();
                    break;
                case TAB_EFFECTS:
                    buildTextEffectOptions();
                    break;
                default:
                    buildTextStyleOptions();
                    break;
            }
            endGroup();
        }

        /** One pill holding the three tabs; the current one is a white segment. */
        private View textTabs() {
            return tabBar(new String[]{s("tab_style", "Style"), s("tab_animation", "Animation"),
                    s("tab_effects", "Effects")}, textTab, this::selectTextTab);
        }

        private void selectTextTab(int index) {
            textTab = index;
            selectElement(Element.TEXT);
            optionsScroll.scrollTo(0, 0);
            optionsScroll.post(() -> optionsScroll.scrollTo(0, 0));
        }

        /** The panel's tab bar: one pill, the current tab a white segment; picking one rebuilds
         *  the options below it (through {@code onPick}) and scrolls them to the top. */
        private View tabBar(String[] labels, int current, java.util.function.IntConsumer onPick) {
            LinearLayout bar = new LinearLayout(activity);
            bar.setPadding(dp(3), dp(3), dp(3), dp(3));
            GradientDrawable barBg = new GradientDrawable();
            barBg.setCornerRadius(dp(20));
            barBg.setColor(0x1AFFFFFF);
            bar.setBackground(barBg);
            for (int i = 0; i < labels.length; i++) {
                TextView tab = text(labels[i], 14, i == current ? Color.BLACK : 0xCCFFFFFF, i == current);
                tab.setGravity(Gravity.CENTER);
                tab.setSingleLine(true);
                tab.setPadding(dp(8), dp(8), dp(8), dp(8));
                if (i == current) {
                    GradientDrawable on = new GradientDrawable();
                    on.setCornerRadius(dp(17));
                    on.setColor(Color.WHITE);
                    tab.setBackground(on);
                }
                final int index = i;
                tab.setOnClickListener(v -> {
                    if (current == index) return;
                    onPick.accept(index);
                    optionsScroll.scrollTo(0, 0);
                });
                bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            return bar;
        }

        /** Style: size, font, weight, line spacing. */
        private void buildTextStyleOptions() {
            addSectionLabel(Settings.LYRICS_TEXT_SIZE, 10);
            addOption(customSliderRow(Settings.LYRICS_TEXT_SIZE,
                    Settings.LYRICS_TEXT_SIZE_CUSTOM, "custom", null), matchWrap(8));
            addOption(toggleRow(Settings.LYRICS_ADAPTIVE_TEXT_SIZE,
                    strings.setting(Settings.LYRICS_ADAPTIVE_TEXT_SIZE), null), matchWrap(8));
            addOption(toggleRow(Settings.ADAPTIVE_SECTIONING,
                    strings.setting(Settings.ADAPTIVE_SECTIONING), null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.LYRICS_FONT, 10);
            addOption(fontChipRow(), matchWrap(8));
            if ("custom".equals(store.get(Settings.LYRICS_FONT))) {
                addOption(text(s("font_hint",
                        "Tap below to choose a font."),
                        12, 0x7AFFFFFF, false), matchWrap(6));
                addOption(customFontDropdown(), matchWrap(8));
                String summary = fontCoverageSummary(store.get(Settings.LYRICS_FONT_CUSTOM_PATH));
                if (summary != null && !summary.isEmpty()) {
                    addOption(text(summary, 12, 0x80FFFFFF, false), matchWrap(0));
                }
            }

            addDivider();
            addSectionLabel(Settings.LYRICS_WEIGHT, 10);
            addOption(chipRow(Settings.LYRICS_WEIGHT,
                    new String[]{"Regular", "Medium", "Bold"}, null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.LINE_SPACING, 10);
            addOption(customSliderRow(Settings.LINE_SPACING,
                    Settings.LINE_SPACING_CUSTOM, "custom", null), matchWrap(14));

            endGroup();
        }

        /** Effects: interlude icon, glow, line blur. */
        private void buildTextEffectOptions() {
            addSectionLabel(Settings.INTERLUDE_ICON, 10);
            addOption(chipRow(Settings.INTERLUDE_ICON,
                    new String[]{"dots", "note"}, null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.ENABLE_GLOW_BLUR, 10);
            addOption(toggleRow(Settings.ENABLE_GLOW_BLUR,
                    strings.setting(Settings.ENABLE_GLOW_BLUR), null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.ENABLE_LINE_BLUR, 10);
            int blur = "Off".equals(store.get(Settings.ENABLE_LINE_BLUR))
                    ? 0 : safeGet(Settings.LYRICS_BLUR_INTENSITY);
            addOption(text(s("blur_intensity_hint", "Off at 0%; increase to blur distant lines more."),
                    12, 0x7AFFFFFF, false), matchWrap(6));
            addOption(dragRow(0, Settings.LYRICS_BLUR_INTENSITY.maxValue, blur, "%", 0,
                    value -> {
                        writer.put(Settings.ENABLE_LINE_BLUR, value == 0 ? "Off" : "Heavy");
                        if (value > 0) writer.put(Settings.LYRICS_BLUR_INTENSITY, value);
                    }), matchWrap(14));
            endGroup();
        }

        /** Animation style and everything that depends on it, in the order they appear. */
        private void buildAnimationOptions() {
            beginGroup(strings.setting(Settings.ANIMATION_STYLE));
            addOption(chipRow(Settings.ANIMATION_STYLE,
                    new String[]{"Gradient wash", "Spotlight", "Apple Music"},
                    () -> selectElement(Element.TEXT)), matchWrap(8));
            addOption(toggleRow(Settings.LOAD_LIFT_ANIMATION,
                    strings.setting(Settings.LOAD_LIFT_ANIMATION), null), matchWrap(12));
            if (isAppleStyle()) {
                addOption(toggleRow(Settings.LINE_SLIDE_ANIMATION,
                        strings.setting(Settings.LINE_SLIDE_ANIMATION), null), matchWrap(6));
                addOption(toggleRow(Settings.APPLE_LIFT,
                        strings.setting(Settings.APPLE_LIFT), null), matchWrap(6));
                addOption(toggleRow(Settings.APPLE_FADE_PASSED_LINES,
                        strings.setting(Settings.APPLE_FADE_PASSED_LINES), null), matchWrap(12));
                addOption(text(strings.setting(Settings.APPLE_CASCADE_SPEED),
                        12, GROUP_TITLE_COLOR, true), matchWrap(8));
                addOption(settingSlider(Settings.APPLE_CASCADE_SPEED, "%", null), matchWrap(12));
                addOption(text(strings.setting(Settings.APPLE_SPRING_STRENGTH),
                        12, GROUP_TITLE_COLOR, true), matchWrap(8));
                addOption(settingSlider(Settings.APPLE_SPRING_STRENGTH, "%", null), matchWrap(12));
            }
            if (!isAppleStyle()) {
                addDivider();
                addSectionLabel(Settings.WORD_BOUNCE, 10);
                addOption(chipRow(Settings.WORD_BOUNCE,
                        new String[]{"Off", "Word/syllable synced only", "All synced rows"},
                        () -> selectElement(Element.TEXT)), matchWrap(8));
                if (!"Off".equals(store.get(Settings.WORD_BOUNCE))) {
                    addOption(chipRow(Settings.WORD_BOUNCE_STYLE,
                            new String[]{"Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift"},
                            () -> selectElement(Element.TEXT)), matchWrap(14));
                }

                addDivider();
                addSectionLabel(Settings.LINE_SYNC_FILL, 10);
                addOption(chipRow(Settings.LINE_SYNC_FILL,
                        new String[]{"Top to bottom", "Left to right (block)", "Left to right (sentence)"},
                        () -> selectElement(Element.TEXT)), matchWrap(0));
            }
            endGroup();
        }

        /** A smooth integer slider. The setting's default is marked, but never magnetically snaps. */
        private LinearLayout settingSlider(Settings.IntegerSetting setting, String unit,
                Runnable onChanged) {
            return dragRow(setting.minValue, setting.maxValue, safeGet(setting), unit,
                    setting.defaultValue, value -> {
                        if (onChanged == null) writer.put(setting, value);
                        else put(setting, value, onChanged);
                    });
        }

        private void addSectionLabel(Settings.Setting<?> setting, int bottomDp) {
            beginGroup(strings.setting(setting));
        }

        private void addDivider() {
            endGroup();
        }

        // -- options panel grouping ------------------------------------------
        //
        // The panel used to be one flat column: a 13sp label, a control, a hairline rule, a 13sp
        // label, a control, and so on for every setting an element has. Label and control text
        // were the same size, so nothing established what belonged to what, and with a rule
        // between every single row the whole card read as dense ruled paper rather than a set of
        // choices. Each setting now gets its own softly-tinted rounded card with a smaller, muted
        // title above larger, clearly tappable controls: the same options, one obvious visual unit
        // each, and no rules at all.

        /** Card the option builders are currently writing into; null means "straight to the card". */
        private LinearLayout optionsGroup;

        private void beginGroup(String title) {
            endGroup();
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(GROUP_RADIUS_DP));
            bg.setColor(GROUP_FILL_COLOR);
            box.setBackground(bg);
            box.setPadding(dp(14), dp(12), dp(14), dp(13));
            if (title != null && !title.isEmpty()) {
                TextView label = text(title, GROUP_TITLE_SP, GROUP_TITLE_COLOR, true);
                label.setLetterSpacing(0.02f);
                box.addView(label, matchWrap(10));
            }
            optionsCard.addView(box, matchWrap(10));
            optionsGroup = box;
        }

        private void endGroup() {
            optionsGroup = null;
        }

        /** Option-builder entry point: lands in the open group card, or the panel itself when a
         *  builder adds something before opening one. */
        private void addOption(View view, LinearLayout.LayoutParams lp) {
            (optionsGroup != null ? optionsGroup : optionsCard).addView(view, lp);
        }

        /** Single dropdown button that shows the current custom font selection. Tapping opens a
         *  PopupMenu with all system font families, a "Pick file..." option, and a
         *  "Type path..." option - consolidating the old system-font chips + folder + "+"
         *  into one compact control. */
        private View customFontDropdown() {
            String current = store.get(Settings.LYRICS_FONT_CUSTOM_PATH);
            String displayLabel = fontDisplayName(current);

            TextView button = chip(displayLabel);
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(0x22FFFFFF);
            bg.setStroke(dp(1), 0x33FFFFFF);
            button.setBackground(bg);
            button.setTextColor(TEXT_COLOR);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(button, btnLp);

            button.setOnClickListener(v -> openFontChooser(button));
            return row;
        }

        /** Resolves a display name for the current font path - shows the system family name
         *  directly, or the filename portion for a file path, or a placeholder if empty. */
        private String fontDisplayName(String path) {
            if (path == null || path.isEmpty()) {
                return s("font_no_selection", "Select font...");
            }
            if (!path.contains("/") && !path.contains("\\")) {
                return path;
            }
            // File path - show the filename
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = lastSep >= 0 ? path.substring(lastSep + 1) : path;
            if (name.length() > 28) name = name.substring(0, 25) + "...";
            return name;
        }

        /** Font chips. Spotify/Apple apply at once; Custom only opens the chooser, and the setting
         *  changes once a font is actually picked - backing out of the chooser (or the file
         *  picker) leaves the previous font in place instead of a "custom" with nothing behind it. */
        private LinearLayout fontChipRow() {
            String[] values = {"spotify", "apple", "custom"};
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            String current = store.get(Settings.LYRICS_FONT);
            TextView[] chips = new TextView[values.length];
            for (int i = 0; i < values.length; i++) {
                TextView chip = chip(strings.option((Settings.StringSetting) Settings.LYRICS_FONT, values[i]));
                chips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                row.addView(chip, lp);
                paintChip(chip, values[i].equals(current));
            }
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                TextView chip = chips[i];
                chip.setOnClickListener(v -> {
                    if ("custom".equals(value)) {
                        openFontChooser(chip);
                        return;
                    }
                    for (int j = 0; j < values.length; j++) paintChip(chips[j], values[j].equals(value));
                    put(Settings.LYRICS_FONT, value, () -> selectElement(Element.TEXT));
                });
            }
            return row;
        }

        private static final String[] SYSTEM_FONTS = {"sans-serif", "sans-serif-medium",
                "sans-serif-condensed", "serif", "monospace", "casual", "cursive"};

        /** System font families plus "Pick file...". Nothing is written until one is chosen. */
        private void openFontChooser(View anchor) {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, anchor);
            for (int i = 0; i < SYSTEM_FONTS.length; i++) {
                popup.getMenu().add(0, i, i, SYSTEM_FONTS[i]);
            }
            popup.getMenu().add(1, 100, 100, s("font_pick_file", "Pick file..."));
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 100) {
                    pickFontFile();
                } else if (item.getItemId() < SYSTEM_FONTS.length) {
                    commitCustomFont(SYSTEM_FONTS[item.getItemId()]);
                }
                return true;
            });
            popup.show();
        }

        private void commitCustomFont(String path) {
            if (path == null || path.isEmpty()) return;
            writer.put(Settings.LYRICS_FONT_CUSTOM_PATH, path);
            put(Settings.LYRICS_FONT, "custom", () -> selectElement(Element.TEXT));
        }

        /** System document picker for a .ttf/.otf. The result comes back through
         *  {@link ActivityResultBridge} (Spotify's own activity would otherwise receive it), and the
         *  file is copied into Spotify's private files: the picker's content URI grant does not
         *  outlive the process, a plain file does. */
        private void pickFontFile() {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf",
                    "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                    "application/font-sfnt", "application/octet-stream"});
            ActivityResultBridge.start(activity, intent, FILE_PICKER_REQUEST_CODE, data -> {
                android.net.Uri uri = data == null ? null : data.getData();
                if (uri == null) return;
                android.content.Context app = activity.getApplicationContext();
                new Thread(() -> {
                    String path = copyFontToPrivateStorage(app, uri);
                    overlay.post(() -> {
                        if (path == null) {
                            android.widget.Toast.makeText(activity, s("font_pick_failed",
                                    "Couldn't read that font file"), android.widget.Toast.LENGTH_SHORT).show();
                        } else if (overlay.getParent() != null) {
                            commitCustomFont(path);
                        } else {
                            // Settings save immediately, so a picker result remains authoritative
                            // even if the editor was closed while Android's document UI was open.
                            writer.put(Settings.LYRICS_FONT_CUSTOM_PATH, path);
                            writer.put(Settings.LYRICS_FONT, "custom");
                            cleanupCustomFontsExcept(path);
                        }
                    });
                }, "SpicyFontCopy").start();
            });
        }

        /** Copies the picked font next to Spotify's own files; null if it is not a usable font. */
        private static String copyFontToPrivateStorage(android.content.Context context, android.net.Uri uri) {
            String name = "font";
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                    name = cursor.getString(0);
                }
            } catch (Throwable ignored) {
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String ext = lower.endsWith(".otf") ? ".otf" : ".ttf";
            String base = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (base.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
            }
            java.io.File dir = new java.io.File(context.getFilesDir(), "spicyex_fonts");
            java.io.File out = new java.io.File(dir, base + "-" + System.currentTimeMillis() + ext);
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                try (java.io.InputStream in = context.getContentResolver().openInputStream(uri);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    if (in == null) return null;
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
                }
                android.graphics.Typeface.createFromFile(out);
                return out.getAbsolutePath();
            } catch (Throwable t) {
                out.delete();
                return null;
            }
        }

        /** Deletes copied editor fonts other than the path the completed session retained. Files
         * outside our private font directory (including system-family names) are never touched. */
        private void cleanupCustomFontsExcept(String keepPath) {
            java.io.File dir = new java.io.File(activity.getApplicationContext().getFilesDir(),
                    "spicyex_fonts");
            java.io.File[] files = dir.listFiles();
            if (files == null) return;
            for (java.io.File file : files) {
                if (keepPath == null || !file.getAbsolutePath().equals(keepPath)) file.delete();
            }
        }

        /** Mirrors PanelDialogs#reportFontCoverage's own resolution order (a real file on disk,
         *  else an installed/system font family name) so the same value gets the same verdict in
         *  either surface. */
        private String fontCoverageSummary(String path) {
            if (path == null || path.isEmpty()) return "";
            android.graphics.Typeface typeface = null;
            java.io.File file = new java.io.File(path);
            if (file.isFile()) {
                try {
                    typeface = android.graphics.Typeface.createFromFile(file);
                } catch (Throwable ignored) {
                }
            }
            if (typeface == null) typeface = android.graphics.Typeface.create(path, android.graphics.Typeface.NORMAL);
            java.util.List<String> missing = com.eza.spicyex.lyrics.LyricsFontValidator.missingScripts(typeface);
            return missing.isEmpty()
                    ? strings.get("settings_lyrics_font_check_all_covered", "Covers every supported language")
                    : strings.get("settings_lyrics_font_check_missing", "Falls back for") + ": "
                            + String.join(", ", missing);
        }

        private void buildBackgroundOptions() {
            beginGroup(strings.setting(Settings.BACKGROUND_STYLE));
            addOption(chipRow(Settings.BACKGROUND_STYLE,
                    new String[]{"Gradient", "Static texture", "Animated texture"},
                    () -> {
                        // Background style change can trigger ambient controller to recreate views
                        // in shellRoot, shifting geometry. Refresh captures after a delay so they
                        // re-align with the real views once the ambient layer has settled.
                        selectElement(Element.BACKGROUND);
                        overlay.postDelayed(this::refreshAllCaptures, 300);
                    }), matchWrap(12));

            if ("Animated texture".equals(store.get(Settings.BACKGROUND_STYLE))) {
                endGroup();
                beginGroup(strings.setting(Settings.BACKGROUND_RENDER_QUALITY));
                addOption(text(s("background_quality_hint", "Lower trades a softer/grainier "
                        + "look for less sustained GPU load."), 12, 0x80FFFFFF, false), matchWrap(4));
                addOption(settingSlider(Settings.BACKGROUND_RENDER_QUALITY, "%", null),
                        matchWrap(12));
            }

            endGroup();
            // Force-dark is one control: 0-100% intensity. The legacy boolean remains enabled
            // internally for compatibility, but is no longer exposed as a separate button. Zero
            // disables the effect; moving away from zero re-enables it, including for users whose
            // old standalone toggle was off when they entered this editor.
            beginGroup(strings.setting(Settings.FORCE_DARK_BACKGROUND));
            addOption(dragRow(Settings.EXTRA_DARK_BACKGROUND.minValue,
                    Settings.EXTRA_DARK_BACKGROUND.maxValue,
                    safeGet(Settings.EXTRA_DARK_BACKGROUND), "%",
                    Settings.EXTRA_DARK_BACKGROUND.defaultValue, value -> {
                        writer.put(Settings.EXTRA_DARK_BACKGROUND, value);
                        writer.put(Settings.FORCE_DARK_BACKGROUND, value > 0);
                    }), matchWrap(12));

            endGroup();
        }

        private void buildSkipOptions() {
            buildChipOptions();
        }

        private void buildFollowOptions() {
            buildChipOptions();
        }

        /** Skip and Follow are one floating-control system, so either outline opens the same
         * combined options instead of making users bounce between two near-identical panels. */
        private void buildChipOptions() {
            beginGroup(s("element_skip", "Skip"));
            addOption(text(strings.setting(Settings.SKIP_CHIP_POSITION), 12, GROUP_TITLE_COLOR, true), matchWrap(6));
            addOption(chipRow(Settings.SKIP_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshSkipChip();
                        refreshFollowChip();
                        selectElement(selected, false);
                    }), matchWrap(12));
            addOption(text(strings.setting(Settings.SKIP_CHIP_STYLE), 12, GROUP_TITLE_COLOR, true), matchWrap(6));
            addOption(chipRow(Settings.SKIP_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshSkipChip();
                        selectElement(selected, false);
                    }), matchWrap(12));

            endGroup();
            beginGroup(s("element_follow", "Follow"));
            addOption(text(strings.setting(Settings.FOLLOW_CHIP_POSITION), 12, GROUP_TITLE_COLOR, true), matchWrap(6));
            addOption(chipRow(Settings.FOLLOW_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshFollowChip();
                        refreshSkipChip();
                        selectElement(selected, false);
                    }), matchWrap(12));
            addOption(text(strings.setting(Settings.FOLLOW_CHIP_STYLE), 12, GROUP_TITLE_COLOR, true), matchWrap(6));
            addOption(chipRow(Settings.FOLLOW_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshFollowChip();
                        selectElement(selected, false);
                    }), matchWrap(12));

            endGroup();
        }

        private void buildDockOptions() {
            beginGroup(s("visibility", "Visibility"));
            addOption(toggleRow(Settings.SHOW_FULLSCREEN_BACK_BUTTON,
                    strings.setting(Settings.SHOW_FULLSCREEN_BACK_BUTTON), () -> {
                        refreshDock();
                        refreshBackButton();
                        selectElement(Element.DOCK, false);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.LIKED_SONGS_BUTTON));
            addOption(chipRow(Settings.LIKED_SONGS_BUTTON,
                    new String[]{"Off", "Heart", "Star"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.CHROME_CLUSTER_POSITION));
            addOption(chipRow(Settings.CHROME_CLUSTER_POSITION,
                    new String[]{"Left", "Right"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.CHROME_CLUSTER_LAYOUT));
            addOption(chipRow(Settings.CHROME_CLUSTER_LAYOUT,
                    new String[]{"Auto", "Vertical", "Horizontal"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FULLSCREEN_CONTROLS));
            int timeout = com.eza.spicyex.lyrics.LyricsShellSettings
                    .parseFullscreenControlsSeconds(store.get(Settings.FULLSCREEN_CONTROLS));
            // 1..30 are seconds; 31 is the final Always stop.
            addOption(dragRow(1, 31, timeout == 0 ? 31 : timeout, "", 31,
                    value -> value == 31 ? s("always", "Always")
                            : value + (value == 1 ? " second" : " seconds"), value -> {
                String stored = com.eza.spicyex.lyrics.LyricsShellSettings
                        .fullscreenControlsValue(value == 31 ? 0 : value);
                writer.put(Settings.FULLSCREEN_CONTROLS, stored);
                if (applyPreferences != null) applyPreferences.run();
            }), matchWrap(0));
        }

        // -- shared row builders ------------------------------------------------

        /** Chip labels always come from {@link SettingsUiStrings#option}, the same lookup the
         *  main Settings panel uses for this exact setting/value pair - so a value already
         *  labeled and translated there (most of them: Off/Top/Bottom/Regular/Small/...) reads
         *  identically here with no separate translation to maintain. */
        private LinearLayout chipRow(Settings.Setting<String> setting, String[] values,
                Runnable onChanged) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView[] chips = new TextView[values.length];
            String current = store.get(setting);
            int labelCharacters = 0;
            for (String value : values) {
                labelCharacters += strings.option((Settings.StringSetting) setting, value).length();
            }
            int maxPerLine = values.length <= 3 ? values.length
                    : (values.length == 4 || labelCharacters > 36 || values.length >= 5 ? 2 : 3);
            LinearLayout line = null;
            for (int i = 0; i < values.length; i++) {
                if (i % maxPerLine == 0) {
                    line = new LinearLayout(activity);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lineLp = matchWrap(0);
                    if (i > 0) lineLp.topMargin = dp(6);
                    row.addView(line, lineLp);
                }
                TextView chip = chip(strings.option((Settings.StringSetting) setting, values[i]));
                chips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                line.addView(chip, lp);
            }
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                chips[i].setOnClickListener(v -> {
                    for (int j = 0; j < values.length; j++) paintChip(chips[j], values[j].equals(value));
                    // Deferred: TRACK_INFO_POSITION can reparent a whole view subtree
                    // (entering/leaving Header mode) - see put()'s javadoc for why that can't
                    // happen synchronously from inside this click callback.
                    put(setting, value, onChanged);
                });
                paintChip(chips[i], value.equals(current));
            }
            return row;
        }

        /** Label + a two-state On/Off chip, for boolean settings - the same {@code chip()}/
         *  {@code paintChip()} widgets {@code chipRow()} uses, just one toggling chip instead of
         *  a row of mutually-exclusive ones. */
        private LinearLayout toggleRow(Settings.Setting<Boolean> setting, String label, Runnable onChanged) {
            boolean current = Boolean.TRUE.equals(store.get(setting));
            return toggleRow(label, current, (toggle, next) -> put(setting, next, onChanged));
        }

        /** Label + a two-state On/Off chip, backed by a plain boolean rather than a
         *  {@code Setting<Boolean>} directly - for a toggle derived from some other value (e.g.
         *  Extra darken's on/off state is just "is the 0-100 intensity setting non-zero") rather
         *  than its own persisted key. {@code onToggle} is responsible for persisting the new
         *  state; it's given the chip so it can still opt into the same optimistic repaint the
         *  plain-setting overload above does, or rebuild the whole panel (e.g. via
         *  {@code selectElement}) when the toggle changes what else is shown. */
        private LinearLayout toggleRow(String label, boolean current,
                java.util.function.BiConsumer<TextView, Boolean> onToggle) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView labelView = text(label, CONTROL_TEXT_SP, 0xE0FFFFFF, false);
            row.addView(labelView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView toggle = chip(onOffLabel(current));
            paintChip(toggle, current);
            boolean[] state = {current};
            toggle.setOnClickListener(v -> {
                boolean next = !state[0];
                state[0] = next;
                toggle.setText(onOffLabel(next));
                paintChip(toggle, next);
                onToggle.accept(toggle, next);
            });
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    dp(68), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(toggle, toggleLp);
            return row;
        }

        /** @param defaultMarker optional visual marker for the one declared default. It never
         *  changes, rounds, or magnetically snaps the user's value. */
        private LinearLayout dragRow(int min, int max, int initial, String unit, Integer defaultMarker,
                IntConsumer onChange) {
            return dragRow(min, max, initial, unit, defaultMarker, value -> value + unit, onChange);
        }

        private LinearLayout dragRow(int min, int max, int initial, String unit, Integer defaultMarker,
                java.util.function.IntFunction<String> labelForValue, IntConsumer onChange) {
            int[] value = {initial};
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(labelForValue.apply(initial), CONTROL_TEXT_SP, Color.WHITE, false);
            valueLabel.setSingleLine(true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            track.setFocusable(true);
            track.setContentDescription(valueLabel.getText());
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);

            View tick = null;
            if (defaultMarker != null) {
                tick = new View(activity);
                tick.setBackgroundColor(0x80FFFFFF);
                track.addView(tick, new FrameLayout.LayoutParams(dp(2), dp(10), Gravity.CENTER_VERTICAL));
            }
            final View tickView = tick;

            View thumb = new View(activity);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setShape(GradientDrawable.OVAL);
            thumbBg.setColor(ACCENT_COLOR);
            thumb.setBackground(thumbBg);
            int thumbSize = dp(HANDLE_SIZE_DP);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                    thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.START);
            track.addView(thumb, thumbLp);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
            trackLp.leftMargin = dp(10);
            row.addView(track, trackLp);

            Runnable[] paint = new Runnable[1];
            paint[0] = () -> {
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) {
                    track.post(() -> paint[0].run());
                    return;
                }
                float fraction = (value[0] - min) / (float) Math.max(1, max - min);
                thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
                thumb.setLayoutParams(thumbLp);
                if (tickView != null) {
                    float detentFraction = (defaultMarker - min) / (float) Math.max(1, max - min);
                    FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) tickView.getLayoutParams();
                    tickLp.leftMargin = thumbSize / 2 - dp(1) + Math.round(trackW * clamp01(detentFraction));
                    tickView.setLayoutParams(tickLp);
                }
            };
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
            track.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                // The options card now scrolls (see MaxHeightScrollView) - without this, a drag
                // that drifts even slightly vertically hands the gesture to that scroll instead
                // of finishing the slide here.
                if (action == MotionEvent.ACTION_DOWN) {
                    draggingSlider = true;
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    draggingSlider = false;
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                draggingSlider = true;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(labelForValue.apply(newValue));
                    track.setContentDescription(valueLabel.getText());
                    onChange.accept(newValue);
                }
                paint[0].run();
                return true;
            });
            track.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.SeekBar.class.getName());
                    info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                            android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                            min, max, value[0]));
                    if (value[0] < max) info.addAction(
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    if (value[0] > min) info.addAction(
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    int direction = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1
                            : action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 0;
                    if (direction == 0) return super.performAccessibilityAction(host, action, args);
                    int step = Math.max(1, Math.round((max - min) / 20f));
                    int next = clamp(value[0] + direction * step, min, max);
                    if (next == value[0]) return true;
                    value[0] = next;
                    valueLabel.setText(labelForValue.apply(next));
                    track.setContentDescription(valueLabel.getText());
                    onChange.accept(next);
                    paint[0].run();
                    host.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
                    return true;
                }
            });
            return row;
        }

        /** Smooth custom-value slider for settings that retain legacy named modes in storage.
         * Entering the editor converts the currently rendered named mode to its equivalent value;
         * the first user movement then writes Custom directly with no preset snap points. */
        private LinearLayout customSliderRow(Settings.Setting<String> modeSetting,
                Settings.IntegerSetting customSetting, String customValue, Runnable onChanged) {
            int initial = resolvedCustomPercent(modeSetting, customSetting);
            return dragRow(customSetting.minValue, customSetting.maxValue, initial, "%",
                    customSetting.defaultValue, value -> {
                        writer.put(modeSetting, customValue);
                        writer.put(customSetting, value);
                        if (onChanged != null) onChanged.run();
                    });
        }

        private int resolvedCustomPercent(Settings.Setting<String> modeSetting,
                Settings.IntegerSetting customSetting) {
            String mode = store.get(modeSetting);
            if (modeSetting == Settings.LINE_SPACING) {
                if ("compact".equals(mode)) return 80;
                if ("default".equals(mode)) return 110;
                if ("spacious".equals(mode)) return 150;
                if ("more".equals(mode)) return 200;
                if ("max".equals(mode)) return 250;
            } else if (modeSetting == Settings.TRACK_INFO_TEXT_SIZE) {
                if ("Small".equals(mode)) return 87;
                if ("Normal".equals(mode)) return 100;
                if ("Large".equals(mode)) return 120;
                if ("XLarge".equals(mode)) return 147;
            } else {
                if ("small".equalsIgnoreCase(mode)) return 90;
                if ("normal".equalsIgnoreCase(mode)) return 100;
                if ("large".equalsIgnoreCase(mode)) return 120;
                if ("xlarge".equalsIgnoreCase(mode)) return 150;
            }
            return safeGet(customSetting);
        }

        // -- small view helpers -------------------------------------------------

        /** Editor-only freeform text (hints, element names, action labels) that has no
         *  corresponding {@link Settings.Setting} of its own to borrow a label from. */
        private String s(String key, String fallback) {
            return strings.get("settings_layout_editor_" + key, fallback);
        }

        private String onOffLabel(boolean on) {
            return strings.get(on ? "settings_option_on" : "settings_option_off", on ? "On" : "Off");
        }

        private TextView text(String value, int sp, int color, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            return view;
        }

        private TextView textButton(String label) {
            TextView view = text(label, 14, TEXT_COLOR, false);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            view.setClickable(true);
            view.setFocusable(true);
            return view;
        }

        private TextView chip(String label) {
            TextView chip = text(label, CONTROL_TEXT_SP, TEXT_COLOR, false);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setPadding(dp(6), dp(11), dp(6), dp(11));
            chip.setMinHeight(dp(CONTROL_MIN_HEIGHT_DP));
            chip.setSingleLine(true);
            // Long option names ("Left to right (sentence)") shrink to fit rather than being cut
            // off or wrapping the chip to two lines, which was most of the panel's raggedness.
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return chip;
        }

        private void paintChip(TextView chip, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(selected ? 0x331ED760 : 0x14FFFFFF);
            chip.setBackground(bg);
            chip.setTextColor(selected ? ACCENT_COLOR : TEXT_COLOR);
            // Unselected chips also dim slightly - color alone (white vs. accent) read as too
            // close in weight; a touch of transparency makes the selected one pop more clearly.
            chip.setAlpha(selected ? 1f : 0.72f);
        }

        private View divider() {
            View line = new View(activity);
            line.setBackgroundColor(0x1FFFFFFF);
            return line;
        }

        private LinearLayout.LayoutParams matchWrap(int bottomMarginDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(bottomMarginDp);
            return lp;
        }

        private boolean isAppleStyle() {
            return "Apple Music".equals(store.get(Settings.ANIMATION_STYLE));
        }

        private int safeGet(Settings.IntegerSetting setting) {
            Integer value = store.get(setting);
            return value == null ? setting.defaultValue : value;
        }

        /** Stored value of a string setting, with its declared default as the fallback - for a
         *  control derived from another setting (the two-column on/off row over the readout
         *  position) rather than backed by one. */
        private String safeGetString(Settings.Setting<String> setting) {
            String value = store.get(setting);
            return value == null ? setting.defaultValue : value;
        }

        /** Current lyrics text size as a percent, matching whichever named preset (or custom
         *  value) buildTextOptions()'s own size slider resolves - used as the pinch-zoom
         *  gesture's starting point so it continues smoothly from a preset instead of jumping. */
        private int currentLyricsTextSizePercent() {
            String[] presetValues = {"small", "normal", "large", "xlarge"};
            int[] presetPercents = {90, 100, 120, 150};
            String mode = store.get(Settings.LYRICS_TEXT_SIZE);
            int percent = safeGet(Settings.LYRICS_TEXT_SIZE_CUSTOM);
            for (int i = 0; i < presetValues.length; i++) {
                if (presetValues[i].equalsIgnoreCase(mode)) percent = presetPercents[i];
            }
            // Landscape shows the portrait size scaled to fit until it gets its own value (see
            // LyricsShellSettings#lyricsTextSizeMultiplier); start the pinch from what is shown.
            String landscapeKey = Settings.landscapeKey(activity, Settings.LYRICS_TEXT_SIZE);
            if (landscapeKey != null && !activity.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE).contains(landscapeKey)) {
                percent = Math.round(percent * com.eza.spicyex.lyrics.LyricsShellSettings.LANDSCAPE_FIT_SCALE);
            }
            return percent;
        }
    }

    /** Caps its own height to the panel's height budget for the current placement, and scrolls the
     *  rest - a plain WRAP_CONTENT ScrollView here would still grow to fill nearly the entire
     *  available height before Android ever has reason to let it scroll, which in landscape's
     *  shorter screen means the options list running off the screen with the top bar sitting on
     *  top of it. */
    private static class MaxHeightScrollView extends android.widget.ScrollView {
        /** Hard cap on the list's own height in pixels; 0 leaves it uncapped. */
        int maxHeightPx = 0;

        MaxHeightScrollView(Activity activity) {
            super(activity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeightPx > 0
                    && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                int capped = Math.min(View.MeasureSpec.getSize(heightMeasureSpec), maxHeightPx);
                heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /** An Apple-style curved bracket hugging the frame's actual corner point (vertex at this
     *  view's own top-left, which {@code refreshArtwork()} positions exactly on the real corner) -
     *  not a dot floating centered on top of the corner. Rounded stroke joins give the two arms a
     *  single continuous curve at the vertex instead of a sharp right angle. The view's full
     *  bounds stay the touch target; only the bracket itself is painted. */
    private static final class CornerGripView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF oval = new android.graphics.RectF();
        private final int cornerOffset;
        private final float cornerRadiusPx;

        /** iOS Control Center's resize grip: a thick, soft-white arc lying along the inside of the
         *  element's rounded bottom-right corner. {@code cornerOffset} is where that corner sits
         *  in this view (both axes); {@code cornerRadiusPx} is the element's own corner radius, so
         *  the arc follows its curve. The whole view is the touch target. */
        CornerGripView(Activity activity, int cornerOffset, float cornerRadiusPx) {
            super(activity);
            this.cornerOffset = cornerOffset;
            this.cornerRadiusPx = cornerRadiusPx;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(6));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(Color.argb(240, 255, 255, 255));
            shade.setStyle(android.graphics.Paint.Style.STROKE);
            shade.setStrokeWidth(dp(9));
            shade.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            shade.setColor(Color.argb(70, 0, 0, 0));
            shade.setMaskFilter(new android.graphics.BlurMaskFilter(dp(3),
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float stroke = paint.getStrokeWidth();
            // Concentric with the element's corner curve, pulled in so the stroke sits just inside
            // the outline; tiny radii still get a readable arc.
            float r = Math.max(dp(14), Math.min(dp(30), cornerRadiusPx)) - stroke / 2f - dp(2);
            float c = cornerOffset - stroke / 2f - dp(2);
            oval.set(c - 2f * r, c - 2f * r, c, c);
            canvas.drawArc(oval, 8f, 74f, false, shade);
            canvas.drawArc(oval, 8f, 74f, false, paint);
        }
    }

    /** Focus-point line visual: four small L-shaped corner brackets (viewfinder-style, echoing
     *  {@link CornerGripView}'s curved-corner language) at the left/right ends of a thin dashed
     *  line, instead of a single flat bar spanning the full width. Purely decorative - the touch
     *  band and drag logic in {@code buildFocusHandle()} are unchanged. */
    private static final class FocusLineView extends View {
        private static final int CORNER_ARM_DP = 8;
        private static final int BRACKET_HALF_HEIGHT_DP = 10;
        private static final int INSET_DP = 20;
        private final android.graphics.Paint bracketPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dashPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean isSelected;

        FocusLineView(Activity activity) {
            super(activity);
            bracketPaint.setStyle(android.graphics.Paint.Style.STROKE);
            bracketPaint.setStrokeWidth(dp(3));
            bracketPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            bracketPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            bracketPaint.setColor(ACCENT_COLOR);
            dashPaint.setStyle(android.graphics.Paint.Style.STROKE);
            dashPaint.setStrokeWidth(dp(2));
            dashPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            dashPaint.setColor(0x99FFFFFF);
            dashPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(4), dp(4)}, 0));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setSelectedState(boolean value) {
            if (isSelected == value) return;
            isSelected = value;
            bracketPaint.setColor(value ? ACCENT_COLOR : GRAY_IDLE_COLOR);
            bracketPaint.setStrokeWidth(dp(value ? 3 : OUTLINE_IDLE_DP));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getHeight() / 2f;
            float arm = dp(CORNER_ARM_DP);
            float halfBracket = dp(BRACKET_HALF_HEIGHT_DP);
            float left = dp(INSET_DP);
            float right = getWidth() - dp(INSET_DP);

            android.graphics.Path corners = new android.graphics.Path();
            // Top-left / bottom-left.
            corners.moveTo(left, centerY - halfBracket + arm);
            corners.lineTo(left, centerY - halfBracket);
            corners.lineTo(left + arm, centerY - halfBracket);
            corners.moveTo(left, centerY + halfBracket - arm);
            corners.lineTo(left, centerY + halfBracket);
            corners.lineTo(left + arm, centerY + halfBracket);
            // Top-right / bottom-right.
            corners.moveTo(right, centerY - halfBracket + arm);
            corners.lineTo(right, centerY - halfBracket);
            corners.lineTo(right - arm, centerY - halfBracket);
            corners.moveTo(right, centerY + halfBracket - arm);
            corners.lineTo(right, centerY + halfBracket);
            corners.lineTo(right - arm, centerY + halfBracket);
            canvas.drawPath(corners, bracketPaint);

            // The dashed rule spans the whole screen width, so it only earns its place while the
            // focus point is what is being edited; idle, the corner brackets alone say where it is.
            if (!isSelected) return;
            float dashStart = left + arm + dp(6);
            float dashEnd = right - arm - dp(6);
            if (dashEnd > dashStart) canvas.drawLine(dashStart, centerY, dashEnd, centerY, dashPaint);
        }
    }

    /** Draws one rounded-rect outline per currently mounted lyric row, hugging each row's own
     *  text content (its bounds minus its own line-spacing padding - see hitsRowContent()) rather
     *  than one box spanning the whole scrollable column. Redraws every frame while attached so it
     *  tracks live scrolling/remounting without needing its own scroll-change plumbing - cheap:
     *  it's a handful of stroked rounded-rects, not a real layout pass. */
    private static final class RowOutlinesView extends View {
        private final Supplier<ViewGroup> mountedRowsHostSupplier;
        private final Supplier<View> artSupplier;
        private final Supplier<View> trackTextSupplier;
        private final Supplier<View> chromeSupplier;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private boolean isSelected;

        RowOutlinesView(Activity activity, Supplier<ViewGroup> mountedRowsHostSupplier,
                        Supplier<View> artSupplier, Supplier<View> trackTextSupplier,
                        Supplier<View> chromeSupplier) {
            super(activity);
            this.mountedRowsHostSupplier = mountedRowsHostSupplier;
            this.artSupplier = artSupplier;
            this.trackTextSupplier = trackTextSupplier;
            this.chromeSupplier = chromeSupplier;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            setWillNotDraw(false);
        }

        void setSelectedState(boolean value) {
            if (isSelected == value) return;
            isSelected = value;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            // Only while Lyrics is the selection. A box around every mounted row, drawn at all
            // times, turned the whole screen into a grid the moment the editor opened and made
            // every other element's outline hard to pick out - and there is nothing to aim at
            // here anyway: the rows are one element, tapped anywhere, not individually editable.
            if (!isSelected) {
                postInvalidateOnAnimation();
                return;
            }
            paint.setColor(ACCENT_COLOR);
            paint.setStrokeWidth(dp(OUTLINE_SELECTED_DP));
            ViewGroup host = mountedRowsHostSupplier == null ? null : mountedRowsHostSupplier.get();
            if (host != null) {
                int[] myLoc = new int[2];
                getLocationOnScreen(myLoc);
                int[] rowLoc = new int[2];
                for (int i = 0; i < host.getChildCount(); i++) {
                    View row = host.getChildAt(i);
                    if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0) continue;
                    row.getLocationOnScreen(rowLoc);
                    float left = rowLoc[0] - myLoc[0] + row.getPaddingLeft();
                    float top = rowLoc[1] - myLoc[1] + row.getPaddingTop();
                    float right = rowLoc[0] - myLoc[0] + row.getWidth() - row.getPaddingRight();
                    float bottom = rowLoc[1] - myLoc[1] + row.getHeight() - row.getPaddingBottom();
                    if (right <= left || bottom <= top) continue;
                    if (intersectsLiveControl(rowLoc[0] + row.getPaddingLeft(),
                            rowLoc[1] + row.getPaddingTop(),
                            rowLoc[0] + row.getWidth() - row.getPaddingRight(),
                            rowLoc[1] + row.getHeight() - row.getPaddingBottom())) continue;
                    rect.set(left, top, right, bottom);
                    canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                }
            }
            postInvalidateOnAnimation();
        }

        private boolean intersectsLiveControl(int left, int top, int right, int bottom) {
            return intersects(left, top, right, bottom, artSupplier == null ? null : artSupplier.get())
                    || intersects(left, top, right, bottom,
                    trackTextSupplier == null ? null : trackTextSupplier.get())
                    || intersects(left, top, right, bottom,
                    chromeSupplier == null ? null : chromeSupplier.get());
        }

        private static boolean intersects(int left, int top, int right, int bottom, View other) {
            if (other == null || other.getVisibility() != View.VISIBLE
                    || other.getWidth() <= 0 || other.getHeight() <= 0) return false;
            int[] loc = new int[2];
            other.getLocationOnScreen(loc);
            return left < loc[0] + other.getWidth() && right > loc[0]
                    && top < loc[1] + other.getHeight() && bottom > loc[1];
        }
    }

    private static int[] relativePosition(View child, View ancestor) {
        int[] childLoc = new int[2];
        int[] ancestorLoc = new int[2];
        child.getLocationOnScreen(childLoc);
        ancestor.getLocationOnScreen(ancestorLoc);
        return new int[]{childLoc[0] - ancestorLoc[0], childLoc[1] - ancestorLoc[1]};
    }

    /**
     * Screen-space rect of a view that is actually on screen to measure, or null when it is not.
     *
     * <p>A view that is detached, hidden, faded out, or not laid out has no honest rect: reporting
     * its last known position would be a plausible-looking wrong answer, which is the exact failure
     * this probe exists to remove. The whole ancestor chain counts, not just the view: the editor's
     * card capture keeps VISIBLE inside a layer it GONEs away in lyrics mode, and its stale last
     * layout would otherwise be reported as if it were on screen. Null is dropped by
     * {@link LayoutProbeReport#rect}, and a rule that needs a missing rect skips rather than guesses.
     *
     * <p>Reads only - it never requests layout and never touches a view's state, so running it from
     * a probe cannot itself change the geometry being probed.
     */
    static int[] screenRectOf(View view) {
        if (view == null || !view.isAttachedToWindow()) return null;
        if (!isShownInTree(view)) return null;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return new int[]{loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight()};
    }

    private static boolean isShownInTree(View view) {
        for (View current = view; current != null; ) {
            if (current.getVisibility() != View.VISIBLE) return false;
            if (current.getAlpha() <= 0.01f) return false;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @SuppressWarnings("unchecked")
    private static <T> void restoreTyped(SettingsWriter writer, Settings.Setting<T> setting, Object value) {
        writer.put(setting, (T) value);
    }
}
