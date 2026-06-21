package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

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
    }

    @Test
    public void latinPassThroughBreaksAdjacency() {
        assertEquals("eumak rock", sound("음악 rock"));
    }

    @Test
    public void readablePhraseSpacing() {
        assertEquals("annyeong haseyo", sound("안녕하세요"));
        assertEquals("sarang haeyo", sound("사랑해요"));
        assertEquals("bogo sipeo", sound("보고싶어"));
    }

    @Test
    public void fullLinePiecesPreserveSplitChunkPronunciation() {
        assertEquals(Arrays.asList("han", "gu", "geo"), SpicyKoreanG2P.romanizeSyllablePieces("한국어"));
        assertEquals(Arrays.asList("baeng", "ma"), SpicyKoreanG2P.romanizeSyllablePieces("백마"));
        assertEquals(Arrays.asList("an", "nyeong", " ", "ha", "se", "yo"), SpicyKoreanG2P.romanizeReadablePieces("안녕하세요"));
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
                new RomanizationOptions("", "Pronunciation", false, "Russian", false),
                doc,
                line,
                line.text);

        assertEquals("han", line.syllables.get(0).romanizedText);
        assertEquals("gu", line.syllables.get(1).romanizedText);
        assertEquals("geo", line.syllables.get(2).romanizedText);
    }

    private static SyllableSegment seg(String text) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        return segment;
    }
}
