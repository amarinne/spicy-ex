package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Golden corpus for Cyrillic romanization (docs/ROMANIZATION_AUDIT_BACKLOG.md CY-T1). */
public class CyrillicRomanizerTest {
    @Test
    public void yePositionalRuleOnRussianNames() {
        assertEquals("Yelena", SpicyRomanizer.romanizeCyrillic("Елена"));
        assertEquals("Dostoyevskiy", SpicyRomanizer.romanizeCyrillic("Достоевский"));
        assertEquals("Sergeyevna", SpicyRomanizer.romanizeCyrillic("Сергеевна"));
    }

    @Test
    public void yoAndHardSoftSigns() {
        assertEquals("yo", SpicyRomanizer.romanizeCyrillic("ё"));
        assertEquals("obyekt", SpicyRomanizer.romanizeCyrillic("объект"));
        assertEquals("myagkiy", SpicyRomanizer.romanizeCyrillic("мягкий"));
    }

    @Test
    public void mixedScriptApostrophesPreserved() {
        assertEquals("Privet rock'n'roll", SpicyRomanizer.romanizeCyrillic("Привет rock'n'roll"));
        assertEquals("Ya lyublyu pop-muzyku", SpicyRomanizer.romanizeCyrillic("Я люблю pop-музыку"));
    }

    @Test
    public void ukrainianMode() {
        // г→h, и→y, і→i, ї→yi, є→ye, е→e (no positional ye) — distinct from Russian.
        assertEquals("hora", SpicyRomanizer.romanizeCyrillic("гора", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        assertEquals("Kyyiv", SpicyRomanizer.romanizeCyrillic("Київ", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        assertEquals("Ukrayina", SpicyRomanizer.romanizeCyrillic("Україна", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        assertEquals("ganok", SpicyRomanizer.romanizeCyrillic("ґанок", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        // Same letters, Russian values for contrast.
        assertEquals("gora", SpicyRomanizer.romanizeCyrillic("гора", SpicyRomanizer.CYRILLIC_RUSSIAN, false));
    }

    @Test
    public void serbianLettersUseConsistentAsciiSimplification() {
        assertEquals("dj c dz lj nj", SpicyRomanizer.romanizeCyrillic("ђ ћ џ љ њ"));
        assertEquals("Dj C Dz Lj Nj", SpicyRomanizer.romanizeCyrillic("Ђ Ћ Џ Љ Њ"));
    }

    @Test
    public void southSlavicAndMacedonianLettersDoNotLeaveDiacritics() {
        assertEquals("g k dz dj c dz", SpicyRomanizer.romanizeCyrillic("ѓ ќ ѕ ђ ћ џ"));
        assertEquals("G K Dz Dj C Dz", SpicyRomanizer.romanizeCyrillic("Ѓ Ќ Ѕ Ђ Ћ Џ"));
    }

    @Test
    public void keepSignsToggle() {
        assertEquals("den", SpicyRomanizer.romanizeCyrillic("день", SpicyRomanizer.CYRILLIC_RUSSIAN, false));
        assertEquals("denʹ", SpicyRomanizer.romanizeCyrillic("день", SpicyRomanizer.CYRILLIC_RUSSIAN, true));
        assertEquals("obʺyekt", SpicyRomanizer.romanizeCyrillic("объект", SpicyRomanizer.CYRILLIC_RUSSIAN, true));
    }

    @Test
    public void centralAsianOverridesApplyInRussianMode() {
        assertEquals("Aldadyngby", SpicyRomanizer.romanizeCyrillic("Алдадыңбы"));
        assertEquals("Kalbadyngby zhanymda", SpicyRomanizer.romanizeCyrillic("Калбадыңбы жанымда"));
        assertEquals("Omur", SpicyRomanizer.romanizeCyrillic("Өмүр"));
    }

    @Test
    public void centralAsianOverridesApplyInUkrainianMode() {
        assertEquals("Aldadyngby", SpicyRomanizer.romanizeCyrillic("Алдадыңбы", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        assertEquals("Kalbadyngby zhanymda", SpicyRomanizer.romanizeCyrillic("Калбадыңбы жанымда", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        assertEquals("Omur", SpicyRomanizer.romanizeCyrillic("Өмүр", SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
    }
}
