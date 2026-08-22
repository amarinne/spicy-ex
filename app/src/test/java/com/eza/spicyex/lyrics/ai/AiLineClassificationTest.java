package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Classification decides what the model is asked about at all, so it is narrow on purpose: it only
 * withholds a row when it is certain, and treats every ambiguous case as an ordinary lyric line.
 */
public class AiLineClassificationTest {

    private static void assertClass(AiLineClass expected, String... values) {
        for (String value : values) {
            assertEquals("classifying: [" + value + "]", expected,
                    AiLineClassifier.classify(value));
        }
    }

    @Test
    public void emptyRowsMusicalNotesAndSectionHeadingsAreStructural() {
        assertClass(AiLineClass.STRUCTURAL, "", "   ", "\u266a", "\u266b", "\u266c",
                "[Chorus]", "[VERSE 2]", "[ pre-chorus ]", "[Intro]", "[post-chorus]",
                "[instrumental]", "[Bridge]", "[Outro]");
    }

    @Test
    public void interjectionsAreAdlibs() {
        assertClass(AiLineClass.ADLIB, "Yeah", "la la la", "Oh, oh!", "woah yeah", "Ooh-ooh",
                "HEY!", "na na na na");
    }

    @Test
    public void anythingAmbiguousStaysOrdinary() {
        assertClass(AiLineClass.ORDINARY, "[Producer]", "Alice", "yeah forever", "I'm sorry",
                "[Verse 2 of 3]", "Oh Alice", "...");
    }

    @Test
    public void aHeadingWrittenInAnotherScriptIsNotAKnownSectionMarker() {
        // The heading list is ASCII by construction; a bracketed non-ASCII label is lyric content.
        assertClass(AiLineClass.ORDINARY, "[\u30b3\u30fc\u30e9\u30b9]", "[\uc0ac\ub791]");
    }

    @Test
    public void classificationIgnoresWidthCaseAndPunctuation() {
        // NFKC folds the full-width forms, so a full-width "YEAH!!" is the same ad-lib.
        assertClass(AiLineClass.ADLIB, "\uff59\uff45\uff41\uff48\uff01\uff01");
    }

    @Test
    public void nonBreakingSpaceCountsAsBlank() {
        // JavaScript's trim treats NBSP as whitespace; a row of them is empty, not a lyric.
        assertClass(AiLineClass.STRUCTURAL, "\u00a0\u2003");
    }
}
