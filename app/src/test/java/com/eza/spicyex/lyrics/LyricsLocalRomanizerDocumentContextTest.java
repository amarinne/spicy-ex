package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyPlusConfig;

import org.junit.Test;

public class LyricsLocalRomanizerDocumentContextTest {
    private static final RomanizationOptions JYUTPING = new RomanizationOptions(
            SpotifyPlusConfig.CHINESE_MODE_JYUTPING, "Off", true, "Off", false);

    @Test
    public void hanOnlyLineInJapaneseDocumentUsesJapaneseAnalyzer() {
        LyricsDocument doc = doc("ja", "これはテスト", "生意気問題児");
        LyricsLine line = doc.lines.get(1);

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, LyricsDocumentProcessor.collectText(doc));

        assertNotNull(romanized);
        assertFalse(containsJyutpingToneDigits(romanized));
        assertTrue(line.chineseMode == null || line.chineseMode.isEmpty());
    }

    @Test
    public void hanOnlyUniverseLineInJapaneseDocumentUsesJapaneseAnalyzer() {
        LyricsDocument doc = doc("jpn", "これはテスト", "全宇宙全世界");
        LyricsLine line = doc.lines.get(1);

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, LyricsDocumentProcessor.collectText(doc));

        assertNotNull(romanized);
        assertTrue(romanized.contains("zen"));
        assertTrue(romanized.contains("uchuu"));
        assertTrue(romanized.contains("sekai"));
        assertFalse(containsJyutpingToneDigits(romanized));
        assertTrue(line.chineseMode == null || line.chineseMode.isEmpty());
    }

    @Test
    public void mixedLatinHanLineInJapaneseDocumentDoesNotUseJyutping() {
        LyricsDocument doc = doc("ja", "これはテスト", "I'm fucking hater princess 生意気問題児");
        LyricsLine line = doc.lines.get(1);

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, LyricsDocumentProcessor.collectText(doc));

        assertNotNull(romanized);
        assertFalse(containsJyutpingToneDigits(romanized));
        assertTrue(line.chineseMode == null || line.chineseMode.isEmpty());
    }

    @Test
    public void pureChineseDocumentStillUsesJyutping() {
        LyricsDocument doc = doc("yue", "香港");
        LyricsLine line = doc.lines.get(0);

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, LyricsDocumentProcessor.collectText(doc));

        assertEquals("hoeng1 gong2", romanized);
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_JYUTPING, line.chineseMode);
    }

    private static LyricsDocument doc(String language, String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.language = language;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            doc.lines.add(line);
        }
        doc.detectedScripts.addAll(SpicyTextDetection.detectPresentScripts(
                LyricsDocumentProcessor.collectText(doc), doc.language, ""));
        doc.detectedChinese = doc.detectedScripts.contains(SpicyTextDetection.Script.CHINESE);
        return doc;
    }

    private static boolean containsJyutpingToneDigits(String value) {
        return value != null && value.matches(".*[1-6].*");
    }
}
