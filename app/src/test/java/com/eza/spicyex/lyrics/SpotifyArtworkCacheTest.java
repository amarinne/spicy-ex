package com.eza.spicyex.lyrics;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyArtworkCacheTest {
    @Test public void imageIdentityWinsOverTrackFallback() {
        assertTrue(SpotifyArtworkCache.matches("spotify:image:abc","spotify:track:old","abc","spotify:track:new"));
        assertTrue(SpotifyArtworkCache.matches("https://i.scdn.co/image/abc",null,"abc","spotify:track:new"));
        assertTrue(SpotifyArtworkCache.matches("content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3Aabc?transformation=NONE",null,"abc","spotify:track:new"));
        assertFalse(SpotifyArtworkCache.matches("spotify:image:old","spotify:track:new","abc","spotify:track:new"));
        assertFalse(SpotifyArtworkCache.matches("https://unrelated/abc",null,"abc","spotify:track:new"));
    }
    @Test public void absentArtworkUriRequiresExactTrackIdentity() {
        assertTrue(SpotifyArtworkCache.matches(null,"spotify:track:abc","image","spotify:track:abc"));
        assertFalse(SpotifyArtworkCache.matches(null,"spotify:track:old","image","spotify:track:new"));
        assertFalse(SpotifyArtworkCache.matches(null,null,"image",null));
        assertFalse(SpotifyArtworkCache.matches(null,"","image",""));
    }
}
