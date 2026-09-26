package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.UiLanguage;
import com.eza.spicyex.settings.SettingsWriter;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

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
    }
    private static final int HANDLE_SIZE_DP = 22;
    /** Extra grab margin around the corner grip's drawn bracket. */
    private static final int HANDLE_TOUCH_PAD_DP = 10;
    /** Touch box of the Apple-style resize grip; the element's corner sits GRIP_OUTSIDE_DP in
     *  from its bottom-right, so most of the box (and the drawn arc) lies inside the element. */
    private static final int GRIP_BOX_DP = 56;
    private static final int GRIP_OUTSIDE_DP = 12;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int ACCENT_COLOR = Color.rgb(30, 215, 96);
    /** Outline color actually painted on an UNSELECTED capture. Every capturable element is
     *  outlined at all times so the editor reads as one visual language, but at full-strength
     *  gray, seven simultaneous boxes plus a per-lyric-row grid buried the one box the user
     *  was actually editing. Unselected outlines are therefore drawn as a faint hairline: still
     *  legible as "this is tappable", no longer competing with the selection. */
    private static final int GRAY_IDLE_COLOR = 0x3DFFFFFF;
    private static final int OUTLINE_SELECTED_DP = 2;
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

    /** One editor invocation's mutable state - a plain instance instead of a pile of one-element
     *  arrays now that there's real state (selected element, snapshot, current drag) to carry. */
    /** A setting the next editor session should open on (a settings-search result); one-shot. */
    private static volatile Settings.Setting<?> pendingFocus;

    static void requestFocus(Settings.Setting<?> setting) {
        pendingFocus = setting;
    }

    /** The settings the editor covers, for the settings search (see LayoutEditorSettings). */
    static Settings.Setting<?>[] coveredSettings() {
        return Session.TOUCHED_SETTINGS.clone();
    }

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
                Settings.LIKED_SONGS_BUTTON, Settings.CHROME_CLUSTER_POSITION, Settings.FULLSCREEN_CONTROLS,
                Settings.LYRICS_ADAPTIVE_TEXT_SIZE, Settings.INTERLUDE_ICON,
                Settings.TRACK_INFO_BACKGROUND, Settings.TRACK_INFO_TEXT_OVERFLOW,
                Settings.WORD_BOUNCE, Settings.WORD_BOUNCE_STYLE,
                Settings.ENABLE_GLOW_BLUR, Settings.LINE_SYNC_FILL,
                Settings.ANIMATION_STYLE, Settings.LOAD_LIFT_ANIMATION,
                Settings.APPLE_CASCADE_SPEED, Settings.APPLE_SPRING_STRENGTH,
                Settings.FOLLOW_CHIP_ANIMATION, Settings.FOLLOW_CHIP_PROGRESS,
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
        private final java.util.IdentityHashMap<Settings.Setting<?>, Object> initialSettings =
                new java.util.IdentityHashMap<>();
        private final Runnable applyPreferences;
        private final Runnable onChromeReveal;
        private final Runnable onClosed;
        private final Runnable enableDemoData;
        private final Runnable disableDemoData;
        private final EditableChip skipChip;
        private final EditableChip followChip;
        private final boolean startInCardMode;

        private final FrameLayout overlay;
        private final FrameLayout artLayer;
        private final FrameLayout trackTextLayer;
        private final FrameLayout skipLayer;
        private final FrameLayout followLayer;
        private final FrameLayout dockLayer;
        private final FrameLayout backLayer;
        private final LinearLayout optionsCard;
        private final MaxHeightScrollView optionsScroll;
        private final SheetLayout panelContainer;
        private final TextView sheetTitle;

        private Element selected = Element.ARTWORK;
        private boolean panelVisible;
        private View artCapture;
        private View artHandle;
        private View trackTextCapture;
        private View focusHandle;
        private View textOutline;
        private View skipCapture;
        private View followCapture;
        private View dockCapture;
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
            for (int i = 0; i < captureBindings.size(); i++) {
                CaptureBinding binding = captureBindings.get(i);
                View capture = binding.capture;
                if (capture == null || capture.getParent() == null) continue;
                if (resizingArtwork && capture == artCapture) continue;
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
                    if (capture.getVisibility() != View.GONE) capture.setVisibility(View.GONE);
                    continue;
                }
                if (capture.getVisibility() != View.VISIBLE) capture.setVisibility(View.VISIBLE);
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
                }
                syncHandle(binding, lp);
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
            this.store = new SettingsStore(activity);
            this.writer = new SettingsWriter(store);
            this.strings = UiLanguage.strings(activity, store.get(Settings.UI_LANGUAGE));
            for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                initialSettings.put(setting, store.get(setting));
            }
            this.overlay = new FrameLayout(activity);
            this.artLayer = new FrameLayout(activity);
            this.trackTextLayer = new FrameLayout(activity);
            this.skipLayer = new FrameLayout(activity);
            this.followLayer = new FrameLayout(activity);
            this.dockLayer = new FrameLayout(activity);
            this.backLayer = new FrameLayout(activity);
            this.optionsCard = new LinearLayout(activity);
            this.optionsScroll = new MaxHeightScrollView(activity);
            this.panelContainer = new SheetLayout(activity);
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

            artLayer.setClipChildren(false);
            overlay.addView(artLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            trackTextLayer.setClipChildren(false);
            overlay.addView(trackTextLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Added before the focus handle so the handle's smaller touch band still wins where
            // the two overlap (later-added children win overlapping touches in a FrameLayout).
            buildTextTapLayer();
            buildFocusHandle();

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
            // empty/error state, so every element had to be selected blind. Full only - the demo's
            // whole point is previewing furigana/pinyin/romanization/translation, none of which
            // exist in the Lite build. Deferred to after the first layout pass so the editor UI
            // appears instantly instead of blocking on document render + bitmap creation.
            if (!demoActive && enableDemoData != null
                    && com.eza.spicyex.FeatureAvailability.transliterationAvailable()) {
                demoActive = true;
                overlay.post(() -> overlay.post(() -> {
                    if (enableDemoData != null) enableDemoData.run();
                }));
            }

            optionsCard.setOrientation(LinearLayout.VERTICAL);
            optionsCard.setPadding(dp(14), dp(4), dp(14), dp(10));

            // The card can run long (Artwork/Background have half a dozen rows each), and in
            // landscape's shorter height a plain WRAP_CONTENT card would grow tall enough to sit
            // under the top bar, with no way to reach whatever scrolled past it. Wrapping it in a
            // scroll view - capped well short of the full height by MaxHeightScrollView - keeps it
            // reachable by drag in both orientations instead.
            //
            // Moved background and elevation to the panelContainer itself to create a cohesive 
            // full-width BottomSheet appearance.
            optionsScroll.setVerticalScrollBarEnabled(false);
            optionsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            optionsScroll.addView(optionsCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // On a wide screen (landscape, unfolded foldable) a full-width bottom sheet covers most
            // of what is being edited; it becomes a floating side sheet on the trailing edge.
            android.content.res.Configuration screen = activity.getResources().getConfiguration();
            boolean sideSheet = screen.screenWidthDp > screen.screenHeightDp || screen.screenWidthDp >= 600;
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(0xF21C1C22);
            float cornerRadius = dp(24);
            if (sideSheet) {
                panelBg.setCornerRadius(cornerRadius);
                optionsScroll.maxHeightFraction = 0.82f;
            } else {
                panelBg.setCornerRadii(new float[]{cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0, 0, 0, 0});
            }
            panelBg.setStroke(dp(1), 0x24FFFFFF);
            panelContainer.setBackground(panelBg);
            panelContainer.setElevation(dp(16));

            panelContainer.removeAllViews();
            panelContainer.addView(sheetGrabber(), sheetGrabberLp());
            // Names what the sheet is editing: selection is tap-driven on the real screen, so
            // without it the only cue is which outline happens to be green.
            sheetTitle.setSingleLine(true);
            sheetTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = matchWrap(6);
            titleLp.leftMargin = dp(20);
            titleLp.rightMargin = dp(20);
            panelContainer.addView(sheetTitle, titleLp);
            // Editor actions live in the top chrome, so the sheet is only as tall as its options.
            panelContainer.addView(optionsScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout.LayoutParams panelLp;
            if (sideSheet) {
                int screenW = Math.round(screen.screenWidthDp * activity.getResources().getDisplayMetrics().density);
                panelLp = new FrameLayout.LayoutParams(Math.min(dp(420), Math.round(screenW * 0.46f)),
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                panelLp.rightMargin = dp(12);
                panelLp.bottomMargin = dp(12);
            } else {
                panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            }
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
                Settings.Setting<?> focus = pendingFocus;
                pendingFocus = null;
                if (focus != null) openOn(focus);
            });
            startCaptureSync();
        }

        private void close() {
            stopCaptureSync();
            overlay.removeCallbacks(cardFrame);
            if (disableDemoData != null) {
                demoActive = false;
                disableDemoData.run();
            }
            if (skipChip != null && skipChip.restoreVisibility != null) skipChip.restoreVisibility.run();
            if (followChip != null && followChip.restoreVisibility != null) followChip.restoreVisibility.run();
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
            if (onClosed != null) onClosed.run();
            // The overlay's own scrim sat over the real chrome row the whole time it was open;
            // make sure it (and the settings cog on it) is actually visible again afterward
            // rather than relying on whatever auto-hide state it happened to be in already.
            if (onChromeReveal != null) onChromeReveal.run();
        }

        /** X means cancel: restore the snapshot captured when this editor session opened, then
         * close after the live shell has re-applied those values. */
        private void cancel() {
            for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                restoreTyped(writer, setting, initialSettings.get(setting));
            }
            if (applyPreferences != null) applyPreferences.run();
            close();
        }

        @Override
        public boolean onBackPressed() {
            if (overlay.getParent() == null) return false;
            if (panelVisible || panelContainer.getVisibility() == View.VISIBLE) {
                hidePanelSheet(true);
            } else {
                close();
            }
            return true;
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
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                afterNextLayout(() -> {
                    refreshArtwork();
                    refreshTrackText();
                    repaintFocusHandle();
                    refreshSkipChip();
                    refreshFollowChip();
                    refreshDock();
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
            button.setScaleType(ImageView.ScaleType.CENTER);
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
                int sizePx = dp(safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP));
                // Editor preview is intentionally top-mounted. It remains selectable even when
                // the saved track-info mode is Off; changing the mode in the sheet still writes
                // the user's real preference, while this virtual frame never disappears.
                int[] pos = new int[] { dp(16), dp(78) };
                View capture = new View(activity);
                GradientDrawable outline = new GradientDrawable();
                outline.setStroke(dp(2), ACCENT_COLOR);
                outline.setCornerRadius(dp(safeGet(Settings.TRACK_INFO_ART_RADIUS)));
                capture.setBackground(outline);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP | Gravity.START);
                lp.leftMargin = pos[0];
                lp.topMargin = pos[1];
                capture.setOnClickListener(v -> selectElement(Element.ARTWORK));
                artLayer.addView(capture, lp);
                artCapture = capture;
                // This is an editor-only virtual target for TRACK_INFO_POSITION=Off. Do not bind
                // it to a null source: capture sync quite correctly hides null-source bindings,
                // which used to make Artwork impossible to select in the Off state.
                paintCapture(artCapture, selected == Element.ARTWORK);
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            int radiusDp = safeGet(Settings.TRACK_INFO_ART_RADIUS);

            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(radiusDp));
            capture.setBackground(outline);
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
            boolean[] dragging = new boolean[1];
            capture.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY[0] = event.getRawY();
                        dragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startY[0];
                        if (!dragging[0] && Math.abs(dy) > slopPx) dragging[0] = true;
                        if (dragging[0]) capture.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = event.getRawY() - startY[0];
                        capture.setTranslationY(0f);
                        if (dragging[0]) {
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
            int max = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.maxValue;
            float density = Math.max(0.5f, activity.getResources().getDisplayMetrics().density);
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            int[] startSizeDp = new int[1];
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
                            startSizeDp[0] = currentArtSizeDp(min, max, density);
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
                            int newSizeDp = clamp(Math.round(startSizeDp[0] + dragDp), min, max);
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
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        width, height, Gravity.TOP | Gravity.START);
                lp.leftMargin = dp(16);
                lp.topMargin = dp(78) + dp(safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP)) + dp(10);
                capture.setOnClickListener(v -> selectElement(Element.TRACK_TEXT));
                trackTextLayer.addView(capture, lp);
                trackTextCapture = capture;
                paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(10));
            capture.setBackground(outline);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            capture.setOnClickListener(v -> selectElement(Element.TRACK_TEXT));
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
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (withinCapturedFrame(event.getRawX(), event.getRawY())) return false;
                        startRawX[0] = event.getRawX();
                        startRawY[0] = event.getRawY();
                        dragging[0] = false;
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = MotionEvent.obtain(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - startRawX[0];
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.hypot(dx, dy) > slopPx) {
                            dragging[0] = true;
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
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) forwardToFocusArea(event);
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
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
            // The sheet is another real interactive child below this touch plane; excluding its
            // current bounds is what lets the grabber and every option row receive the gesture.
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
            lp.width = Math.max(0, focusArea.getWidth());
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1] + Math.round(areaHeight * fraction) - lp.height / 2;
            focusHandle.setLayoutParams(lp);
        }

        private float currentFocusFraction() {
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

        /** Captures the real top-left Back control while editing, so it closes the editor (or
         *  its open sheet) instead of finishing Spotify's tab through the shell callback. */
        private void refreshBackButton() {
            backLayer.removeAllViews();
            dropBindingsIn(backLayer);
            View source = backButtonSupplier == null ? null : backButtonSupplier.get();
            int buttonSize = source != null && source.getHeight() > 0 ? source.getHeight() : dp(44);
            int[] pos = source != null && source.getWidth() > 0 && source.getHeight() > 0
                    ? relativePosition(source, shellRoot) : new int[]{dp(8), dp(8)};

            ImageView cancel = iconButton(ActionIconDrawable.Kind.CLOSE, 0xE6FFFFFF,
                    s("cancel", "Cancel"), this::cancel);
            ImageView save = iconButton(ActionIconDrawable.Kind.CHECK, ACCENT_COLOR,
                    s("save", "Save"), this::close);
            backLayer.addView(cancel, editorActionLp(pos[0], pos[1], buttonSize));
            backLayer.addView(save, editorActionLp(pos[0] + buttonSize + dp(6), pos[1], buttonSize));
            LinearLayout pills = new LinearLayout(activity);
            pills.setOrientation(LinearLayout.HORIZONTAL);
            TextView mode = actionPill(cardMode ? s("mode_lyrics", "Lyrics screen") : s("mode_card", "Now playing card"),
                    () -> setCardMode(!cardMode));
            pills.addView(mode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, buttonSize));
            if (!cardMode && enableDemoData != null && disableDemoData != null) {
                TextView demoPill = actionPill(demoActive ? s("demo_lyrics", "Demo") : s("live_lyrics", "Live"),
                        this::toggleDemo);
                LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, buttonSize);
                sourceLp.leftMargin = dp(6);
                pills.addView(demoPill, sourceLp);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, buttonSize, Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0] + 2 * (buttonSize + dp(6));
            lp.topMargin = pos[1];
            backLayer.addView(pills, lp);
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
        private float sheetFractionBeforeCard;
        /** The card sheet's tabs: Text (size, weight, secondary line, overflow) and Animation. */
        private int cardTab;

        /** Layers that belong to the lyrics-screen elements; hidden while editing the card. */
        private View[] lyricsModeViews() {
            return new View[]{artLayer, trackTextLayer, focusHandle, textTapLayer, textOutline,
                    skipLayer, followLayer, dockLayer};
        }

        /**
         * Opens straight on what a settings search found: the element that owns the setting,
         * the tab it is on, and its sheet open - so the result lands on the control, not on the
         * editor's front page.
         */
        private void openOn(Settings.Setting<?> setting) {
            Element element = elementFor(setting);
            if (element == null) return;
            if (element == Element.CARD) {
                cardTab = CARD_ANIMATION_SETTINGS.contains(setting) ? 1 : 0;
                if (!cardMode) setCardMode(true);
            } else if (element == Element.TEXT) {
                textTab = TEXT_ANIMATION_SETTINGS.contains(setting) ? TAB_ANIMATION
                        : TEXT_EFFECT_SETTINGS.contains(setting) ? TAB_EFFECTS : TAB_STYLE;
            }
            selectElement(element, false);
            revealSheet();
        }

        private static final java.util.Set<Settings.Setting<?>> CARD_ANIMATION_SETTINGS =
                new java.util.HashSet<>(java.util.Arrays.asList(Settings.LIVE_CARD_ANIMATION,
                        Settings.LIVE_CARD_GLOW, Settings.LIVE_CARD_LINE_SYNC_FILL, Settings.LIVE_CARD_TRANSITION));
        private static final java.util.Set<Settings.Setting<?>> TEXT_ANIMATION_SETTINGS =
                new java.util.HashSet<>(java.util.Arrays.asList(Settings.ANIMATION_STYLE,
                        Settings.APPLE_CASCADE_SPEED, Settings.APPLE_FADE_PASSED_LINES, Settings.APPLE_LIFT,
                        Settings.APPLE_SPRING_STRENGTH, Settings.LINE_SLIDE_ANIMATION, Settings.LINE_SYNC_FILL,
                        Settings.LOAD_LIFT_ANIMATION, Settings.WORD_BOUNCE, Settings.WORD_BOUNCE_STYLE));
        private static final java.util.Set<Settings.Setting<?>> TEXT_EFFECT_SETTINGS =
                new java.util.HashSet<>(java.util.Arrays.asList(Settings.ENABLE_GLOW_BLUR,
                        Settings.ENABLE_LINE_BLUR, Settings.INTERLUDE_ICON, Settings.LYRICS_BLUR_INTENSITY));

        /** Which element's sheet carries a setting. */
        private static Element elementFor(Settings.Setting<?> s) {
            if (s == null) return null;
            if (s.key != null && s.key.startsWith("lyrics_live_card")) return Element.CARD;
            if (s == Settings.TRACK_INFO_POSITION || s == Settings.TRACK_INFO_ART_RADIUS
                    || s == Settings.TRACK_INFO_ART_SIZE || s == Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP
                    || s == Settings.ADAPTIVE_LANDSCAPE_LAYOUT || s == Settings.PANEL_MEDIA_CONTROLS) {
                return Element.ARTWORK;
            }
            if (s == Settings.TRACK_INFO_BACKGROUND || s == Settings.TRACK_INFO_SHOW_ALBUM
                    || s == Settings.TRACK_INFO_SHOW_ARTIST || s == Settings.TRACK_INFO_SHOW_TITLE
                    || s == Settings.TRACK_INFO_TEXT_ALIGN || s == Settings.TRACK_INFO_TEXT_OVERFLOW
                    || s == Settings.TRACK_INFO_TEXT_SIZE || s == Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE
                    || s == Settings.TRACK_INFO_TEXT_SIZE_CUSTOM) {
                return Element.TRACK_TEXT;
            }
            if (s == Settings.LYRICS_FOCUS_POSITION || s == Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT) {
                return Element.FOCUS;
            }
            if (s == Settings.BACKGROUND_RENDER_QUALITY || s == Settings.BACKGROUND_STYLE
                    || s == Settings.EXTRA_DARK_BACKGROUND || s == Settings.FORCE_DARK_BACKGROUND) {
                return Element.BACKGROUND;
            }
            if (s == Settings.SKIP_CHIP_POSITION || s == Settings.SKIP_CHIP_STYLE) return Element.SKIP;
            if (s == Settings.FOLLOW_CHIP_ANIMATION || s == Settings.FOLLOW_CHIP_POSITION
                    || s == Settings.FOLLOW_CHIP_PROGRESS || s == Settings.FOLLOW_CHIP_STYLE) {
                return Element.FOLLOW;
            }
            if (s == Settings.CHROME_CLUSTER_POSITION || s == Settings.FULLSCREEN_CONTROLS
                    || s == Settings.LIKED_SONGS_BUTTON) {
                return Element.DOCK;
            }
            return Element.TEXT;
        }

        /**
         * Opens the sheet and makes sure it really is open once layout settles: on a slow first
         * frame the sheet can still be unmeasured when it is asked to show, and would animate
         * from a zero offset to nowhere. Checked again after the next layout, and once more a
         * little later, it ends up fully open either way.
         */
        private void revealSheet() {
            showPanelSheet(true);
            Runnable settle = () -> {
                if (overlay.getParent() == null) return;
                if (!panelVisible || panelContainer.getVisibility() != View.VISIBLE) {
                    showPanelSheet(true);
                } else if (Math.abs(panelContainer.getTranslationY()) > 1f) {
                    animateSheetTo(0f, 0f, true, null);
                }
            };
            afterNextLayout(settle);
            overlay.postDelayed(settle, 450);
        }

        private void setCardMode(boolean enabled) {
            if (enabled == cardMode && (!enabled || cardLayer != null)) return;
            cardMode = enabled;
            for (View view : lyricsModeViews()) {
                if (view != null) view.setVisibility(enabled ? View.GONE : View.VISIBLE);
            }
            overlay.setBackgroundColor(enabled ? 0xD9000000 : 0x4D000000);
            // The card sits in the top part of its stage; the sheet stays below it.
            if (enabled) {
                sheetFractionBeforeCard = optionsScroll.maxHeightFraction;
                optionsScroll.maxHeightFraction = Math.min(sheetFractionBeforeCard, 0.5f);
            } else if (sheetFractionBeforeCard > 0f) {
                optionsScroll.maxHeightFraction = sheetFractionBeforeCard;
            }
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
            // The card has one element and one sheet: nothing to pick first, so it opens by itself
            // (once the stage is laid out). The lyrics screen starts closed, for picking.
            if (enabled) {
                hidePanelSheet(false);
                afterNextLayout(this::revealSheet);
            } else {
                hidePanelSheet(false);
            }
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
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            cardPreview = new com.eza.spicyex.lyrics.LiveLyricCardView(activity);
            card.addView(cardPreview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setOnClickListener(v -> selectElement(Element.CARD));
            // In the top part of the stage, under the editor's buttons, where the options sheet
            // (capped to half the height) never reaches it; beside the side sheet when wide.
            android.content.res.Configuration screen = activity.getResources().getConfiguration();
            boolean sideSheet = screen.screenWidthDp > screen.screenHeightDp || screen.screenWidthDp >= 600;
            int width = overlay.getWidth() > 0 ? overlay.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            int sheetWidth = sideSheet ? Math.min(dp(420), Math.round(width * 0.46f)) + dp(24) : 0;
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
            captionLp.topMargin = sideSheet ? dp(72) : dp(122);
            cardLayer.addView(caption, captionLp);
            // Above the lyrics-screen layers, below the editor's own buttons and the sheet.
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
                addOption(presetSliderRow(Settings.LIVE_CARD_TEXT_SIZE, Settings.LIVE_CARD_TEXT_SIZE_CUSTOM,
                        "custom", new String[]{"small", "normal", "large", "xlarge"},
                        new int[]{90, 100, 120, 150}, this::markCardDirty), matchWrap(12));
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
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    chip.getWidth(), chip.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            capture.setOnClickListener(v -> selectElement(element));
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
            // scroll position and leave the sheet exactly where it is.
            int previousScrollY = optionsScroll.getScrollY();
            boolean rebuildOnly = selected == element && panelVisible;
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
            // A tap on an element opens its sheet; drags and resizes select without covering the
            // canvas with it (they pass revealPanel = false).
            if (revealPanel && !panelVisible) showPanelSheet(true);
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

        // -- bottom sheet -------------------------------------------------------

        /** iOS sheet presentation curve: quick departure, long soft landing, no overshoot. */
        private static final android.animation.TimeInterpolator SHEET_EASE =
                new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
        /** Release speed past which a downward flick dismisses regardless of distance. */
        private static final int SHEET_DISMISS_VELOCITY_DP = 900;
        /** Share of its height the sheet must be pulled down to dismiss on a slow release. */
        private static final float SHEET_DISMISS_FRACTION = 0.3f;

        private void showPanelSheet(boolean animate) {
            panelVisible = true;
            if (panelContainer.getVisibility() != View.VISIBLE) {
                panelContainer.setTranslationY(sheetHiddenOffset());
                panelContainer.setVisibility(View.VISIBLE);
            }
            animateSheetTo(0f, 0f, animate, null);
        }

        private void hidePanelSheet(boolean animate) {
            hidePanelSheet(animate, 0f);
        }

        private void hidePanelSheet(boolean animate, float velocityPxPerSec) {
            panelVisible = false;
            animateSheetTo(sheetHiddenOffset(), velocityPxPerSec, animate,
                    () -> panelContainer.setVisibility(View.INVISIBLE));
        }

        /** Where the sheet goes when the finger lets go: a flick or a long enough pull closes it,
         *  anything else springs it back open. */
        private void settleSheet(float velocityPxPerSec) {
            float offset = panelContainer.getTranslationY();
            boolean dismiss = velocityPxPerSec > dp(SHEET_DISMISS_VELOCITY_DP)
                    || (velocityPxPerSec > -dp(SHEET_DISMISS_VELOCITY_DP) / 3f
                        && offset > sheetHiddenOffset() * SHEET_DISMISS_FRACTION);
            if (dismiss) {
                hidePanelSheet(true, velocityPxPerSec);
            } else {
                animateSheetTo(0f, velocityPxPerSec, true, null);
            }
        }

        /** One animation path for open, close and snap-back. The duration follows the remaining
         *  distance and the speed the finger left with, so a flick carries straight on instead of
         *  slowing down to a fixed-length glide. */
        private void animateSheetTo(float target, float velocityPxPerSec, boolean animate,
                Runnable endAction) {
            panelContainer.animate().cancel();
            if (!animate) {
                panelContainer.setTranslationY(target);
                if (endAction != null) endAction.run();
                return;
            }
            float distance = Math.abs(target - panelContainer.getTranslationY());
            long duration = 340L;
            float speed = Math.abs(velocityPxPerSec);
            if (speed > 1f) {
                // The curve leaves ~2.3x faster than its average speed; this matches the finger.
                duration = Math.round(1000f * 2.3f * distance / speed);
            }
            duration = Math.max(160L, Math.min(360L, duration));
            panelContainer.animate().translationY(target)
                    .setDuration(duration).setInterpolator(SHEET_EASE)
                    .withEndAction(endAction).start();
        }

        private float sheetHiddenOffset() {
            return Math.max(dp(120), panelContainer.getHeight());
        }

        /**
         * The options sheet. A downward drag moves it with the finger - from the grabber and title
         * at any time, from the option list once that list has nothing left to scroll up to,
         * including mid-gesture: scroll the list back to its top and keep pulling, and the same
         * gesture carries on into dragging the sheet, as on iOS.
         *
         * <p>Handled in dispatchTouchEvent rather than onInterceptTouchEvent because the list
         * blocks interception as soon as it starts scrolling, which is exactly the moment the
         * hand-off has to happen.
         */
        private final class SheetLayout extends LinearLayout {
            private final int touchSlop;
            private android.view.VelocityTracker velocity;
            private float downRawX;
            private float downRawY;
            private float lastRawY;
            /** Downward travel since the list was last able to scroll further up. */
            private float pull;
            private boolean dragging;
            private boolean fromHeader;
            /** A slider claimed this gesture on DOWN; never steal it. */
            private boolean childLocked;
            private boolean disallowRequested;

            SheetLayout(Activity activity) {
                super(activity);
                setOrientation(VERTICAL);
                // Swallow taps on the sheet's own padding so they never fall through to the
                // element outlines underneath it.
                setClickable(true);
                touchSlop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            }

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallow) {
                if (disallow) disallowRequested = true;
                super.requestDisallowInterceptTouchEvent(disallow);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                float rawY = event.getRawY();
                trackVelocity(event, action == MotionEvent.ACTION_DOWN);
                if (action == MotionEvent.ACTION_DOWN) {
                    dragging = false;
                    pull = 0f;
                    downRawX = event.getRawX();
                    downRawY = lastRawY = rawY;
                    fromHeader = event.getY() < optionsScroll.getTop();
                    disallowRequested = false;
                    super.dispatchTouchEvent(event);
                    childLocked = disallowRequested;
                    return true;
                }
                float dy = rawY - lastRawY;
                lastRawY = rawY;
                if (dragging) {
                    if (action == MotionEvent.ACTION_MOVE) {
                        setTranslationY(Math.max(0f,
                                Math.min(sheetHiddenOffset(), getTranslationY() + dy)));
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        dragging = false;
                        settleSheet(action == MotionEvent.ACTION_UP ? releaseVelocity() : 0f);
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE && !childLocked) {
                    boolean listAtTop = !optionsScroll.canScrollVertically(-1);
                    if ((fromHeader || listAtTop) && dy > 0f) {
                        pull += dy;
                    } else if (dy < 0f || !listAtTop) {
                        pull = 0f;
                    }
                    float sideways = Math.abs(event.getRawX() - downRawX);
                    if (pull > touchSlop && sideways < Math.abs(rawY - downRawY)) {
                        dragging = true;
                        animate().cancel();
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        return true;
                    }
                }
                return super.dispatchTouchEvent(event);
            }

            /** Tracks in screen coordinates: the sheet itself moves under the finger. */
            private void trackVelocity(MotionEvent event, boolean reset) {
                if (velocity == null) velocity = android.view.VelocityTracker.obtain();
                if (reset) velocity.clear();
                MotionEvent screen = MotionEvent.obtain(event);
                screen.setLocation(event.getRawX(), event.getRawY());
                velocity.addMovement(screen);
                screen.recycle();
            }

            private float releaseVelocity() {
                velocity.computeCurrentVelocity(1000);
                return velocity.getYVelocity();
            }
        }

        /** Recolors every currently-built capture outline for the current selection - green when
         *  it is the selection, gray otherwise - without rebuilding any of them. Cheap: just
         *  mutates each capture's existing GradientDrawable stroke color. */
        private void repaintAllCaptures() {
            boolean artworkSelected = selected == Element.ARTWORK || selected == Element.TRACK_TEXT;
            boolean trackTextSelected = selected == Element.TRACK_TEXT || selected == Element.ARTWORK;
            paintCapture(artCapture, artworkSelected);
            paintCapture(trackTextCapture, trackTextSelected);
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
                case SKIP: return s("element_skip", "Skip");
                case FOLLOW: return s("element_follow", "Follow");
                case DOCK: return s("element_top_bar", "Top bar");
                case CARD: return s("element_card", "Now playing card");
                default: return s("element_background", "Background");
            }
        }

        private void buildArtworkOptions() {
            beginGroup(strings.setting(Settings.TRACK_INFO_POSITION));
            addOption(chipRow(Settings.TRACK_INFO_POSITION,
                    new String[]{"Off", "Top", "Bottom"},
                    () -> {
                        refreshArtwork();
                        refreshTrackText();
                        selectElement(Element.ARTWORK);
                    }), matchWrap(12));

            addOption(text(s("artwork_hint", "Drag the handle at its corner to resize; "
                    + "drag the artwork itself up/down to reposition."), 12, 0x80FFFFFF, false), matchWrap(12));

            endGroup();
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
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_ALIGN));
            addOption(chipRow(Settings.TRACK_INFO_TEXT_ALIGN,
                    new String[]{"Top", "Center", "Bottom"}, null), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_SIZE));
            addOption(toggleRow(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                    strings.setting(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(8));
            if (!Boolean.TRUE.equals(store.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE))) {
                addOption(presetSliderRow(Settings.TRACK_INFO_TEXT_SIZE,
                        Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, "Custom",
                        new String[]{"Small", "Normal", "Large", "XLarge"},
                        new int[]{87, 100, 120, 147},
                        null), matchWrap(12));
            }

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_BACKGROUND));
            addOption(chipRow(Settings.TRACK_INFO_BACKGROUND,
                    new String[]{"Gradient", "Solid", "None"}, null), matchWrap(12));

            endGroup();
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
                    s("tab_effects", "Effects")}, textTab, index -> {
                textTab = index;
                selectElement(Element.TEXT);
            });
        }

        /** A sheet's tab bar: one pill, the current tab a white segment; picking one rebuilds the
         *  sheet (through {@code onPick}) and shows it from the top. */
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
            addOption(presetSliderRow(Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                    "custom", new String[]{"small", "normal", "large", "xlarge"},
                    new int[]{90, 100, 120, 150},
                    null), matchWrap(8));
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
            addOption(presetSliderRow(Settings.LINE_SPACING, Settings.LINE_SPACING_CUSTOM,
                    "custom", new String[]{"compact", "default", "spacious", "more", "max"},
                    new int[]{80, 110, 150, 200, 250}, null), matchWrap(14));

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
            addOption(chipRow(Settings.ENABLE_LINE_BLUR,
                    new String[]{"Off", "Slight", "Heavy"},
                    () -> selectElement(Element.TEXT)), matchWrap(8));
            if (!"Off".equals(store.get(Settings.ENABLE_LINE_BLUR))) {
                addOption(text(s("blur_intensity_hint", "Blur intensity - how much stronger "
                        + "the blur gets on lines further from the current one"),
                        12, 0x7AFFFFFF, false), matchWrap(6));
                addOption(settingSlider(Settings.LYRICS_BLUR_INTENSITY, "%", null), matchWrap(14));
            }
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

        /** A slider for an integer setting, with a magnetic detent at the setting's default so it
         *  is always easy to get back to. */
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
                            writer.put(Settings.LYRICS_FONT_CUSTOM_PATH, path);
                            writer.put(Settings.LYRICS_FONT, "custom");
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
                // Older copies are no longer referenced by the setting.
                java.io.File[] old = dir.listFiles();
                if (old != null) for (java.io.File f : old) if (!f.equals(out)) f.delete();
                return out.getAbsolutePath();
            } catch (Throwable t) {
                out.delete();
                return null;
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
            // internally for compatibility, but is no longer exposed as a separate button.
            beginGroup(strings.setting(Settings.FORCE_DARK_BACKGROUND));
            addOption(settingSlider(Settings.EXTRA_DARK_BACKGROUND, "%", null), matchWrap(12));

            endGroup();
        }

        private void buildSkipOptions() {
            beginGroup(strings.setting(Settings.SKIP_CHIP_POSITION));
            addOption(chipRow(Settings.SKIP_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshSkipChip();
                        refreshFollowChip();
                        selectElement(Element.SKIP);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.SKIP_CHIP_STYLE));
            addOption(chipRow(Settings.SKIP_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshSkipChip();
                        selectElement(Element.SKIP);
                    }), matchWrap(0));
        }

        private void buildFollowOptions() {
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_POSITION));
            addOption(chipRow(Settings.FOLLOW_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshFollowChip();
                        refreshSkipChip();
                        selectElement(Element.FOLLOW);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_STYLE));
            addOption(chipRow(Settings.FOLLOW_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshFollowChip();
                        selectElement(Element.FOLLOW);
                    }), matchWrap(12));

            // Only how the chip looks lives here; when follow resumes is behaviour, and stays in
            // the Settings panel.
            endGroup();
            beginGroup(s("follow_chip_look", "Chip"));
            addOption(toggleRow(Settings.FOLLOW_CHIP_ANIMATION,
                    strings.setting(Settings.FOLLOW_CHIP_ANIMATION), null), matchWrap(6));
            addOption(toggleRow(Settings.FOLLOW_CHIP_PROGRESS,
                    strings.setting(Settings.FOLLOW_CHIP_PROGRESS), null), matchWrap(0));
        }

        private void buildDockOptions() {
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
            beginGroup(strings.setting(Settings.FULLSCREEN_CONTROLS));
            addOption(chipRow(Settings.FULLSCREEN_CONTROLS,
                    new String[]{"5 seconds", "10 seconds", "30 seconds", "Always on"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(0));
        }

        private LinearLayout.LayoutParams sheetGrabberLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(5));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(10);
            return lp;
        }

        /** Purely visual: the whole sheet header is the drag target (see SheetLayout). */
        private View sheetGrabber() {
            View handle = new View(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x4DFFFFFF);
            bg.setCornerRadius(dp(3));
            handle.setBackground(bg);
            return handle;
        }

        // -- shared row builders ------------------------------------------------

        /** Chip labels always come from {@link SettingsUiStrings#option}, the same lookup the
         *  main Settings panel uses for this exact setting/value pair - so a value already
         *  labeled and translated there (most of them: Off/Top/Bottom/Regular/Small/...) reads
         *  identically here with no separate translation to maintain. */
        private LinearLayout chipRow(Settings.Setting<String> setting, String[] values,
                Runnable onChanged) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView[] chips = new TextView[values.length];
            String current = store.get(setting);
            for (int i = 0; i < values.length; i++) {
                TextView chip = chip(strings.option((Settings.StringSetting) setting, values[i]));
                chips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                row.addView(chip, lp);
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

        /** @param detent an optional value (typically the setting's own default) the thumb
         *  magnetically pulls to within a small tolerance band - easy to land on exactly, but a
         *  continued drag past the band releases it smoothly rather than needing a jump to escape.
         *  A thin tick mark on the track marks where it is. Null behaves exactly like the plain
         *  overload (no snap, no tick) - for settings like Extra darken/Background quality where
         *  every value is equally valid and a magnetic point would just be in the way. */
        private LinearLayout dragRow(int min, int max, int initial, String unit, Integer detent,
                IntConsumer onChange) {
            int[] value = {initial};
            boolean[] snapped = {detent != null && initial == detent};
            // Half a step's worth of range, so the snap band scales with the slider instead of
            // being a fixed px width that's too wide on a short range or invisible on a long one.
            int snapBand = detent == null ? 0 : Math.max(1, Math.round((max - min) * 0.025f));
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(initial + unit, CONTROL_TEXT_SP, Color.WHITE, false);
            valueLabel.setSingleLine(true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);

            View tick = null;
            if (detent != null) {
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
                    float detentFraction = (detent - min) / (float) Math.max(1, max - min);
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
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                boolean nowSnapped = detent != null && Math.abs(newValue - detent) <= snapBand;
                if (nowSnapped) newValue = detent;
                if (nowSnapped && !snapped[0]) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                snapped[0] = nowSnapped;
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(newValue + unit);
                    onChange.accept(newValue);
                }
                paint[0].run();
                return true;
            });
            return row;
        }

        /** One slider standing in for a "named preset chips + separate custom-% slider" pair:
         *  each preset is a magnetic detent on the same track (with a tick + label), and landing
         *  outside all of them commits "custom" at the dragged percent instead. Replaces having to
         *  pick a preset chip first, then a separate slider only appearing once "Custom" is
         *  already selected.
         *  @param presetValues the enum's stored values (e.g. "small","normal") in ascending
         *         percent order; presetPercents their equivalent points on the same 0-500 scale
         *         Settings#LYRICS_TEXT_SIZE_CUSTOM/TRACK_INFO_TEXT_SIZE_CUSTOM already use. */
        private LinearLayout presetSliderRow(Settings.Setting<String> modeSetting,
                Settings.IntegerSetting customSetting, String customValue,
                String[] presetValues, int[] presetPercents,
                Runnable onChanged) {
            int min = customSetting.minValue;
            int max = customSetting.maxValue;
            String mode = store.get(modeSetting);
            int presetIndex = -1;
            for (int i = 0; i < presetValues.length; i++) {
                if (presetValues[i].equalsIgnoreCase(mode)) {
                    presetIndex = i;
                    break;
                }
            }
            int[] value = {presetIndex >= 0 ? presetPercents[presetIndex] : safeGet(customSetting)};
            int[] snappedIndex = {presetIndex};
            int snapBand = Math.max(1, Math.round((max - min) * 0.025f));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(presetLabelFor(modeSetting, value[0], presetValues, presetPercents, snapBand),
                    13, Color.WHITE, false);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);

            View[] ticks = new View[presetPercents.length];
            for (int i = 0; i < presetPercents.length; i++) {
                View tick = new View(activity);
                tick.setBackgroundColor(0x80FFFFFF);
                track.addView(tick, new FrameLayout.LayoutParams(dp(2), dp(10), Gravity.CENTER_VERTICAL));
                ticks[i] = tick;
            }

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
                for (int i = 0; i < ticks.length; i++) {
                    float detentFraction = (presetPercents[i] - min) / (float) Math.max(1, max - min);
                    FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) ticks[i].getLayoutParams();
                    tickLp.leftMargin = thumbSize / 2 - dp(1) + Math.round(trackW * clamp01(detentFraction));
                    ticks[i].setLayoutParams(tickLp);
                }
            };
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
            track.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                int nearestPreset = nearestWithin(newValue, presetPercents, snapBand);
                if (nearestPreset >= 0) newValue = presetPercents[nearestPreset];
                if (nearestPreset != snappedIndex[0] && nearestPreset >= 0) {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                snappedIndex[0] = nearestPreset;
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(presetLabelFor(modeSetting, newValue, presetValues, presetPercents, snapBand));
                    if (nearestPreset >= 0) {
                        writer.put(modeSetting, presetValues[nearestPreset]);
                    } else {
                        writer.put(modeSetting, customValue);
                        writer.put(customSetting, newValue);
                    }
                    if (onChanged != null) onChanged.run();
                }
                paint[0].run();
                return true;
            });
            return row;
        }

        private static int nearestWithin(int value, int[] candidates, int band) {
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < candidates.length; i++) {
                int dist = Math.abs(value - candidates[i]);
                if (dist <= band && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }
            return best;
        }

        private String presetLabelFor(Settings.Setting<String> modeSetting, int value,
                String[] presetValues, int[] presetPercents, int band) {
            int match = nearestWithin(value, presetPercents, band);
            return match >= 0
                    ? strings.option((Settings.StringSetting) modeSetting, presetValues[match])
                    : value + "%";
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
            if (landscapeKey != null && !activity.getSharedPreferences("SpotifyPlus",
                    android.content.Context.MODE_PRIVATE).contains(landscapeKey)) {
                percent = Math.round(percent * com.eza.spicyex.lyrics.LyricsShellSettings.LANDSCAPE_FIT_SCALE);
            }
            return percent;
        }
    }

    /** Caps its own height to a fraction of whatever space its FrameLayout parent offers it, and
     *  scrolls the rest - a plain WRAP_CONTENT ScrollView here would still grow to fill nearly the
     *  entire available height before Android ever has reason to let it scroll, which in
     *  landscape's shorter screen means the options card sitting under (and blocking) the top
     *  bar rather than leaving room below it. */
    private static class MaxHeightScrollView extends android.widget.ScrollView {
        /** Share of the parent's height the list may take; the side sheet allows more. */
        float maxHeightFraction = 0.6f;

        MaxHeightScrollView(Activity activity) {
            super(activity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                int capped = Math.round(View.MeasureSpec.getSize(heightMeasureSpec) * maxHeightFraction);
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
