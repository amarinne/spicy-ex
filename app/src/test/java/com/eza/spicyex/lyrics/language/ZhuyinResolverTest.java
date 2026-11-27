package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.reading.DefaultRenderPlanBuilder;
import com.eza.spicyex.lyrics.session.DetectionResult;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class ZhuyinResolverTest {
    private static String pinyin(String text) {
        return SpicyJapaneseChineseProcessor.romanizeChineseLine(text, "pinyin", true);
    }
    @Test public void screenshotHookResolvesAsWholeSyllables() {
        assertEquals("mū mū xī lū xī lī", pinyin("ㄇㄨㄇㄨㄒㄧㄌㄨㄒㄧㄌㄧ"));
        assertEquals("mū mū xī lū xī lī", pinyin("ㄇㄨ ㄇㄨ ㄒㄧ ㄌㄨ ㄒㄧ ㄌㄧ"));
    }
    @Test public void qingTianProviderUsesHanOneAsAMedialWithoutChangingHanOne() {
        assertEquals("ruī sōu sōu xī dōu xī lā", pinyin("ㄖㄨㄟ ㄙㄡ ㄙㄡ ㄒ一 ㄉㄡ ㄒ一 ㄌㄚ"));
        assertEquals("sōu lā xī xī xī xī lā xī lā sōu", pinyin("ㄙㄡ ㄌㄚ ㄒ一 ㄒ一 ㄒ一 ㄒ一 ㄌㄚ ㄒ一 ㄌㄚ ㄙㄡ"));
        assertEquals("xi1", ZhuyinResolver.resolve("一ㄒ一").get(0).numbered);
        assertEquals(1, ZhuyinResolver.resolve("一ㄒ一").get(0).start);
        assertEquals(3, ZhuyinResolver.resolve("一ㄒ一").get(0).end);
        assertEquals(pinyin("一") + " xī", pinyin("一ㄒ一"));
        assertEquals("ㄅ一ㄚ", pinyin("ㄅ一ㄚ"));
        assertEquals(pinyin("一個人"), SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine("一個人", true));
    }
    @Test public void standardSpellingAndAuthoredTones() {
        assertEquals("zhī chí shǐ rì zī cí sǐ", pinyin("ㄓ ㄔˊ ㄕˇ ㄖˋ ㄗ ㄘˊ ㄙˇ"));
        assertEquals("yī wǔ yǔ yuè yún yuán yǒng", pinyin("ㄧ ㄨˇ ㄩˇ ㄩㄝˋ ㄩㄣˊ ㄩㄢˊ ㄩㄥˇ"));
        assertEquals("jiǔ guì lún lǜ nüè", pinyin("ㄐㄧㄡˇ ㄍㄨㄟˋ ㄌㄨㄣˊ ㄌㄩˋ ㄋㄩㄝˋ"));
        assertEquals("mā ma mā ma", pinyin("ㄇㄚˉ ˙ㄇㄚ ㄇㄚ ㄇㄚ˙"));
        assertEquals("mā ma", pinyin("ㄇㄚ˙ㄇㄚ"));
    }
    @Test public void authoredPhoneticsNeverInventHanOrGrammar() {
        assertEquals("nỉ hảo", SpicyJapaneseChineseProcessor.romanizeChineseLine("ㄋㄧˇㄏㄠˇ", "pinyin-eza", true));
        assertEquals("ni hao", SpicyJapaneseChineseProcessor.romanizeChineseLine("ㄋㄧˇㄏㄠˇ", "pinyin-eza", false));
        assertEquals("nǐ hǎo", SpicyJapaneseChineseProcessor.romanizeChineseLine("ㄋㄧˇㄏㄠˇ", "jyutping", true));
        assertEquals("nỉ hảo", SpicyJapaneseChineseProcessor.romanizeChineseLine("ㄋㄧˇㄏㄠˇ", "jyutping-eza", true));
    }
    @Test public void invalidRunsAndOtherScriptsStayLiteral() {
        assertEquals("plain lyric", pinyin(" plain   lyric "));
        for (String text : Arrays.asList("ㄅㄆ", "ㄒㄨ", "ㄅㄩ", "ㄇㄨㄚ", "ㄇㄨㄇ", "ㄪㄚ", "ㄇㄚˊˋ", "˙ㄇㄚˋ", "ㄇㄚㆠ", "カタカナ", "mu xi", "ˊ")) {
            assertEquals(text, pinyin(text));
            assertTrue(text, ZhuyinResolver.resolve(text).isEmpty());
        }
        assertEquals("ㄅㄆ nǐ", pinyin("ㄅㄆ ㄋㄧˇ"));
        assertEquals("😀nǐ! Love 42", pinyin("😀ㄋㄧˇ! Love 42"));
        assertEquals(pinyin("愛") + " nǐ," + pinyin("好"), pinyin("愛ㄋㄧˇ,好"));
        assertEquals("huā ér", pinyin("ㄏㄨㄚㄦˊ"));
    }
    @Test public void rangesIncludeTonesAndDoNotUseSurfaceIdentity() {
        List<ZhuyinResolver.Syllable> rows = ZhuyinResolver.resolve("😀ㄋㄧˇ˙ㄇㄚ ㄋㄧˇ");
        assertEquals(Arrays.asList("ni3", "ma5", "ni3"), rows.stream().map(r -> r.numbered).collect(Collectors.toList()));
        assertEquals(2, rows.get(0).start); assertEquals(5, rows.get(0).end);
        assertEquals(5, rows.get(1).start); assertEquals(8, rows.get(1).end);
        assertEquals(9, rows.get(2).start); assertEquals(12, rows.get(2).end);
    }
    @Test public void detectionNeedsNoModelAndRespectsJapaneseKana() {
        LanguageDetectorManager manager = new LanguageDetectorManager(() -> { throw new AssertionError("no model needed"); });
        assertEquals("zh", manager.detect("ㄇㄨㄇㄨㄒㄧ").language);
        assertEquals("zh", manager.detect("ㄧ").language);
        assertEquals("zh", manager.detect("愛ㄋㄧˇ").language);
        assertEquals("ja", manager.detect("あㄋㄧˇ").language);
        assertTrue(SpicyTextDetection.hasRomanizableScript("ㄋㄧˇ"));
        assertTrue(SpicyTextDetection.hasResidualScript("ㄋㄧˇ"));
        assertFalse(SpicyTextDetection.hasKana("ㄋㄧˇ"));
    }
    @Test public void timedFragmentsResolveBeforeProjectionAndDoNotDuplicateReading() {
        LyricsDocument doc = new LyricsDocument(); doc.language = "zh";
        LyricsLine line = new LyricsLine(); line.text = "ㄋㄧˇㄏㄠˇ"; line.startMs = 100; line.endMs = 900;
        line.syllables = new ArrayList<>();
        int at = 0;
        for (String text : Arrays.asList("ㄋ", "ㄧˇ", "ㄏㄠˇ")) {
            SyllableSegment seg = new SyllableSegment(); seg.text = text; seg.sourceText = text;
            seg.spanId = "s" + at; seg.startMs = 100 + at * 200; seg.endMs = seg.startMs + 200;
            line.syllables.add(seg); at++;
        }
        line.detection = DetectionResult.detected("row", line.text, ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
        RomanizationOptions options = new RomanizationOptions("pinyin", "Off", true, "Off", false);
        assertTrue(LyricsLocalRomanizer.shouldLocalRomanize(true, "pinyin", doc, line, line.text));
        assertEquals("nǐ hǎo", LyricsLocalRomanizer.romanizeLine(options, doc, line, line.text));
        assertEquals("ㄋㄧˇㄏㄠˇ", line.text);
        assertNotNull(line.readingRenderPlan);
        assertTrue(DefaultRenderPlanBuilder.validate(line.readingRenderPlan).valid);
        assertEquals("nǐ hǎo", line.readingRenderPlan.joinedDisplayText);
        assertEquals(3, line.readingRenderPlan.timedReadingUnits.size());
        assertEquals("nǐ", line.readingRenderPlan.timedReadingUnits.get(0).text);
        assertEquals("", line.readingRenderPlan.timedReadingUnits.get(1).text);
        assertEquals(line.readingRenderPlan.logicalGroupForSpans("s0"), line.readingRenderPlan.logicalGroupForSpans("s1"));
        assertEquals(100, line.syllables.get(0).startMs);
        assertEquals(700, line.syllables.get(2).endMs);
        RomanizationOptions off = new RomanizationOptions("", "Off", true, "Off", false);
        assertTrue(SoundWithholdPolicy.withholdRow(off, line.text, line.detection, "zh"));
    }
}
