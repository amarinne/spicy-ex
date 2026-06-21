package com.eza.spicyex.lyrics;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owns mounted lyric row window state and host attach/detach ordering. */
public final class LyricsMountedRowWindow {
    private final LinkedHashSet<Integer> mountedIndices = new LinkedHashSet<>();
    private int renderedWindowStart = 0;
    private int renderedWindowEnd = -1;
    private boolean dirty;

    public Set<Integer> mountedIndices() {
        return Collections.unmodifiableSet(mountedIndices);
    }

    public int start() {
        return renderedWindowStart;
    }

    public int end() {
        return renderedWindowEnd;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean containsIndex(int index) {
        return index >= renderedWindowStart && index <= renderedWindowEnd;
    }

    public boolean matches(BoundedLyricWindow.Range range) {
        return range != null && range.start == renderedWindowStart && range.end == renderedWindowEnd && !dirty;
    }

    public void reset(LinearLayout host) {
        renderedWindowStart = 0;
        renderedWindowEnd = -1;
        dirty = true;
        mountedIndices.clear();
        if (host != null) host.removeAllViews();
    }

    public void render(
            BoundedLyricWindow.Range range,
            List<AppliedLine> lines,
            LinearLayout host,
            int activeIndex,
            RowAccess rowAccess,
            LineCallback mountedCallback,
            LineCallback unmountedCallback,
            LineStyleCallback styleCallback
    ) {
        if (range == null || lines == null || host == null) return;
        renderedWindowStart = range.start;
        renderedWindowEnd = range.end;

        ArrayList<Integer> stale = new ArrayList<>();
        for (int index : mountedIndices) {
            if (!range.contains(index)) stale.add(index);
        }
        for (int index : stale) {
            if (index < 0 || index >= lines.size()) continue;
            AppliedLine line = lines.get(index);
            View row = rowAccess == null ? null : rowAccess.attachedRowFor(line);
            if (row != null) {
                host.removeView(row);
                if (unmountedCallback != null) unmountedCallback.apply(line);
            }
        }

        for (int childIndex = 0, lineIndex = range.start; lineIndex <= range.end; lineIndex++, childIndex++) {
            if (lineIndex < 0 || lineIndex >= lines.size()) continue;
            AppliedLine line = lines.get(lineIndex);
            if (line == null) continue;
            View row = rowAccess == null ? null : rowAccess.rowFor(line);
            if (row == null) continue;
            if (row.getParent() != host) {
                if (row.getParent() instanceof ViewGroup) {
                    ((ViewGroup) row.getParent()).removeView(row);
                }
                host.addView(row, childIndex, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                if (mountedCallback != null) mountedCallback.apply(line);
            } else {
                int currentIndex = host.indexOfChild(row);
                if (currentIndex != childIndex) {
                    host.removeViewAt(currentIndex);
                    host.addView(row, childIndex);
                }
            }
            if (styleCallback != null) styleCallback.apply(lineIndex, lineIndex == activeIndex);
        }

        mountedIndices.clear();
        for (int lineIndex = range.start; lineIndex <= range.end; lineIndex++) {
            if (lineIndex < 0 || lineIndex >= lines.size()) continue;
            AppliedLine line = lines.get(lineIndex);
            if (rowAccess != null && rowAccess.attachedRowFor(line) != null) {
                mountedIndices.add(lineIndex);
            }
        }
        dirty = false;
    }

    public interface RowAccess {
        View rowFor(AppliedLine line);

        View attachedRowFor(AppliedLine line);
    }

    public interface RowProvider {
        View rowFor(AppliedLine line);
    }

    public interface LineCallback {
        void apply(AppliedLine line);
    }

    public interface LineStyleCallback {
        void apply(int index, boolean active);
    }
}
