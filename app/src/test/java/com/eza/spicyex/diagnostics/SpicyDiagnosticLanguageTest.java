package com.eza.spicyex.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpicyDiagnosticLanguageTest {
    @Test
    public void explicitRuntimeOrProviderLanguageWins() {
        assertEquals("ko", SpicyDiagnosticLanguage.resolve("ko-KR", "ja", "アニョハセヨ"));
        assertEquals("ja", SpicyDiagnosticLanguage.resolve("unknown", "jpn", "hello"));
        assertEquals("en", SpicyDiagnosticLanguage.resolve("", "en-US", "hello"));
    }

    @Test
    public void unambiguousScriptProvidesSafeFallback() {
        assertEquals("ja", SpicyDiagnosticLanguage.resolve("", "", "アニョハセヨ"));
        assertEquals("ko", SpicyDiagnosticLanguage.resolve("", "", "안녕하세요"));
        assertEquals("cyrillic", SpicyDiagnosticLanguage.resolve("", "", "Привет"));
        assertEquals("el", SpicyDiagnosticLanguage.resolve("", "", "Γειά σου"));
    }

    @Test
    public void hanOnlyLatinEmptyAndMixedScriptsRemainUnknown() {
        assertEquals("unknown", SpicyDiagnosticLanguage.resolve("", "", "世界"));
        assertEquals("unknown", SpicyDiagnosticLanguage.resolve("", "", "hello"));
        assertEquals("unknown", SpicyDiagnosticLanguage.resolve("", "", ""));
        assertEquals("unknown", SpicyDiagnosticLanguage.resolve("", "", "かな 안녕"));
    }

    @Test
    public void explicitChineseMetadataDisambiguatesHanOnlyText() {
        assertEquals("zh", SpicyDiagnosticLanguage.resolve("", "zh-TW", "世界"));
    }
}
