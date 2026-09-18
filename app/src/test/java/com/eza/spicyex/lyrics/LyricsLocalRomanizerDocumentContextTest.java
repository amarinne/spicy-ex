package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyPlusConfig;

import org.junit.Test;
import java.util.List;

import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;

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
    public void chineseModeChangeClearsPreviousReadingPlan() {
        LyricsDocument doc = doc("yue", "香港");
        LyricsLine line = doc.lines.get(0);
        line.chineseMode = SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        line.readingRenderPlan = new RenderPlan("old", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "hoeng1 gong2", null);

        RomanizationOptions pinyin = new RomanizationOptions(
                SpotifyPlusConfig.CHINESE_MODE_PINYIN, "Off", true, "Off", false);
        String romanized = LyricsLocalRomanizer.romanizeLine(pinyin, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertTrue(romanized != null && !romanized.isEmpty());
        assertFalse(romanized.contains("hoeng1"));
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_PINYIN, line.chineseMode);
        assertTrue(line.readingRenderPlan == null);
    }

    @Test
    public void cyrillicModeChangeClearsPreviousReadingPlan() {
        LyricsDocument doc = doc("ru", "гора");
        LyricsLine line = doc.lines.get(0);
        line.readingRenderPlan = new RenderPlan("old", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "hora", null);

        RomanizationOptions ukrainian = new RomanizationOptions(
                "", "Off", false, SpicyRomanizer.CYRILLIC_UKRAINIAN, false);
        String romanized = LyricsLocalRomanizer.romanizeLine(ukrainian, doc, line,
                LyricsDocumentProcessor.collectText(doc));

        assertEquals("hora", romanized);
        assertTrue(line.readingRenderPlan == null);
    }

    @Test
    public void unmatchedDisplaySegmentFallsBackEvenWhenLineHasReadingPlan() {
        LyricsDocument doc = doc("yue", "香港");
        AppliedLine applied = new AppliedLine();
        applied.sourceLine = doc.lines.get(0);
        applied.readingRenderPlan = new RenderPlan("source", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "hoeng1 gong2", null);
        SyllableSegment synthetic = new SyllableSegment();
        synthetic.text = "香";

        String reading = LyricsLocalRomanizer.romanizeDisplaySegment(
                JYUTPING, doc, applied, synthetic, LyricsDocumentProcessor.collectText(doc));

        assertEquals("hoeng1", reading);
        assertEquals("hoeng1", synthetic.romanizedText);
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

    @Test
    public void packedJapaneseClauseFlagsPreserveWindAndPersonReadings() {
        String[] parts = {"時", "は", "まくら", "ぎ", "風", "は", "にきは", "だ",
                "星", "は", "うぶす", "な", "人", "は", "かげろ", "う"};
        LyricsDocument doc = doc("jpn", String.join("", parts));
        LyricsLine line = doc.lines.get(0);
        for (int i = 0; i < parts.length; i++) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = segment.sourceText = parts[i];
            segment.providerPartOfWord = i % 4 != 3;
            segment.startMs = i * 100;
            segment.endMs = (i + 1) * 100;
            line.syllables.add(segment);
        }
        line.text = com.eza.spicyex.lyrics.reading.SyllableCanonicalizer.canonicalize(
                "suzume", line.text, line.syllables).text;
        assertEquals("時はまくらぎ 風はにきはだ 星はうぶすな 人はかげろう", line.text);
        String reading = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, line.text);
        assertTrue(reading, reading.contains("kaze wa"));
        assertTrue(reading, reading.contains("hito wa"));
        assertTrue(line.japaneseReading.furigana.stream().anyMatch(
                ruby -> ruby.start == 7 && "かぜ".equals(ruby.reading)));
        assertTrue(line.japaneseReading.furigana.stream().anyMatch(
                ruby -> ruby.start == 21 && "ひと".equals(ruby.reading)));
        assertEquals(400, line.syllables.get(4).startMs);
        assertEquals(7, line.syllables.get(4).canonicalStartCp);
        assertNotNull(line.readingRenderPlan);
    }

    @Test
    public void packedPersonCounterBoundaryPreventsJinketsuCompound() {
        String[] parts = {"ほら", "この", "まま", "2", "人", "血", "が"};
        LyricsDocument doc = doc("jpn", String.join("", parts));
        LyricsLine line = doc.lines.get(0);
        for (int i = 0; i < parts.length; i++) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = segment.sourceText = parts[i];
            segment.providerPartOfWord = i != 4 && i != 6;
            segment.startMs = i * 100;
            segment.endMs = (i + 1) * 100;
            line.syllables.add(segment);
        }
        line.text = com.eza.spicyex.lyrics.reading.SyllableCanonicalizer.canonicalize(
                "person-counter", line.text, line.syllables).text;
        assertEquals("ほらこのまま 2人 血が", line.text);
        String reading = LyricsLocalRomanizer.romanizeLine(JYUTPING, doc, line, line.text);
        assertTrue(reading, reading.contains("futari chi ga"));
        assertTrue(line.japaneseReading.furigana.stream().anyMatch(
                ruby -> ruby.start == 7 && ruby.end == 9 && "ふたり".equals(ruby.reading)));
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
