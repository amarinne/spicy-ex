package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AdBreakInfoTest {

    @Test
    public void positionFromSpotifysLabel() {
        AdBreakInfo info = AdBreakInfo.parseText("Advertisement • 2 of 3");
        assertEquals(2, info.index);
        assertEquals(3, info.count);
        AdBreakInfo korean = AdBreakInfo.parseText("광고 • 3개 중 1");
        assertEquals(1, korean.index);
        assertEquals(3, korean.count);
        assertNull(AdBreakInfo.parseText("0:07"));
    }

    @Test
    public void timeLeftInTheBreak() {
        assertEquals(37, AdBreakInfo.parseBreakLeft("37s left in the break"));
        assertEquals(65, AdBreakInfo.parseBreakLeft("1:05 left in the break"));
        assertEquals(37, AdBreakInfo.parseBreakLeft("광고 37초 남음"));
        assertEquals(-1, AdBreakInfo.parseBreakLeft("Your music will continue after the break"));
        assertEquals(-1, AdBreakInfo.parseBreakLeft("0:20"));
    }
}
