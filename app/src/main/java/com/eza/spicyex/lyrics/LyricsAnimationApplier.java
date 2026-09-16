package com.eza.spicyex.lyrics;

import android.view.View;

import java.util.List;

/** Applies per-frame lyric row animation to mounted renderer views. */
public final class LyricsAnimationApplier {
    private LyricsAnimationApplier() {
    }

    public static float stepLineOpacity(AppliedLine line, boolean active, boolean sung, float deltaSeconds) {
        return stepLineOpacity(line, active, sung, deltaSeconds, false);
    }

    public static float stepLineOpacity(AppliedLine line, boolean active, boolean sung, float deltaSeconds,
                                        boolean appleDimPassed) {
        if (line == null) return 1f;
        if (line.dotLine && !active) return LyricsLineViewState.stepOpacity(line, 0f, deltaSeconds);
        float target = active ? 1.0f : (sung
                ? (appleDimPassed ? 0.60f : 0.82f)
                : (appleDimPassed ? 0.38f : 0.42f));
        if (line.bgLine && !active) target *= 0.90f;
        return LyricsLineViewState.stepOpacity(line, target, deltaSeconds);
    }

    public static float stepLineScale(AppliedLine line, float targetScale, float deltaSeconds) {
        return LyricsLineViewState.stepLineScale(line, targetScale, deltaSeconds);
    }

    /** Eased line glow — rises gradually when the line activates, then holds after it passes. Always
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
        animateSyllables(line, positionMs, deltaSeconds, basePx, sink,
                spotlight, true, true, false, false, false, false, false);
    }

    public static void animateSyllables(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink,
            boolean spotlight,
            boolean glowEnabled,
            boolean wordBounceEnabled,
            boolean directMotion,
            boolean liftMotion,
            boolean individualWordMotion,
            boolean appleLift,
            boolean appleGlow
    ) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (int groupStart = 0; groupStart < line.words.size();) {
            int groupEnd = TimedWordGrouping.groupEnd(line, groupStart);
            SyllableSegment motionOwner = line.words.get(groupStart);
            if (LyricsSyllableViewState.isMotionAttached(motionOwner)) {
                if (!wordBounceEnabled) {
                    for (int wordIndex = groupStart; wordIndex <= groupEnd; wordIndex++) {
                        LyricsSyllableViewState.applyNeutralWordMotion(
                                line.words.get(wordIndex), sink);
                    }
                } else {
                    long groupStartMs = TimedWordGrouping.startMs(line, groupStart);
                    long groupEndMs = TimedWordGrouping.endMs(line, groupStart);
                    boolean grouped = groupEnd > groupStart;
                    int focusIndex = TimedWordGrouping.focusIndex(
                            line, groupStart, groupEnd, positionMs);
                    SyllableSegment focus = line.words.get(focusIndex);
                    boolean focusActive = focus != null && positionMs >= focus.startMs
                            && positionMs < focus.endMs;
                    float focusProgress = focus == null ? 0f : progress01(
                            positionMs, focus.startMs, focus.endMs);
                    boolean motionActive = focusActive;
                    float motionProgress = focusProgress;
                    if (grouped) {
                        motionActive = positionMs >= groupStartMs && positionMs < groupEndMs;
                        motionProgress = progress01(positionMs, groupStartMs, groupEndMs);
                    }
                    boolean letterUnitMotion = individualWordMotion && !grouped
                            && LyricsSyllableViewState.letterCount(motionOwner) > 1;
                    boolean appleLetterDriven = appleLift && liftMotion
                            && LyricsSyllableViewState.letterCount(motionOwner) > 1;
                    // Phrase modes own the group wrapper. Word modes keep it neutral and animate
                    // each timed segment below.
                    float targetScale = (grouped && individualWordMotion) || letterUnitMotion
                            ? 1f : wordMotionScale(
                            liftMotion, motionActive, positionMs >= groupEndMs, motionProgress);
                    float targetY = (grouped && individualWordMotion) || letterUnitMotion || appleLetterDriven
                            ? 0f : wordMotionY(
                            liftMotion, motionActive, positionMs >= groupEndMs, motionProgress, appleLift);
                    float scale = LyricsSyllableViewState.stepWordScale(
                            motionOwner, targetScale, deltaSeconds, directMotion);
                    float y = LyricsSyllableViewState.stepWordY(
                            motionOwner, targetY, deltaSeconds, directMotion);
                    LyricsSyllableViewState.updateTextPivot(
                            motionOwner, grouped ? null : focus);
                    LyricsSyllableViewState.applyWordFrame(
                            motionOwner, sink, scale, y, basePx);
                    if (grouped) {
                        for (int wordIndex = groupStart; wordIndex <= groupEnd; wordIndex++) {
                            SyllableSegment segment = line.words.get(wordIndex);
                            boolean localActive = segment != null
                                    && positionMs >= segment.startMs && positionMs < segment.endMs;
                            float localProgress = segment == null ? 0f : progress01(
                                    positionMs, segment.startMs, segment.endMs);
                            boolean localSung = segment != null && positionMs >= segment.endMs;
                            boolean localLetterUnits = individualWordMotion
                                    && LyricsSyllableViewState.letterCount(segment) > 1;
                            float localScaleTarget = groupedLocalScale(
                                    individualWordMotion && !localLetterUnits,
                                    liftMotion, localActive,
                                    localSung, localProgress);
                            float localScale = LyricsSyllableViewState.stepLocalWordScale(
                                    segment, localScaleTarget, deltaSeconds, directMotion);
                            float localY = LyricsSyllableViewState.stepLocalWordY(
                                    segment, groupedLocalY(
                                            individualWordMotion && !localLetterUnits,
                                            liftMotion, appleLift, localActive,
                                            localSung, localProgress),
                                    deltaSeconds, directMotion);
                            LyricsSyllableViewState.applyLocalWordFrame(segment, sink,
                                    localScale, localScale, localY, basePx);
                        }
                    }
                }
            }
            for (int wordIndex = groupStart; wordIndex <= groupEnd; wordIndex++) {
                animateSegmentVisuals(line.words.get(wordIndex), positionMs, deltaSeconds,
                        basePx, sink, spotlight, glowEnabled,
                        wordBounceEnabled, directMotion, liftMotion, individualWordMotion,
                        appleLift, appleGlow);
            }
            groupStart = groupEnd + 1;
        }
    }

    public static void animateSyllables(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink,
            boolean spotlight,
            boolean glowEnabled,
            boolean wordBounceEnabled
    ) {
        animateSyllables(line, positionMs, deltaSeconds, basePx, sink,
                spotlight, glowEnabled, wordBounceEnabled, false, false, false, false, false);
    }

    private static void animateSegmentVisuals(
            SyllableSegment seg,
            long positionMs,
            float deltaSeconds,
            float basePx,
            StyleSink sink,
            boolean spotlight,
            boolean glowEnabled,
            boolean wordBounceEnabled,
            boolean directMotion,
            boolean liftMotion,
            boolean individualWordMotion,
            boolean appleLift,
            boolean appleGlow
    ) {
        if (!LyricsSyllableViewState.isWordAttached(seg)) return;
        float progress = progress01(positionMs, seg.startMs, seg.endMs);
        boolean active = positionMs >= seg.startMs && positionMs < seg.endMs;
        boolean sung = positionMs >= seg.endMs;
        // Spotlight: no per-word fill — the active word is lit solid and its glow builds with
        // the word's progress (gradual, not an instant pop), then holds after completion.
        float targetGlow = spotlight
                ? (active ? (appleGlow ? 0.15f + 0.65f * progress : 0.60f * progress)
                        : (sung && !appleGlow ? 0.60f : 0f))
                : (active ? (appleGlow ? 0.25f + 0.60f * LyricAnimations.glowSpline(progress)
                                : 0.55f * LyricAnimations.glowSpline(progress))
                        : (sung && !appleGlow ? 0.55f : 0f));
        if (!glowEnabled) targetGlow = 0f;
        float targetGradient = spotlight
                ? (active || sung ? LyricAnimations.GRADIENT_SUNG : LyricAnimations.GRADIENT_UNSUNG)
                : (active ? LyricAnimations.gradientPosition(progress)
                : (sung ? LyricAnimations.GRADIENT_SUNG : LyricAnimations.GRADIENT_UNSUNG));
        float targetBrightness = spotlight && active ? spotlightBrightness(progress) : 1f;
        float glow = LyricsSyllableViewState.stepWordGlow(seg, targetGlow, deltaSeconds);
        float band = appleGlow && active ? 64f : Float.NaN;
        SpicyAnimatedTextView wordText = LyricsSyllableViewState.wordTextView(seg);
        if (wordText != null) wordText.setGradientBandWidth(band);
        SpicyAnimatedTextView romanText = LyricsSyllableViewState.romanizedTextView(seg);
        if (romanText != null) romanText.setGradientBandWidth(band);
        LyricsSyllableViewState.applyWordGradient(seg, targetGradient, glow, targetBrightness);

        int letterCount = LyricsSyllableViewState.letterCount(seg);
        if (letterCount <= 0) return;
        boolean letterUnitMotion = wordBounceEnabled && individualWordMotion && letterCount > 1;
        float timeAlpha = (float) Math.sin(progress * (Math.PI / 2d));
        float letterAnchor = LyricAnimations.activeLetterPosition(letterCount, timeAlpha);
        for (int letterIndex = 0; letterIndex < letterCount; letterIndex++) {
            AnimatedLetterState letter = LyricsSyllableViewState.letterAt(seg, letterIndex);
            if (letter == null || letter.view == null) continue;
            float letterTime = timeAlpha - letter.start;
            float letterTimeScale = clamp01(letterTime / Math.max(0.0001f, letter.duration));
            float glowTimeScale = clamp01(letterTime / Math.max(0.0001f, letter.glowDuration));
            float distance = Math.abs(letterIndex - letterAnchor);
            float glowFalloff = !appleGlow
                    ? LyricAnimations.letterGlowFalloff(distance)
                    : active ? LyricAnimations.appleActiveLetterGlowFalloff(distance)
                            : LyricAnimations.appleLetterGlowFalloff(distance);
            float motionTime = progress - letter.start;
            float motionProgress = clamp01(motionTime / Math.max(0.0001f, letter.duration));
            boolean motionActive = active && progress >= letter.start
                    && progress < letter.start + letter.duration;
            boolean motionSung = sung || (active
                    && progress >= letter.start + letter.duration);
            float targetLetterScale = letterUnitMotion
                    ? wordMotionScale(liftMotion, motionActive, motionSung, motionProgress) : 1f;
            float ownLetterY = letterUnitMotion
                    ? wordMotionY(liftMotion, motionActive, motionSung, motionProgress, appleLift) : 0f;
            float targetLetterY = ownLetterY;
            if (letterUnitMotion && liftMotion && appleLift) {
                float wavePos = timeAlpha * letterCount - letterIndex;
                float localLift = clamp01(wavePos);
                targetLetterY = -0.04f * localLift * localLift * (3f - 2f * localLift);
            }
            float targetLetterGlow = spotlight
                    ? ((active || (sung && !appleGlow)) ? 0.6f : 0f)
                    : ((sung && !appleGlow) ? 0.55f
                            : (active ? LyricAnimations.glowSpline(glowTimeScale) * glowFalloff : 0f));
            if (!glowEnabled) targetLetterGlow = 0f;
            float letterEnd = letter.start + letter.duration;
            float letterGradient;
            if (spotlight) letterGradient = active || sung ? LyricAnimations.GRADIENT_SUNG : LyricAnimations.GRADIENT_UNSUNG;
            else if (timeAlpha >= letterEnd) letterGradient = LyricAnimations.GRADIENT_SUNG;
            else if (timeAlpha <= letter.start) letterGradient = LyricAnimations.GRADIENT_UNSUNG;
            else letterGradient = LyricAnimations.gradientPosition(LyricAnimations.easeSinOut(letterTimeScale));
            float targetLetterBrightness = spotlight && active ? spotlightBrightness(letterTimeScale) : 1f;
            float letterScale = LyricsSyllableViewState.stepLetterScale(letter, targetLetterScale, deltaSeconds, directMotion);
            float letterY = LyricsSyllableViewState.stepLetterY(letter, targetLetterY, deltaSeconds, directMotion);
            float letterGlow = LyricsSyllableViewState.stepLetterGlow(letter, targetLetterGlow, deltaSeconds);
            if (letter.view != null) letter.view.setGradientBandWidth(band);
            LyricsSyllableViewState.applyLetterFrame(letter, sink, letterScale, letterY, basePx,
                    letterGradient, letterGlow, targetLetterBrightness);
        }
    }

    static float wordMotionScale(boolean liftMotion, boolean active, boolean sung, float progress) {
        if (liftMotion) return 1f;
        return active ? LyricAnimations.scaleSpline(progress) : (sung ? 1f : 0.95f);
    }

    static float wordMotionY(boolean liftMotion, boolean active, boolean sung, float progress) {
        return wordMotionY(liftMotion, active, sung, progress, false);
    }

    static float wordMotionY(boolean liftMotion, boolean active, boolean sung, float progress,
                             boolean appleLift) {
        if (liftMotion) {
            if (appleLift) {
                if (active) return LyricAnimations.appleLiftYOffsetSpline(progress);
                return sung ? LyricAnimations.appleLiftYOffsetSpline(1f) : 0f;
            }
            if (active) return LyricAnimations.liftYOffsetSpline(progress);
            return 0f;
        }
        return active ? LyricAnimations.yOffsetSpline(progress) : (sung ? 0f : 0.01f);
    }

    static float groupedWrapperScale(boolean beforeGroup) {
        return 1f;
    }

    static float groupedWrapperY(boolean beforeGroup) {
        return 0f;
    }

    static float groupedLocalScale(boolean individualWordMotion, boolean liftMotion,
                                   boolean active, boolean sung, float progress) {
        return individualWordMotion
                ? wordMotionScale(liftMotion, active, sung, progress) : 1f;
    }

    static float groupedLocalY(boolean individualWordMotion, boolean liftMotion,
                               boolean active, boolean sung, float progress) {
        return groupedLocalY(individualWordMotion, liftMotion, false, active, sung, progress);
    }

    static float groupedLocalY(boolean individualWordMotion, boolean liftMotion, boolean appleLift,
                               boolean active, boolean sung, float progress) {
        return individualWordMotion
                ? wordMotionY(liftMotion, active, sung, progress, appleLift) : 0f;
    }

    static float inactiveWordScale(boolean motionEnabled, boolean liftMotion) {
        return motionEnabled && !liftMotion ? 0.95f : 1f;
    }

    static float letterMotionScale(boolean motionEnabled, float progress, float falloff) {
        return motionEnabled
                ? 1f + ((LyricAnimations.letterScaleSpline(progress) - 1f) * falloff) : 1f;
    }

    static float letterMotionY(boolean motionEnabled, float progress, float falloff) {
        return motionEnabled ? LyricAnimations.letterYOffsetSpline(progress) * falloff : 0f;
    }

    public static void resetSyllables(AppliedLine line, StyleSink sink, boolean motionEnabled,
                                      boolean liftMotion, boolean individualWordMotion) {
        if (line == null || line.words == null || line.words.isEmpty() || sink == null) return;
        for (SyllableSegment seg : line.words) {
            LyricsSyllableViewState.resetAnimatedWord(
                    seg, sink, motionEnabled, liftMotion, individualWordMotion);
        }
    }

    public static void resetSyllables(AppliedLine line, StyleSink sink, boolean motionEnabled) {
        resetSyllables(line, sink, motionEnabled, false, false);
    }

    public static void animateInterludeDots(
            AppliedLine line,
            long positionMs,
            float deltaSeconds,
            float dotBasePx,
            StyleSink sink
    ) {
        if (line == null || !LyricsLineViewState.hasDotViews(line) || sink == null) return;
        boolean preHide = line.endMs > line.startMs && positionMs >= line.endMs - LyricTimeline.PRE_HIDDEN_DOT_LINE_MS;
        float groupScale = LyricsLineViewState.stepDotMainScale(line, preHide ? 0f : 1f, deltaSeconds);
        float groupOpacity = LyricsLineViewState.stepDotMainOpacity(line, preHide ? 0f : 1f, deltaSeconds);
        List<SpicyAnimatedTextView> dotViews = LyricsLineViewState.dotViews(line);
        for (int i = 0; i < dotViews.size(); i++) {
            SpicyAnimatedTextView dot = dotViews.get(i);
            if (dot == null) continue;
            SyllableSegment seg = line.words != null && i < line.words.size() ? line.words.get(i) : null;
            DotTargets targets = dotTargets(seg, positionMs);
            float dotScale = groupScale * targets.scale;
            float dotY = dotBasePx * targets.yOffset;
            float glow = targets.glow * groupOpacity;
            float opacity = targets.opacity * groupOpacity;
            sink.applyScale(dot, dotScale, dotScale);
            sink.applyTranslationY(dot, dotY);
            sink.applyAlpha(dot, opacity);
            dot.setGradientPosition(LyricAnimations.gradientPosition(targets.gradientProgress), glow);
        }
    }

    public static void resetInterludeDots(AppliedLine line, StyleSink sink, boolean sung, float deltaSeconds) {
        resetInterludeDots(line, sink, sung, deltaSeconds, false);
    }

    public static void resetInterludeDots(AppliedLine line, StyleSink sink, boolean sung, float deltaSeconds,
                                          boolean appleStyle) {
        if (line == null || sink == null) return;
        // Both targets used to be applied as instant literals (0.75f/0.45f) whenever the
        // (appleStyle && sung) case didn't apply, with no spring involved at all - a real,
        // always-reproducible hard snap every time this ran in that branch, confirmed live via
        // AnimTracer's "suspicious jump" log (dot views logged jumping straight from 1.0 to
        // exactly 0.45, no fractional easing steps at all). Route both through the spring
        // unconditionally so the rest state is approached smoothly like everything else here.
        float restScale = LyricsLineViewState.stepDotMainScale(
                line, appleStyle && sung ? 0f : 0.75f, deltaSeconds);
        float restOpacity = LyricsLineViewState.stepDotMainOpacity(
                line, appleStyle && sung ? 0f : 0.45f, deltaSeconds);
        for (SpicyAnimatedTextView dot : LyricsLineViewState.dotViews(line)) {
            if (dot == null) continue;
            sink.applyScale(dot, restScale, restScale);
            sink.applyTranslationY(dot, 0f);
            sink.applyAlpha(dot, restOpacity);
            dot.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
        }
    }

    static DotTargets dotTargets(SyllableSegment seg, long positionMs) {
        if (seg == null) return new DotTargets(0.75f, 0f, 0f, 0.35f, 0f);
        if (positionMs < seg.startMs) return new DotTargets(0.75f, 0f, 0f, 0.35f, 0f);
        if (positionMs >= seg.endMs) return new DotTargets(1f, 0f, 1f, 1f, 1f);
        float progress = progress01(positionMs, seg.startMs, seg.endMs);
        return new DotTargets(
                LyricAnimations.dotScaleSpline(progress),
                LyricAnimations.dotYOffsetSpline(progress),
                LyricAnimations.dotGlowSpline(progress),
                LyricAnimations.dotOpacitySpline(progress),
                progress
        );
    }

    static final class DotTargets {
        final float scale;
        final float yOffset;
        final float glow;
        final float opacity;
        final float gradientProgress;

        DotTargets(float scale, float yOffset, float glow, float opacity, float gradientProgress) {
            this.scale = scale;
            this.yOffset = yOffset;
            this.glow = glow;
            this.opacity = opacity;
            this.gradientProgress = gradientProgress;
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
