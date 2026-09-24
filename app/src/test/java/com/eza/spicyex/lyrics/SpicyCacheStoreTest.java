package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpicyCacheStoreTest {
    @Test
    public void quotaUsesUtf8PayloadBytes() {
        assertEquals(1, SpicyCacheStore.utf8Bytes("a"));
        assertEquals(3, SpicyCacheStore.utf8Bytes("あ"));
        assertEquals(4, SpicyCacheStore.utf8Bytes("😀"));
    }

    @Test
    public void migrationSkipsLegacyIndexKeys() {
        assertTrue(SpicyCacheStore.isLegacyIndexKey("__cache_order"));
        assertTrue(SpicyCacheStore.isLegacyIndexKey("__paid_index"));
        assertFalse(SpicyCacheStore.isLegacyIndexKey("song"));
    }

    @Test
    public void capacityChecksBytesAndEntryCount() {
        assertFalse(SpicyCacheStore.exceedsCapacity(3, 4, 1, 2, 7));
        assertTrue(SpicyCacheStore.exceedsCapacity(4, 4, 1, 2, 7));
        assertTrue(SpicyCacheStore.exceedsCapacity(0, 1, 2, 2,
                CacheStoragePolicy.UNLIMITED));
    }
}
