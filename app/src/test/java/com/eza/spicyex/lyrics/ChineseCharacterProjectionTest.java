package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.reading.ReadingModels.*;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChineseCharacterProjectionTest {
    @Test public void splitTimedPhrasesUseWholeLineContextAndKeepCanonicalOwners() {
        LyricsDocument doc = document("😀头发发出声音皇后以后", "😀", "头", "发", "发", "出", "声", "音", "皇", "后", "以", "后");
        LyricsLine source = doc.lines.get(0);
        source.romanizedText = "tou fa fa chu sheng yin";
        source.translatedText = "A voice";
        source.syllables.get(2).romanizedText = "fa";
        doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        LyricTimeline.applySyncedRows(doc);
        AppliedLine row = doc.appliedLines.get(0);
        assertEquals("😀頭髮發出聲音皇后以後", row.text);
        assertEquals(row.text, joined(row));
        assertEquals("髮", row.words.get(2).text);
        assertEquals("發", row.words.get(3).text);
        assertEquals("后", row.words.get(8).text);
        assertSame(source, row.sourceLine);
        assertEquals("😀头发发出声音皇后以后", source.text);
        assertEquals("发", source.syllables.get(2).text);
        assertEquals(source.romanizedText, row.romanizedText);
        assertEquals(source.translatedText, row.translatedText);
        for (int i = 0; i < row.words.size(); i++) {
            SyllableSegment from = source.syllables.get(i), to = row.words.get(i);
            assertNotSame(from, to);
            assertEquals(from.spanId, to.spanId);
            assertEquals(from.sourceText, to.sourceText);
            assertEquals(from.romanizedText, to.romanizedText);
            assertEquals(from.startMs, to.startMs);
            assertEquals(from.endMs, to.endMs);
            assertEquals(from.boundaryAfter, to.boundaryAfter);
            assertEquals(from.canonicalStartCp, to.canonicalStartCp);
            assertEquals(from.canonicalEndCp, to.canonicalEndCp);
        }
    }

    @Test public void simplifiedTraditionalOffRestoresSourceAndBackground() {
        LyricsDocument doc = document("靜静看着头发与皇后😀", "靜", "静", "看", "着", "头", "发", "与", "皇", "后", "😀");
        BackgroundLine bg = new BackgroundLine();
        bg.text = "请別离开";
        bg.startMs = 100; bg.endMs = 800;
        bg.syllables.add(span("b0", "请別", 0, 2));
        bg.syllables.add(span("b1", "离开", 2, 4));
        doc.lines.get(0).backgroundLines.add(bg);
        ChineseCharacterForm[] forms = {ChineseCharacterForm.ORIGINAL, ChineseCharacterForm.SIMPLIFIED,
                ChineseCharacterForm.TRADITIONAL, ChineseCharacterForm.ORIGINAL};
        String[] expected = {"靜静看着头发与皇后😀", "静静看着头发与皇后😀",
                "靜靜看著頭髮與皇后😀", "靜静看着头发与皇后😀"};
        String[] backgrounds = {"请別离开", "请别离开", "請別離開", "请別离开"};
        for (int i = 0; i < forms.length; i++) {
            doc.chineseCharacterForm = forms[i];
            LyricTimeline.applySyncedRows(doc);
            assertEquals(expected[i], doc.appliedLines.get(0).text);
            assertEquals(expected[i], joined(doc.appliedLines.get(0)));
            assertEquals(backgrounds[i], doc.appliedLines.get(1).text);
            assertEquals(backgrounds[i], joined(doc.appliedLines.get(1)));
            assertTrue(doc.appliedLines.get(1).words.get(0).bgWord);
            assertEquals("请別离开", bg.text);
            assertEquals("请別", bg.syllables.get(0).text);
        }
    }

    @Test public void canonicalRangeThenReadingPlanThenSequentialMatchingPreservesContext() {
        for (boolean plan : new boolean[]{false, true}) {
            LyricsDocument doc = document("😀 头发", "😀", "头", "发");
            LyricsLine line = doc.lines.get(0);
            for (SyllableSegment seg : line.syllables) seg.canonicalStartCp = seg.canonicalEndCp = -1;
            if (plan) line.readingRenderPlan = new RenderPlan("row", Arrays.asList(
                    new CanonicalSpanMapping("w0", new TextRange(0, 1)),
                    new CanonicalSpanMapping("w1", new TextRange(2, 3)),
                    new CanonicalSpanMapping("w2", new TextRange(3, 4))),
                    Collections.emptyList(), Collections.emptyList(), "tou fa", null);
            doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
            LyricTimeline.applySyncedRows(doc);
            AppliedLine row = doc.appliedLines.get(0);
            assertEquals("😀 頭髮", row.text);
            assertEquals("頭", row.words.get(1).text);
            assertEquals("髮", row.words.get(2).text);
            assertEquals(3, row.words.get(2).canonicalStartCp);
            assertEquals(-1, line.syllables.get(2).canonicalStartCp);
            assertSame(line.readingRenderPlan, row.readingRenderPlan);
        }
    }

    @Test public void rowDetectionOwnsLanguageAndUnresolvedHanStaysOriginal() {
        LyricsDocument doc = document("汉语头发", "汉语", "头发");
        doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        LyricsLine line = doc.lines.get(0);
        line.detection = DetectionResult.unknown("row", line.text);
        LyricTimeline.applySyncedRows(doc);
        assertEquals(line.text, doc.appliedLines.get(0).text);
        line.detection = DetectionResult.detected("row", line.text, ScriptClassifier.ScriptClass.CHINESE, "ja", 1);
        LyricTimeline.applySyncedRows(doc);
        assertEquals(line.text, doc.appliedLines.get(0).text);
        doc.language = "ja"; doc.detectedChinese = false;
        line.detection = DetectionResult.detected("row", line.text, ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
        LyricTimeline.applySyncedRows(doc);
        assertEquals("漢語頭髮", doc.appliedLines.get(0).text);
    }

    @Test public void detectedChineseWithoutLanguageMetadataConvertsButKanaDoesNot() {
        LyricsDocument doc = document("请別离开", "请", "別", "离", "开");
        doc.language = "unknown"; doc.detectedChinese = true;
        doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        LyricTimeline.applySyncedRows(doc);
        assertEquals("請別離開", doc.appliedLines.get(0).text);
        doc.lines.get(0).text = "静かな夜";
        LyricTimeline.applySyncedRows(doc);
        assertEquals("静かな夜", doc.appliedLines.get(0).text);
    }

    @Test public void staticBackgroundsUseTheSameProjection() {
        LyricsDocument doc = document("头发", "头", "发");
        doc.type = "Static";
        BackgroundLine bg = new BackgroundLine();
        bg.text = "请別离开"; bg.startMs = 0; bg.endMs = 1000;
        doc.lines.get(0).backgroundLines.add(bg);
        doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        LyricTimeline.applySyncedRows(doc);
        assertEquals(2, doc.appliedLines.size());
        assertEquals("頭髮", doc.appliedLines.get(0).text);
        assertEquals("請別離開", doc.appliedLines.get(1).text);
    }

    @Test public void readingRefreshDoesNotRevertConvertedMainGlyphs() {
        LyricsDocument doc = document("头发", "头", "发");
        doc.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        LyricTimeline.applySyncedRows(doc);
        AppliedLine row = doc.appliedLines.get(0);
        doc.lines.get(0).romanizedText = "tau4 faat3";
        doc.lines.get(0).syllables.get(1).romanizedText = "faat3";
        assertTrue(LyricTimeline.refreshAppliedDerivedText(doc));
        assertSame(row, doc.appliedLines.get(0));
        assertEquals("頭髮", row.text);
        assertEquals("髮", row.words.get(1).text);
        assertEquals("tau4 faat3", row.romanizedText);
        assertEquals("faat3", row.words.get(1).romanizedText);
    }

    @Test public void sameSourceDetectionPublicationsReprojectMainGlyphsAndPreserveReadings() {
        LyricsDocument mounted = document("头发", "头", "发");
        mounted.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        mounted.lines.get(0).detection = DetectionResult.unknown("row", "头发");
        mounted.lines.get(0).romanizedText = "tou fa";
        LyricTimeline.applySyncedRows(mounted);
        assertEquals("头发", mounted.appliedLines.get(0).text);
        LyricsDocument published = LyricsDocument.copyOf(mounted);
        published.lines.get(0).romanizedText = "";
        published.lines.get(0).detection = DetectionResult.detected("row", "头发",
                ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
        LyricsDocumentProcessor.mergeDerivedPublication(mounted, published);
        assertTrue(LyricTimeline.refreshChineseCharacterProjection(mounted));
        assertEquals("頭髮", mounted.appliedLines.get(0).text);
        assertEquals("髮", mounted.appliedLines.get(0).words.get(1).text);
        assertEquals("tou fa", mounted.appliedLines.get(0).romanizedText);
        assertFalse(LyricTimeline.refreshChineseCharacterProjection(mounted));
        published.lines.get(0).detection = DetectionResult.detected("row", "头发",
                ScriptClassifier.ScriptClass.CHINESE, "ja", 1);
        LyricsDocumentProcessor.mergeDerivedPublication(mounted, published);
        assertTrue(LyricTimeline.refreshChineseCharacterProjection(mounted));
        assertEquals("头发", mounted.appliedLines.get(0).text);
        assertEquals("发", mounted.lines.get(0).syllables.get(1).text);
    }

    static LyricsDocument document(String text, String... words) {
        LyricsDocument doc = new LyricsDocument();
        doc.language = "zh"; doc.detectedChinese = true; doc.type = "Syllable";
        LyricsLine line = new LyricsLine();
        line.text = text; line.startMs = 0; line.endMs = 2000;
        int cursor = 0;
        for (int i = 0; i < words.length; i++) {
            int from = text.indexOf(words[i], cursor);
            int startCp = text.codePointCount(0, from);
            int endCp = startCp + words[i].codePointCount(0, words[i].length());
            line.syllables.add(span("w" + i, words[i], startCp, endCp));
            cursor = from + words[i].length();
        }
        doc.lines.add(line);
        return doc;
    }

    private static SyllableSegment span(String id, String text, int startCp, int endCp) {
        SyllableSegment seg = new SyllableSegment();
        seg.spanId = id; seg.text = seg.sourceText = text;
        seg.canonicalStartCp = startCp; seg.canonicalEndCp = endCp;
        seg.startMs = startCp * 100L; seg.endMs = endCp * 100L; seg.totalMs = seg.endMs - seg.startMs;
        return seg;
    }

    private static String joined(AppliedLine row) {
        StringBuilder text = new StringBuilder();
        for (SyllableSegment seg : row.words) text.append(seg.text);
        return text.toString();
    }
}
