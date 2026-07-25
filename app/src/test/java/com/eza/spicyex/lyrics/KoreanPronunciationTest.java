package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Golden corpus for the "follow sound" Korean pronunciation mode (KR-1, jamo-aware G2P).
 * Targets canonical Revised-Romanization pronunciation outputs, contrasted with the literal
 * "follow spelling" mode in {@link KoreanRomanizerTest}.
 */
public class KoreanPronunciationTest {
    private static String sound(String s) {
        return SpicyKoreanG2P.romanize(s);
    }

    @Test
    public void liaisonBeforeNullOnset() {
        assertEquals("eumak", sound("음악"));        // ㅁ liaisons
        assertEquals("hangugeo", sound("한국어"));   // ㄱ liaisons (vs literal "hangukeo")
        assertEquals("gangaji", sound("강아지"));    // ㅇ(ng) stays
    }

    @Test
    public void palatalization() {
        assertEquals("haedoji", sound("해돋이"));    // ㄷ + 이 → ji
        assertEquals("gachi", sound("같이"));        // ㅌ + 이 → chi
    }

    @Test
    public void obstruentNasalization() {
        assertEquals("baengma", sound("백마"));      // ㄱ + ㅁ → ng
        assertEquals("gungmul", sound("국물"));      // ㄱ + ㅁ → ng
        assertEquals("dongnip", sound("독립"));      // ㄱ…ㄹ → ng…n
    }

    @Test
    public void lateralizationAndRNasalization() {
        assertEquals("silla", sound("신라"));        // ㄴ + ㄹ → ll
        assertEquals("jongno", sound("종로"));       // ㅇ + ㄹ → ng…n
    }

    @Test
    public void hAspirationAndElision() {
        assertEquals("joko", sound("좋고"));         // ㅎ + ㄱ → k
        assertEquals("joa", sound("좋아"));          // ㅎ + vowel → elided
        assertEquals("anko", sound("않고"));         // ㄶ + ㄱ → ㄴ + ㅋ
    }

    @Test
    public void latinPassThroughBreaksAdjacency() {
        assertEquals("eumak rock", sound("음악 rock"));
    }

    @Test
    public void authoredSpacingIsNotRewrittenForReadability() {
        assertEquals("annyeonghaseyo", sound("안녕하세요"));
        assertEquals("saranghaeyo", sound("사랑해요"));
        assertEquals("bogosipeo", sound("보고싶어"));
    }

    @Test
    public void realCorpusKoreanG2PContext() {
        assertEquals("sumgyeojin tteusi nan gunggeumhaeseo", sound("숨겨진 뜻이 난 궁금해서"));
        assertEquals("ireobeorin geu nunbit", sound("잃어버린 그 눈빛"));
        assertEquals("noajulge", sound("놓아줄게"));
        assertEquals("eopjji", sound("없지"));
        assertEquals("eopsseo", sound("없어"));
        assertEquals("eopsseul geoya", sound("없을 거야"));
    }

    @Test
    public void fullLinePiecesPreserveSplitChunkPronunciation() {
        assertEquals(Arrays.asList("han", "gu", "geo"), SpicyKoreanG2P.romanizeSyllablePieces("한국어"));
        assertEquals(Arrays.asList("baeng", "ma"), SpicyKoreanG2P.romanizeSyllablePieces("백마"));
        assertEquals(Arrays.asList("an", "nyeong", "ha", "se", "yo"), SpicyKoreanG2P.romanizeReadablePieces("안녕하세요"));
    }

    @Test
    public void localRomanizerUsesFullLineContextForSyllableChunks() {
        LyricsDocument doc = new LyricsDocument();
        doc.language = "ko";
        doc.detectedScripts.add(SpicyTextDetection.Script.KOREAN);
        LyricsLine line = new LyricsLine();
        line.text = "한국어";
        line.syllables.add(seg("한"));
        line.syllables.add(seg("국"));
        line.syllables.add(seg("어"));

        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("", KoreanDisplayMode.RR_PRONUNCIATION.value, false, "Russian", false),
                doc,
                line,
                line.text);

        assertEquals("han", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("-gu", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("geo", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());
    }

    @Test
    public void localRomanizerMapsKoreanDisplayModesToSyncedSpans() {
        LyricsDocument doc = koreanDoc();
        LyricsLine line = line("한국어", "한", "국", "어");

        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("", KoreanDisplayMode.WORD_TRANSLIT.value, false, "Russian", false),
                doc, line, line.text);

        assertEquals("han-", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("guk-", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("eo", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());

        line = line("한국어", "한", "국", "어");
        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false),
                doc, line, line.text);

        assertEquals("han", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("gu", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("geo", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());
    }

    @Test
    public void koreanTtmlFragmentsUseFullLineSpacingContext() {
        LyricsDocument doc = koreanDoc();
        LyricsLine line = line("그대 아무런 말도 하지 마요", "그대", "아무런", "말", "도", "하", "지", "마", "요");

        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false),
                doc, line, line.text);

        String[] expected = {"gưdê", "amuron", "mal", "dô", "ha", "ji", "ma", "yô"};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], line.readingRenderPlan.timedReadingUnits.get(index).text.trim());
        }
    }

    @Test
    public void koreanUiVowelTransformMatchesDesktopDisplay() {
        assertEquals("bamê muniga a", display("밤의 무늬가 아", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("gưrokê urinưn hanaê", display("그렇게 우리는 하나의", KoreanDisplayMode.VN_PRONUNCIATION));

        LyricsLine line = line("밤의 무늬가 아", "밤의", "무늬가", "아");
        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                new RomanizationOptions("", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false),
                koreanDoc(), line, line.text);

        assertEquals("bamê", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("muniga", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("a", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());
    }

    @Test
    public void koreanSyllableBoundariesRestoreSpacingBeforeG2p() {
        LyricsLine line = line("더 이상 기댈 곳은 필요 없어", "더", "이상", "기댈", "곳은", "필요", "없어");
        LyricsDocument doc = koreanDoc();
        RomanizationOptions opts = new RomanizationOptions("", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false);

        assertEquals("do isang gidêl gôsưn piryô opsso", LyricsLocalRomanizer.romanizeLine(opts, doc, line, line.text));

        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, doc, line, line.text);

        assertEquals("do", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("isang", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("gidêl", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());
        assertEquals("gôsưn", line.readingRenderPlan.timedReadingUnits.get(3).text.trim());
        assertEquals("piryô", line.readingRenderPlan.timedReadingUnits.get(4).text.trim());
        assertEquals("opsso", line.readingRenderPlan.timedReadingUnits.get(5).text.trim());
        for (SyllableSegment segment : line.syllables) assertEquals("", segment.romanizedText);
    }

    @Test
    public void koreanWordLevelSpansRecoverSpacesWhenPartOfWordIsUnreliable() {
        LyricsLine mixed = lineWithPartFlags("미련이 아냐, 그저 Hard to see it", true,
                "미련이", "아냐,", "그저", "Hard", "to", "see", "it");
        assertEquals("miryoni anya, gưjo Hard to see it",
                LyricsLocalRomanizer.romanizeLine(
                        new RomanizationOptions("", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false),
                        koreanDoc(), mixed, mixed.text));

        LyricsLine korean = lineWithPartFlags("처음부터 잘못됐단 걸", true,
                "처음부터", "잘못됐단", "걸");
        assertEquals("choưmbuto jalmôt-ttwêt-ttan gol",
                LyricsLocalRomanizer.romanizeLine(
                        new RomanizationOptions("", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false),
                        koreanDoc(), korean, korean.text));
    }

    @Test
    public void koreanUnreliablePartFlagsKeepRomanizedWordSpaces() {
        LyricsLine line = lineWithPartFlags("점점 내 모습이", true, "점점", "내", "모습이");
        RomanizationOptions opts = new RomanizationOptions("", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false);

        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, koreanDoc(), line, line.text);

        assertEquals("jeomjeom", line.readingRenderPlan.timedReadingUnits.get(0).text.trim());
        assertEquals("nae", line.readingRenderPlan.timedReadingUnits.get(1).text.trim());
        assertEquals("moseubi", line.readingRenderPlan.timedReadingUnits.get(2).text.trim());
    }

    @Test
    public void koreanTtmlSpanWhitespaceKeepsRomanizedWordSpaces() {
        LyricsLine line = line("점점 내 모습이", "점", "점", "내", "모", "습", "이");
        line.syllables.get(0).sourceText = "점";
        line.syllables.get(1).sourceText = "점 ";
        line.syllables.get(2).sourceText = "내 ";
        line.syllables.get(3).sourceText = "모";
        line.syllables.get(4).sourceText = "습";
        line.syllables.get(5).sourceText = "이";
        line.syllables.get(0).providerPartOfWord = true;
        line.syllables.get(1).providerPartOfWord = false;
        line.syllables.get(2).providerPartOfWord = false;
        line.syllables.get(3).providerPartOfWord = true;
        line.syllables.get(4).providerPartOfWord = true;
        line.syllables.get(5).providerPartOfWord = true;
        RomanizationOptions opts = new RomanizationOptions("", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false);

        assertEquals("점점 내 모습이", SpicyRomanizer.buildKoreanSyllableSource(line.syllables).text);

        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, koreanDoc(), line, line.text);

        String[] expected = {"jeom", "jeom", "nae", "mo", "seu", "bi"};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], line.readingRenderPlan.timedReadingUnits.get(index).text.trim());
        }
    }

    @Test
    public void completeProviderLineOwnsParticlesAndEndings() {
        LyricsLine line = lineWithPartFlags("그대 아무런 말도 하지 마요", false,
                "그대", "아무런", "말", "도", "하", "지", "마", "요");
        assertEquals("geudae amureon maldo haji mayo",
                LyricsLocalRomanizer.romanizeLine(
                        new RomanizationOptions("", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false),
                        koreanDoc(), line, line.text));
    }

    @Test
    public void koreanHeartBurnPreservesAuthoredBoundariesAcrossTransports() {
        assertAuthoredLine(new String[]{"그대 ", "아무런 ", "말", "도 ", "하", "지 ", "마", "요"},
                "그대 아무런 말도 하지 마요", "geudae amureon maldo haji mayo");
        assertAuthoredLine(new String[]{"이 ", "맘은 ", "여전", "히 ", "그대", "로", "예", "요"},
                "이 맘은 여전히 그대로예요", "i mameun yeojeonhi geudaeroyeyo");
        assertAuthoredLine(new String[]{"따가", "운 ", "햇살 ", "그 ", "아", "래 ", "우리"},
                "따가운 햇살 그 아래 우리", "ttagaun haessal geu arae uri");
        assertAuthoredLine(new String[]{"이 ", "분", "위기 ", "난 ", "좋아", "요"},
                "이 분위기 난 좋아요", "i bunwigi nan joayo");
        assertAuthoredLine(new String[]{"어떡", "해 ", "나 ", "숨이 ", "가", "빠져요"},
                "어떡해 나 숨이 가빠져요", "eotteokae na sumi gappajeoyo");
        assertAuthoredLine(new String[]{"열이 ", "올라", "요 ", "에", "오"},
                "열이 올라요 에오", "yeori ollayo e-o");
        assertAuthoredLine(new String[]{"뜨거워진 ", "온도 ", "탓", "일까", "요"},
                "뜨거워진 온도 탓일까요", "tteugeowojin ondo tasilkkayo");
        assertAuthoredLine(new String[]{"약이 ", "올라", "요 ", "에", "오"},
                "약이 올라요 에오", "yagi ollayo e-o");
        assertAuthoredLine(new String[]{"한 ", "번쯤은 ", "무", "너", "져", "줄", "게", "요"},
                "한 번쯤은 무너져줄게요", "han beonjjeumeun muneojeojulgeyo");
    }

    private static void assertAuthoredLine(String[] rawSpans, String source, String display) {
        List<SyllableSegment> raw = heartBurnSegments(rawSpans, true);
        assertEquals(source, SpicyRomanizer.buildKoreanSyllableSource(raw).text);
        assertEquals(display, SpicyRomanizer.romanizeKoreanSyllableLineForDisplay(
                raw, KoreanDisplayMode.RR_PRONUNCIATION).display);

        List<SyllableSegment> parserTrimmed = heartBurnSegments(rawSpans, false);
        assertEquals(source, SpicyRomanizer.buildKoreanSyllableSource(parserTrimmed).text);
        assertEquals(display, SpicyRomanizer.romanizeKoreanSyllableLineForDisplay(
                parserTrimmed, KoreanDisplayMode.RR_PRONUNCIATION).display);
    }

    private static List<SyllableSegment> heartBurnSegments(String[] rawSpans, boolean preserveRawText) {
        ArrayList<SyllableSegment> segments = new ArrayList<>();
        for (String raw : rawSpans) {
            SyllableSegment segment = seg(raw.trim());
            segment.sourceText = preserveRawText ? raw : raw.trim();
            segment.providerPartOfWord = !raw.endsWith(" ");
            segment.partOfWord = !raw.endsWith(" ");
            segments.add(segment);
        }
        return segments;
    }

    private static SyllableSegment seg(String text) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        return segment;
    }

    private static LyricsDocument koreanDoc() {
        LyricsDocument doc = new LyricsDocument();
        doc.language = "ko";
        doc.detectedScripts.add(SpicyTextDetection.Script.KOREAN);
        return doc;
    }

    private static LyricsLine line(String text, String... spans) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        for (String span : spans) line.syllables.add(seg(span));
        return line;
    }

    private static LyricsLine lineWithPartFlags(String text, boolean partOfWord, String... spans) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        for (String span : spans) {
            SyllableSegment seg = seg(span);
            seg.providerPartOfWord = partOfWord;
            seg.partOfWord = partOfWord;
            line.syllables.add(seg);
        }
        return line;
    }

    private static String display(String text, KoreanDisplayMode mode) {
        return SpicyRomanizer.romanizeKoreanForDisplay(text, mode).display;
    }

    /**
     * Junction hyphen policy goldens (approved 2026-07-12, shared with desktop
     * tests/romanization-corpus.test.ts): hyphen only for sound-ambiguous n|g /
     * ng|vowel-glide junctions, RR vowel-digraph junctions, and triple same-letter
     * collisions. Doubles stay joined; sound-identical liaisons stay joined.
     */
    @Test
    public void koreanJunctionHyphenPolicyCorpus() {
        String[][] corpus = {
                {"마요", "mayo", "mayô"},
                {"같이", "gachi", "gachi"},
                {"먹어", "meogeo", "mogo"},
                {"없어", "eopsseo", "opsso"},
                {"없지", "eopjji", "opjji"},
                {"좋다", "jota", "jôta"},
                {"축하", "chuka", "chuka"},
                {"미련이", "miryeoni", "miryoni"},
                {"아무런", "amureon", "amuron"},
                {"말이", "mari", "mari"},
                {"종로", "jongno", "jôngnô"},
                {"생각", "saenggak", "sênggak"},
                {"강원", "gang-won", "gang-won"},
                {"한국말", "han-gungmal", "han-gungmal"},
                {"한국", "han-guk", "han-guk"},
                {"해운대", "hae-undae", "hêundê"},
                {"처음", "cheo-eum", "choưm"},
                {"악기", "ak-kki", "ak-kki"},
                {"신라", "silla", "silla"},
                {"잘못됐단", "jalmot-ttwaet-ttan", "jalmôt-ttwêt-ttan"},
        };
        for (String[] entry : corpus) {
            assertEquals(entry[0] + " rr", entry[1], display(entry[0], KoreanDisplayMode.RR_PRONUNCIATION));
            assertEquals(entry[0] + " vn", entry[2], display(entry[0], KoreanDisplayMode.VN_PRONUNCIATION));
        }
    }

    @Test
    public void koreanDisplayModeRegressionCorpus() {
        assertEquals("han-guk-eo", display("한국어", KoreanDisplayMode.WORD_TRANSLIT));
        assertEquals("nunbit", display("눈빛", KoreanDisplayMode.RR_STANDARD));
        assertEquals("gamchul-ssu itkke", display("감출 수 있게", KoreanDisplayMode.RR_PRONUNCIATION));
        assertEquals("gamchul-ssu itkkê", display("감출 수 있게", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("narô dasi dôragal-ssu itkkê", display("나로 다시 돌아갈 수 있게", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("gal-kkôt", display("갈 곳", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("nunttôngja", display("눈동자", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("jomjom nê môsưbi himihêjo", display("점점 내 모습이 희미해져", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("jujo opssi da, Probably delete it", display("주저 없이 다, Probably delete it", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("naê", display("나의", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("noê", display("너의", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("uriê", display("우리의", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("najê", display("낮의", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("nê nê gê gê", display("내 네 개 게", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("wê wê wê", display("왜 외 웨", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("hêdôji", display("해돋이", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("bêngma", display("백마", KoreanDisplayMode.VN_PRONUNCIATION));
        assertEquals("sêngnyonpil", display("색연필", KoreanDisplayMode.VN_PRONUNCIATION));
    }

    @Test
    public void legacyKoreanSettingsMigrateToDisplayModes() {
        assertEquals(KoreanDisplayMode.RR_STANDARD, KoreanDisplayMode.normalizeLegacy("plain", null));
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT, KoreanDisplayMode.normalizeLegacy("blocks", null));
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION, KoreanDisplayMode.normalizeLegacy("pronunciation", "vn"));
        assertEquals(KoreanDisplayMode.RR_PRONUNCIATION, KoreanDisplayMode.normalizeLegacy("pronunciation", null));
    }

    @Test
    public void koreanSettingKeepsCycleMode() {
        assertEquals("cycle", com.eza.spicyex.Settings.KOREAN_ROMANIZATION.coerce("cycle"));
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, com.eza.spicyex.Settings.KOREAN_ROMANIZATION.coerce("plain"));
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, com.eza.spicyex.Settings.LAST_KOREAN_CYCLE_MODE.coerce("Letter-by-letter"));
        assertEquals(KoreanDisplayMode.RR_PRONUNCIATION.value, com.eza.spicyex.Settings.LAST_KOREAN_CYCLE_MODE.coerce("Pronunciation"));
    }

    @Test
    public void romanizationOptionsPreserveKoreanOffSentinel() {
        assertEquals("Off", new RomanizationOptions("", "Off", false, "", false).koreanMode);
    }
}
