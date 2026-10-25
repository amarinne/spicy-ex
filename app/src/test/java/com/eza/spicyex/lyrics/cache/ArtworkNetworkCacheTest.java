package com.eza.spicyex.lyrics.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ArtworkNetworkCacheTest {
    @Test
    public void sampleSizeKeepsLongestEdgeNearTarget() {
        assertEquals(4, ArtworkNetworkCache.sampleSize(2048, 2048, 384));
        assertEquals(2, ArtworkNetworkCache.sampleSize(1200, 600, 384));
        assertEquals(1, ArtworkNetworkCache.sampleSize(640, 360, 384));
    }

    @Test
    public void sampleSizeHandlesTinyAndInvalidBounds() {
        assertEquals(1, ArtworkNetworkCache.sampleSize(96, 96, 384));
        assertEquals(1, ArtworkNetworkCache.sampleSize(-1, -1, 384));
        assertEquals(1, ArtworkNetworkCache.sampleSize(800, 400, 0));
    }

    @Test
    public void bareCoverIdsUseTheImageCdnAndUrlsPassThrough() {
        assertEquals("https://i.scdn.co/image/ab67616d00001e02",
                ArtworkNetworkCache.url("ab67616d00001e02"));
        assertEquals("https://example.com/a.jpg", ArtworkNetworkCache.url("https://example.com/a.jpg"));
    }
}
