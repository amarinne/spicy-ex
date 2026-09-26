package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Fixtures are real translate_a/single responses to {@link GoogleEnhancer#batchQuery} text. */
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

    @Test
    public void garbageIsNoLines() {
        assertTrue(GoogleEnhancer.parseBatchRomanization("<html>Sorry</html>").isEmpty());
    }
}
