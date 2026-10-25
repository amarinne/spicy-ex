package com.eza.spicyex.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentDemoFrameRunTest {
    @Test public void durationAndRunIdentityAreBounded() {
        assertTrue(AgentDemoFrameRun.validRequest(new String[]{"5", "pair_1"}));
        assertTrue(AgentDemoFrameRun.validRequest(new String[]{"60", "pair-2"}));
        for (String[] bad : new String[][]{{}, {"50"}, {"4", "id"}, {"61", "id"},
                {"bad", "id"}, {"50", "../out"}, {"50", ""}, {"50", "id", "extra"}})
            assertFalse(AgentDemoFrameRun.validRequest(bad));
    }
}
