package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure geometry contracts for ruby sizing and row clearance. */
public class JapaneseRubyGeometryTest {
    @Test
    public void rubyRatiosStaySharedAndExact() {
        assertEquals(46f, FuriganaText.rubyTextSize(100f), 0.001f);
        assertEquals(58, FuriganaText.rubyAscentReservationPx(100f));
        assertEquals(12, FuriganaText.rubyGapReservationPx(100f));
    }

    @Test
    public void liveCardMultiplierCannotShrinkBelowRubyReservation() {
        assertEquals(58, LyricsRowViewFactory.topClearancePx(10, 0.5f, 100f, true));
        assertEquals(70, LyricsRowViewFactory.topClearancePx(100, 0.7f, 100f, true));
        assertEquals(5, LyricsRowViewFactory.topClearancePx(10, 0.5f, 100f, false));
    }
}
