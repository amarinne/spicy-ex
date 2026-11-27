package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A translation that only tidies its source must not show as a second line. */
public class AiMeaningEchoTest {
    @Test
    public void punctuationCaseAndSpacingAloneDoNotMakeATranslation() {
        assertTrue(AiMeaningRun.sameWords("They might fizzle out soon", "They might fizzle, out soon"));
        assertTrue(AiMeaningRun.sameWords("Ain't gon' play nice", "Ain't gon'play nice"));
        assertTrue(AiMeaningRun.sameWords("(Super happy now)", "（super happy now）"));
    }

    @Test
    public void changedWordsStillShow() {
        assertFalse(AiMeaningRun.sameWords("I feel alive", "Me siento vivo"));
        assertFalse(AiMeaningRun.sameWords("Show them it, girl", "Show dem it, girl"));
    }

    @Test
    public void aClosingPeriodTheSourceLacksIsDropped() {
        org.junit.Assert.assertEquals("A girl made my mind grow up",
                AiMeaningRun.lyricPunctuation("A girl made my mind grow up.", "有个女仔令我思想变得大个"));
        org.junit.Assert.assertEquals("It ends here.",
                AiMeaningRun.lyricPunctuation("It ends here.", "Это конец."));
        org.junit.Assert.assertEquals("And then...",
                AiMeaningRun.lyricPunctuation("And then...", "그리고"));
        org.junit.Assert.assertEquals("Why are you silent?",
                AiMeaningRun.lyricPunctuation("Why are you silent?", "Почему ты молчишь"));
    }
}
