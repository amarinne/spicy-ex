package com.eza.spicyex.lyrics;

import android.view.View;

import java.util.List;

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
        return LyricsLineViewState.stepOpacity(line, target, deltaSeconds);
    }

    public static float stepLineScale(AppliedLine line, float targetScale, float deltaSeconds) {
        return LyricsLineViewState.stepLineScale(line, targetScale, deltaSeconds);
    }

    /** Eased line glow — rises gradually when the line activates, decays after it passes. Always
     *  starts from 0 (never the current target) so a line that mounts mid-play still fades in. */
    public static float stepLineGlow(AppliedLine line, float targetGlow, float deltaSeconds) {
        return LyricsLineViewState.stepLineGlow(line, targetGlow, deltaSeconds);
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
        animateSyllables(line, positionMs, deltaSeconds, basePx, sink, spotlight, true);
    }

    public static void animateSyllables(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink,
            boolean spotlight,
            boolean glowEnabled
    ) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (SyllableSegment seg : line.words) {
            if (!LyricsSyllableViewState.isWordAttached(seg)) continue;
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
            if (!glowEnabled) targetGlow = 0f;
            float targetGradient = spotlight
                    ? (active || sung ? 100f : -20f)
                    : (active ? (-20f + 120f * progress) : (sung ? 100f : -20f));
            float targetBrightness = spotlight && active ? spotlightBrightness(progress) : 1f;
            float scale = LyricsSyllableViewState.stepWordScale(seg, targetScale, deltaSeconds);
            float y = LyricsSyllableViewState.stepWordY(seg, targetY, deltaSeconds);
            float glow = LyricsSyllableViewState.stepWordGlow(seg, targetGlow, deltaSeconds);
            LyricsSyllableViewState.updateTextPivot(seg);
            LyricsSyllableViewState.applyWordFrame(seg, sink, scale, y, basePx);
            LyricsSyllableViewState.applyWordGradient(seg, targetGradient, glow, targetBrightness);

            int letterCount = LyricsSyllableViewState.letterCount(seg);
            if (letterCount <= 0) continue;
            float timeAlpha = (float) Math.sin(progress * (Math.PI / 2d));
            float letterAnchor = LyricAnimations.activeLetterPosition(letterCount, timeAlpha);
            for (int letterIndex = 0; letterIndex < letterCount; letterIndex++) {
                AnimatedLetterState letter = LyricsSyllableViewState.letterAt(seg, letterIndex);
                if (letter == null || letter.view == null) continue;
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
                if (!glowEnabled) targetLetterGlow = 0f;
                float letterEnd = letter.start + letter.duration;
                float letterGradient;
                if (spotlight) letterGradient = active || sung ? 100f : -20f;
                else if (timeAlpha >= letterEnd) letterGradient = 100f;
                else if (timeAlpha <= letter.start) letterGradient = -20f;
                else letterGradient = -20f + 120f * LyricAnimations.easeSinOut(letterTimeScale);
                float targetLetterBrightness = spotlight && active ? spotlightBrightness(letterTimeScale) : 1f;
                float letterScale = LyricsSyllableViewState.stepLetterScale(letter, targetLetterScale, deltaSeconds);
                float letterY = LyricsSyllableViewState.stepLetterY(letter, targetLetterY, deltaSeconds);
                float letterGlow = LyricsSyllableViewState.stepLetterGlow(letter, targetLetterGlow, deltaSeconds);
                LyricsSyllableViewState.applyLetterFrame(letter, sink, letterScale, letterY, basePx,
                        letterGradient, letterGlow, targetLetterBrightness);
            }
        }
    }

    public static void resetSyllables(AppliedLine line, StyleSink sink) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (SyllableSegment seg : line.words) {
            LyricsSyllableViewState.resetAnimatedWord(seg, sink);
        }
    }

    public static void animateInterludeDots(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float dotBasePx,
            StyleSink sink
    ) {
        if (line == null || !LyricsLineViewState.hasDotViews(line) || sink == null) return;
        float lineProgress = progress01(positionMs, line.startMs, line.endMs);
        boolean preHide = line.endMs > line.startMs && positionMs >= line.endMs - LyricTimeline.PRE_HIDDEN_DOT_LINE_MS;
        float mainScale = LyricsLineViewState.stepDotMainScale(
                line, preHide ? 0f : LyricAnimations.dotMainScaleSpline(lineProgress), deltaSeconds);
        float mainOpacity = LyricsLineViewState.stepDotMainOpacity(
                line, preHide ? 0f : LyricAnimations.dotMainOpacitySpline(lineProgress), deltaSeconds);
        List<SpicyAnimatedTextView> dotViews = LyricsLineViewState.dotViews(line);
        for (int i = 0; i < dotViews.size(); i++) {
            SpicyAnimatedTextView dot = dotViews.get(i);
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
        if (line == null || sink == null) return;
        for (SpicyAnimatedTextView dot : LyricsLineViewState.dotViews(line)) {
            if (dot == null) continue;
            sink.applyScale(dot, 0.75f, 0.75f);
            sink.applyTranslationY(dot, 0f);
            sink.applyAlpha(dot, 0.45f);
            dot.setGradientPosition(-20f, 0f);
        }
    }

    private static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (positionMs - startMs) / (float) (endMs - startMs)));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float spotlightBrightness(float progress) {
        float eased = LyricAnimations.easeSinOut(clamp01(progress));
        return 0.42f + 0.58f * eased * eased;
    }

    public interface StyleSink {
        void applyAlpha(View view, float alpha);
        void applyScale(View view, float scaleX, float scaleY);
        void applyTranslationY(View view, float translationY);
    }
}
