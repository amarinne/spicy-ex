package com.eza.spicyex.lyrics.reading;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.eza.spicyex.lyrics.KoreanDisplayMode;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.SpicyTextDetection;
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
                        seg.providerPartOfWord, null));
            }
        } else {
            spans.add(new SourceSpan("0", line.text, line.text, line.startMs, line.endMs, false, null));
        }
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsed);
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
                    seg == null ? line.endMs : seg.endMs,
                    seg == null ? null : seg.providerPartOfWord, null));
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
        if (parts == null) return lineFallback(line, reading.romaji, "local");
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
        return validAuthoritativePlan(plan, reading.romaji)
                ? plan : lineFallback(line, reading.romaji, "local");
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
        StringBuilder inputShape = new StringBuilder();
        for (String value : input) inputShape.append(alignmentShape(value));
        String displayShape = alignmentShape(display);
        if (inputShape.toString().equals(displayShape)) {
            List<String> exact = new ArrayList<>();
            int displayCursor = 0;
            int shapeCursor = 0;
            for (String value : input) {
                String chunk = value == null ? "" : value;
                int chunkShapeLength = CodePointRanges.length(alignmentShape(chunk));
                if (chunkShapeLength == 0) {
                    exact.add("");
                    continue;
                }
                shapeCursor += chunkShapeLength;
                int displayEnd = display.length();
                int seen = 0;
                for (int index = 0; index < display.length();) {
                    int cp = display.codePointAt(index);
                    int next = index + Character.charCount(cp);
                    seen = CodePointRanges.length(alignmentShape(display.substring(0, next)));
                    if (seen == shapeCursor) {
                        displayEnd = next;
                        break;
                    }
                    index = next;
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
            return joined(exact).equals(display) ? exact : null;
        }
        List<String> out = new ArrayList<>(input);
        int cursor = 0;
        for (int index = 0; index < out.size(); index++) {
            String text = out.get(index) == null ? "" : out.get(index);
            if (text.isEmpty()) continue;
            int found = display.indexOf(text, cursor);
            if (found < 0) return null;
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
        return joined(out).equals(display) ? out : null;
    }

    /**
     * Alignment compares pronunciation shape, not tone-mark spelling. A whole-line phrase may
     * correctly use a neutral tone while an isolated provider span uses its dictionary tone
     * ({@code 记得 -> jì de}, but {@code 得 -> dé}). The full-line reading remains authoritative;
     * this folded shape only decides whether its exact text can be projected over existing spans.
     */
    private static String alignmentShape(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        boolean previousBaseWasLatin = false;
        for (int index = 0; index < normalized.length();) {
            int cp = normalized.codePointAt(index);
            int type = Character.getType(cp);
            boolean mark = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            if (mark) {
                // Tone marks may differ between whole-phrase and isolated pinyin. Thai, Indic,
                // and other script marks carry letters' meaning and must remain part of shape.
                if (!previousBaseWasLatin) out.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp)) {
                previousBaseWasLatin = false;
            } else {
                out.appendCodePoint(cp);
                previousBaseWasLatin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
            }
            index += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String joined(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append(value == null ? "" : value);
        return out.toString();
    }

    private static boolean validAuthoritativePlan(RenderPlan plan, String display) {
        return plan != null
                && DefaultRenderPlanBuilder.validate(plan).valid
                && (display == null ? "" : display).equals(plan.joinedDisplayText);
    }

    public static RenderPlan timedLegacy(LyricsLine line, String display, String processor) {
        if (line == null || line.syllables == null || line.syllables.isEmpty() || display == null || display.isEmpty()) return null;
        List<SourceSpan> spans = new ArrayList<>();
        for (int index = 0; index < line.syllables.size(); index++) {
            SyllableSegment seg = line.syllables.get(index);
            spans.add(new SourceSpan(spanId(seg, index), seg.sourceText, seg.text, seg.startMs, seg.endMs,
                    seg.providerPartOfWord, null));
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
        if (chunks == null) return lineFallback(line, display, "local");
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
        return validAuthoritativePlan(plan, display) ? plan : lineFallback(line, display, "local");
    }

    public static RenderPlan lineFallback(LyricsLine line, String display, String provenance) {
        if (line == null || display == null || display.isEmpty()) return null;
        ParsedLine parsed = new ParsedLine("line-" + line.startMs + "-" + line.endMs, line.text,
                Collections.singletonList(new SourceSpan("line", line.text, line.text, line.startMs, line.endMs, false, null)),
                null, ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(parsed);
        boolean local = "local".equals(provenance);
        ReadingUnit unit = new ReadingUnit(new TextRange(0, CodePointRanges.length(canonical.text)),
                display, ReadingUnitKind.TRANSFORMED,
                local ? "local-line-fallback" : "line-fallback", Collections.emptyList());
        ReadingProvenance source = local ? ReadingProvenance.LOCAL
                : "remoteFallback".equals(provenance) ? ReadingProvenance.REMOTE_FALLBACK
                : "ai".equals(provenance) ? ReadingProvenance.AI : ReadingProvenance.PROVIDER;
        return new DefaultRenderPlanBuilder().build(parsed, canonical, Collections.singletonList(
                new ReadingAnnotation("Fallback", "line", source, Collections.singletonList(unit))));
    }

    /** True only when a plan contains reading work, not source-script passthrough timing. */
    public static boolean hasTransformedReading(RenderPlan plan) {
        if (plan == null || plan.readingUnits == null) return false;
        for (ReadingUnit unit : plan.readingUnits) {
            if (unit != null && unit.kind == ReadingUnitKind.TRANSFORMED
                    && unit.text != null && !unit.text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String spanId(SyllableSegment segment, int index) {
        if (segment != null && segment.spanId != null && !segment.spanId.trim().isEmpty()) return segment.spanId;
        return String.valueOf(index);
    }
}
