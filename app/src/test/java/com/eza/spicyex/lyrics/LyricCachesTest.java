package com.eza.spicyex.lyrics;

import org.junit.Test;

import com.eza.spicyex.lyrics.session.LayerConfigIds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricCachesTest {
    @Test
    public void localWholeLineAuthorityBumpsTheReadingCacheIdentity() {
        assertEquals(8, ProcessedLyricsCache.READING_SCHEMA_VERSION);
        String options = RomanizationOptions.DEFAULTS.cacheKey();
        String old = LayerConfigIds.sound(true, options, "ru", 3);
        String current = LayerConfigIds.sound(true, options, "ru",
                ProcessedLyricsCache.READING_SCHEMA_VERSION);

        assertFalse("v3 line-fallback plans must not survive as current local coverage",
                old.equals(current));
    }

    @Test
    public void cacheKeysNormalizeUnknownLanguageToAuto() {
        assertEquals("auto", LyricCaches.sourceLanguageForCache(null));
        assertEquals("auto", LyricCaches.sourceLanguageForCache("unknown"));
        assertEquals("ja", LyricCaches.sourceLanguageForCache("ja"));
        assertEquals("hi", LyricCaches.sourceLanguageForCache("hin"));
    }

    @Test
    public void soundAndMeaningArtifactKeysShareNothingButTheCanonicalDigest() {
        String sound = LyricCaches.soundArtifactKey("digest-a",
                LayerConfigIds.sound(true, RomanizationOptions.DEFAULTS.cacheKey(), "ja", 3));
        String meaning = LyricCaches.meaningArtifactKey("digest-a",
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto", "google_draft"));

        assertTrue(sound.contains("digest-a"));
        assertTrue(meaning.contains("digest-a"));
        assertTrue(!sound.equals(meaning));
        // No Meaning input may appear in the Sound key.
        assertTrue(!sound.contains("google_unofficial"));
        assertTrue(!sound.contains("target="));
        // No Sound input may appear in the Meaning key.
        assertTrue(!meaning.contains("cn="));
        assertTrue(!meaning.contains("kr="));
    }

    @Test
    public void readingStyleArtifactKeysSeparateByModeAndDigest() {
        String rr = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);
        String vn = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);

        assertFalse(LyricCaches.soundArtifactKey("digest-a", rr)
                .equals(LyricCaches.soundArtifactKey("digest-a", vn)));
        assertFalse(LyricCaches.soundArtifactKey("digest-a", rr)
                .equals(LyricCaches.soundArtifactKey("digest-b", rr)));
    }

    @Test
    public void aStoredReadingRecordIsRejectedWhenItsModeIsNoLongerCurrent() {
        String rr = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);
        String vn = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);
        com.google.gson.JsonObject stored =
                ProcessedLyricsCache.newRecordHeader("SOUND", "digest-a", rr, true);

        assertTrue(ProcessedLyricsCache.recordMatches(stored, "digest-a", rr));
        // Reading isolation lives in the record now that the key is digest-only.
        assertFalse(ProcessedLyricsCache.recordMatches(stored, "digest-a", vn));
        assertFalse(ProcessedLyricsCache.recordMatches(stored, "digest-b", rr));
        assertFalse(ProcessedLyricsCache.recordMatches(null, "digest-a", rr));
    }

    @Test
    public void meaningKeyTracksBackendTargetAndSourceModeOnly() {
        String autoGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto", "google_draft");
        String spanishGoogle = LayerConfigIds.meaning(true, "google_unofficial", "es", "auto", "auto", "google_draft");
        String manualGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "manual", "hi", "google_draft");
        String disabled = LayerConfigIds.meaning(false, "disabled", "en", "auto", "auto", "google_draft");

        assertTrue(!autoGoogle.equals(spanishGoogle));
        assertTrue(!autoGoogle.equals(manualGoogle));
        assertTrue(!autoGoogle.equals(disabled));
    }

    @Test
    public void soundConfigTracksKoreanDisplayModeAndNothingFromMeaning() {
        String rr = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);
        String vn = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);

        assertTrue(!rr.equals(vn));
        assertTrue(!rr.contains("target="));
    }

}
