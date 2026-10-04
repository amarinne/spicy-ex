package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoSurfaceFitTest {
    @Test public void secondaryRowsBelongOnlyToFullPlayer() {
        assertTrue(AutoSurfaceFit.secondaryAllowed("player-full"));
        assertFalse(AutoSurfaceFit.secondaryAllowed("player"));
        assertFalse(AutoSurfaceFit.secondaryAllowed("weather"));
        assertFalse(AutoSurfaceFit.secondaryAllowed(null));
    }

    @Test public void dotsFollowThreeCalculatedStepsAndStayLitInsteadOfRepeating() {
        AppliedLine gap = com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(1000, 10550, false);
        assertEquals(4000, gap.words.get(0).endMs);
        assertEquals(7000, gap.words.get(1).endMs);
        assertEquals(10000, gap.words.get(2).endMs);
        assertEquals(.35f, AutoSurfaceFit.dotAlpha(gap, 1000, 0), .001f);
        assertEquals(1f, AutoSurfaceFit.dotAlpha(gap, 4000, 0), .001f);
        assertEquals(.35f, AutoSurfaceFit.dotAlpha(gap, 4000, 1), .001f);
        assertEquals(1f, AutoSurfaceFit.dotAlpha(gap, 7000, 1), .001f);
        assertEquals(.35f, AutoSurfaceFit.dotAlpha(gap, 7000, 2), .001f);
        assertEquals(1f, AutoSurfaceFit.dotAlpha(gap, 10000, 2), .001f);
        for (long pos = 4000; pos <= 10000; pos += 30)
            assertEquals(1f, AutoSurfaceFit.dotAlpha(gap, pos, 0), .001f);
        assertEquals(.5f, AutoSurfaceFit.dotAlpha(gap, 10300, 2), .001f);
        assertEquals(0f, AutoSurfaceFit.dotAlpha(gap, 10550, 2), .001f);
    }
    @Test public void dotSpeedChangesWithGapLengthAndPlayheadNotWallTime() {
        AppliedLine shortGap = com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(0, 6550, false);
        AppliedLine longGap = com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(0, 18550, false);
        assertTrue(AutoSurfaceFit.dotAlpha(shortGap, 1000, 0) > AutoSurfaceFit.dotAlpha(longGap, 1000, 0));
        AppliedLine shifted = com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(50000, 56550, false);
        assertEquals(AutoSurfaceFit.dotAlpha(shortGap, 1000, 0), AutoSurfaceFit.dotAlpha(shifted, 51000, 0), .001f);
    }
    @Test public void readableSentenceCapTracksContentWidthAndFontScale() {
        assertEquals(56, AutoSurfaceFit.comfortableMaximum(378, 1f));
        assertEquals(56, AutoSurfaceFit.comfortableMaximum(384, 1f));
        assertEquals(80, AutoSurfaceFit.comfortableMaximum(706, 1f));
        assertEquals(56, AutoSurfaceFit.comfortableMaximum(756, 2f));
        assertEquals(56, AutoSurfaceFit.comfortableMaximum(100, 1f));
        assertEquals(80, AutoSurfaceFit.comfortableMaximum(2000, 1f));
    }
    @Test public void selectsLargestFitIncludingSecondaryReserve() {
        assertEquals(70, AutoSurfaceFit.largestSize(24, 128, size -> size * 2 + 60 <= 200));
        assertEquals(128, AutoSurfaceFit.largestSize(24, 128, size -> true));
        assertEquals(-1, AutoSurfaceFit.largestSize(24, 128, size -> false));
        assertTrue(AutoSurfaceFit.pages("text", (start, end) -> false).isEmpty());
    }
    @Test public void pagesCoverTextWithoutSplittingSurrogateOrCombiningMark() {
        String text = "alpha 🎵 e\u0301 beta 界かな";
        List<int[]> pages = AutoSurfaceFit.pages(text, (start, end) -> end - start <= 7);
        int next = 0;
        for (int[] page : pages) {
            assertEquals(next, page[0]); assertTrue(page[1] > page[0]);
            if (page[1] < text.length()) {
                assertFalse(Character.isLowSurrogate(text.charAt(page[1])));
                assertNotEquals(Character.NON_SPACING_MARK, Character.getType(text.charAt(page[1])));
            }
            next = page[1];
        }
        assertEquals(text.length(), next);
    }
    @Test public void japaneseVerbStaysWholeAcrossPagesAndSlices() {
        AppliedLine line = new AppliedLine(); line.text = "曖昧な心を 解かして繋いだ";
        line.displayLayoutGroups = java.util.Arrays.asList(
                new com.eza.spicyex.lyrics.DisplayLayoutGroup(0, 3, "ja", true, 1),
                new com.eza.spicyex.lyrics.DisplayLayoutGroup(3, 5, "ja", true, 1),
                new com.eza.spicyex.lyrics.DisplayLayoutGroup(6, 10, "ja", true, 1),
                new com.eza.spicyex.lyrics.DisplayLayoutGroup(10, 13, "ja", true, 1));
        List<int[]> pages = AutoSurfaceFit.pages(line.text, line.displayLayoutGroups,
                (start, end) -> end - start <= 11);
        assertEquals(10, pages.get(0)[1]);
        AppliedLine last = AutoLyricsView.slice(line, 10, 13);
        assertEquals("繋いだ", last.text);
        assertEquals(0, last.displayLayoutGroups.get(0).start);
        assertEquals(3, last.displayLayoutGroups.get(0).end);
        assertSame(last.displayLayoutGroups, com.eza.spicyex.lyrics.DisplayLayoutGroup.forLine(last));
        String before = AutoLyricsView.contentSignature(last);
        last.displayLayoutGroups = java.util.Collections.emptyList();
        assertNotEquals(before, AutoLyricsView.contentSignature(last));
    }
    @Test public void oversizedJapaneseGroupCanStillPageWithoutLosingText() {
        String text = "繋いだ";
        List<int[]> pages = AutoSurfaceFit.pages(text, java.util.Collections.singletonList(
                new com.eza.spicyex.lyrics.DisplayLayoutGroup(0, 3, "ja", true, 1)),
                (start, end) -> end - start <= 2);
        assertEquals(2, pages.size());
        assertEquals(0, pages.get(0)[0]); assertEquals(3, pages.get(1)[1]);
    }
    @Test public void bridgeRejectsOverlappingOutOfRangeAndSurrogateSplitGroups() {
        AutoSurfaceFit.validateGroups("🎵繋いだ", new int[]{0, 2, 2, 5});
        for (int[] invalid : new int[][]{{0}, {0, 3, 2, 5}, {0, 6}, {0, 1}, {-1, 2}}) {
            try { AutoSurfaceFit.validateGroups("🎵繋いだ", invalid); fail("accepted invalid groups"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void seekChoosesPageFromCurrentPositionNotPreviousPage() {
        AppliedLine line = new AppliedLine(); line.text = "🎵 hello world";
        SyllableSegment first = word(0, 1, 100, 200), second = word(2, 7, 200, 400);
        line.words.add(first); line.words.add(second);
        List<int[]> pages = AutoSurfaceFit.pages(line.text, (start, end) -> end - start <= 5);
        int forward = AutoSurfaceFit.pageAt(pages, AutoLyricsView.sourceOffset(line, 300));
        int backward = AutoSurfaceFit.pageAt(pages, AutoLyricsView.sourceOffset(line, 120));
        assertTrue(forward > backward);
        assertEquals(0, backward);
        assertEquals(5, AutoLyricsView.sourceOffset(line, 300));
    }
    @Test public void slicedWordKeepsTimingAndUsesCodePointOffsets() {
        AppliedLine line = new AppliedLine(); line.text = "🎵 hello";
        line.words.add(word(2, 7, 1200, 1800));
        AppliedLine sliced = AutoLyricsView.slice(line, 3, line.text.length());
        assertEquals("hello", sliced.text); assertEquals(1, sliced.words.size());
        SyllableSegment copied = sliced.words.get(0);
        assertEquals(1200, copied.startMs); assertEquals(1800, copied.endMs);
        assertEquals(0, copied.canonicalStartCp); assertEquals(5, copied.canonicalEndCp);
        assertEquals(2, line.words.get(0).canonicalStartCp);
    }
    @Test public void oversizedTimedTokenVisitsEveryPageAndRetainsAuthoredTiming() {
        AppliedLine line = new AppliedLine(); line.text = "abcdefgh";
        line.words.add(word(0, 8, 100, 900));
        List<int[]> pages = AutoSurfaceFit.pages(line.text, (start, end) -> end - start <= 2);
        assertEquals(4, pages.size());
        for (int i = 0; i < pages.size(); i++) {
            assertEquals(i, AutoSurfaceFit.pageAt(pages, AutoLyricsView.sourceOffset(line, 150 + i * 200)));
            AppliedLine part = AutoLyricsView.slice(line, pages.get(i)[0], pages.get(i)[1]);
            assertEquals(1, part.words.size());
            assertEquals(100, part.words.get(0).startMs); assertEquals(900, part.words.get(0).endMs);
            assertEquals(0, part.words.get(0).canonicalStartCp); assertEquals(2, part.words.get(0).canonicalEndCp);
            assertEquals(part.text, part.words.get(0).text);
        }
        assertEquals(0, AutoLyricsView.sourceOffset(new AppliedLine(), 100));
    }
    @Test public void timingCorrectionChangesContentSignature() {
        AppliedLine line = new AppliedLine(); line.text = "same"; line.words.add(word(0, 4, 100, 900));
        String before = AutoLyricsView.contentSignature(line);
        line.words.get(0).endMs = 1000;
        assertNotEquals(before, AutoLyricsView.contentSignature(line));
        before = AutoLyricsView.contentSignature(line); line.endMs = 2000;
        assertNotEquals(before, AutoLyricsView.contentSignature(line));
        before = AutoLyricsView.contentSignature(line); line.syntheticWords = true;
        assertNotEquals(before, AutoLyricsView.contentSignature(line));
    }
    @Test public void projectedPresetSuppressesMotionWithoutChangingOtherSurfaces() throws Exception {
        java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(
                com.eza.spicyex.lyrics.LyricsRenderConfig.class.getDeclaredConstructors())
                .max(java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).get();
        constructor.setAccessible(true);
        Class<?>[] types = constructor.getParameterTypes(); Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) values[i] = true;
            else if (types[i] == int.class) values[i] = 0;
            else if (types[i] == float.class) values[i] = 1f;
            else values[i] = "Word lift";
        }
        com.eza.spicyex.lyrics.LyricsRenderConfig original =
                (com.eza.spicyex.lyrics.LyricsRenderConfig) constructor.newInstance(values);
        com.eza.spicyex.lyrics.LyricsRenderConfig auto = original.forAndroidAuto();
        assertFalse(auto.wordBounceEnabled); assertFalse(auto.appleLift); assertFalse(auto.appleStyle);
        assertFalse(auto.glowBlurEnabled); assertFalse(auto.lineBlurEnabled);
        assertTrue(auto.lineGradientEnabled); assertFalse(auto.lineSyncFillTopDown());
        assertTrue(auto.lineSyncFillSentence());
        assertTrue(original.wordBounceEnabled); assertTrue(original.forLiveCard().wordBounceEnabled);
    }
    private static SyllableSegment word(int start, int end, long from, long to) {
        SyllableSegment word = new SyllableSegment(); word.canonicalStartCp = start; word.canonicalEndCp = end;
        word.startMs = from; word.endMs = to; return word;
    }
}
