package com.eza.spicyex.lyrics;

import android.graphics.Color;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Registers renderer-owned mounted views for one syllable/word segment. */
public final class LyricsSyllableViewState {
    private static final Map<SyllableSegment, SyllableRenderState> STATES = new WeakHashMap<>();

    /** Apple lift motion active: bouncier spring constants for words and letters. */
    private static boolean appleMotion;

    private LyricsSyllableViewState() {
    }

    /**
     * Switches the motion constants. Resets live springs so in-flight words adopt the new
     * physics immediately instead of finishing on stale constants. Cheap no-op when unchanged;
     * safe to call per frame (covers first mount, unlike config-change-only sync).
     */
    public static void setAppleMotion(boolean enabled) {
        if (appleMotion == enabled) return;
        appleMotion = enabled;
        for (SyllableRenderState state : STATES.values()) {
            if (state == null) continue;
            state.ySpring = null;
            state.glowSpring = null;
            state.localScaleSpring = null;
            state.localYSpring = null;
            for (AnimatedLetterState letter : state.letters) {
                if (letter == null) continue;
                letter.ySpring = null;
                letter.glowSpring = null;
            }
        }
    }

    public static SpicyAnimatedTextView wordTextView(SyllableSegment segment) {
        return segment == null ? null : state(segment).textView;
    }

    public static SpicyAnimatedTextView romanizedTextView(SyllableSegment segment) {
        return segment == null ? null : state(segment).romanizedTextView;
    }

    public static void setWordView(SyllableSegment segment, View view) {
        if (segment != null) state(segment).view = view;
    }

    public static void configureWordMotion(SyllableSegment segment, View motionView,
                                           View containerView, boolean motionOwner) {
        if (segment == null) return;
        SyllableRenderState state = state(segment);
        state.motionView = motionView;
        state.followerMotionViews = new ArrayList<>();
        state.containerView = containerView;
        state.motionOwner = motionOwner;
        state.scaleSpring = null;
        state.ySpring = null;
        state.localScaleSpring = null;
        state.localYSpring = null;
        if (motionOwner && motionView != null) {
            // Pin the rest pivot at layout time with the same formula updateTextPivot resolves at
            // rest, so the mount state and animated state stay pixel-identical near row edges.
            motionView.removeOnLayoutChangeListener(REST_MOTION_PIVOT_LISTENER);
            motionView.addOnLayoutChangeListener(REST_MOTION_PIVOT_LISTENER);
        }
    }

    public static void setFollowerMotionViews(SyllableSegment segment, List<View> views) {
        if (segment == null) return;
        state(segment).followerMotionViews = new ArrayList<>(views);
        for (View view : views) {
            view.removeOnLayoutChangeListener(REST_MOTION_PIVOT_LISTENER);
            view.addOnLayoutChangeListener(REST_MOTION_PIVOT_LISTENER);
        }
    }

    private static final View.OnLayoutChangeListener REST_MOTION_PIVOT_LISTENER =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    applyRestMotionPivot((View) v);

    /** Rest pivot for a word motion view: leading-edge clamp near the row edges, else the group
     * center — exactly what {@link #updateTextPivot} computes at rest — with the baseline as the
     * vertical pivot. */
    private static void applyRestMotionPivot(View motion) {
        if (motion == null || motion.getWidth() <= 0 || motion.getHeight() <= 0) return;
        int parentWidth = motion.getParent() instanceof View
                ? ((View) motion.getParent()).getWidth() : 0;
        float pivotX = horizontalMotionPivot(motion.getLeft(), motion.getRight(),
                motion.getWidth(), parentWidth, motion.getWidth() / 2f);
        if (Math.abs(motion.getPivotX() - pivotX) > 0.5f) {
            motion.setPivotX(pivotX);
        }
        if (Math.abs(motion.getPivotY() - motion.getHeight()) > 0.5f) {
            motion.setPivotY(motion.getHeight());
        }
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
        if (state(segment).motionOwner) styleBatcher.invalidateRecursive(motionView(segment));
        styleBatcher.invalidateRecursive(state(segment).textView);
        styleBatcher.invalidateRecursive(state(segment).romanizedTextView);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter != null) styleBatcher.invalidateRecursive(letter.view);
        }
    }

    public static void style(SyllableSegment segment, FrameStyleBatcher styleBatcher, int baseTextSp, int color) {
        if (segment == null || state(segment).view == null || styleBatcher == null) return;
        styleBatcher.applyAlphaIfChanged(state(segment).view, 1.0f);
        if (state(segment).motionOwner) {
            View motion = motionView(segment);
            styleBatcher.applyScaleIfChanged(motion, 1f, 1f);
            styleBatcher.applyTranslationYIfChanged(motion, 0f);
        }
        if (hasGroupedMotion(segment)) {
            styleBatcher.applyScaleIfChanged(state(segment).view, 1f, 1f);
            styleBatcher.applyTranslationYIfChanged(state(segment).view, 0f);
        }
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
            letter.view.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
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

    public static boolean isMotionAttached(SyllableSegment segment) {
        View motion = motionView(segment);
        return segment != null && state(segment).motionOwner
                && motion != null && motion.isAttachedToWindow();
    }

    public static float stepWordScale(SyllableSegment segment, float targetScale, float deltaSeconds) {
        return stepWordScale(segment, targetScale, deltaSeconds, false);
    }

    public static float stepWordScale(SyllableSegment segment, float targetScale, float deltaSeconds,
                                      boolean direct) {
        if (segment == null) return targetScale;
        if (direct) return targetScale;
        ensureWordScaleSpring(segment, targetScale);
        state(segment).scaleSpring.setGoal(targetScale);
        return state(segment).scaleSpring.step(deltaSeconds);
    }

    public static float directWordScale(float targetScale) {
        return targetScale;
    }

    public static float directWordY(float targetY) {
        return targetY;
    }

    public static float stepWordY(SyllableSegment segment, float targetY, float deltaSeconds) {
        return stepWordY(segment, targetY, deltaSeconds, false);
    }

    public static float stepWordY(SyllableSegment segment, float targetY, float deltaSeconds,
                                  boolean direct) {
        if (segment == null) return targetY;
        if (direct) return targetY;
        ensureWordYSpring(segment, targetY);
        state(segment).ySpring.setGoal(targetY);
        return state(segment).ySpring.step(deltaSeconds);
    }

    public static float stepWordGlow(SyllableSegment segment, float targetGlow, float deltaSeconds) {
        if (segment == null) return targetGlow;
        ensureWordGlowSpring(segment);
        state(segment).glowSpring.setGoal(targetGlow);
        return state(segment).glowSpring.step(deltaSeconds);
    }

    public static float stepLocalWordScale(SyllableSegment segment, float targetScale,
                                           float deltaSeconds) {
        return stepLocalWordScale(segment, targetScale, deltaSeconds, false);
    }

    public static float stepLocalWordScale(SyllableSegment segment, float targetScale,
                                           float deltaSeconds, boolean direct) {
        if (segment == null) return targetScale;
        if (direct) return targetScale;
        ensureLocalWordScaleSpring(segment, targetScale);
        state(segment).localScaleSpring.setGoal(targetScale);
        return state(segment).localScaleSpring.step(deltaSeconds);
    }

    public static float stepLocalWordY(SyllableSegment segment, float targetY, float deltaSeconds) {
        return stepLocalWordY(segment, targetY, deltaSeconds, false);
    }

    public static float stepLocalWordY(SyllableSegment segment, float targetY, float deltaSeconds,
                                       boolean direct) {
        if (segment == null) return targetY;
        if (direct) return targetY;
        ensureLocalWordYSpring(segment, targetY);
        state(segment).localYSpring.setGoal(targetY);
        return state(segment).localYSpring.step(deltaSeconds);
    }

    public static void updateTextPivot(SyllableSegment segment, SyllableSegment focusSegment) {
        updateTextPivot(segment, focusSegment, 0.5f);
    }

    /**
     * Places the transform origin at the currently sung position inside the active word. This
     * keeps Apple's lift anchored to the karaoke edge instead of scaling the whole word around
     * its centre (which looks like a generic pop and makes the progress feel disconnected).
     */
    public static void updateTextPivot(SyllableSegment segment, SyllableSegment focusSegment,
                                       float focusProgress) {
        View motion = motionView(segment);
        if (segment == null || !state(segment).motionOwner || motion == null
                || motion.getHeight() <= 0) return;
        View focus = focusSegment == null ? null : state(focusSegment).view;
        float requestedPivot = motion.getWidth() / 2f;
        if (focus != null && focus != motion && focus.getWidth() > 0) {
            requestedPivot = offsetWithin(focus, motion)
                    + focus.getWidth() * Math.max(0f, Math.min(1f, focusProgress));
        } else if (focus == motion && motion.getWidth() > 0) {
            requestedPivot = motion.getWidth() * Math.max(0f, Math.min(1f, focusProgress));
        }
        float pivotX = horizontalMotionPivot(motion.getLeft(), motion.getRight(),
                motion.getWidth(), motion.getParent() instanceof View
                        ? ((View) motion.getParent()).getWidth() : 0, requestedPivot);
        float pivotY = motion.getHeight();
        if (Math.abs(motion.getPivotX() - pivotX) > 0.5f) {
            motion.setPivotX(pivotX);
        }
        if (Math.abs(motion.getPivotY() - pivotY) > 0.5f) {
            motion.setPivotY(pivotY);
        }
    }

    static float horizontalMotionPivot(int left, int right, int width, int parentWidth) {
        return horizontalMotionPivot(left, right, width, parentWidth, width / 2f);
    }

    static float horizontalMotionPivot(int left, int right, int width, int parentWidth,
                                       float requestedPivot) {
        if (width <= 0) return 0f;
        float expansion = width * ((1.025f - 1f) * 0.5f);
        if (left < expansion) return 0f;
        if (parentWidth > 0 && parentWidth - right < expansion) return width;
        return Math.max(0f, Math.min(width, requestedPivot));
    }

    public static void applyWordFrame(SyllableSegment segment, LyricsAnimationApplier.StyleSink sink,
                                       float scale, float y, float basePx) {
        View motion = motionView(segment);
        if (segment == null || !state(segment).motionOwner || motion == null || sink == null) return;
        sink.applyScale(motion, scale, scale);
        sink.applyTranslationY(motion, basePx * y);
        sink.applyAlpha(motion, 1.0f);
        for (View follower : state(segment).followerMotionViews) {
            sink.applyScale(follower, scale, scale);
            sink.applyTranslationY(follower, basePx * y);
        }
    }

    public static void applyLocalWordFrame(SyllableSegment segment,
                                           LyricsAnimationApplier.StyleSink sink,
                                           float scaleX, float scaleY, float y, float basePx) {
        if (segment == null || sink == null || !hasGroupedMotion(segment)) return;
        View word = state(segment).view;
        if (word == null) return;
        if (word.getHeight() > 0 && Math.abs(word.getPivotY() - word.getHeight()) > 0.5f) {
            word.setPivotY(word.getHeight());
        }
        sink.applyScale(word, scaleX, scaleY);
        sink.applyTranslationY(word, basePx * y);
        sink.applyAlpha(word, 1f);
    }

    public static void applyNeutralWordMotion(SyllableSegment segment,
                                              LyricsAnimationApplier.StyleSink sink) {
        if (segment == null || sink == null) return;
        snapWordMotionSprings(segment, 1f, 0f);
        snapLocalWordSprings(segment, 1f, 0f);
        if (state(segment).motionOwner) {
            View motion = motionView(segment);
            if (motion != null) {
                sink.applyScale(motion, 1f, 1f);
                sink.applyTranslationY(motion, 0f);
                sink.applyAlpha(motion, 1f);
            }
            for (View follower : state(segment).followerMotionViews) {
                sink.applyScale(follower, 1f, 1f);
                sink.applyTranslationY(follower, 0f);
            }
        }
        if (hasGroupedMotion(segment) && state(segment).view != null) {
            sink.applyScale(state(segment).view, 1f, 1f);
            sink.applyTranslationY(state(segment).view, 0f);
            sink.applyAlpha(state(segment).view, 1f);
        }
    }

    public static void applyWordGradient(SyllableSegment segment, float gradient, float glow) {
        applyWordGradient(segment, gradient, glow, 1f);
    }

    public static void applyWordGradient(SyllableSegment segment, float gradient, float glow, float brightness) {
        if (segment == null) return;
        applyTextGradient(state(segment).textView, gradient, glow, brightness);
        applyTextGradient(state(segment).romanizedTextView, gradient, glow, brightness);
    }

    /** Lights a whole word at once, for a line-synced row: fully sung gradient at the given
     *  brightness on the word and on every letter. */
    public static void applyLitFrame(SyllableSegment segment, float brightness) {
        if (segment == null) return;
        applyWordGradient(segment, LyricAnimations.GRADIENT_SUNG, 0f, brightness);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter == null || letter.view == null) continue;
            letter.view.setBrightnessMultiplier(brightness);
            letter.view.setGradientPosition(LyricAnimations.GRADIENT_SUNG, 0f);
        }
    }

    /** Reset every visual child of a word for an unsynced/static lyric row. */
    public static void applyStaticFrame(SyllableSegment segment, FrameStyleBatcher styleBatcher) {
        if (segment == null || styleBatcher == null) return;
        snapWordSprings(segment, 1f, 0f, 0f);
        snapLocalWordSprings(segment, 1f, 0f);
        View word = state(segment).view;
        if (word != null) {
            styleBatcher.applyAlphaIfChanged(word, 1f);
            styleBatcher.applyScaleIfChanged(word, 1f, 1f);
            styleBatcher.applyTranslationYIfChanged(word, 0f);
        }
        if (state(segment).motionOwner) {
            View motion = motionView(segment);
            styleBatcher.applyScaleIfChanged(motion, 1f, 1f);
            styleBatcher.applyTranslationYIfChanged(motion, 0f);
        }
        applyWordGradient(segment, LyricAnimations.GRADIENT_SUNG, 0f, 1f);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter == null || letter.view == null) continue;
            snapLetterSprings(letter, 1f, 0f, 0f);
            styleBatcher.applyAlphaIfChanged(letter.view, 1f);
            styleBatcher.applyScaleIfChanged(letter.view, 1f, 1f);
            styleBatcher.applyTranslationYIfChanged(letter.view, 0f);
            letter.view.setBrightnessMultiplier(1f);
            letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            letter.view.setGradientPosition(LyricAnimations.GRADIENT_SUNG, 0f);
        }
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
        return stepLetterScale(letter, targetScale, deltaSeconds, false);
    }

    public static float stepLetterScale(AnimatedLetterState letter, float targetScale, float deltaSeconds,
                                        boolean direct) {
        if (letter == null) return targetScale;
        if (direct) return targetScale;
        ensureLetterScaleSpring(letter, targetScale);
        letter.scaleSpring.setGoal(targetScale);
        return letter.scaleSpring.step(deltaSeconds);
    }

    public static float directLetterScale(float targetScale) {
        return targetScale;
    }

    public static float directLetterY(float targetY) {
        return targetY;
    }

    public static float stepLetterY(AnimatedLetterState letter, float targetY, float deltaSeconds) {
        return stepLetterY(letter, targetY, deltaSeconds, false);
    }

    public static float stepLetterY(AnimatedLetterState letter, float targetY, float deltaSeconds,
                                    boolean direct) {
        if (letter == null) return targetY;
        if (direct) return targetY;
        ensureLetterYSpring(letter, targetY);
        letter.ySpring.setGoal(targetY);
        return letter.ySpring.step(deltaSeconds);
    }

    public static float stepLetterGlow(AnimatedLetterState letter, float targetGlow, float deltaSeconds) {
        if (letter == null) return targetGlow;
        ensureLetterGlowSpring(letter);
        letter.glowSpring.setGoal(targetGlow);
        return letter.glowSpring.step(deltaSeconds);
    }

    public static void resetAnimatedWord(SyllableSegment segment,
                                         LyricsAnimationApplier.StyleSink sink,
                                         boolean motionEnabled,
                                         boolean liftMotion,
                                         boolean individualWordMotion) {
        View motion = motionView(segment);
        if (segment == null || sink == null) return;
        float inactiveScale = LyricsAnimationApplier.inactiveWordScale(
                motionEnabled, liftMotion);
        boolean grouped = hasGroupedMotion(segment);
        boolean letterUnits = individualWordMotion && letterCount(segment) > 1;
        float wrapperScale = motionEnabled && (!individualWordMotion
                || (!grouped && !letterUnits)) ? inactiveScale : 1f;
        float localScale = motionEnabled && individualWordMotion
                && grouped && !letterUnits ? inactiveScale : 1f;
        float letterScale = motionEnabled && letterUnits ? inactiveScale : 1f;
        snapWordSprings(segment, wrapperScale, 0f, 0f);
        snapLocalWordSprings(segment, localScale, 0f);
        if (state(segment).motionOwner && motion != null) {
            sink.applyScale(motion, wrapperScale, wrapperScale);
            sink.applyTranslationY(motion, 0f);
            sink.applyAlpha(motion, 1.0f);
        }
        if (hasGroupedMotion(segment) && state(segment).view != null) {
            sink.applyScale(state(segment).view, localScale, localScale);
            sink.applyTranslationY(state(segment).view, 0f);
            sink.applyAlpha(state(segment).view, 1f);
        }
        applyWordGradient(segment, LyricAnimations.GRADIENT_UNSUNG, 0f);
        for (AnimatedLetterState letter : state(segment).letters) {
            if (letter == null || letter.view == null) continue;
            snapLetterSprings(letter, letterScale, 0f, 0f);
            sink.applyScale(letter.view, letterScale, letterScale);
            sink.applyTranslationY(letter.view, 0f);
            if (letter.view.getTranslationX() != 0f) letter.view.setTranslationX(0f);
            sink.applyAlpha(letter.view, 1.0f);
            letter.view.setBrightnessMultiplier(1f);
            letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            letter.view.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
        }
    }

    public static View parentView(SyllableSegment segment) {
        if (segment == null || state(segment).view == null) return null;
        if (state(segment).containerView != null) return state(segment).containerView;
        Object parent = state(segment).view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    public static void resetWordTransform(SyllableSegment segment) {
        if (segment == null) return;
        snapWordMotionSprings(segment, 1f, 0f);
        snapLocalWordSprings(segment, 1f, 0f);
        if (state(segment).motionOwner) resetTransform(motionView(segment));
        for (View follower : state(segment).followerMotionViews) resetTransform(follower);
        if (hasGroupedMotion(segment)) resetTransform(state(segment).view);
    }

    public static boolean isSettled(SyllableSegment segment) {
        if (segment == null) return true;
        SyllableRenderState state = state(segment);
        if (!springAtRest(state.scaleSpring) || !springAtRest(state.ySpring)
                || !springAtRest(state.glowSpring) || !springAtRest(state.localScaleSpring)
                || !springAtRest(state.localYSpring)) return false;
        for (AnimatedLetterState letter : state.letters) {
            if (letter == null) continue;
            if (!springAtRest(letter.scaleSpring) || !springAtRest(letter.ySpring)
                    || !springAtRest(letter.glowSpring)) return false;
        }
        return true;
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

    private static void ensureWordScaleSpring(SyllableSegment segment, float initialScale) {
        if (state(segment).scaleSpring != null) return;
        state(segment).scaleSpring = new Spring(initialScale, 0.88f, 0.64f);
    }

    private static void ensureWordYSpring(SyllableSegment segment, float initialY) {
        if (state(segment).ySpring != null) return;
        state(segment).ySpring = appleMotion
                ? new Spring(initialY, 1.3f, 0.7f)
                : new Spring(initialY, 1.45f, 0.4f);
    }

    private static void ensureWordGlowSpring(SyllableSegment segment) {
        if (state(segment).glowSpring != null) return;
        state(segment).glowSpring = appleMotion
                ? new Spring(0f, 2.4f, 0.65f)
                : new Spring(0f, 1.18f, 0.56f);
    }

    private static void ensureLocalWordScaleSpring(SyllableSegment segment, float initialScale) {
        if (state(segment).localScaleSpring != null) return;
        state(segment).localScaleSpring = appleMotion
                ? new Spring(initialScale, 1.15f, 0.8f)
                : new Spring(initialScale, 0.88f, 0.64f);
    }

    private static void ensureLocalWordYSpring(SyllableSegment segment, float initialY) {
        if (state(segment).localYSpring != null) return;
        state(segment).localYSpring = appleMotion
                ? new Spring(initialY, 1.3f, 0.7f)
                : new Spring(initialY, 1.45f, 0.4f);
    }

    private static void snapWordSprings(SyllableSegment segment, float scale, float y, float glow) {
        SyllableRenderState state = state(segment);
        snapWordMotionSprings(segment, scale, y);
        if (state.glowSpring != null) state.glowSpring.snap(glow);
    }

    private static void snapWordMotionSprings(SyllableSegment segment, float scale, float y) {
        SyllableRenderState state = state(segment);
        if (state.scaleSpring != null) state.scaleSpring.snap(scale);
        if (state.ySpring != null) state.ySpring.snap(y);
    }

    private static void snapLocalWordSprings(SyllableSegment segment, float scale, float y) {
        SyllableRenderState state = state(segment);
        if (state.localScaleSpring != null) state.localScaleSpring.snap(scale);
        if (state.localYSpring != null) state.localYSpring.snap(y);
    }

    private static SyllableRenderState state(SyllableSegment segment) {
        SyllableRenderState state = STATES.get(segment);
        if (state == null) {
            state = new SyllableRenderState();
            STATES.put(segment, state);
        }
        return state;
    }

    private static View motionView(SyllableSegment segment) {
        if (segment == null) return null;
        SyllableRenderState state = state(segment);
        return state.motionView == null ? state.view : state.motionView;
    }

    private static boolean hasGroupedMotion(SyllableSegment segment) {
        if (segment == null) return false;
        SyllableRenderState state = state(segment);
        return state.view != null && state.motionView != null && state.motionView != state.view;
    }

    private static void resetTransform(View view) {
        if (view == null) return;
        if (Math.abs(view.getScaleX() - 1f) > 0.002f) view.setScaleX(1f);
        if (Math.abs(view.getScaleY() - 1f) > 0.002f) view.setScaleY(1f);
        if (Math.abs(view.getTranslationY()) > 0.5f) view.setTranslationY(0f);
    }

    private static void ensureLetterScaleSpring(AnimatedLetterState letter, float initialScale) {
        if (letter.scaleSpring != null) return;
        letter.scaleSpring = new Spring(initialScale, 0.6f, 0.7f);
    }

    private static void ensureLetterYSpring(AnimatedLetterState letter, float initialY) {
        if (letter.ySpring != null) return;
        letter.ySpring = appleMotion
                ? new Spring(initialY, 1.3f, 0.7f)
                : new Spring(initialY, 1.25f, 0.4f);
    }

    private static void ensureLetterGlowSpring(AnimatedLetterState letter) {
        if (letter.glowSpring != null) return;
        letter.glowSpring = appleMotion
                ? new Spring(0f, 2.4f, 0.65f)
                : new Spring(0f, 1f, 0.5f);
    }

    private static void snapLetterSprings(AnimatedLetterState letter,
                                          float scale, float y, float glow) {
        if (letter.scaleSpring != null) letter.scaleSpring.snap(scale);
        if (letter.ySpring != null) letter.ySpring.snap(y);
        if (letter.glowSpring != null) letter.glowSpring.snap(glow);
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
        View gradientContainer = isDescendantOf(view, container) ? container : parentView(view);
        int gradientWidth = gradientContainer == container
                ? containerWidth : gradientContainer == null ? 0 : gradientContainer.getWidth();
        if (gradientContainer != null && gradientWidth > 0 && view.isAttachedToWindow()) {
            view.setContainerGradientPosition(
                    gradient, glow, gradientWidth, offsetWithin(view, gradientContainer));
        } else {
            view.setGradientPosition(gradient, glow);
        }
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        if (child == null || ancestor == null) return false;
        for (View current = child; current != null; current = parentView(current)) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static View parentView(View view) {
        if (view == null) return null;
        Object parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
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

    private static boolean springAtRest(Spring spring) {
        return spring == null || spring.isAtRest(0.0025f, 0.0025f);
    }
}
