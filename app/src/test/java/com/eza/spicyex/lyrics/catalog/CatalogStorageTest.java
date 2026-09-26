package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CatalogStorageTest {
    @Test
    public void reportShowsCountsAndPayloadSize() {
        assertEquals("12 tracks · 34 candidates · 4.2 MB",
                CatalogStorage.formatReport(12, 34, 4404019L));
        assertEquals("0 tracks · 0 candidates · 0 B",
                CatalogStorage.formatReport(0, 0, 0L));
    }

    @Test
    public void byteUnitsScaleWithoutThrowing() {
        assertEquals("512 B", CatalogStorage.formatReport(1, 1, 512L).split(" · ")[2]);
        assertEquals("38.0 KB", CatalogStorage.formatReport(1, 1, 38912L).split(" · ")[2]);
        assertEquals("0 B", CatalogStorage.formatReport(-1, -2, -3L).split(" · ")[2]);
    }
}
