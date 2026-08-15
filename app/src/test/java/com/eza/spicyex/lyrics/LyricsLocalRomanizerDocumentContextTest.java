package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyPlusConfig;

import org.junit.Test;
import java.util.List;

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

    @Test
    public void providerFuriganaJapaneseLineStillProducesPlanAuthority() {
        LyricsDocument doc = doc("jpn", "紅葉");
        LyricsLine line = doc.lines.get(0);
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading("紅葉", "", java.util.Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "こうよう")));

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertEquals("kouyou", romanized);
        assertNotNull(line.readingRenderPlan);
        assertEquals("kouyou", line.readingRenderPlan.joinedDisplayText);
    }

    @Test
    public void repeatedProviderWordSpacingIsSoftForJapaneseAnalysis() {
        LyricsDocument doc = doc("jpn", "黙れ フィーリング 印 埋葬");
        LyricsLine line = doc.lines.get(0);

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertEquals("damare fiiringu in maisou", romanized);
        assertEquals("黙れ フィーリング 印 埋葬", line.text);
        assertNotNull(line.japaneseReading);
        assertTrue(line.japaneseReading.readingContext.tokens.stream().anyMatch(
                token -> "印".equals(token.surface)
                        && token.candidates.stream().anyMatch(candidate -> "いん".equals(candidate.kana))));
    }

    @Test
    public void loneJapaneseWhitespaceRemainsAuthoredAndHard() {
        LyricsDocument doc = doc("jpn", "一 等");
        LyricsLine line = doc.lines.get(0);

        List<JapaneseReadingPolicyModels.BoundaryEvidence> boundaries =
                LyricsLocalRomanizer.japaneseAnalysisBoundaries(line);
        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertTrue(boundaries.isEmpty());
        assertEquals("ichi tou", romanized);
    }

    @Test
    public void inferredSyllableGapsRemapReadingToDisplayCoordinates() {
        LyricsDocument doc = doc("jpn", "黙れ フィーリング 印 埋葬");
        LyricsLine line = doc.lines.get(0);
        String[] parts = {"黙れ", "フィーリング", "印", "埋葬"};
        int[] starts = {0, 3, 10, 12};
        int[] ends = {2, 9, 11, 14};
        for (int index = 0; index < parts.length; index++) {
            SyllableSegment segment = new SyllableSegment();
            segment.spanId = String.valueOf(index);
            segment.text = parts[index];
            segment.canonicalStartCp = starts[index];
            segment.canonicalEndCp = ends[index];
            segment.boundaryAfter = index + 1 < parts.length;
            segment.boundaryProvenance = index + 1 < parts.length
                    ? "completeProviderLine" : "lineEnd";
            line.syllables.add(segment);
        }

        String romanized = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertEquals("damare fiiringu in maisou", romanized);
        assertNotNull(line.readingRenderPlan);
        assertEquals(4, line.readingRenderPlan.timedReadingUnits.size());
        assertEquals("damare fiiringu in maisou", line.readingRenderPlan.joinedDisplayText);
        assertTrue(line.japaneseReading.furigana.stream().anyMatch(
                ruby -> ruby.start == 10 && ruby.end == 11 && "いん".equals(ruby.reading)));
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
