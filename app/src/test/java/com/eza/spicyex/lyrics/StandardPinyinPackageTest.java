package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.language.MandarinReadings;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Standard Pinyin stays on the plain platform package.
 *
 * <p>The owner ruling keeps Standard Pinyin on pinyin4j plus the frozen display phrase table. It
 * must not read the bundled g2pM pack, the jieba segmenter, the neutral-tone overlay, or the model
 * path. These assertions pin outputs that only the plain package can produce: the reader answers
 * 嗯 with the pinyin-data default and 谱 with a caron, so a regression that routes Standard Pinyin
 * through the reader fails here. The structural half of the guard lives in
 * {@code scripts/check_repo_invariants.py} (plain-pinyin-package).
 */
public class StandardPinyinPackageTest {
    private static String plain(String text, boolean tones) {
        return SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(text, tones);
    }

    @Test
    public void platformFallbackReadingIsVisible() {
        // pinyin4j keeps its own numbered fallback for syllabic nasals; the reader would return
        // n2/en4 and a reader-based tone overlay would letter it. Pinning ng4 proves the platform
        // package answers this position.
        assertEquals("ng4", plain("嗯", true));
    }

    @Test
    public void standaloneCharacterKeepsThePlatformToneMark() {
        // The phrase layer uses a caron, pinyin4j uses its own mark; the pack would use a caron.
        assertEquals("yuè pǔ", plain("乐谱", true));
        assertEquals("pŭ", plain("谱", true));
    }

    @Test
    public void phraseLayerStillResolvesPolyphones() {
        assertEquals("yín háng", plain("银行", true));
        assertEquals("yīn yuè", plain("音乐", true));
        assertEquals("chóng xīn", plain("重新", true));
    }

    @Test
    public void latinAndPunctuationPassThrough() {
        assertEquals("Azhong guo", plain("A中国", false));
        assertEquals("BTS4 zài 2024 nián", plain("BTS4 在 2024 年", true));
    }

    @Test
    public void plainRenderingNeverStartsTheReader() throws Exception {
        boolean readyBefore = MandarinReadings.isReady();
        for (int pass = 0; pass < 25; pass++) {
            plain("嗯乐谱银行音乐重新A中国", pass % 2 == 0);
        }
        // A plain pass may run after another test already loaded the reader; either way it must
        // not be the thing that starts or needs it.
        if (!readyBefore) {
            assertFalse("Standard Pinyin must not load the g2pM pack", MandarinReadings.isReady());
        }
    }
}
