package com.eza.spicyex.lyrics.reading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.eza.spicyex.lyrics.KoreanDisplayMode;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.SpicyRomanizer;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.SpicyTextDetection;
import com.eza.spicyex.lyrics.reading.ReadingModels.Boundary;
import com.eza.spicyex.lyrics.reading.ReadingModels.BoundaryKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnitKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

public final class ReadingPlanFactory {
    private ReadingPlanFactory() {}

    public static RenderPlan korean(LyricsLine line, KoreanDisplayMode mode) {
        if (line == null) return null;
        List<SourceSpan> spans = new ArrayList<>();
        if (line.syllables != null && !line.syllables.isEmpty()) {
            for (int index = 0; index < line.syllables.size(); index++) {
                SyllableSegment seg = line.syllables.get(index);
                if (seg == null) continue;
                spans.add(new SourceSpan(spanId(seg, index), seg.sourceText, seg.text, seg.startMs, seg.endMs,
                        seg.partOfWord, null));
            }
        } else {
            spans.add(new SourceSpan("0", line.text, line.text, line.startMs, line.endMs, false, null));
        }
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical;
        if (line.syllables != null && !line.syllables.isEmpty()) {
            SpicyRomanizer.KoreanSyllableSource source = SpicyRomanizer.buildKoreanSyllableSource(line.syllables);
            List<CanonicalSpanMapping> mappings = new ArrayList<>();
            for (int index = 0; index < line.syllables.size(); index++) {
                SyllableSegment seg = line.syllables.get(index);
                int startCp = source.pieceStart(index);
                int countCp = seg == null || seg.text == null ? 0 : seg.text.trim().codePointCount(0, seg.text.trim().length());
                if (startCp < 0) startCp = index == 0 ? 0 : mappings.get(mappings.size() - 1).canonicalRange.endCp;
                mappings.add(new CanonicalSpanMapping(String.valueOf(index), new TextRange(startCp, startCp + countCp)));
            }
            List<Boundary> boundaries = new ArrayList<>();
            int cpCount = source.text.codePointCount(0, source.text.length());
            for (int offset = 0; offset < cpCount; offset++) {
                int utf16 = source.text.offsetByCodePoints(0, offset);
                if (Character.isWhitespace(source.text.codePointAt(utf16))) {
                    boundaries.add(new Boundary(offset, BoundaryKind.INFERRED, 1.0, "providerAdapter:legacySpacingEvidence"));
                }
            }
            canonical = new CanonicalLine(parsed.id, source.text, mappings, boundaries);
        } else {
            canonical = new DefaultCanonicalLineBuilder().build(parsed);
        }
        ReadingAnnotation annotation = KoreanReadingProcessor.annotate(canonical, mode);
        RenderPlan plan = new DefaultRenderPlanBuilder().build(parsed, canonical,
                Collections.singletonList(annotation));
        return DefaultRenderPlanBuilder.validate(plan).valid ? plan : null;
    }

    public static RenderPlan japanese(LyricsLine line, SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        if (line == null || reading == null || reading.romaji == null || reading.romaji.isEmpty()) return null;
        List<SyllableSegment> sourceSegments = line.syllables == null || line.syllables.isEmpty()
                ? Collections.singletonList(singleSegment(line)) : line.syllables;
        List<SourceSpan> spans = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < sourceSegments.size(); index++) {
            SyllableSegment seg = sourceSegments.get(index);
            String text = seg == null || seg.text == null ? "" : seg.text.trim();
            spans.add(new SourceSpan(spanId(seg, index), seg == null ? text : seg.sourceText, text,
                    seg == null ? line.startMs : seg.startMs,
                    seg == null ? line.endMs : seg.endMs, seg != null && seg.partOfWord, null));
            texts.add(text);
        }
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsed);
        // Finalized-analysis reuse: the reading already carries its analysis groups,
        // so timing projection must not tokenize the line a second time.
        List<String> parts = SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(reading, texts);
        for (int index = 0; index < parts.size(); index++) {
            if ((parts.get(index) == null || parts.get(index).isEmpty())
                    && texts.get(index).matches(".*\\p{IsLatin}.*")) parts.set(index, texts.get(index));
        }
        parts = align(parts, reading.romaji);
        List<ReadingUnit> units = new ArrayList<>();
        int group = 0;
        for (int index = 0; index < canonical.spanMappings.size(); index++) {
            if (index > 0 && parts.get(index) != null && !parts.get(index).isEmpty()) group++;
            String source = texts.get(index);
            units.add(new ReadingUnit(canonical.spanMappings.get(index).canonicalRange, parts.get(index),
                    SpicyTextDetection.itemJapaneseTest(source) ? ReadingUnitKind.TRANSFORMED : ReadingUnitKind.PASSTHROUGH,
                    "jp-" + group, Collections.singletonList(canonical.spanMappings.get(index).spanId)));
        }
        ReadingAnnotation annotation = new ReadingAnnotation("Japanese", "romaji", ReadingProvenance.LOCAL, units);
        RenderPlan plan = new DefaultRenderPlanBuilder().build(parsed, canonical, Collections.singletonList(annotation));
        return DefaultRenderPlanBuilder.validate(plan).valid ? plan : null;
    }

    private static SyllableSegment singleSegment(LyricsLine line) {
        SyllableSegment seg = new SyllableSegment();
        seg.spanId = "line";
        seg.text = line.text;
        seg.startMs = line.startMs;
        seg.endMs = line.endMs;
        return seg;
    }

    private static List<String> align(List<String> input, String display) {
        StringBuilder compactInput = new StringBuilder();
        for (String value : input) compactInput.append(value == null ? "" : value.replaceAll("\\s+", ""));
        String compactDisplay = display == null ? "" : display.replaceAll("\\s+", "");
        if (compactInput.toString().equals(compactDisplay)) {
            List<String> exact = new ArrayList<>();
            int displayCursor = 0;
            int compactCursor = 0;
            for (String value : input) {
                String chunk = value == null ? "" : value;
                String compactChunk = chunk.replaceAll("\\s+", "");
                if (compactChunk.isEmpty()) {
                    exact.add("");
                    continue;
                }
                compactCursor += compactChunk.length();
                int displayEnd = display.length();
                int seen = 0;
                for (int index = 0; index < display.length(); index++) {
                    if (!Character.isWhitespace(display.charAt(index))) seen++;
                    if (seen == compactCursor) {
                        displayEnd = index + 1;
                        break;
                    }
                }
                exact.add(display.substring(displayCursor, displayEnd));
                displayCursor = displayEnd;
            }
            if (displayCursor < display.length()) {
                for (int index = exact.size() - 1; index >= 0; index--) {
                    if (!exact.get(index).isEmpty()) {
                        exact.set(index, exact.get(index) + display.substring(displayCursor));
                        break;
                    }
                }
            }
            return exact;
        }
        List<String> out = new ArrayList<>(input);
        int cursor = 0;
        for (int index = 0; index < out.size(); index++) {
            String text = out.get(index) == null ? "" : out.get(index);
            if (text.isEmpty()) continue;
            int found = display.indexOf(text, cursor);
            if (found < 0) return input;
            out.set(index, display.substring(cursor, found) + text);
            cursor = found + text.length();
        }
        boolean any = false;
        for (String value : out) any |= value != null && !value.isEmpty();
        if (!any && !out.isEmpty()) out.set(0, display);
        else if (cursor < display.length()) {
            for (int index = out.size() - 1; index >= 0; index--) {
                if (out.get(index) != null && !out.get(index).isEmpty()) {
                    out.set(index, out.get(index) + display.substring(cursor));
                    break;
                }
            }
        }
        return out;
    }

    public static RenderPlan timedLegacy(LyricsLine line, String display, String processor) {
        if (line == null || line.syllables == null || line.syllables.isEmpty() || display == null || display.isEmpty()) return null;
        List<SourceSpan> spans = new ArrayList<>();
        for (int index = 0; index < line.syllables.size(); index++) {
            SyllableSegment seg = line.syllables.get(index);
            spans.add(new SourceSpan(spanId(seg, index), seg.sourceText, seg.text, seg.startMs, seg.endMs,
                    seg.partOfWord, null));
        }
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsed);
        List<String> chunks = new ArrayList<>();
        for (SyllableSegment seg : line.syllables) {
            String value = seg.romanizedText == null || seg.romanizedText.isEmpty() ? seg.text : seg.romanizedText;
            chunks.add(value == null ? "" : value.trim());
        }
        chunks = align(chunks, display);
        List<ReadingUnit> units = new ArrayList<>();
        for (int index = 0; index < canonical.spanMappings.size(); index++) {
            String source = line.syllables.get(index).text == null ? "" : line.syllables.get(index).text.trim();
            String chunk = chunks.get(index);
            units.add(new ReadingUnit(canonical.spanMappings.get(index).canonicalRange, chunk,
                    chunk.trim().equals(source) ? ReadingUnitKind.PASSTHROUGH : ReadingUnitKind.TRANSFORMED,
                    "legacy-" + index, Collections.singletonList(canonical.spanMappings.get(index).spanId)));
        }
        RenderPlan plan = new DefaultRenderPlanBuilder().build(parsed, canonical,
                Collections.singletonList(new ReadingAnnotation(processor, "local", ReadingProvenance.LOCAL, units)));
        return DefaultRenderPlanBuilder.validate(plan).valid ? plan : null;
    }

    public static RenderPlan lineFallback(LyricsLine line, String display, String provenance) {
        if (line == null || display == null || display.isEmpty()) return null;
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text,
                Collections.singletonList(new SourceSpan("line", line.text, line.text, line.startMs, line.endMs, false, null)),
                null, ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsed);
        ReadingUnit unit = new ReadingUnit(new TextRange(0, CodePointRanges.length(canonical.text)), display,
                ReadingUnitKind.TRANSFORMED, "line-fallback", Collections.emptyList());
        ReadingProvenance source = "remoteFallback".equals(provenance)
                ? ReadingProvenance.REMOTE_FALLBACK : ReadingProvenance.PROVIDER;
        return new DefaultRenderPlanBuilder().build(parsed, canonical, Collections.singletonList(
                new ReadingAnnotation("Fallback", "line", source, Collections.singletonList(unit))));
    }

    private static String spanId(SyllableSegment segment, int index) {
        if (segment != null && segment.spanId != null && !segment.spanId.trim().isEmpty()) return segment.spanId;
        return String.valueOf(index);
    }
}
