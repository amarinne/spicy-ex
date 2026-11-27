package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.language.mandarin.MandarinSpelling;

import java.text.Normalizer;
import java.util.Locale;

/** Structured syllable adapters for the accepted eza式 Mandarin and Cantonese spellings. */
public final class EzaChineseRomanizer {
    private EzaChineseRomanizer() {}

    /** Layer 4 of revision {@code eza-2026-11-15.3}; one shared implementation with the reader path. */
    public static String mandarin(String reading, boolean tones) {
        if (reading == null || reading.trim().isEmpty()) return reading;
        StringBuilder out = new StringBuilder();
        for (String token : reading.trim().split("\\s+")) {
            if (out.length() > 0) out.append(' ');
            out.append(MandarinSpelling.spell(token, tones));
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }

    public static String cantonese(String reading, boolean tones) {
        return convertReading(reading, tones);
    }

    private static String convertReading(String reading, boolean tones) {
        if (reading == null || reading.trim().isEmpty()) return reading;
        StringBuilder out = new StringBuilder();
        for (String token : reading.trim().split("\\s+")) {
            if (out.length() > 0) out.append(' ');
            out.append(cantoneseSyllable(token, tones));
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }

    private static String cantoneseSyllable(String raw, boolean tones) {
        String s = normalizeProvider(raw);
        int tone = trailingTone(s, 0);
        if (tone >= 0) s = s.substring(0, s.length() - 1);
        if (tone == 7) tone = 1;
        else if (tone == 8) tone = 3;
        else if (tone == 9) tone = 6;
        String onset = "";
        for (String candidate : new String[]{"gw", "kw", "ng", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "z", "c", "s", "j", "w"}) {
            if (s.startsWith(candidate) && mapCantoneseFinal(s.substring(candidate.length())) != null) {
                onset = candidate;
                break;
            }
        }
        String fin = s.substring(onset.length());
        String mappedOnset;
        switch (onset) {
            case "d": mappedOnset = "t"; break;
            case "t": mappedOnset = "th"; break;
            case "g": mappedOnset = "k"; break;
            case "k": mappedOnset = "kh"; break;
            case "c": mappedOnset = "ts"; break;
            case "j": mappedOnset = "y"; break;
            case "gw": mappedOnset = "qu"; break;
            case "kw": mappedOnset = (fin.startsWith("a") || fin.startsWith("ă")) ? "kho" : "khw"; break;
            default: mappedOnset = onset;
        }
        String mappedFinal = mapCantoneseFinal(fin);
        if (mappedFinal == null) return raw;
        int toneNucleus = cantoneseToneNucleus(fin);
        if (onset.equals("j") && (fin.equals("yu") || fin.equals("yun") || fin.equals("yut"))) {
            mappedOnset = "";
        }
        return mappedOnset + (tones ? applyTone(mappedFinal, tone, false, toneNucleus) : mappedFinal);
    }

    private static String mapCantoneseFinal(String fin) {
        String[] from = {"aa", "aai", "aau", "aam", "aan", "aang", "aap", "aat", "aak", "a", "ai", "au", "am", "an", "ang", "ap", "at", "ak", "eoi", "eon", "eot", "oe", "oeng", "oet", "oek", "e", "ei", "eu", "em", "eng", "ep", "ek", "i", "iu", "im", "in", "ing", "ip", "it", "ik", "o", "oi", "ou", "on", "ong", "ot", "ok", "u", "ui", "un", "ung", "ut", "uk", "yu", "yun", "yut"};
        String[] to = {"a", "ai", "ao", "am", "an", "ang", "ap", "at", "ac", "ă", "ay", "au", "ăm", "ăn", "ăng", "ăp", "ăt", "ăc", "ơi", "ơn", "ơt", "ơo", "ơong", "ơot", "ơoc", "e", "ei", "eu", "em", "eng", "ep", "ec", "i", "iu", "im", "in", "ing", "ip", "it", "ic", "o", "oi", "ou", "on", "ong", "ot", "oc", "u", "ui", "un", "ung", "ut", "uc", "yu", "yun", "yut"};
        for (int i = 0; i < from.length; i++) if (fin.equals(from[i])) return to[i];
        // Cantonese revision 2, R3: syllabic nasals spell ưm/ưng (hm/hng → hưm/hưng); R4 marks the ư.
        return fin.equals("m") ? "ưm" : fin.equals("ng") ? "ưng" : null;
    }

    /** Eza tone nuclei are defined by the source final, not by the last-i/u heuristic. */
    private static int cantoneseToneNucleus(String fin) {
        return (fin.equals("yu") || fin.equals("yun") || fin.equals("yut")) ? 1 : 0;
    }

    private static int trailingTone(String value, int neutral) {
        if (value == null || value.isEmpty()) return -1;
        char c = value.charAt(value.length() - 1);
        return c >= '0' && c <= '9' ? c - '0' : -1;
    }

    private static String normalizeProvider(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü');
    }

    private static String applyTone(String value, int tone, boolean mandarin, int index) {
        if (tone < 0 || (mandarin && tone == 1)) return value;
        String[] marks = mandarin ? new String[]{"\u0323", "", "\u0301", "\u0309", "\u0300"}
                : new String[]{"", "\u0303", "\u0301", "", "\u0300", "\u0309", "\u0323"};
        if (tone >= marks.length || marks[tone].isEmpty()) return value;
        return index < 0 || index >= value.length()
                ? value : value.substring(0, index + 1) + marks[tone] + value.substring(index + 1);
    }

    private static int toneIndex(String value, boolean mandarin) {
        if (!mandarin && (value.startsWith("m") || value.startsWith("hm"))) return value.indexOf('m');
        if (!mandarin && value.startsWith("ng")) return 0;
        if (!mandarin && value.startsWith("yu")) return 1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("ăâêôơư".indexOf(c) >= 0) return i;
        }
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == 'a') return i;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == 'o') return i;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == 'e') return i;
        for (int i = value.length() - 1; i >= 0; i--) if ("iu".indexOf(value.charAt(i)) >= 0) return i;
        return -1;
    }
}
