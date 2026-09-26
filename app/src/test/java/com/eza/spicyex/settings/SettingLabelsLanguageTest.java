package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class SettingLabelsLanguageTest {
    private static final List<String> TARGETS = Arrays.asList("en", "ko", "zh", "zh-TW", "he", "id", "no");

    @Test
    public void phoneLocalesMapOntoTranslationTargets() {
        assertEquals("ko", SettingLabels.translationTargetFor(Locale.KOREA, TARGETS));
        assertEquals("zh", SettingLabels.translationTargetFor(Locale.forLanguageTag("zh-CN"), TARGETS));
        assertEquals("zh-TW", SettingLabels.translationTargetFor(Locale.forLanguageTag("zh-Hant-HK"), TARGETS));
        assertEquals("zh-TW", SettingLabels.translationTargetFor(Locale.forLanguageTag("zh-TW"), TARGETS));
        assertEquals("he", SettingLabels.translationTargetFor(new Locale("iw", "IL"), TARGETS));
        assertEquals("no", SettingLabels.translationTargetFor(Locale.forLanguageTag("nb-NO"), TARGETS));
        assertNull(SettingLabels.translationTargetFor(Locale.forLanguageTag("sw"), TARGETS));
    }

    @Test
    public void labelsDropTheTrailingCode() {
        assertEquals("Spanish", SettingLabels.withoutCode("Spanish (es)"));
        assertEquals("번체 중국어", SettingLabels.withoutCode("번체 중국어 (zh-TW)"));
        assertEquals("Off (default)", SettingLabels.withoutCode("Off (default)"));
    }
}
