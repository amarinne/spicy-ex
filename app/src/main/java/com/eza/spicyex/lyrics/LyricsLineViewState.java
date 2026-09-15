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
        return effectiveBaseTextSp(line, false);
    }

    public static int effectiveBaseTextSp(AppliedLine line, boolean appleCompactText) {
        if (line == null) return 0;
        if (state(line).baseTextSp > 0) return state(line).baseTextSp;
        return appleCompactText
                ? LyricVisuals.appleLyricTextSizeSp(line.text)
                : LyricVisuals.lyricTextSizeSp(line.text);
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

    public static SpicyAnimatedTextView romanView(AppliedLine line) {
        return line == null ? null : state(line).romanView;
    }

    public static void setTranslationView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null) state(line).translationView = view;
    }

    public static SpicyAnimatedTextView translationView(AppliedLine line) {
        return line == null ? null : state(line).translationView;
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

    public static void applyStaticFrame(AppliedLine line, FrameStyleBatcher styleBatcher) {
        if (line == null || styleBatcher == null || state(line).rowView == null) return;
        styleBatcher.applyAlphaIfChanged(state(line).rowView, 1f);
        styleBatcher.queueBlurIfChanged(state(line).rowView, 0f, 0.25f);
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

    public static void applyRowFrame(AppliedLine line, FrameStyleBatcher styleBatcher, float opacity, float blurPx) {
        if (line == null || styleBatcher == null || state(line).rowView == null) return;
        styleBatcher.applyAlphaIfChanged(state(line).rowView, opacity);
        // A negative blurPx means the caller has handed this row's RenderEffect off to a different
        // owner for this frame (see LyricsFrameRenderer's top-melt window) - skip touching it here.
        // Both paths ultimately call View.setRenderEffect on the same row; letting both write in
        // the same frame is a last-write-wins race between a plain blur and a masked+faded one,
        // which read as the row's blur popping in and out during scroll.
        if (blurPx >= 0f) styleBatcher.queueBlurIfChanged(state(line).rowView, blurPx, 0.25f);
    }

    public static void applyLineShadow(AppliedLine line, float intensity) {
        if (line == null) return;
        AppliedLineRenderState st = state(line);
        if (st.mainView != null) st.mainView.setLineShadow(intensity);
        View container = line.words != null && !line.words.isEmpty()
                ? LyricsSyllableViewState.parentView(line.words.get(0)) : null;
        if (container instanceof GlowFlexbox) ((GlowFlexbox) container).setLineShadowIntensity(intensity);
    }

    public static boolean hasMainView(AppliedLine line) {
        return line != null && state(line).mainView != null;
    }

    public static void applyMainScale(AppliedLine line, FrameStyleBatcher styleBatcher, float scale) {
        if (line == null || styleBatcher == null || state(line).mainView == null) return;
        styleBatcher.applyScaleIfChanged(state(line).mainView, scale, scale);
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
        applyLineLevelGradient(line, gradient, glow, 1f, Float.NaN);
    }

    public static void applyLineLevelGradient(AppliedLine line, float gradient, float glow, float brightness) {
        applyLineLevelGradient(line, gradient, glow, brightness, Float.NaN);
    }

    public static void applyLineLevelGradient(AppliedLine line, float gradient, float glow, float brightness,
                                              float bandWidth) {
        if (line == null) return;
        View row = state(line).rowView;
        boolean blockGradient = row != null && row.getHeight() > 0
                && state(line).mainView != null && state(line).mainView.usesVerticalGradient();
        if (state(line).mainView != null) {
            state(line).mainView.setBrightnessMultiplier(brightness);
            applyLineGradientView(state(line).mainView, row, blockGradient, gradient, glow, bandWidth);
        }
        if (state(line).romanView != null) {
            state(line).romanView.setBrightnessMultiplier(brightness);
            applyLineGradientView(state(line).romanView, row, blockGradient, gradient, glow, bandWidth);
        }
        if (state(line).translationView != null) {
            if (blockGradient) {
                applyLineGradientView(state(line).translationView, row, true, gradient, glow, bandWidth);
            } else {
                state(line).translationView.setGradientPosition(LyricAnimations.GRADIENT_SUNG, 0f);
            }
        }
    }

    private static void applyLineGradientView(SpicyAnimatedTextView view, View row,
                                              boolean blockGradient, float gradient, float glow) {
        applyLineGradientView(view, row, blockGradient, gradient, glow, Float.NaN);
    }

    private static void applyLineGradientView(SpicyAnimatedTextView view, View row,
                                              boolean blockGradient, float gradient, float glow,
                                              float bandWidth) {
        if (view == null) return;
        view.setGradientBandWidth(bandWidth);
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
            state(line).lineScaleSpring = new Spring(targetScale, 1.0f, 0.7f);
        }
        state(line).lineScaleSpring.setGoal(targetScale);
        return state(line).lineScaleSpring.step(frameDelta(deltaSeconds));
    }

    public static float stepLineGlow(AppliedLine line, float targetGlow, float deltaSeconds) {
        if (line == null) return targetGlow;
        if (state(line).lineGlowSpring == null) {
            state(line).lineGlowSpring = new Spring(0f, 1.2f, 1.0f);
        }
        state(line).lineGlowSpring.setGoal(targetGlow);
        return clamp(state(line).lineGlowSpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static float stepBlur(AppliedLine line, float targetBlurPx, float deltaSeconds) {
        if (line == null) return targetBlurPx;
        if (state(line).blurSpring == null) {
            // 1.4Hz critically-damped settles in ~0.6-0.7s - reported live as blur reading
            // "weak" on some lines (the target can change again, e.g. during a fast scroll,
            // before the spring ever gets close to it) and as slow to clear after releasing a
            // manual scroll hold. 3.6Hz settles in roughly 0.2-0.25s instead, still critically
            // damped (no overshoot - blur overshooting its target would look like a glitch, not
            // a bounce), just proportionally faster to actually reach it.
            state(line).blurSpring = new Spring(targetBlurPx, 3.6f, 1.0f);
        }
        state(line).blurSpring.setGoal(targetBlurPx);
        return Math.max(0f, state(line).blurSpring.step(frameDelta(deltaSeconds)));
    }

    public static void snapBlur(AppliedLine line) {
        if (line == null) return;
        if (state(line).blurSpring != null) state(line).blurSpring.snap(0f);
    }

    public static void applyTopMeltMask(AppliedLine line, float t0, float t1, float blurPx) {
        if (line == null || android.os.Build.VERSION.SDK_INT < 31) return;
        AppliedLineRenderState st = state(line);
        View row = st.rowView;
        if (row == null) return;
        int height = row.getHeight();
        float ct0 = clamp(t0, 0f, 1f);
        float ct1 = clamp(t1, 0f, 1f);
        if (height <= 0 || Math.max(ct0, ct1) <= 0.01f) return;
        if (height == st.lastTopMeltHeight
                && Math.abs(ct0 - st.lastTopMeltT0) <= 0.004f
                && Math.abs(ct1 - st.lastTopMeltT1) <= 0.004f
                && Math.abs(blurPx - st.lastTopMeltBlurPx) <= 0.15f) {
            return;
        }
        st.lastTopMeltHeight = height;
        st.lastTopMeltT0 = ct0;
        st.lastTopMeltT1 = ct1;
        st.lastTopMeltBlurPx = blurPx;
        if (row.getLayerType() != View.LAYER_TYPE_HARDWARE) row.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        android.graphics.RenderEffect content = blurPx < 0.4f
                ? android.graphics.RenderEffect.createOffsetEffect(0f, 0f)
                : android.graphics.RenderEffect.createBlurEffect(
                        blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP);
        android.graphics.Shader maskShader = new android.graphics.LinearGradient(
                0f, 0f, 0f, height,
                android.graphics.Color.argb(Math.round(255f * (1f - ct0)), 0, 0, 0),
                android.graphics.Color.argb(Math.round(255f * (1f - ct1)), 0, 0, 0),
                android.graphics.Shader.TileMode.CLAMP);
        row.setRenderEffect(android.graphics.RenderEffect.createBlendModeEffect(
                content, android.graphics.RenderEffect.createShaderEffect(maskShader),
                android.graphics.BlendMode.DST_IN));
        AnimTracer.noteRenderEffectWrite(row, "topMelt", "t0=" + ct0 + " t1=" + ct1 + " blur=" + blurPx);
    }

    public static float stepLineShadow(AppliedLine line, float targetIntensity, float deltaSeconds) {
        if (line == null) return targetIntensity;
        if (state(line).lineShadowSpring == null) {
            state(line).lineShadowSpring = new Spring(0f, 0.5f, 1.0f);
        }
        state(line).lineShadowSpring.setGoal(targetIntensity);
        return clamp(state(line).lineShadowSpring.step(frameDelta(deltaSeconds)), 0f, 1f);
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
        return state.needsRender || state.lastTargetClass != targetClass || !isSettled(line);
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
                || !springAtRest(state.lineGlowSpring) || !springAtRest(state.dotMainScaleSpring)
                || !springAtRest(state.dotMainOpacitySpring) || !springAtRest(state.blurSpring)
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
            boolean showTranslation,
            LyricsSecondaryRowUpdater.TranslationAppender translationAppender,
            LyricsSecondaryRowUpdater.RomanAppender romanAppender
    ) {
        if (line == null || state(line).rowView == null) return false;
        boolean needsNewRoman = romanChanged && showRomanization && !isBlank(roman) && state(line).romanView == null;
        boolean needsNewTranslation = translatedChanged && showTranslation && !isBlank(translated)
                && state(line).translationView == null;
        if (needsNewRoman) {
            // Same reasoning as the translation branch below: grow the row in place via its own
            // LayoutTransition when possible (lines with no per-word timing, the common case),
            // instead of tearing it down and popping it back in at full size. Lines whose
            // romanization must restructure the row's word views (per-word/timed/furigana) aren't
            // eligible - see appendRomanView - and fall back to the previous full-rebuild behavior.
            if (romanAppender == null || !romanAppender.append(line)) {
                clear(line, mountedRowsHost, invalidation);
            }
            return true;
        }
        if (needsNewTranslation) {
            // A translation arriving for a line already on screen (the common async-AI-layer
            // case) grows the row in place - via its own LayoutTransition - instead of tearing
            // the whole row down and popping it back in at full size.
            if (translationAppender == null || !translationAppender.append(line)) {
                clear(line, mountedRowsHost, invalidation);
            }
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
