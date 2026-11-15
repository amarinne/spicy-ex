package com.eza.spicyex.lyrics.cache;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessedLyricsCacheMeaningTest {
    @Test public void pollutedCachedRowCannotCompleteMeaningOrHideValidRows() {
        LyricsDocument document = new LyricsDocument();
        for (String text : new String[]{"hola", "adios"}) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            document.lines.add(line);
        }
        CanonicalBase base = CanonicalBase.fromDocument("track", document);
        JsonObject record = new JsonObject();
        record.addProperty("complete", true);
        JsonObject rows = new JsonObject();
        record.add("rows", rows);
        for (int i = 0; i < 2; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("text", document.lines.get(i).text);
            row.addProperty("translatedText", i == 0 ? "【SPX_八】 Hello" : "Goodbye");
            rows.add(base.rows.get(i).rowId, row);
        }
        ProcessedLyricsCache.Applied applied = ProcessedLyricsCache.applyMeaningRecord(document, base, record);
        assertTrue(applied.present);
        assertFalse(applied.complete);
        assertEquals(1, applied.rows);
        assertEquals("", document.lines.get(0).translatedText);
        assertEquals("Goodbye", document.lines.get(1).translatedText);
        assertTrue("saved data stays intact", record.get("complete").getAsBoolean());
    }
}
