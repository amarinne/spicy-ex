package com.eza.spicyex.auto;

import com.eza.spicyex.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoSecondaryTextTest {
    @Test public void mainOnlyIsDefaultAndInvalidValuesFallBack() {
        assertEquals("Main only", Settings.AUTO_SECONDARY_TEXT.defaultValue);
        assertEquals("Main only", Settings.AUTO_SECONDARY_TEXT.coerce("invalid"));
        assertFalse(AutoSecondaryText.showReading("Main only"));
        assertFalse(AutoSecondaryText.showTranslation("Main only"));
    }
    @Test public void readingAndTranslationAreIndependentChoices() {
        assertTrue(AutoSecondaryText.showReading("Transliteration"));
        assertFalse(AutoSecondaryText.showTranslation("Transliteration"));
        assertFalse(AutoSecondaryText.showReading("Translation"));
        assertTrue(AutoSecondaryText.showTranslation("Translation"));
        assertTrue(AutoSecondaryText.showReading("Both"));
        assertTrue(AutoSecondaryText.showTranslation("Both"));
    }
}
