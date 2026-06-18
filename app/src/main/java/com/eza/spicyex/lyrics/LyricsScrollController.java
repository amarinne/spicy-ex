package com.eza.spicyex.lyrics;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** View-coordinate helpers for the fullscreen lyric scroll surface. */
public final class LyricsScrollController {
    private final ScrollView scrollView;
    private final LinearLayout contentColumn;
    private final View topStaticSpacer;

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
        int center = scrollView.getScrollY() + Math.max(1, scrollView.getHeight()) / 2;
        int offset = Math.max(0, center - topStaticSpacer.getHeight());
        return LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, offset, lineCount);
    }

    public int contentYForTouch(float yInScroll) {
        if (scrollView == null) return Math.round(yInScroll);
        return scrollView.getScrollY() + Math.round(yInScroll) - scrollView.getPaddingTop();
    }

    public int centeredScrollTarget(View row) {
        if (scrollView == null || contentColumn == null || row == null) return 0;
        Rect r = new Rect(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, r);
        return scrollView.getPaddingTop() + r.top - (scrollView.getHeight() / 2) + (row.getHeight() / 2);
    }

    public int rowCenterInContent(View row) {
        if (contentColumn == null || row == null) return 0;
        Rect r = new Rect(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, r);
        return r.top + Math.max(1, r.height()) / 2;
    }
}
