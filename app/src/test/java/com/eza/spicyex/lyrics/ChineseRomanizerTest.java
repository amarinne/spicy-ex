package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.eza.spicyex.SpotifyPlusConfig;

import java.util.Collections;

import org.junit.Test;

/**
 * Golden corpus for Mandarin pinyin output (docs/ROMANIZATION_AUDIT_BACKLOG.md CN-T1/CN-2).
 * Phrase dictionary covers common polyphones before pinyin4j per-character fallback.
 */
public class ChineseRomanizerTest {
    private static String pinyin(String line) {
        return SpicyJapaneseChineseProcessor.romanizeChineseLine(line, "pinyin");
    }

    @Test
    public void perCharacterPinyinWithoutTones() {
        assertEquals("zhong guo", pinyin("中国"));
        assertEquals("yin yue", pinyin("音乐"));
        assertEquals("kuai le", pinyin("快乐"));
    }

    @Test
    public void polyphonePhrasesOverrideFirstReading() {
        assertEquals("yin hang", pinyin("银行"));
        assertEquals("zhong yao", pinyin("重要"));
        assertEquals("shui jiao", pinyin("睡觉"));
        assertEquals("hai you", pinyin("还有"));
    }

    @Test
    public void latinAndPunctuationPassThrough() {
        assertEquals("Azhong guo", pinyin("A中国"));   // no space inserted between Latin and pinyin
        assertEquals("zhong guo rock'n'roll", pinyin("中国 rock'n'roll"));
    }

    @Test
    public void pinyinToneMarksWhenEnabled() {
        assertEquals("zhōng guó", SpicyJapaneseChineseProcessor.romanizeChineseLine("中国", "pinyin", true));
        assertEquals("zhong guo", SpicyJapaneseChineseProcessor.romanizeChineseLine("中国", "pinyin", false));
        assertEquals("yīn yuè", SpicyJapaneseChineseProcessor.romanizeChineseLine("音乐", "pinyin", true));
        assertEquals("yin yue", SpicyJapaneseChineseProcessor.romanizeChineseLine("音乐", "pinyin", false));
    }

    @Test
    public void jyutpingToneNumbersStrippedWhenDisabled() {
        String withTones = SpicyJapaneseChineseProcessor.romanizeChineseLine("你好", "jyutping", true);
        String noTones = SpicyJapaneseChineseProcessor.romanizeChineseLine("你好", "jyutping", false);
        assertEquals(withTones.replaceAll("[1-6]", ""), noTones);   // off strips the trailing tone digits
        org.junit.Assert.assertNotEquals(withTones, noTones);       // and they differ (tones were present)
    }

    @Test
    public void chineseLinesAreNotDisplayedAsJapanese() {
        AppliedLine line = new AppliedLine();
        line.text = "中国";
        line.romanizedText = "zhong guo";
        assertFalse(LyricsDisplayMode.isJapaneseLine(line));
    }

    @Test
    public void chineseRomanizationClearsStaleJapaneseReadingInMixedJapaneseChineseDocument() {
        LyricsDocument doc = new LyricsDocument();
        doc.detectedScripts.add(SpicyTextDetection.Script.JAPANESE);
        LyricsLine line = new LyricsLine();
        line.text = "中国";
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                "中国", "naka kuni", Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 1, "なか")));

        String romanized = LyricsLocalRomanizer.romanizeLine(
                new RomanizationOptions(SpotifyPlusConfig.CHINESE_MODE_PINYIN, "Off", false, "Off", false),
                doc,
                line,
                line.text);

        assertEquals("zhong guo", romanized);
        assertFalse(line.japaneseReading != null && line.japaneseReading.furigana != null
                && !line.japaneseReading.furigana.isEmpty());
    }
}
