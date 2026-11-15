package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.DisplayLayoutGroup;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

/** Surface fitting only. Lyric timing and text painting remain in the shared renderer. */
final class AutoSurfaceFit {
    static final int MIN_SP = 24, MAX_SP = 80;
    private AutoSurfaceFit() { }

    static boolean secondaryAllowed(String target) { return "player-full".equals(target); }

    /** Duet scenes need room for two sung lines; the weather card stays single-line by design. */
    static boolean duetAllowed(String target) { return "player".equals(target) || "player-full".equals(target); }

    /**
     * A duet caps each section's extra rows at one line so the pair fits without shrinking the
     * lyrics; a solo line keeps its legacy two-line secondaries.
     */
    static int secondaryLineCap(boolean duet) { return duet ? 1 : 2; }

    static float dotAlpha(com.eza.spicyex.lyrics.AppliedLine timing, long positionMs, int dot) {
        if (timing == null || dot < 0 || dot >= timing.words.size()) return .35f;
        com.eza.spicyex.lyrics.SyllableSegment step = timing.words.get(dot);
        float progress = positionMs < step.startMs ? 0f : positionMs >= step.endMs ? 1f
                : (positionMs - step.startMs) / (float) Math.max(1, step.endMs - step.startMs);
        float remaining = (timing.endMs - positionMs)
                / (float) com.eza.spicyex.lyrics.LyricTimeline.PRE_HIDDEN_DOT_LINE_MS;
        return com.eza.spicyex.lyrics.LyricAnimations.dotOpacitySpline(progress)
                * Math.max(0f, Math.min(1f, remaining));
    }

    /** Wide full-player text is twice the native 40sp header. Compact player text uses a 56sp cap. */
    static int comfortableMaximum(int widthPx, float scaledDensity) {
        return widthPx / Math.max(1f, scaledDensity) >= 600 ? MAX_SP : 56;
    }

    static int largestSize(int minimum, int maximum, IntPredicate fits) {
        if (!fits.test(minimum)) return -1;
        int low = minimum, high = maximum;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (fits.test(middle)) low = middle; else high = middle - 1;
        }
        return low;
    }

    /** Prefer language-aware line breaks. An oversized token uses character boundaries. */
    static List<int[]> pages(String text, BiPredicate<Integer, Integer> fits) {
        return pages(text, null, fits);
    }

    static void validateGroups(String text, int[] ranges) {
        if (ranges == null) return;
        if (ranges.length > 1024 || ranges.length % 2 != 0) throw new IllegalArgumentException("Invalid layout groups");
        int previous = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            int start = ranges[i], end = ranges[i + 1];
            if (start < previous || end <= start || end > text.length()
                    || start > 0 && Character.isLowSurrogate(text.charAt(start))
                    || end < text.length() && Character.isLowSurrogate(text.charAt(end)))
                throw new IllegalArgumentException("Invalid layout group range");
            previous = end;
        }
    }

    static List<int[]> pages(String text, List<DisplayLayoutGroup> groups,
                             BiPredicate<Integer, Integer> fits) {
        List<int[]> result = new ArrayList<>();
        BreakIterator lines = BreakIterator.getLineInstance(Locale.ROOT);
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        lines.setText(text); characters.setText(text);
        List<Integer> boundaries = new ArrayList<>();
        for (int next = lines.first(); next != BreakIterator.DONE; next = lines.next()) boundaries.add(next);
        int start = 0;
        while (start < text.length()) {
            final int from = start;
            List<Integer> available = new ArrayList<>();
            for (int boundary : boundaries) if (boundary > start && safeBoundary(boundary, groups)) available.add(boundary);
            int count = largestSize(0, available.size(), n -> n == 0 || fits.test(from, available.get(n - 1)));
            int end;
            if (count == 0) {
                List<Integer> graphemes = new ArrayList<>();
                for (int next = characters.following(start); next != BreakIterator.DONE; next = characters.next()) graphemes.add(next);
                int fitted = largestSize(0, graphemes.size(), n -> n == 0 || fits.test(from, graphemes.get(n - 1)));
                if (fitted == 0) return new ArrayList<>();
                end = graphemes.get(fitted - 1);
            } else {
                int chosen = count - 1;
                // A page break that leaves one word alone on the next page reads as a
                // stray fragment. Step back to the largest break that keeps a real word
                // pair on the following page, as long as this page still holds two words.
                while (chosen > 0 && isolatedWord(text, available.get(chosen))
                        && wordCount(text, from, available.get(chosen - 1)) > 1) chosen--;
                end = available.get(chosen);
            }
            result.add(new int[]{start, end}); start = end;
        }
        return result;
    }

    /** True when the text after {@code end} is one unspaced word, so the next page is a stray fragment. */
    private static boolean isolatedWord(String text, int end) {
        int from = end;
        while (from < text.length() && space(text.charAt(from))) from++;
        if (from >= text.length()) return false;
        boolean word = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (space(c) || unspacedScript(c)) return false;
            if (Character.isLetterOrDigit(c)) word = true;
        }
        return word;
    }

    /** Space-delimited words in one candidate page; unspaced scripts stay on the raw boundary. */
    private static int wordCount(String text, int start, int end) {
        int words = 0;
        boolean inside = false;
        for (int i = start; i < end; i++) {
            boolean boundary = space(text.charAt(i)) || unspacedScript(text.charAt(i));
            if (!boundary && !inside) words++;
            inside = !boundary;
        }
        return words;
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Scripts that do not separate words with spaces, where an orphan-word rule would misfire. */
    private static boolean unspacedScript(char c) {
        return c >= 0x0E00 && c <= 0x0EFF || c >= 0x1000 && c <= 0x109F
                || c >= 0x1780 && c <= 0x17FF || c >= 0x2E80 && c <= 0x9FFF
                || c >= 0xA960 && c <= 0xA97F || c >= 0xAC00 && c <= 0xD7FF
                || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF65 && c <= 0xFF9F;
    }

    private static boolean safeBoundary(int offset, List<DisplayLayoutGroup> groups) {
        if (groups != null) for (DisplayLayoutGroup group : groups)
            if (group.keepTogether && offset > group.start && offset < group.end) return false;
        return true;
    }

    static int pageAt(List<int[]> pages, int sourceOffset) {
        for (int i = 0; i < pages.size(); i++) if (sourceOffset < pages.get(i)[1]) return i;
        return Math.max(0, pages.size() - 1);
    }
}
