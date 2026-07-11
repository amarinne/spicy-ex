package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.lyrics.reading.CanonicalValidator;
import com.eza.spicyex.lyrics.reading.DefaultCanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.DefaultScriptPartitioner;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.LanguageContext;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ScriptRun;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;

public class CanonicalReadingTest {
    private JsonObject fixture() {
        return JsonParser.parseReader(new InputStreamReader(getClass().getClassLoader()
                .getResourceAsStream("lyrics-reading/v1/camouflage-provider.json"))).getAsJsonObject();
    }

    private ParsedLine parsedLine(JsonObject raw) {
        List<SourceSpan> spans = new ArrayList<>();
        JsonArray tuples = raw.getAsJsonArray("spans");
        for (int i = 0; i < tuples.size(); i++) {
            JsonArray span = tuples.get(i).getAsJsonArray();
            spans.add(new SourceSpan(raw.get("id").getAsString() + "-s" + i,
                    span.get(0).getAsString(), span.get(0).getAsString(),
                    span.get(2).getAsLong(), span.get(3).getAsLong(),
                    span.get(1).getAsBoolean(), null));
        }
        String expected = raw.getAsJsonObject("expected").get("canonicalText").getAsString();
        return new ParsedLine(raw.get("id").getAsString(), expected, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
    }

    @Test
    public void canonicalBuilderExactlyMapsCapturedRows() {
        DefaultCanonicalLineBuilder builder = new DefaultCanonicalLineBuilder();
        DefaultScriptPartitioner partitioner = new DefaultScriptPartitioner();
        JsonObject fixture = fixture();
        for (int i = 0; i < fixture.getAsJsonArray("lines").size(); i++) {
            JsonObject raw = fixture.getAsJsonArray("lines").get(i).getAsJsonObject();
            ParsedLine parsed = parsedLine(raw);
            CanonicalLine canonical = builder.build(parsed);
            List<ScriptRun> runs = partitioner.partition(canonical,
                    new LanguageContext(fixture.get("language").getAsString(), Collections.emptyList()));
            assertEquals(raw.getAsJsonObject("expected").get("canonicalText").getAsString(), canonical.text);
            assertEquals(parsed.spans.size(), canonical.spanMappings.size());
            assertTrue(CanonicalValidator.validate(canonical, runs).errors.toString(),
                    CanonicalValidator.validate(canonical, runs).valid);
        }
    }
}
