package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure geometry contracts for ruby sizing and row clearance. */
public class JapaneseRubyGeometryTest {
    @Test
    public void rubyRatiosStaySharedAndExact() {
        assertEquals(46f, FuriganaText.rubyTextSize(100f), 0.001f);
        assertEquals(52, FuriganaText.rubyAscentReservationPx(100f));
        assertEquals(6, FuriganaText.rubyGapReservationPx(100f));
    }

    @Test
    public void liveCardMultiplierCannotShrinkBelowRubyReservation() {
        assertEquals(52, LyricsRowViewFactory.topClearancePx(10, 0.5f, 100f, true));
        assertEquals(70, LyricsRowViewFactory.topClearancePx(100, 0.7f, 100f, true));
        assertEquals(5, LyricsRowViewFactory.topClearancePx(10, 0.5f, 100f, false));
    }

    @Test
    public void furiganaRowsCanDisableBlurGlowLayer() {
        assertFalse(GlowFlexbox.shouldDrawGlow(false, 1f));
        assertFalse(GlowFlexbox.shouldDrawGlow(true, 0.02f));
        assertTrue(GlowFlexbox.shouldDrawGlow(true, 0.5f));
    }
}
