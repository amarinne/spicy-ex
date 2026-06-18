package com.eza.spicyex.lyrics;

/**
 * Lightweight Korean grapheme-to-phoneme pass for the "follow sound" (pronunciation)
 * romanization mode. Operates on decomposed jamo across syllable boundaries, then romanizes
 * with Revised-Romanization letter values.
 *
 * <p>Covers the common, visible lyric rules: resyllabification/liaison before ㅇ, ㅎ aspiration
 * and ㅎ-elision, palatalization (ㄷ/ㅌ + 이), obstruent nasalization (ㄱ/ㄷ/ㅂ + ㄴ/ㅁ), ㄹ→ㄴ
 * nasalization, and ㄴ/ㄹ lateralization. It is NOT a full G2P: tensification (된소리) is
 * morphology-sensitive and intentionally omitted, and some double-coda edges are simplified.
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

    private static final int NUC_I = 20;       // ㅣ
    private static final int CODA_NONE = 0, CODA_G = 1, CODA_N = 4, CODA_D = 7, CODA_L = 8, CODA_M = 16, CODA_B = 17, CODA_NG = 21, CODA_H = 27;
    private static final int ON_G = 0, ON_N = 2, ON_D = 3, ON_R = 5, ON_B = 7, ON_J = 12, ON_CH = 14, ON_K = 15, ON_T = 16, ON_P = 17, ON_H = 18, ON_NULL = 11, ON_S = 9, ON_SS = 10;

    static String romanize(String text) {
        if (text == null) return null;
        // Tokenize into syllables; non-Hangul chars break adjacency.
        int n = text.length();
        StringBuilder out = new StringBuilder();
        // Buffer of consecutive syllables we can apply boundary rules within.
        java.util.ArrayList<int[]> run = new java.util.ArrayList<>();
        for (int i = 0; i < n; ) {
            int cp = text.codePointAt(i);
            if (cp >= 0xAC00 && cp <= 0xD7A3) {
                int s = cp - 0xAC00;
                run.add(new int[]{s / 588, (s % 588) / 28, s % 28});
            } else {
                flush(run, out);
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        flush(run, out);
        return out.toString();
    }

    private static void flush(java.util.ArrayList<int[]> run, StringBuilder out) {
        if (run.isEmpty()) return;
        applyRules(run);
        int prevCoda = CODA_NONE;
        for (int[] syl : run) {
            // A ㄹ onset right after a ㄹ coda is the lateralized ㄹㄹ → write "ll", not "rl".
            String onset = (syl[0] == ON_R && prevCoda == CODA_L) ? "l" : ONSET[syl[0]];
            out.append(onset).append(VOWEL[syl[1]]);
            String coda = CODA_ROMAN[syl[2]];
            out.append(coda == null ? "" : coda);
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
    }

    private static int ON_M_ONSET() { return 6; } // ㅁ onset index

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
