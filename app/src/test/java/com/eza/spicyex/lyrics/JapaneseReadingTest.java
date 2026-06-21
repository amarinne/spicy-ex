package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Golden corpus for the Japanese reading pipeline (docs/JAPANESE_NLP_AUDIT_AND_PLAN.md).
 *
 * Every dictionary or rule change must be diffed against this corpus. Cases come
 * from real lyric lines plus the regressions found in the 2026-06-12 audit of the
 * old override layer (tanaka kimi, tou nen go, even-split furigana, ー handling).
 */
public class JapaneseReadingTest {
    private static String romaji(String line) {
        SpicyJapaneseChineseProcessor.JapaneseReading r =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line, null);
        return r == null ? null : r.romaji;
    }

    private static List<String> furigana(String line) {
        SpicyJapaneseChineseProcessor.JapaneseReading r =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line, null);
        assertNotNull(r);
        ArrayList<String> out = new ArrayList<>();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment f : r.furigana) {
            out.add(r.sourceText.substring(f.start, Math.min(f.end, r.sourceText.length())) + "=" + f.reading);
        }
        return out;
    }

    @Test
    public void kanjiUsesJapaneseReadingNotChinesePinyin() {
        // 描 must read as the Japanese "egaku", never the Chinese pinyin "miao".
        assertEquals("egaku", romaji("描く"));
        assertEquals("kanjita mama ni egaku", romaji("感じたままに描く"));
    }

    @Test
    public void topicParticleHaReadsAsWa() {
        assertEquals("kore wa himitsu", romaji("これは秘密"));
        assertEquals("watashi wa", romaji("私は"));
    }

    @Test
    public void objectAndDirectionParticles() {
        // を → "wo", verb chain stays intact.
        assertEquals("hontou no koe wo hibikasete yo", romaji("本当の声を響かせてよ"));
        assertEquals("toukyou e ikou", romaji("東京へ行こう"));
    }

    @Test
    public void contextualReadingsResolvedByDictionaryLattice() {
        assertEquals("san nin", romaji("三人"));
        assertEquals("kono kata", romaji("この方"));
        assertEquals("hajime no hou e", romaji("初めの方へ"));
        assertEquals("ikite", romaji("生きて"));
        assertEquals("hitori de ikiteikenai", romaji("一人で生きていけない"));
        assertEquals("nan ji desu ka", romaji("何時ですか")); // short-unit split, consistent with "san nin"
    }

    @Test
    public void lexicalOverridesStayPosGuarded() {
        // 私 as pronoun reads watashi (UniDic default is the formal watakushi).
        assertEquals("watashi wa utau", romaji("私は歌う"));
        // Rendaku plural-person suffix after pronouns.
        assertEquals("anata gata", romaji("貴方方"));
    }

    @Test
    public void oldOverrideLayerRegressionsStayFixed() {
        // The pre-refactor jukujikun map clobbered correct dictionary readings.
        assertEquals("tanaka kun", romaji("田中君"));
        assertEquals("kimi", romaji("君"));
        assertEquals("kimi no na wa", romaji("君の名は"));
        assertEquals("juu nen go", romaji("十年後"));
        assertEquals("hitori", romaji("一人"));
        assertEquals("futari", romaji("二人"));
        assertEquals("ikkai", romaji("一回"));
    }

    @Test
    public void longVowelsUseOrthographicSpelling() {
        assertEquals("sensei", romaji("先生"));        // センセー pron → せんせい
        assertEquals("hontou", romaji("本当"));        // ホントー pron → ほんとう
        assertEquals("ookina sora", romaji("大きな空")); // おお word, not おう
        assertEquals("nee", romaji("ねえ"));
        assertEquals("mou ii yo", romaji("もーいいよ")); // ー in kana surface no longer leaks through
        assertEquals("suupaasutaa", romaji("スーパースター")); // loanword ー extends the vowel
    }

    @Test
    public void crossTokenSokuonGeminates() {
        assertEquals("itte", romaji("言って"));
        assertEquals("matteru", romaji("待ってる"));
        assertEquals("itteshimatta", romaji("行ってしまった"));
    }

    @Test
    public void deshouStaysSeparateWord() {
        assertEquals("sou omou deshou", romaji("そう思うでしょう"));
    }

    @Test
    public void furiganaSpansWholeKanjiRuns() {
        // Whole-compound (jukugo) ruby — never even-split guesses like 世(せか)界(い).
        assertEquals(Arrays.asList("時計=とけい"), furigana("時計"));
        assertEquals(Arrays.asList("世界=せかい"), furigana("世界"));
        // Okurigana anchor the kanji-run reading.
        assertEquals(Arrays.asList("生=い"), furigana("生きて"));
        assertTrue(furigana("感じたままに描く").contains("感=かん"));
        assertTrue(furigana("感じたままに描く").contains("描=えが"));
    }

    @Test
    public void furiganaAndRomajiShareOneReading() {
        // The old layer could emit romaji "momiji" with furigana こうよう. Whatever
        // reading wins must drive both renderings.
        SpicyJapaneseChineseProcessor.JapaneseReading r =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine("紅葉", null);
        assertNotNull(r);
        assertFalse(r.furigana.isEmpty());
        String kana = r.furigana.get(0).reading;
        assertEquals(kana.equals("こうよう") ? "kouyou" : "momiji", r.romaji);
    }

    @Test
    public void providerFuriganaDrivesRomaji() {
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> provider = new ArrayList<>();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "こうよう"));
        assertEquals("kouyou", SpicyJapaneseChineseProcessor.romanizeJapaneseLineFromFurigana("紅葉", provider));
        provider.clear();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "もみじ"));
        assertEquals("momiji", SpicyJapaneseChineseProcessor.romanizeJapaneseLineFromFurigana("紅葉", provider));
    }

    @Test
    public void localPronounCorrectionStillWinsOverProviderFurigana() {
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> provider = new ArrayList<>();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 1, "くん"));
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLineWithProviderFurigana("君", provider);
        assertNotNull(reading);
        assertEquals("kimi", reading.romaji);
        assertEquals(Arrays.asList("君=きみ"), furiganaFrom(reading));
    }

    @Test
    public void syllableRomanizationUsesFullLineContext() {
        List<String> parts = SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(
                "本当の声を響かせてよ",
                Arrays.asList("本当", "の", "声", "を", "響か", "せ", "て", "よ"));
        assertEquals(Arrays.asList("hontou", "no", "koe", "wo", "hibika", "se", "te", "yo"), parts);
    }

    private static List<String> furiganaFrom(SpicyJapaneseChineseProcessor.JapaneseReading r) {
        ArrayList<String> out = new ArrayList<>();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment f : r.furigana) {
            out.add(r.sourceText.substring(f.start, Math.min(f.end, r.sourceText.length())) + "=" + f.reading);
        }
        return out;
    }
}
