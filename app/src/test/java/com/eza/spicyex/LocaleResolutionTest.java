package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure locale-tag cleanup and supported-locale matching behind language selection. */
public class LocaleResolutionTest {
    @Test
    public void normalizeUnifiesSeparatorsAndStripsRevisions() {
        assertEquals("zh-CN", SettingsUiStrings.normalizeLocaleTag("zh-rCN"));
        assertEquals("zh-CN", SettingsUiStrings.normalizeLocaleTag("zh_CN"));
        assertEquals("en", SettingsUiStrings.normalizeLocaleTag("en"));
        assertEquals("", SettingsUiStrings.normalizeLocaleTag(null));
    }

    @Test
    public void exactAndRegionMatchesPass() {
        assertTrue(SettingsUiStrings.matchesSupportedLocale("ru", "ru"));
        assertTrue(SettingsUiStrings.matchesSupportedLocale("ru-RU", "ru"));
        assertTrue(SettingsUiStrings.matchesSupportedLocale("ru", "ru-RU"));
        assertTrue(SettingsUiStrings.matchesSupportedLocale("zh-CN", "zh-CN"));
        assertTrue(SettingsUiStrings.matchesSupportedLocale("en", "en"));
    }

    @Test
    public void crossLanguageMatchesFail() {
        assertFalse(SettingsUiStrings.matchesSupportedLocale("ru", "zh-CN"));
        assertFalse(SettingsUiStrings.matchesSupportedLocale("en", "ru"));
        assertFalse(SettingsUiStrings.matchesSupportedLocale("zh-CN", "zh-TW"));
    }
}
