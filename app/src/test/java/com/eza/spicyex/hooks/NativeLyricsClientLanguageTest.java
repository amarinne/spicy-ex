package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

/** Explicit Spotify clientLanguage prefers the app resource locale, then system locale. */
public class NativeLyricsClientLanguageTest {
    @Test
    public void appLocaleWinsOverSystem() {
        assertEquals("de",
                NativeLyricsCaptureHook.resolveClientLanguage(
                        Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("fr-FR")));
    }

    @Test
    public void emptyAppLanguageFallsBackToSystem() {
        assertEquals("fr",
                NativeLyricsCaptureHook.resolveClientLanguage(
                        new Locale("", ""), Locale.forLanguageTag("fr-FR")));
    }

    @Test
    public void nullAppLocaleFallsBackToSystem() {
        assertEquals("ja",
                NativeLyricsCaptureHook.resolveClientLanguage(
                        null, Locale.forLanguageTag("ja-JP")));
    }

    @Test
    public void emptySystemLanguageStaysEmpty() {
        assertEquals("",
                NativeLyricsCaptureHook.resolveClientLanguage(
                        new Locale("", ""), new Locale("", "")));
    }

    @Test
    public void unavailableLocalesStayEmpty() {
        assertEquals("", NativeLyricsCaptureHook.resolveClientLanguage(null, null));
    }
}
