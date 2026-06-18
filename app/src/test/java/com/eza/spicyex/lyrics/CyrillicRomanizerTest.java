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
    public void keepSignsToggle() {
        assertEquals("den", SpicyRomanizer.romanizeCyrillic("день", SpicyRomanizer.CYRILLIC_RUSSIAN, false));
        assertEquals("denʹ", SpicyRomanizer.romanizeCyrillic("день", SpicyRomanizer.CYRILLIC_RUSSIAN, true));
        assertEquals("obʺyekt", SpicyRomanizer.romanizeCyrillic("объект", SpicyRomanizer.CYRILLIC_RUSSIAN, true));
    }
}
