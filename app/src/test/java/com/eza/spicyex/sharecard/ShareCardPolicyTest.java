package com.eza.spicyex.sharecard;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShareCardPolicyTest {
    @Test public void communityImageAndShareTextKeepAllResponseCredits() {
        LyricsDocument document = org("spicy_lyrics");
        document.spicyOrgUploader = "A complete uploader name";
        document.spicyOrgUploaderUrl = "https://spicylyrics.org/uid/uploader";
        document.spicyOrgMaker = "A complete maker name";
        document.spicyOrgMakerUrl = "https://spicylyrics.org/uid/maker";
        assertEquals("Lyrics from Spicy Lyrics\nuploaded by A complete uploader name, made by A complete maker name",
                ShareCardPolicy.creditText(document, false));
        String text = ShareCardPolicy.sharedText(document, "lyric excerpt", "Song", "Artist", "track-link");
        assertFalse(text.contains("lyric excerpt"));
        assertTrue(text.contains(document.spicyOrgUploaderUrl));
        assertTrue(text.contains(document.spicyOrgMakerUrl));
        assertTrue(text.endsWith("track-link"));
        assertTrue(ShareCardPolicy.requiresImage(document));
    }

    @Test public void commercialOrgResponsesKeepTheirOwnProvider() {
        assertEquals("Lyrics from Apple Music", ShareCardPolicy.creditText(org("apple_music"), false));
        assertEquals("Lyrics from Spotify", ShareCardPolicy.creditText(org("spotify"), false));
        assertEquals("Lyrics from Unknown source", ShareCardPolicy.creditText(org("future_provider"), false));
    }

    @Test public void otherSourcesKeepQuotedTextAndTextFallback() {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "apple";
        assertEquals("\"lyric excerpt\"\nSong - Artist\ntrack-link",
                ShareCardPolicy.sharedText(document, "lyric excerpt", "Song", "Artist", "track-link"));
        assertEquals("Song - Artist\ntrack-link",
                ShareCardPolicy.sharedText(document, "", "Song", "Artist", "track-link"));
        assertFalse(ShareCardPolicy.requiresImage(document));
        assertEquals("", ShareCardPolicy.creditText(document, false));
    }

    @Test public void softRefreshRemainsShareableButHardExpiryDoesNot() {
        LyricsDocument document = org("apple_music");
        document.spicyOrgFetchedAtMs = 1000;
        assertTrue(ShareCardPolicy.usable(null, document, 1000 + SpicyOrgPolicy.REFRESH_AFTER_MS));
        assertTrue(ShareCardPolicy.usable(null, document, 1000 + SpicyOrgPolicy.RETENTION_MS - 1));
        assertFalse(ShareCardPolicy.usable(null, document, 1000 + SpicyOrgPolicy.RETENTION_MS));
        assertFalse(ShareCardPolicy.usable(null, document, 999));
        document.spicyOrgFetchedAtMs = 0;
        assertFalse(ShareCardPolicy.usable(null, document, 1000));
        document.fetchSource = "apple";
        assertTrue(ShareCardPolicy.usable(null, document, 1000));
    }

    private static LyricsDocument org(String source) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "spicy_org";
        document.spicyOrgSource = source;
        return document;
    }
}
