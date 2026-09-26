package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import org.junit.Test;

public class CatalogRequestIdentityTest {
    @Test
    public void keyCarriesTrackSourceRevisionAndEpoch() {
        assertEquals("abc|apple|rev=r1|auth=none",
                CatalogRequestIdentity.key("abc", SourceId.APPLE, "r1", null));
        assertEquals("abc|lrclib|rev=|auth=none",
                CatalogRequestIdentity.key("abc", SourceId.LRCLIB, null, null));
    }

    @Test
    public void keyRejectsMissingTrackOrSource() {
        assertEquals("", CatalogRequestIdentity.key("", SourceId.APPLE, "r", "none"));
        assertEquals("", CatalogRequestIdentity.key(null, SourceId.APPLE, "r", "none"));
        assertEquals("", CatalogRequestIdentity.key("abc", null, "r", "none"));
    }

    @Test
    public void credentialFreeSourcesIgnoreSpotifyTokenState() {
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.APPLE, 42));
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.APPLE,
                CatalogRequestIdentity.TOKEN_GENERATION_NONE));
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.AMLL, 7));
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.LRCLIB, 7));
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.QQ, 7));
        assertEquals("none", CatalogRequestIdentity.authEpoch(SourceId.NETEASE, 7));
        assertEquals("local", CatalogRequestIdentity.authEpoch(SourceId.SPOTIFY_NATIVE, 7));
        assertEquals("local",
                CatalogRequestIdentity.authEpoch(SourceId.SPOTIFY_NATIVE,
                        CatalogRequestIdentity.TOKEN_GENERATION_NONE));
    }

    @Test
    public void appleAvailabilityAloneKeepsTheUpgradeWindowOpen() {
        assertTrue(CatalogRequestIdentity.upgradeExpected(false, true));
        assertTrue(CatalogRequestIdentity.upgradeExpected(true, false));
        assertTrue(CatalogRequestIdentity.upgradeExpected(true, true));
        assertFalse(CatalogRequestIdentity.upgradeExpected(false, false));
    }
}
