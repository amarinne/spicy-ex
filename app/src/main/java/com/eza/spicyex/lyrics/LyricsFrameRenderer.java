package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Set;

import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Applies one fullscreen lyric animation frame to the currently mounted row window. */
public final class LyricsFrameRenderer {
    private final FrameStyleBatcher styleBatcher;
    private final LyricsAnimationApplier.StyleSink styleSink;
    private final float scaledDensity;

    public LyricsFrameRenderer(Context context, FrameStyleBatcher styleBatcher) {
        this.styleBatcher = styleBatcher;
        scaledDensity = context == null ? 1f : context.getResources().getDisplayMetrics().scaledDensity;
        styleSink = new LyricsAnimationApplier.StyleSink() {
            @Override
            public void applyAlpha(View view, float alpha) {
                LyricsFrameRenderer.this.styleBatcher.applyAlphaIfChanged(view, alpha);
            }

            @Override
            public void applyScale(View view, float scaleX, float scaleY) {
                LyricsFrameRenderer.this.styleBatcher.applyScaleIfChanged(view, scaleX, scaleY);
            }

            @Override
            public void applyTranslationY(View view, float translationY) {
                LyricsFrameRenderer.this.styleBatcher.applyTranslationYIfChanged(view, translationY);
            }
        };
    }

    /** Unsynced lyrics: every mounted row drawn fully bright, no blur/scale/wash. */
    public void applyStatic(LyricsDocument document, Set<Integer> mountedIndices, ViewGroup mountedRowsHost) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;
            LyricsLineViewState.applyStaticFrame(line, styleBatcher);
        }
        styleBatcher.flush();
    }

    public void applySynced(
            LyricsDocument document,
            Set<Integer> mountedIndices,
            ViewGroup mountedRowsHost,
            LyricsRenderConfig config,
            long positionMs,
            int activeIndex,
            float deltaSeconds,
            boolean userScrollHeld
    ) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;

            LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                    line, positionMs, config.spotlight, config.lineGradientEnabled);
            float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active, lineState.sung, deltaSeconds);
            LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity,
                    mobileLineBlurPx(line, i, activeIndex, userScrollHeld, config));
            float lineGlowTarget = config.glowBlurEnabled ? lineState.glowTarget : 0f;
            float lineGlow = LyricsAnimationApplier.stepLineGlow(line, lineGlowTarget, deltaSeconds);

            if (LyricsLineViewState.hasMainView(line)) {
                float scale = LyricsAnimationApplier.stepLineScale(line, lineState.scaleTarget, deltaSeconds);
                LyricsLineViewState.updateMainScalePivot(line);
                LyricsLineViewState.applyMainScale(line, styleBatcher, scale);
                LyricsLineViewState.applyLineLevelGradient(
                        line, lineState.gradient, lineGlow, lineState.brightnessTarget);
            }
            if (line.dotLine) {
                if (lineState.active) {
                    LyricsAnimationApplier.animateInterludeDots(line, positionMs, deltaSeconds, spToPx(44), styleSink);
                } else {
                    LyricsAnimationApplier.resetInterludeDots(line, styleSink);
                }
            } else {
                applySecondaryGradient(line, positionMs, lineGlow, config);
                if (hasRealTimedWords(line)) {
                    if (lineState.active) {
                        LyricsAnimationApplier.animateSyllables(
                                line,
                                positionMs,
                                deltaSeconds,
                                spToPx(LyricsLineViewState.effectiveBaseTextSp(line)),
                                styleSink,
                                config.spotlight,
                                config.glowBlurEnabled);
                    } else {
                        LyricsAnimationApplier.resetSyllables(line, styleSink);
                    }
                } else if (line.words != null && !line.words.isEmpty()
                        && !config.lineSyncFillWord()
                        && !config.lineSyncFillSentence()) {
                    animateContinuousLineWords(line, lineState, lineGlow, deltaSeconds);
                } else if (lineState.active) {
                    LyricsAnimationApplier.animateSyllables(
                            line,
                            positionMs,
                            deltaSeconds,
                            spToPx(LyricsLineViewState.effectiveBaseTextSp(line)),
                            styleSink,
                            config.spotlight,
                            config.glowBlurEnabled);
                } else {
                    if (config.lineSyncFillWord() || config.lineSyncFillSentence()) {
                        resetNearbySyllables(line, i, activeIndex, styleSink);
                    } else {
                        LyricsAnimationApplier.resetSyllables(line, styleSink);
                    }
                }
            }
        }
        styleBatcher.flush();
    }

    private boolean hasRealTimedWords(AppliedLine line) {
        return line != null && !line.syntheticWords && line.words != null && !line.words.isEmpty();
    }

    private void animateContinuousLineWords(AppliedLine line, LyricsLineAnimationState lineState,
                                            float lineGlow, float deltaSeconds) {
        if (line == null || line.words == null || line.words.isEmpty() || lineState == null) return;

        View container = LyricsSyllableViewState.parentView(line.words.get(0));
        if (container != null) {
            float scale = LyricsAnimationApplier.stepLineScale(line, lineState.scaleTarget, deltaSeconds);
            if (container.getWidth() > 0 && container.getHeight() > 0) {
                container.setPivotX(line.oppositeAligned ? container.getWidth() : 0f);
                container.setPivotY(container.getHeight() * 0.5f);
            }
            styleBatcher.applyScaleIfChanged(container, scale, scale);
        }

        int containerWidth = container == null ? 0 : container.getWidth();
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            LyricsSyllableViewState.resetWordTransform(seg);
            LyricsSyllableViewState.applySyntheticLineGradient(
                    seg, container, containerWidth, lineState.gradient, lineGlow,
                    lineState.brightnessTarget);
        }
    }

    private void resetNearbySyllables(AppliedLine line, int index, int activeIndex,
                                      LyricsAnimationApplier.StyleSink sink) {
        if (activeIndex < 0 || Math.abs(index - activeIndex) <= 2) {
            LyricsAnimationApplier.resetSyllables(line, sink);
        }
    }

    private void applySecondaryGradient(AppliedLine line, long positionMs, float spotGlow, LyricsRenderConfig config) {
        if (line == null) return;
        if (config.spotlight) {
            float glow = config.glowBlurEnabled ? spotGlow : 0f;
            LyricsLineViewState.applyLineSecondaryGradient(line, secondaryLineGradientPosition(line, positionMs), glow);
            return;
        }
        boolean animate = config.lineGradientEnabled;
        float gradient = animate ? secondaryLineGradientPosition(line, positionMs) : 100f;
        float glow = config.glowBlurEnabled && animate && positionMs >= line.startMs && positionMs < line.endMs ? 0.10f : 0f;
        LyricsLineViewState.applyLineSecondaryGradient(line, gradient, glow);
    }

    private float secondaryLineGradientPosition(AppliedLine line, long positionMs) {
        if (line == null || positionMs < line.startMs) return -20f;
        long fillEnd = LyricTimeline.fillEndMs(line);
        if (positionMs >= fillEnd) return 100f;
        return -20f + 120f * progress01(positionMs, line.startMs, fillEnd);
    }

    private float mobileLineBlurPx(AppliedLine line, int index, int active, boolean userScrollHeld,
                                   LyricsRenderConfig config) {
        if (line == null || Build.VERSION.SDK_INT < 31) return 0f;
        if (!config.lineBlurEnabled) return 0f;
        if (userScrollHeld) return 0f;
        float quality = config.blurQuality;
        if (quality <= 0f) return 0f;
        if (active < 0) return 0f;
        int distance = Math.abs(index - active);
        if (distance <= 1) return 0f;
        boolean emphasized = safe(line.text).codePointCount(0, safe(line.text).length()) <= 12 || line.dotLine;
        float max = emphasized ? 1.0f : 1.8f;
        float weighted = max * Math.min(1f, distance / 4f);
        if (distance == 2) weighted *= 0.55f;
        return weighted * quality;
    }

    private float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return LyricAnimations.clamp01((positionMs - startMs) / (float) (endMs - startMs));
    }

    private float spToPx(float sp) {
        return sp * scaledDensity;
    }
}
