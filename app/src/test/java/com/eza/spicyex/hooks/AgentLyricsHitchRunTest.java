package com.eza.spicyex.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentLyricsHitchRunTest {
    @Test public void changeCountAndRunIdentityAreBounded() {
        assertTrue(AgentLyricsHitchRun.validRequest(new String[]{"10", "hitch_1"}));
        assertTrue(AgentLyricsHitchRun.validRequest(new String[]{"20", "hitch-2"}));
        for (String[] bad : new String[][]{{}, {"10"}, {"9", "id"}, {"21", "id"},
                {"bad", "id"}, {"10", "../out"}, {"10", ""}, {"10", "id", "extra"}})
            assertFalse(AgentLyricsHitchRun.validRequest(bad));
    }

    /**
     * The predicate has no window-focus input on purpose: the Fold 3 reports no focused window
     * while the surface is visible. Every other condition must still hold.
     */
    @Test public void visibleSurfaceNeedsEveryConditionExceptWindowFocus() {
        assertTrue(AgentLyricsHitchRun.ready(false, false, true, true, true, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(true, false, true, true, true, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(false, true, true, true, true, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, false, true, true, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, true, false, true, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, true, true, false, 0, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, true, true, true, 4, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, true, true, true, 8, true));
        assertFalse(AgentLyricsHitchRun.ready(false, false, true, true, true, 0, false));
    }

    @Test public void visibilityNamesMatchWhatTheFailureReports() {
        assertEquals("VISIBLE", AgentLyricsHitchRun.visibilityName(0));
        assertEquals("INVISIBLE", AgentLyricsHitchRun.visibilityName(4));
        assertEquals("GONE", AgentLyricsHitchRun.visibilityName(8));
        assertEquals("visibility 12", AgentLyricsHitchRun.visibilityName(12));
    }
}
