package com.eza.spicyex.settings.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chip row's wrapping plan: which chips share a line, so that no option loses the words
 * that tell two stored values apart.
 *
 * <p>Pure: the labels in, the plan out. The editor's options panel has a fixed width, so the
 * plan can be computed before the panel is laid out, and the rule can be checked on the JVM.
 *
 * <p>A line of n chips gives every chip 1/n of the row's width. A label that does not fit its
 * share moves to a line of its own, and a label that does not fit even a whole line wraps
 * inside its chip instead of being cut. The row never shows an ellipsis.
 */
final class ChipRowPlan {
    /**
     * The width of the chip row itself, in dp. The editor's options panel is at most 360dp
     * wide; the list and the group card take 14dp on each side of the row.
     */
    private static final int ROW_WIDTH_DP = 304;
    /** A chip's own horizontal padding. */
    private static final int CHIP_PADDING_DP = 12;
    /** The margin between two chips on the same line. */
    private static final int CHIP_MARGIN_DP = 6;
    /** The width of one label character at the chip's 15sp text size. */
    private static final float CHAR_WIDTH_DP = 7.5f;

    private ChipRowPlan() { }

    /**
     * The lines of the row, each listing the indices of the chips on it, in order.
     *
     * <p>A row of up to three chips is one line of equal columns; a longer row is two chips per
     * line, as it has always been. A line then takes fewer chips when a label does not fit the
     * share it would get there.
     */
    static List<List<Integer>> plan(List<String> labels) {
        List<List<Integer>> lines = new ArrayList<>();
        int cap = labels.size() <= 3 ? labels.size() : 2;
        int index = 0;
        while (index < labels.size()) {
            int count = Math.min(cap, labels.size() - index);
            while (count > 1 && !fits(labels, index, count)) count--;
            List<Integer> line = new ArrayList<>();
            for (int i = 0; i < count; i++) line.add(index + i);
            lines.add(Collections.unmodifiableList(line));
            index += count;
        }
        return Collections.unmodifiableList(lines);
    }

    /** True when a label still fits the share of the row it gets on a line of that many chips. */
    static boolean holds(String label, int chipsOnLine) {
        if (label == null || label.isEmpty()) return true;
        return label.length() <= capacity(chipsOnLine);
    }

    /** The label characters that fit one chip on a line of that many chips. */
    static int capacity(int chipsOnLine) {
        if (chipsOnLine < 1) return 0;
        float textWidthDp = (ROW_WIDTH_DP - CHIP_MARGIN_DP * chipsOnLine) / (float) chipsOnLine
                - CHIP_PADDING_DP;
        return (int) Math.floor(textWidthDp / CHAR_WIDTH_DP);
    }

    private static boolean fits(List<String> labels, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (!holds(labels.get(i), count)) return false;
        }
        return true;
    }
}
