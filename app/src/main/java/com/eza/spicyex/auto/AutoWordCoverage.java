package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/** Partial timing must not replace complete lyric text with a subset of its words. */
final class AutoWordCoverage {
    static final int MAX_WORDS = 128;
    private AutoWordCoverage() { }

    static boolean withinLimit(int count) { return count <= MAX_WORDS; }

    static boolean complete(AppliedLine line) {
        if (line.words.isEmpty() || !withinLimit(line.words.size())) return false;
        int length = line.text.codePointCount(0, line.text.length());
        boolean[] covered = new boolean[length];
        for (SyllableSegment word : line.words) {
            if (word == null || word.canonicalStartCp < 0
                    || word.canonicalEndCp <= word.canonicalStartCp
                    || word.canonicalEndCp > length) return false;
            for (int cp = word.canonicalStartCp; cp < word.canonicalEndCp; cp++) covered[cp] = true;
        }
        int cp = 0;
        for (int offset = 0; offset < line.text.length(); cp++) {
            int character = line.text.codePointAt(offset);
            if (!covered[cp] && !Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
            offset += Character.charCount(character);
        }
        return true;
    }

    static void fallbackIfIncomplete(AppliedLine line) {
        if (line.syntheticWords || !complete(line)) {
            line.words.clear();
            line.syntheticWords = true;
        }
    }
}
