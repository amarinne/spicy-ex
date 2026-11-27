package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.DisplayLayoutGroup;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.util.List;
import java.util.Objects;

/**
 * Pure policy for reusing a mounted Android Auto lyric row when the same line repeats consecutively.
 * Only timing metadata changes on the mounted {@link AppliedLine} and its {@link SyllableSegment}s,
 * preserving every view binding so the display reanimates gradient progress without rebuilding or
 * crossfading the row.
 */
final class AutoRepeatedLine {
    private AutoRepeatedLine() { }

    /**
     * True when {@code previous} and {@code next} build the same rendered view tree, layout groups,
     * and visible secondary text, so reusing the mounted row changes only timing and gradient fill.
     */
    static boolean sameRenderedLine(AppliedLine previous, AppliedLine next,
                                    boolean checkReading, boolean checkTranslation) {
        if (previous == null || next == null) return false;
        if (!Objects.equals(safe(previous.text), safe(next.text))) return false;
        if (checkReading && !Objects.equals(safe(previous.romanizedText), safe(next.romanizedText)))
            return false;
        if (checkTranslation && !Objects.equals(safe(previous.translatedText), safe(next.translatedText)))
            return false;
        boolean prevLineLevel = isLineLevel(previous);
        boolean nextLineLevel = isLineLevel(next);
        if (prevLineLevel != nextLineLevel) return false;
        if (!sameLayoutGroups(previous.displayLayoutGroups, next.displayLayoutGroups)) return false;
        if (prevLineLevel) return true;
        if (previous.words.size() != next.words.size()) return false;
        for (int i = 0; i < previous.words.size(); i++) {
            SyllableSegment a = previous.words.get(i);
            SyllableSegment b = next.words.get(i);
            if (a == null || b == null) return false;
            if (!Objects.equals(safe(a.text), safe(b.text))) return false;
            if (a.canonicalStartCp != b.canonicalStartCp
                    || a.canonicalEndCp != b.canonicalEndCp
                    || a.boundaryAfter != b.boundaryAfter) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rebinds timing fields onto an already-mounted slice in place without replacing {@code mounted}
     * or any of its {@link SyllableSegment} instances. Returns false without mutating if the slice's
     * word structure does not match {@code nextSource}.
     */
    static boolean rebindTiming(AppliedLine mounted, AppliedLine nextSource, int pageStart, int pageEnd) {
        if (mounted == null || nextSource == null) return false;
        boolean mountedLineLevel = isLineLevel(mounted);
        boolean nextLineLevel = isLineLevel(nextSource);
        if (mountedLineLevel != nextLineLevel) return false;
        if (pageStart < 0 || pageEnd < pageStart || pageEnd > safe(nextSource.text).length()) return false;

        if (!mountedLineLevel) {
            int expected = countSlicedWords(nextSource, pageStart, pageEnd);
            if (expected != mounted.words.size()) return false;
            int index = 0;
            for (SyllableSegment original : nextSource.words) {
                int from = nextSource.text.offsetByCodePoints(0, original.canonicalStartCp);
                int to = nextSource.text.offsetByCodePoints(0, original.canonicalEndCp);
                from = Math.max(from, pageStart);
                to = Math.min(to, pageEnd);
                if (from >= to) continue;
                SyllableSegment target = mounted.words.get(index++);
                target.startMs = original.startMs;
                target.endMs = original.endMs;
                target.totalMs = original.totalMs;
            }
        }

        mounted.startMs = nextSource.startMs;
        mounted.endMs = nextSource.endMs;
        mounted.totalMs = nextSource.totalMs;
        mounted.gradientSource = nextSource.gradientSource == null ? nextSource : nextSource.gradientSource;
        return true;
    }

    private static int countSlicedWords(AppliedLine source, int pageStart, int pageEnd) {
        int count = 0;
        for (SyllableSegment original : source.words) {
            if (original == null) return -1;
            int from = source.text.offsetByCodePoints(0, original.canonicalStartCp);
            int to = source.text.offsetByCodePoints(0, original.canonicalEndCp);
            from = Math.max(from, pageStart);
            to = Math.min(to, pageEnd);
            if (from < to) count++;
        }
        return count;
    }

    private static boolean isLineLevel(AppliedLine line) {
        return line.syntheticWords || line.words == null || line.words.isEmpty();
    }

    private static boolean sameLayoutGroups(List<DisplayLayoutGroup> a, List<DisplayLayoutGroup> b) {
        int sizeA = a == null ? 0 : a.size();
        int sizeB = b == null ? 0 : b.size();
        if (sizeA != sizeB) return false;
        for (int i = 0; i < sizeA; i++) {
            DisplayLayoutGroup ga = a.get(i);
            DisplayLayoutGroup gb = b.get(i);
            if (ga == null || gb == null) return false;
            if (ga.start != gb.start || ga.end != gb.end || ga.keepTogether != gb.keepTogether)
                return false;
        }
        return true;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
