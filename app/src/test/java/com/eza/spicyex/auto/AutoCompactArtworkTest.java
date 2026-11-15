package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoCompactArtworkTest {
    @Test public void rowAndOtherSurfaceChangesCannotRestartAnOwnedFade() {
        AutoCompactArtwork artwork = new AutoCompactArtwork();
        assertTrue(artwork.update(true, true, false));
        for (int i = 0; i < 10; i++) assertFalse(artwork.update(true, true, false));
        assertTrue(artwork.owned());
    }

    @Test public void weatherAppearanceAndSpotifyReleasePermitTheNextEntrance() {
        AutoCompactArtwork artwork = new AutoCompactArtwork();
        assertTrue(artwork.update(true, true, false));
        assertFalse(artwork.update(true, true, true)); assertFalse(artwork.owned());
        assertTrue(artwork.update(true, true, false));
        assertFalse(artwork.update(true, false, false));
        assertTrue(artwork.update(true, true, false));
        assertFalse(artwork.update(false, true, false));
        assertTrue(artwork.update(true, true, false));
    }
}
