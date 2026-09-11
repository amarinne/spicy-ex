package com.eza.spicyex.lyrics;

import org.junit.Test;

import com.eza.spicyex.lyrics.session.LayerConfigIds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.LinkedHashMap;
import java.util.Map;

public class LyricCachesTest {
    @Test
    public void localWholeLineAuthorityBumpsTheReadingCacheIdentity() {
        assertEquals(5, ProcessedLyricsCache.READING_SCHEMA_VERSION);
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
    public void eachReadingStyleKeepsItsOwnRecordSoSwitchingBackIsInstant() {
        String rr = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);
        String vn = LayerConfigIds.sound(true,
                new RomanizationOptions("pinyin", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false)
                        .cacheKey(), "ko", 3);

        assertFalse(LyricCaches.soundArtifactKey("digest-a", rr)
                .equals(LyricCaches.soundArtifactKey("digest-a", vn)));
        assertEquals(LyricCaches.soundArtifactKey("digest-a", rr),
                LyricCaches.soundArtifactKey("digest-a", rr));
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
    public void translationTargetChangeLeavesTheSoundKeyIntact() {
        String english = LyricCaches.meaningArtifactKey("digest-a",
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto", "google_draft"));
        String spanish = LyricCaches.meaningArtifactKey("digest-a",
                LayerConfigIds.meaning(true, "google_unofficial", "es", "auto", "auto", "google_draft"));

        String soundConfig = LayerConfigIds.sound(true, RomanizationOptions.DEFAULTS.cacheKey(), "hin", 3);
        assertTrue(!english.equals(spanish));
        assertEquals(LyricCaches.soundArtifactKey("digest-a", soundConfig),
                LyricCaches.soundArtifactKey("digest-a", soundConfig));
    }

    @Test
    public void meaningKeyTracksBackendTargetAndSourceModeOnly() {
        String autoGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto", "google_draft");
        String manualGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "manual", "hi", "google_draft");
        String disabled = LayerConfigIds.meaning(false, "disabled", "en", "auto", "auto", "google_draft");

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

    @Test
    public void boundedGoogleCacheOrderMovesExistingKeyToNewest() {
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("b", 7L);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|4\nb|4\nc|4", null, writes, 15L);

        assertEquals("a|4\nc|4\nb|7", update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void googleCacheMigratesPlainKeysUsingKnownSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("a", 6L);
        sizes.put("b", 4L);
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("c", 4L);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a\nb", sizes, writes, 8L);

        assertEquals("b|4\nc|4", update.nextOrder);
        assertEquals(1, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
    }

    @Test
    public void boundedGoogleCacheOrderIgnoresBlankAndSentinelEntries() {
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("b", 4L);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "\n__cache_order\na|4\n", null, writes, 8L);

        assertEquals("a|4\nb|4", update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void boundedGoogleCacheOrderAddsBatchAndMovesDuplicatesOnce() {
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("b", 4L);
        writes.put("d", 3L);
        writes.put("e", 4L);
        writes.put("d", 4L);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|4\nb|4\nc|4", null, writes, 16L);

        assertEquals("c|4\nb|4\nd|4\ne|4", update.nextOrder);
        assertEquals(1, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
    }

    @Test
    public void boundedGoogleCacheOrderEvictsWholeOverflowForBatch() {
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("d", 4L);
        writes.put("e", 4L);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|4\nb|4\nc|4", null, writes, 12L);

        assertEquals("c|4\nd|4\ne|4", update.nextOrder);
        assertEquals(2, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
        assertTrue(update.evictedKeys.contains("b"));
    }

    @Test
    public void unlimitedGoogleCacheKeepsAllEntries() {
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("b", Long.MAX_VALUE);
        LyricCaches.GoogleQuotaUpdate update = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|8", null, writes, CacheStoragePolicy.UNLIMITED);

        assertEquals("a|8\nb|" + Long.MAX_VALUE, update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void processedCacheOrderEvictsByEntryAndByteLimits() {
        LyricCaches.ProcessedCacheOrderUpdate update = LyricCaches.boundedProcessedCacheOrder(
                "a|100|40\nb|100|40", "c", 40, 200, 2, 80, 1000);

        assertEquals("b|100|40\nc|200|40", update.nextOrder);
        assertTrue(update.evictedKeys.contains("a"));
    }

    @Test
    public void processedCacheOrderExpiresOldEntries() {
        LyricCaches.ProcessedCacheOrderUpdate update = LyricCaches.boundedProcessedCacheOrder(
                "old|100|20\nfresh|950|20", "new", 20, 1000, 4, 100, 100);

        assertEquals("fresh|950|20\nnew|1000|20", update.nextOrder);
        assertTrue(update.evictedKeys.contains("old"));
    }

    @Test
    public void processedCacheOrderKeepsOldEntriesWhenAgeLimitIsDisabled() {
        LyricCaches.ProcessedCacheOrderUpdate update = LyricCaches.boundedProcessedCacheOrder(
                "old|100|20\nfresh|950|20", "new", 20, 1000,
                Integer.MAX_VALUE, 1000, 0L);

        assertEquals("old|100|20\nfresh|950|20\nnew|1000|20", update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void googleCacheEvictsByBytesInsteadOfOldEntryCount() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("a", 4L);
        sizes.put("b", 4L);
        Map<String, Long> writes = new LinkedHashMap<>();
        writes.put("c", 4L);

        LyricCaches.GoogleQuotaUpdate roomy = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|4\nb|4", sizes, writes, 12L);
        assertEquals("a|4\nb|4\nc|4", roomy.nextOrder);
        assertTrue(roomy.evictedKeys.isEmpty());

        LyricCaches.GoogleQuotaUpdate tight = LyricCaches.boundedGoogleCacheOrderByBytes(
                "a|4\nb|4", sizes, writes, 8L);
        assertEquals("b|4\nc|4", tight.nextOrder);
        assertTrue(tight.evictedKeys.contains("a"));
    }
}
