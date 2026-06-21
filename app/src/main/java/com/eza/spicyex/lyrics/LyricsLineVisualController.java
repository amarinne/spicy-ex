package com.eza.spicyex.lyrics;

import android.graphics.Color;

import java.util.List;

/** Applies low-frequency row visual resets and invalidates style-cache entries for row remounts. */
public final class LyricsLineVisualController {
    private final FrameStyleBatcher styleBatcher;

    public LyricsLineVisualController(FrameStyleBatcher styleBatcher) {
        this.styleBatcher = styleBatcher;
    }

    public void invalidate(AppliedLine line) {
        if (line == null || styleBatcher == null) return;
        LyricsLineViewState.invalidate(line, styleBatcher);
        if (line.words == null) return;
        for (SyllableSegment seg : line.words) {
            LyricsSyllableViewState.invalidate(seg, styleBatcher);
        }
    }

    public void style(List<AppliedLine> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size() || styleBatcher == null) return;
        AppliedLine line = lines.get(index);
        if (line == null) return;
        int base = LyricsLineViewState.effectiveBaseTextSp(line);
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        LyricsLineViewState.styleMain(line, styleBatcher, base, color);
        if (line.words == null) return;
        for (SyllableSegment seg : line.words) {
            LyricsSyllableViewState.style(seg, styleBatcher, base, color);
        }
    }
}
