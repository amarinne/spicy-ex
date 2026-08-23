package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Text primitives the protocol is defined in terms of.
 *
 * <p>These deliberately reproduce JavaScript semantics rather than Java's, because the contract
 * they implement was written against them. {@code String.trim()} stops at U+0020 while the source
 * definition of "blank" includes NBSP and the Unicode separators; a validator that disagreed about
 * which lines are empty would accept and reject different responses than the fork it was ported
 * from, which is the one failure this whole slice exists to avoid.
 */
public final class AiText {
    private AiText() {
    }

    public static String nz(String value) {
        return value == null ? "" : value;
    }

    /** UTF-8 length, the unit every byte bound in the contract is expressed in. */
    public static int utf8Bytes(String value) {
        return nz(value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String nfc(String value) {
        return Digests.nfc(value);
    }

    public static String nfkc(String value) {
        String text = nz(value);
        return Normalizer.isNormalized(text, Normalizer.Form.NFKC)
                ? text : Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    public static String lower(String value) {
        return nz(value).toLowerCase(Locale.ROOT);
    }

    /** The whitespace set JavaScript's {@code trim} and {@code \s} recognize. */
    public static boolean isJsWhitespace(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0b: case '\f': case '\r': case ' ':
            case 0x00a0: case 0x1680: case 0x2028: case 0x2029: case 0x202f:
            case 0x205f: case 0x3000: case 0xfeff:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200a;
        }
    }

    public static String trim(String value) {
        String text = nz(value);
        int start = 0;
        int end = text.length();
        while (start < end && isJsWhitespace(text.charAt(start))) start++;
        while (end > start && isJsWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    public static String trimEnd(String value) {
        String text = nz(value);
        int end = text.length();
        while (end > 0 && isJsWhitespace(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** Collapses every run of whitespace to a single space. */
    public static String collapseWhitespace(String value) {
        String text = nz(value);
        StringBuilder out = new StringBuilder(text.length());
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isJsWhitespace(c)) {
                pending = out.length() > 0;
                continue;
            }
            if (pending) {
                out.append(' ');
                pending = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Number of {@code " / "}-delimited segments, counted literally.
     *
     * <p>Not {@code String.split}, which treats its argument as a regex and drops trailing empty
     * segments — a response ending in {@code " / "} would then match a source that does not.
     */
    public static int segmentCount(String value) {
        String text = nz(value);
        int count = 1;
        int at = text.indexOf(" / ");
        while (at >= 0) {
            count++;
            at = text.indexOf(" / ", at + 3);
        }
        return count;
    }

    /** Splits on the literal {@code " / "} delimiter, keeping every segment including empties. */
    public static java.util.List<String> splitSegments(String value) {
        String text = nz(value);
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        int at = text.indexOf(" / ");
        while (at >= 0) {
            out.add(text.substring(start, at));
            start = at + 3;
            at = text.indexOf(" / ", start);
        }
        out.add(text.substring(start));
        return out;
    }

    /**
     * True when the text carries a character no lyric row may contain: a line or paragraph
     * separator, or a C0/C1 control. These break the one-row-per-item shape the whole protocol
     * depends on, so they reject the chunk rather than being stripped.
     */
    public static boolean containsForbiddenText(String value) {
        String text = nz(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0x2028 || c == 0x2029 || c <= 0x1f || (c >= 0x7f && c <= 0x9f)) return true;
        }
        return false;
    }

    /** Strips Unicode punctuation and symbols, leaving single spaces in their place. */
    public static String stripPunctuationAndSymbols(String value) {
        String text = nz(value);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            out.append(isPunctuationOrSymbol(cp) ? ' ' : new String(Character.toChars(cp)));
        }
        return out.toString();
    }

    private static boolean isPunctuationOrSymbol(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /** True when any character of {@code value} belongs to the target orthography's script. */
    public static boolean containsTargetScript(String value, String orthography) {
        String text = nz(value);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isTargetScript(cp, orthography)) return true;
        }
        return false;
    }

    public static boolean isTargetScript(int codePoint, String orthography) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException notAssigned) {
            return false;
        }
        if (AiContract.ORTHOGRAPHY_KANA.equals(orthography)) {
            return script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA;
        }
        if (AiContract.ORTHOGRAPHY_HANGUL.equals(orthography)) {
            return script == Character.UnicodeScript.HANGUL;
        }
        if (AiContract.ORTHOGRAPHY_CYRILLIC.equals(orthography)) {
            return script == Character.UnicodeScript.CYRILLIC;
        }
        return script == Character.UnicodeScript.LATIN;
    }

    public static boolean isLatin(int codePoint) {
        return isTargetScript(codePoint, AiContract.ORTHOGRAPHY_LATIN);
    }
}
