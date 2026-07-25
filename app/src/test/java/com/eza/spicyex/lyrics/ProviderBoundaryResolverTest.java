package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.eza.spicyex.lyrics.reading.ProviderBoundaryResolver;
import com.eza.spicyex.lyrics.reading.ReadingModels.Boundary;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.SpanJoinEvidence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProviderBoundaryResolverTest {
    @Test
    public void sharedProviderBoundaryCorpusMatches() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "lyrics-reading/v2/provider-boundary-corpus.json");
        assertNotNull(stream);
        JsonObject fixture = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
        assertEquals(2, fixture.get("schemaVersion").getAsInt());
        ProviderBoundaryResolver resolver = new ProviderBoundaryResolver();

        for (JsonElement element : fixture.getAsJsonArray("lines")) {
            JsonObject raw = element.getAsJsonObject();
            JsonObject expected = raw.getAsJsonObject("expected");
            ProviderBoundaryResolver.Resolution resolution = resolver.resolve(parsedLine(raw));
            String id = raw.get("id").getAsString();
            assertEquals(id, expected.get("canonicalText").getAsString(), resolution.canonical.text);
            assertEquals(id, expected.get("completeLineAccepted").getAsBoolean(),
                    resolution.completeLineAccepted);
            assertEquals(id, stringList(expected.getAsJsonArray("diagnostics")), resolution.diagnostics);

            JsonArray mappings = expected.getAsJsonArray("spanMappings");
            assertEquals(id, mappings.size(), resolution.canonical.spanMappings.size());
            for (int index = 0; index < mappings.size(); index++) {
                JsonArray tuple = mappings.get(index).getAsJsonArray();
                CanonicalSpanMapping actual = resolution.canonical.spanMappings.get(index);
                assertEquals(id, tuple.get(0).getAsInt(), actual.canonicalRange.startCp);
                assertEquals(id, tuple.get(1).getAsInt(), actual.canonicalRange.endCp);
            }

            JsonArray boundaries = expected.getAsJsonArray("boundaries");
            assertEquals(id, boundaries.size(), resolution.canonical.boundaries.size());
            for (int index = 0; index < boundaries.size(); index++) {
                JsonArray tuple = boundaries.get(index).getAsJsonArray();
                Boundary actual = resolution.canonical.boundaries.get(index);
                assertEquals(id, tuple.get(0).getAsInt(), actual.offsetCp);
                assertEquals(id, tuple.get(1).getAsString(), lowerCamel(actual.kind.toString()));
                assertEquals(id, tuple.get(2).getAsString(), actual.provenance);
            }

            JsonArray joins = expected.getAsJsonArray("joins");
            assertEquals(id, joins.size(), resolution.canonical.joins.size());
            for (int index = 0; index < joins.size(); index++) {
                JsonArray tuple = joins.get(index).getAsJsonArray();
                SpanJoinEvidence actual = resolution.canonical.joins.get(index);
                assertEquals(id, tuple.get(0).getAsString(), actual.relation.toString().toLowerCase());
                assertEquals(id, tuple.get(1).getAsString(), actual.provenance);
            }
        }
    }

    private static ParsedLine parsedLine(JsonObject raw) {
        List<SourceSpan> spans = new ArrayList<>();
        JsonArray tuples = raw.getAsJsonArray("spans");
        for (int index = 0; index < tuples.size(); index++) {
            JsonArray tuple = tuples.get(index).getAsJsonArray();
            Boolean flag = tuple.get(1).isJsonNull() ? null : tuple.get(1).getAsBoolean();
            spans.add(new SourceSpan(String.valueOf(index), tuple.get(0).getAsString(),
                    tuple.get(0).getAsString(), tuple.get(2).getAsLong(), tuple.get(3).getAsLong(),
                    flag, null));
        }
        return new ParsedLine(raw.get("id").getAsString(), raw.get("displayText").getAsString(), spans,
                null, ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
    }

    private static List<String> stringList(JsonArray values) {
        List<String> out = new ArrayList<>();
        for (JsonElement value : values) out.add(value.getAsString());
        return out;
    }

    private static String lowerCamel(String value) {
        String[] parts = value.toLowerCase().split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            out.append(Character.toUpperCase(parts[index].charAt(0))).append(parts[index].substring(1));
        }
        return out.toString();
    }
}
