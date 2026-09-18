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
    private static final int SCROLL_RENDER_MARGIN_ROWS = 8;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsAnimationApplier.StyleSink styleSink;
    private final float scaledDensity;
    private int lastActiveIndex = Integer.MIN_VALUE;
    private boolean lastUserScrollHeld;

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
        applySynced(document, mountedIndices, mountedRowsHost, config, positionMs, activeIndex,
                deltaSeconds, userScrollHeld, 0, Integer.MAX_VALUE);
    }

    public void applySynced(
            LyricsDocument document,
            Set<Integer> mountedIndices,
            ViewGroup mountedRowsHost,
            LyricsRenderConfig config,
            long positionMs,
            int activeIndex,
            float deltaSeconds,
            boolean userScrollHeld,
            int visibleStart,
            int visibleEnd
    ) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return;
        // Every caller should select the correct mode, but this boundary must fail closed. Static
        // documents can acquire synthetic timestamps during parsing; those timestamps are layout
        // data, not permission to dim every row except a fabricated active one.
        if (LyricsRenderMode.isStatic(document)) {
            applyStatic(document, mountedIndices, mountedRowsHost);
            return;
        }
        // Sync the lift-motion constants here (not only on config change): springs are created
        // lazily at mount, so a config-path-only sync misses first mount with Apple already on.
        // No-op when unchanged; the only caller of the animated path is this fullscreen renderer.
        LyricsSyllableViewState.setAppleMotion(config != null && config.appleLift);
        int boundedVisibleStart = Math.max(0, visibleStart - SCROLL_RENDER_MARGIN_ROWS);
        int boundedVisibleEnd = visibleEnd >= Integer.MAX_VALUE - SCROLL_RENDER_MARGIN_ROWS
                ? Integer.MAX_VALUE
                : visibleEnd + SCROLL_RENDER_MARGIN_ROWS;
        boolean activeChanged = activeIndex != lastActiveIndex;
        boolean scrollHoldChanged = userScrollHeld != lastUserScrollHeld;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            if (userScrollHeld && i != activeIndex
                    && (i < boundedVisibleStart || i > boundedVisibleEnd)) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;

            LyricsLineAnimationState lineState = LyricsLineAnimationState.forLine(
                    line, positionMs, config.spotlight, config.lineGradientEnabled,
                    config.appleDimPassed);
            int targetClass = lineState.active ? 1 : lineState.sung ? 2 : 0;
            // Refresh blur on hold transitions so rows go sharp while held and restore on
            // release; steady hold needs no refresh (values already settled). While held,
            // active-line advances must not restore blur either: resume happens only on
            // snap-back (hold release), which arrives as scrollHoldChanged.
            boolean blurNeedsRefresh = blurNeedsRefresh(
                    config.lineBlurEnabled, activeChanged, scrollHoldChanged, userScrollHeld);
            if (!lineState.active && !blurNeedsRefresh
                    && !LyricsLineViewState.needsFrame(line, targetClass)) {
                continue;
            }
            float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active, lineState.sung,
                    deltaSeconds, config.appleDimPassed);
            float blurTarget = mobileLineBlurPx(line, i, activeIndex, lineState.active, userScrollHeld, config);
            LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blurTarget);
            if (config.appleStyle) {
                float lineShadowTarget = lineState.active && !line.bgLine ? 1f : 0f;
                float lineShadow = LyricsLineViewState.stepLineShadow(line, lineShadowTarget, deltaSeconds);
                LyricsLineViewState.applyLineShadow(line, lineShadow);
            }
            float lineGlowTarget = config.glowBlurEnabled ? lineState.glowTarget : 0f;
            if (config.appleDimPassed && lineState.active) lineGlowTarget = Math.max(lineGlowTarget, 0.28f);
            float lineGlow = LyricsAnimationApplier.stepLineGlow(line, lineGlowTarget, deltaSeconds);

            if (LyricsLineViewState.hasMainView(line)) {
                float lineScaleTarget = lineLevelBounceEnabled(config, line)
                        ? lineState.scaleTarget : 1f;
                float scale = LyricsAnimationApplier.stepLineScale(line, lineScaleTarget, deltaSeconds);
                LyricsLineViewState.updateMainScalePivot(line);
                LyricsLineViewState.applyMainScale(line, styleBatcher, scale);
                LyricsLineViewState.applyLineLevelGradient(
                        line, lineState.gradient, lineGlow, lineState.brightnessTarget,
                        config.appleDimPassed && lineState.active ? 64f : Float.NaN);
            }
            if (line.dotLine) {
                if (lineState.active) {
                    LyricsAnimationApplier.animateInterludeDots(line, positionMs, deltaSeconds, spToPx(44), styleSink);
                } else {
                    LyricsAnimationApplier.resetInterludeDots(line, styleSink, lineState.sung,
                            config != null && config.appleStyle);
                }
            } else {
                applySecondaryGradient(line, positionMs, lineGlow, config);
                WordGradientRoute wordGradientRoute = wordGradientRoute(config.lineSyncFillMode);
                if (hasRealTimedWords(line)) {
                    boolean degenerateWordTiming = wordGradientRoute == WordGradientRoute.TIMED_WORDS
                            && hasDegenerateWordTiming(line);
                    if (lineState.active || lineState.sung) {
                        if (degenerateWordTiming) {
                            // Provider word spans are too compressed or malformed to light word by
                            // word; neutralize word motion and sweep the line as one sentence so
                            // the row matches line-synced rows instead of popping as a block.
                            LyricsAnimationApplier.resetSyllables(line, styleSink, false);
                            applyContinuousWordGradient(line, lineState, lineGlow);
                        } else {
                            LyricsAnimationApplier.animateSyllables(
                                    line,
                                    positionMs,
                                    deltaSeconds,
                                    spToPx(LyricsLineViewState.effectiveBaseTextSp(line)),
                                    styleSink,
                                    config.spotlight,
                                    config.glowBlurEnabled,
                                    wordBounceEnabled(config, line),
                                    !config.appleStyle,
                                    liftMotion(config),
                                    individualWordBounce(config),
                                    config.appleLift,
                                    config.appleDimPassed);
                            if (wordGradientRoute == WordGradientRoute.CONTINUOUS_BLOCK) {
                                applyContinuousWordGradient(line, lineState, lineGlow);
                            }
                        }
                    } else {
                            LyricsAnimationApplier.resetSyllables(
                                    line, styleSink, wordBounceEnabled(config, line),
                                    liftBounce(config), individualWordBounce(config));
                    }
                } else if (line.words != null && !line.words.isEmpty()
                        && wordGradientRoute == WordGradientRoute.CONTINUOUS_BLOCK) {
                    animateContinuousLineWords(line, lineState, lineGlow, deltaSeconds);
                } else if (lineState.active || lineState.sung) {
                    LyricsAnimationApplier.animateSyllables(
                            line,
                            positionMs,
                            deltaSeconds,
                            spToPx(LyricsLineViewState.effectiveBaseTextSp(line)),
                            styleSink,
                            config.spotlight,
                            config.glowBlurEnabled,
                            wordBounceEnabled(config, line),
                            !config.appleStyle,
                            liftMotion(config),
                            individualWordBounce(config),
                            config.appleLift,
                            config.appleDimPassed);
                } else {
                    if (config.lineSyncFillWord() || config.lineSyncFillSentence()) {
                        resetNearbySyllables(
                                line, i, activeIndex, styleSink, wordBounceEnabled(config, line),
                                liftBounce(config), individualWordBounce(config));
                    } else {
                        LyricsAnimationApplier.resetSyllables(
                                line, styleSink, wordBounceEnabled(config, line),
                                liftBounce(config), individualWordBounce(config));
                    }
                }
            }
            LyricsLineViewState.markFrameApplied(line, targetClass);
        }
        lastActiveIndex = activeIndex;
        lastUserScrollHeld = userScrollHeld;
        styleBatcher.flush();
    }

    private boolean hasRealTimedWords(AppliedLine line) {
        return line != null && !line.syntheticWords && line.words != null && !line.words.isEmpty();
    }

    private boolean wordBounceEnabled(LyricsRenderConfig config, AppliedLine line) {
        // Apple lift carries its own motion: it must not depend on the shared Word bounce gate.
        if (config != null && config.appleStyle && config.appleLift) return true;
        return config != null && config.wordBounceEnabled
                && (config.wordBounceScope.equals("All synced rows") || hasRealTimedWords(line));
    }

    /** Lift curve source: shared Lift bounce style, or Apple lift owning motion in Apple style. */
    private boolean liftMotion(LyricsRenderConfig config) {
        return liftBounce(config) || (config != null && config.appleLift);
    }

    private boolean liftBounce(LyricsRenderConfig config) {
        return config != null && !"Apple lift".equals(config.wordBounceStyle)
                && config.wordBounceStyle.endsWith(" lift");
    }

    private boolean individualWordBounce(LyricsRenderConfig config) {
        return config != null && (config.wordBounceStyle.startsWith("Word ")
                || config.appleLift);
    }

    private boolean lineLevelBounceEnabled(LyricsRenderConfig config, AppliedLine line) {
        return config != null && config.wordBounceEnabled
                && "All synced rows".equals(config.wordBounceScope);
    }

    /**
     * Blur refresh gate (pure, unit-tested): active-line advances refresh blur only when
     * the user is not holding the scroll; a hold release (snap-back) always refreshes so
     * blur restores exactly when follow resumes.
     */
    static boolean blurNeedsRefresh(boolean lineBlurEnabled, boolean activeChanged,
            boolean scrollHoldChanged, boolean userScrollHeld) {
        if (!lineBlurEnabled) return false;
        if (scrollHoldChanged) return true;
        return activeChanged && !userScrollHeld;
    }

    static WordGradientRoute wordGradientRoute(String fillMode) {
        if ("Left to right (block)".equals(fillMode)) {
            return WordGradientRoute.CONTINUOUS_BLOCK;
        }
        if ("Left to right (sentence)".equals(fillMode)
                || "Left to right (word)".equals(fillMode)) {
            return WordGradientRoute.TIMED_WORDS;
        }
        return WordGradientRoute.LINE_LEVEL;
    }

    enum WordGradientRoute {
        LINE_LEVEL,
        CONTINUOUS_BLOCK,
        TIMED_WORDS
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

    private void applyContinuousWordGradient(AppliedLine line, LyricsLineAnimationState lineState,
                                             float lineGlow) {
        if (line == null || line.words == null || line.words.isEmpty() || lineState == null) return;
        View contentContainer = LyricsSyllableViewState.parentView(line.words.get(0));
        int contentWidth = contentContainer == null ? 0 : contentContainer.getWidth();
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            LyricsSyllableViewState.applySyntheticLineGradient(
                    seg, contentContainer, contentWidth, lineState.gradient, lineGlow,
                    lineState.brightnessTarget);
        }
    }

    /** True when provider word spans are too compressed or malformed to fill word by word — every
     *  word would effectively light at once (the "full block" pop). Under "Left to right
     *  (sentence)" those lines fall back to the continuous sentence sweep, matching line-synced
     *  rows. A line whose words genuinely cover most of it keeps the timed word fill. */
    static boolean hasDegenerateWordTiming(AppliedLine line) {
        if (line == null || line.words == null || line.words.isEmpty()) return false;
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            if (seg.endMs <= seg.startMs) return true;
            firstStart = Math.min(firstStart, seg.startMs);
            lastEnd = Math.max(lastEnd, seg.endMs);
        }
        if (firstStart == Long.MAX_VALUE) return false;
        long wordSpan = lastEnd - firstStart;
        if (wordSpan <= 0) return true;
        long lineSpan = line.endMs - line.startMs;
        return lineSpan > 0 && wordSpan * 100L < lineSpan * 15L;
    }

    private void resetNearbySyllables(AppliedLine line, int index, int activeIndex,
                                      LyricsAnimationApplier.StyleSink sink,
                                      boolean motionEnabled,
                                      boolean liftMotion,
                                      boolean individualWordMotion) {
        if (activeIndex < 0 || Math.abs(index - activeIndex) <= 2) {
            LyricsAnimationApplier.resetSyllables(
                    line, sink, motionEnabled, liftMotion, individualWordMotion);
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
        if (line == null || positionMs < line.startMs) return LyricAnimations.GRADIENT_UNSUNG;
        long fillEnd = LyricTimeline.fillEndMs(line);
        if (positionMs >= fillEnd) return LyricAnimations.GRADIENT_SUNG;
        return LyricAnimations.gradientPosition(progress01(positionMs, line.startMs, fillEnd));
    }

    private float mobileLineBlurPx(AppliedLine line, int index, int active, boolean lineActive,
                                   boolean userScrollHeld, LyricsRenderConfig config) {
        if (line == null || Build.VERSION.SDK_INT < 31) return 0f;
        if (!config.lineBlurEnabled) return 0f;
        if (userScrollHeld) return 0f;
        float quality = config.blurQuality;
        if (quality <= 0f) return 0f;
        if (active < 0) return 0f;
        // Apple only: an extended-window active row (index past the active one) never blurs.
        if (config.appleStyle && lineActive) return 0f;
        int distance = Math.abs(index - active);
        if (distance == 0) return 0f;
        String lineText = safe(line.text);
        if (config.lineBlurHeavy) {
            boolean emphasized = lineText.codePointCount(0, lineText.length()) <= 12 || line.dotLine;
            float max = emphasized ? 5.0f : 8.0f;
            float curved = (float) Math.pow(Math.min(1f, distance / 4f), 0.75);
            return max * curved * quality;
        }
        if (distance <= 1) return 0f;
        boolean legacyEmphasized = lineText.codePointCount(0, lineText.length()) <= 12 || line.dotLine;
        float legacyMax = legacyEmphasized ? 1.0f : 1.8f;
        float weighted = legacyMax * Math.min(1f, distance / 4f);
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
