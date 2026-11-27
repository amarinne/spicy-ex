package com.eza.spicyex.lyrics.language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Text guess of whether a Chinese song is sung in Cantonese or Mandarin: a port of the desktop
 * {@code ChineseRhymeGuess} revision {@code tone-rhyme-v4}. The decision combines rhyme evidence
 * under each reading, the tone-melody parallelism of the Jyutping reading, and Cantonese-only
 * function characters through one fitted logistic.
 *
 * <p>Readings come from this app's own engines: Standard Pinyin (pinyin4j) for Mandarin finals
 * and the Jyutping lexicon, read through the revision 2 lookup form without the ng insertion.
 */
public final class ChineseSongGuess {
    public static final String REVISION = "tone-rhyme-v4";
    public static final String YUE = "yue";
    public static final String CMN = "cmn";

    /**
     * Cantonese-only function characters. Two different ones decide a song as Cantonese. Characters
     * that also occur in Mandarin (晒/曬, 郁, 咩, 畀/俾) are excluded.
     */
    private static final String CANTONESE_MARKERS = "嘅咗啲冇唔佢喺嘢嚟睇乜嗰哋搵啱攞諗瞓噉咁啩喎囉嗮冚揸嘥攰";
    private static final int CANTONESE_MARKER_THRESHOLD = 2;
    /** Chance that two unrelated line endings share a rhyme key under each reading. */
    static final double MANDARIN_CHANCE_COLLISION = 0.105;
    static final double JYUTPING_CHANCE_COLLISION = 0.0508;
    static final double WEIGHT_RHYME_Z = 0.613;
    static final double WEIGHT_TONE_EXCESS = 29.35;
    static final double WEIGHT_MARKERS = 3.03;
    static final double WEIGHT_BIAS = -5.87;
    static final double DECISION_HIGH = 0.9;
    static final double DECISION_LOW = 0.1;
    /** Below this many usable lines the text evidence is unreliable. */
    static final int MIN_DECISION_LINES = 12;
    private static final int TONE_MIN_LINE_LENGTH = 5;
    private static final Map<String, String> MANDARIN_RHYMES = new HashMap<>();
    private static final String[] PINYIN_INITIALS = {
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "r", "z", "c", "s"};
    private static final String[] JYUTPING_INITIALS = {
            "gw", "kw", "ng", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "z", "c", "s", "j", "w"};

    static {
        String[][] rows = {{"ie", "e"}, {"üe", "e"}, {"o", "e"}, {"uo", "o"}, {"ong", "eng"},
                {"iong", "eng"}, {"ing", "eng"}, {"in", "en"}, {"un", "en"}, {"ün", "en"},
                {"ui", "ei"}, {"iu", "ou"}, {"ü", "i"}};
        for (String[] row : rows) MANDARIN_RHYMES.put(row[0], row[1]);
    }

    private ChineseSongGuess() {}

    /** One document's evidence and decision. {@link #language} is null when undecided. */
    public static final class Result {
        public final String language;
        public final int lineCount;
        public final int cantoneseOnlyPairs;
        public final int mandarinOnlyPairs;
        public final int cantoneseMarkers;
        public final double jyutpingZ;
        public final double mandarinZ;
        public final double toneExcess;
        public final double probability;

        Result(String language, int lineCount, int cantoneseOnlyPairs, int mandarinOnlyPairs,
               int cantoneseMarkers, double jyutpingZ, double mandarinZ, double toneExcess,
               double probability) {
            this.language = language;
            this.lineCount = lineCount;
            this.cantoneseOnlyPairs = cantoneseOnlyPairs;
            this.mandarinOnlyPairs = mandarinOnlyPairs;
            this.cantoneseMarkers = cantoneseMarkers;
            this.jyutpingZ = jyutpingZ;
            this.mandarinZ = mandarinZ;
            this.toneExcess = toneExcess;
            this.probability = probability;
        }
    }

    /** Rhyme keys of one line ending; either is null when the reading is unknown. */
    static final class RhymeKey {
        final String pinyin;
        final String jyutping;

        RhymeKey(String pinyin, String jyutping) {
            this.pinyin = pinyin;
            this.jyutping = jyutping;
        }
    }

    public static Result guess(List<String> lines) {
        List<String> accepted = cleanLines(lines);
        Set<Character> markers = new LinkedHashSet<>();
        List<RhymeKey> keys = new ArrayList<>(accepted.size());
        List<String> hans = new ArrayList<>(accepted.size());
        for (String line : accepted) {
            for (int i = 0; i < line.length(); i++) {
                if (CANTONESE_MARKERS.indexOf(line.charAt(i)) >= 0) markers.add(line.charAt(i));
            }
            String han = hanOf(line);
            hans.add(han);
            keys.add(rhymeKey(han));
        }
        int cantoneseOnly = 0, mandarinOnly = 0, rhymePairs = 0, sharedJyutping = 0, sharedMandarin = 0;
        for (int distance = 1; distance <= 2; distance++) {
            for (int i = 0; i + distance < keys.size(); i++) {
                RhymeKey a = keys.get(i), b = keys.get(i + distance);
                if (a.pinyin == null || b.pinyin == null || a.jyutping == null || b.jyutping == null) continue;
                rhymePairs++;
                boolean canto = a.jyutping.equals(b.jyutping);
                boolean mando = a.pinyin.equals(b.pinyin);
                if (canto) sharedJyutping++;
                if (mando) sharedMandarin++;
                if (canto && !mando) cantoneseOnly++;
                if (mando && !canto) mandarinOnly++;
            }
        }
        double jyutpingZ = zScore(sharedJyutping, rhymePairs, JYUTPING_CHANCE_COLLISION);
        double mandarinZ = zScore(sharedMandarin, rhymePairs, MANDARIN_CHANCE_COLLISION);
        int markerCount = markers.size();
        double toneExcess = toneExcess(hans);
        double logit = WEIGHT_RHYME_Z * (jyutpingZ - mandarinZ)
                + WEIGHT_TONE_EXCESS * toneExcess
                + WEIGHT_MARKERS * Math.min(markerCount, 3)
                + WEIGHT_BIAS;
        double probability = 1.0 / (1.0 + Math.exp(-logit));
        String language = markerCount >= CANTONESE_MARKER_THRESHOLD ? YUE
                : accepted.size() < MIN_DECISION_LINES ? null
                : probability > DECISION_HIGH ? YUE
                : probability < DECISION_LOW ? CMN : null;
        return new Result(language, accepted.size(), cantoneseOnly, mandarinOnly, markerCount,
                jyutpingZ, mandarinZ, toneExcess, probability);
    }

    private static double zScore(int observed, int pairs, double chance) {
        double expected = pairs * chance;
        return expected > 0 ? (observed - expected) / Math.sqrt(expected * (1 - chance)) : 0;
    }

    /** Timestamps, credit lines, repeated choruses and one-character lines carry no evidence. */
    static List<String> cleanLines(List<String> lines) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (lines == null) return new ArrayList<>();
        for (String raw : lines) {
            if (raw == null) continue;
            String line = jsTrim(raw.replaceAll("\\[[^\\]]*\\]", ""));
            if (line.isEmpty() || line.indexOf(':') >= 0 || line.indexOf('：') >= 0
                    || hanOf(line).length() < 2) continue;
            unique.add(line);
        }
        return new ArrayList<>(unique);
    }

    static boolean isHan(char c) {
        return (c >= '㐀' && c <= '鿿') || (c >= '豈' && c <= '﫿');
    }

    private static String hanOf(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) if (isHan(line.charAt(i))) out.append(line.charAt(i));
        return out.toString();
    }

    private static String jsTrim(String value) {
        int start = 0, end = value.length();
        while (start < end && jsSpace(value.charAt(start))) start++;
        while (end > start && jsSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean jsSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    /** Keys of the line-final Han character of {@code han}, a line's Han characters. */
    static RhymeKey rhymeKey(String han) {
        char ending = han.charAt(han.length() - 1);
        String lookup = ChineseCharacterConverter.cantoneseLookupForm(han);
        char lookupEnding = lookup.length() == han.length() ? lookup.charAt(lookup.length() - 1) : ending;
        return new RhymeKey(pinyinKey(endingPinyin(han)),
                jyutpingKey(JyutpingRomanizer.characterReading(lookupEnding)));
    }

    /**
     * The toneless Standard Pinyin of the line's last character, read in the context of the line
     * so phrase readings apply (什麼 ends in me, not the single-character ma). The phrase table is
     * simplified, so the line is read through its simplified form when that keeps every character.
     * Falls back to the character alone when the reading does not align one syllable per character.
     */
    private static String endingPinyin(String han) {
        String simplified = ChineseCharacterConverter.convert(han, ChineseCharacterForm.SIMPLIFIED);
        if (simplified.length() == han.length()) han = simplified;
        String line = SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(han, false);
        if (line != null) {
            String[] syllables = line.trim().split("\\s+");
            if (syllables.length == han.length()) {
                String last = normalizePinyin(syllables[syllables.length - 1]);
                if (last != null) return last;
            }
        }
        return pinyinSyllable(han.charAt(han.length() - 1));
    }

    private static String normalizePinyin(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü');
        return s.matches("[a-zü]+") ? s : null;
    }

    /** The toneless Standard Pinyin syllable of one character, or null. */
    private static String pinyinSyllable(char c) {
        String value = SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(String.valueOf(c), false);
        if (value == null) return null;
        value = value.trim().toLowerCase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü');
        int space = value.indexOf(' ');
        if (space > 0) value = value.substring(0, space);
        return value.matches("[a-zü]+") ? value : null;
    }

    /**
     * Rhyme key of a toneless pinyin syllable. The final follows pinyin-pro: y/w are dropped,
     * the initial is stripped, and u after j/q/x is ü. The medial before a/e/o/ê is then
     * stripped and the finals merge into Mandarin rhyme groups.
     */
    static String pinyinKey(String syllable) {
        if (syllable == null || syllable.isEmpty()) return null;
        String s = syllable.replace('v', 'ü');
        String fin;
        if (s.startsWith("y") || s.startsWith("w")) {
            fin = s.substring(1);
        } else {
            String initial = "";
            for (String candidate : PINYIN_INITIALS) {
                if (s.startsWith(candidate)) {
                    initial = candidate;
                    break;
                }
            }
            fin = s.substring(initial.length());
            if (("j".equals(initial) || "q".equals(initial) || "x".equals(initial)) && fin.startsWith("u")) {
                fin = "ü" + fin.substring(1);
            }
        }
        if (fin.length() > 1 && "iuü".indexOf(fin.charAt(0)) >= 0 && "aeoê".indexOf(fin.charAt(1)) >= 0) {
            fin = fin.substring(1);
        }
        if (fin.isEmpty()) return null;
        String merged = MANDARIN_RHYMES.get(fin);
        return merged == null ? fin : merged;
    }

    /**
     * Rhyme key of a Jyutping reading: the first syllable without tone and initial, with the
     * v4 groups. -m/-n codas merge, -p/-t stops merge, and yun/yut join in/it; -ng and -k stay.
     */
    static String jyutpingKey(String reading) {
        if (reading == null) return null;
        String syllable = reading.trim().split("\\s+")[0].replaceAll("\\d$", "");
        if (syllable.isEmpty()) return null;
        String fin = syllable;
        for (String candidate : JYUTPING_INITIALS) {
            if (syllable.startsWith(candidate)) {
                fin = syllable.substring(candidate.length());
                break;
            }
        }
        if (fin.isEmpty()) fin = syllable;
        fin = fin.replaceAll("[mn]$", "N").replaceAll("[pt]$", "T").replaceAll("^yu(?=[NT])", "i");
        return fin;
    }

    /** 0243-style pitch classes: tone 4 lowest, 6 low-mid, 3/5 mid, 1/2 high; -1 when unknown. */
    static int toneClass(String syllable) {
        if (syllable == null || syllable.isEmpty()) return -1;
        switch (syllable.charAt(syllable.length() - 1)) {
            case '4': return 0;
            case '6': return 1;
            case '3': case '5': return 2;
            case '1': case '2': return 3;
            default: return -1;
        }
    }

    /** In-context Jyutping tone classes of a line's Han characters. */
    static int[] toneClasses(String han) {
        int[] tones = new int[han.length()];
        java.util.Arrays.fill(tones, -1);
        int index = 0;
        for (JyutpingRomanizer.Segment segment : JyutpingRomanizer.walk(han, false).segments) {
            String[] syllables = segment.reading == null ? new String[0] : segment.reading.trim().split("\\s+");
            if (segment.length == syllables.length) {
                for (int k = 0; k < syllables.length && index + k < tones.length; k++) {
                    tones[index + k] = toneClass(syllables[k]);
                }
            }
            index += segment.length;
        }
        return tones;
    }

    private static final class ToneLine {
        final String han;
        final int[] tones;

        ToneLine(String han) {
            this.han = han;
            this.tones = toneClasses(han);
        }
    }

    /**
     * Mean best-partner tone agreement among same-length lines, minus the agreement with the
     * same partner read backwards.
     */
    static double toneExcess(List<String> hans) {
        Map<Integer, List<ToneLine>> groups = new LinkedHashMap<>();
        for (String han : hans) {
            if (han.length() < TONE_MIN_LINE_LENGTH) continue;
            groups.computeIfAbsent(han.length(), length -> new ArrayList<>()).add(new ToneLine(han));
        }
        double sum = 0, baseline = 0;
        int counted = 0;
        for (List<ToneLine> group : groups.values()) {
            if (group.size() < 2) continue;
            for (ToneLine line : group) {
                double best = 0, reversed = 0;
                for (ToneLine partner : group) {
                    if (partner == line || sameCharacterRatio(line.han, partner.han) > 0.6) continue;
                    best = Math.max(best, toneAgreement(line.tones, partner.tones, line.han, partner.han));
                    reversed = Math.max(reversed, toneAgreement(line.tones, reverse(partner.tones), line.han,
                            new StringBuilder(partner.han).reverse().toString()));
                }
                sum += best;
                baseline += reversed;
                counted++;
            }
        }
        return counted > 0 ? (sum - baseline) / counted : 0;
    }

    private static int[] reverse(int[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[values.length - 1 - i];
        return out;
    }

    private static double toneAgreement(int[] tones, int[] partner, String han, String partnerHan) {
        int matches = 0, compared = 0;
        for (int i = 0; i < tones.length; i++) {
            if (han.charAt(i) == partnerHan.charAt(i) || tones[i] < 0 || partner[i] < 0) continue;
            compared++;
            if (tones[i] == partner[i]) matches++;
        }
        return compared >= 3 ? (double) matches / compared : 0;
    }

    private static double sameCharacterRatio(String han, String partnerHan) {
        if (han.isEmpty()) return 1;
        int same = 0;
        for (int i = 0; i < han.length(); i++) if (han.charAt(i) == partnerHan.charAt(i)) same++;
        return (double) same / han.length();
    }
}
