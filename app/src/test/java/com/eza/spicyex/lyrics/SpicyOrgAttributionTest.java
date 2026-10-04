package com.eza.spicyex.lyrics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SpicyOrgAttributionTest {
    @Test public void footerKeepsBothContributorLinksWithoutRepeatingProvider() {
        LyricsDocument doc = restricted("spicy_lyrics");
        doc.spicyOrgUploader = "uploader";
        doc.spicyOrgUploaderUrl = "https://spicylyrics.org/uid/1";
        doc.spicyOrgMaker = "maker";
        doc.spicyOrgMakerUrl = "https://spicylyrics.org/uid/2";
        List<SpicyOrgAttribution.Credit> credits = SpicyOrgAttribution.credits(doc, false);
        assertEquals(2, credits.size());
        assertEquals("uploaded by uploader", credits.get(0).label);
        assertEquals(doc.spicyOrgUploaderUrl, credits.get(0).url);
        assertEquals("made by maker", credits.get(1).label);
        assertEquals(doc.spicyOrgMakerUrl, credits.get(1).url);
        assertTrue(SpicyOrgAttribution.credits(restricted("apple_music"), false).isEmpty());
    }
    @Test public void communityCreditsIncludeSuppliedContributorLinks() {
        LyricsDocument doc = restricted("spicy_lyrics");
        doc.spicyOrgUploader = "uploader";
        doc.spicyOrgUploaderUrl = "https://spicylyrics.org/uid/1";
        doc.spicyOrgMaker = "maker";
        doc.spicyOrgMakerUrl = "https://spicylyrics.org/uid/2";
        List<SpicyOrgAttribution.Credit> credits = SpicyOrgAttribution.credits(doc);
        assertEquals(3, credits.size());
        assertEquals("Lyrics from Spicy Lyrics", credits.get(0).label);
        assertEquals("uploader", credits.get(1).label.substring(credits.get(1).nameStart));
        assertEquals("maker", credits.get(2).label.substring(credits.get(2).nameStart));
        assertEquals("uploaded by uploader", credits.get(1).label);
        assertEquals(doc.spicyOrgUploaderUrl, credits.get(1).url);
        assertEquals("made by maker", credits.get(2).label);
        assertEquals(doc.spicyOrgMakerUrl, credits.get(2).url);
    }

    @Test public void nonCommunitySourcesDoNotDisplayContributorFields() {
        String[] sources = {"apple_music", "spotify", "musixmatch", "", "future_source"};
        String[] labels = {"Apple Music", "Spotify", "Musixmatch", "Unknown source", "Unknown source"};
        for (int i = 0; i < sources.length; i++) {
            LyricsDocument doc = restricted(sources[i]);
            doc.spicyOrgUploader = "unexpected uploader";
            doc.spicyOrgMaker = "unexpected maker";
            List<SpicyOrgAttribution.Credit> credits = SpicyOrgAttribution.credits(doc);
            assertEquals(1, credits.size());
            assertEquals("Lyrics from " + labels[i], credits.get(0).label);
        }
    }

    @Test public void missingContributorsDoNotProduceEmptyLabels() {
        LyricsDocument doc = restricted("spicy_lyrics");
        doc.spicyOrgUploader = "  ";
        doc.spicyOrgMaker = null;
        assertEquals(1, SpicyOrgAttribution.credits(doc).size());
        assertTrue(SpicyOrgAttribution.credits(new LyricsDocument()).isEmpty());
        assertTrue(SpicyOrgAttribution.credits(null).isEmpty());
    }

    private static LyricsDocument restricted(String source) {
        LyricsDocument doc = new LyricsDocument();
        doc.fetchSource = "spicy_org_live";
        doc.spicyOrgSource = source;
        return doc;
    }
}
