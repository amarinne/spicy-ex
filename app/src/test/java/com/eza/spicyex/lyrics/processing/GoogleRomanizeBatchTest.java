package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.eza.spicyex.lyrics.cache.LyricCaches;

/**
 * Batched romanization: one request for a song, split back onto the lines it was asked about.
 *
 * <p>Fixtures are real translate_a/single responses to {@link GoogleEnhancer#batchQuery} text, so
 * the marker shapes asserted here are shapes the endpoint actually returns.
 */
public class GoogleRomanizeBatchTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = GoogleRomanizeBatchTest.class.getResourceAsStream("/google/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<GoogleEnhancer.BatchLine> lines(String... texts) {
        List<GoogleEnhancer.BatchLine> out = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) out.add(new GoogleEnhancer.BatchLine(i, texts[i]));
        return out;
    }

    @Test
    public void queryPutsEachLineBehindItsMarker() {
        assertEquals("[[SPX_000]] Я тебя люблю\n[[SPX_001]] Мы идём домой",
                GoogleEnhancer.batchQuery(lines("Я тебя люблю", "Мы идём домой")));
    }

    @Test
    public void oneResponseSplitsBackIntoLines() throws Exception {
        Map<Integer, String> ru = GoogleEnhancer.parseBatchRomanization(fixture("romanize_batch_ru.json"));
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), new ArrayList<>(ru.keySet()));
        assertEquals("YA tebya lyublyu", ru.get(0));
        assertEquals("Noch', ulitsa, fonar', apteka", ru.get(3));
        assertEquals("Ty ne odna", ru.get(4));

        Map<Integer, String> th = GoogleEnhancer.parseBatchRomanization(fixture("romanize_batch_th.json"));
        assertEquals(3, th.size());
        assertTrue(th.get(0).startsWith("c"));
    }

    /** The fixture is an answer to the query this code builds, markers and all. */
    @Test
    public void theFixtureAnswersTheQueryThisCodeSends() throws Exception {
        JsonArray sentences = JsonParser.parseString(fixture("romanize_batch_ru.json"))
                .getAsJsonArray().get(0).getAsJsonArray();
        String[] sent = GoogleEnhancer.batchQuery(lines(
                "I love you", "We're going home", "Stars over the city",
                "Night, street, lantern, pharmacy", "You are not alone")).split("\n", -1);

        // Google echoes the query back one sentence per line; only the last lacks a newline.
        int position = 0;
        for (JsonElement sentence : sentences) {
            if (!sentence.isJsonArray()) continue;
            JsonElement echoedSource = sentence.getAsJsonArray().get(0);
            if (echoedSource.isJsonNull()) continue;
            assertEquals(sent[position++], echoedSource.getAsString().trim());
        }
        assertEquals("one echo per line the query carried", sent.length, position);
    }

    @Test
    public void markersGoogleSpacedOutStillSplit() throws Exception {
        // Real: Google's Japanese romanization returns "[ [SPX _ 000] ] yoru ni kakeru [ [SPX _
        // 001] ] ..." - markers spaced out and the line breaks gone.
        Map<Integer, String> ja = GoogleEnhancer.parseBatchRomanization(
                fixture("romanize_batch_ja_spaced_markers.json"));
        assertEquals("yoru ni kakeru", ja.get(0));
        assertEquals("shizumu yō ni tokete yuku yō ni", ja.get(1));
        assertEquals("sayonara dakedatta", ja.get(2));
    }

    @Test
    public void fullWidthMarkersInTranslationsStillSplit() {
        String body = "[[[\"［［ＳＰＸ＿０００］］ 愛してる\\n［［SPX_001］］ 帰ろう\",\"x\",null,null]]]";
        Map<Integer, String> parsed = GoogleEnhancer.parseBatchTranslation(body);
        assertEquals("愛してる", parsed.get(0));
        assertEquals("帰ろう", parsed.get(1));
    }

    /**
     * The lane writes a reading onto the canonical row the line's own index names, so chunking
     * may regroup lines but must never renumber or reorder them.
     */
    @Test
    public void chunkingKeepsEachLineItsOwnIndex() {
        int[] indexes = {7, 3, 11, 0, 5};
        List<GoogleEnhancer.BatchLine> song = new ArrayList<>();
        for (int index : indexes) {
            song.add(new GoogleEnhancer.BatchLine(index, "Ночь, улица, фонарь, аптека"));
        }
        List<List<GoogleEnhancer.BatchLine>> whole = GoogleEnhancer.chunk(song, 100, 3000);
        assertEquals(1, whole.size());
        for (int position = 0; position < indexes.length; position++) {
            assertEquals(indexes[position], whole.get(0).get(position).index);
        }

        List<List<GoogleEnhancer.BatchLine>> split = GoogleEnhancer.chunk(song, 2, 3000);
        assertEquals(3, split.size());
        assertEquals(Arrays.asList(7, 3), new ArrayList<>(indexesOf(split.get(0))));
        assertEquals(Arrays.asList(11, 0), new ArrayList<>(indexesOf(split.get(1))));
        assertEquals(Arrays.asList(5), new ArrayList<>(indexesOf(split.get(2))));

        // Each request still numbers its own lines from zero, whatever the song's indexes were.
        assertEquals("[[SPX_000]] Ночь, улица, фонарь, аптека\n[[SPX_001]] Ночь, улица, фонарь, аптека",
                GoogleEnhancer.batchQuery(split.get(0)));
    }

    /**
     * Cache identity: a batched line keeps the exact key it owned when every line asked alone, so
     * a reading cached before batching is still found, and one fetched now is still found later.
     */
    @Test
    public void aBatchedLineKeepsItsOwnCacheKey() {
        String track = "batch-identity";
        List<String> asked = new ArrayList<>();
        Map<Integer, String> result = new LinkedHashMap<>();
        List<GoogleEnhancer.BatchLine> pending = GoogleEnhancer.romanizePending(track, "ru",
                lines("Я тебя люблю", "Мы идём домой"), key -> {
                    asked.add(key);
                    return key.equals(LyricCaches.romanizationKey(track, "ru", "Мы идём домой"))
                            ? "My idom domoy" : null;
                }, result);

        assertEquals(Arrays.asList(
                LyricCaches.romanizationKey(track, "ru", "Я тебя люблю"),
                LyricCaches.romanizationKey(track, "ru", "Мы идём домой")), asked);
        assertEquals(1, pending.size());
        assertEquals(0, pending.get(0).index);
        assertEquals(asked.get(0),
                LyricCaches.romanizationKey(track, "ru", pending.get(0).text));

        // The line already read costs no request, and keeps its own index in the result.
        assertEquals(Arrays.asList(1), new ArrayList<>(result.keySet()));
        assertEquals("My idom domoy", result.get(1));
    }

    /** A stored reading still in the source script is not a reading: it is asked again. */
    @Test
    public void aStoredValueThatIsStillSourceScriptIsAskedAgain() {
        String track = "batch-script";
        String text = "Мы идём домой";
        Map<String, String> cache = new LinkedHashMap<>();
        cache.put(LyricCaches.romanizationKey(track, "ru", text), text);

        List<GoogleEnhancer.BatchLine> pending = GoogleEnhancer.romanizePending(track, "ru",
                lines(text), cache::get, new LinkedHashMap<>());
        assertEquals(1, pending.size());
        assertEquals(LyricCaches.romanizationKey(track, "ru", text),
                LyricCaches.romanizationKey(track, "ru", pending.get(0).text));
    }

    /**
     * A chorus repeats its lines, and each repeat is a row of the song. Both rows go out in the
     * same batch and each comes back on its own index - the row a reading is written to, and the
     * one a map keyed by the per-text cache key would have dropped.
     */
    @Test
    public void repeatedLinesEachKeepTheirOwnRow() {
        String track = "batch-repeat";
        List<GoogleEnhancer.BatchLine> song = Arrays.asList(
                new GoogleEnhancer.BatchLine(4, "Я тебя люблю"),
                new GoogleEnhancer.BatchLine(9, "Мы идём домой"),
                new GoogleEnhancer.BatchLine(12, "Я тебя люблю"));
        Map<Integer, String> result = new LinkedHashMap<>();
        List<GoogleEnhancer.BatchLine> pending = GoogleEnhancer.romanizePending(track, "ru", song,
                key -> null, result);
        assertEquals(Arrays.asList(4, 9, 12), indexesOf(pending));

        // One query carries the repeat twice, each behind its own marker, and Google answers both.
        assertEquals("[[SPX_000]] Я тебя люблю\n[[SPX_001]] Мы идём домой\n[[SPX_002]] Я тебя люблю",
                GoogleEnhancer.batchQuery(pending));
        String body = "[[[\"[[SPX_000]] Я тебя люблю\",null,null,\n"
                + "\"[[SPX_000]] YA tebya lyublyu\"],"
                + "[\"[[SPX_001]] Мы идём домой\",null,null,\n"
                + "\"[[SPX_001]] My idom domoy\"],"
                + "[\"[[SPX_002]] Я тебя люблю\",null,null,\n"
                + "\"[[SPX_002]] YA tebya lyublyu\"]]]";
        Map<String, String> writes = new LinkedHashMap<>();
        List<GoogleEnhancer.BatchLine> missing = GoogleEnhancer.applyRomanized(pending,
                GoogleEnhancer.parseBatchRomanization(body), track, "ru", result, writes);

        assertTrue(missing.toString(), missing.isEmpty());
        assertEquals("YA tebya lyublyu", result.get(4));
        assertEquals("My idom domoy", result.get(9));
        assertEquals("YA tebya lyublyu", result.get(12));
        // Both rows of the repeat read and write the one cache key their text owns.
        assertEquals(Map.of(
                LyricCaches.romanizationKey(track, "ru", "Я тебя люблю"), "YA tebya lyublyu",
                LyricCaches.romanizationKey(track, "ru", "Мы идём домой"), "My idom domoy"),
                new LinkedHashMap<>(writes));
    }

    @Test
    public void aWholeSongIsOneRequest() {
        List<GoogleEnhancer.BatchLine> song = new ArrayList<>();
        for (int i = 0; i < 60; i++) song.add(new GoogleEnhancer.BatchLine(i, "Ночь, улица, фонарь, аптека"));
        // 60 lines went out as 60 requests; with the lane's limits they are one.
        assertEquals(1, GoogleEnhancer.chunk(song, 100, 3000).size());
        List<List<GoogleEnhancer.BatchLine>> capped = GoogleEnhancer.chunk(song, 100, 1800);
        assertEquals(2, capped.size());
        assertEquals(60, capped.get(0).size() + capped.get(1).size());
    }

    /** Chunking only regroups: no line is dropped, duplicated, or reordered. */
    @Test
    public void chunkingLosesAndReordersNothing() {
        List<GoogleEnhancer.BatchLine> song = new ArrayList<>();
        for (int i = 0; i < 60; i++) song.add(new GoogleEnhancer.BatchLine(i, "строка " + i));
        List<Integer> flattened = new ArrayList<>();
        int count = 0;
        for (List<GoogleEnhancer.BatchLine> chunk : GoogleEnhancer.chunk(song, 7, 400)) {
            count += chunk.size();
            flattened.addAll(indexesOf(chunk));
        }
        assertEquals(60, count);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 60; i++) expected.add(i);
        assertEquals(expected, flattened);
    }

    @Test
    public void noLinesToReadIsNoRequest() {
        assertTrue(GoogleEnhancer.romanizePending("t", "ru", null, key -> null, null).isEmpty());
        assertTrue(GoogleEnhancer.romanizePending("t", "ru", lines("", "   "),
                key -> null, null).isEmpty());
        assertTrue(GoogleEnhancer.chunk(lines("", "   "), 100, 3000).isEmpty());
    }

    @Test
    public void garbageIsNoLines() {
        assertTrue(GoogleEnhancer.parseBatchRomanization("<html>Sorry</html>").isEmpty());
        assertTrue(GoogleEnhancer.parseBatchTranslation("not json at all").isEmpty());
        // A reading without a marker is one blob, not a line, so nothing is claimed for a line.
        assertFalse(GoogleEnhancer.parseBatchRomanization(
                "[[[\"no markers here\",null,null,null]]]").containsKey(0));
    }

    private static List<Integer> indexesOf(List<GoogleEnhancer.BatchLine> chunk) {
        List<Integer> out = new ArrayList<>();
        for (GoogleEnhancer.BatchLine line : chunk) out.add(line.index);
        return out;
    }
}
