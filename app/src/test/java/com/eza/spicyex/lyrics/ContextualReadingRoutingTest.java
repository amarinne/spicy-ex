package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextualReadingRoutingTest {
    @Test public void compactBackendLoadsAndAbstainsOnAmbiguousHan() {
        try {
            assertEquals("en", LatinLanguageGate.detect(
                    "This is an ordinary English sentence about singing together.").language);
            assertEquals("zh", LatinLanguageGate.detect("风很温柔").language);
            assertFalse(LatinLanguageGate.detect("東京").hasLanguage());
        } finally { LatinLanguageGate.trimMemory(); }
    }
    @Test public void backgroundTextNeverInheritsItsLeadLanguage() {
        AppliedLine row = new AppliedLine(); row.bgLine = true; row.text = "中国";
        row.sourceLine = new LyricsLine(); row.sourceLine.text = "君の声";
        row.sourceLine.detection = DetectionResult.detected("", row.sourceLine.text,
                ScriptClassifier.ScriptClass.JAPANESE, "ja", 1);
        assertFalse(LyricsDisplayMode.isJapaneseLine(row));
        assertEquals("und", ReadingLanguagePolicy.layoutLanguage(row));
        row.text = "こんにちは";
        row.sourceLine.detection = DetectionResult.detected("", "中国",
                ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
        assertTrue(LyricsDisplayMode.isJapaneseLine(row));
    }
    @Test public void targetChangesTranslationButNeverReadingLanguage() {
        String text = "東京";
        DetectionResult ja = DetectionResult.detected("", text, ScriptClassifier.classify(text), "ja", .8);
        assertTrue(SpicyProcessing.flagsFor(text, "", "en", ja).translationPending);
        assertFalse(SpicyProcessing.flagsFor(text, "", "ja", ja).translationPending);
        assertTrue(SpicyProcessing.flagsFor(text, "", "zh-Hans", ja).translationPending);
        assertEquals("ja", ReadingLanguagePolicy.language(text, ja, "zh"));
    }
    @Test public void latinDetectionWorksWithoutSourceMetadata() {
        String text = "Nous chantons ensemble ce soir";
        DetectionResult fr = DetectionResult.detected("", text, ScriptClassifier.classify(text), "fr", .95);
        assertFalse(SpicyProcessing.flagsFor(text, "", "fr-FR", fr).translationPending);
        assertTrue(SpicyProcessing.flagsFor(text, "", "en", fr).translationPending);
    }
    @Test public void unresolvedHanCanTranslateToEnglishButCannotGetGuessedReadings() {
        String text = "漢字";
        DetectionResult unknown = DetectionResult.scriptOnly("", text, ScriptClassifier.classify(text));
        assertEquals("", ReadingLanguagePolicy.language(text, unknown, "ja"));
        assertTrue(SpicyProcessing.flagsFor(text, "ja", "en", unknown).translationPending);
        LyricsLine line = new LyricsLine(); line.text = text; line.detection = unknown;
        assertFalse(LyricsLocalRomanizer.shouldGoogleRomanize(true, line));
        LyricsDocument doc = new LyricsDocument(); doc.language = "ja"; doc.lines.add(line);
        assertFalse(LyricsLocalRomanizer.shouldLocalRomanize(true, "pinyin", doc, line, "君の声 漢字"));
    }
    @Test public void chineseResultSuppressesStaleJapaneseDisplayEvidence() {
        LyricsLine source = new LyricsLine(); source.text = "今天我们一起唱歌";
        source.detection = DetectionResult.detected("", source.text, ScriptClassifier.classify(source.text), "zh", .99);
        AppliedLine line = new AppliedLine(); line.text = source.text; line.sourceLine = source;
        assertFalse(LyricsDisplayMode.isJapaneseLine(line));
    }
    @Test public void hanOnlyLineGetsNoParseTimeJapaneseReading() {
        assertNull(SpicyJapaneseChineseProcessor.finalizeParsedJapaneseReading(null, "今天我们一起唱歌"));
        SpicyJapaneseChineseProcessor.JapaneseReading kana =
                SpicyJapaneseChineseProcessor.finalizeParsedJapaneseReading(null, "君の声");
        assertNotNull(kana);
        assertFalse(kana.romaji.trim().isEmpty());
    }
    @Test public void hanOnlyProviderTextRequiresItsOwnLanguageEvidence() {
        assertEquals("", ProviderTranslationResolver.resolve("hello", "世界", "", "ja"));
        DetectionResult ja = DetectionResult.detected("", "世界", ScriptClassifier.classify("世界"), "ja", .95);
        assertEquals("世界", ProviderTranslationResolver.resolve("hello", "世界", "", "ja", text -> ja));
        assertEquals("", ProviderTranslationResolver.resolve("hello", "世界", "", "zh", text -> ja));
    }
}
