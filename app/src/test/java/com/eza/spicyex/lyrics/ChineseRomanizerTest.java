package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Golden corpus for current Mandarin pinyin output (docs/ROMANIZATION_AUDIT_BACKLOG.md CN-T1).
 * Uses pinyin4j first-reading, no-tone, per-character mode — encodes behavior before CN-2.
 */
public class ChineseRomanizerTest {
    private static String pinyin(String line) {
        return SpicyJapaneseChineseProcessor.romanizeChineseLine(line, "pinyin");
    }

    @Test
    public void perCharacterPinyinWithoutTones() {
        assertEquals("zhong guo", pinyin("中国"));
        assertEquals("yin le", pinyin("音乐"));   // 乐 first reading is "le" (CN-2 polyphone gap: music should be "yue")
        assertEquals("kuai le", pinyin("快乐"));
    }

    @Test
    public void polyphoneUsesFirstReading() {
        // pinyin4j values[0] — documents current behavior, not contextual disambiguation.
        assertEquals("yin xing", pinyin("银行"));   // 行 first reading "xing" (CN-2 gap: bank should be "hang")
        assertEquals("zhong yao", pinyin("重要"));
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
    }

    @Test
    public void jyutpingToneNumbersStrippedWhenDisabled() {
        String withTones = SpicyJapaneseChineseProcessor.romanizeChineseLine("你好", "jyutping", true);
        String noTones = SpicyJapaneseChineseProcessor.romanizeChineseLine("你好", "jyutping", false);
        assertEquals(withTones.replaceAll("[1-6]", ""), noTones);   // off strips the trailing tone digits
        org.junit.Assert.assertNotEquals(withTones, noTones);       // and they differ (tones were present)
    }
}
