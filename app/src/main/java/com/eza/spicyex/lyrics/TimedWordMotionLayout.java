package com.eza.spicyex.lyrics;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Atomic timed-word container that preserves direct Flexbox baseline geometry. */
final class TimedWordMotionLayout extends ViewGroup {
    private int measuredBaseline = -1;

    TimedWordMotionLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getPaddingLeft() + getPaddingRight();
        int maxNaturalHeight = 0;
        int maxAscent = -1;
        int maxDescent = -1;
        int childState = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, width, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = lp.leftMargin + child.getMeasuredWidth() + lp.rightMargin;
            int childHeight = lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
            width += childWidth;
            maxNaturalHeight = Math.max(maxNaturalHeight, childHeight);
            int baseline = child.getBaseline();
            if (baseline >= 0) {
                maxAscent = Math.max(maxAscent, lp.topMargin + baseline);
                maxDescent = Math.max(maxDescent,
                        child.getMeasuredHeight() - baseline + lp.bottomMargin);
            }
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }
        int contentHeight = baselineHeight(maxNaturalHeight, maxAscent, maxDescent);
        measuredBaseline = maxAscent < 0 ? -1 : getPaddingTop() + maxAscent;
        int height = getPaddingTop() + contentHeight + getPaddingBottom();
        setMeasuredDimension(
                resolveSizeAndState(Math.max(width, getSuggestedMinimumWidth()),
                        widthMeasureSpec, childState),
                resolveSizeAndState(Math.max(height, getSuggestedMinimumHeight()),
                        heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
        int baselineInContent = measuredBaseline < 0 ? -1 : measuredBaseline - getPaddingTop();
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childBaseline = child.getBaseline();
            int childTop = getPaddingTop() + lp.topMargin;
            if (baselineInContent >= 0 && childBaseline >= 0) {
                childTop = getPaddingTop() + baselineInContent - childBaseline;
            }
            if (rtl) {
                x -= lp.rightMargin + child.getMeasuredWidth();
                child.layout(x, childTop, x + child.getMeasuredWidth(),
                        childTop + child.getMeasuredHeight());
                x -= lp.leftMargin;
            } else {
                x += lp.leftMargin;
                child.layout(x, childTop, x + child.getMeasuredWidth(),
                        childTop + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + lp.rightMargin;
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
