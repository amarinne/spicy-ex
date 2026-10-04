package com.eza.spicyex.lyrics.language;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.DetectionResult;

import org.junit.Test;

/**
 * One user choice must mean the same thing in all three Sound tiers. The local engine already
 * produces nothing for an Off row; this pins the row selection the lane work lists and the AI
 * gap decision share, so neither network tier can treat that absence as a gap.
 */
public class SoundWithholdPolicyTest {

    private static RomanizationOptions opts(String chineseMode, String koreanMode,
                                            String cyrillicMode) {
        return new RomanizationOptions(chineseMode, koreanMode, false, cyrillicMode, false);
    }

    private static RomanizationOptions defaults() {
        return RomanizationOptions.DEFAULTS;
    }

    private static DetectionResult detectedZh(String text) {
        return DetectionResult.detected("r0", text, ScriptClassifier.ScriptClass.CHINESE,
                "zh", 1.0);
    }

    @Test public void koreanOffWithholdsHangulRows() {
        RomanizationOptions off = opts("pinyin", KoreanDisplayMode.OFF.value, "Russian");
        assertTrue(SoundWithholdPolicy.withholdRow(off, "안녕하세요", null, "ko"));
        assertFalse(SoundWithholdPolicy.withholdRow(defaults(), "안녕하세요", null, "ko"));
        assertFalse(SoundWithholdPolicy.withholdRow(off, "hello", null, "ko"));
    }

    @Test public void cyrillicOffWithholdsCyrillicRows() {
        RomanizationOptions off = opts("pinyin", KoreanDisplayMode.RR_STANDARD.value, "Off");
        assertTrue(SoundWithholdPolicy.withholdRow(off, "Моя любовь", null, "ru"));
        assertFalse(SoundWithholdPolicy.withholdRow(defaults(), "Моя любовь", null, "ru"));
        assertFalse(SoundWithholdPolicy.withholdRow(off, "hello", null, "ru"));
    }

    @Test public void chineseOffWithholdsOnlyRowsRoutedAsChinese() {
        RomanizationOptions off = opts("", KoreanDisplayMode.RR_STANDARD.value, "Russian");
        assertTrue("detected Chinese is withheld",
                SoundWithholdPolicy.withholdRow(off, "你好", detectedZh("你好"), "zh"));
        assertTrue("undetected Chinese with a Chinese document hint is withheld",
                SoundWithholdPolicy.withholdRow(off, "你好", null, "zh"));
        assertFalse(SoundWithholdPolicy.withholdRow(defaults(), "你好", detectedZh("你好"), "zh"));
    }

    @Test public void chineseOffNeverWithholdsJapaneseKanjiLines() {
        RomanizationOptions off = opts("", KoreanDisplayMode.RR_STANDARD.value, "Russian");
        // Kanji without kana, no detection, no hint: unresolved Han, not a Chinese row.
        assertFalse(SoundWithholdPolicy.withholdRow(off, "今日", null, ""));
        // Kana marks the row Japanese even with a Chinese document hint.
        assertFalse(SoundWithholdPolicy.withholdRow(off, "今日は", null, "zh"));
    }

    @Test public void thaiIsNeverWithheld() {
        RomanizationOptions allOff = opts("", KoreanDisplayMode.OFF.value, "Off");
        assertFalse(SoundWithholdPolicy.withholdRow(allOff, "สวัสดี", null, "th"));
    }

    @Test public void nullInputsNeverWithhold() {
        assertFalse(SoundWithholdPolicy.withholdRow(null, "안녕하세요", null, "ko"));
        assertFalse(SoundWithholdPolicy.withholdRow(defaults(), null, null, "ko"));
        assertFalse(SoundWithholdPolicy.withholdRow(defaults(), "", null, "ko"));
    }

    @Test public void chineseRoutingMatchesTheLocalEngine() {
        assertTrue(SoundWithholdPolicy.isChineseLine("你好", detectedZh("你好"), "zh"));
        assertTrue(SoundWithholdPolicy.isChineseLine("你好", null, "zh"));
        assertFalse(SoundWithholdPolicy.isChineseLine("今日は", null, "ja"));
        assertFalse(SoundWithholdPolicy.isChineseLine("안녕하세요", null, "ko"));
        assertFalse(SoundWithholdPolicy.isChineseLine(null, null, "zh"));
    }
}
