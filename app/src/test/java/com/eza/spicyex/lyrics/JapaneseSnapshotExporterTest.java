package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in deterministic mobile oracle export for cross-platform Japanese fixtures. */
public class JapaneseSnapshotExporterTest {
    @Test
    public void exportWhenPathsProvided() throws Exception {
        String inputPath = System.getenv("JP_SNAPSHOT_INPUT");
        String outputPath = System.getenv("JP_SNAPSHOT_OUTPUT");
        Assume.assumeTrue(inputPath != null && outputPath != null);
        JsonObject source = JsonParser.parseString(new String(Files.readAllBytes(Path.of(inputPath)), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray output = new JsonArray();
        for (var lineElement : source.getAsJsonArray("lines")) {
            JsonObject lineJson = lineElement.getAsJsonObject();
            String text = lineJson.get("displayText").getAsString();
            LyricsLine line = new LyricsLine();
            line.text = text;
            for (var spanElement : lineJson.getAsJsonArray("spans")) {
                JsonObject span = spanElement.getAsJsonObject();
                SyllableSegment segment = new SyllableSegment();
                segment.text = span.get("text").getAsString();
                segment.startMs = span.has("beginMs") ? span.get("beginMs").getAsLong() : 0L;
                segment.endMs = span.has("endMs") ? span.get("endMs").getAsLong() : 0L;
                segment.partOfWord = !segment.text.matches(".*\\s$");
                line.syllables.add(segment);
            }
            SpicyJapaneseChineseProcessor.JapaneseDebugSnapshot debug =
                    SpicyJapaneseChineseProcessor.debugJapaneseSnapshot(text, null);
            SpicyJapaneseChineseProcessor.JapaneseReading reading =
                    SpicyJapaneseChineseProcessor.analyzeJapaneseLine(text, null);
            RenderPlan plan = ReadingPlanFactory.japanese(line, reading);
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("id", lineJson.get("id").getAsString());
            snapshot.add("debug", new Gson().toJsonTree(debug));
            snapshot.add("renderPlan", new Gson().toJsonTree(plan));
            output.add(snapshot);
        }
        JsonObject root = new JsonObject();
        root.addProperty("sourceSha256", source.get("sourceSha256").getAsString());
        root.add("lines", output);
        Files.write(Path.of(outputPath), (new Gson().newBuilder().setPrettyPrinting().create().toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
