package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

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
}
