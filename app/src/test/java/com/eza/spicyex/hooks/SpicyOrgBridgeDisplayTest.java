package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.SpicyOrgAttribution;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class SpicyOrgBridgeDisplayTest {
    @Test public void orgLyricsCanLeaveViaDisplayBridgeWithoutChangingCredits() throws Exception {
        for (String origin : new String[]{"spicy_org", "spicy_org_cache"}) {
            for (String source : new String[]{"spicy_lyrics", "apple_music", "spotify", "musixmatch"}) {
                LyricsDocument doc = new LyricsDocument();
                doc.fetchSource = origin;
                doc.spicyOrgSource = source;
                doc.spicyOrgUploader = "Uploader";
                doc.spicyOrgUploaderUrl = "https://spicylyrics.org/uid/uploader";
                doc.spicyOrgMaker = "Maker";
                doc.spicyOrgMakerUrl = "https://spicylyrics.org/uid/maker";
                AppliedLine line = new AppliedLine();
                line.text = "display lyric";
                line.endMs = 1000;
                doc.appliedLines.add(line);
                String sourceLabel = SpicyOrgAttribution.sourceLabel(doc);

                byte[] encoded = SpicyLyricBridgeDocumentSerializer.serialize(doc, "test", 1, "track");
                JsonObject json;
                try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
                    json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
                assertEquals("display lyric", json.getAsJsonArray("rows").get(0).getAsJsonObject()
                        .get("text").getAsString());
                assertEquals(origin, doc.fetchSource);
                assertEquals(sourceLabel, SpicyOrgAttribution.sourceLabel(doc));
                assertEquals("https://spicylyrics.org/uid/uploader", doc.spicyOrgUploaderUrl);
                assertEquals("https://spicylyrics.org/uid/maker", doc.spicyOrgMakerUrl);
                assertEquals("spicy_lyrics".equals(source) ? 3 : 1, SpicyOrgAttribution.credits(doc).size());
            }
        }
    }
}
