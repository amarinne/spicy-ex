package com.eza.spicyex.lyrics;

import android.view.View;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Set;

/** Coordinates the bounded mounted lyric row window and virtual spacer heights. */
public final class LyricsRowMountController {
    private final LinearLayout mountedRowsHost;
    private final LyricsSpaceView topVirtualSpacer;
    private final LyricsSpaceView bottomVirtualSpacer;
    private final LyricsMountedRowWindow mountedRows = new LyricsMountedRowWindow();
    private final BoundedLyricWindow lyricWindow;
    private final int fullRenderThreshold;
    private final int edgeBuffer;
    private int[] rowHeightPrefix;

    public LyricsRowMountController(
            LinearLayout mountedRowsHost,
            LyricsSpaceView topVirtualSpacer,
            LyricsSpaceView bottomVirtualSpacer,
            int fullRenderThreshold,
            int beforeActive,
            int afterActive,
            int edgeBuffer
    ) {
        this.mountedRowsHost = mountedRowsHost;
        this.topVirtualSpacer = topVirtualSpacer;
        this.bottomVirtualSpacer = bottomVirtualSpacer;
        this.fullRenderThreshold = fullRenderThreshold;
        this.edgeBuffer = edgeBuffer;
        this.lyricWindow = new BoundedLyricWindow(fullRenderThreshold, beforeActive, afterActive);
    }

    public Set<Integer> mountedIndices() {
        return mountedRows.mountedIndices();
    }

    public boolean containsIndex(int index) {
        return mountedRows.containsIndex(index);
    }

    public View rowViewOrBuild(AppliedLine line, LyricsMountedRowWindow.RowProvider rowProvider) {
        if (line == null) return null;
        View row = LyricsLineViewState.rowView(line);
        if (row != null) return row;
        return rowProvider == null ? null : rowProvider.rowFor(line);
    }

    public View attachedRowView(AppliedLine line) {
        return LyricsLineViewState.attachedRowView(line, mountedRowsHost);
    }

    public void markDirty() {
        mountedRows.markDirty();
    }

    public void reset() {
        invalidateRowHeightPrefix();
        mountedRows.reset(mountedRowsHost);
        clearSpacerHeights();
    }

    public boolean renderWindow(
            List<AppliedLine> lines,
            int anchor,
            int activeIndex,
            LyricsMountedRowWindow.RowProvider rowProvider,
            LyricsMountedRowWindow.LineCallback mountedCallback,
            LyricsMountedRowWindow.LineCallback unmountedCallback,
            LyricsMountedRowWindow.LineStyleCallback styleCallback,
            LyricsRowVirtualizer.RowHeightEstimator rowHeightEstimator
    ) {
        if (lines == null || lines.isEmpty()) return false;
        BoundedLyricWindow.Range range = lyricWindow.rangeFor(lines.size(), anchor);
        if (mountedRows.matches(range)) {
            updateSpacerHeights(lines, rowHeightEstimator);
            return false;
        }
        mountedRows.render(
                range,
                lines,
                mountedRowsHost,
                activeIndex,
                new LyricsMountedRowWindow.RowAccess() {
                    @Override
                    public View rowFor(AppliedLine line) {
                        return rowViewOrBuild(line, rowProvider);
                    }

                    @Override
                    public View attachedRowFor(AppliedLine line) {
                        return attachedRowView(line);
                    }
                },
                mountedCallback,
                unmountedCallback,
                styleCallback);
        updateSpacerHeights(lines, rowHeightEstimator);
        return true;
    }

    public int[] rowHeightPrefix(List<AppliedLine> lines, LyricsRowVirtualizer.RowHeightEstimator estimator) {
        if (rowHeightPrefix != null) return rowHeightPrefix;
        rowHeightPrefix = LyricsRowVirtualizer.buildRowHeightPrefix(lines, estimator);
        return rowHeightPrefix;
    }

    public int rowHeightForIndex(
            List<AppliedLine> lines,
            int index,
            int estimatedBaseHeightPx,
            int secondaryExtraHeightPx,
            boolean showRomanization,
            boolean showTranslation
    ) {
        if (lines == null || index < 0 || index >= lines.size()) return 0;
        AppliedLine line = lines.get(index);
        if (line == null) return 0;
        int measuredHeightPx = LyricsLineViewState.measuredHeightPx(line);
        if (measuredHeightPx > 0) return measuredHeightPx;
        int estimate = estimatedBaseHeightPx;
        if (!line.bgLine && showRomanization && !isBlank(line.romanizedText)) estimate += secondaryExtraHeightPx;
        if (!line.bgLine && showTranslation && !isBlank(line.translatedText)) estimate += secondaryExtraHeightPx;
        return estimate;
    }

    public void invalidateRowHeightPrefix() {
        rowHeightPrefix = null;
    }

    public void updateSpacerHeights(List<AppliedLine> lines, LyricsRowVirtualizer.RowHeightEstimator estimator) {
        if (lines == null || lines.isEmpty()) {
            clearSpacerHeights();
            return;
        }
        int count = lines.size();
        LyricsRowVirtualizer.SpacerHeights heights = LyricsRowVirtualizer.spacerHeights(
                rowHeightPrefix(lines, estimator), count, mountedRows.start(), mountedRows.end());
        topVirtualSpacer.setHeightPx(heights.top);
        bottomVirtualSpacer.setHeightPx(heights.bottom);
    }

    public boolean remeasureMountedRows(List<AppliedLine> lines, LineRemeasurer remeasurer) {
        if (lines == null || lines.isEmpty() || remeasurer == null) return false;
        boolean changed = false;
        for (int index : mountedRows.mountedIndices()) {
            if (index < 0 || index >= lines.size()) continue;
            if (remeasurer.remeasure(lines.get(index))) changed = true;
        }
        return changed;
    }

    public boolean remeasureLine(AppliedLine line) {
        View row = LyricsLineViewState.rowView(line);
        if (row == null || !row.isAttachedToWindow()) return false;
        int height = row.getHeight();
        if (height <= 0 || Math.abs(LyricsLineViewState.measuredHeightPx(line) - height) < 1) return false;
        LyricsLineViewState.updateMeasuredHeight(line, height);
        invalidateRowHeightPrefix();
        return true;
    }

    public boolean shouldRemountWindowForViewport(List<AppliedLine> lines, int anchor) {
        int count = lines == null ? 0 : lines.size();
        return LyricsRowVirtualizer.shouldRemountWindowForViewport(
                count,
                mountedRows.dirty(),
                mountedRows.start(),
                mountedRows.end(),
                fullRenderThreshold,
                edgeBuffer,
                anchor);
    }

    private void clearSpacerHeights() {
        topVirtualSpacer.setHeightPx(0);
        bottomVirtualSpacer.setHeightPx(0);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public interface LineRemeasurer {
        boolean remeasure(AppliedLine line);
    }
}
