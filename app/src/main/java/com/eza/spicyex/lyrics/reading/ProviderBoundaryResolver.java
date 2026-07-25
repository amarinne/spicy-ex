package com.eza.spicyex.lyrics.reading;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.eza.spicyex.lyrics.LyricUtils;
import com.eza.spicyex.lyrics.reading.ReadingModels.Boundary;
import com.eza.spicyex.lyrics.reading.ReadingModels.BoundaryKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.JoinRelation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.SpanJoinEvidence;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

/** Resolves provider-specific boundary evidence once, at the adapter edge. */
public final class ProviderBoundaryResolver {
    public Resolution resolve(ParsedLine line) {
        if (line == null) {
            return new Resolution(new CanonicalLine("", "", Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList()), false,
                    Collections.singletonList("missingLine"));
        }

        List<SpanState> spans = new ArrayList<>();
        for (SourceSpan span : line.spans) spans.add(new SpanState(span));
        CompleteLineAlignment complete = alignCompleteLine(normalize(line.displayText), spans);
        List<String> diagnostics = new ArrayList<>();
        if (!normalize(line.displayText).isEmpty() && complete == null) {
            diagnostics.add("invalidCompleteProviderLine");
        }

        StringBuilder text = new StringBuilder();
        List<CanonicalSpanMapping> mappings = new ArrayList<>();
        List<Boundary> boundaries = new ArrayList<>();
        List<SpanJoinEvidence> joins = new ArrayList<>();
        for (int index = 0; index < spans.size(); index++) {
            SpanState current = spans.get(index);
            int startCp = CodePointRanges.length(text.toString());
            text.append(current.core);
            int endCp = CodePointRanges.length(text.toString());
            mappings.add(new CanonicalSpanMapping(current.source.id, new TextRange(startCp, endCp)));
            if (index >= spans.size() - 1) continue;

            ResolvedJoin join = resolveJoin(current, spans.get(index + 1),
                    complete == null ? null : complete.separators.get(index),
                    complete != null && complete.hasWhitespace);
            joins.add(new SpanJoinEvidence(current.source.id, join.relation,
                    join.confidence, join.provenance));
            if (join.relation == JoinRelation.BOUNDARY) {
                int offsetCp = CodePointRanges.length(text.toString());
                boundaries.add(new Boundary(offsetCp, join.kind, join.confidence, join.provenance));
                text.append(' ');
            } else if (join.relation == JoinRelation.UNKNOWN) {
                diagnostics.add("unknownJoinAfter:" + current.source.id);
            }
        }
        return new Resolution(new CanonicalLine(line.id, text.toString(), mappings, boundaries, joins),
                complete != null, diagnostics);
    }

    private static ResolvedJoin resolveJoin(SpanState current, SpanState next, String providerSeparator,
                                            boolean completeLineHasWhitespace) {
        if (endsWithWhitespace(current.normalizedRaw) || startsWithWhitespace(next.normalizedRaw)) {
            return boundary(BoundaryKind.EXPLICIT_WHITESPACE, 1.0, "rawEdgeWhitespace");
        }
        if (providerSeparator != null && containsWhitespace(providerSeparator)) {
            return boundary(BoundaryKind.INFERRED, 1.0, "completeProviderLine");
        }
        if (current.source.paragraphId != null && next.source.paragraphId != null
                && !current.source.paragraphId.equals(next.source.paragraphId)) {
            return boundary(BoundaryKind.PARAGRAPH, 1.0, "providerParagraph");
        }

        JoinRelation script = scriptRelation(current.core, next.core);
        if (script == JoinRelation.ATTACHED) {
            return attached(0.9, "scriptFallback");
        }
        if (script == JoinRelation.BOUNDARY) {
            return boundary(BoundaryKind.SCRIPT, 0.9, "scriptFallback");
        }
        if (providerSeparator != null && providerSeparator.isEmpty() && completeLineHasWhitespace) {
            return attached(0.85, "completeProviderLineCompact");
        }
        if (current.source.providerPartOfWord != null) {
            return current.source.providerPartOfWord
                    ? attached(0.7, "providerFlagAfterSpan")
                    : boundary(BoundaryKind.INFERRED, 0.7, "providerFlagAfterSpan");
        }
        // A compact complete line proves content/order, but absence of a space is weak evidence.
        if (providerSeparator != null) return attached(0.5, "completeProviderLineCompact");
        return new ResolvedJoin(JoinRelation.UNKNOWN, BoundaryKind.INFERRED, 0.0, "unresolved");
    }

    private static JoinRelation scriptRelation(String previous, String next) {
        int previousCp = lastCodePoint(previous);
        int nextCp = firstCodePoint(next);
        if (previousCp < 0 || nextCp < 0) return JoinRelation.UNKNOWN;
        if (("1".equals(previous) || "2".equals(previous)) && nextCp == '人') {
            return JoinRelation.ATTACHED;
        }
        if (isClosingPunctuation(nextCp) || isJoinPunctuation(nextCp)
                || isOpeningPunctuation(previousCp) || isJoinPunctuation(previousCp)) {
            return JoinRelation.ATTACHED;
        }
        boolean previousJapanese = isJapanese(previousCp);
        boolean nextJapanese = isJapanese(nextCp);
        if (previousJapanese && nextJapanese) return JoinRelation.ATTACHED;
        if (isLatinOrDigit(previousCp) && nextJapanese
                || previousJapanese && isLatinOrDigit(nextCp)) {
            return JoinRelation.BOUNDARY;
        }
        Character.UnicodeScript previousScript = Character.UnicodeScript.of(previousCp);
        Character.UnicodeScript nextScript = Character.UnicodeScript.of(nextCp);
        if (previousScript != nextScript && isLetterScript(previousScript) && isLetterScript(nextScript)) {
            return JoinRelation.BOUNDARY;
        }
        return JoinRelation.UNKNOWN;
    }

    private static boolean isLetterScript(Character.UnicodeScript script) {
        return script == Character.UnicodeScript.LATIN
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.CYRILLIC
                || script == Character.UnicodeScript.GREEK;
    }

    private static boolean isJapanese(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    private static boolean isLatinOrDigit(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN || Character.isDigit(cp);
    }

    private static boolean isClosingPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.END_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || ",.;:!?、。！？)]}〉》」』】〕〗〙〛".indexOf(cp) >= 0;
    }

    private static boolean isOpeningPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.START_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || "([{〈《「『【〔〖〘〚".indexOf(cp) >= 0;
    }

    private static boolean isJoinPunctuation(int cp) {
        return cp == '\'' || cp == '’' || cp == '-' || cp == '‐' || cp == '‑';
    }

    private static ResolvedJoin attached(double confidence, String provenance) {
        return new ResolvedJoin(JoinRelation.ATTACHED, BoundaryKind.INFERRED, confidence, provenance);
    }

    private static ResolvedJoin boundary(BoundaryKind kind, double confidence, String provenance) {
        return new ResolvedJoin(JoinRelation.BOUNDARY, kind, confidence, provenance);
    }

    private static CompleteLineAlignment alignCompleteLine(String displayText, List<SpanState> spans) {
        String candidate = trim(displayText);
        if (candidate.isEmpty() || spans.isEmpty()) return null;
        List<String> separators = new ArrayList<>();
        int cursor = 0;
        for (int index = 0; index < spans.size(); index++) {
            String core = spans.get(index).core;
            if (core.isEmpty()) return null;
            int found = candidate.indexOf(core, cursor);
            if (found < 0 || !onlyWhitespace(candidate.substring(cursor, found))) return null;
            if (index > 0) separators.add(candidate.substring(cursor, found));
            cursor = found + core.length();
        }
        if (!onlyWhitespace(candidate.substring(cursor))) return null;
        return new CompleteLineAlignment(separators, containsAnyWhitespace(candidate));
    }

    private static String normalize(String value) {
        return LyricUtils.cleanInvisiblesPreserveEdges(
                Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC));
    }

    private static String trim(String value) {
        if (value == null || value.isEmpty()) return "";
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!Character.isWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!Character.isWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    private static boolean onlyWhitespace(String value) {
        for (int index = 0; index < value.length();) {
            int cp = value.codePointAt(index);
            if (!Character.isWhitespace(cp)) return false;
            index += Character.charCount(cp);
        }
        return true;
    }

    private static boolean containsAnyWhitespace(String value) {
        for (int index = 0; index < value.length();) {
            int cp = value.codePointAt(index);
            if (Character.isWhitespace(cp)) return true;
            index += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsWhitespace(String value) {
        return value != null && !value.isEmpty() && onlyWhitespace(value);
    }

    private static boolean startsWithWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.codePointAt(0));
    }

    private static boolean endsWithWhitespace(String value) {
        return value != null && !value.isEmpty()
                && Character.isWhitespace(value.codePointBefore(value.length()));
    }

    private static int firstCodePoint(String value) {
        return value == null || value.isEmpty() ? -1 : value.codePointAt(0);
    }

    private static int lastCodePoint(String value) {
        return value == null || value.isEmpty() ? -1 : value.codePointBefore(value.length());
    }

    private static final class SpanState {
        final SourceSpan source;
        final String normalizedRaw;
        final String core;
        SpanState(SourceSpan source) {
            this.source = source;
            this.normalizedRaw = normalize(source.rawText == null || source.rawText.isEmpty()
                    ? source.cleanText : source.rawText);
            this.core = trim(normalizedRaw);
        }
    }

    private static final class CompleteLineAlignment {
        final List<String> separators;
        final boolean hasWhitespace;
        CompleteLineAlignment(List<String> separators, boolean hasWhitespace) {
            this.separators = separators;
            this.hasWhitespace = hasWhitespace;
        }
    }

    private static final class ResolvedJoin {
        final JoinRelation relation;
        final BoundaryKind kind;
        final double confidence;
        final String provenance;
        ResolvedJoin(JoinRelation relation, BoundaryKind kind, double confidence, String provenance) {
            this.relation = relation;
            this.kind = kind;
            this.confidence = confidence;
            this.provenance = provenance;
        }
    }

    public static final class Resolution {
        public final CanonicalLine canonical;
        public final boolean completeLineAccepted;
        public final List<String> diagnostics;
        Resolution(CanonicalLine canonical, boolean completeLineAccepted, List<String> diagnostics) {
            this.canonical = canonical;
            this.completeLineAccepted = completeLineAccepted;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
}
