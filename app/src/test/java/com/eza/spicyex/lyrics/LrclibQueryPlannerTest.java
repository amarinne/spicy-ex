package com.eza.spicyex.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure JVM coverage for remaster-suffix normalization and duration-aware LRCLIB picks. */
public class LrclibQueryPlannerTest {
    @Test
    public void stripsRemasterFamilyMarkers() {
        assertEquals("allo allo", LrclibQueryPlanner.normalizedTitle("allo allo (Remaster)"));
        assertEquals("Je ne sais pas pourquoi",
                LrclibQueryPlanner.normalizedTitle("Je ne sais pas pourquoi (Remastered)"));
        assertEquals("Song", LrclibQueryPlanner.normalizedTitle("Song [Remix 2011]"));
        assertEquals("Song", LrclibQueryPlanner.normalizedTitle("Song - Remastered"));
        assertEquals("Song", LrclibQueryPlanner.normalizedTitle("Song (THE REMASTER)"));
    }

    @Test
    public void keepsNonVersionMarkersAndCoreTitles() {
        assertEquals("Milan Blue", LrclibQueryPlanner.normalizedTitle("Milan Blue"));
        assertEquals("Song (Live)", LrclibQueryPlanner.normalizedTitle("Song (Live)"));
        assertEquals("Song (Acoustic)", LrclibQueryPlanner.normalizedTitle("Song (Acoustic)"));
        assertEquals("(Remaster)", LrclibQueryPlanner.normalizedTitle("(Remaster)"));
        assertEquals("", LrclibQueryPlanner.normalizedTitle(""));
    }

    @Test
    public void queryPlanPutsRawFirst() {
        List<String> plan = LrclibQueryPlanner.queryTitles("allo allo (Remaster)");
        assertEquals(2, plan.size());
        assertEquals("allo allo (Remaster)", plan.get(0));
        assertEquals("allo allo", plan.get(1));
        assertEquals(1, LrclibQueryPlanner.queryTitles("Milan Blue").size());
    }

    @Test
    public void mergedPickPrefersSyncedOriginalOverPlainRemaster() {
        List<JsonObject> candidates = new ArrayList<>();
        candidates.add(obj("{'trackName':'Allo Allo (Remaster)','duration':201.0,"
                + "'plainLyrics':'allo allo','syncedLyrics':''}"));
        candidates.add(obj("{'trackName':'Allo Allo','duration':201.0,"
                + "'plainLyrics':'allo allo','syncedLyrics':'[00:00.00] allo allo'}"));
        assertEquals(1, LrclibQueryPlanner.pickBest(candidates, 201.0));
    }

    @Test
    public void rejectsAbsurdDurationsAndEmptyRecords() {
        List<JsonObject> candidates = new ArrayList<>();
        candidates.add(obj("{'trackName':'X','duration':4.0,"
                + "'plainLyrics':'x','syncedLyrics':'[00:00.00] x'}"));
        candidates.add(obj("{'trackName':'X','duration':259.0,"
                + "'plainLyrics':'x','syncedLyrics':'[00:00.00] x'}"));
        candidates.add(obj("{'trackName':'Empty','duration':261.0,"
                + "'plainLyrics':'','syncedLyrics':''}"));
        assertEquals(1, LrclibQueryPlanner.pickBest(candidates, 261.0));
    }

    @Test
    public void returnsMinusOneWhenNothingUsable() {
        List<JsonObject> candidates = new ArrayList<>();
        candidates.add(obj("{'trackName':'Empty','duration':200.0,"
                + "'plainLyrics':'','syncedLyrics':''}"));
        assertEquals(-1, LrclibQueryPlanner.pickBest(candidates, 200.0));
        assertEquals(-1, LrclibQueryPlanner.pickBest(new ArrayList<JsonObject>(), 200.0));
    }

    @Test
    public void freeTextUsesNormalizedTitle() {
        String query = LrclibQueryPlanner.freeTextQuery("allo allo (Remaster)", "Bonjour Suzuki");
        assertTrue(query.contains("allo allo"));
        assertTrue(query.contains("bonjour suzuki"));
    }

    private static JsonObject obj(String singleQuotedJson) {
        return JsonParser.parseString(singleQuotedJson.replace('\'', '"')).getAsJsonObject();
    }
}
