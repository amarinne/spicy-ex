package com.eza.spicyex.lyrics;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** View-coordinate helpers for the fullscreen lyric scroll surface. */
public final class LyricsScrollController {
    public static final long ALL_LINES = packRange(0, Integer.MAX_VALUE);
    private final ScrollView scrollView;
    private final LinearLayout contentColumn;
    private final View topStaticSpacer;
    private final Rect workRect = new Rect();

    public LyricsScrollController(ScrollView scrollView, LinearLayout contentColumn, View topStaticSpacer) {
        this.scrollView = scrollView;
        this.contentColumn = contentColumn;
        this.topStaticSpacer = topStaticSpacer;
    }

    public void applyCenterPadding(int safeTopPx, int bottomPaddingPx, int fallbackViewportHeightPx, int rowHalfPx) {
        if (scrollView == null) return;
        int viewport = scrollView.getHeight();
        if (viewport <= 0) viewport = fallbackViewportHeightPx;
        int center = Math.max(0, viewport / 2 - rowHalfPx);
        scrollView.setPadding(0, Math.max(safeTopPx, center), 0, Math.max(bottomPaddingPx, center));
    }

    public int viewportAnchor(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return 0;
        int center = contentCenterY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop());
        int offset = Math.max(0, center - topStaticSpacer.getHeight());
        return LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, offset, lineCount);
    }

    public long visibleLineRange(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return ALL_LINES;
        int contentTop = scrollView.getScrollY() - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int contentBottom = scrollView.getScrollY() + scrollView.getHeight()
                - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int start = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentTop), lineCount);
        int end = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentBottom), lineCount);
        return packRange(start, Math.max(start, end));
    }

    public static int rangeStart(long packedRange) {
        return (int) (packedRange >> 32);
    }

    public static int rangeEnd(long packedRange) {
        return (int) packedRange;
    }

    private static long packRange(int start, int end) {
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    static int contentCenterY(int scrollY, int viewportHeight, int paddingTop) {
        return scrollY + Math.max(1, viewportHeight) / 2 - Math.max(0, paddingTop);
    }

    public int contentYForTouch(float yInScroll) {
        if (scrollView == null) return Math.round(yInScroll);
        return scrollView.getScrollY() + Math.round(yInScroll) - scrollView.getPaddingTop();
    }

    public int centeredScrollTarget(View row) {
        if (scrollView == null || contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        return scrollView.getPaddingTop() + workRect.top - (scrollView.getHeight() / 2) + (row.getHeight() / 2);
    }

    public boolean isRowVisible(View row, int minVisiblePx) {
        if (scrollView == null || contentColumn == null || row == null) return false;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int top = scrollView.getScrollY() + scrollView.getPaddingTop();
        int bottom = scrollView.getScrollY() + scrollView.getHeight() - scrollView.getPaddingBottom();
        return Math.min(workRect.bottom, bottom) - Math.max(workRect.top, top) >= Math.max(1, minVisiblePx);
    }

    public int rowCenterInContent(View row) {
        if (contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        return workRect.top + Math.max(1, workRect.height()) / 2;
    }
}
