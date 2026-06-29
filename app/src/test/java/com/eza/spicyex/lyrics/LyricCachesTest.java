package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricCachesTest {
    @Test
    public void cacheKeysNormalizeUnknownLanguageToAuto() {
        assertEquals("auto", LyricCaches.sourceLanguageForCache(null));
        assertEquals("auto", LyricCaches.sourceLanguageForCache("unknown"));
        assertEquals("ja", LyricCaches.sourceLanguageForCache("ja"));
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
}
