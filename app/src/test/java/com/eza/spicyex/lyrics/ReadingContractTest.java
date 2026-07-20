package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.eza.spicyex.lyrics.reading.CodePointRanges;
import com.eza.spicyex.lyrics.reading.DefaultCanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.DefaultScriptPartitioner;
import com.eza.spicyex.lyrics.reading.KoreanReadingProcessor;
import com.eza.spicyex.lyrics.reading.ReadingModels.Boundary;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.LanguageContext;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ScriptRun;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

public class ReadingContractTest {
    @Test
    public void coordinatesUseUnicodeCodePoints() {
        String text = "A😀한국";
        assertEquals(4, CodePointRanges.length(text));
        assertEquals("😀한", CodePointRanges.slice(text, new TextRange(1, 3)));
        assertEquals(3, CodePointRanges.codePointOffsetToUtf16Index(text, 2));
        assertEquals(2, CodePointRanges.utf16IndexToCodePointOffset(text, 3));
        assertTrue(CodePointRanges.isValid(text, new TextRange(0, 4)));
        assertFalse(CodePointRanges.isValid(text, new TextRange(3, 5)));
    }

    @Test
    public void unavailableParagraphProvenanceIsExplicit() {
        ParsedLine line = new ParsedLine("line-1", "text", Collections.emptyList(), null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        assertNull(line.paragraphId);
        assertEquals(ParagraphProvenance.UNAVAILABLE, line.paragraphProvenance);
    }

    /** Maps enum names (EXPLICIT_WHITESPACE) to the fixtures' shared lowerCamel strings. */
    private static String sharedName(Object enumValue) {
        String[] parts = enumValue.toString().toLowerCase().split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return out.toString();
    }

    private static ParsedLine parsedLine(JsonObject raw) {
        List<SourceSpan> spans = new ArrayList<>();
        JsonArray tuples = raw.getAsJsonArray("spans");
        for (int i = 0; i < tuples.size(); i++) {
            JsonArray span = tuples.get(i).getAsJsonArray();
            spans.add(new SourceSpan(raw.get("id").getAsString() + "-s" + i,
                    span.get(0).getAsString(), span.get(0).getAsString(),
                    span.get(2).getAsLong(), span.get(3).getAsLong(),
                    span.get(1).getAsBoolean(), null));
        }
        return new ParsedLine(raw.get("id").getAsString(),
                raw.getAsJsonObject("expected").get("canonicalText").getAsString(), spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
    }

    /**
     * The cross-platform contract: for every fixture line, this platform's pipeline
     * must reproduce the shared expected values exactly. The desktop repo asserts the
     * same fixtures (byte-identical copies) through its own pipeline.
     */
    private void assertFixtureSemantics(String resource) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resource);
        assertTrue(resource, stream != null);
        JsonObject fixture = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        DefaultCanonicalLineBuilder builder = new DefaultCanonicalLineBuilder();
        DefaultScriptPartitioner partitioner = new DefaultScriptPartitioner();
        JsonArray lines = fixture.getAsJsonArray("lines");
        for (int i = 0; i < lines.size(); i++) {
            JsonObject raw = lines.get(i).getAsJsonObject();
            String id = raw.get("id").getAsString();
            JsonObject expected = raw.getAsJsonObject("expected");
            ParsedLine parsed = parsedLine(raw);
            CanonicalLine canonical = builder.build(parsed);
            assertEquals(id, expected.get("canonicalText").getAsString(), canonical.text);

            JsonArray boundaries = expected.getAsJsonArray("boundaries");
            assertEquals(id + " boundaries", boundaries.size(), canonical.boundaries.size());
            for (int b = 0; b < boundaries.size(); b++) {
                JsonArray e = boundaries.get(b).getAsJsonArray();
                Boundary actual = canonical.boundaries.get(b);
                assertEquals(id, e.get(0).getAsInt(), actual.offsetCp);
                assertEquals(id, e.get(1).getAsString(), sharedName(actual.kind));
            }

            JsonArray mappings = expected.getAsJsonArray("spanMappings");
            assertEquals(id + " spanMappings", mappings.size(), canonical.spanMappings.size());
            for (int m = 0; m < mappings.size(); m++) {
                JsonArray e = mappings.get(m).getAsJsonArray();
                assertEquals(id, e.get(0).getAsInt(), canonical.spanMappings.get(m).canonicalRange.startCp);
                assertEquals(id, e.get(1).getAsInt(), canonical.spanMappings.get(m).canonicalRange.endCp);
            }

            List<ScriptRun> runs = partitioner.partition(canonical,
                    new LanguageContext(fixture.get("language").getAsString(), Collections.emptyList()));
            JsonArray runArr = expected.getAsJsonArray("scriptRuns");
            assertEquals(id + " scriptRuns", runArr.size(), runs.size());
            for (int r = 0; r < runArr.size(); r++) {
                JsonArray e = runArr.get(r).getAsJsonArray();
                assertEquals(id, e.get(0).getAsInt(), runs.get(r).canonicalRange.startCp);
                assertEquals(id, e.get(1).getAsInt(), runs.get(r).canonicalRange.endCp);
                assertEquals(id, e.get(2).getAsString(), runs.get(r).script);
            }

            if (expected.has("readingMode")) {
                KoreanDisplayMode mode = KoreanDisplayMode.fromSetting(expected.get("readingMode").getAsString());
                ReadingAnnotation annotation = KoreanReadingProcessor.annotate(canonical, mode);
                JsonArray units = expected.getAsJsonArray("readingUnits");
                assertEquals(id + " readingUnits", units.size(), annotation.units.size());
                for (int u = 0; u < units.size(); u++) {
                    JsonObject e = units.get(u).getAsJsonObject();
                    ReadingUnit actual = annotation.units.get(u);
                    assertEquals(id, e.get("text").getAsString(), actual.text);
                    assertEquals(id, e.get("kind").getAsString(), sharedName(actual.kind));
                    assertEquals(id, e.get("startCp").getAsInt(), actual.canonicalRange.startCp);
                    assertEquals(id, e.get("endCp").getAsInt(), actual.canonicalRange.endCp);
                }
                JsonArray timed = expected.getAsJsonArray("timedReadingUnits");
                assertEquals(id + " timedReadingUnits", timed.size(), annotation.units.size());
                for (int t = 0; t < timed.size(); t++) {
                    JsonArray e = timed.get(t).getAsJsonArray();
                    int spanIndex = e.get(0).getAsInt();
                    ReadingUnit actual = annotation.units.get(t);
                    assertEquals(id, 1, actual.timingRefs.size());
                    assertEquals(id, id + "-s" + spanIndex, actual.timingRefs.get(0));
                    assertEquals(id, e.get(1).getAsString(), actual.text);
                }
                assertEquals(id, expected.get("joinedDisplayText").getAsString(),
                        KoreanReadingProcessor.join(annotation));
            }

            if (expected.has("pronunciationDisplays")) {
                JsonObject displays = expected.getAsJsonObject("pronunciationDisplays");
                for (String modeName : new String[]{"rrPronunciation", "vnPronunciation"}) {
                    KoreanDisplayMode mode = KoreanDisplayMode.fromSetting(modeName);
                    ReadingAnnotation annotation = KoreanReadingProcessor.annotate(canonical, mode);
                    assertEquals(id + " " + modeName, displays.get(modeName).getAsString(),
                            KoreanReadingProcessor.join(annotation));
                }
            }

            if (expected.has("japanesePlan")) {
                LyricsLine lyric = new LyricsLine();
                lyric.text = canonical.text;
                for (int s = 0; s < parsed.spans.size(); s++) {
                    SourceSpan source = parsed.spans.get(s);
                    SyllableSegment segment = new SyllableSegment();
                    segment.spanId = String.valueOf(s);
                    segment.sourceText = source.rawText;
                    segment.text = source.cleanText == null ? "" : source.cleanText.trim();
                    segment.startMs = source.startMs;
                    segment.endMs = source.endMs;
                    segment.partOfWord = Boolean.TRUE.equals(source.providerPartOfWord);
                    lyric.syllables.add(segment);
                }
                SpicyJapaneseChineseProcessor.JapaneseReading reading =
                        SpicyJapaneseChineseProcessor.analyzeJapaneseLine(lyric.text, null);
                RenderPlan plan = ReadingPlanFactory.japanese(lyric, reading);
                JsonObject japanese = expected.getAsJsonObject("japanesePlan");
                assertTrue(id + " japanese plan", plan != null);
                assertEquals(id, japanese.get("joinedDisplayText").getAsString(), plan.joinedDisplayText);
                JsonArray owners = japanese.getAsJsonArray("timedSpanIds");
                assertEquals(id, owners.size(), plan.timedReadingUnits.size());
                for (int t = 0; t < owners.size(); t++) {
                    assertEquals(id, owners.get(t).getAsString(), plan.timedReadingUnits.get(t).spanId);
                }
            }
        }
    }

    @Test
    public void camouflageFixtureSemanticsMatchSharedExpectations() {
        assertFixtureSemantics("lyrics-reading/v1/camouflage-provider.json");
    }

    @Test
    public void scriptCorpusFixtureSemanticsMatchSharedExpectations() {
        assertFixtureSemantics("lyrics-reading/v1/script-corpus.json");
    }
}
