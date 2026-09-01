package com.eza.spicyex.lyrics.reading;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.JoinRelation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.SpanJoinEvidence;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

/** Applies one canonical provider-boundary resolution to mutable adapter segments. */
public final class SyllableCanonicalizer {
    private SyllableCanonicalizer() {}

    public static CanonicalLine canonicalize(String lineId, String displayText,
                                             List<SyllableSegment> segments) {
        List<SourceSpan> spans = new ArrayList<>();
        for (int index = 0; segments != null && index < segments.size(); index++) {
            SyllableSegment segment = segments.get(index);
            if (segment == null) continue;
            if (segment.spanId == null || segment.spanId.trim().isEmpty()) {
                segment.spanId = String.valueOf(index);
            }
            String raw = segment.sourceText == null || segment.sourceText.isEmpty()
                    ? segment.text : segment.sourceText;
            spans.add(new SourceSpan(segment.spanId, raw, segment.text, segment.startMs, segment.endMs,
                    segment.providerPartOfWord, null));
        }
        ParsedLine parsed = new ParsedLine(lineId, displayText, spans, null,
                ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new ProviderBoundaryResolver().resolve(parsed).canonical;
        Map<String, CanonicalSpanMapping> mappings = new HashMap<>();
        for (CanonicalSpanMapping mapping : canonical.spanMappings) mappings.put(mapping.spanId, mapping);
        Map<String, SpanJoinEvidence> joins = new HashMap<>();
        for (SpanJoinEvidence join : canonical.joins) joins.put(join.afterSpanId, join);
        for (SyllableSegment segment : segments) {
            if (segment == null) continue;
            CanonicalSpanMapping mapping = mappings.get(segment.spanId);
            if (mapping != null) {
                segment.canonicalStartCp = mapping.canonicalRange.startCp;
                segment.canonicalEndCp = mapping.canonicalRange.endCp;
                segment.text = CodePointRanges.slice(canonical.text, mapping.canonicalRange);
            }
            SpanJoinEvidence join = joins.get(segment.spanId);
            segment.boundaryAfter = join != null && join.relation == JoinRelation.BOUNDARY;
            segment.boundaryProvenance = join == null ? "lineEnd" : join.provenance;
            segment.partOfWord = !segment.boundaryAfter;
        }
        return canonical;
    }

    /** Restores provider-authored glyph forms when they are NFKC-equivalent and range-safe.
     * Canonical text still owns matching and ranges; display keeps Japanese punctuation such as ？. */
    public static String displayText(CanonicalLine canonical, List<SyllableSegment> segments) {
        if (canonical == null) return "";
        return restoreAuthoredGlyphs(canonical.text, canonical.spanMappings, segments);
    }

    /** Applies display restoration to a cached canonical line without rebuilding provider joins. */
    public static String restoreAuthoredGlyphs(String canonicalText, List<SyllableSegment> segments) {
        if (canonicalText == null || segments == null) return canonicalText == null ? "" : canonicalText;
        List<CanonicalSpanMapping> mappings = new ArrayList<>();
        for (SyllableSegment segment : segments) {
            if (segment == null || segment.canonicalStartCp < 0
                    || segment.canonicalEndCp <= segment.canonicalStartCp) continue;
            mappings.add(new CanonicalSpanMapping(segment.spanId,
                    new TextRange(segment.canonicalStartCp, segment.canonicalEndCp)));
        }
        return restoreAuthoredGlyphs(canonicalText, mappings, segments);
    }

    private static String restoreAuthoredGlyphs(String canonicalText,
                                                 List<CanonicalSpanMapping> spanMappings,
                                                 List<SyllableSegment> segments) {
        if (canonicalText == null || spanMappings == null || segments == null) return "";
        Map<String, SyllableSegment> byId = new HashMap<>();
        for (SyllableSegment segment : segments) {
            if (segment != null && segment.spanId != null) byId.put(segment.spanId, segment);
        }
        StringBuilder out = new StringBuilder();
        int cursorCp = 0;
        for (CanonicalSpanMapping mapping : spanMappings) {
            if (mapping == null || mapping.canonicalRange == null) continue;
            if (cursorCp < mapping.canonicalRange.startCp) {
                out.append(CodePointRanges.slice(canonicalText,
                        new TextRange(cursorCp, mapping.canonicalRange.startCp)));
            }
            String canonicalPiece = CodePointRanges.slice(canonicalText, mapping.canonicalRange);
            SyllableSegment segment = byId.get(mapping.spanId);
            String authored = trimWhitespace(segment == null ? "" : segment.sourceText);
            if (!authored.isEmpty()
                    && authored.codePointCount(0, authored.length())
                    == canonicalPiece.codePointCount(0, canonicalPiece.length())
                    && Normalizer.normalize(authored, Normalizer.Form.NFKC).equals(canonicalPiece)) {
                out.append(authored);
                segment.text = authored;
            } else {
                out.append(canonicalPiece);
            }
            cursorCp = mapping.canonicalRange.endCp;
        }
        int totalCp = CodePointRanges.length(canonicalText);
        if (cursorCp < totalCp) {
            out.append(CodePointRanges.slice(canonicalText,
                    new TextRange(cursorCp, totalCp)));
        }
        return out.toString();
    }

    private static String trimWhitespace(String value) {
        if (value == null || value.isEmpty()) return "";
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }
}
