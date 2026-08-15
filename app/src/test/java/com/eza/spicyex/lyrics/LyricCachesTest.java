package com.eza.spicyex.lyrics;

import org.junit.Test;

import com.eza.spicyex.lyrics.session.LayerConfigIds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;

public class LyricCachesTest {
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
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto"));

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
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto"));
        String spanish = LyricCaches.meaningArtifactKey("digest-a",
                LayerConfigIds.meaning(true, "google_unofficial", "es", "auto", "auto"));

        String soundConfig = LayerConfigIds.sound(true, RomanizationOptions.DEFAULTS.cacheKey(), "hin", 3);
        assertTrue(!english.equals(spanish));
        assertEquals(LyricCaches.soundArtifactKey("digest-a", soundConfig),
                LyricCaches.soundArtifactKey("digest-a", soundConfig));
    }

    @Test
    public void meaningKeyTracksBackendTargetAndSourceModeOnly() {
        String autoGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto");
        String manualGoogle = LayerConfigIds.meaning(true, "google_unofficial", "en", "manual", "hi");
        String disabled = LayerConfigIds.meaning(false, "disabled", "en", "auto", "auto");

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
        LyricCaches.CacheOrderUpdate update = LyricCaches.boundedGoogleCacheOrder("a\nb\nc", "b", 3);

        assertEquals("a\nc\nb", update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void boundedGoogleCacheOrderEvictsOldestEntries() {
        LyricCaches.CacheOrderUpdate update = LyricCaches.boundedGoogleCacheOrder("a\nb\nc", "d", 3);

        assertEquals("b\nc\nd", update.nextOrder);
        assertEquals(1, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
    }

    @Test
    public void boundedGoogleCacheOrderIgnoresBlankAndSentinelEntries() {
        LyricCaches.CacheOrderUpdate update = LyricCaches.boundedGoogleCacheOrder("\n__cache_order\na\n", "b", 5);

        assertEquals("a\nb", update.nextOrder);
        assertTrue(update.evictedKeys.isEmpty());
    }

    @Test
    public void boundedGoogleCacheOrderAddsBatchAndMovesDuplicatesOnce() {
        LyricCaches.CacheOrderUpdate update = LyricCaches.boundedGoogleCacheOrder(
                "a\nb\nc", Arrays.asList("b", "d", "e", "d"), 4);

        assertEquals("c\nb\ne\nd", update.nextOrder);
        assertEquals(1, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
    }

    @Test
    public void boundedGoogleCacheOrderEvictsWholeOverflowForBatch() {
        LyricCaches.CacheOrderUpdate update = LyricCaches.boundedGoogleCacheOrder(
                "a\nb\nc", Arrays.asList("d", "e"), 3);

        assertEquals("c\nd\ne", update.nextOrder);
        assertEquals(2, update.evictedKeys.size());
        assertTrue(update.evictedKeys.contains("a"));
        assertTrue(update.evictedKeys.contains("b"));
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
}
