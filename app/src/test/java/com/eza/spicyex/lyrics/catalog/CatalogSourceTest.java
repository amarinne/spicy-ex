package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

public class CatalogSourceTest {
    @Test
    public void bareTrackIdAcceptsUriAndBareId() {
        assertEquals("abc123", CatalogSource.bareTrackId("spotify:track:abc123"));
        assertEquals("abc123", CatalogSource.bareTrackId("abc123"));
        assertEquals("abc123", CatalogSource.bareTrackId("  abc123  "));
    }

    @Test
    public void bareTrackIdRejectsNonTrackIdentity() {
        assertEquals("", CatalogSource.bareTrackId(null));
        assertEquals("", CatalogSource.bareTrackId(""));
        assertEquals("", CatalogSource.bareTrackId("spotify:episode:xyz"));
        assertEquals("", CatalogSource.bareTrackId("spotify:local:artist:album:song:12"));
        assertEquals("", CatalogSource.bareTrackId("not an id"));
    }

    @Test
    public void retiredSpicyRouteMapsToApple() {
        assertEquals(SourceId.APPLE, CatalogSource.inferSourceId("spicy", "Spicy Lyrics"));
        assertEquals(SourceId.APPLE, CatalogSource.inferSourceId("apple_music", null));
        assertEquals(SourceId.APPLE, CatalogSource.inferSourceId("lenerd", null));
    }

    @Test
    public void knownProviderSourcesResolve() {
        assertEquals(SourceId.SPOTIFY_NATIVE, CatalogSource.inferSourceId("native", null));
        assertEquals(SourceId.AMLL, CatalogSource.inferSourceId("amll", null));
        assertEquals(SourceId.LRCLIB, CatalogSource.inferSourceId("lrclib", null));
    }

    @Test
    public void directMusixmatchHasNoRoute() {
        assertNull(CatalogSource.inferSourceId("musixmatch", "Musixmatch"));
        assertNull(CatalogSource.inferSourceId("unknown", null));
        assertNull(CatalogSource.inferSourceId(null, null));
        for (SourceId source : SourceId.values()) {
            assertTrue(!"musixmatch".equals(source.id));
        }
    }

    @Test
    public void tieBreakOrderIsAppleNativeAmllLrclibQqNetease() {
        assertTrue(SourceId.APPLE.ordinal() < SourceId.SPOTIFY_NATIVE.ordinal());
        assertTrue(SourceId.SPOTIFY_NATIVE.ordinal() < SourceId.AMLL.ordinal());
        assertTrue(SourceId.AMLL.ordinal() < SourceId.LRCLIB.ordinal());
        assertTrue(SourceId.LRCLIB.ordinal() < SourceId.QQ.ordinal());
        assertTrue(SourceId.QQ.ordinal() < SourceId.NETEASE.ordinal());
    }

    @Test
    public void timingLevelRanksSyllableAboveWordAboveLine() {
        assertEquals(TimingLevel.SYLLABLE, TimingLevel.fromDocumentType("Syllable"));
        assertEquals(TimingLevel.WORD, TimingLevel.fromDocumentType("Word"));
        assertEquals(TimingLevel.LINE, TimingLevel.fromDocumentType("Line"));
        assertEquals(TimingLevel.UNSYNCED, TimingLevel.fromDocumentType("Unknown"));
        assertEquals(TimingLevel.UNSYNCED, TimingLevel.fromDocumentType(null));
        assertTrue(TimingLevel.SYLLABLE.ordinal() < TimingLevel.WORD.ordinal());
        assertTrue(TimingLevel.WORD.ordinal() < TimingLevel.LINE.ordinal());
        assertTrue(TimingLevel.LINE.ordinal() < TimingLevel.UNSYNCED.ordinal());
    }

    @Test
    public void checkingIsMemoryOnlyAndNeverPersisted() {
        assertNull(ProviderStatus.parse("checking"));
        assertNull(ProviderStatus.parse("CHECKING"));
        for (ProviderStatus status : ProviderStatus.values()) {
            assertTrue(!"CHECKING".equals(status.name()));
        }
    }

    @Test
    public void weakMatchesAndUnknownModesFailClosed() {
        assertEquals(MatchMethod.WEAK, MatchMethod.valueOf("WEAK"));
        assertEquals(SelectionMode.AUTO, SelectionMode.parse("bogus"));
        assertEquals(SelectionMode.AUTO, SelectionMode.parse(null));
    }
}
