package com.eza.spicyex.lyrics;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** View-coordinate helpers for the fullscreen lyric scroll surface. */
public final class LyricsScrollController {
    public static final long ALL_LINES = packRange(0, Integer.MAX_VALUE);
    private static final float DEFAULT_ANCHOR_FRACTION = 0.5f;
    private static final float RAISED_ANCHOR_FRACTION = 0.28f;
    private static final float TOP_BLUR_ZONE_FRACTION = 0.32f;
    private static final float BOTTOM_BLUR_ZONE_FRACTION = 0.18f;
    private final ScrollView scrollView;
    private final LinearLayout contentColumn;
    private final View topStaticSpacer;
    private final Rect workRect = new Rect();
    private float anchorFraction = DEFAULT_ANCHOR_FRACTION;

    public LyricsScrollController(ScrollView scrollView, LinearLayout contentColumn, View topStaticSpacer) {
        this.scrollView = scrollView;
        this.contentColumn = contentColumn;
        this.topStaticSpacer = topStaticSpacer;
    }

    public void setRaisedAnchor(boolean raised) {
        anchorFraction = raised ? RAISED_ANCHOR_FRACTION : DEFAULT_ANCHOR_FRACTION;
    }

    public void applyCenterPadding(int safeTopPx, int bottomPaddingPx, int fallbackViewportHeightPx, int rowHalfPx) {
        if (scrollView == null) return;
        int viewport = scrollView.getHeight();
        if (viewport <= 0) viewport = fallbackViewportHeightPx;
        int topAnchor = Math.max(0, Math.round(viewport * anchorFraction) - rowHalfPx);
        int bottomAnchor = Math.max(0, Math.round(viewport * (1f - anchorFraction)) - rowHalfPx);
        scrollView.setPadding(0, Math.max(safeTopPx, topAnchor), 0, Math.max(bottomPaddingPx, bottomAnchor));
    }

    public int viewportAnchor(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return 0;
        int center = contentAnchorY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop(), anchorFraction);
        int offset = Math.max(0, center - topStaticSpacer.getHeight());
        return LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, offset, lineCount);
    }

    /**
     * {@code bufferLines} pads the strictly-visible range on both ends. The caller uses this to
     * restrict which lines get their per-frame style stepped while the user is mid-drag (a real
     * performance win - most of the document is off-screen during a hold). But with zero buffer, a
     * line sitting just outside the exact viewport gets NO per-frame updates at all while
     * off-screen, so the moment the drag brings it into view it renders its instantaneous "correct
     * for right now" state directly - active (bright) one frame, already-sung (dim) the next -
     * instead of having been smoothly interpolating the whole time it was just out of sight. That
     * hard snap is what read as flicker during manual scroll (confirmed live: a single-frame
     * bright flash immediately followed by a snap to dim, not a fade). A few lines of buffer keeps
     * near-edge lines animating continuously, so by the time they're actually visible they're
     * already at (or smoothly approaching) the right state.
     */
    public long visibleLineRange(int[] rowHeightPrefix, int lineCount) {
        return visibleLineRange(rowHeightPrefix, lineCount, 0);
    }

    public long visibleLineRange(int[] rowHeightPrefix, int lineCount, int bufferLines) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return ALL_LINES;
        int contentTop = scrollView.getScrollY() - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int contentBottom = scrollView.getScrollY() + scrollView.getHeight()
                - scrollView.getPaddingTop() - topStaticSpacer.getHeight();
        int start = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentTop), lineCount);
        int end = LyricsRowVirtualizer.findLineIndexForOffset(rowHeightPrefix, Math.max(0, contentBottom), lineCount);
        start = Math.max(0, start - Math.max(0, bufferLines));
        end = Math.min(lineCount - 1, Math.max(start, end) + Math.max(0, bufferLines));
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

    static int contentAnchorY(int scrollY, int viewportHeight, int paddingTop, float anchorFraction) {
        return scrollY + Math.round(Math.max(1, viewportHeight) * anchorFraction) - Math.max(0, paddingTop);
    }

    public static boolean shouldScrollInstantly(boolean requested, int previousActiveIndex) {
        return requested || previousActiveIndex < 0;
    }

    public int contentYForTouch(float yInScroll) {
        if (scrollView == null) return Math.round(yInScroll);
        return scrollView.getScrollY() + Math.round(yInScroll) - scrollView.getPaddingTop();
    }

    public int centeredScrollTarget(View row) {
        if (scrollView == null || contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        return scrollView.getPaddingTop() + workRect.top
                - Math.round(scrollView.getHeight() * anchorFraction) + (row.getHeight() / 2);
    }

    public boolean isRowVisible(View row, int minVisiblePx) {
        if (scrollView == null || contentColumn == null || row == null) return false;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int top = contentAnchorY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop(), 0f);
        int bottom = contentAnchorY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop(), 1f);
        return Math.min(workRect.bottom, bottom) - Math.max(workRect.top, top) >= Math.max(1, minVisiblePx);
    }

    public float rowTopProximity01(View row) {
        return rowEdgeProximity01(row, 0f);
    }

    public float rowEdgeProximity01(View row, float edgeFraction) {
        if (scrollView == null || contentColumn == null || row == null) return 0f;
        if (row.getWidth() <= 0 || row.getHeight() <= 0 || scrollView.getHeight() <= 0) return 0f;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int viewportTop = contentAnchorY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop(), 0f);
        int sampleY = Math.round(workRect.top + edgeFraction * (workRect.bottom - workRect.top));
        int offset = sampleY - viewportTop;
        int zonePx = Math.round(Math.max(1, scrollView.getHeight()) * TOP_BLUR_ZONE_FRACTION);
        if (offset >= zonePx) return 0f;
        return 1f - Math.max(0f, Math.min(1f, offset / (float) zonePx));
    }

    public float rowBottomEdgeProximity01(View row, float edgeFraction) {
        if (scrollView == null || contentColumn == null || row == null) return 0f;
        if (row.getWidth() <= 0 || row.getHeight() <= 0 || scrollView.getHeight() <= 0) return 0f;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int viewportBottom = contentAnchorY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop(), 1f);
        int sampleY = Math.round(workRect.top + edgeFraction * (workRect.bottom - workRect.top));
        int offset = viewportBottom - sampleY;
        int zonePx = Math.round(Math.max(1, scrollView.getHeight()) * BOTTOM_BLUR_ZONE_FRACTION);
        if (offset >= zonePx) return 0f;
        return 1f - Math.max(0f, Math.min(1f, offset / (float) zonePx));
    }

    public int rowCenterInContent(View row) {
        if (contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        return workRect.top + Math.max(1, workRect.height()) / 2;
    }
}
