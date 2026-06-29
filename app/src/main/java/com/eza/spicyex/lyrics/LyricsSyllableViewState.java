package com.eza.spicyex.lyrics;

import android.graphics.Color;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/** Registers renderer-owned mounted views for one syllable/word segment. */
public final class LyricsSyllableViewState {
    private static final Map<SyllableSegment, SyllableRenderState> STATES = new WeakHashMap<>();

    private LyricsSyllableViewState() {
    }

    public static void setWordView(SyllableSegment segment, View view) {
        if (segment != null) state(segment).view = view;
    }

    public static void clear(SyllableSegment segment) {
        if (segment != null) state(segment).clear();
    }

    public static void clearRomanizedTextView(SyllableSegment segment) {
        if (segment != null) state(segment).romanizedTextView = null;
    }

    public static void setRomanizedTextView(SyllableSegment segment, SpicyAnimatedTextView view) {
        if (segment != null) state(segment).romanizedTextView = view;
    }

    public static void clearLetters(SyllableSegment segment) {
        if (segment != null) state(segment).letters.clear();
    }

    public static void addLetter(SyllableSegment segment, AnimatedLetterState letter) {
        if (segment != null) state(segment).letters.add(letter);
    }

    public static void clearTextView(SyllableSegment segment) {
        if (segment != null) state(segment).textView = null;
    }

    public static void setTextView(SyllableSegment segment, SpicyAnimatedTextView view) {
        if (segment != null) state(segment).textView = view;
    }

    public static void invalidate(SyllableSegment segment, FrameStyleBatcher styleBatcher) {
        if (segment == null || styleBatcher == null) return;
        styleBatcher.invalidateRecursive(state(segment).view);
        styleBatcher.invalidateRecursive(state(segment).textView);
        styleBatcher.invalidateRecursive(state(segment).romanizedTextView);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter != null) styleBatcher.invalidateRecursive(letter.view);
        }
    }

    public static void style(SyllableSegment segment, FrameStyleBatcher styleBatcher, int baseTextSp, int color) {
        if (segment == null || state(segment).view == null || styleBatcher == null) return;
        styleBatcher.applyAlphaIfChanged(state(segment).view, 1.0f);
        styleBatcher.applyScaleIfChanged(state(segment).view, 0.95f, 0.95f);
        styleBatcher.applyTranslationYIfChanged(state(segment).view, 0f);
        if (state(segment).textView != null) {
            state(segment).textView.setTextColor(color);
            state(segment).textView.setTextSize(baseTextSp);
            state(segment).textView.setBrightnessMultiplier(1f);
            state(segment).textView.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        }
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter == null || letter.view == null) continue;
            letter.view.setTextColor(color);
            letter.view.setTextSize(baseTextSp);
            styleBatcher.applyScaleIfChanged(letter.view, 1.0f, 1.0f);
            styleBatcher.applyTranslationYIfChanged(letter.view, 0f);
            styleBatcher.applyAlphaIfChanged(letter.view, 1.0f);
            letter.view.setBrightnessMultiplier(1f);
            letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            letter.view.setGradientPosition(-20f, 0f);
        }
    }

    public static View wordView(SyllableSegment segment) {
        return segment == null ? null : state(segment).view;
    }

    public static boolean isWordAttached(SyllableSegment segment) {
        return segment != null
                && state(segment).view != null
                && state(segment).view.isAttachedToWindow();
    }

    public static float stepWordScale(SyllableSegment segment, float targetScale, float deltaSeconds) {
        if (segment == null) return targetScale;
        ensureWordSprings(segment);
        state(segment).scaleSpring.setGoal(targetScale);
        return state(segment).scaleSpring.step(deltaSeconds);
    }

    public static float stepWordY(SyllableSegment segment, float targetY, float deltaSeconds) {
        if (segment == null) return targetY;
        ensureWordSprings(segment);
        state(segment).ySpring.setGoal(targetY);
        return state(segment).ySpring.step(deltaSeconds);
    }

    public static float stepWordGlow(SyllableSegment segment, float targetGlow, float deltaSeconds) {
        if (segment == null) return targetGlow;
        ensureWordSprings(segment);
        state(segment).glowSpring.setGoal(targetGlow);
        return state(segment).glowSpring.step(deltaSeconds);
    }

    public static void updateTextPivot(SyllableSegment segment) {
        if (segment == null || state(segment).textView == null || state(segment).textView.getHeight() <= 0) return;
        int baseline = state(segment).textView.getBaseline();
        state(segment).textView.setPivotX(state(segment).textView.getWidth() / 2f);
        state(segment).textView.setPivotY(baseline > 0 ? baseline : state(segment).textView.getHeight());
    }

    public static void applyWordFrame(SyllableSegment segment, LyricsAnimationApplier.StyleSink sink,
                                      float scale, float y, float basePx) {
        if (segment == null || state(segment).view == null || sink == null) return;
        sink.applyScale(state(segment).view, scale, scale);
        sink.applyTranslationY(state(segment).view, basePx * y);
        sink.applyAlpha(state(segment).view, 1.0f);
    }

    public static void applyWordGradient(SyllableSegment segment, float gradient, float glow) {
        applyWordGradient(segment, gradient, glow, 1f);
    }

    public static void applyWordGradient(SyllableSegment segment, float gradient, float glow, float brightness) {
        if (segment == null) return;
        applyTextGradient(state(segment).textView, gradient, glow, brightness);
        applyTextGradient(state(segment).romanizedTextView, gradient, glow, brightness);
    }

    public static int letterCount(SyllableSegment segment) {
        return segment == null || state(segment).letters == null ? 0 : state(segment).letters.size();
    }

    public static AnimatedLetterState letterAt(SyllableSegment segment, int index) {
        if (segment == null || state(segment).letters == null || index < 0 || index >= state(segment).letters.size()) return null;
        return state(segment).letters.get(index);
    }

    public static void applyLetterFrame(AnimatedLetterState letter, LyricsAnimationApplier.StyleSink sink,
                                        float scale, float y, float basePx, float gradient, float glow) {
        applyLetterFrame(letter, sink, scale, y, basePx, gradient, glow, 1f);
    }

    public static void applyLetterFrame(AnimatedLetterState letter, LyricsAnimationApplier.StyleSink sink,
                                        float scale, float y, float basePx, float gradient, float glow,
                                        float brightness) {
        if (letter == null || letter.view == null || sink == null) return;
        sink.applyScale(letter.view, scale, scale);
        sink.applyTranslationY(letter.view, basePx * y * 2f);
        sink.applyAlpha(letter.view, 1.0f);
        letter.view.setBrightnessMultiplier(brightness);
        letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        letter.view.setGradientPosition(gradient, glow);
    }

    public static float stepLetterScale(AnimatedLetterState letter, float targetScale, float deltaSeconds) {
        if (letter == null) return targetScale;
        ensureLetterSprings(letter);
        letter.scaleSpring.setGoal(targetScale);
        return letter.scaleSpring.step(deltaSeconds);
    }

    public static float stepLetterY(AnimatedLetterState letter, float targetY, float deltaSeconds) {
        if (letter == null) return targetY;
        ensureLetterSprings(letter);
        letter.ySpring.setGoal(targetY);
        return letter.ySpring.step(deltaSeconds);
    }

    public static float stepLetterGlow(AnimatedLetterState letter, float targetGlow, float deltaSeconds) {
        if (letter == null) return targetGlow;
        ensureLetterSprings(letter);
        letter.glowSpring.setGoal(targetGlow);
        return letter.glowSpring.step(deltaSeconds);
    }

    public static void resetAnimatedWord(SyllableSegment segment, LyricsAnimationApplier.StyleSink sink) {
        if (segment == null || state(segment).view == null || sink == null) return;
        sink.applyScale(state(segment).view, 0.95f, 0.95f);
        sink.applyTranslationY(state(segment).view, 0f);
        sink.applyAlpha(state(segment).view, 1.0f);
        applyWordGradient(segment, -20f, 0f);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter == null || letter.view == null) continue;
            sink.applyScale(letter.view, 1.0f, 1.0f);
            sink.applyTranslationY(letter.view, 0f);
            sink.applyAlpha(letter.view, 1.0f);
            letter.view.setBrightnessMultiplier(1f);
            letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            letter.view.setGradientPosition(-20f, 0f);
        }
    }

    public static View parentView(SyllableSegment segment) {
        if (segment == null || state(segment).view == null) return null;
        Object parent = state(segment).view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    public static void resetWordTransform(SyllableSegment segment) {
        if (segment == null || state(segment).view == null) return;
        state(segment).view.setScaleX(1f);
        state(segment).view.setScaleY(1f);
        state(segment).view.setTranslationY(0f);
    }

    public static void applySyntheticLineGradient(SyllableSegment segment, View container,
                                                  int containerWidth, float gradient, float glow) {
        applySyntheticLineGradient(segment, container, containerWidth, gradient, glow, 1f);
    }

    public static void applySyntheticLineGradient(SyllableSegment segment, View container,
                                                  int containerWidth, float gradient, float glow,
                                                  float brightness) {
        if (segment == null) return;
        applyContainerGradient(state(segment).textView, container, containerWidth, gradient, glow, brightness);
        applyContainerGradient(state(segment).romanizedTextView, container, containerWidth, gradient, glow, brightness);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter != null) {
                applyContainerGradient(letter.view, container, containerWidth, gradient, glow, brightness);
            }
        }
    }

    private static void ensureWordSprings(SyllableSegment segment) {
        if (state(segment).scaleSpring != null && state(segment).ySpring != null && state(segment).glowSpring != null) return;
        state(segment).scaleSpring = new Spring(0.95f, 0.7f, 0.6f);
        state(segment).ySpring = new Spring(0.01f, 1.25f, 0.4f);
        state(segment).glowSpring = new Spring(0f, 1f, 0.5f);
    }

    private static SyllableRenderState state(SyllableSegment segment) {
        SyllableRenderState state = STATES.get(segment);
        if (state == null) {
            state = new SyllableRenderState();
            STATES.put(segment, state);
        }
        return state;
    }

    private static void ensureLetterSprings(AnimatedLetterState letter) {
        if (letter.scaleSpring != null && letter.ySpring != null && letter.glowSpring != null) return;
        letter.scaleSpring = new Spring(1f, 0.6f, 0.7f);
        letter.ySpring = new Spring(0f, 1.25f, 0.4f);
        letter.glowSpring = new Spring(0f, 1f, 0.5f);
    }

    private static void applyTextGradient(SpicyAnimatedTextView view, float gradient, float glow, float brightness) {
        if (view == null) return;
        view.setBrightnessMultiplier(brightness);
        view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        view.setGradientPosition(gradient, glow);
    }

    private static void applyContainerGradient(SpicyAnimatedTextView view, View container,
                                               int containerWidth, float gradient, float glow, float brightness) {
        if (view == null) return;
        view.setBrightnessMultiplier(brightness);
        if (container != null && containerWidth > 0 && view.isAttachedToWindow()) {
            view.setContainerGradientPosition(gradient, glow, containerWidth, offsetWithin(view, container));
        } else {
            view.setGradientPosition(gradient, glow);
        }
    }

    private static float offsetWithin(View child, View ancestor) {
        float x = 0f;
        View current = child;
        while (current != null && current != ancestor) {
            x += current.getLeft() + current.getTranslationX();
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return x;
    }
}
