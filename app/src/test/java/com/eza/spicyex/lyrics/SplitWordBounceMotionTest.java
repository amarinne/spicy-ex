package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.settings.values.WordBounceStyle;

import java.lang.reflect.Field;
import org.junit.Test;

/**
 * Verifies smooth, seam-locked per-syllable zoom and lift on split words, exact edge-to-edge
 * seam alignment in LTR/RTL/edge-clamped layouts, responsive damped springs, eased Y splines,
 * consistent Apple lift heights, and lift-mode resets.
 */
public class SplitWordBounceMotionTest {
    private static final float EPS = 1e-4f;

    @Test
    public void wordZoomSplitWordUsesTrueZoomCurvePerSyllableNotLift() {
        // Split word never drops down to per-letter motion inside a group.
        assertFalse(LyricsAnimationApplier.usesLetterUnitMotion(true, true, 3));
        assertFalse(LyricsAnimationApplier.usesAppleLetterDrive(true, true, true, 3));

        // Wrapper stays neutral in word-level modes; each syllable gets the exact Word zoom curve.
        assertEquals(1f, LyricsAnimationApplier.wrapperTargetScale(
                false, true, false, true, false, 0.7f, false, 0f), EPS);
        assertEquals(0f, LyricsAnimationApplier.wrapperTargetY(
                false, false, true, false, true, false, 0.9f, false, 0f), EPS);

        // Unsung syllable rests at 0.95 / +0.01em, active peaks at 1.025 / -1/60em (NOT -0.04em lift),
        // and sung settles at 1.0 / 0 — identical to whole-word Word zoom.
        assertEquals(0.95f, LyricsAnimationApplier.groupedLocalScale(true, false, false, false, 0f), EPS);
        assertEquals(0.01f, LyricsAnimationApplier.groupedLocalY(true, false, false, false, false, 0f), EPS);
        assertEquals(1.025f, LyricsAnimationApplier.groupedLocalScale(true, false, true, false, 0.7f), EPS);
        assertEquals(-(1f / 60f), LyricsAnimationApplier.groupedLocalY(true, false, false, true, false, 0.9f), EPS);
        assertEquals(1f, LyricsAnimationApplier.groupedLocalScale(true, false, false, true, 1f), EPS);
        assertEquals(0f, LyricsAnimationApplier.groupedLocalY(true, false, false, false, true, 1f), EPS);
    }

    @Test
    public void phraseModesAnimateWholeSplitWordOnWrapperWithNeutralChildren() {
        assertEquals(1.025f, LyricsAnimationApplier.wrapperTargetScale(
                false, false, false, true, false, 0.7f, false, 0f), EPS);
        assertEquals(-(1f / 60f), LyricsAnimationApplier.wrapperTargetY(
                false, false, false, false, true, false, 0.9f, false, 0f), EPS);
        assertEquals(-0.04f, LyricsAnimationApplier.wrapperTargetY(
                false, false, false, true, true, false, 0.5f, false, 0f), EPS);
        assertEquals(1f, LyricsAnimationApplier.groupedLocalScale(false, false, true, false, 0.5f), EPS);
        assertEquals(0f, LyricsAnimationApplier.groupedLocalY(false, false, false, true, false, 0.5f), EPS);
    }

    @Test
    public void wordLiftAndAppleLiftAnimateSplitSyllablesWithoutHeightMismatch() {
        assertEquals(1f, LyricsAnimationApplier.wrapperTargetScale(
                false, true, true, true, false, 0.5f, false, 0f), EPS);
        assertEquals(0f, LyricsAnimationApplier.wrapperTargetY(
                false, false, true, true, true, false, 0.5f, true, 0f), EPS);

        assertEquals(-0.04f, LyricsAnimationApplier.groupedLocalY(true, true, false, true, false, 0.5f), EPS);
        assertEquals(0f, LyricsAnimationApplier.groupedLocalY(true, true, false, false, true, 1f), EPS);

        assertEquals(0f, LyricsAnimationApplier.groupedLocalY(true, true, true, false, false, 0f), EPS);
        float midApple = LyricsAnimationApplier.groupedLocalY(true, true, true, true, false, 0.5f);
        assertTrue(midApple < 0f && midApple > -LyricAnimations.APPLE_LIFT_EM);
        assertEquals(-LyricAnimations.APPLE_LIFT_EM,
                LyricsAnimationApplier.groupedLocalY(true, true, true, false, true, 1f), EPS);
    }

    @Test
    public void seamOffsetsKeepLtrScaledSyllablesFlushWithZeroGapOrOverlap() {
        // "to"(60px) + "ge"(40px) + "ther"(80px) on one mid-line row, total = 180px.
        int[] widths = {60, 40, 80};
        int[] rows = {0, 0, 0};
        float[] tx = new float[3];

        // Case 1: all unsung at 0.95 — centered, 0.0px seam error.
        float[] unsung = {0.95f, 0.95f, 0.95f};
        TimedWordMotionLayout.computeSeamOffsets(widths, unsung, rows, 3, 1, false,
                200, 380, 180, 1000, tx);
        assertFlushLtr(widths, unsung, tx, 0f, 180f, true);

        // Case 2: "to" sung (1.0), "ge" zooming (1.025), "ther" unsung (0.95) — zero overlap, zero gap.
        float[] active = {1.0f, 1.025f, 0.95f};
        TimedWordMotionLayout.computeSeamOffsets(widths, active, rows, 3, 1, false,
                200, 380, 180, 1000, tx);
        assertFlushLtr(widths, active, tx, 0f, 180f, true);

        // Case 3: left-edge word pins left edge at 0 so it never clips.
        TimedWordMotionLayout.computeSeamOffsets(widths, active, rows, 3, 1, false,
                0, 180, 180, 1000, tx);
        assertEquals(0f, tx[0], EPS);
        assertFlushLtr(widths, active, tx, 0f, 180f, false);

        // Case 4: right-edge word pins right edge at 180 so it never clips.
        TimedWordMotionLayout.computeSeamOffsets(widths, active, rows, 3, 1, false,
                820, 1000, 180, 1000, tx);
        float rightEdge = (60 + 40) + tx[2] + 80 * active[2];
        assertEquals(180f, rightEdge, EPS);
        assertFlushLtr(widths, active, tx, 0f, 180f, false);
    }

    @Test
    public void seamOffsetsKeepRtlAndWrappedRowsFlush() {
        // RTL: child 0 on right [40, 100] (w=60), child 1 on left [0, 40] (w=40).
        int[] widths = {60, 40};
        float[] scales = {1.025f, 0.95f};
        int[] rows = {0, 0};
        float[] tx = new float[2];
        TimedWordMotionLayout.computeSeamOffsets(widths, scales, rows, 2, 1, true,
                300, 400, 100, 1000, tx);
        float child0Right = 100f + tx[0];
        float child0Left = child0Right - 60f * scales[0];
        float child1Right = 40f + tx[1];
        float child1Left = child1Right - 40f * scales[1];
        assertEquals("RTL adjacent syllables must touch flush", child0Left, child1Right, EPS);
        assertEquals("RTL mid-line word stays centered", 50f, (child1Left + child0Right) * 0.5f, EPS);

        // Wrapped two-row group computes each row independently.
        int[] wrappedWidths = {50, 50, 70};
        float[] wrappedScales = {1.025f, 0.95f, 0.95f};
        int[] wrappedRows = {0, 0, 1};
        float[] wrappedTx = new float[3];
        TimedWordMotionLayout.computeSeamOffsets(wrappedWidths, wrappedScales, wrappedRows, 3, 2,
                false, 200, 300, 100, 1000, wrappedTx);
        assertEquals(wrappedTx[0] + 50f * 1.025f, 50f + wrappedTx[1], EPS);
        // Row 1 single child centered around its own 0.95 shrink (+0.025 * 70 = +1.75).
        assertEquals(1.75f, wrappedTx[2], EPS);
    }

    @Test
    public void ungroupedMultiLetterWordsKeepLetterUnitAndAppleLetterDrive() {
        assertTrue(LyricsAnimationApplier.usesLetterUnitMotion(true, false, 4));
        assertFalse(LyricsAnimationApplier.usesLetterUnitMotion(false, false, 4));
        assertFalse(LyricsAnimationApplier.usesLetterUnitMotion(true, false, 1));
        assertTrue(LyricsAnimationApplier.usesAppleLetterDrive(true, true, false, 4));
        assertFalse(LyricsAnimationApplier.usesAppleLetterDrive(true, true, false, 1));
    }

    @Test
    public void appleLetterLiftWaveMatchesWordAppleLiftHeight() {
        assertEquals(0f, LyricsAnimationApplier.appleLetterLiftY(0f, 0, 4), EPS);
        assertEquals(-LyricAnimations.APPLE_LIFT_EM,
                LyricsAnimationApplier.appleLetterLiftY(1f, 0, 4), EPS);
        assertEquals(-LyricAnimations.APPLE_LIFT_EM,
                LyricsAnimationApplier.appleLetterLiftY(1f, 3, 4), EPS);
        assertEquals(LyricsAnimationApplier.wordMotionY(true, false, true, 1f, true),
                LyricsAnimationApplier.appleLetterLiftY(1f, 2, 4), EPS);
    }

    @Test
    public void yOffsetSplineEasesSmoothlyAcrossPeakAndLanding() {
        float atPeak = LyricAnimations.yOffsetSpline(0.9f);
        float justBeforePeak = LyricAnimations.yOffsetSpline(0.89f);
        float justAfterPeak = LyricAnimations.yOffsetSpline(0.91f);
        assertEquals(-(1f / 60f), atPeak, EPS);
        assertTrue("approaching peak should ease instead of a linear corner",
                Math.abs(atPeak - justBeforePeak) < 0.0002f);
        assertTrue("leaving peak should ease instead of snapping",
                Math.abs(justAfterPeak - atPeak) < 0.001f);
        assertTrue("landing at t=1 should ease gently",
                Math.abs(LyricAnimations.yOffsetSpline(0.99f)) < 0.001f);
    }

    @Test
    public void resetScalesMatchSplitWordAndLiftContracts() {
        // Word zoom on grouped split word: children rest at 0.95 (seam-locked), wrapper stays 1.0.
        float[] wordZoomGrouped = LyricsSyllableViewState.resetScales(true, false, true, true, 3);
        assertEquals(1f, wordZoomGrouped[0], EPS);
        assertEquals(0.95f, wordZoomGrouped[1], EPS);
        assertEquals(1f, wordZoomGrouped[2], EPS);

        // Phrase zoom on grouped split word: wrapper rests at 0.95, children stay 1.0.
        float[] phraseZoomGrouped = LyricsSyllableViewState.resetScales(true, false, false, true, 3);
        assertEquals(0.95f, phraseZoomGrouped[0], EPS);
        assertEquals(1f, phraseZoomGrouped[1], EPS);
        assertEquals(1f, phraseZoomGrouped[2], EPS);

        // Word zoom on ungrouped multi-letter word: letters rest at 0.95, wrapper stays 1.0.
        float[] ungroupedLettersZoom = LyricsSyllableViewState.resetScales(true, false, true, false, 4);
        assertEquals(1f, ungroupedLettersZoom[0], EPS);
        assertEquals(1f, ungroupedLettersZoom[1], EPS);
        assertEquals(0.95f, ungroupedLettersZoom[2], EPS);

        // Lift modes (Word lift, Apple lift, Apple Music): everything rests at 1.0.
        float[] groupedLift = LyricsSyllableViewState.resetScales(true, true, true, true, 3);
        assertEquals(1f, groupedLift[0], EPS);
        assertEquals(1f, groupedLift[1], EPS);
        assertEquals(1f, groupedLift[2], EPS);
    }

    @Test
    public void liftMotionCoversAppleLiftWithoutAppleStyleForResetConsistency() throws Exception {
        LyricsRenderConfig appleLiftShared = withFields(WordBounceStyle.APPLE_LIFT, false, true);
        assertTrue(LyricsFrameRenderer.liftMotion(appleLiftShared));

        LyricsRenderConfig appleMusic = withFields(WordBounceStyle.WORD_ZOOM, true, true);
        assertTrue(LyricsFrameRenderer.liftMotion(appleMusic));

        LyricsRenderConfig wordZoom = withFields(WordBounceStyle.WORD_ZOOM, false, false);
        assertFalse(LyricsFrameRenderer.liftMotion(wordZoom));
    }

    @Test
    public void responsiveZoomSpringsReachVisiblePeakAndSettleCleanly() {
        SyllableSegment syl = new SyllableSegment();
        LyricsSyllableViewState.syncSegmentMotion(syl, false);
        LyricsSyllableViewState.stepLocalWordScale(syl, 0.95f, 1f / 60f, false);
        LyricsSyllableViewState.stepLocalWordY(syl, 0.01f, 1f / 60f, false);

        // Simulate a 220ms syllable under Word zoom at 60fps: blooms smoothly past 1.0 and settles.
        float peakScale = 0.95f;
        float minY = 0.01f;
        for (int frame = 0; frame < 50; frame++) {
            float tMs = frame * (1000f / 60f);
            boolean active = tMs < 220f;
            float prog = Math.min(1f, tMs / 220f);
            float scaleTarget = LyricsAnimationApplier.groupedLocalScale(true, false, active, !active, prog);
            float yTarget = LyricsAnimationApplier.groupedLocalY(true, false, false, active, !active, prog);
            peakScale = Math.max(peakScale,
                    LyricsSyllableViewState.stepLocalWordScale(syl, scaleTarget, 1f / 60f, false));
            minY = Math.min(minY,
                    LyricsSyllableViewState.stepLocalWordY(syl, yTarget, 1f / 60f, false));
        }
        assertTrue("220ms zoom syllable should visibly bloom past 1.0, got " + peakScale,
                peakScale >= 1.016f);
        assertTrue("220ms zoom syllable should use gentle zoom Y, not lift Y, got " + minY,
                minY <= -0.010f && minY >= -0.022f);
        assertTrue("syllable springs should settle to rest after finishing",
                LyricsSyllableViewState.isSettled(syl));

        // Oversized deltaSeconds after wake stays clamped and smooth.
        SyllableSegment wakeSeg = new SyllableSegment();
        LyricsSyllableViewState.syncSegmentMotion(wakeSeg, false);
        LyricsSyllableViewState.stepWordScale(wakeSeg, 0.95f, 1f / 60f, false);
        float firstScale = LyricsSyllableViewState.stepWordScale(wakeSeg, 1.025f, 5f, false);
        assertTrue("clamped wake step stays smooth", firstScale > 0.95f && firstScale < 1.04f);
    }

    private static void assertFlushLtr(int[] widths, float[] scales, float[] tx,
                                       float boxLeft, float boxRight, boolean expectCentered) {
        float layoutX = boxLeft;
        float prevRight = Float.NaN;
        float firstLeft = 0f;
        float lastRight = 0f;
        for (int i = 0; i < widths.length; i++) {
            float left = layoutX + tx[i];
            float right = left + widths[i] * scales[i];
            if (i == 0) firstLeft = left;
            if (i > 0) {
                assertEquals("adjacent syllables must stay edge-to-edge", prevRight, left, EPS);
            }
            prevRight = right;
            lastRight = right;
            layoutX += widths[i];
        }
        if (expectCentered) {
            assertEquals("centered word keeps its midpoint",
                    (boxLeft + boxRight) * 0.5f, (firstLeft + lastRight) * 0.5f, EPS);
        }
    }

    private static LyricsRenderConfig withFields(WordBounceStyle bounceStyle, boolean appleStyle,
                                                 boolean appleLift) throws Exception {
        LyricsRenderConfig config = LyricsRenderConfig.read(null, null);
        setField(config, "wordBounceStyle", bounceStyle);
        setField(config, "appleStyle", appleStyle);
        setField(config, "appleLift", appleLift);
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
