package com.eza.spicyex.lyrics;

import android.graphics.Color;
import android.view.View;

/** Applies per-frame lyric row animation to mounted renderer views. */
public final class LyricsAnimationApplier {
    private LyricsAnimationApplier() {
    }

    public static float stepLineOpacity(AppliedLine line, boolean active, boolean sung, float deltaSeconds) {
        if (line == null) return 1f;
        // Sung (already-played) lines stay close to the current line so they read as "still here"
        // rather than graying out like the not-yet-sung lines below — more immersive. Upcoming lines
        // are clearly dimmer. (Distance still reads via blur.)
        float target = active ? 1.0f : (sung ? 0.82f : 0.42f);
        if (line.dotLine && !active) target *= 0.75f;
        if (line.bgLine && !active) target *= 0.90f;
        if (line.opacitySpring == null) {
            line.opacitySpring = new Spring(active ? 1.0f : target, 1.85f, 1.0f);
        }
        line.opacitySpring.setGoal(target);
        return clamp(line.opacitySpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static float stepLineScale(AppliedLine line, float targetScale, float deltaSeconds) {
        if (line == null) return targetScale;
        if (line.lineScaleSpring == null) line.lineScaleSpring = new Spring(targetScale, 1.0f, 0.7f);
        line.lineScaleSpring.setGoal(targetScale);
        return line.lineScaleSpring.step(frameDelta(deltaSeconds));
    }

    /** Eased line glow — rises gradually when the line activates, decays after it passes. Always
     *  starts from 0 (never the current target) so a line that mounts mid-play still fades in. */
    public static float stepLineGlow(AppliedLine line, float targetGlow, float deltaSeconds) {
        if (line == null) return targetGlow;
        if (line.lineGlowSpring == null) line.lineGlowSpring = new Spring(0f, 1.2f, 1.0f);
        line.lineGlowSpring.setGoal(targetGlow);
        return clamp(line.lineGlowSpring.step(frameDelta(deltaSeconds)), 0f, 1f);
    }

    public static void animateSyllables(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink
    ) {
        animateSyllables(line, positionMs, deltaSeconds, basePx, sink, false);
    }

    public static void animateSyllables(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink,
            boolean spotlight
    ) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (SyllableSegment seg : line.words) {
            if (seg == null || seg.view == null || !seg.view.isAttachedToWindow()) continue;
            ensureSyllableSprings(seg);
            float progress = progress01(positionMs, seg.startMs, seg.endMs);
            boolean active = positionMs >= seg.startMs && positionMs < seg.endMs;
            boolean sung = positionMs >= seg.endMs;
            float targetScale = active ? LyricAnimations.scaleSpline(progress) : (sung ? 1.0f : 0.95f);
            float targetY = active ? LyricAnimations.yOffsetSpline(progress) : (sung ? 0f : 0.01f);
            // Spotlight: no per-word fill — the active word is lit solid and its glow builds with
            // the word's progress (gradual, not an instant pop), then the spring decays it.
            float targetGlow = spotlight
                    ? (active ? 0.60f * progress : 0f)
                    : (active ? 0.55f * LyricAnimations.glowSpline(progress) : 0f);
            float targetGradient = spotlight
                    ? (active || sung ? 100f : -20f)
                    : (active ? (-20f + 120f * progress) : (sung ? 100f : -20f));
            seg.scaleSpring.setGoal(targetScale);
            seg.ySpring.setGoal(targetY);
            seg.glowSpring.setGoal(targetGlow);
            float scale = seg.scaleSpring.step(deltaSeconds);
            float y = seg.ySpring.step(deltaSeconds);
            float glow = seg.glowSpring.step(deltaSeconds);
            if (seg.textView != null && seg.textView.getHeight() > 0) {
                int baseline = seg.textView.getBaseline();
                seg.textView.setPivotX(seg.textView.getWidth() / 2f);
                seg.textView.setPivotY(baseline > 0 ? baseline : seg.textView.getHeight());
            }
            sink.applyScale(seg.view, scale, scale);
            sink.applyTranslationY(seg.view, basePx * y);
            sink.applyAlpha(seg.view, 1.0f);
            if (seg.textView != null) {
                seg.textView.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                seg.textView.setGradientPosition(targetGradient, glow);
            }
            if (seg.romanizedTextView != null) {
                seg.romanizedTextView.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                seg.romanizedTextView.setGradientPosition(targetGradient, glow);
            }

            if (seg.letters == null || seg.letters.isEmpty()) continue;
            float timeAlpha = (float) Math.sin(progress * (Math.PI / 2d));
            float letterAnchor = LyricAnimations.activeLetterPosition(seg.letters, timeAlpha);
            for (int letterIndex = 0; letterIndex < seg.letters.size(); letterIndex++) {
                AnimatedLetterState letter = seg.letters.get(letterIndex);
                if (letter == null || letter.view == null) continue;
                ensureLetterSprings(letter);
                float letterTime = timeAlpha - letter.start;
                float letterTimeScale = clamp01(letterTime / Math.max(0.0001f, letter.duration));
                float glowTimeScale = clamp01(letterTime / Math.max(0.0001f, letter.glowDuration));
                float distance = Math.abs(letterIndex - letterAnchor);
                float motionFalloff = LyricAnimations.letterMotionFalloff(distance);
                float glowFalloff = LyricAnimations.letterGlowFalloff(distance);
                float targetLetterScale = 1f + ((LyricAnimations.letterScaleSpline(letterTimeScale) - 1f) * motionFalloff);
                float targetLetterY = LyricAnimations.letterYOffsetSpline(letterTimeScale) * motionFalloff;
                float targetLetterGlow = spotlight
                        ? (active ? 0.6f : 0f)
                        : LyricAnimations.glowSpline(glowTimeScale) * glowFalloff;
                float letterEnd = letter.start + letter.duration;
                float letterGradient;
                if (spotlight) letterGradient = active || sung ? 100f : -20f;
                else if (timeAlpha >= letterEnd) letterGradient = 100f;
                else if (timeAlpha <= letter.start) letterGradient = -20f;
                else letterGradient = -20f + 120f * LyricAnimations.easeSinOut(letterTimeScale);
                letter.scaleSpring.setGoal(targetLetterScale);
                letter.ySpring.setGoal(targetLetterY);
                letter.glowSpring.setGoal(targetLetterGlow);
                float letterScale = letter.scaleSpring.step(deltaSeconds);
                float letterY = letter.ySpring.step(deltaSeconds);
                float letterGlow = letter.glowSpring.step(deltaSeconds);
                sink.applyScale(letter.view, letterScale, letterScale);
                sink.applyTranslationY(letter.view, basePx * letterY * 2f);
                sink.applyAlpha(letter.view, 1.0f);
                letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                letter.view.setGradientPosition(letterGradient, letterGlow);
            }
        }
    }

    public static void resetSyllables(AppliedLine line, StyleSink sink) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (SyllableSegment seg : line.words) {
            if (seg == null || seg.view == null) continue;
            sink.applyScale(seg.view, 0.95f, 0.95f);
            sink.applyTranslationY(seg.view, 0f);
            sink.applyAlpha(seg.view, 1.0f);
            if (seg.textView != null) {
                seg.textView.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                seg.textView.setGradientPosition(-20f, 0f);
            }
            if (seg.romanizedTextView != null) {
                seg.romanizedTextView.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                seg.romanizedTextView.setGradientPosition(-20f, 0f);
            }
            if (seg.letters == null) continue;
            for (AnimatedLetterState letter : seg.letters) {
                if (letter == null || letter.view == null) continue;
                sink.applyScale(letter.view, 1.0f, 1.0f);
                sink.applyTranslationY(letter.view, 0f);
                sink.applyAlpha(letter.view, 1.0f);
                letter.view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
                letter.view.setGradientPosition(-20f, 0f);
            }
        }
    }

    public static void animateInterludeDots(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float dotBasePx,
            StyleSink sink
    ) {
        if (line == null || line.dotViews == null || line.dotViews.isEmpty() || sink == null) return;
        ensureDotSprings(line);
        float lineProgress = progress01(positionMs, line.startMs, line.endMs);
        boolean preHide = line.endMs > line.startMs && positionMs >= line.endMs - LyricTimeline.PRE_HIDDEN_DOT_LINE_MS;
        line.dotMainScaleSpring.setGoal(preHide ? 0f : LyricAnimations.dotMainScaleSpline(lineProgress));
        line.dotMainOpacitySpring.setGoal(preHide ? 0f : LyricAnimations.dotMainOpacitySpline(lineProgress));
        float mainScale = line.dotMainScaleSpring.step(deltaSeconds);
        float mainOpacity = line.dotMainOpacitySpring.step(deltaSeconds);
        for (int i = 0; i < line.dotViews.size(); i++) {
            SpicyAnimatedTextView dot = line.dotViews.get(i);
            if (dot == null) continue;
            float pulse = LyricAnimations.dotPulse(positionMs, i);
            float stagger = Math.max(0f, Math.min(1f, lineProgress * 1.25f - i * 0.09f));
            float dotScale = mainScale * LyricAnimations.dotScaleSpline(stagger) * pulse;
            float dotY = dotBasePx * LyricAnimations.dotYOffsetSpline(stagger);
            float glow = LyricAnimations.dotGlowSpline(stagger) * mainOpacity;
            float opacity = mainOpacity * LyricAnimations.dotOpacitySpline(stagger);
            sink.applyScale(dot, dotScale, dotScale);
            sink.applyTranslationY(dot, dotY);
            sink.applyAlpha(dot, opacity);
            dot.setGradientPosition(-20f + 120f * stagger, glow);
        }
    }

    public static void resetInterludeDots(AppliedLine line, StyleSink sink) {
        if (line == null || line.dotViews == null || sink == null) return;
        for (SpicyAnimatedTextView dot : line.dotViews) {
            if (dot == null) continue;
            sink.applyScale(dot, 0.75f, 0.75f);
            sink.applyTranslationY(dot, 0f);
            sink.applyAlpha(dot, 0.45f);
            dot.setGradientPosition(-20f, 0f);
        }
    }

    private static void ensureSyllableSprings(SyllableSegment seg) {
        if (seg.scaleSpring != null && seg.ySpring != null && seg.glowSpring != null) return;
        seg.scaleSpring = new Spring(0.95f, 0.7f, 0.6f);
        seg.ySpring = new Spring(0.01f, 1.25f, 0.4f);
        seg.glowSpring = new Spring(0f, 1f, 0.5f);
    }

    private static void ensureLetterSprings(AnimatedLetterState letter) {
        if (letter.scaleSpring != null && letter.ySpring != null && letter.glowSpring != null) return;
        letter.scaleSpring = new Spring(1f, 0.6f, 0.7f);
        letter.ySpring = new Spring(0f, 1.25f, 0.4f);
        letter.glowSpring = new Spring(0f, 1f, 0.5f);
    }

    private static void ensureDotSprings(AppliedLine line) {
        if (line.dotMainScaleSpring != null && line.dotMainOpacitySpring != null) return;
        line.dotMainScaleSpring = new Spring(0f, 0.72f, 0.74f);
        line.dotMainOpacitySpring = new Spring(0f, 0.9f, 0.82f);
    }

    private static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (positionMs - startMs) / (float) (endMs - startMs)));
    }

    private static float frameDelta(float deltaSeconds) {
        return Math.max(0.001f, Math.min(0.08f, deltaSeconds));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface StyleSink {
        void applyAlpha(View view, float alpha);
        void applyScale(View view, float scaleX, float scaleY);
        void applyTranslationY(View view, float translationY);
    }
}
