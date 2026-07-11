package com.eza.spicyex.lyrics.reading;

import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

public final class CodePointRanges {
    private CodePointRanges() {}

    public static int length(String text) {
        String value = text == null ? "" : text;
        return value.codePointCount(0, value.length());
    }

    public static String slice(String text, TextRange range) {
        String value = text == null ? "" : text;
        int start = value.offsetByCodePoints(0, range.startCp);
        int end = value.offsetByCodePoints(0, range.endCp);
        return value.substring(start, end);
    }

    public static boolean isValid(String text, TextRange range) {
        int length = length(text);
        return range != null && range.startCp >= 0 && range.endCp >= range.startCp && range.endCp <= length;
    }

    public static int codePointOffsetToUtf16Index(String text, int offsetCp) {
        String value = text == null ? "" : text;
        return value.offsetByCodePoints(0, Math.max(0, Math.min(offsetCp, length(value))));
    }

    public static int utf16IndexToCodePointOffset(String text, int utf16Index) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(utf16Index, value.length()));
        return value.codePointCount(0, safe);
    }
}
