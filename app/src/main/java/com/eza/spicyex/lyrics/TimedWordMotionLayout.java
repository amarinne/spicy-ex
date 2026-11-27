package com.eza.spicyex.lyrics;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Atomic timed-word container that preserves direct Flexbox baseline geometry. */
final class TimedWordMotionLayout extends ViewGroup {
    private int measuredBaseline = -1;
    private int[] childRows = new int[0];
    private int[] rowHeights = new int[0];
    private int[] rowAscents = new int[0];
    private float[] childTargetScales = new float[0];
    private int[] seamWidthsScratch = new int[0];
    private float[] seamScalesScratch = new float[0];
    private float[] seamOffsetsScratch = new float[0];

    TimedWordMotionLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int childCount = getChildCount();
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        int available = View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(1, MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding);
        int[] widths = new int[childCount];
        int[] heights = new int[childCount];
        int[] ascents = new int[childCount];
        int childState = 0;
        for (int index = 0; index < childCount; index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            // Measure every child against the full available width. Passing the accumulated row
            // width as widthUsed makes later CJK fragments shrink during probing and produces a
            // different layout from the final pass.
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            widths[index] = lp.leftMargin + child.getMeasuredWidth() + lp.rightMargin;
            heights[index] = lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
            int baseline = child.getBaseline();
            if (baseline >= 0) {
                ascents[index] = lp.topMargin + baseline;
            } else {
                ascents[index] = -1;
            }
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }

        childRows = new int[childCount];
        java.util.Arrays.fill(childRows, -1);
        boolean[] planned = AdaptiveBreakPlanner.plan(widths, available, null, null);
        int rowCount = 0;
        int rowWidth = 0;
        for (int index = 0; index < childCount; index++) {
            if (getChildAt(index).getVisibility() == GONE) continue;
            boolean wrap = index > 0 && planned.length > index && planned[index];
            // The planner deliberately falls back when one child is wider than the viewport.
            // Keep the group bounded anyway by starting a new row before every subsequent child.
            if (!wrap && rowWidth > 0 && rowWidth + widths[index] > available) wrap = true;
            if (wrap || rowWidth == 0) {
                if (rowWidth > 0) rowCount++;
                rowWidth = 0;
            }
            childRows[index] = rowCount;
            rowWidth += widths[index];
        }
        if (rowWidth > 0) rowCount++;
        rowHeights = new int[rowCount];
        rowAscents = new int[rowCount];
        java.util.Arrays.fill(rowAscents, -1);
        int[] rowWidths = new int[rowCount];
        for (int index = 0; index < childCount; index++) {
            int row = childRows[index];
            if (row < 0) continue;
            rowWidths[row] += widths[index];
            rowHeights[row] = Math.max(rowHeights[row], heights[index]);
            if (ascents[index] >= 0) rowAscents[row] = Math.max(rowAscents[row], ascents[index]);
        }
        for (int row = 0; row < rowCount; row++) {
            if (rowAscents[row] < 0) continue;
            int descent = rowHeights[row] - rowAscents[row];
            rowHeights[row] = baselineHeight(rowHeights[row], rowAscents[row], descent);
        }
        int naturalWidth = horizontalPadding;
        for (int rowWidthValue : rowWidths) naturalWidth = Math.max(naturalWidth, horizontalPadding + rowWidthValue);
        int contentHeight = 0;
        for (int rowHeight : rowHeights) contentHeight += rowHeight;
        measuredBaseline = rowCount == 0 || rowAscents[0] < 0
                ? -1 : getPaddingTop() + rowAscents[0];
        int height = verticalPadding + contentHeight;
        setMeasuredDimension(
                resolveSizeAndState(Math.max(naturalWidth, getSuggestedMinimumWidth()),
                        widthMeasureSpec, childState),
                resolveSizeAndState(Math.max(height, getSuggestedMinimumHeight()),
                        heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int rowTop = getPaddingTop();
        int[] rowX = new int[rowHeights.length];
        for (int row = 0; row < rowHeights.length; row++) {
            rowX[row] = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
            for (int index = 0; index < getChildCount(); index++) {
                if (childRows[index] != row) continue;
                View child = getChildAt(index);
                if (child.getVisibility() == GONE) continue;
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int childBaseline = child.getBaseline();
                int childTop = rowTop + lp.topMargin;
                if (rowAscents[row] >= 0 && childBaseline >= 0) {
                    childTop = rowTop + rowAscents[row] - childBaseline;
                }
                if (rtl) {
                    rowX[row] -= lp.rightMargin + child.getMeasuredWidth();
                    child.layout(rowX[row], childTop, rowX[row] + child.getMeasuredWidth(),
                            childTop + child.getMeasuredHeight());
                    rowX[row] -= lp.leftMargin;
                } else {
                    rowX[row] += lp.leftMargin;
                    child.layout(rowX[row], childTop, rowX[row] + child.getMeasuredWidth(),
                            childTop + child.getMeasuredHeight());
                    rowX[row] += child.getMeasuredWidth() + lp.rightMargin;
                }
            }
            rowTop += rowHeights[row];
        }
        alignScaledSeams();
        /*
         * Layout is intentionally row-based rather than a single horizontal strip. The outer
         * GlowFlexbox can only wrap this ViewGroup as one timing atom; putting all of its CJK
         * children on one line was the source of the off-screen overflow.
         */
    }

    /** Records the next frame's horizontal scale for one syllable child before seam alignment. */
    void recordChildScaleX(View child, float scaleX) {
        int count = getChildCount();
        ensureSeamBuffers(count);
        for (int i = 0; i < count; i++) {
            if (getChildAt(i) == child) {
                childTargetScales[i] = scaleX;
                return;
            }
        }
    }

    /**
     * Keeps adjacent scaled syllable children pixel-flush (zero seam gap, zero overlap) by
     * pinning each child's horizontal pivot to its leading edge and shifting each child's
     * translationX to absorb its siblings' scale deltas.
     */
    void alignScaledSeams() {
        int count = getChildCount();
        if (count == 0 || rowHeights.length == 0 || childRows.length < count) return;
        ensureSeamBuffers(count);
        boolean anyScaled = false;
        boolean anyTranslated = false;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            int width = child.getVisibility() == GONE ? 0 : child.getWidth();
            seamWidthsScratch[i] = width;
            float scale = childTargetScales[i];
            seamScalesScratch[i] = scale;
            if (Math.abs(scale - 1f) > 0.0005f) anyScaled = true;
            if (Math.abs(child.getTranslationX()) > 0.05f) anyTranslated = true;
        }
        if (!anyScaled && !anyTranslated) return;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int parentWidth = getParent() instanceof View ? ((View) getParent()).getWidth() : 0;
        computeSeamOffsets(seamWidthsScratch, seamScalesScratch, childRows, count,
                rowHeights.length, rtl, getLeft(), getRight(), getWidth(), parentWidth,
                seamOffsetsScratch);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int width = child.getWidth();
            int height = child.getHeight();
            if (width > 0 && height > 0) {
                float pivotX = rtl ? width : 0f;
                if (Math.abs(child.getPivotX() - pivotX) > 0.5f) child.setPivotX(pivotX);
                if (Math.abs(child.getPivotY() - height) > 0.5f) child.setPivotY(height);
            }
            float tx = seamOffsetsScratch[i];
            if (Math.abs(child.getTranslationX() - tx) > 0.05f) {
                child.setTranslationX(tx);
            }
        }
    }

    private void ensureSeamBuffers(int count) {
        if (childTargetScales.length < count) {
            int oldLen = childTargetScales.length;
            childTargetScales = java.util.Arrays.copyOf(childTargetScales, count);
            java.util.Arrays.fill(childTargetScales, oldLen, count, 1f);
        }
        if (seamWidthsScratch.length < count) {
            seamWidthsScratch = new int[count];
            seamScalesScratch = new float[count];
            seamOffsetsScratch = new float[count];
        }
    }

    /**
     * Pure seam-alignment geometry (unit-tested). Given each child's unscaled width, current
     * horizontal scale (pivoted at leading edge: 0 in LTR, width in RTL), and wrapped row index,
     * writes each child's translationX into {@code outOffsets} so siblings stay edge-to-edge while
     * the word stays centered in its layout box (or clamped inward near row edges).
     */
    static void computeSeamOffsets(int[] widths, float[] scales, int[] rows, int count,
                                   int rowCount, boolean rtl, int leftEdge, int rightEdge,
                                   int containerWidth, int parentWidth, float[] outOffsets) {
        java.util.Arrays.fill(outOffsets, 0, count, 0f);
        if (count <= 0 || rowCount <= 0) return;
        float expansion = containerWidth * ((1.025f - 1f) * 0.5f);
        boolean pinLeft = leftEdge < expansion;
        boolean pinRight = parentWidth > 0 && (parentWidth - rightEdge) < expansion;
        for (int row = 0; row < rowCount; row++) {
            float rowDelta = 0f;
            for (int i = 0; i < count; i++) {
                if (rows[i] != row || widths[i] <= 0) continue;
                rowDelta += (scales[i] - 1f) * widths[i];
            }
            float anchor;
            if (rtl) {
                anchor = pinRight ? 0f : pinLeft ? rowDelta : rowDelta * 0.5f;
            } else {
                anchor = pinLeft ? 0f : pinRight ? -rowDelta : -rowDelta * 0.5f;
            }
            float accDelta = 0f;
            for (int i = 0; i < count; i++) {
                if (rows[i] != row || widths[i] <= 0) continue;
                outOffsets[i] = rtl ? (anchor - accDelta) : (anchor + accDelta);
                accDelta += (scales[i] - 1f) * widths[i];
            }
        }
    }

    @Override
    public int getBaseline() {
        return measuredBaseline;
    }

    static int baselineHeight(int maxNaturalHeight, int maxAscent, int maxDescent) {
        if (maxAscent < 0 || maxDescent < 0) return Math.max(0, maxNaturalHeight);
        return Math.max(maxNaturalHeight, maxAscent + maxDescent);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams source) {
        return source instanceof MarginLayoutParams
                ? new MarginLayoutParams((MarginLayoutParams) source)
                : new MarginLayoutParams(source);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}
