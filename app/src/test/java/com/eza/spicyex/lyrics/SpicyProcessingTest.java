package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpicyProcessingTest {
    @Test
    public void mapsIndicIso3LanguagesToIso2() {
        assertEquals("hi", SpicyProcessing.toIso2("hin"));
        assertEquals("pa", SpicyProcessing.toIso2("pan"));
        assertEquals("pa", SpicyProcessing.toIso2("pun"));
        assertEquals("bn", SpicyProcessing.toIso2("ben"));
        assertEquals("mr", SpicyProcessing.toIso2("mar"));
        assertEquals("ta", SpicyProcessing.toIso2("tam"));
        assertEquals("te", SpicyProcessing.toIso2("tel"));
        assertEquals("ur", SpicyProcessing.toIso2("urd"));
        assertEquals("gu", SpicyProcessing.toIso2("guj"));
        assertEquals("kn", SpicyProcessing.toIso2("kan"));
        assertEquals("ml", SpicyProcessing.toIso2("mal"));
    }

    @Test
    public void indicScriptsTriggerEnglishTranslationWork() {
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("तुम ही हो", "en"));
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("ਸਾਡਾ ਪਿਆਰ", "en"));
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("ভালোবাসা", "en"));
        assertTrue(SpicyProcessing.shouldTranslateLine("तुम ही हो", "hin", "en"));
        assertTrue(SpicyProcessing.shouldTranslateLine("ਸਾਡਾ ਪਿਆਰ", "pan", "en"));
        assertTrue(SpicyProcessing.shouldTranslateLine("ভালোবাসা", "ben", "en"));
    }

    @Test
    public void indicSourceHintTriggersTranslationForLatinLyrics() {
        assertTrue(SpicyProcessing.flagsFor("tum hi ho ab tum hi ho", "hin", "en").translationPending);
        assertTrue(SpicyProcessing.shouldTranslateLine("sada pyaar", "pan", "en"));
    }

    @Test
    public void latinLyricsTriggerAutoEnglishTranslationWork() {
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("Na de dil pardesi nu", "en"));
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("tainu nit da rona pai jaauga", "en"));
    }

    @Test
    public void realCachedIndicLyricLinesTriggerEnglishTranslationWork() {
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("ओ सनम, ओ जिगर, तुमको हो क्या ही खबर", "en"));
        assertTrue(SpicyProcessing.hasTranslationWorkQuick("ਕੋਈ ਜੰਮਿਆ ਆਸ਼ਿਕ਼ ਨੀ ਇੱਥੇ ਜਿੱਤਣ ਲਈ", "en"));
    }

    @Test
    public void autoModeUsesGoogleAutoSource() {
        assertEquals("auto", LyricsSecondaryProcessingSession.effectiveGoogleSourceLanguage("auto", "hi"));
        assertEquals("auto", LyricsSecondaryProcessingSession.effectiveGoogleSourceLanguage("", "ja"));
        assertEquals("hin", LyricsSecondaryProcessingSession.effectiveGoogleSourceLanguage("manual", "hin"));
    }

    @Test
    public void indicScriptsDoNotTriggerWhenTargetMatchesScriptLanguage() {
        assertFalse(SpicyProcessing.shouldTranslateLine("तुम ही हो", "hin", "hi"));
        assertFalse(SpicyProcessing.shouldTranslateLine("ਸਾਡਾ ਪਿਆਰ", "pan", "pa"));
        assertFalse(SpicyProcessing.shouldTranslateLine("ভালোবাসা", "ben", "bn"));
    }

    @Test
    public void indicFontFallbackPredicateDetectsIndicScripts() {
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("तुम ही हो"));
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("ਸਾਡਾ ਪਿਆਰ"));
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("ভালোবাসা"));
        assertFalse(LyricsTextFactory.shouldUseSystemFallbackForText("hello world"));
    }
}
