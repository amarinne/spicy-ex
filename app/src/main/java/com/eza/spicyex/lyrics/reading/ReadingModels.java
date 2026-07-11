package com.eza.spicyex.lyrics.reading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ReadingModels {
    private ReadingModels() {}

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.emptyList() : values));
    }

    public static final class TextRange {
        public final int startCp;
        public final int endCp;
        public TextRange(int startCp, int endCp) { this.startCp = startCp; this.endCp = endCp; }
    }

    public enum ParagraphProvenance { PROVIDER, LINE_BOUNDARY, UNAVAILABLE }
    public enum BoundaryKind { EXPLICIT_WHITESPACE, PARAGRAPH, SCRIPT, INFERRED }
    public enum ReadingUnitKind { TRANSFORMED, PASSTHROUGH, PUNCTUATION }
    public enum ReadingProvenance { PROVIDER, LOCAL, REMOTE_FALLBACK }

    public static final class SourceSpan {
        public final String id;
        public final String rawText;
        public final String cleanText;
        public final long startMs;
        public final long endMs;
        public final Boolean providerPartOfWord;
        public final String paragraphId;
        public SourceSpan(String id, String rawText, String cleanText, long startMs, long endMs,
                          Boolean providerPartOfWord, String paragraphId) {
            this.id = id;
            this.rawText = rawText;
            this.cleanText = cleanText;
            this.startMs = startMs;
            this.endMs = endMs;
            this.providerPartOfWord = providerPartOfWord;
            this.paragraphId = paragraphId;
        }
    }

    public static final class ParsedLine {
        public final String id;
        public final String displayText;
        public final List<SourceSpan> spans;
        public final String paragraphId;
        public final ParagraphProvenance paragraphProvenance;
        public final Map<String, Object> providerAnnotations;
        public ParsedLine(String id, String displayText, List<SourceSpan> spans, String paragraphId,
                          ParagraphProvenance paragraphProvenance, Map<String, Object> providerAnnotations) {
            this.id = id;
            this.displayText = displayText;
            this.spans = immutable(spans);
            this.paragraphId = paragraphId;
            this.paragraphProvenance = paragraphProvenance;
            this.providerAnnotations = providerAnnotations == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(providerAnnotations);
        }
    }

    public static final class ParsedDocument {
        public final String id;
        public final String language;
        public final List<ParsedLine> lines;
        public ParsedDocument(String id, String language, List<ParsedLine> lines) {
            this.id = id;
            this.language = language;
            this.lines = immutable(lines);
        }
    }

    public static final class CanonicalSpanMapping {
        public final String spanId;
        public final TextRange canonicalRange;
        public CanonicalSpanMapping(String spanId, TextRange canonicalRange) {
            this.spanId = spanId;
            this.canonicalRange = canonicalRange;
        }
    }

    public static final class Boundary {
        public final int offsetCp;
        public final BoundaryKind kind;
        public final double confidence;
        public final String provenance;
        public Boundary(int offsetCp, BoundaryKind kind, double confidence, String provenance) {
            this.offsetCp = offsetCp;
            this.kind = kind;
            this.confidence = confidence;
            this.provenance = provenance;
        }
    }

    public static final class CanonicalLine {
        public final String lineId;
        public final String text;
        public final List<CanonicalSpanMapping> spanMappings;
        public final List<Boundary> boundaries;
        public CanonicalLine(String lineId, String text, List<CanonicalSpanMapping> spanMappings,
                             List<Boundary> boundaries) {
            this.lineId = lineId;
            this.text = text;
            this.spanMappings = immutable(spanMappings);
            this.boundaries = immutable(boundaries);
        }
    }

    public static final class ScriptRun {
        public final String script;
        public final TextRange canonicalRange;
        public ScriptRun(String script, TextRange canonicalRange) {
            this.script = script;
            this.canonicalRange = canonicalRange;
        }
    }

    public static final class ReadingUnit {
        public final TextRange canonicalRange;
        public final String text;
        public final ReadingUnitKind kind;
        public final String logicalGroupId;
        public final List<String> timingRefs;
        public ReadingUnit(TextRange canonicalRange, String text, ReadingUnitKind kind,
                           String logicalGroupId, List<String> timingRefs) {
            this.canonicalRange = canonicalRange;
            this.text = text;
            this.kind = kind;
            this.logicalGroupId = logicalGroupId;
            this.timingRefs = immutable(timingRefs);
        }
    }

    public static final class ReadingAnnotation {
        public final String processor;
        public final String mode;
        public final ReadingProvenance provenance;
        public final List<ReadingUnit> units;
        public ReadingAnnotation(String processor, String mode, ReadingProvenance provenance,
                                 List<ReadingUnit> units) {
            this.processor = processor;
            this.mode = mode;
            this.provenance = provenance;
            this.units = immutable(units);
        }
    }

    public static final class TimedReadingUnit {
        public final String spanId;
        public final TextRange canonicalRange;
        public final String text;
        public final String logicalGroupId;
        public TimedReadingUnit(String spanId, TextRange canonicalRange, String text, String logicalGroupId) {
            this.spanId = spanId;
            this.canonicalRange = canonicalRange;
            this.text = text;
            this.logicalGroupId = logicalGroupId;
        }
    }

    public static final class RenderPlan {
        public final String lineId;
        public final List<CanonicalSpanMapping> sourceUnits;
        public final List<ReadingUnit> readingUnits;
        public final List<TimedReadingUnit> timedReadingUnits;
        public final String joinedDisplayText;
        public final String translation;
        public RenderPlan(String lineId, List<CanonicalSpanMapping> sourceUnits,
                          List<ReadingUnit> readingUnits, List<TimedReadingUnit> timedReadingUnits,
                          String joinedDisplayText, String translation) {
            this.lineId = lineId;
            this.sourceUnits = immutable(sourceUnits);
            this.readingUnits = immutable(readingUnits);
            this.timedReadingUnits = immutable(timedReadingUnits);
            this.joinedDisplayText = joinedDisplayText;
            this.translation = translation;
        }
    }

    public static final class LanguageContext {
        public final String language;
        public final List<String> scripts;
        public LanguageContext(String language, List<String> scripts) {
            this.language = language;
            this.scripts = immutable(scripts);
        }
    }

    public static final class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = immutable(errors);
        }
    }
}
