package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class SpicyLyricBridgeResponseCreditTest {
    @Test
    public void communityResponseIncludesWritersAndContributorsWithoutRawPayload() throws Exception {
        LyricsDocument document = orgDocument("spicy_lyrics");
        document.songWriters = "Writer One";
        document.spicyOrgUploader = "Uploader";
        document.spicyOrgMaker = "Maker";
        document.spicyOrgUploaderUrl = "https://example.invalid/uploader";
        document.spicyOrgRawPayload = "private response metadata";

        JsonObject encoded = encoded(document);
        assertEquals(2, encoded.get("version").getAsInt());
        assertEquals("Written by: Writer One\nLyrics from Spicy Lyrics\nuploaded by Uploader\nmade by Maker",
                encoded.get("responseCredit").getAsString());
        assertFalse(encoded.toString().contains("private response metadata"));
        assertFalse(encoded.toString().contains("https://example.invalid/uploader"));
    }

    @Test
    public void commercialResponsesCreditTheirActualSourceWithoutCommunityContributors() throws Exception {
        for (String[] source : new String[][]{
                {"apple_music", "Apple Music"}, {"spotify", "Spotify"}, {"musixmatch", "Musixmatch"}}) {
            LyricsDocument document = orgDocument(source[0]);
            document.spicyOrgUploader = "Unused uploader";
            document.spicyOrgMaker = "Unused maker";
            assertEquals("Lyrics from " + source[1], encoded(document).get("responseCredit").getAsString());
        }
    }

    @Test
    public void otherSourcesKeepSongwriterCreditsAndOmitEmptyCredits() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.provider = "QQ";
        document.spicyOrgUploader = "Unused uploader";
        document.songWriters = " Writer Two ";
        assertEquals("Written by: Writer Two", encoded(document).get("responseCredit").getAsString());
        document.songWriters = "  ";
        assertFalse(encoded(document).has("responseCredit"));
    }

    @Test
    public void creditOnlyChangesInvalidateTheBridgeFingerprint() {
        LyricsDocument document = orgDocument("spicy_lyrics");
        String lyricsFingerprint = LyricsDocumentProcessor.publicationFingerprint(document);
        String fingerprint = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document);
        document.spicyOrgUploader = "Uploader";
        String uploaderFingerprint = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document);
        assertNotEquals(fingerprint, uploaderFingerprint);
        document.spicyOrgMaker = "Maker";
        String makerFingerprint = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document);
        assertNotEquals(uploaderFingerprint, makerFingerprint);
        document.songWriters = "Writer";
        String writerFingerprint = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document);
        assertNotEquals(makerFingerprint, writerFingerprint);
        document.spicyOrgSource = "apple_music";
        assertNotEquals(writerFingerprint, SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document));
        assertEquals(lyricsFingerprint, LyricsDocumentProcessor.publicationFingerprint(document));
    }

    @Test
    public void undisplayedResponseMetadataDoesNotRepublish() {
        LyricsDocument document = orgDocument("spicy_lyrics");
        String fingerprint = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document);
        document.spicyOrgRawPayload = "changed private response metadata";
        document.spicyOrgUploaderUrl = "https://example.invalid/uploader";
        assertEquals(fingerprint, SpicyLyricBridgeDocumentSerializer.publicationFingerprint(document));
    }

    private static LyricsDocument orgDocument(String source) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "spicy_org_cache";
        document.spicyOrgSource = source;
        return document;
    }

    private static JsonObject encoded(LyricsDocument document) throws Exception {
        byte[] compressed = SpicyLyricBridgeDocumentSerializer.serialize(document, "producer", 1, "track");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        return JsonParser.parseString(output.toString(StandardCharsets.UTF_8.name())).getAsJsonObject();
    }
}
