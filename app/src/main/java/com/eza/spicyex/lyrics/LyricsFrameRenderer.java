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
            if (!isMounted(line, mountedRowsHost)) continue;
            styleBatcher.applyAlphaIfChanged(line.rowView, 1f);
            styleBatcher.queueBlurIfChanged(line.rowView, 0f, 0.25f);
            if (line.mainView != null) {
                styleBatcher.applyScaleIfChanged(line.mainView, 1f, 1f);
                line.mainView.setGradientPosition(100f, 0f);
            }
            if (line.romanView != null) line.romanView.setGradientPosition(100f, 0f);
            if (line.translationView != null) line.translationView.setGradientPosition(100f, 0f);
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
            if (!isMounted(line, mountedRowsHost)) continue;

            LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                    line, positionMs, config.spotlight, true);
            float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active, lineState.sung, deltaSeconds);
            styleBatcher.applyAlphaIfChanged(line.rowView, opacity);
            styleBatcher.queueBlurIfChanged(line.rowView,
                    mobileLineBlurPx(line, i, activeIndex, userScrollHeld, config), 0.25f);
            float lineGlow = LyricsAnimationApplier.stepLineGlow(line, lineState.glowTarget, deltaSeconds);

            if (line.mainView != null) {
                float scale = LyricsAnimationApplier.stepLineScale(line, lineState.scaleTarget, deltaSeconds);
                updateLineScalePivot(line);
                styleBatcher.applyScaleIfChanged(line.mainView, scale, scale);
                applyLineLevelLyricGradient(line, lineState.gradient, lineGlow);
            }
            if (line.dotLine) {
                if (lineState.active) {
                    LyricsAnimationApplier.animateInterludeDots(line, positionMs, deltaSeconds, spToPx(44), styleSink);
                } else {
                    LyricsAnimationApplier.resetInterludeDots(line, styleSink);
                }
            } else {
                applySecondaryGradient(line, positionMs, lineGlow, config);
                if (line.syntheticWords) {
                    animateSyntheticLineWords(line, lineState, lineGlow, deltaSeconds);
                } else if (lineState.active) {
                    LyricsAnimationApplier.animateSyllables(
                            line,
                            positionMs,
                            deltaSeconds,
                            spToPx(line.baseTextSp > 0 ? line.baseTextSp : LyricVisuals.lyricTextSizeSp(line.text)),
                            styleSink,
                            config.spotlight);
                } else {
                    LyricsAnimationApplier.resetSyllables(line, styleSink);
                }
            }
        }
        styleBatcher.flush();
    }

    private boolean isMounted(AppliedLine line, ViewGroup mountedRowsHost) {
        return line != null && line.rowView != null && line.rowView.getParent() == mountedRowsHost;
    }

    private void updateLineScalePivot(AppliedLine line) {
        if (line == null || line.mainView == null) return;
        int width = line.mainView.getWidth();
        int height = line.mainView.getHeight();
        if (width <= 0 || height <= 0) return;
        float pivotX = line.oppositeAligned ? width : 0f;
        float pivotY = height * 0.5f;
        if (Math.abs(line.mainView.getPivotX() - pivotX) > 0.5f) line.mainView.setPivotX(pivotX);
        if (Math.abs(line.mainView.getPivotY() - pivotY) > 0.5f) line.mainView.setPivotY(pivotY);
    }

    private void applyLineLevelLyricGradient(AppliedLine line, float gradient, float glow) {
        if (line == null) return;
        if (line.mainView != null) line.mainView.setGradientPosition(gradient, glow);
        if (line.romanView != null) line.romanView.setGradientPosition(gradient, glow);
        if (line.translationView != null) line.translationView.setGradientPosition(100f, 0f);
    }

    private void animateSyntheticLineWords(AppliedLine line, LyricsLineAnimationState lineState,
                                           float lineGlow, float deltaSeconds) {
        if (line == null || line.words == null || line.words.isEmpty() || lineState == null) return;

        View container = null;
        SyllableSegment first = line.words.get(0);
        if (first != null && first.view != null && first.view.getParent() instanceof View) {
            container = (View) first.view.getParent();
        }
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
            if (seg.view != null) {
                seg.view.setScaleX(1f);
                seg.view.setScaleY(1f);
                seg.view.setTranslationY(0f);
            }
            applySyntheticLineGradient(seg.textView, container, containerWidth, lineState.gradient, lineGlow);
            applySyntheticLineGradient(seg.romanizedTextView, container, containerWidth, lineState.gradient, lineGlow);
            if (seg.letters != null) {
                for (AnimatedLetterState letter : seg.letters) {
                    if (letter != null && letter.view != null) {
                        applySyntheticLineGradient(letter.view, container, containerWidth, lineState.gradient, lineGlow);
                    }
                }
            }
        }
    }

    private void applySyntheticLineGradient(SpicyAnimatedTextView view, View container,
                                            int containerWidth, float gradient, float glow) {
        if (view == null) return;
        if (container != null && containerWidth > 0 && view.isAttachedToWindow()) {
            view.setContainerGradientPosition(gradient, glow, containerWidth, offsetWithin(view, container));
        } else {
            view.setGradientPosition(gradient, glow);
        }
    }

    private float offsetWithin(View child, View ancestor) {
        float x = 0f;
        View current = child;
        while (current != null && current != ancestor) {
            x += current.getLeft() + current.getTranslationX();
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return x;
    }

    private void applySecondaryGradient(AppliedLine line, long positionMs, float spotGlow, LyricsRenderConfig config) {
        if (line == null) return;
        if (config.spotlight) {
            if (line.romanView != null && line.mainView == null) line.romanView.setGradientPosition(100f, spotGlow);
            if (line.translationView != null) line.translationView.setGradientPosition(100f, 0f);
            if (line.words != null) {
                for (SyllableSegment seg : line.words) {
                    if (seg != null && seg.romanizedTextView != null) {
                        seg.romanizedTextView.setGradientPosition(100f, spotGlow * 0.9f);
                    }
                }
            }
            return;
        }
        boolean animate = config.lineGradientEnabled;
        if (line.romanView != null && line.mainView == null) {
            float gradient = animate ? secondaryLineGradientPosition(line, positionMs) : 100f;
            float glow = animate && positionMs >= line.startMs && positionMs < line.endMs ? 0.10f : 0f;
            line.romanView.setGradientPosition(gradient, glow);
        }
        if (line.translationView != null) line.translationView.setGradientPosition(100f, 0f);
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
