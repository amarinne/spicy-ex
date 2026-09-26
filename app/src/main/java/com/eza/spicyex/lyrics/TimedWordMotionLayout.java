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
        /*
         * Layout is intentionally row-based rather than a single horizontal strip. The outer
         * GlowFlexbox can only wrap this ViewGroup as one timing atom; putting all of its CJK
         * children on one line was the source of the off-screen overflow.
         */
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
