package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.auto.AutoDuetLayout.DuetSectionId;
import com.eza.spicyex.auto.AutoDuetLayout.TransitionPasses;
import com.eza.spicyex.lyrics.AppliedLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoDuetLayoutTest {
    @Test public void backgroundOverlapJoinsWithShortSharedWindow() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("lead line", 10000, 14000),
                background("response", 12000, 12500));
        assertEquals(1, AutoDuetLayout.concurrentRowAt(rows, 0, 12200));
    }

    @Test public void leadLeadOverlapNeedsOneSecondSharedWindow() {
        List<AppliedLine> joining = Arrays.asList(
                vocal("first voice", 10000, 14000),
                vocal("second voice", 13000, 17000));
        assertEquals(1, AutoDuetLayout.concurrentRowAt(joining, 0, 13200));
        List<AppliedLine> shortOverlap = Arrays.asList(
                vocal("first voice", 10000, 14000),
                vocal("second voice", 13001, 17000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(shortOverlap, 0, 13200));
    }

    @Test public void sequentialTouchingLinesNeverJoin() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("first line", 10000, 14000),
                vocal("second line", 14000, 18000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 0, 13900));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 1, 14000));
    }

    @Test public void dotRowsNeverJoinAndDotPrimaryStaysSolo() {
        AppliedLine dots = new AppliedLine();
        dots.dotLine = true; dots.text = "• • •"; dots.startMs = 10000; dots.endMs = 15000;
        List<AppliedLine> rows = Arrays.asList(vocal("lead line", 10000, 14000), dots);
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 0, 12000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 1, 12000));
    }

    @Test public void coveringPrefersLatestStartedWithRowOrderTiebreak() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("lead line", 10000, 16000),
                background("early echo", 11000, 15000),
                background("late echo", 12000, 15000));
        assertEquals(2, AutoDuetLayout.concurrentRowAt(rows, 0, 13000));
        List<AppliedLine> tied = Arrays.asList(
                vocal("lead line", 10000, 16000),
                background("first tie", 12000, 15000),
                background("second tie", 12000, 15000));
        assertEquals(1, AutoDuetLayout.concurrentRowAt(tied, 0, 13000));
    }

    @Test public void endedOverlapHeldSoBothFadeOutTogether() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("lead line", 10000, 16000),
                background("early echo", 10500, 11000),
                background("late echo", 11000, 12000));
        assertEquals(2, AutoDuetLayout.concurrentRowAt(rows, 0, 15000));
    }

    @Test public void futureOverlapPrejoinsInsidePrimaryWindow() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("lead line", 10000, 16000),
                background("late echo", 12000, 15000),
                background("later echo", 13000, 15500));
        assertEquals(1, AutoDuetLayout.concurrentRowAt(rows, 0, 11000));
        List<AppliedLine> outside = Arrays.asList(
                vocal("lead line", 10000, 12000),
                background("next echo", 13000, 15000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(outside, 0, 11000));
    }

    @Test public void blankTextAndBadInputStaySolo() {
        List<AppliedLine> rows = Arrays.asList(
                vocal("lead line", 10000, 14000),
                background("   ", 11000, 13000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 0, 12000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(null, 0, 12000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, -1, 12000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(rows, 5, 12000));
        assertEquals(-1, AutoDuetLayout.concurrentRowAt(new ArrayList<AppliedLine>(), 0, 12000));
    }

    @Test public void backgroundPrimaryFindsOverlappingLead() {
        List<AppliedLine> rows = Arrays.asList(
                background("echo under lead", 10000, 14000),
                vocal("lead line", 11000, 15000));
        assertEquals(1, AutoDuetLayout.concurrentRowAt(rows, 0, 12000));
    }

    @Test public void sceneJoinThresholds() {
        assertTrue(AutoDuetLayout.joinsScene(vocal("a", 10000, 14000), background("b", 13999, 15000)));
        assertFalse(AutoDuetLayout.joinsScene(vocal("a", 10000, 14000), vocal("b", 13001, 17000)));
        assertTrue(AutoDuetLayout.joinsScene(vocal("a", 10000, 14000), vocal("b", 13000, 17000)));
        assertFalse(AutoDuetLayout.joinsScene(vocal("a", 10000, 14000), vocal("b", 14000, 18000)));
        assertFalse(AutoDuetLayout.joinsScene(vocal("a", 10000, 11000), vocal("b", 12000, 13000)));
    }

    @Test public void newcomerInheritsVacatedSlotAboveSurvivor() {
        DuetSectionId first = id(10000, 12000, 0);
        DuetSectionId second = id(11000, 15000, 1);
        DuetSectionId third = id(13000, 17000, 2);
        assertEquals(Arrays.asList(third, second),
                AutoDuetLayout.assignDuetSlots(Arrays.asList(second, third), Arrays.asList(first, second)));
    }

    @Test public void fullSwapKeepsCurrentOrderAndSoloPassesThrough() {
        assertEquals(Arrays.asList(id(13000, 17000, 2), id(14000, 18000, 3)),
                AutoDuetLayout.assignDuetSlots(
                        Arrays.asList(id(13000, 17000, 2), id(14000, 18000, 3)),
                        Arrays.asList(id(10000, 12000, 0), id(11000, 15000, 1))));
        assertEquals(Collections.singletonList(id(10000, 12000, 0)),
                AutoDuetLayout.assignDuetSlots(
                        Collections.singletonList(id(10000, 12000, 0)),
                        Arrays.asList(id(10000, 12000, 0), id(11000, 15000, 1))));
        assertTrue(AutoDuetLayout.assignDuetSlots(new ArrayList<DuetSectionId>(), null).isEmpty());
    }

    @Test public void ordinalDistinguishesCoTimedLinesButCorrectionsKeepSlots() {
        assertNotEquals(id(10000, 14000, 0), id(10000, 14000, 1));
        DuetSectionId lead = id(10000, 14000, 0);
        DuetSectionId echo = id(12000, 15000, 1);
        assertEquals(Arrays.asList(lead, echo),
                AutoDuetLayout.assignDuetSlots(Arrays.asList(lead, echo), Collections.singletonList(lead)));
    }

    @Test public void survivorDrawnOnceWhileJoinerAndLeaverFade() {
        DuetSectionId first = id(10000, 12000, 0);
        DuetSectionId second = id(11000, 15000, 1);
        DuetSectionId third = id(13000, 17000, 2);
        TransitionPasses join = AutoDuetLayout.resolveDuetTransitionPasses(
                Collections.singletonList(first), Arrays.asList(first, third), null, null);
        assertEquals(Collections.singleton(0), join.continuingEnterBlocks);
        assertTrue(join.departingExitBlocks.isEmpty());
        assertEquals(Collections.singleton(1), join.arrivingEnterBlocks);
        TransitionPasses leave = AutoDuetLayout.resolveDuetTransitionPasses(
                Arrays.asList(first, second), Collections.singletonList(first), null, null);
        assertEquals(Collections.singleton(0), leave.continuingEnterBlocks);
        assertEquals(Collections.singleton(1), leave.departingExitBlocks);
        assertTrue(leave.arrivingEnterBlocks.isEmpty());
        TransitionPasses swap = AutoDuetLayout.resolveDuetTransitionPasses(
                Collections.singletonList(first), Collections.singletonList(second), null, null);
        assertTrue(swap.continuingEnterBlocks.isEmpty());
        assertEquals(Collections.singleton(0), swap.departingExitBlocks);
        assertEquals(Collections.singleton(0), swap.arrivingEnterBlocks);
    }

    @Test public void wrappedLineCountChangeCrossfadesInsteadOfBlinking() {
        DuetSectionId lead = id(10000, 14000, 0);
        TransitionPasses changed = AutoDuetLayout.resolveDuetTransitionPasses(
                Collections.singletonList(lead), Collections.singletonList(lead),
                Collections.singletonList(2), Collections.singletonList(1));
        assertTrue(changed.continuingEnterBlocks.isEmpty());
        assertEquals(Collections.singleton(0), changed.departingExitBlocks);
        assertEquals(Collections.singleton(0), changed.arrivingEnterBlocks);
        TransitionPasses same = AutoDuetLayout.resolveDuetTransitionPasses(
                Collections.singletonList(lead), Collections.singletonList(lead),
                Collections.singletonList(2), Collections.singletonList(2));
        assertEquals(Collections.singleton(0), same.continuingEnterBlocks);
        assertTrue(same.departingExitBlocks.isEmpty());
        assertTrue(same.arrivingEnterBlocks.isEmpty());
    }

    @Test public void soloScenesNeverDeferAndDuetDefersFutureBlocksOnly() {
        assertTrue(AutoDuetLayout.deferredDuetBlockIndices(Collections.singletonList(5000L), 2000).isEmpty());
        assertEquals(Collections.singleton(1),
                AutoDuetLayout.deferredDuetBlockIndices(Arrays.asList(1000L, 5000L), 2000));
        assertTrue(AutoDuetLayout.deferredDuetBlockIndices(Arrays.asList(1000L, 5000L), 6000).isEmpty());
    }

    @Test public void recenterOnlyWhenDuetEndsAndSlideOnlyForSolo() {
        assertTrue(AutoDuetLayout.shouldRecenterAfterDuet(true, 1));
        assertFalse(AutoDuetLayout.shouldRecenterAfterDuet(true, 2));
        assertFalse(AutoDuetLayout.shouldRecenterAfterDuet(false, 1));
        assertFalse(AutoDuetLayout.shouldRecenterAfterDuet(false, 0));
        assertFalse(AutoDuetLayout.isDuetTransition(1, 1));
        assertTrue(AutoDuetLayout.isDuetTransition(1, 2));
        assertTrue(AutoDuetLayout.isDuetTransition(2, 1));
        assertTrue(AutoDuetLayout.isDuetTransition(2, 2));
    }

    @Test public void duetBundleSurvivesRowSanitationWithRowKeysOnly() {
        Bundle next = new Bundle();
        next.putString("line", "next lyric");
        next.putInt("lineIndex", 2);
        Bundle duet = new Bundle();
        duet.putString("line", "overlapping echo");
        duet.putInt("lineIndex", 3);
        duet.putLong("lineStartMs", 12000);
        duet.putLong("lineEndMs", 15000);
        duet.putString("trackUri", "spotify:track:stale");
        duet.putBundle("duet", new Bundle());
        next.putBundle("duet", duet);
        Bundle clean = AutoPrototypeProvider.sanitizeRow(next);
        assertEquals("next lyric", clean.getString("line"));
        Bundle kept = clean.getBundle("duet");
        assertNotNull(kept);
        assertEquals("overlapping echo", kept.getString("line"));
        assertEquals(3, kept.getInt("lineIndex"));
        assertEquals(12000, kept.getLong("lineStartMs"));
        assertFalse(kept.containsKey("trackUri"));
        assertFalse(kept.containsKey("duet"));
    }

    @Test public void localAdvanceCarriesDuetForwardAndClearsStaleDuet() {
        Bundle current = new Bundle();
        current.putString("trackUri", "spotify:track:test");
        Bundle next = new Bundle();
        next.putString("line", "next lyric");
        next.putInt("lineIndex", 2);
        Bundle duet = new Bundle();
        duet.putString("line", "overlapping echo");
        duet.putInt("lineIndex", 3);
        next.putBundle("duet", duet);
        Bundle selected = AutoLyricsView.selectNext(current, next);
        assertEquals("next lyric", selected.getString("line"));
        assertEquals("overlapping echo", selected.getBundle("duet").getString("line"));
        Bundle stale = new Bundle(current);
        stale.putBundle("duet", duet);
        Bundle cleared = AutoLyricsView.selectNext(stale, plainNext());
        assertFalse(cleared.containsKey("duet"));
    }

    private static Bundle plainNext() {
        Bundle next = new Bundle();
        next.putString("line", "next lyric");
        next.putInt("lineIndex", 2);
        return next;
    }

    private static AppliedLine vocal(String text, long startMs, long endMs) {
        AppliedLine row = new AppliedLine();
        row.text = text; row.startMs = startMs; row.endMs = endMs;
        return row;
    }

    private static AppliedLine background(String text, long startMs, long endMs) {
        AppliedLine row = vocal(text, startMs, endMs);
        row.bgLine = true;
        return row;
    }

    private static DuetSectionId id(long startMs, long endMs, int ordinal) {
        return new DuetSectionId(7, startMs, endMs, ordinal);
    }
}
