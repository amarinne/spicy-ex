package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ChineseSongGuessTest {
    @Test
    public void jyutpingKeysMergeNasalCodasAndStopsButKeepNgAndK() {
        assertEquals("aN", ChineseSongGuess.jyutpingKey("sam1"));
        assertEquals("aN", ChineseSongGuess.jyutpingKey("san1"));
        assertEquals("aT", ChineseSongGuess.jyutpingKey("sap1"));
        assertEquals("aT", ChineseSongGuess.jyutpingKey("sat1"));
        assertEquals("ang", ChineseSongGuess.jyutpingKey("sang1"));
        assertEquals("ak", ChineseSongGuess.jyutpingKey("sak1"));
        assertEquals("iN", ChineseSongGuess.jyutpingKey("jyun4"));
        assertEquals("iT", ChineseSongGuess.jyutpingKey("jyut6"));
        assertEquals("iN", ChineseSongGuess.jyutpingKey("cyun1"));
        assertEquals("yu", ChineseSongGuess.jyutpingKey("jyu4"));
        assertEquals("oi", ChineseSongGuess.jyutpingKey("ngoi3"));
        assertEquals("aa", ChineseSongGuess.jyutpingKey("gwaa1"));
        // A syllabic nasal has no initial to strip and keeps its letter before grouping.
        assertEquals("N", ChineseSongGuess.jyutpingKey("m4"));
        assertEquals("ng", ChineseSongGuess.jyutpingKey("ng5"));
        assertEquals("ei", ChineseSongGuess.jyutpingKey("lei4 lei5"));
        assertNull(ChineseSongGuess.jyutpingKey(null));
    }

    @Test
    public void pinyinKeysFollowPinyinProFinalsAndTheMandarinRhymeGroups() {
        assertEquals("e", ChineseSongGuess.pinyinKey("xue"));
        assertEquals("e", ChineseSongGuess.pinyinKey("ye"));
        assertEquals("u", ChineseSongGuess.pinyinKey("yu"));
        assertEquals("i", ChineseSongGuess.pinyinKey("ju"));
        assertEquals("i", ChineseSongGuess.pinyinKey("lü"));
        assertEquals("i", ChineseSongGuess.pinyinKey("lv"));
        assertEquals("e", ChineseSongGuess.pinyinKey("wo"));
        assertEquals("e", ChineseSongGuess.pinyinKey("duo"));
        assertEquals("e", ChineseSongGuess.pinyinKey("mo"));
        assertEquals("ou", ChineseSongGuess.pinyinKey("jiu"));
        assertEquals("ei", ChineseSongGuess.pinyinKey("hui"));
        assertEquals("ei", ChineseSongGuess.pinyinKey("wei"));
        assertEquals("eng", ChineseSongGuess.pinyinKey("dong"));
        assertEquals("eng", ChineseSongGuess.pinyinKey("xiong"));
        assertEquals("eng", ChineseSongGuess.pinyinKey("ying"));
        assertEquals("en", ChineseSongGuess.pinyinKey("xun"));
        assertEquals("an", ChineseSongGuess.pinyinKey("quan"));
        assertEquals("ang", ChineseSongGuess.pinyinKey("zhuang"));
        assertEquals("i", ChineseSongGuess.pinyinKey("shi"));
        assertEquals("er", ChineseSongGuess.pinyinKey("er"));
        assertNull(ChineseSongGuess.pinyinKey("n"));
    }

    @Test
    public void toneClassesFollowThePitchOrder() {
        assertEquals(0, ChineseSongGuess.toneClass("sam4"));
        assertEquals(1, ChineseSongGuess.toneClass("sam6"));
        assertEquals(2, ChineseSongGuess.toneClass("sam3"));
        assertEquals(2, ChineseSongGuess.toneClass("sam5"));
        assertEquals(3, ChineseSongGuess.toneClass("sam1"));
        assertEquals(3, ChineseSongGuess.toneClass("sam2"));
        assertEquals(-1, ChineseSongGuess.toneClass("sam"));
    }

    @Test
    public void cleanLinesDropsTimestampsCreditsRepeatsAndShortLines() {
        assertEquals(Arrays.asList("你好嗎", "我很好"), ChineseSongGuess.cleanLines(Arrays.asList(
                "[00:01.00]你好嗎", "作詞：某人", "你好嗎", " 我很好 ", "好", "", "hello")));
    }

    @Test
    public void fewerThanTwelveLinesStayUndecidedUnlessTwoMarkersAppear() {
        List<String> short11 = new ArrayList<>();
        for (int i = 0; i < 11; i++) short11.add("我們一起走過第" + (char) ('一' + i) + "天");
        assertNull(ChineseSongGuess.guess(short11).language);
        assertNull(ChineseSongGuess.guess(Arrays.asList("你唔好走", "我好掛住你")).language);
        assertEquals(ChineseSongGuess.YUE, ChineseSongGuess.guess(Arrays.asList(
                "你唔好走", "佢話你知")).language);
        assertEquals(2, ChineseSongGuess.guess(Arrays.asList("你唔好走", "佢話你知 唔")).cantoneseMarkers);
    }

    @Test
    public void autoModesResolvePerSongAndAManualChoiceWins() {
        assertEquals("jyutping", ChineseAutoMode.resolve("auto", ChineseSongGuess.YUE));
        assertEquals("pinyin", ChineseAutoMode.resolve("auto", ChineseSongGuess.CMN));
        assertEquals("pinyin", ChineseAutoMode.resolve("auto", (String) null));
        assertEquals("jyutping-eza", ChineseAutoMode.resolve("auto-eza", ChineseSongGuess.YUE));
        assertEquals("pinyin-eza", ChineseAutoMode.resolve("auto-eza", (String) null));
        assertEquals("jyutping", ChineseAutoMode.resolve("jyutping", (LyricsDocument) null));

        LyricsDocument cantonese = document("autoModeTrack", "你唔好走", "佢話你知");
        assertEquals("jyutping-eza", ChineseAutoMode.resolve("auto-eza", cantonese));
        ChineseAutoMode.setOverride("autoModeTrack", "pinyin");
        try {
            assertEquals("pinyin-eza", ChineseAutoMode.resolve("auto-eza", cantonese));
            assertEquals("pinyin", RomanizationOptionsFor.mode("auto", cantonese));
        } finally {
            ChineseAutoMode.setOverride("autoModeTrack", null);
        }
        assertEquals("jyutping", ChineseAutoMode.resolve("auto", cantonese));
        // An undecided document reads as Pinyin.
        assertEquals("pinyin", ChineseAutoMode.resolve("auto", document("shortTrack", "今天天氣很好")));
    }

    private static LyricsDocument document(String trackId, String... lines) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackId;
        for (String text : lines) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            doc.lines.add(line);
        }
        return doc;
    }

    /** The options path used by every surface. */
    private static final class RomanizationOptionsFor {
        static String mode(String configured, LyricsDocument doc) {
            return new RomanizationOptions(configured, "Off", false, "Off", false).forDocument(doc).chineseMode;
        }
    }
}
