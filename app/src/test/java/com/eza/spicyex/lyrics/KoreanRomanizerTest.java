package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Golden corpus for current Korean table romanization (docs/ROMANIZATION_AUDIT_BACKLOG.md KR-T1).
 * Encodes aromanize-compatible spelling output before any pronunciation pass (KR-1).
 */
public class KoreanRomanizerTest {
    @Test
    public void syllableTableRomanization() {
        assertEquals("eumak", SpicyRomanizer.romanizeKorean("음악"));
        assertEquals("hangukeo", SpicyRomanizer.romanizeKorean("한국어"));
        assertEquals("hakgyo", SpicyRomanizer.romanizeKorean("학교"));
        assertEquals("baekma", SpicyRomanizer.romanizeKorean("백마"));
    }

    @Test
    public void commonLyricPhrases() {
        assertEquals("annyeonghaseyo", SpicyRomanizer.romanizeKorean("안녕하세요"));
        assertEquals("sarang", SpicyRomanizer.romanizeKorean("사랑"));
    }

    @Test
    public void latinPassThrough() {
        assertEquals("BTS feat. IU", SpicyRomanizer.romanizeKorean("BTS feat. IU"));
    }
}
