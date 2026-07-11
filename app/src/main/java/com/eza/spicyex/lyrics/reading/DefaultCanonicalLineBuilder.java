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
        return LyricUtils.cleanInvisibles(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC));
    }

    private static String trim(String value) {
        return value.replaceFirst("^\\s+", "").replaceFirst("\\s+$", "");
    }

    @Override
    public CanonicalLine build(ParsedLine line) {
        StringBuilder text = new StringBuilder();
        List<CanonicalSpanMapping> mappings = new ArrayList<>();
        List<Boundary> boundaries = new ArrayList<>();
        for (int index = 0; index < line.spans.size(); index++) {
            SourceSpan span = line.spans.get(index);
            String normalized = normalize(span.rawText == null || span.rawText.isEmpty() ? span.cleanText : span.rawText);
            if (index > 0) {
                SourceSpan previous = line.spans.get(index - 1);
                String previousText = normalize(previous.rawText == null || previous.rawText.isEmpty()
                        ? previous.cleanText : previous.rawText);
                boolean explicit = normalized.matches("^\\s.*") || previousText.matches(".*\\s$");
                if ((explicit || Boolean.FALSE.equals(previous.providerPartOfWord))
                        && text.length() > 0 && !Character.isWhitespace(text.charAt(text.length() - 1))) {
                    int offset = CodePointRanges.length(text.toString());
                    boundaries.add(new Boundary(offset,
                            explicit ? BoundaryKind.EXPLICIT_WHITESPACE : BoundaryKind.INFERRED,
                            1.0, explicit ? "providerTextWhitespace" : "providerPartOfWord:false"));
                    text.append(' ');
                }
            }
            int startCp = CodePointRanges.length(text.toString());
            text.append(trim(normalized));
            mappings.add(new CanonicalSpanMapping(span.id,
                    new TextRange(startCp, CodePointRanges.length(text.toString()))));
        }
        return new CanonicalLine(line.id, text.toString(), mappings, boundaries);
    }
}
