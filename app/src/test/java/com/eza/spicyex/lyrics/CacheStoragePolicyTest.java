package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CacheStoragePolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void parsesSupportedLabelsAndDefaultsInvalidValues() {
        assertEquals(32L * MIB, CacheStoragePolicy.totalBudgetBytes("32 MB"));
        assertEquals(128L * MIB, CacheStoragePolicy.totalBudgetBytes("128 MB"));
        assertEquals(512L * MIB, CacheStoragePolicy.totalBudgetBytes("512 MB"));
        assertEquals(1024L * MIB, CacheStoragePolicy.totalBudgetBytes("1024 MB"));
        assertEquals(CacheStoragePolicy.UNLIMITED,
                CacheStoragePolicy.totalBudgetBytes("No limit"));
        assertEquals(128L * MIB, CacheStoragePolicy.totalBudgetBytes("bad"));
    }

    @Test
    public void finiteBudgetIsAllocatedExactlyOnceAcrossStores() {
        long total = CacheStoragePolicy.totalBudgetBytes("128 MB");
        long allocated = CacheStoragePolicy.paidAiQuota(total)
                + CacheStoragePolicy.soundQuota(total)
                + CacheStoragePolicy.meaningQuota(total)
                + CacheStoragePolicy.googleQuota(total)
                + CacheStoragePolicy.canonicalQuota(total)
                + CacheStoragePolicy.rawResponseQuota(total)
                + CacheStoragePolicy.detectionQuota(total);

        assertEquals(total, allocated);
        assertEquals(60_397_981L, CacheStoragePolicy.paidAiQuota(total));
        assertEquals(20_132_659L, CacheStoragePolicy.soundQuota(total));
        assertEquals(13_421_772L, CacheStoragePolicy.meaningQuota(total));
        assertEquals(6_710_886L, CacheStoragePolicy.rawResponseQuota(total));
        assertEquals(6_710_886L, CacheStoragePolicy.detectionQuota(total));
    }

    @Test
    public void noLimitPropagatesToEveryStore() {
        long total = CacheStoragePolicy.totalBudgetBytes("No limit");

        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.paidAiQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.soundQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.meaningQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.googleQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.canonicalQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.rawResponseQuota(total));
        assertEquals(CacheStoragePolicy.UNLIMITED, CacheStoragePolicy.detectionQuota(total));
    }

    @Test
    public void formatsCompactBinaryUsage() {
        assertEquals("0 B", CacheStoragePolicy.formatBytes(0L));
        assertEquals("1 KB", CacheStoragePolicy.formatBytes(1024L));
        assertEquals("1.5 MB", CacheStoragePolicy.formatBytes(1_572_864L));
        assertEquals("1 GB", CacheStoragePolicy.formatBytes(1024L * MIB));
    }
}
