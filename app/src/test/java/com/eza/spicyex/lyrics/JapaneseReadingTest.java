package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;

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
    public void renderPlanKeepsSplitJapaneseTimingOwnersUnique() {
        LyricsLine line = new LyricsLine();
        line.text = "だんだん剥がれてく";
        for (String text : Arrays.asList("だん", "だん", "剥", "がれて", "く")) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = text;
            segment.partOfWord = true;
            line.syllables.add(segment);
        }
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line.text, null);
        RenderPlan plan = ReadingPlanFactory.japanese(line, reading);
        assertNotNull(plan);
        assertEquals(5, plan.timedReadingUnits.size());
        assertEquals(reading.romaji, plan.joinedDisplayText);
    }

    @Test
    public void doyomekiKanjiOrthographyReadsDoyo() {
        // UniDic 2.1.2 has no surface entry for 響めき (どよめき; its lemma is
        // 響動めき, usually written kana-only) and falls back to 響(ひびき)+めき.
        // Lexical override + めく-suffix join must produce doyomeki as one word.
        assertEquals("doyomeki kirameki to kimi mo", romaji("響めき煌めきと君も"));
        assertEquals("doyomeku", romaji("響めく"));
        assertTrue(furigana("響めき").contains("響=どよ"));
        // 響 outside the めく context keeps its dictionary readings.
        assertEquals("hibiku", romaji("響く"));
        assertEquals("hibiki", romaji("響き"));
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
    public void irisNanDemoUsesFullLineContextAcrossTimingSplit() {
        assertEquals("nan", romaji("何"));
        String source = "パチモンでもいい何でもいい";
        assertEquals("pachi mon de mo ii nan de mo ii", romaji(source));

        LyricsLine line = new LyricsLine();
        line.text = source;
        for (String text : Arrays.asList("パチモン", "でも", "いい", "何", "でも", "いい")) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = text;
            segment.partOfWord = true;
            line.syllables.add(segment);
        }
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line.text, null);
        RenderPlan plan = ReadingPlanFactory.japanese(line, reading);
        assertNotNull(plan);
        assertEquals(reading.romaji, plan.joinedDisplayText);
        assertEquals(6, plan.timedReadingUnits.size());
        assertEquals(" nan", plan.timedReadingUnits.get(3).text);
        assertEquals(" de mo", plan.timedReadingUnits.get(4).text);
    }

    @Test
    public void irisTimingFragmentsPreserveSemanticRomajiSpaces() {
        String source = "ダーリンベイビーダーリン 半端なくラブ!ときらめき浮き足立つフィロソフィ";
        LyricsLine line = new LyricsLine();
        line.text = source;
        for (String text : Arrays.asList("ダー", "リン", "ベイビー", "ダー", "リン ", "半", "端", "なく", "ラブ!と", "きらめき", "浮き", "足", "立つ", "フィロソ", "フィ")) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = text;
            segment.partOfWord = !text.matches(".*\\s$");
            line.syllables.add(segment);
        }
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(source, null);
        RenderPlan plan = ReadingPlanFactory.japanese(line, reading);
        assertNotNull(plan);
        assertEquals(reading.romaji, plan.joinedDisplayText);
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
        assertEquals("ippo", romaji("一歩"));
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
    public void verbReadingSurvivesProviderSpacing() {
        assertEquals("koroshita", romaji("殺した"));
        assertEquals("koroshita", romaji("殺 した"));
        assertEquals("hibiku", romaji("響く"));
        assertEquals("hibiku", romaji("響 く"));
    }

    @Test
    public void deshouStaysSeparateWord() {
        assertEquals("sou omou deshou", romaji("そう思うでしょう"));
    }

    @Test
    public void furiganaSpansWholeKanjiRuns() {
        // Dictionary-backed ruby: irregular entries may be whole, normal compounds may split.
        assertEquals(Arrays.asList("時計=とけい"), furigana("時計"));
        assertEquals(Arrays.asList("世=せ", "界=かい"), furigana("世界"));
        assertEquals(Arrays.asList("今年=ことし"), furigana("今年"));
        assertEquals(Arrays.asList("今日=きょう"), furigana("今日"));
        assertEquals(Arrays.asList("残=ざん", "念=ねん"), furigana("残念"));
        // Okurigana anchor the kanji-run reading.
        assertEquals(Arrays.asList("生=い"), furigana("生きて"));
        assertTrue(furigana("感じたままに描く").contains("感=かん"));
        assertTrue(furigana("感じたままに描く").contains("描=えが"));
    }

    @Test
    public void dictionaryFirstFuriganaRules() {
        assertEquals(Arrays.asList("大人=おとな"), furigana("大人"));
        assertEquals(Arrays.asList("大人=おとな", "買=が"), furigana("大人買い"));
        assertEquals(Arrays.asList("大=だい", "事=じ"), furigana("大事"));
        assertEquals(Arrays.asList("代=か", "代=が"), furigana("代わる代わる"));
        assertEquals(Arrays.asList("最=さい", "後=ご"), furigana("最後"));
        assertEquals(Arrays.asList("愛=あい"), furigana("ありがとう愛してた"));
    }

    @Test
    public void realLyricFuriganaUsesPhraseAndOkuriganaRules() {
        assertEquals(Arrays.asList("長=なが", "長=なが"), furigana("長い長い Our story"));
        assertEquals(Arrays.asList("最=さい", "後=ご"), furigana("最後になりそうだね"));
        assertEquals(Arrays.asList("全=ぜん", "宇=う", "宙=ちゅう", "全=ぜん", "世=せ", "界=かい"), furigana("全宇宙全世界"));
    }

    @Test
    public void furiganaOffsetsSurviveProviderSpacing() {
        List<String> spacedYear = furigana("今 年 も早いね");
        assertTrue(spacedYear.contains("今 年=ことし"));
        assertFalse(spacedYear.contains("今 =ことし"));

        assertEquals(Arrays.asList("残=ざん", "念=ねん"), furigana("残 念"));
    }

    @Test
    public void wordLevelFuriganaKeepsCompoundReadingAtSegmentStart() {
        SpicyJapaneseChineseProcessor.FuriganaSegment segment =
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "ことし");
        assertTrue(FuriganaText.segmentStartsInWord(segment, 0, 1));
        assertFalse(FuriganaText.segmentStartsInWord(segment, 1, 2));
    }

    @Test
    public void furiganaAndRomajiShareOneReading() {
        // The old layer could emit romaji "momiji" with furigana こうよう. Whatever
        // reading wins must drive both renderings.
        SpicyJapaneseChineseProcessor.JapaneseReading r =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine("紅葉", null);
        assertNotNull(r);
        assertFalse(r.furigana.isEmpty());
        StringBuilder kana = new StringBuilder();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : r.furigana) kana.append(segment.reading);
        assertEquals("kouyou", r.romaji);
        assertEquals("こうよう", kana.toString());
    }

    @Test
    public void providerFuriganaDrivesRomaji() {
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> provider = new ArrayList<>();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "こうよう"));
        assertEquals("kouyou", SpicyJapaneseChineseProcessor.romanizeJapaneseLineFromFurigana("紅葉", provider));
        provider.clear();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "もみじ"));
        // Provider ruby is accepted only when it reconstructs UniDic's token reading.
        assertEquals("kouyou", SpicyJapaneseChineseProcessor.romanizeJapaneseLineFromFurigana("紅葉", provider));
    }

    @Test
    public void providerFuriganaRequiresCompleteValidTokenCoverage() {
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> provider = new ArrayList<>();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 1, "こう"));
        assertEquals("kouyou", SpicyJapaneseChineseProcessor.romanizeJapaneseLineFromFurigana("紅葉", provider));

        provider.clear();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "こうよう"));
        SpicyJapaneseChineseProcessor.JapaneseDebugSnapshot accepted =
                SpicyJapaneseChineseProcessor.debugJapaneseSnapshot("紅葉", provider);
        assertEquals("providerRubyValidated", accepted.tokens.get(0).readingReason);
        SpicyJapaneseChineseProcessor.JapaneseReading acceptedReading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLineWithProviderFurigana("紅葉", provider);
        assertEquals(Arrays.asList("紅葉=こうよう"), furiganaFrom(acceptedReading));

        provider.clear();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 1, "こう"));
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "こうよう"));
        SpicyJapaneseChineseProcessor.JapaneseDebugSnapshot overlap =
                SpicyJapaneseChineseProcessor.debugJapaneseSnapshot("紅葉", provider);
        assertEquals("unidicReadingOfRecord", overlap.tokens.get(0).readingReason);
    }

    @Test
    public void debugSnapshotExposesDeterministicOracleStages() {
        SpicyJapaneseChineseProcessor.JapaneseDebugSnapshot snapshot =
                SpicyJapaneseChineseProcessor.debugJapaneseSnapshot("殺 した", null);
        assertEquals("殺 した", snapshot.displayText);
        assertEquals("殺した", snapshot.analysisText);
        assertEquals(3, snapshot.analysisToDisplayUtf16.length);
        assertEquals(0, snapshot.analysisToDisplayUtf16[0]);
        assertEquals(2, snapshot.analysisToDisplayUtf16[1]);
        assertEquals("koroshita", snapshot.romaji);
        assertFalse(snapshot.tokens.isEmpty());
        assertEquals("殺し", snapshot.tokens.get(0).surface);
        assertEquals("unidicReadingOfRecord", snapshot.tokens.get(0).readingReason);
    }

    @Test
    public void realCachedJapaneseLyricLineKeepsJapanesePunctuation() {
        assertEquals("suki、kirai", romaji("好き、嫌い"));
    }

    @Test
    public void latinTokensInsideJapaneseLineStayVerbatim() {
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine("無二になれ Let's go!", null);
        assertNotNull(reading);
        assertEquals("muni ni nare Let's go!", reading.romaji);
        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : reading.furigana) {
            String target = reading.sourceText.substring(segment.start, Math.min(segment.end, reading.sourceText.length()));
            assertFalse(target.contains("Let's"));
            assertFalse(target.contains("go"));
        }

        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> provider = new ArrayList<>();
        provider.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "むに"));
        SpicyJapaneseChineseProcessor.JapaneseReading providerReading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLineWithProviderFurigana("無二になれ Let's go!", provider);
        assertNotNull(providerReading);
        assertEquals("muni ni nare Let's go!", providerReading.romaji);
    }

    @Test
    public void okuriganaAnchorUsesTrailingKana() {
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine("大嫌い、好き", null);
        assertNotNull(reading);
        assertEquals("daikirai、suki", reading.romaji);
        assertTrue(furiganaFrom(reading).contains("大=だい"));
        assertTrue(furiganaFrom(reading).contains("嫌=きら"));
        assertFalse(furiganaFrom(reading).contains("大嫌=だ"));
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
        assertEquals(Arrays.asList("hontou", "no", "koe", "wo", "hibikasete", "", "", "yo"), parts);

        assertEquals(Arrays.asList("koroshita", ""), SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(
                "殺 した", Arrays.asList("殺", "した")));
        assertEquals(Arrays.asList("hibiku", ""), SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(
                "響 く", Arrays.asList("響", "く")));
    }

    @Test
    public void localSegmentRomanizationUsesLineAnalysisForProviderChunks() {
        LyricsLine line = new LyricsLine();
        line.text = "今 年 も 早い ね";
        line.syllables.add(segment("今"));
        line.syllables.add(segment("年"));
        line.syllables.add(segment("も"));
        line.syllables.add(segment("早い"));
        line.syllables.add(segment("ね"));

        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("pinyin", "Off", false, "Off", false), null, line, line.text);

        assertEquals("kotoshi", line.syllables.get(0).romanizedText);
        assertEquals("", line.syllables.get(1).romanizedText);
        assertEquals("mo", line.syllables.get(2).romanizedText);
        assertEquals("hayai", line.syllables.get(3).romanizedText);
        assertEquals("ne", line.syllables.get(4).romanizedText);
    }

    private static SyllableSegment segment(String text) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        return segment;
    }

    private static List<String> furiganaFrom(SpicyJapaneseChineseProcessor.JapaneseReading r) {
        ArrayList<String> out = new ArrayList<>();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment f : r.furigana) {
            out.add(r.sourceText.substring(f.start, Math.min(f.end, r.sourceText.length())) + "=" + f.reading);
        }
        return out;
    }
}
