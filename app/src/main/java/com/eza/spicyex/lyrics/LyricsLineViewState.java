package com.eza.spicyex.lyrics;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Clears renderer-owned view and spring references stored on applied lyric rows. */
public final class LyricsLineViewState {
    private static final Map<AppliedLine, AppliedLineRenderState> STATES = new WeakHashMap<>();

    private LyricsLineViewState() {
    }

    public static void setBaseTextSp(AppliedLine line, int baseTextSp) {
        if (line != null) state(line).baseTextSp = baseTextSp;
    }

    public static int baseTextSp(AppliedLine line) {
        return line == null ? 0 : state(line).baseTextSp;
    }

    public static int effectiveBaseTextSp(AppliedLine line) {
        if (line == null) return 0;
        return state(line).baseTextSp > 0 ? state(line).baseTextSp : LyricVisuals.lyricTextSizeSp(line.text);
    }

    public static void clearMainView(AppliedLine line) {
        if (line != null) state(line).mainView = null;
    }

    public static void setRowView(AppliedLine line, View row) {
        if (line != null) {
            state(line).rowView = row;
            state(line).needsRender = true;
        }
    }

    public static View rowView(AppliedLine line) {
        return line == null ? null : state(line).rowView;
    }

    public static View attachedRowView(AppliedLine line, ViewGroup mountedRowsHost) {
        View row = rowView(line);
        return row != null && row.getParent() == mountedRowsHost ? row : null;
    }

    public static boolean isMounted(AppliedLine line, ViewGroup mountedRowsHost) {
        return attachedRowView(line, mountedRowsHost) != null;
    }

    public static void setMainView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line == null) return;
        state(line).mainView = view;
        if (view == null) return;
        // Inactive line-level rows mount at 0.95 scale. TextView's default center pivot would
        // create a leading inset until the row becomes active and gets another render frame.
        view.setPivotX(line.oppositeAligned ? view.getWidth() : 0f);
        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) ->
                updateMainScalePivot(line));
    }

    public static void setRomanView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null) state(line).romanView = view;
    }

    public static void setTranslationView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null) state(line).translationView = view;
    }

    public static void setTimedRomanRow(AppliedLine line, View view) {
        if (line != null) state(line).timedRomanRow = view;
    }

    public static View translationView(AppliedLine line) {
        return line == null ? null : state(line).translationView;
    }

    /** Reading and translation rows mounted under this line's main text, top to bottom. */
    public static List<View> secondaryViews(AppliedLine line) {
        List<View> views = new ArrayList<>(3);
        if (line == null) return views;
        AppliedLineRenderState st = state(line);
        if (st.timedRomanRow != null) views.add(st.timedRomanRow);
        if (st.romanView != null) views.add(st.romanView);
        if (st.translationView != null) views.add(st.translationView);
        return views;
    }

    public static void beginDotViews(AppliedLine line) {
        if (line != null) state(line).dotViews = new ArrayList<>();
    }

    public static void addDotView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null && state(line).dotViews != null) state(line).dotViews.add(view);
    }

    public static boolean updateMeasuredHeight(AppliedLine line, int heightPx) {
        if (line == null || state(line).measuredHeightPx == heightPx) return false;
        state(line).measuredHeightPx = heightPx;
        return true;
    }

    public static int measuredHeightPx(AppliedLine line) {
        return line == null ? 0 : state(line).measuredHeightPx;
    }

    public static void invalidate(AppliedLine line, FrameStyleBatcher styleBatcher) {
        if (line == null || styleBatcher == null) return;
        styleBatcher.invalidateRecursive(state(line).rowView);
        styleBatcher.invalidateRecursive(state(line).mainView);
        styleBatcher.invalidateRecursive(state(line).romanView);
        styleBatcher.invalidateRecursive(state(line).translationView);
        if (state(line).dotViews == null) return;
        for (SpicyAnimatedTextView dot : state(line).dotViews) {
            styleBatcher.invalidateRecursive(dot);
        }
    }

    public static void styleMain(AppliedLine line, FrameStyleBatcher styleBatcher, int baseTextSp, int color) {
        if (line == null || styleBatcher == null || state(line).mainView == null) return;
        state(line).mainView.setTextColor(color);
        state(line).mainView.setTextSize(baseTextSp);
        styleBatcher.applyAlphaIfChanged(state(line).mainView, 1.0f);
        state(line).mainView.setShadowLayer(0, 0, 0, android.graphics.Color.TRANSPARENT);
    }

    /** Load-reveal fade factor for this row. Owned by the shell's entrance stepper and folded into
     *  the row's alpha here, so the reveal composes with the renderer's own opacity instead of two
     *  animators writing the same View property. */
    public static void setEntranceProgress(AppliedLine line, float progress) {
        setEntranceProgress(line, progress, 0f);
    }

    /** @param blurPx extra blur the reveal adds while the row is still arriving. */
    public static void setEntranceProgress(AppliedLine line, float progress, float blurPx) {
        if (line == null) return;
        float clamped = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        AppliedLineRenderState st = state(line);
        st.entranceBlurPx = clamped >= 1f ? 0f : Math.max(0f, blurPx);
        // Landing exactly on 1 always publishes, however small the last step was: that write is
        // what takes the row back out of the reveal for good.
        if (clamped >= 1f ? st.entranceProgress >= 1f
                : Math.abs(st.entranceProgress - clamped) < 0.0005f) return;
        st.entranceProgress = clamped;
        // The renderer skips rows it believes are settled; the reveal is the one motion it has no
        // spring for, so it has to be told the row still needs a frame.
        st.needsRender = true;
    }

    public static float entranceProgress(AppliedLine line) {
        return line == null ? 1f : state(line).entranceProgress;
    }

    public static void applyStaticFrame(AppliedLine line, FrameStyleBatcher styleBatcher) {
        if (line == null || styleBatcher == null || state(line).rowView == null) return;
        styleBatcher.applyAlphaIfChanged(state(line).rowView, state(line).entranceProgress);
        styleBatcher.queueBlurIfChanged(state(line).rowView, state(line).entranceBlurPx, 0.25f);
        if (state(line).mainView != null) {
            styleBatcher.applyScaleIfChanged(state(line).mainView, 1f, 1f);
            state(line).mainView.setBrightnessMultiplier(1f);
            state(line).mainView.setGradientPosition(100f, 0f);
        }
        if (state(line).romanView != null) {
            state(line).romanView.setBrightnessMultiplier(1f);
            state(line).romanView.setGradientPosition(100f, 0f);
        }
        if (state(line).translationView != null) state(line).translationView.setGradientPosition(100f, 0f);
        if (line.words != null) {
            for (SyllableSegment word : line.words) {
                LyricsSyllableViewState.applyStaticFrame(word, styleBatcher);
            }
        }
    }

    /** Blur at which a row has faded by the full {@link #BLUR_FADE}. */
    private static final float BLUR_FADE_FULL_PX = 9f;
    /** Opacity a fully blurred row gives up. */
    private static final float BLUR_FADE = 0.42f;

    public static void applyRowFrame(AppliedLine line, FrameStyleBatcher styleBatcher, float opacity, float blurPx) {
        if (line == null || styleBatcher == null || state(line).rowView == null) return;
        float blur = Math.max(blurPx, state(line).entranceBlurPx);
        // Apple's out-of-focus lines don't just soften, they recede: dimming in step with the
        // blur lets the smeared glyphs dissolve into the background instead of sitting on top
        // of it as a bright haze.
        float fade = 1f - BLUR_FADE * Math.min(1f, Math.max(0f, blurPx) / BLUR_FADE_FULL_PX);
        styleBatcher.applyAlphaIfChanged(state(line).rowView,
                opacity * fade * state(line).entranceProgress);
        styleBatcher.queueBlurIfChanged(state(line).rowView, blur, 0.25f);
    }

    public static void applyLineShadow(AppliedLine line, float intensity) {
        if (line == null) return;
        AppliedLineRenderState st = state(line);
        if (st.mainView != null) st.mainView.setLineShadow(intensity);
        View container = line.words != null && !line.words.isEmpty()
                ? LyricsSyllableViewState.parentView(line.words.get(0)) : null;
        if (container instanceof GlowFlexbox) ((GlowFlexbox) container).setLineShadowIntensity(intensity);
    }

    public static View mainViewOf(AppliedLine line) {
        return line == null ? null : state(line).mainView;
    }

    public static boolean hasMainView(AppliedLine line) {
        return line != null && state(line).mainView != null;
    }

    public static void applyMainScale(AppliedLine line, FrameStyleBatcher styleBatcher, float scale) {
        if (line == null || styleBatcher == null || state(line).mainView == null) return;
        styleBatcher.applyScaleIfChanged(state(line).mainView, scale, scale);
    }

    private static final int[] FIT_LOC = new int[2];

    /**
     * Largest zoom at or below {@code scale} at which a line's text still keeps {@code marginPx}
     * from the screen edges. The zoom pivots on the line's start edge, so a long line grew past the
     * far edge (or sat pressed against it). Never below 1: the cap only trims the enlargement.
     */
    public static float fitScale(View view, boolean oppositeAligned, float scale, float marginPx) {
        if (view == null || scale <= 1f || view.getWidth() <= 0) return scale;
        View root = view.getRootView();
        int screenWidth = root == null ? 0 : root.getWidth();
        if (screenWidth <= 0) return scale;
        float[] extent = textExtent(view);
        if (extent == null) return scale;
        view.getLocationInWindow(FIT_LOC);
        // getLocationInWindow includes the current scale about the pivot; undo it to get the
        // unscaled left edge.
        float current = view.getScaleX();
        float pivot = view.getPivotX();
        float left = FIT_LOC[0] - pivot * (1f - current);
        float max;
        if (oppositeAligned) {
            float span = view.getWidth() - extent[0];
            max = span <= 0f ? scale : (left + view.getWidth() - marginPx) / span;
        } else {
            max = extent[1] <= 0f ? scale : (screenWidth - marginPx - left) / extent[1];
        }
        return Math.max(1f, Math.min(scale, max));
    }

    /** [left, right] of the drawn text inside {@code view}, in its own unscaled coordinates. */
    private static float[] textExtent(View view) {
        if (view instanceof android.widget.TextView) {
            android.text.Layout layout = ((android.widget.TextView) view).getLayout();
            if (layout == null) return null;
            float l = Float.MAX_VALUE, r = 0f;
            for (int i = 0; i < layout.getLineCount(); i++) {
                l = Math.min(l, layout.getLineLeft(i));
                r = Math.max(r, layout.getLineRight(i));
            }
            float pad = ((android.widget.TextView) view).getTotalPaddingLeft();
            return new float[]{l + pad, r + pad};
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            float l = Float.MAX_VALUE, r = 0f;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) continue;
                l = Math.min(l, child.getLeft());
                r = Math.max(r, child.getRight());
            }
            return r <= 0f ? null : new float[]{l, r};
        }
        return new float[]{0f, view.getWidth()};
    }

    /**
     * Pins the line's scale pivot to its start edge (vertical centre). A view scales about its
     * centre until a pivot is set, and a row settled on the fast frame path never got one: dimmed
     * lines (scale 0.95) then shrank toward the middle, sitting visibly indented from the edge,
     * and jumped back to it when they became active. Cheap when already right.
     */
    public static void ensureScalePivots(AppliedLine line) {
        if (line == null) return;
        updateMainScalePivot(line);
        if (state(line).mainView == null && line.words != null && !line.words.isEmpty()) {
            View container = LyricsSyllableViewState.parentView(line.words.get(0));
            if (container != null && container.getWidth() > 0 && container.getHeight() > 0) {
                float pivotX = line.oppositeAligned ? container.getWidth() : 0f;
                float pivotY = container.getHeight() * 0.5f;
                if (Math.abs(container.getPivotX() - pivotX) > 0.5f) container.setPivotX(pivotX);
                if (Math.abs(container.getPivotY() - pivotY) > 0.5f) container.setPivotY(pivotY);
            }
        }
    }

    public static void updateMainScalePivot(AppliedLine line) {
        if (line == null || state(line).mainView == null) return;
        int width = state(line).mainView.getWidth();
        int height = state(line).mainView.getHeight();
        if (width <= 0 || height <= 0) return;
        float pivotX = line.oppositeAligned ? width : 0f;
        float pivotY = height * 0.5f;
        if (Math.abs(state(line).mainView.getPivotX() - pivotX) > 0.5f) state(line).mainView.setPivotX(pivotX);
        if (Math.abs(state(line).mainView.getPivotY() - pivotY) > 0.5f) state(line).mainView.setPivotY(pivotY);
    }

    public static void applyLineLevelGradient(AppliedLine line, float gradient, float glow) {
        applyLineLevelGradient(line, gradient, glow, 1f);
    }

    public static void applyLineLevelGradient(AppliedLine line, float gradient, float glow, float brightness) {
        if (line == null) return;
        View row = state(line).rowView;
        boolean blockGradient = row != null && row.getHeight() > 0
                && state(line).mainView != null && state(line).mainView.usesVerticalGradient();
        if (state(line).mainView != null) {
            state(line).mainView.setBrightnessMultiplier(brightness);
            applyLineGradientView(state(line).mainView, row, blockGradient, gradient, glow);
        }
        if (state(line).romanView != null) {
            state(line).romanView.setBrightnessMultiplier(brightness);
            applyLineGradientView(state(line).romanView, row, blockGradient, gradient, glow);
        }
        if (state(line).translationView != null) {
            if (blockGradient) {
                applyLineGradientView(state(line).translationView, row, true, gradient, glow);
            } else {
                state(line).translationView.setGradientPosition(LyricAnimations.GRADIENT_SUNG, 0f);
            }
        }
    }

    private static void applyLineGradientView(SpicyAnimatedTextView view, View row,
                                              boolean blockGradient, float gradient, float glow) {
        if (view == null) return;
        if (blockGradient && row != null) {
            view.setContainerVerticalGradientPosition(gradient, glow, row.getHeight(), view.getTop());
        } else {
            view.setGradientPosition(gradient, glow);
        }
    }

    public static void applyLineSecondaryGradient(AppliedLine line, float gradient, float glow) {
        if (line == null) return;
        if (state(line).romanView != null && state(line).mainView == null) {
            state(line).romanView.setGradientPosition(gradient, glow);
        }
        if (state(line).translationView != null) {
            state(line).translationView.setGradientPosition(100f, 0f);
        }
    }

    public static float stepOpacity(AppliedLine line, float target, float deltaSeconds) {
        if (line == null) return target;
        if (state(line).opacitySpring == null) {
            state(line).opacitySpring = new Spring(target, 1.85f, 1.0f);
        }
        state(line).opacitySpring.setGoal(target);
        return clamp(state(line).opacitySpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static float stepLineScale(AppliedLine line, float targetScale, float deltaSeconds) {
        if (line == null) return targetScale;
        if (state(line).lineScaleSpring == null) {
            state(line).lineScaleSpring = new Spring(targetScale, 2.2f, 0.85f);
        }
        state(line).lineScaleSpring.setGoal(targetScale);
        return state(line).lineScaleSpring.step(frameDelta(deltaSeconds));
    }

    /** Brightness a lit line reaches, as a multiplier of the unsung level. */
    private static final float UNSUNG_TO_SUNG_RATIO = 0.35f / 0.85f;

    /**
     * A line-synced row has no timing inside the line, so there is nothing to sweep: the whole
     * line lights when it starts. Stepped as a quick, critically damped fade rather than a switch,
     * and returned as the brightness multiplier to use with a fully sung gradient - at 0 that
     * reproduces the unsung colour exactly, at 1 the sung one.
     */
    public static float stepLineLit(AppliedLine line, boolean lit, float deltaSeconds) {
        if (line == null) return lit ? 1f : UNSUNG_TO_SUNG_RATIO;
        float target = lit ? 1f : 0f;
        AppliedLineRenderState st = state(line);
        // Only lighting up fades: that happens on the active row, which renders every frame.
        // Going back to unsung (a seek backwards) snaps, because a row that is no longer active
        // may not be rendered again until it next changes and would keep a half-lit colour.
        if (st.lineLitSpring == null || !lit) st.lineLitSpring = new Spring(target, 2.6f, 1.0f);
        st.lineLitSpring.setGoal(target);
        float value = Math.max(0f, Math.min(1f, st.lineLitSpring.step(frameDelta(deltaSeconds))));
        return UNSUNG_TO_SUNG_RATIO + (1f - UNSUNG_TO_SUNG_RATIO) * value;
    }

    public static float stepLineGlow(AppliedLine line, float targetGlow, float deltaSeconds) {
        if (line == null) return targetGlow;
        if (state(line).lineGlowSpring == null) {
            // Start where the row should already be. From 0, every row mounted as the window
            // scrolled - including long-sung ones - re-glowed over about a second, and ran the full
            // per-syllable frame path (re-rendering its cached blur layer) the whole time.
            state(line).lineGlowSpring = new Spring(targetGlow, 1.2f, 1.0f);
        }
        state(line).lineGlowSpring.setGoal(targetGlow);
        return clamp(state(line).lineGlowSpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static float stepLineShadow(AppliedLine line, float targetIntensity, float deltaSeconds) {
        if (line == null) return targetIntensity;
        if (state(line).lineShadowSpring == null) {
            state(line).lineShadowSpring = new Spring(0f, 0.5f, 1.0f);
        }
        state(line).lineShadowSpring.setGoal(targetIntensity);
        return clamp(state(line).lineShadowSpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static float stepLineBlur(AppliedLine line, float targetBlurPx, float deltaSeconds) {
        if (line == null) return targetBlurPx;
        AppliedLineRenderState st = state(line);
        if (st.lineBlurSpring == null) {
            // Snappy but controlled spring (2.8Hz, 0.92 damping) for a firm, "elastic"
            // feel when blur follows the active line transition.
            st.lineBlurSpring = new Spring(targetBlurPx, 2.8f, 0.92f);
        }
        st.lineBlurSpring.setGoal(targetBlurPx);
        return Math.max(0f, st.lineBlurSpring.step(frameDelta(deltaSeconds)));
    }

    public static boolean hasDotViews(AppliedLine line) {
        return line != null && state(line).dotViews != null && !state(line).dotViews.isEmpty();
    }

    public static List<SpicyAnimatedTextView> dotViews(AppliedLine line) {
        if (line == null || state(line).dotViews == null) return Collections.emptyList();
        return state(line).dotViews;
    }

    public static float stepDotMainScale(AppliedLine line, float targetScale, float deltaSeconds) {
        if (line == null) return targetScale;
        ensureDotSprings(line);
        state(line).dotMainScaleSpring.setGoal(targetScale);
        return state(line).dotMainScaleSpring.step(deltaSeconds);
    }

    public static float stepDotMainOpacity(AppliedLine line, float targetOpacity, float deltaSeconds) {
        if (line == null) return targetOpacity;
        ensureDotSprings(line);
        state(line).dotMainOpacitySpring.setGoal(targetOpacity);
        return state(line).dotMainOpacitySpring.step(deltaSeconds);
    }

    public static boolean needsFrame(AppliedLine line, int targetClass) {
        if (line == null) return false;
        AppliedLineRenderState state = state(line);
        if (state.needsRender || state.lastTargetClass != targetClass) return true;
        // Walking every syllable and letter spring of every mounted row each frame was a large
        // share of the frame loop. Once a row is fully settled only its opacity and blur springs
        // can move (the fast path steps just those), so check only them until the full path runs.
        if (state.settledExceptFade) {
            return !springAtRest(state.opacitySpring) || !springAtRest(state.lineBlurSpring);
        }
        boolean settled = isSettled(line);
        state.settledExceptFade = settled;
        return !settled;
    }

    /** The full frame path is about to move this row's springs again. */
    public static void invalidateSettled(AppliedLine line) {
        if (line != null) state(line).settledExceptFade = false;
    }

    public static void markFrameApplied(AppliedLine line, int targetClass) {
        if (line == null) return;
        state(line).needsRender = false;
        state(line).lastTargetClass = targetClass;
    }

    public static boolean isSettled(AppliedLine line) {
        if (line == null) return true;
        AppliedLineRenderState state = state(line);
        if (!springAtRest(state.opacitySpring) || !springAtRest(state.lineScaleSpring)
                || !springAtRest(state.lineGlowSpring) || !springAtRest(state.lineBlurSpring)
                || !springAtRest(state.dotMainScaleSpring)
                || !springAtRest(state.dotMainOpacitySpring)
                || !springAtRest(state.lineShadowSpring)) return false;
        if (line.words != null) {
            for (SyllableSegment segment : line.words) {
                if (!LyricsSyllableViewState.isSettled(segment)) return false;
            }
        }
        return true;
    }

    public static void clear(AppliedLine line, ViewGroup mountedRowsHost, Invalidation invalidation) {
        if (line == null) return;
        if (state(line).rowView != null && state(line).rowView.getParent() == mountedRowsHost) {
            mountedRowsHost.removeView(state(line).rowView);
        }
        if (invalidation != null) invalidation.invalidate(line);
        state(line).clearMounts();
        if (line.words == null) return;
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            LyricsSyllableViewState.clear(seg);
        }
    }

    public static boolean applySecondaryTextUpdate(
            AppliedLine line,
            ViewGroup mountedRowsHost,
            Invalidation invalidation,
            boolean romanChanged,
            String roman,
            boolean showRomanization,
            boolean translatedChanged,
            String translated,
            boolean showTranslation
    ) {
        if (line == null || state(line).rowView == null) return false;
        boolean needsNewViews =
                (romanChanged && showRomanization && !isBlank(roman) && state(line).romanView == null)
                        || (translatedChanged && showTranslation && !isBlank(translated) && state(line).translationView == null);
        if (needsNewViews) {
            clear(line, mountedRowsHost, invalidation);
            return true;
        }
        if (romanChanged && state(line).romanView != null) state(line).romanView.setText(roman);
        if (translatedChanged && state(line).translationView != null) state(line).translationView.setText(translated);
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static AppliedLineRenderState state(AppliedLine line) {
        AppliedLineRenderState state = STATES.get(line);
        if (state == null) {
            state = new AppliedLineRenderState();
            STATES.put(line, state);
        }
        return state;
    }

    private static void ensureDotSprings(AppliedLine line) {
        if (state(line).dotMainScaleSpring != null && state(line).dotMainOpacitySpring != null) return;
        state(line).dotMainScaleSpring = new Spring(0f, 0.72f, 0.74f);
        state(line).dotMainOpacitySpring = new Spring(0f, 0.9f, 0.82f);
    }

    private static float frameDelta(float deltaSeconds) {
        return Math.max(0.001f, Math.min(0.08f, deltaSeconds));
    }

    private static boolean springAtRest(Spring spring) {
        return spring == null || spring.isAtRest(0.0025f, 0.0025f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface Invalidation {
        void invalidate(AppliedLine line);
    }
}
