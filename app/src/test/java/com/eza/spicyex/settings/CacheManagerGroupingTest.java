package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CacheManagerGroupingTest {
    @Test
    public void sourceNamesFromEitherFieldReadAsOneProvider() {
        assertEquals("Apple Music", CacheManager.sourceLabel("Apple Music"));
        assertEquals("Apple Music", CacheManager.sourceLabel("apple"));
        assertEquals("QQ Music", CacheManager.sourceLabel("QQ Music"));
        assertEquals("LRCLIB", CacheManager.sourceLabel("LRCLIB"));
        assertEquals("Spicy Lyrics", CacheManager.sourceLabel("Spicy Lyrics"));
        assertEquals("?", CacheManager.sourceLabel(""));
    }
}
