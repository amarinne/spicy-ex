package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Golden corpus for the current Greek table romanizer. */
public class GreekRomanizerTest {
    @Test
    public void basicAlphabetValues() {
        assertEquals("Agapi", SpicyRomanizer.romanizeGreek("Αγαπη"));
        assertEquals("Thalassa", SpicyRomanizer.romanizeGreek("Θαλασσα"));
        assertEquals("Psychi", SpicyRomanizer.romanizeGreek("Ψυχη"));
    }

    @Test
    public void stripsGreekDiacriticsBeforeMapping() {
        assertEquals("Agapi", SpicyRomanizer.romanizeGreek("Αγάπη"));
        assertEquals("Otan se eida", SpicyRomanizer.romanizeGreek("Όταν σε είδα"));
    }

    @Test
    public void finalSigmaAndMixedTextPassThrough() {
        assertEquals("kosmos", SpicyRomanizer.romanizeGreek("κόσμος"));
        assertEquals("Agapi rock'n'roll", SpicyRomanizer.romanizeGreek("Αγάπη rock'n'roll"));
    }
}
