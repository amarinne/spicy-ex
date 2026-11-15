package com.eza.spicyex.lyrics.language.mandarin;

import com.eza.spicyex.lyrics.language.MandarinReadings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The cross-app parity corpus for Mandarin eza式 revision 3 ({@code eza-2026-11-15.3}).
 *
 * <p>{@code tests/fixtures/eza-rev3-parity.json} in cjk-labs is the asserted corpus: the {@code eza}
 * column is the full provider output, which this port must reproduce line for line. The
 * {@code pinyin} column is the desktop app's plain package output (pinyin-pro), not an eza layer;
 * this app's Standard Pinyin stays its own plain package, so that column is checked separately as
 * a recorded, non-asserted difference count.
 *
 * <p>{@code eza-rev3-parity-changes.json} records every row that changed from revision 2 with a
 * reason; this test checks the record covers the accepted rows and that each recorded reason and
 * text still holds.
 */
public class MandarinRevision3ParityTest {
    private static MandarinReader reader;

    @BeforeClass
    public static void loadReader() throws Exception {
        reader = MandarinReadings.reader();
        assertNotNull(reader);
    }

    @Test
    public void parityCorpusMatchesThePortablePipeline() throws Exception {
        JsonObject fixture = resource("eza-rev3-parity.json");
        assertEquals("cjk-labs.eza-rev3-parity.v1", fixture.get("schema").getAsString());
        assertEquals(MandarinEza.REVISION, fixture.get("policy").getAsString());
        assertEquals("eza-2026-11-15.3", MandarinEza.REVISION);
        assertEquals("eza-2026-11-15.3/adapter-2026-11-15.1", MandarinEza.ADAPTER_REVISION);
        assertEquals("0.7.0", MandarinEza.PROVIDER_VERSION);
        assertEquals("mandarin-g2pm-reader-2026-11-15.3", MandarinReader.REVISION);
        JsonArray cases = fixture.getAsJsonArray("cases");
        assertTrue("the corpus keeps at least 110 lines", cases.size() >= 110);
        List<String> failures = new ArrayList<>();
        for (JsonElement element : cases) {
            JsonObject row = element.getAsJsonObject();
            String source = row.get("source").getAsString();
            String expected = row.get("eza").getAsString();
            String actual = MandarinEza.render(reader.read(source), true);
            if (!expected.equals(actual)) {
                failures.add(source + ": eza " + actual + " (want " + expected + ")");
            }
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void revisionTwoChangesCarryTheirReasons() throws Exception {
        JsonObject changes = resource("eza-rev3-parity-changes.json");
        assertEquals("cjk-labs.eza-rev3-parity-changes.v1", changes.get("schema").getAsString());
        assertEquals(MandarinEza.REVISION, changes.get("policy").getAsString());
        assertEquals("eza-2026-11-12.2", changes.get("fromPolicy").getAsString());
        JsonObject fixture = resource("eza-rev3-parity.json");
        Map<String, String> eza = new HashMap<>();
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject row = element.getAsJsonObject();
            eza.put(row.get("source").getAsString(), row.get("eza").getAsString());
        }
        JsonArray rows = changes.getAsJsonArray("rows");
        assertEquals(17, rows.size());
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String source = row.get("source").getAsString();
            assertTrue("corpus covers " + source, eza.containsKey(source));
            assertEquals("recorded new text for " + source, row.get("to").getAsString(),
                    eza.get(source));
            assertTrue("recorded old text differs for " + source,
                    !row.get("from").getAsString().equals(row.get("to").getAsString()));
            assertTrue("reason for " + source, row.get("reason").getAsString().trim().length() > 0);
            String kind = row.get("kind").getAsString();
            assertTrue("known kind for " + source + ": " + kind,
                    List.of("third-tone", "repeated-character", "neutral-mark", "tone-4",
                            "reading-gap").contains(kind));
        }
    }

    @Test
    public void standardPinyinColumnStaysThePlainPackage() throws Exception {
        // The corpus pinyin column is the desktop app's pinyin-pro output. This app's Standard
        // Pinyin is the plain pinyin4j package by owner ruling, so it agrees on the rows that only
        // share citation readings and differs where the platform package writes tone 3 as a breve
        // (nĭ) or keeps a full tone where pinyin-pro reduces it (这个 zhè gè vs zhè ge). Both pinned
        // counts exist so routing Standard Pinyin through the eza reader or its layers fails loudly:
        // the layers produce the eza column, which differs on all but the toneless 啦啦啦啦 row.
        JsonArray cases = resource("eza-rev3-parity.json").getAsJsonArray("cases");
        int identical = 0;
        List<String> likeEza = new ArrayList<>();
        for (JsonElement element : cases) {
            JsonObject row = element.getAsJsonObject();
            String source = row.get("source").getAsString();
            String plain = com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor
                    .romanizeChinesePinyinLine(source, true);
            if (row.get("pinyin").getAsString().equals(plain)) identical++;
            if (row.get("eza").getAsString().equals(plain)) likeEza.add(source);
        }
        assertEquals("plain-package agreement with the desktop column", 46, identical);
        assertEquals("rows where every syllable is toneless for both", 1, likeEza.size());
        assertEquals("啦啦啦啦", likeEza.get(0));
        assertEquals(112, cases.size());
    }

    private static JsonObject resource(String name) throws IOException {
        try (InputStream in = MandarinRevision3ParityTest.class.getResourceAsStream(
                "/mandarin/" + name)) {
            if (in == null) throw new IOException("Missing test resource: " + name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
