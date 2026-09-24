package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricEmphasisTest {

    @Test
    public void onlyHeldShortWordsQualify() {
        assertNull(LyricEmphasis.forWord("love", 0, 900, false));
        assertNotNull(LyricEmphasis.forWord("love", 0, 1500, false));
        assertNull("single letters do not distort", LyricEmphasis.forWord("I", 0, 3000, false));
        assertNull("long words would become illegible",
                LyricEmphasis.forWord("everything", 0, 3000, false));
        assertNotNull("CJK has no length limit", LyricEmphasis.forWord("永遠に", 0, 1200, false));
    }

    @Test
    public void longerHoldsAndTheLastWordAreStronger() {
        LyricEmphasis.Params short1 = LyricEmphasis.forWord("love", 0, 1200, false);
        LyricEmphasis.Params long1 = LyricEmphasis.forWord("love", 0, 4000, false);
        LyricEmphasis.Params last = LyricEmphasis.forWord("love", 0, 4000, true);
        assertTrue(long1.amount > short1.amount);
        assertTrue(last.amount >= long1.amount);
        assertTrue(last.durationMs > long1.durationMs);
        assertTrue(last.amount <= 1.2f && last.glow <= 0.8f);
    }

    @Test
    public void easingIsABellThatReturnsToRest() {
        assertEquals(0f, LyricEmphasis.easing(0f), 1e-4f);
        assertEquals(1f, LyricEmphasis.easing(0.5f), 1e-3f);
        assertEquals(0f, LyricEmphasis.easing(1f), 1e-4f);
        assertTrue(LyricEmphasis.easing(0.25f) > 0f && LyricEmphasis.easing(0.75f) > 0f);
    }

    @Test
    public void lettersPeakInTurnAndPushAwayFromTheCentre() {
        LyricEmphasis.Params params = LyricEmphasis.forWord("hold", 0, 3000, false);
        LyricEmphasis.Frame first = new LyricEmphasis.Frame();
        LyricEmphasis.Frame lastLetter = new LyricEmphasis.Frame();
        // Early on the first letter is swelling while the last has not started.
        LyricEmphasis.letterFrame(params, 0, 4, 900f, false, first);
        LyricEmphasis.letterFrame(params, 3, 4, 900f, false, lastLetter);
        assertTrue(first.scale > lastLetter.scale);
        // Left of centre moves left, right of centre moves right.
        LyricEmphasis.letterFrame(params, 0, 4, 1500f, false, first);
        LyricEmphasis.letterFrame(params, 3, 4, 2400f, false, lastLetter);
        assertTrue(first.xEm < 0f);
        assertTrue(lastLetter.xEm > 0f);
        assertTrue(first.yEm < 0f);
    }

    @Test
    public void restsBeforeAndAfter() {
        LyricEmphasis.Params params = LyricEmphasis.forWord("hold", 0, 2000, false);
        LyricEmphasis.Frame frame = new LyricEmphasis.Frame();
        LyricEmphasis.letterFrame(params, 1, 4, -1000f, false, frame);
        assertEquals(1f, frame.scale, 1e-4f);
        assertEquals(0f, frame.yEm, 1e-4f);
        LyricEmphasis.letterFrame(params, 1, 4, 10000f, false, frame);
        assertEquals(1f, frame.scale, 1e-4f);
        assertEquals(0f, frame.xEm, 1e-4f);
        assertEquals(0f, frame.yEm, 1e-4f);
    }
}
