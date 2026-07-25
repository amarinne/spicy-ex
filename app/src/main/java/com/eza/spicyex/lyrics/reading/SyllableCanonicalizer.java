package com.eza.spicyex.lyrics.reading;

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
}
