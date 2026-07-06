package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SpicyResponseClassifierTest {
    @Test
    public void flagsLatinWordsWithInterleavedCyrillicLookalikes() {
        LyricsDocument doc = staticDoc("\u041d\u0435ll\u043e w\u043erld");

        SpicyResponseClassifier.apply(doc);

        assertTrue(doc.spicyPoisoned);
        assertEquals("POISON_HOMOGLYPH_NOTICE", doc.spicyQualityReason);
    }

    @Test
    public void allowsPureRussianLine() {
        assertOk(staticDoc("\u0411\u043e\u043b\u044c\u0448\u0435 \u043d\u0435 \u0440\u0430\u0434\u0443\u0435\u0448\u044c \u043b\u0443\u0447\u0430\u043c\u0438"));
    }

    @Test
    public void allowsKoreanEnglishMixedLine() {
        assertOk(staticDoc("Tonight \uc774\uc81c \ub108\ub97c \ub193\uc544\uc904\uac8c"));
    }

    @Test
    public void allowsRussianAndKyrgyzCyrillicLines() {
        LyricsDocument doc = staticDoc(
                "\u042f \u043f\u043e\u043c\u043d\u044e \u043a\u0430\u0436\u0434\u044b\u0439 \u043c\u0438\u0433",
                "\u0410\u043b\u0434\u0430\u0434\u044b\u04a3\u0431\u044b");

        assertOk(doc);
    }

    @Test
    public void allowsLatinOnlyDoc() {
        assertOk(staticDoc("Hello world", "Tonight I let you go"));
    }

    @Test
    public void allowsStaticTypeWithNormalText() {
        LyricsDocument doc = staticDoc("Normal static lyrics", "No timing available");
        doc.type = "Static";

        assertOk(doc);
    }

    @Test
    public void allowsPackedSyllablePayload() {
        LyricsDocument doc = staticDoc("hello");
        doc.type = "Syllable";
        doc.spicyPackedPayload = true;

        assertOk(doc);
    }

    @Test
    public void allowsPackedLinePayload() {
        LyricsDocument doc = staticDoc("hello");
        doc.type = "Line";
        doc.spicyPackedPayload = true;

        assertOk(doc);
    }

    @Test
    public void flagsNon200QueryStatus() {
        LyricsDocument doc = staticDoc("Normal text");
        doc.spicyQueryStatus = 204;

        SpicyResponseClassifier.apply(doc);

        assertTrue(doc.spicyPoisoned);
        assertEquals("QUERY_STATUS_NON_200", doc.spicyQualityReason);
    }

    @Test
    public void flagsPlainStaticDowngradeFormat() {
        LyricsDocument doc = staticDoc("Normal text");
        doc.spicyFormat = "plain";
        doc.spicyPackedPayload = false;

        SpicyResponseClassifier.apply(doc);

        assertTrue(doc.spicyPoisoned);
        assertEquals("DOWNGRADED_STATIC_PLAIN", doc.spicyQualityReason);
    }

    private static void assertOk(LyricsDocument doc) {
        SpicyResponseClassifier.apply(doc);
        assertFalse(doc.spicyPoisoned);
        assertNull(doc.spicyQualityReason);
    }

    private static LyricsDocument staticDoc(String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Static";
        doc.spicyQueryStatus = 200;
        doc.spicyFormat = "json";
        doc.spicyPackedPayload = true;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            doc.lines.add(line);
        }
        return doc;
    }
}
