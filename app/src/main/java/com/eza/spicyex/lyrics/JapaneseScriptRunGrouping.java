package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Heuristic Japanese word grouping for line wrapping, used when no morphological analysis is
 * available for the line.
 *
 * <p>Japanese lyric text has no spaces, so without grouping information the wrapper is free to
 * break between any two characters and routinely splits a word down the middle. The previous
 * fallback was whitespace tokenisation, which on a space-less line produced a single
 * keep-together group spanning the whole line; the break planner then found its constraints
 * unsatisfiable and dropped to plain greedy wrapping - i.e. breaking anywhere at all. This gives
 * it real, if approximate, word boundaries instead.
 *
 * <p>The heuristics, in the order they matter:
 * <ul>
 *   <li><b>A katakana run is one word.</b> Katakana marks loanwords and names, which are written
 *       as an unbroken run, so adjacent katakana almost always belong together.
 *   <li><b>A kanji run is one word.</b> Adjacent kanji form compounds (熱情, 約束, 幸福感).
 *   <li><b>Okurigana stays with its kanji.</b> A short hiragana tail directly after a kanji run is
 *       inflection (歌う, 新しい), not a separate word. Only a short tail: past that it is
 *       grammatical particles, which are exactly where a break belongs.
 *   <li><b>Long runs stay breakable.</b> The caveat to the first two rules. Long katakana runs are
 *       usually either reduplicated onomatopoeia (ドキドキ, キラキラ) or a long loanword that
 *       simply has to break somewhere to fit. A reduplicated run is split at its repeat seam,
 *       which is a real morpheme boundary; anything else long enough to threaten the layout is
 *       left breakable rather than forcing the planner to give up on the whole line.
 *   <li><b>Kinsoku.</b> Small kana, the prolonged sound mark and closing punctuation can never
 *       start a line, and opening punctuation can never end one, so those joins are never break
 *       candidates regardless of script.
 * </ul>
 *
 * <p>Output is ordinary {@link DisplayLayoutGroup} keep-together ranges, so the existing planner
 * path (forbidden breaks + keep-together groups) consumes it unchanged.
 */
final class JapaneseScriptRunGrouping {

    /**
     * A katakana run at least this long is allowed to break inside. Four covers the common
     * reduplicated onomatopoeia (ドキドキ, ワクワク, キラキラ) and typical loanwords (ギター,
     * コーヒー) without making them breakable; from five up a run is long enough that refusing to
     * break it can push the whole line into greedy wrapping, which breaks it worse.
     */
    static final int KATAKANA_BREAKABLE_LENGTH = 5;

    /**
     * A kanji run at least this long is allowed to break inside. Two- and three-kanji compounds
     * are single words; four or more is usually two compounds sitting next to each other.
     */
    static final int KANJI_BREAKABLE_LENGTH = 4;

    /** Hiragana directly after a kanji run is treated as okurigana up to this many characters. */
    static final int MAX_OKURIGANA = 2;

    private JapaneseScriptRunGrouping() {
    }

    /** Keep-together ranges over {@code text}; empty when there is nothing to group. */
    static List<DisplayLayoutGroup> forText(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        int index = 0;
        int length = text.length();
        while (index < length) {
            int cp = text.codePointAt(index);
            if (Character.isWhitespace(cp)) {
                index += Character.charCount(cp);
                continue;
            }
            int runEnd = readRun(text, index);
            if (runEnd <= index) {
                index += Character.charCount(cp);
                continue;
            }
            addRun(groups, text, index, runEnd);
            index = runEnd;
        }
        return groups;
    }

    /**
     * Extends a run from {@code start} to the end of the word it most likely belongs to.
     *
     * <p>Leading punctuation/symbols are swallowed into the run that follows them, and trailing
     * closing punctuation, small kana and prolonged sound marks into the run before, so a break is
     * never planned at a position Japanese typography forbids.
     */
    private static int readRun(String text, int start) {
        int length = text.length();
        int index = start;
        // Opening punctuation binds forward to whatever it introduces.
        while (index < length) {
            int cp = text.codePointAt(index);
            if (!isOpeningPunctuation(cp)) break;
            index += Character.charCount(cp);
        }
        if (index >= length) return length;

        int cp = text.codePointAt(index);
        Kind kind = classify(cp);
        if (kind == Kind.OTHER) {
            // A lone symbol run: consume like characters so it does not fragment per glyph.
            while (index < length && classify(text.codePointAt(index)) == Kind.OTHER
                    && !Character.isWhitespace(text.codePointAt(index))) {
                index += Character.charCount(text.codePointAt(index));
            }
            return attachTrailing(text, index);
        }
        while (index < length) {
            int current = text.codePointAt(index);
            Kind currentKind = classify(current);
            boolean continues = currentKind == kind
                    // ー and small kana never start a word, so they always continue the run.
                    || isKanaTail(current) && (kind == Kind.KATAKANA || kind == Kind.HIRAGANA)
                    // 々 repeats the preceding kanji and belongs to it.
                    || current == 0x3005 && kind == Kind.KANJI;
            if (!continues) break;
            index += Character.charCount(current);
        }
        if (kind == Kind.KANJI) index = attachOkurigana(text, index);
        return attachTrailing(text, index);
    }

    /** Pulls a short hiragana inflection tail into the preceding kanji run. */
    private static int attachOkurigana(String text, int kanjiEnd) {
        int index = kanjiEnd;
        int taken = 0;
        while (index < text.length() && taken < MAX_OKURIGANA) {
            int cp = text.codePointAt(index);
            if (classify(cp) != Kind.HIRAGANA && !isKanaTail(cp)) break;
            index += Character.charCount(cp);
            taken++;
        }
        return index;
    }

    /** Swallows closing punctuation, which may never begin a line. */
    private static int attachTrailing(String text, int end) {
        int index = end;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            if (!isClosingPunctuation(cp) && !isKanaTail(cp)) break;
            index += Character.charCount(cp);
        }
        return index;
    }

    /**
     * Emits one run as either a single keep-together group or, when it is long enough that holding
     * it together would jeopardise the whole line, as breakable pieces.
     */
    private static void addRun(List<DisplayLayoutGroup> groups, String text, int start, int end) {
        int count = text.codePointCount(start, end);
        Kind kind = classify(text.codePointAt(start));
        int breakable = kind == Kind.KATAKANA ? KATAKANA_BREAKABLE_LENGTH
                : kind == Kind.KANJI ? KANJI_BREAKABLE_LENGTH : Integer.MAX_VALUE;
        if (count < breakable) {
            groups.add(new DisplayLayoutGroup(start, end, kindName(kind), true, 0.6));
            return;
        }
        int unit = reduplicationUnit(text, start, end);
        if (unit > 0) {
            // ドキドキ / キラキラキラ: the seams between repetitions are genuine morpheme
            // boundaries, so splitting there reads as intended rather than as a word cut in half.
            for (int i = start; i < end; i += unit) {
                groups.add(new DisplayLayoutGroup(i, Math.min(end, i + unit),
                        kindName(kind) + "-reduplicated", true, 0.55));
            }
            return;
        }
        // Too long to pin and not reduplicated: leave it breakable (one group per character) so the
        // planner still has somewhere to wrap instead of abandoning the whole line to greedy mode.
        for (int i = start; i < end; ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            // Never leave a break before a character that may not start a line.
            while (next < end && isKanaTail(text.codePointAt(next))) {
                next += Character.charCount(text.codePointAt(next));
            }
            groups.add(new DisplayLayoutGroup(i, next, kindName(kind) + "-long", true, 0.3));
            i = next;
        }
    }

    /**
     * UTF-16 length of the shortest unit the run is an exact repetition of, or -1 when it is not
     * periodic. Onomatopoeia is overwhelmingly formed by reduplication - ドキドキ (A+A) but also
     * キラキラキラ (A+A+A) - and the seams between repetitions are the only positions inside such a
     * run where a break does not look like a mistake.
     *
     * <p>The unit must be at least two code points: a single repeated character (ーー, ンンン) is
     * an elongation, not reduplication, and splitting it would leave a line starting on a
     * character that may not start one.
     */
    static int reduplicationUnit(String text, int start, int end) {
        int count = text.codePointCount(start, end);
        if (count < 4) return -1;
        if (isSingleRepeatedCodePoint(text, start, end)) return -1;
        for (int unitCount = 2; unitCount <= count / 2; unitCount++) {
            if (count % unitCount != 0) continue;
            int unitEnd = text.offsetByCodePoints(start, unitCount);
            int unitLength = unitEnd - start;
            boolean periodic = true;
            for (int at = unitEnd; at < end && periodic; at += unitLength) {
                if (at + unitLength > end || !text.regionMatches(start, text, at, unitLength)) {
                    periodic = false;
                }
            }
            if (periodic) return unitLength;
        }
        return -1;
    }

    /** One character held for emphasis (ンンンン, ラララララ) rather than a reduplicated morpheme. */
    private static boolean isSingleRepeatedCodePoint(String text, int start, int end) {
        int first = text.codePointAt(start);
        for (int i = start; i < end; ) {
            int cp = text.codePointAt(i);
            if (cp != first) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private enum Kind { KANJI, KATAKANA, HIRAGANA, LATIN, OTHER }

    private static String kindName(Kind kind) {
        switch (kind) {
            case KANJI: return "ja-kanji-run";
            case KATAKANA: return "ja-katakana-run";
            case HIRAGANA: return "ja-hiragana-run";
            case LATIN: return "ja-latin-run";
            default: return "ja-symbol-run";
        }
    }

    private static Kind classify(int cp) {
        if (isKanji(cp)) return Kind.KANJI;
        if (isKatakana(cp)) return Kind.KATAKANA;
        if (isHiragana(cp)) return Kind.HIRAGANA;
        if (cp < 0x80 && Character.isLetterOrDigit(cp)) return Kind.LATIN;
        return Kind.OTHER;
    }

    static boolean isKanji(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF
                || cp >= 0x3400 && cp <= 0x4DBF
                || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0x20000 && cp <= 0x2FA1F;
    }

    static boolean isKatakana(int cp) {
        // 0x30FB (・) and 0x30FC (ー) are excluded here and handled as joiners instead.
        return cp >= 0x30A1 && cp <= 0x30FA
                || cp >= 0x31F0 && cp <= 0x31FF
                || cp >= 0xFF66 && cp <= 0xFF9D;
    }

    static boolean isHiragana(int cp) {
        return cp >= 0x3041 && cp <= 0x309F && cp != 0x3099 && cp != 0x309A;
    }

    /** Characters that may never begin a line: small kana, the prolonged sound mark, iteration
     *  marks and the combining voiced-sound marks. */
    static boolean isKanaTail(int cp) {
        switch (cp) {
            case 0x30FC: // ー
            case 0xFF70: // halfwidth ー
            case 0x30FB: // ・
            case 0x309D: case 0x309E: // ゝゞ
            case 0x30FD: case 0x30FE: // ヽヾ
            case 0x3099: case 0x309A: case 0x309B: case 0x309C:
                return true;
            default:
                break;
        }
        return isSmallKana(cp);
    }

    static boolean isSmallKana(int cp) {
        return "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ".indexOf(cp) >= 0
                || cp >= 0xFF67 && cp <= 0xFF6F;
    }

    static boolean isClosingPunctuation(int cp) {
        return "、。，．！？!?」』）】〉》〕］)…‥〜ー".indexOf(cp) >= 0;
    }

    static boolean isOpeningPunctuation(int cp) {
        return "「『（【〈《〔［(".indexOf(cp) >= 0;
    }
}
