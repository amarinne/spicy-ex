package com.eza.spicyex.lyrics.language.mandarin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layers 2 and 3 of Mandarin eza式 revision 3: tone sandhi and the written tone of tone 4.
 *
 * <p>Policy {@code eza-2026-11-15.3}. Layer 2 reads only the layer-1 contract and emits digits
 * 1-4 plus 0 (neutral, no mark), 6 (neutral grave) and 7 (neutral hook). Layer 3 needs the whole
 * line's adjacency, so it cannot run inside a token renderer. Neither layer changes letters,
 * chooses readings, or writes marks.
 *
 * <p>Input contract: 一 is {@code yi1}, 不 is {@code bu4}, and a neutral syllable carries its real
 * tone when the data has one with the same letters.
 */
public final class MandarinSandhi {
    public static final String REVISION = "eza-2026-11-15.3";

    /** 1-4 are surface tones; 0 is a neutral with no mark, 6 a neutral grave, 7 a neutral hook. */
    public static final int NEUTRAL = 0;
    public static final int NEUTRAL_GRAVE = 6;
    public static final int NEUTRAL_HOOK = 7;

    private static final String HAN_NUMERALS = "〇零一二三四五六七八九十百千万萬亿億两兩";
    /** Structural particles keep an identical-character 不 out of the reduplicated-verb pattern (得不得了). */
    private static final String PARTICLE_CHARACTERS = "得的地了着著过過吗嗎吧呢啦哇呀嘛啊";
    private static final String PRONOUNS = "我你";

    private MandarinSandhi() {
    }

    /**
     * Layer 2 over one line. The result stays aligned to the line's tokens; {@code null} marks a
     * non-Han or unavailable token.
     */
    public static List<List<String>> surface(MandarinReadingLine line) {
        assertInput(line);
        int count = line.tokens().size();
        List<List<String>> out = new ArrayList<>(Collections.nCopies(count, (List<String>) null));
        List<MandarinToken> run = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            MandarinToken token = line.tokens().get(index);
            if (!"han".equals(token.kind()) || unavailable(token)) {
                flush(run, indices, out);
                continue;
            }
            run.add(token);
            indices.add(index);
        }
        flush(run, indices, out);
        return out;
    }

    /** Layer 3 over the whole line: tone 4 before tone 4 or a neutral is written flat. */
    public static List<List<String>> realize(List<List<String>> surface) {
        if (surface == null) return null;
        List<List<String>> out = new ArrayList<>(surface.size());
        for (List<String> own : surface) out.add(own == null ? null : new ArrayList<>(own));
        List<Slot> run = new ArrayList<>();
        for (int token = 0; token < out.size(); token++) {
            List<String> own = out.get(token);
            if (own == null) {
                flushRealize(run, out);
                continue;
            }
            for (int at = 0; at < own.size(); at++) {
                String syllable = own.get(at);
                int digit = syllable == null ? -1 : digit(syllable);
                if (digit < 0 || digit > 7) {
                    flushRealize(run, out);
                    continue;
                }
                run.add(new Slot(token, at, letters(syllable), digit));
            }
        }
        flushRealize(run, out);
        return out;
    }

    /** Layer 2 and layer 3 together, the input layer 4 spells. */
    public static List<List<String>> surfaceAndRealize(MandarinReadingLine line) {
        return realize(surface(line));
    }

    /** Layer 2 rejects a line that did not come out of layer 1 in revision-3 form. */
    public static void assertInput(MandarinReadingLine line) {
        for (MandarinToken token : line.tokens()) {
            if (!"han".equals(token.kind())) continue;
            String[] chars = MandarinG2pM.points(token.text());
            for (int at = 0; at < token.syllables().size() && at < chars.length; at++) {
                MandarinSyllable syllable = token.syllables().get(at);
                if (syllable.isUnavailable()) continue;
                String character = chars[at];
                if ("一".equals(character)
                        && (!"yi".equals(syllable.letters()) || syllable.tone() != 1 || syllable.neutral())) {
                    throw new IllegalArgumentException("Layer 2 input: 一 must be yi1, got "
                            + syllable.numbered() + (syllable.neutral() ? " N" : "") + ".");
                }
                if ("不".equals(character)
                        && (!"bu".equals(syllable.letters()) || syllable.tone() != 4 || syllable.neutral())) {
                    throw new IllegalArgumentException("Layer 2 input: 不 must be bu4, got "
                            + syllable.numbered() + (syllable.neutral() ? " N" : "") + ".");
                }
                if (syllable.neutral() && syllable.tone() == 5 && syllable.alternatives() != null) {
                    boolean full = false;
                    for (String alternative : syllable.alternatives()) {
                        int tone = digit(alternative);
                        if (tone >= 1 && tone <= 4 && letters(alternative).equals(syllable.letters())) {
                            full = true;
                            break;
                        }
                    }
                    if (full) {
                        throw new IllegalArgumentException("Layer 2 input: neutral "
                                + syllable.letters() + " must carry its real tone.");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- layer 2 internals

    private static void flush(List<MandarinToken> run, List<Integer> indices, List<List<String>> out) {
        if (run.isEmpty()) return;
        List<String> flat = domain(run);
        int offset = 0;
        for (int index = 0; index < run.size(); index++) {
            int size = run.get(index).syllables().size();
            out.set(indices.get(index), new ArrayList<>(flat.subList(offset, offset + size)));
            offset += size;
        }
        run.clear();
        indices.clear();
    }

    private static boolean unavailable(MandarinToken token) {
        for (MandarinSyllable syllable : token.syllables()) {
            if (syllable.isUnavailable()) return true;
        }
        return false;
    }

    /** One maximal run of adjacent Han tokens with a reading for every character. */
    private static List<String> domain(List<MandarinToken> tokens) {
        int count = 0;
        for (MandarinToken token : tokens) count += token.syllables().size();
        String[] chars = new String[count];
        int[] tokenOf = new int[count];
        int[] tokenStart = new int[count];
        MandarinSyllable[] syllables = new MandarinSyllable[count];
        int at = 0;
        for (int index = 0; index < tokens.size(); index++) {
            MandarinToken token = tokens.get(index);
            String[] points = MandarinG2pM.points(token.text());
            for (int offset = 0; offset < token.syllables().size(); offset++) {
                syllables[at] = token.syllables().get(offset);
                chars[at] = offset < points.length ? points[offset] : "";
                tokenOf[at] = index;
                tokenStart[at] = token.start();
                at++;
            }
        }

        String[] letters = new String[count];
        int[] realTone = new int[count];
        boolean[] neutral = new boolean[count];
        boolean[] alwaysNeutral = new boolean[count];
        int[] surface = new int[count];
        for (int index = 0; index < count; index++) {
            MandarinSyllable syllable = syllables[index];
            letters[index] = syllable.letters();
            realTone[index] = syllable.tone();
            neutral[index] = syllable.neutral();
            alwaysNeutral[index] = syllable.alwaysNeutral();
            surface[index] = neutral[index]
                    ? NEUTRAL : digit(syllable.letters() + realTone[index]);
        }

        Domain domain = new Domain(tokens, chars, tokenOf, tokenStart, syllables,
                letters, realTone, neutral, alwaysNeutral, surface);
        applyBuYi(domain);

        // A tone 3 before a neutral whose real tone is 3 rises unless the word is always neutral.
        Set<Integer> lowered = new HashSet<>();
        for (int index = 0; index + 1 < surface.length; index++) {
            if (surface[index] != 3 || !neutral[index + 1] || realTone[index + 1] != 3
                    || alwaysNeutral[index + 1]) continue;
            surface[index] = 2;
            lowered.add(index);
        }

        // Maximal runs of third tones cross token boundaries.
        for (int start = 0; start < surface.length; ) {
            if (surface[start] != 3) {
                start++;
                continue;
            }
            int end = start;
            while (end < surface.length && surface[end] == 3) end++;
            lowerRun(domain, lowered, start, end);
            start = end;
        }

        for (int index = 0; index < surface.length; index++) {
            if (surface[index] != NEUTRAL) continue;
            surface[index] = neutralMark(domain, index, lowered);
        }

        List<String> out = new ArrayList<>(count);
        for (int index = 0; index < count; index++) out.add(letters[index] + surface[index]);
        return out;
    }

    private static void applyBuYi(Domain domain) {
        for (int index = 0; index < domain.chars.length; index++) {
            if ("一".equals(domain.chars[index])) {
                if (keepsYiTone(domain, index)) continue;
                if (isNeutralYi(domain, index) || isVerbMeasure(domain, index)) {
                    domain.surface[index] = NEUTRAL;
                    continue;
                }
                Integer next = effectiveTone(domain, index + 1);
                if (next != null && next == 4) domain.surface[index] = 2;
                else if (next != null && next >= 1 && next <= 3) domain.surface[index] = 4;
                continue;
            }
            if (!"不".equals(domain.chars[index])) continue;
            if (isNeutralBu(domain, index)) {
                domain.surface[index] = NEUTRAL;
                continue;
            }
            Integer next = effectiveTone(domain, index + 1);
            if (next != null && next == 4) domain.surface[index] = 2;
        }
    }

    /** The tone a following neutral contributes: its real tone, never its neutral surface. */
    private static Integer effectiveTone(Domain domain, int index) {
        if (index >= domain.surface.length) return null;
        return domain.neutral[index] ? domain.realTone[index] : domain.surface[index];
    }

    /** A verb token: jieba folds every verb subclass under {@code v}. */
    private static boolean isVerb(MandarinToken token) {
        return token != null && token.tag() != null && token.tag().startsWith("v");
    }

    /** 一 keeps tone 1 as an ordinal (第一), in a digit series (一九一) and after a numeral (十一). */
    private static boolean keepsYiTone(Domain domain, int index) {
        String before = index > 0 ? domain.chars[index - 1] : null;
        if ("第".equals(before)) return true;
        if (before != null && isNumeral(before)) return true;
        String[] chars = MandarinG2pM.points(domain.tokens.get(domain.tokenOf[index]).text());
        if (chars.length <= 1) return false;
        for (String character : chars) {
            if (!isNumeral(character)) return false;
        }
        return true;
    }

    /** 一 between a verb and a measure word is neutral: 走一趟, 来一趟, 买一个, 睡一觉. */
    private static boolean isVerbMeasure(Domain domain, int index) {
        if (index <= 0 || index + 1 >= domain.tokenOf.length) return false;
        int before = domain.tokenOf[index - 1];
        int after = domain.tokenOf[index + 1];
        MandarinToken current = domain.tokens.get(domain.tokenOf[index]);
        if (before == after && before == domain.tokenOf[index]) {
            if (!isVerb(current) && !"i".equals(current.tag())) return false;
            return current.subwords().contains(slice(domain.chars, index, index + 2));
        }
        if (!isVerb(domain.tokens.get(before))) return false;
        MandarinToken next = domain.tokens.get(after);
        // Separate tokens: 来 / 一趟, 买 / 一个. A two-character numeral-classifier token is a
        // measure phrase; a longer time word (一会儿) is not.
        return next.start() == index
                && MandarinG2pM.points(next.text()).length == 2
                && next.tag() != null && (next.tag().startsWith("m") || next.tag().startsWith("q"));
    }

    /** 不 in verb + 不 + complement (看不懂) or between identical characters (对不对) is neutral. */
    private static boolean isNeutralBu(Domain domain, int index) {
        MandarinToken token = domain.tokens.get(domain.tokenOf[index]);
        boolean inside = index > 0 && index + 1 < domain.tokenOf.length
                && domain.tokenOf[index] == domain.tokenOf[index - 1]
                && domain.tokenOf[index] == domain.tokenOf[index + 1];
        if (inside && MandarinG2pM.points(token.text()).length == 3 && isVerb(token)) return true;
        if (index <= 0 || index + 1 >= domain.chars.length) return false;
        String left = domain.chars[index - 1];
        String right = domain.chars[index + 1];
        String before = index >= 2 ? domain.chars[index - 2] : null;
        return left != null && left.equals(right) && !"不".equals(before)
                && !PARTICLE_CHARACTERS.contains(left);
    }

    /** 一 between identical characters is the reduplicated-verb pattern (看一看, 数一数). */
    private static boolean isNeutralYi(Domain domain, int index) {
        if (index <= 0 || index + 1 >= domain.chars.length) return false;
        String left = domain.chars[index - 1];
        return left != null && left.equals(domain.chars[index + 1]) && !isNumeral(left);
    }

    /**
     * Where a three-syllable token splits at this offset, from its search subwords:
     * 2 = word + syllable, 1 = syllable + word.
     */
    private static int subwordLead(MandarinToken token, int offset) {
        String[] chars = MandarinG2pM.points(token.text());
        boolean lead = token.subwords().contains(slice(chars, offset, offset + 2));
        boolean trail = token.subwords().contains(slice(chars, offset + 1, offset + 3));
        if (lead == trail) return 0;
        return lead ? 2 : 1;
    }

    /** A three-syllable unit: 2 2 3 for word + syllable, verb + object or a repeated character. */
    private static boolean leadingShape(Domain domain, int at) {
        String first = domain.chars[at];
        String second = domain.chars[at + 1];
        String third = domain.chars[at + 2];
        if (first.equals(second) && second.equals(third)) return true;
        if (PRONOUNS.contains(first)) return false;
        int a = domain.tokenOf[at];
        int b = domain.tokenOf[at + 1];
        int c = domain.tokenOf[at + 2];
        if (a == b && b == c) {
            MandarinToken token = domain.tokens.get(a);
            int lead = subwordLead(token, at - domain.tokenStart[at]);
            if (lead == 2) return true;
            if (lead == 1) return false;
            return isVerb(token);
        }
        if (a == b) return true;
        if (b == c) return isVerb(domain.tokens.get(a));
        return isVerb(domain.tokens.get(a));
    }

    /** Lower a maximal third-tone run: pairs from the left, an odd run ends with three syllables. */
    private static void lowerRun(Domain domain, Set<Integer> lowered, int start, int end) {
        int count = end - start;
        if (count == 1) return;
        int pairEnd = count % 2 == 1 ? end - 3 : end;
        for (int at = start; at < pairEnd; at += 2) lower(domain, lowered, at);
        if (count % 2 == 1) {
            int at = end - 3;
            if (leadingShape(domain, at)) lower(domain, lowered, at);
            lower(domain, lowered, at + 1);
        }
    }

    private static void lower(Domain domain, Set<Integer> lowered, int at) {
        if (domain.surface[at] != 3) return;
        domain.surface[at] = 2;
        lowered.add(at);
    }

    /** 6 and 7 tell layer 3 which mark to write. A neutral after a lowered third tone stays unmarked. */
    private static int neutralMark(Domain domain, int index, Set<Integer> lowered) {
        Integer before = index > 0 ? domain.surface[index - 1] : null;
        if (before != null && (before == 1 || before == 4)) return NEUTRAL_GRAVE;
        if (before == null || before != 2 || lowered.contains(index - 1)) return NEUTRAL;
        return finalClass(domain.letters[index]).equals("full") ? NEUTRAL_HOOK : NEUTRAL;
    }

    private static boolean isNumeral(String character) {
        if (character == null || character.isEmpty()) return false;
        int code = character.codePointAt(0);
        if (Character.isDigit(code) && Character.getType(code) == Character.DECIMAL_DIGIT_NUMBER) {
            return true;
        }
        return HAN_NUMERALS.contains(character);
    }

    private static String slice(String[] chars, int start, int end) {
        if (start < 0 || end > chars.length || start >= end) return "";
        StringBuilder out = new StringBuilder();
        for (int index = start; index < end; index++) out.append(chars[index]);
        return out.toString();
    }

    private static final class Domain {
        final List<MandarinToken> tokens;
        final String[] chars;
        final int[] tokenOf;
        final int[] tokenStart;
        final MandarinSyllable[] syllables;
        final String[] letters;
        final int[] realTone;
        final boolean[] neutral;
        final boolean[] alwaysNeutral;
        final int[] surface;

        Domain(List<MandarinToken> tokens, String[] chars, int[] tokenOf, int[] tokenStart,
               MandarinSyllable[] syllables, String[] letters, int[] realTone, boolean[] neutral,
               boolean[] alwaysNeutral, int[] surface) {
            this.tokens = tokens;
            this.chars = chars;
            this.tokenOf = tokenOf;
            this.tokenStart = tokenStart;
            this.syllables = syllables;
            this.letters = letters;
            this.realTone = realTone;
            this.neutral = neutral;
            this.alwaysNeutral = alwaysNeutral;
            this.surface = surface;
        }
    }

    // ---------------------------------------------------------------- layer 3 internals

    private static void flushRealize(List<Slot> run, List<List<String>> out) {
        if (run.isEmpty()) return;
        int[] tones = new int[run.size()];
        for (int index = 0; index < run.size(); index++) tones[index] = run.get(index).tone;
        int[] realized = realizeNeutralTones(tones);
        for (int index = 0; index < run.size(); index++) {
            Slot slot = run.get(index);
            out.get(slot.token).set(slot.at, slot.letters + realized[index]);
        }
        run.clear();
    }

    /**
     * A tone 4 before a tone 4 is written flat; before a neutral it is written flat and the neutral
     * takes the grave. Uses the input snapshot, so a realized digit never feeds a later decision.
     */
    public static int[] realizeNeutralTones(int[] input) {
        int[] out = new int[input.length];
        for (int index = 0; index < input.length; index++) out[index] = input[index] == 5 ? NEUTRAL : input[index];
        int[] base = out.clone();
        for (int index = 0; index + 1 < base.length; index++) {
            if (base[index] != 4) continue;
            int next = base[index + 1];
            if (next == 4) {
                out[index] = 1;
            } else if (next == NEUTRAL || next == NEUTRAL_GRAVE || next == NEUTRAL_HOOK) {
                out[index] = 1;
                out[index + 1] = NEUTRAL_GRAVE;
            }
        }
        return out;
    }

    private static final class Slot {
        final int token;
        final int at;
        final String letters;
        final int tone;

        Slot(int token, int at, String letters, int tone) {
            this.token = token;
            this.at = at;
            this.letters = letters;
            this.tone = tone;
        }
    }

    // ---------------------------------------------------------------- shared syllable parsing

    /** The final class of a reading: a reduced open e, a high vowel, or any other full vowel. */
    public static String finalClass(String letters) {
        String fin = ZERO_ONSET_FINALS.get(letters);
        if (fin == null) {
            String onset = "";
            for (String candidate : ONSETS) {
                if (letters.startsWith(candidate)) {
                    onset = candidate;
                    break;
                }
            }
            fin = letters.substring(onset.length());
        }
        if ("e".equals(fin)) return "reduced";
        if ("i".equals(fin) || "u".equals(fin) || "ü".equals(fin)) return "high";
        return "full";
    }

    static int digit(String numbered) {
        if (numbered == null || numbered.isEmpty()) return -1;
        char last = numbered.charAt(numbered.length() - 1);
        return last >= '0' && last <= '7' ? last - '0' : -1;
    }

    static String letters(String numbered) {
        if (numbered == null || numbered.isEmpty()) return "";
        char last = numbered.charAt(numbered.length() - 1);
        return last >= '0' && last <= '7' ? numbered.substring(0, numbered.length() - 1) : numbered;
    }

    private static final String[] ONSETS = {
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "z", "c", "s", "j", "q", "x", "r"
    };
    /** A zero-onset spelling and the final it stands for. */
    private static final java.util.Map<String, String> ZERO_ONSET_FINALS = zeroOnsetFinals();

    private static java.util.Map<String, String> zeroOnsetFinals() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        String[][] rows = {
                {"yi", "i"}, {"ya", "ia"}, {"ye", "ie"}, {"yao", "iao"}, {"you", "iou"},
                {"yan", "ian"}, {"yin", "in"}, {"yang", "iang"}, {"ying", "ing"},
                {"yong", "iong"}, {"yu", "ü"}, {"yue", "üe"}, {"yuan", "üan"}, {"yun", "ün"},
                {"wu", "u"}, {"wa", "ua"}, {"wo", "uo"}, {"wai", "uai"}, {"wei", "uei"},
                {"wan", "uan"}, {"wen", "uen"}, {"wang", "uang"}, {"weng", "ueng"},
        };
        for (String[] row : rows) map.put(row[0], row[1]);
        return map;
    }
}
