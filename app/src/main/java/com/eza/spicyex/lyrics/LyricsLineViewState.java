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
        if (line != null) state(line).rowView = row;
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
        if (line != null) state(line).mainView = view;
    }

    public static void setRomanView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null) state(line).romanView = view;
    }

    public static void setTranslationView(AppliedLine line, SpicyAnimatedTextView view) {
        if (line != null) state(line).translationView = view;
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
    }

    public static void applyRowFrame(AppliedLine line, FrameStyleBatcher styleBatcher, float opacity, float blurPx) {
        if (line == null || styleBatcher == null || state(line).rowView == null) return;
        styleBatcher.applyAlphaIfChanged(state(line).rowView, opacity);
        styleBatcher.queueBlurIfChanged(state(line).rowView, blurPx, 0.25f);
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
        applyLineLevelGradient(line, gradient, glow, 1f);
    }

    public static void applyLineLevelGradient(AppliedLine line, float gradient, float glow, float brightness) {
        if (line == null) return;
        if (state(line).mainView != null) {
            state(line).mainView.setBrightnessMultiplier(brightness);
            state(line).mainView.setGradientPosition(gradient, glow);
        }
        if (state(line).romanView != null) {
            state(line).romanView.setBrightnessMultiplier(brightness);
            state(line).romanView.setGradientPosition(gradient, glow);
        }
        if (state(line).translationView != null) state(line).translationView.setGradientPosition(100f, 0f);
    }

    public static void applySpotlightSecondaryGradient(AppliedLine line, float spotGlow) {
        if (line == null) return;
        if (state(line).romanView != null && state(line).mainView == null) {
            state(line).romanView.setGradientPosition(100f, spotGlow);
        }
        if (state(line).translationView != null) {
            state(line).translationView.setGradientPosition(100f, 0f);
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface Invalidation {
        void invalidate(AppliedLine line);
    }
}
