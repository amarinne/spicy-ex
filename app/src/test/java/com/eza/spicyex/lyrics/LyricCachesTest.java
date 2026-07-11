package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
    public void processedDocumentKeyDiffersByTranslationTarget() {
        String english = LyricCaches.processedDocumentKey(10, "track", "hin", RomanizationOptions.DEFAULTS,
                ProcessedLyricsCache.processingContextKey("on", "google_unofficial", "en", "auto", "auto"));
        String spanish = LyricCaches.processedDocumentKey(10, "track", "hin", RomanizationOptions.DEFAULTS,
                ProcessedLyricsCache.processingContextKey("on", "google_unofficial", "es", "auto", "auto"));

        assertTrue(!english.equals(spanish));
    }

    @Test
    public void processedDocumentKeyDiffersByTranslationSourceModeAndBackend() {
        String autoGoogle = LyricCaches.processedDocumentKey(10, "track", "hin", RomanizationOptions.DEFAULTS,
                ProcessedLyricsCache.processingContextKey("on", "google_unofficial", "en", "auto", "auto"));
        String manualGoogle = LyricCaches.processedDocumentKey(10, "track", "hin", RomanizationOptions.DEFAULTS,
                ProcessedLyricsCache.processingContextKey("on", "google_unofficial", "en", "manual", "hi"));
        String disabled = LyricCaches.processedDocumentKey(10, "track", "hin", RomanizationOptions.DEFAULTS,
                ProcessedLyricsCache.processingContextKey("off", "disabled", "en", "auto", "auto"));

        assertTrue(!autoGoogle.equals(manualGoogle));
        assertTrue(!autoGoogle.equals(disabled));
    }

    @Test
    public void processedDocumentKeyDiffersByKoreanDisplayMode() {
        String rr = LyricCaches.processedDocumentKey(10, "track", "ko",
                new RomanizationOptions("pinyin", KoreanDisplayMode.RR_STANDARD.value, false, "Russian", false));
        String vn = LyricCaches.processedDocumentKey(10, "track", "ko",
                new RomanizationOptions("pinyin", KoreanDisplayMode.VN_PRONUNCIATION.value, false, "Russian", false));

        assertTrue(!rr.equals(vn));
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
