package com.eza.spicyex.lyrics.reading;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import com.eza.spicyex.lyrics.LyricUtils;
import com.eza.spicyex.lyrics.reading.ReadingContracts.CanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.ReadingModels.Boundary;
import com.eza.spicyex.lyrics.reading.ReadingModels.BoundaryKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

public final class DefaultCanonicalLineBuilder implements CanonicalLineBuilder {
    private static String normalize(String value) {
        return LyricUtils.cleanInvisiblesPreserveEdges(
                Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC));
    }

    private static String trim(String value) {
        return value.replaceFirst("^\\s+", "").replaceFirst("\\s+$", "");
    }

    @Override
    public CanonicalLine build(ParsedLine line) {
        String text = normalize(line == null ? "" : line.displayText);
        if (text.isEmpty() && line != null) {
            StringBuilder fallback = new StringBuilder();
            for (SourceSpan span : line.spans) {
                fallback.append(trim(normalize(span.rawText == null || span.rawText.isEmpty()
                        ? span.cleanText : span.rawText)));
            }
            text = fallback.toString();
        }
        List<CanonicalSpanMapping> mappings = new ArrayList<>();
        List<Boundary> boundaries = new ArrayList<>();
        int searchUtf16 = 0;
        int previousEndUtf16 = 0;
        String previousRaw = "";
        for (int index = 0; index < line.spans.size(); index++) {
            SourceSpan span = line.spans.get(index);
            String normalized = normalize(span.rawText == null || span.rawText.isEmpty() ? span.cleanText : span.rawText);
            String clean = trim(normalized);
            int found = clean.isEmpty() ? searchUtf16 : text.indexOf(clean, searchUtf16);
            if (found < 0) found = Math.min(searchUtf16, text.length());
            int endUtf16 = Math.min(text.length(), found + clean.length());
            if (index > 0 && found > previousEndUtf16) {
                String gap = text.substring(previousEndUtf16, found);
                int whitespace = firstWhitespaceUtf16(gap);
                if (whitespace >= 0) {
                    int offset = CodePointRanges.length(text.substring(0, previousEndUtf16 + whitespace));
                    boolean explicit = normalized.matches("^\\s.*") || previousRaw.matches(".*\\s$");
                    boundaries.add(new Boundary(offset,
                            explicit ? BoundaryKind.EXPLICIT_WHITESPACE : BoundaryKind.INFERRED,
                            1.0, explicit ? "providerTextWhitespace" : "adapterDisplayText"));
                }
            }
            int startCp = CodePointRanges.length(text.substring(0, found));
            mappings.add(new CanonicalSpanMapping(span.id,
                    new TextRange(startCp, CodePointRanges.length(text.substring(0, endUtf16)))));
            searchUtf16 = endUtf16;
            previousEndUtf16 = endUtf16;
            previousRaw = normalized;
        }
        return new CanonicalLine(line.id, text, mappings, boundaries);
    }

    private static int firstWhitespaceUtf16(String value) {
        for (int index = 0; index < value.length();) {
            int cp = value.codePointAt(index);
            if (Character.isWhitespace(cp)) return index;
            index += Character.charCount(cp);
        }
        return -1;
    }
}
