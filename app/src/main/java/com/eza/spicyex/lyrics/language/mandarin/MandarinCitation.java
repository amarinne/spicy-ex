package com.eza.spicyex.lyrics.language.mandarin;

import java.text.Normalizer;

/** Standard Pinyin projection of the portable reading line: citation tones, app spelling. */
public final class MandarinCitation {
    private MandarinCitation() {
    }

    public static String render(MandarinReadingLine line, boolean tones) {
        StringBuilder out = new StringBuilder();
        boolean lastWasPinyin = false;
        for (MandarinToken token : line.tokens()) {
            if (!"han".equals(token.kind())) {
                out.append(token.text());
                lastWasPinyin = false;
                continue;
            }
            for (MandarinSyllable syllable : token.syllables()) {
                if (out.length() > 0 && lastWasPinyin) out.append(' ');
                if (syllable.isUnavailable()) {
                    out.append('?');
                } else {
                    // Standard Pinyin keeps the citation tone; the neutral flag is presentation data.
                    out.append(syllable(syllable.letters() + syllable.tone(), tones));
                }
                lastWasPinyin = true;
            }
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }

    private static String lettersOf(MandarinSyllable syllable) {
        return syllable.letters();
    }

    /** {@code nü3} to {@code nǚ}; no-tone output keeps the app's {@code v} convention. */
    public static String syllable(String numbered, boolean tones) {
        return tones ? toneMarked(numbered) : noTone(numbered);
    }

    private static String noTone(String numbered) {
        String letters = numbered;
        if (!letters.isEmpty() && letters.charAt(letters.length() - 1) >= '1'
                && letters.charAt(letters.length() - 1) <= '5') {
            letters = letters.substring(0, letters.length() - 1);
        }
        return letters.replace('ü', 'v');
    }

    private static String toneMarked(String numbered) {
        if (numbered == null || numbered.isEmpty()) return "";
        int tone = 5;
        int last = numbered.length() - 1;
        char c = numbered.charAt(last);
        if (c >= '1' && c <= '5') {
            tone = c - '0';
            numbered = numbered.substring(0, last);
        }
        String syllable = numbered.replace('v', 'ü').replace('V', 'Ü');
        if (tone >= 5) return syllable;

        int index = markIndex(syllable);
        if (index < 0) return syllable;
        String marked = markedVowel(syllable.charAt(index), tone);
        if (marked.isEmpty()) return syllable;
        return syllable.substring(0, index) + marked + syllable.substring(index + 1);
    }

    private static int markIndex(String syllable) {
        int a = indexOfAny(syllable, "aA");
        if (a >= 0) return a;
        int e = indexOfAny(syllable, "eE");
        if (e >= 0) return e;
        int ou = syllable.indexOf("ou");
        if (ou >= 0) return ou;
        int oU = syllable.indexOf("Ou");
        if (oU >= 0) return oU;
        for (int i = syllable.length() - 1; i >= 0; i--) {
            char c = syllable.charAt(i);
            if ("iIuUüÜoO".indexOf(c) >= 0) return i;
        }
        // Syllabic nasals (m, n, ng) carry the tone on the nasal.
        return Math.max(syllable.indexOf('m'), syllable.indexOf('n'));
    }

    private static int indexOfAny(String value, String chars) {
        for (int i = 0; i < value.length(); i++) {
            if (chars.indexOf(value.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static String markedVowel(char vowel, int tone) {
        switch (vowel) {
            case 'a': return String.valueOf("āáǎà".charAt(tone - 1));
            case 'e': return String.valueOf("ēéěè".charAt(tone - 1));
            case 'i': return String.valueOf("īíǐì".charAt(tone - 1));
            case 'o': return String.valueOf("ōóǒò".charAt(tone - 1));
            case 'u': return String.valueOf("ūúǔù".charAt(tone - 1));
            case 'ü': return String.valueOf("ǖǘǚǜ".charAt(tone - 1));
            case 'A': return String.valueOf("ĀÁǍÀ".charAt(tone - 1));
            case 'E': return String.valueOf("ĒÉĚÈ".charAt(tone - 1));
            case 'I': return String.valueOf("ĪÍǏÌ".charAt(tone - 1));
            case 'O': return String.valueOf("ŌÓǑÒ".charAt(tone - 1));
            case 'U': return String.valueOf("ŪÚǓÙ".charAt(tone - 1));
            case 'Ü': return String.valueOf("ǕǗǙǛ".charAt(tone - 1));
            case 'm': case 'M': case 'n': case 'N':
                return vowel + String.valueOf("\u0304\u0301\u030c\u0300".charAt(tone - 1));
            default: return "";
        }
    }
}
