package com.eza.spicyex.lyrics.language.mandarin;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Layer 4 of Mandarin eza式 revision 3: spelling and tone marks, policy {@code eza-2026-11-15.3}.
 *
 * <p>It owns no tone changes. It renders layer-2 digits: 1-4 are full tones, 0 is a neutral with
 * no mark, 6 a neutral grave and 7 a neutral hook. The open {@code e} is {@code ưa} in a full tone
 * and {@code ơ} in the neutral, except after {@code g}, {@code k} or {@code h}.
 */
public final class MandarinSpelling {
    private static final String[] ONSETS = {
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "z", "c", "s", "j", "q", "x", "r"
    };
    private static final String[] MAPPED = {
            "zh", "ch", "sh", "b", "p", "m", "f", "t", "th", "n", "l", "k", "kh", "h",
            "z", "ts", "s", "z", "ch", "sh", "r"
    };
    private static final List<String> RETROFLEX = Arrays.asList("z", "c", "s", "zh", "ch", "sh", "r");
    private static final List<String> PALATAL = Arrays.asList("j", "q", "x");
    private static final List<String> LABIAL = Arrays.asList("b", "p", "m", "f");
    private static final List<String> VELAR = Arrays.asList("g", "k", "h");

    private MandarinSpelling() {
    }

    /** Spell one numbered syllable ({@code zhong1}, {@code de6}, {@code nü3}). */
    public static String spell(String numbered, boolean tones) {
        if (numbered == null || numbered.isEmpty()) return numbered;
        String normalized = numbered.toLowerCase(Locale.ROOT)
                .replace("u:", "ü").replace('v', 'ü');
        int tone = 5;
        char last = normalized.charAt(normalized.length() - 1);
        if (last >= '0' && last <= '9') {
            tone = last - '0';
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (tone == 5) tone = 0;
        boolean neutral = tone == 0 || tone == 6 || tone == 7;
        if (normalized.isEmpty()) return numbered;

        String onset = "";
        for (String candidate : ONSETS) {
            if (normalized.startsWith(candidate)) {
                onset = candidate;
                break;
            }
        }
        String mappedOnset = onset.isEmpty() ? "" : MAPPED[indexOf(ONSETS, onset)];
        String fin = normalized.substring(onset.length());

        if (RETROFLEX.contains(onset) && fin.equals("i")) {
            return mark(mappedOnset + "ư", tone, tones, mappedOnset.length());
        }
        if (PALATAL.contains(onset)) {
            fin = fin.replaceFirst("^(?:ü|u)an", "yuen")
                    .replaceFirst("^(?:ü|u)e", "yue")
                    .replaceFirst("^(?:ü|u)n", "yun")
                    .replaceFirst("^(?:ü|u)", "yu");
        } else if (fin.startsWith("ü")) {
            fin = "yu" + fin.substring(1);
        }
        if (onset.isEmpty() && fin.equals("yuan")) fin = "yuen";
        if (fin.equals("iu")) fin = "iou";
        else if (fin.equals("ui")) fin = "uei";
        else if (fin.equals("un")) fin = "uen";
        if (fin.equals("wei")) fin = "wêi";
        else if (fin.equals("wen")) fin = "wuân";
        else if (fin.equals("weng")) fin = "wuâng";
        if (LABIAL.contains(onset) && fin.equals("o")) fin = "uo";
        if (fin.equals("e")) {
            fin = neutral && !VELAR.contains(onset) ? "ơ" : "ưa";
        } else if (fin.equals("en")) fin = "ân";
        else if (fin.equals("eng")) fin = "âng";
        else if (fin.equals("ei")) fin = "êi";
        else if (fin.equals("uei")) fin = "uêi";
        else if (fin.equals("ian")) fin = "ien";
        else if (fin.equals("yan")) fin = "yien";
        else if (fin.equals("uen")) fin = "uân";
        else if (fin.equals("ueng")) fin = "uâng";

        int anchor = -1;
        for (int i = 0; i < fin.length(); i++) {
            if ("ăâêôơư".indexOf(fin.charAt(i)) >= 0) {
                anchor = i;
                break;
            }
        }
        if (anchor < 0) anchor = fin.indexOf('a');
        if (anchor < 0) anchor = fin.indexOf('o');
        if (anchor < 0) anchor = fin.indexOf('e');
        if (anchor < 0) anchor = Math.max(fin.lastIndexOf('i'), fin.lastIndexOf('u'));
        return mark(mappedOnset + fin, tone, tones,
                anchor < 0 ? -1 : mappedOnset.length() + anchor);
    }

    private static String mark(String value, int tone, boolean tones, int at) {
        if (!tones) return value;
        String mark = tone == 2 ? "\u0301" : tone == 3 || tone == 7 ? "\u0309"
                : tone == 4 || tone == 6 ? "\u0300" : "";
        if (at < 0 || mark.isEmpty() || at >= value.length()) return value;
        return Normalizer.normalize(value.substring(0, at + 1) + mark + value.substring(at + 1),
                Normalizer.Form.NFC);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }
}
