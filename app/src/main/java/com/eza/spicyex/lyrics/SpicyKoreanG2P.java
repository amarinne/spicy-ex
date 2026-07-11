package com.eza.spicyex.lyrics;

/**
 * Lightweight Korean grapheme-to-phoneme pass for the "follow sound" (pronunciation)
 * romanization mode. Operates on decomposed jamo across syllable boundaries, then romanizes
 * with Revised-Romanization letter values.
 *
 * <p>Covers the common, visible lyric rules: resyllabification/liaison before ㅇ, ㅎ aspiration
 * and ㅎ-elision, palatalization (ㄷ/ㅌ + 이), obstruent nasalization (ㄱ/ㄷ/ㅂ + ㄴ/ㅁ), ㄹ→ㄴ
 * nasalization, ㄴ/ㄹ lateralization, and post-obstruent tensification (제23항). It is NOT a
 * full G2P: morphology-sensitive tensification (compounds, -(으)ㄹ futures) is not derived —
 * compound cases stay hardcoded and ㄹ-future cases go through the display bigram layer.
 * The "follow spelling" mode ({@link SpicyRomanizer#romanizeKorean}) stays the default.
 */
final class SpicyKoreanG2P {
    private SpicyKoreanG2P() {
    }

    // Onset (초성) romanization, index 0..18.
    private static final String[] ONSET = {
            "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    };
    // Nucleus (중성) romanization, index 0..20.
    private static final String[] VOWEL = {
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    };
    // Final realization of the 7 representative codas, by coda index used internally.
    // Index uses the standard final table 0..27 but only representative finals are ever romanized.
    private static final String[] CODA_ROMAN = new String[28];
    static {
        CODA_ROMAN[0] = "";
        CODA_ROMAN[1] = "k";   // ㄱ
        CODA_ROMAN[4] = "n";   // ㄴ
        CODA_ROMAN[7] = "t";   // ㄷ
        CODA_ROMAN[8] = "l";   // ㄹ
        CODA_ROMAN[16] = "m";  // ㅁ
        CODA_ROMAN[17] = "p";  // ㅂ
        CODA_ROMAN[21] = "ng"; // ㅇ
    }

    private static final int NUC_UI = 19, NUC_I = 20;       // ㅢ, ㅣ
    private static final int CODA_NONE = 0, CODA_G = 1, CODA_N = 4, CODA_D = 7, CODA_L = 8, CODA_M = 16, CODA_B = 17, CODA_NG = 21, CODA_H = 27;
    private static final int ON_G = 0, ON_N = 2, ON_D = 3, ON_R = 5, ON_B = 7, ON_J = 12, ON_CH = 14, ON_K = 15, ON_T = 16, ON_P = 17, ON_H = 18, ON_NULL = 11, ON_S = 9, ON_SS = 10;

    static String romanize(String text) {
        if (text == null) return null;
        java.util.List<String> pieces = romanizeReadablePieces(text);
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) out.append(piece);
        return out.toString();
    }

    static String codaRoman(int coda) {
        if (coda == 0) return "";
        int rep = codaRepresentative(coda);
        String value = rep >= 0 && rep < CODA_ROMAN.length ? CODA_ROMAN[rep] : null;
        return value == null ? "" : value;
    }

    static String pronounceHangulForDisplay(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (SpicyRomanizer.isHangul(cp) || (run.length() > 0 && Character.isWhitespace(cp))) {
                run.appendCodePoint(cp);
            } else {
                flushPronouncedRun(run, out);
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        flushPronouncedRun(run, out);
        return out.toString();
    }

    private static void flushPronouncedRun(StringBuilder run, StringBuilder out) {
        if (run.length() == 0) return;
        String[] words = run.toString().trim().split("\\s+");
        String leading = run.toString().replaceFirst("^(\\s*).*$", "$1");
        String trailing = run.toString().replaceFirst("^.*?(\\s*)$", "$1");
        out.append(leading);
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(pronounceWordForDisplay(words[i]));
        }
        out.append(trailing);
        run.setLength(0);
    }

    private static String pronounceWordForDisplay(String word) {
        if (word == null || word.isEmpty()) return "";
        // Compound (합성어) tensification is morphology-sensitive and stays hardcoded;
        // post-obstruent tensification is now a rule in applyRules.
        if ("눈빛".equals(word)) return "눈삗";
        if ("눈동자".equals(word)) return "눈똥자";
        if ("해돋이".equals(word)) return "해도지";
        if ("색연필".equals(word)) return "생년필";
        if ("희미해져".equals(word)) return "히미해저";
        word = rewriteUi(word);
        java.util.ArrayList<int[]> run = new java.util.ArrayList<>();
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            int[] syl = SpicyRomanizer.decompose(cp);
            if (syl != null) run.add(syl);
            i += Character.charCount(cp);
        }
        applyRules(run);
        StringBuilder out = new StringBuilder();
        for (int[] syl : run) out.append(compose(syl[0], syl[1], syl[2]));
        return out.toString();
    }

    private static String rewriteUi(String word) {
        if (word.length() > 1 && word.endsWith("의")
                && !"주의".equals(word) && !"의미".equals(word) && !"회의".equals(word) && !"거의".equals(word)) {
            return word.substring(0, word.length() - 1) + "에";
        }
        return word.replaceAll("(?<=.)의", "이");
    }

    private static char compose(int onset, int vowel, int coda) {
        return (char) (0xAC00 + (onset * 588) + (vowel * 28) + coda);
    }

    static java.util.List<String> romanizeSyllablePieces(String text, boolean vn) {
        java.util.ArrayList<String> pieces = new java.util.ArrayList<>();
        String pronounced = pronounceHangulForDisplay(text);
        if (pronounced == null) return pieces;
        int prevCoda = 0;
        int runIndex = 0;
        for (int i = 0; i < pronounced.length(); ) {
            int cp = pronounced.codePointAt(i);
            int[] syl = SpicyRomanizer.decompose(cp);
            if (syl == null) {
                pieces.add(new String(Character.toChars(cp)));
                if (Character.isWhitespace(cp)) {
                    prevCoda = 0;
                    runIndex = 0;
                }
            } else {
                applyUiPronunciation(syl, runIndex);
                String onset = syl[0] == ON_R && prevCoda == CODA_L ? "l" : ONSET[syl[0]];
                String vowel = SpicyRomanizer.koreanDisplayVowel(syl[1], vn);
                pieces.add(onset + vowel + codaRoman(syl[2]));
                prevCoda = syl[2];
                runIndex++;
            }
            i += Character.charCount(cp);
        }
        return pieces;
    }

    private static void applyUiPronunciation(int[] syl, int runIndex) {
        if (syl == null || syl[1] != NUC_UI) return;
        if (syl[0] != ON_NULL || runIndex > 0) syl[1] = NUC_I;
    }

    static java.util.List<String> romanizeReadablePieces(String text) {
        java.util.ArrayList<String> pieces = new java.util.ArrayList<>();
        if (text == null) return pieces;
        int n = text.length();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < n; ) {
            int cp = text.codePointAt(i);
            if (cp >= 0xAC00 && cp <= 0xD7A3) {
                run.appendCodePoint(cp);
            } else {
                flushReadableRun(run, pieces);
                pieces.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        flushReadableRun(run, pieces);
        return pieces;
    }

    private static void flushReadableRun(StringBuilder run, java.util.List<String> out) {
        if (run.length() == 0) return;
        java.util.List<String> syllablePieces = romanizeSyllablePieces(run.toString());
        int pieceIndex = 0;
        java.util.List<String> chunks = SpicyKoreanSpacing.splitRun(run.toString());
        boolean first = true;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) continue;
            if (!first) out.add(" ");
            int pieceCount = chunk.codePointCount(0, chunk.length());
            for (int i = 0; i < pieceCount && pieceIndex < syllablePieces.size(); i++) {
                out.add(syllablePieces.get(pieceIndex++));
            }
            first = false;
        }
        run.setLength(0);
    }

    static java.util.List<String> romanizeSyllablePieces(String text) {
        java.util.ArrayList<String> pieces = new java.util.ArrayList<>();
        if (text == null) return pieces;
        // Tokenize into syllables; non-Hangul chars break adjacency.
        int n = text.length();
        // Buffer of consecutive syllables we can apply boundary rules within.
        java.util.ArrayList<int[]> run = new java.util.ArrayList<>();
        for (int i = 0; i < n; ) {
            int cp = text.codePointAt(i);
            if (cp >= 0xAC00 && cp <= 0xD7A3) {
                int s = cp - 0xAC00;
                run.add(new int[]{s / 588, (s % 588) / 28, s % 28});
            } else {
                flush(run, pieces);
                pieces.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        flush(run, pieces);
        return pieces;
    }

    private static void flush(java.util.ArrayList<int[]> run, java.util.List<String> out) {
        if (run.isEmpty()) return;
        applyRules(run);
        int prevCoda = CODA_NONE;
        for (int[] syl : run) {
            // A ㄹ onset right after a ㄹ coda is the lateralized ㄹㄹ → write "ll", not "rl".
            String onset = (syl[0] == ON_R && prevCoda == CODA_L) ? "l" : ONSET[syl[0]];
            StringBuilder piece = new StringBuilder();
            piece.append(onset).append(VOWEL[syl[1]]);
            String coda = CODA_ROMAN[syl[2]];
            piece.append(coda == null ? "" : coda);
            out.add(piece.toString());
            prevCoda = syl[2];
        }
        run.clear();
    }

    private static void applyRules(java.util.ArrayList<int[]> run) {
        for (int i = 0; i + 1 < run.size(); i++) {
            int[] cur = run.get(i);
            int[] nxt = run.get(i + 1);
            int coda = cur[2];
            int onset = nxt[0];
            int nuc = nxt[1];

            if (coda == CODA_NONE) continue;

            if (onset == ON_NULL) {
                // --- before a vowel: liaison / ㅎ-elision ---
                if (coda == CODA_H) {                 // ㅎ + vowel → ㅎ drops
                    cur[2] = CODA_NONE;
                    continue;
                }
                int[] split = liaisonSplit(coda);     // {codaLeft, movedOnset}
                cur[2] = split[0];
                int moved = split[1];
                // palatalization: ㄷ/ㅌ moved before 이 → ㅈ/ㅊ
                if (nuc == NUC_I && (moved == ON_D)) moved = ON_J;
                else if (nuc == NUC_I && (moved == ON_T)) moved = ON_CH;
                // 제23항: a plain onset moved off a double coda tensifies after the
                // obstruent that stays behind (값이 → 갑씨, 없어 → 업써).
                if (isObstruentCoda(split[0])) moved = tenseOnset(moved);
                nxt[0] = moved;
                continue;
            }

            // --- before a consonant ---
            int rep = codaRepresentative(coda);

            // ㅎ aspiration, both orders
            if (coda == CODA_H) {
                if (onset == ON_G) { nxt[0] = ON_K; cur[2] = CODA_NONE; continue; }
                if (onset == ON_D) { nxt[0] = ON_T; cur[2] = CODA_NONE; continue; }
                if (onset == ON_B) { nxt[0] = ON_P; cur[2] = CODA_NONE; continue; }
                if (onset == ON_J) { nxt[0] = ON_CH; cur[2] = CODA_NONE; continue; }
                if (onset == ON_S) { nxt[0] = ON_SS; cur[2] = CODA_NONE; continue; }
                rep = CODA_D; // ㅎ otherwise neutralizes to ㄷ before consonants
            } else if (onset == ON_H) {
                if (rep == CODA_G) { nxt[0] = ON_K; cur[2] = CODA_NONE; continue; }
                if (rep == CODA_D) { nxt[0] = ON_T; cur[2] = CODA_NONE; continue; }
                if (rep == CODA_B) { nxt[0] = ON_P; cur[2] = CODA_NONE; continue; }
            }

            cur[2] = rep;

            // tensification (제23항): plain ㄱㄷㅂㅅㅈ tense after an obstruent coda
            // (없지 → 업찌, 있게 → 읻께, 됐단 → 뙈딴).
            if (isObstruentCoda(rep)) nxt[0] = tenseOnset(onset);

            // obstruent nasalization: ㄱ/ㄷ/ㅂ + ㄴ/ㅁ
            if (onset == ON_N || onset == ON_M_ONSET()) {
                cur[2] = nasalizeStop(rep);
            }

            // ㄹ interactions
            if (onset == ON_R) {
                if (cur[2] == CODA_N || cur[2] == CODA_L) {
                    cur[2] = CODA_L;                  // lateralization (ㄴ→ㄹ before ㄹ; ㄹ+ㄹ)
                } else {
                    nxt[0] = ON_N;                    // ㄹ → ㄴ after other consonants
                    cur[2] = nasalizeStop(cur[2]);    // and the preceding stop nasalizes before new ㄴ
                }
            } else if (cur[2] == CODA_L && onset == ON_N) {
                nxt[0] = ON_R;                        // ㄹ + ㄴ → ㄹ + ㄹ
            }
        }
        for (int[] syl : run) {
            if (syl[2] != CODA_NONE) syl[2] = codaRepresentative(syl[2]);
        }
    }

    private static int ON_M_ONSET() { return 6; } // ㅁ onset index

    private static boolean isObstruentCoda(int rep) {
        return rep == CODA_G || rep == CODA_D || rep == CODA_B;
    }

    /** Plain ㄱㄷㅂㅅㅈ → tense ㄲㄸㅃㅆㅉ; other onsets unchanged. */
    private static int tenseOnset(int onset) {
        if (onset == ON_G) return 1;
        if (onset == ON_D) return 4;
        if (onset == ON_B) return 8;
        if (onset == ON_S) return ON_SS;
        if (onset == ON_J) return 13;
        return onset;
    }

    /** Underlying coda → {coda remaining on this syllable, onset consonant that moves to the next}. */
    private static int[] liaisonSplit(int coda) {
        switch (coda) {
            case 1:  return new int[]{CODA_NONE, ON_G};   // ㄱ
            case 2:  return new int[]{CODA_NONE, 1};      // ㄲ → onset ㄲ
            case 3:  return new int[]{CODA_G, ON_S};      // ㄳ → ㄱ stays, ㅅ moves
            case 4:  return new int[]{CODA_NONE, ON_N};   // ㄴ
            case 5:  return new int[]{CODA_N, ON_J};      // ㄵ → ㄴ stays, ㅈ moves
            case 6:  return new int[]{CODA_NONE, ON_N};   // ㄶ → ㅎ drops, ㄴ moves
            case 7:  return new int[]{CODA_NONE, ON_D};   // ㄷ
            case 8:  return new int[]{CODA_NONE, ON_R};   // ㄹ
            case 9:  return new int[]{CODA_L, ON_G};      // ㄺ → ㄹ stays, ㄱ moves
            case 10: return new int[]{CODA_L, 6};         // ㄻ → ㄹ stays, ㅁ moves
            case 11: return new int[]{CODA_L, ON_B};      // ㄼ → ㄹ stays, ㅂ moves
            case 12: return new int[]{CODA_L, ON_S};      // ㄽ → ㄹ stays, ㅅ moves
            case 13: return new int[]{CODA_L, ON_T};      // ㄾ → ㄹ stays, ㅌ moves
            case 14: return new int[]{CODA_L, ON_P};      // ㄿ → ㄹ stays, ㅍ moves
            case 15: return new int[]{CODA_NONE, ON_R};   // ㅀ → ㅎ drops, ㄹ moves (싫어 → 시러)
            case 16: return new int[]{CODA_NONE, ON_M_ONSET()}; // ㅁ
            case 17: return new int[]{CODA_NONE, ON_B};   // ㅂ
            case 18: return new int[]{CODA_B, ON_S};      // ㅄ → ㅂ stays, ㅅ moves
            case 19: return new int[]{CODA_NONE, ON_S};   // ㅅ
            case 20: return new int[]{CODA_NONE, ON_SS};  // ㅆ
            case 21: return new int[]{CODA_NG, ON_NULL};  // ㅇ (ng) — stays, nothing moves
            case 22: return new int[]{CODA_NONE, ON_J};   // ㅈ
            case 23: return new int[]{CODA_NONE, ON_CH};  // ㅊ
            case 24: return new int[]{CODA_NONE, ON_K};   // ㅋ
            case 25: return new int[]{CODA_NONE, ON_T};   // ㅌ
            case 26: return new int[]{CODA_NONE, ON_P};   // ㅍ
            default: return new int[]{CODA_NONE, ON_NULL};
        }
    }

    /** Underlying coda → one of the 7 representative final sounds. */
    private static int codaRepresentative(int coda) {
        switch (coda) {
            case 1: case 2: case 24: case 9:  return CODA_G;   // ㄱㄲㅋㄺ
            case 4: case 5: case 6:           return CODA_N;   // ㄴㄵㄶ
            case 7: case 19: case 20: case 22: case 23: case 25: case 27: return CODA_D; // ㄷㅅㅆㅈㅊㅌㅎ
            case 8: case 11: case 12: case 13: case 15: return CODA_L;   // ㄹㄼㄽㄾㅀ
            case 16: case 10:                 return CODA_M;   // ㅁㄻ
            case 17: case 18: case 26: case 14: return CODA_B; // ㅂㅄㅍㄿ
            case 21:                          return CODA_NG;  // ㅇ
            default:                          return CODA_NONE;
        }
    }

    private static int nasalizeStop(int rep) {
        if (rep == CODA_G) return CODA_NG; // ㄱ → ㅇ
        if (rep == CODA_D) return CODA_N;  // ㄷ → ㄴ
        if (rep == CODA_B) return CODA_M;  // ㅂ → ㅁ
        return rep;
    }
}
