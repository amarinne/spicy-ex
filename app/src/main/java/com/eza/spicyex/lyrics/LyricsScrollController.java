package com.eza.spicyex.lyrics;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** View-coordinate helpers for the fullscreen lyric scroll surface. */
public final class LyricsScrollController {
    public static final long ALL_LINES = packRange(0, Integer.MAX_VALUE);
    public static final float CENTER_ANCHOR_FRACTION = 0.5f;
    /** Apple slide rest anchor (active line sits high); mirrored below the center for Bottom. */
    public static final float RAISED_ANCHOR_FRACTION = 0.28f;
    public static final float LOWERED_ANCHOR_FRACTION = 1f - RAISED_ANCHOR_FRACTION;
    private float anchorFraction = CENTER_ANCHOR_FRACTION;
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
        applyCenterPadding(safeTopPx, bottomPaddingPx, fallbackViewportHeightPx, rowHalfPx, 0);
    }

    /** @param sidePaddingPx horizontal inset for the lyric text itself - kept here rather than on
     *  an outer container so the container's own background/blur surface can still reach the
     *  true screen edges while the text keeps a safe reading margin. */
    public void applyCenterPadding(int safeTopPx, int bottomPaddingPx, int fallbackViewportHeightPx,
            int rowHalfPx, int sidePaddingPx) {
        if (scrollView == null) return;
        int viewport = scrollView.getHeight();
        if (viewport <= 0) viewport = fallbackViewportHeightPx;
        int topAnchor;
        int bottomAnchor;
        if (anchorFraction == CENTER_ANCHOR_FRACTION) {
            int center = Math.max(0, viewport / 2 - rowHalfPx);
            topAnchor = center;
            bottomAnchor = center;
        } else {
            topAnchor = Math.max(0, Math.round(viewport * anchorFraction) - rowHalfPx);
            bottomAnchor = Math.max(0, Math.round(viewport * (1f - anchorFraction)) - rowHalfPx);
        }
        // Horizontal padding is removed from the scroll container itself and moved to individual
        // rows (see LyricsRowViewFactory) so the rows can reach the true screen edges for unclipped
        // blur/glow effects while the text keeps its margin.
        scrollView.setPadding(0, Math.max(safeTopPx, topAnchor),
                0, Math.max(bottomPaddingPx, bottomAnchor));
    }

    /** Where the active line rests vertically, as a fraction of the viewport height (0 = top edge,
     *  0.5 = legacy center, 1 = bottom edge). See {@link Settings#LYRICS_FOCUS_POSITION}. */
    public void setAnchorFraction(float fraction) {
        anchorFraction = fraction;
    }

    private int anchorCenterY(int scrollY, int viewportHeight, int paddingTop) {
        if (anchorFraction == CENTER_ANCHOR_FRACTION) return contentCenterY(scrollY, viewportHeight, paddingTop);
        return scrollY + Math.round(Math.max(1, viewportHeight) * anchorFraction) - Math.max(0, paddingTop);
    }

    public int viewportAnchor(int[] rowHeightPrefix, int lineCount) {
        if (scrollView == null || topStaticSpacer == null || lineCount <= 0) return 0;
        int center = anchorCenterY(scrollView.getScrollY(), scrollView.getHeight(), scrollView.getPaddingTop());
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

    /**
     * {@code previousActiveIndex} carries two different negative sentinels from
     * {@code LyricsFollowState}: -2 means no line has ever been active (fresh document/track/
     * seek - nothing sensible to animate from, so snap), while -1 means the previous line's
     * window just ended and the next one hasn't started yet (an ordinary in-song gap - the view
     * is already sitting exactly where the last line put it, so this should animate through like
     * any other line change, not snap as if starting over).
     */
    public static boolean shouldScrollInstantly(boolean requested, int previousActiveIndex) {
        return requested || previousActiveIndex < -1;
    }

    public int contentYForTouch(float yInScroll) {
        if (scrollView == null) return Math.round(yInScroll);
        return scrollView.getScrollY() + Math.round(yInScroll) - scrollView.getPaddingTop();
    }

    /** @param rowHalfPx half the height of a single (unwrapped) lyric line, in px - used as the
     *  offset back from the row's top instead of half the row's OWN height, so a long line that
     *  wraps to two or three visual lines still anchors by its first line's position. Using the
     *  full row height there would center the whole wrapped block instead, sitting the first
     *  (the actually-read) line noticeably off the anchor point for anything but a single-line
     *  row. */
    public int centeredScrollTarget(View row, int rowHalfPx) {
        if (scrollView == null || contentColumn == null || row == null) return 0;
        workRect.set(0, 0, row.getWidth(), row.getHeight());
        contentColumn.offsetDescendantRectToMyCoords(row, workRect);
        int lineOffset = anchorLineOffset(row.getHeight(), rowHalfPx, anchorFraction);
        if (anchorFraction == CENTER_ANCHOR_FRACTION) {
            return scrollView.getPaddingTop() + workRect.top - (scrollView.getHeight() / 2) + lineOffset;
        }
        return scrollView.getPaddingTop() + workRect.top
                - Math.round(scrollView.getHeight() * anchorFraction) + lineOffset;
    }

    /**
     * Which line of a row sits on the focus point, as an offset from the row's top. Up to the
     * middle of the screen it is the first line, so reading starts where the eye already is. Below
     * the middle it moves to the last line: anchoring a wrapped lyric by its first line near the
     * bottom pushed its remaining lines off the screen. The switch is blended over a short band
     * past the middle so a focus point dragged across it glides rather than jumps.
     */
    static int anchorLineOffset(int rowHeightPx, int rowHalfPx, float anchorFraction) {
        float lower = Math.max(0f, Math.min(1f, (anchorFraction - CENTER_ANCHOR_FRACTION) / 0.15f));
        int extra = Math.max(0, rowHeightPx - 2 * rowHalfPx);
        return rowHalfPx + Math.round(extra * lower);
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
