package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.lyrics.reading.DefaultCanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.KoreanReadingProcessor;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnitKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;

public class KoreanReadingProcessorTest {
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
                    span.get(0).getAsString(), span.get(0).getAsString(), span.get(2).getAsLong(),
                    span.get(3).getAsLong(), span.get(1).getAsBoolean(), null));
        }
        return new ParsedLine(raw.get("id").getAsString(),
                raw.getAsJsonObject("expected").get("canonicalText").getAsString(), spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
    }

    @Test
    public void joinedDisplayComesFromTimedUnitsInAllModes() {
        DefaultCanonicalLineBuilder builder = new DefaultCanonicalLineBuilder();
        JsonArray lines = fixture().getAsJsonArray("lines");
        for (int i = 0; i < lines.size(); i++) {
            CanonicalLine canonical = builder.build(parsedLine(lines.get(i).getAsJsonObject()));
            if (!SpicyTextDetection.itemKoreanTest(canonical.text)) continue;
            for (KoreanDisplayMode mode : KoreanDisplayMode.values()) {
                ReadingAnnotation annotation = KoreanReadingProcessor.annotate(canonical, mode);
                assertEquals(canonical.lineId + ":" + mode,
                        SpicyRomanizer.romanizeKoreanForDisplay(canonical.text, mode).display,
                        KoreanReadingProcessor.join(annotation));
                assertEquals(canonical.spanMappings.size(), annotation.units.size());
            }
        }
    }

    @Test
    public void mixedEnglishIsPassthroughAndOrdered() {
        JsonArray lines = fixture().getAsJsonArray("lines");
        JsonObject raw = null;
        for (int i = 0; i < lines.size(); i++) if ("camouflage-29".equals(lines.get(i).getAsJsonObject().get("id").getAsString())) raw = lines.get(i).getAsJsonObject();
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsedLine(raw));
        ReadingAnnotation annotation = KoreanReadingProcessor.annotate(canonical, KoreanDisplayMode.VN_PRONUNCIATION);
        assertEquals("jujo opssi da, Probably delete it", KoreanReadingProcessor.join(annotation));
        assertEquals(ReadingUnitKind.PASSTHROUGH, annotation.units.get(annotation.units.size() - 1).kind);
    }
}
