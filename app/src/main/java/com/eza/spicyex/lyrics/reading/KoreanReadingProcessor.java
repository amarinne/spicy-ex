package com.eza.spicyex.lyrics.reading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.eza.spicyex.lyrics.KoreanDisplayMode;
import com.eza.spicyex.lyrics.SpicyRomanizer;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnitKind;

public final class KoreanReadingProcessor {
    private KoreanReadingProcessor() {}

    private static List<String> alignPieces(List<String> pieces, String display) {
        List<String> aligned = new ArrayList<>();
        int cursor = 0;
        for (String piece : pieces) {
            int found = piece.isEmpty() ? cursor : display.indexOf(piece, cursor);
            if (found < 0) return new ArrayList<>(pieces);
            aligned.add(display.substring(cursor, found) + piece);
            cursor = found + piece.length();
        }
        if (cursor < display.length() && !aligned.isEmpty()) {
            int last = aligned.size() - 1;
            aligned.set(last, aligned.get(last) + display.substring(cursor));
        }
        return aligned;
    }

    private static ReadingUnitKind kindFor(String source) {
        boolean hasHangul = false;
        boolean punctuationOnly = !source.isEmpty();
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL) hasHangul = true;
            int type = Character.getType(cp);
            boolean punctuation = (type >= Character.CONNECTOR_PUNCTUATION && type <= Character.OTHER_PUNCTUATION)
                    || (type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL);
            punctuationOnly &= punctuation;
            i += Character.charCount(cp);
        }
        if (hasHangul) return ReadingUnitKind.TRANSFORMED;
        return punctuationOnly ? ReadingUnitKind.PUNCTUATION : ReadingUnitKind.PASSTHROUGH;
    }

    private static String groupAt(String text, int startCp) {
        String before = CodePointRanges.slice(text, new ReadingModels.TextRange(0, startCp));
        int group = 0;
        boolean inWord = false;
        for (int i = 0; i < before.length();) {
            int cp = before.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                if (inWord) group++;
                inWord = false;
            } else {
                inWord = true;
            }
            i += Character.charCount(cp);
        }
        return "group-" + group;
    }

    public static ReadingAnnotation annotate(CanonicalLine canonical, KoreanDisplayMode mode) {
        KoreanDisplayMode effective = mode == null ? KoreanDisplayMode.RR_STANDARD : mode;
        List<String> pieces = SpicyRomanizer.romanizeKoreanDisplayPieces(canonical.text, effective);
        String display = SpicyRomanizer.romanizeKoreanForDisplay(canonical.text, effective).display;
        List<String> aligned = alignPieces(pieces, display);
        List<ReadingUnit> units = new ArrayList<>();
        for (int index = 0; index < canonical.spanMappings.size(); index++) {
            CanonicalSpanMapping mapping = canonical.spanMappings.get(index);
            int previousEnd = index > 0 ? canonical.spanMappings.get(index - 1).canonicalRange.endCp : 0;
            StringBuilder text = new StringBuilder();
            for (int cp = previousEnd; cp < mapping.canonicalRange.endCp; cp++) text.append(aligned.get(cp));
            String source = CodePointRanges.slice(canonical.text, mapping.canonicalRange);
            units.add(new ReadingUnit(mapping.canonicalRange, text.toString(), kindFor(source),
                    groupAt(canonical.text, mapping.canonicalRange.startCp),
                    Collections.singletonList(mapping.spanId)));
        }
        return new ReadingAnnotation("Korean", effective.value, ReadingProvenance.LOCAL, units);
    }

    public static String join(ReadingAnnotation annotation) {
        StringBuilder out = new StringBuilder();
        for (ReadingUnit unit : annotation.units) out.append(unit.text);
        return out.toString();
    }
}
