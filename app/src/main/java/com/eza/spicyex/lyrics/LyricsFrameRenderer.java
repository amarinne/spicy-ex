package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Set;

/** Applies one fullscreen lyric animation frame to the currently mounted row window. */
public final class LyricsFrameRenderer {
    private static final int SCROLL_RENDER_MARGIN_ROWS = 8;
    /** A row this short reads as a visual accent rather than a paragraph, and takes a gentler blur
     *  curve so it does not dissolve entirely while its neighbours stay legible. */
    private static final int SHORT_LINE_CODE_POINTS = 12;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsAnimationApplier.StyleSink styleSink;
    private final float scaledDensity;
    private final float density;
    private int lastActiveIndex = Integer.MIN_VALUE;
    private boolean lastUserScrollHeld;

    public LyricsFrameRenderer(Context context, FrameStyleBatcher styleBatcher) {
        this.styleBatcher = styleBatcher;
        scaledDensity = context == null ? 1f : context.getResources().getDisplayMetrics().scaledDensity;
        density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
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

    // Cascading rows are drawn away from their layout slot for a moment, so the culling keeps a
    // couple of rows beyond the estimated viewport.
    private static final int OFFSCREEN_MARGIN_ROWS = 2;

    /**
     * The one culling rule, shared by the frame pass and the pending-animation probe.
     *
     * <p>A row outside this window keeps only its cheap row-level fade/blur, so its scale, glow
     * and syllable springs are left mid-flight by design. Counting such a row as pending would
     * pin the frame scheduler on forever: a paused player with one animating row scrolled out of
     * view would never go idle.
     *
     * @param visibleEnd {@link Integer#MAX_VALUE} for "no viewport known" (all lines visible)
     */
    static boolean isCulledOffscreen(int index, int activeIndex, int visibleStart, int visibleEnd) {
        return index != activeIndex && visibleEnd != Integer.MAX_VALUE
                && (index < visibleStart - OFFSCREEN_MARGIN_ROWS
                    || index > visibleEnd + OFFSCREEN_MARGIN_ROWS);
    }

    /**
     * True while a mounted row the renderer would actually draw still has a spring to drain.
     *
     * <p>Culling has to match {@link #applySynced}: rows that pass culled cannot settle while
     * they are off screen, so they must not hold the scheduler open either.
     */
    public boolean hasPendingAnimation(LyricsDocument document, Set<Integer> mountedIndices,
                                       ViewGroup mountedRowsHost, int activeIndex,
                                       int visibleStart, int visibleEnd) {
        if (document == null || document.appliedLines == null || mountedIndices == null) return false;
        for (int i : mountedIndices) {
            if (i < 0 || i >= document.appliedLines.size()) continue;
            if (isCulledOffscreen(i, activeIndex, visibleStart, visibleEnd)) continue;
            AppliedLine line = document.appliedLines.get(i);
            if (LyricsLineViewState.isMounted(line, mountedRowsHost)
                    && !LyricsLineViewState.isSettled(line)) return true;
        }
        return false;
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
            AppliedLine line = document.appliedLines.get(i);
            if (!LyricsLineViewState.isMounted(line, mountedRowsHost)) continue;
            boolean isOutsideVisibleHoldWindow = userScrollHeld && i != activeIndex
                    && (i < boundedVisibleStart || i > boundedVisibleEnd);
            // Off screen (mounted ahead of the viewport, or already scrolled past): nobody sees its
            // per-syllable motion, so it keeps only its cheap row-level fade/blur. When it scrolls
            // into view its springs are still unsettled and it gets the full pass from then on.
            boolean offscreen = isCulledOffscreen(i, activeIndex, visibleStart, visibleEnd);
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
            if (!lineState.active && (offscreen || !blurNeedsRefresh
                    && !LyricsLineViewState.needsFrame(line, targetClass))) {
                // Keep applying the frame while the blur spring settles. Stepping the spring but
                // returning here left the View's RenderEffect at its old value, so the next
                // active-line refresh appeared to "undo" the gradual blur in one frame.
                float blurTarget = mobileLineBlurPx(line, i, activeIndex, lineState.active, userScrollHeld, config);
                float blur = LyricsLineViewState.stepLineBlur(line, blurTarget, deltaSeconds);
                float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active,
                        lineState.sung, deltaSeconds, config.appleDimPassed);
                LyricsLineViewState.ensureScalePivots(line);
                LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blur);
                continue;
            }
            LyricsLineViewState.invalidateSettled(line);
            float opacity = LyricsAnimationApplier.stepLineOpacity(line, lineState.active, lineState.sung,
                    deltaSeconds, config.appleDimPassed);
            float blurTarget = mobileLineBlurPx(line, i, activeIndex, lineState.active, userScrollHeld, config);
            float blur = LyricsLineViewState.stepLineBlur(line, blurTarget, deltaSeconds);
            // Always drain blur for rows outside the visible hold window, but skip
            // expensive rendering since they're not visible during the scroll hold.
            if (isOutsideVisibleHoldWindow) {
                continue;
            }
            LyricsLineViewState.applyRowFrame(line, styleBatcher, opacity, blur);
            if (config.appleStyle) {
                // Let motion and blur carry the transition; do not add a heavier shadow behind
                // the newly focused line. Drain any legacy shadow state to zero as well.
                float lineShadowTarget = 0f;
                float lineShadow = LyricsLineViewState.stepLineShadow(line, lineShadowTarget, deltaSeconds);
                LyricsLineViewState.applyLineShadow(line, lineShadow);
            }
            float lineGlowTarget = config.glowBlurEnabled ? lineState.glowTarget : 0f;
            if (config.appleDimPassed && lineState.active) lineGlowTarget = Math.max(lineGlowTarget, 0.28f);
            float lineGlow = LyricsAnimationApplier.stepLineGlow(line, lineGlowTarget, deltaSeconds);

            boolean appleLineDocument = config.appleStyle
                    && "Line".equalsIgnoreCase(document.type);
            // Line-synced Apple rows light as a whole; see LyricsLineViewState#stepLineLit.
            float litBrightness = appleLineDocument
                    ? lineState.brightnessTarget * LyricsLineViewState.stepLineLit(
                            line, lineState.active || lineState.sung, deltaSeconds)
                    : 1f;
            if (LyricsLineViewState.hasMainView(line)) {
                float lineScaleTarget = lineLevelBounceEnabled(config, line)
                        ? lineState.scaleTarget : 1f;
                float scale = LyricsAnimationApplier.stepLineScale(line, lineScaleTarget, deltaSeconds);
                LyricsLineViewState.updateMainScalePivot(line);
                scale = LyricsLineViewState.fitScale(LyricsLineViewState.mainViewOf(line),
                        line.oppositeAligned, scale, scaleEdgeMarginPx());
                LyricsLineViewState.applyMainScale(line, styleBatcher, scale);
                if (appleLineDocument) {
                    LyricsLineViewState.applyLineLevelGradient(
                            line, LyricAnimations.GRADIENT_SUNG, 0f, litBrightness);
                } else {
                    LyricsLineViewState.applyLineLevelGradient(
                            line, lineState.gradient, lineGlow, lineState.brightnessTarget);
                }
            } else if (line.words != null && !line.words.isEmpty()) {
                View wordContainer = LyricsSyllableViewState.parentView(line.words.get(0));
                if (wordContainer != null) {
                    boolean bounce = lineLevelBounceEnabled(config, line);
                    float lineScaleTarget = bounce ? lineState.scaleTarget : 1f;
                    GlowFlexbox flex = wordContainer instanceof GlowFlexbox ? (GlowFlexbox) wordContainer : null;
                    boolean perVisualLine = flex != null && flex.visualLineCount() > 1;
                    if (perVisualLine) {
                        // Wrapped line: the block itself only settles to 1; the zoom goes to the
                        // visual line being sung (GlowFlexbox#stepLineZoom).
                        if (lineState.active) lineScaleTarget = Math.min(1f, lineScaleTarget);
                        float zoomTarget = 1f;
                        float heldTarget = 1f;
                        int sungLine = -1;
                        if (lineState.active && bounce) {
                            float maxScale = lineState.spotlight ? 1.05f : 1.03f;
                            // The whole wrapped line zooms as the lyric starts - not following the
                            // audio line by line, which drifted from the singing - but as a quick
                            // top-to-bottom ripple: each visual line a moment after the one above.
                            long intoLyric = positionMs - line.startMs;
                            sungLine = (int) Math.min(flex.visualLineCount() - 1,
                                    Math.max(0L, intoLyric) / VISUAL_LINE_STAGGER_MS);
                            zoomTarget = maxScale;
                            heldTarget = maxScale;
                        }
                        flex.stepLineZoom(sungLine, zoomTarget, heldTarget, deltaSeconds,
                                line.oppositeAligned, scaleEdgeMarginPx());
                    } else if (flex != null && flex.hasLineZoom()) {
                        flex.stepLineZoom(-1, 1f, 1f, deltaSeconds, line.oppositeAligned, scaleEdgeMarginPx());
                    }
                    if (flex != null) {
                        // Held words swell, the rest of their visual line narrows to make room.
                        SyllableSegment held = lineState.active && bounce ? heldWord(line, positionMs) : null;
                        flex.stepWordEmphasis(held == null ? null : LyricsSyllableViewState.wordView(held),
                                held == null ? 0f : heldEmphasis(held, positionMs), deltaSeconds);
                    }
                    float scale = LyricsAnimationApplier.stepLineScale(line, lineScaleTarget, deltaSeconds);
                    if (wordContainer.getWidth() > 0 && wordContainer.getHeight() > 0) {
                        wordContainer.setPivotX(line.oppositeAligned ? wordContainer.getWidth() : 0f);
                        wordContainer.setPivotY(wordContainer.getHeight() * 0.5f);
                    }
                    scale = LyricsLineViewState.fitScale(wordContainer, line.oppositeAligned, scale,
                            scaleEdgeMarginPx());
                    styleBatcher.applyScaleIfChanged(wordContainer, scale, scale);
                }
            }
            if (line.dotLine) {
                if (lineState.active) {
                    LyricsAnimationApplier.animateInterludeDots(line, positionMs, deltaSeconds, spToPx(44), styleSink);
                } else {
                    LyricsAnimationApplier.resetInterludeDots(line, styleSink, lineState.sung,
                            config != null && config.appleStyle);
                }
            } else if (appleLineDocument) {
                // Line-synced Apple rows have no real word spans: never animate fabricated words
                // or sweep them. A row built from word views (reading or furigana attached to
                // fabricated spans) lights as a whole, exactly like a single-text row - resetting
                // its words alone left them at the unsung colour, so the line never lit at all.
                if (!LyricsLineViewState.hasMainView(line) && line.words != null) {
                    LyricsAnimationApplier.resetSyllables(line, styleSink, false);
                    for (SyllableSegment word : line.words) {
                        LyricsSyllableViewState.applyLitFrame(word, litBrightness);
                    }
                }
                LyricsLineViewState.applyLineSecondaryGradient(
                        line, LyricAnimations.GRADIENT_SUNG, 0f);
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
                    if (line.syntheticWords
                            && wordGradientRoute == WordGradientRoute.TIMED_WORDS) {
                        // These word spans are fabricated (evenly spread across the line purely to
                        // attach romanization - see AppliedLine#syntheticWords), not real per-word
                        // timing, so animating them under a word-by-word fill mode faked a
                        // karaoke-style reveal for plain Line-synced lyrics. Same fallback as the
                        // degenerate-provider-timing case above: reset the words and sweep the
                        // line as one sentence instead.
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
                    }
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
        if (config != null && config.appleStyle) return true;
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
     *  rows. A line whose words genuinely cover most of it keeps the timed word fill.
     *  Synthetic word lines (no provider timing, words are segment placeholders spanning the
     *  whole line) are always degenerate: each word shares the line's start/end so lighting
     *  them word-by-word fills the entire line at once instead of sweeping left-to-right. */
    static boolean hasDegenerateWordTiming(AppliedLine line) {
        if (line == null || line.words == null || line.words.isEmpty()) return false;
        if (line.syntheticWords) return true;
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        int counted = 0;
        int collapsed = 0;
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            counted++;
            // A single zero-length span is normal provider noise, not a broken line: QQ's QRC in
            // particular emits them for trailing punctuation and for the spacer "words" it uses
            // between sung syllables. Condemning the whole line on the first one threw away real
            // karaoke timing on lines that were otherwise perfectly word-synced, so this now asks
            // whether MOST of the line is collapsed.
            if (seg.endMs <= seg.startMs) {
                collapsed++;
                continue;
            }
            firstStart = Math.min(firstStart, seg.startMs);
            lastEnd = Math.max(lastEnd, seg.endMs);
        }
        if (counted == 0 || firstStart == Long.MAX_VALUE) return false;
        if (collapsed * 2 >= counted) return true;
        long wordSpan = lastEnd - firstStart;
        if (wordSpan <= 0) return true;
        // fillEndMs(), not endMs: a row's endMs is its ACTIVE window, which applySyncedRows extends
        // across any gap shorter than the interlude threshold so the highlight carries to the next
        // line. Measuring against that made a short, fast line followed by a ~3s instrumental gap
        // look as though its words covered a tiny fraction of it, and word-by-word fill was dropped
        // for the sentence sweep on exactly the lines that most needed it.
        long lineSpan = LyricTimeline.fillEndMs(line) - line.startMs;
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
        
        // Active rows (currently being sung) must always remain sharp.
        if (lineActive) return 0f;
        
        float quality = config.blurQuality;
        if (quality <= 0f) return 0f;
        if (active < 0) return 0f;
        int distance = Math.abs(index - active);
        if (distance == 0) return 0f;
        boolean emphasized = line.dotLine || line.isShortText(SHORT_LINE_CODE_POINTS);
        if (config.appleStyle) {
            // Let nearby rows dissolve into the ambient blur as focus advances. The active row
            // remains sharp; the first neighbour gets a restrained veil and the curve grows
            // smoothly with distance rather than switching on abruptly at row two.
            float max = config.lineBlurHeavy
                    ? (emphasized ? 7.0f : 10.0f)
                    : (emphasized ? 3.2f : 4.8f);
            float curved = (float) Math.pow(Math.min(1f, distance / 3.2f), 0.72);
            return max * curved * quality;
        }
        if (config.lineBlurHeavy) {
            float max = emphasized ? 5.0f : 8.0f;
            float curved = (float) Math.pow(Math.min(1f, distance / 4f), 0.75);
            return max * curved * quality;
        }
        if (distance <= 1) return 0f;
        float legacyMax = emphasized ? 1.0f : 1.8f;
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

    /** The word being sung right now (started, not yet ended), or null. */
    private static SyllableSegment heldWord(AppliedLine line, long positionMs) {
        for (SyllableSegment segment : line.words) {
            if (segment == null) continue;
            if (segment.startMs <= positionMs && positionMs < segment.endMs) return segment;
        }
        return null;
    }

    /**
     * 0..1: how much a word swells. Only held words - short ones never swell, so fast passages
     * stay calm - rising over the first part of the word and holding while it is sustained.
     */
    private static float heldEmphasis(SyllableSegment word, long positionMs) {
        long duration = word.endMs - word.startMs;
        float slowness = LyricAnimations.clamp01((duration - 380f) / 900f);
        if (slowness <= 0f) return 0f;
        float progress = LyricAnimations.clamp01((positionMs - word.startMs) / (float) Math.max(1L, duration));
        return slowness * LyricAnimations.easeSinOut(Math.min(1f, progress * 2.5f));
    }

    /** Visual line of the word being sung (the last one started), or -1. */
    private static int sungVisualLine(GlowFlexbox flex, AppliedLine line, long positionMs) {
        SyllableSegment current = null;
        for (SyllableSegment segment : line.words) {
            if (segment == null) continue;
            if (segment.startMs <= positionMs) current = segment;
            else break;
        }
        if (current == null) current = line.words.get(0);
        View view = LyricsSyllableViewState.wordView(current);
        return view == null ? -1 : flex.visualLineOf(view);
    }

    private static final long VISUAL_LINE_STAGGER_MS = 110L;

    /** When singing reaches the first word of one visual line. */
    private static long visualLineStartMs(GlowFlexbox flex, AppliedLine line, int visualLine) {
        long start = Long.MAX_VALUE;
        if (visualLine >= 0) {
            for (SyllableSegment segment : line.words) {
                if (segment == null) continue;
                View view = LyricsSyllableViewState.wordView(segment);
                if (view != null && flex.visualLineOf(view) == visualLine) start = Math.min(start, segment.startMs);
            }
        }
        return start == Long.MAX_VALUE ? line.startMs : start;
    }

    /** 0..1 through the words on one visual line. */
    private static float visualLineProgress(GlowFlexbox flex, AppliedLine line, int visualLine,
                                            long positionMs) {
        if (visualLine < 0) return 0f;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (SyllableSegment segment : line.words) {
            if (segment == null) continue;
            View view = LyricsSyllableViewState.wordView(segment);
            if (view == null || flex.visualLineOf(view) != visualLine) continue;
            start = Math.min(start, segment.startMs);
            end = Math.max(end, segment.endMs);
        }
        if (start == Long.MAX_VALUE || end <= start) return 0f;
        return LyricAnimations.clamp01((positionMs - start) / (float) (end - start));
    }

    /** Space a zoomed line always keeps from the screen edge. */
    private float scaleEdgeMarginPx() {
        return 16f * density;
    }
}
