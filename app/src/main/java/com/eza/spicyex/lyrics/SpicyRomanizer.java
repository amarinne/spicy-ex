package com.eza.spicyex.lyrics;


import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Android port of Spicy fork romanization behavior.
 *
 * Current exact port:
 * - Cyrillic BGN/PCGN transliteration + post-processing from Fork/Romanization.ts
 * - Script priority/detection shape from ProcessLyrics.ts
 *
 * Pending platform ports:
 * - Japanese furigana renderer wiring
 * - Chinese jyutping package behavior
 *
 * Current Android-native ports/adapters:
 * - Korean aromanize-compatible table romanizer (RR letter values, no pronunciation rules)
 * - Greek romanization data path
 *
 * Cyrillic scope: Russian-oriented BGN/PCGN simplification. Shared Cyrillic glyphs
 * that differ in Ukrainian (e.g. г→h, и→y) keep Russian values. Hard/soft signs and
 * ё are simplified for lyric readability (see docs/ROMANIZATION_AUDIT_BACKLOG.md).
 */
public final class SpicyRomanizer {
    private static final Map<Integer, String> BGN_PCGN = new HashMap<>();
    private static final Map<Integer, String> GREEK = new HashMap<>();

    private static final String[] HANGUL_INITIAL = {"g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"};
    private static final String[] HANGUL_VOWEL = {"a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"};
    private static final String[] HANGUL_FINAL = {"", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t"};

    static {
        put("а", "a");
        put("б", "b");
        put("в", "v");
        put("г", "g");
        put("д", "d");
        put("е", "e");
        put("ё", "ë");
        put("ж", "zh");
        put("з", "z");
        put("и", "i");
        put("й", "y");
        put("к", "k");
        put("л", "l");
        put("м", "m");
        put("н", "n");
        put("о", "o");
        put("п", "p");
        put("р", "r");
        put("с", "s");
        put("т", "t");
        put("у", "u");
        put("ф", "f");
        put("х", "kh");
        put("ц", "ts");
        put("ч", "ch");
        put("ш", "sh");
        put("щ", "shch");
        put("ъ", "");
        put("ы", "y");
        put("ь", "");
        put("э", "e");
        put("ю", "yu");
        put("я", "ya");

        put("є", "ye");
        put("і", "i");
        put("ї", "yi");
        put("ґ", "g");
        put("ѝ", "ì");
        put("ѓ", "ǵ");
        put("ќ", "ḱ");
        put("ѕ", "ẑ");
        put("ђ", "đ");
        put("ћ", "ć");
        put("љ", "lj");
        put("њ", "nj");
        put("џ", "dž");

        put("А", "A");
        put("Б", "B");
        put("В", "V");
        put("Г", "G");
        put("Д", "D");
        put("Е", "E");
        put("Ё", "Ë");
        put("Ж", "Zh");
        put("З", "Z");
        put("И", "I");
        put("Й", "Y");
        put("К", "K");
        put("Л", "L");
        put("М", "M");
        put("Н", "N");
        put("О", "O");
        put("П", "P");
        put("Р", "R");
        put("С", "S");
        put("Т", "T");
        put("У", "U");
        put("Ф", "F");
        put("Х", "Kh");
        put("Ц", "Ts");
        put("Ч", "Ch");
        put("Ш", "Sh");
        put("Щ", "Shch");
        put("Ъ", "");
        put("Ы", "Y");
        put("Ь", "");
        put("Э", "E");
        put("Ю", "Yu");
        put("Я", "Ya");
        put("Є", "Ye");
        put("І", "I");
        put("Ї", "Yi");
        put("Ґ", "G");
        put("Ѓ", "Ǵ");
        put("Ќ", "Ḱ");
        put("Ѕ", "Ẑ");
        put("Ђ", "Đ");
        put("Ћ", "Ć");
        put("Љ", "Lj");
        put("Њ", "Nj");
        put("Џ", "Dž");

        putGreek("Α", "A"); putGreek("α", "a");
        putGreek("Β", "V"); putGreek("β", "v");
        putGreek("Γ", "G"); putGreek("γ", "g");
        putGreek("Δ", "D"); putGreek("δ", "d");
        putGreek("Ε", "E"); putGreek("ε", "e");
        putGreek("Ζ", "Z"); putGreek("ζ", "z");
        putGreek("Η", "I"); putGreek("η", "i");
        putGreek("Θ", "Th"); putGreek("θ", "th");
        putGreek("Ι", "I"); putGreek("ι", "i");
        putGreek("Κ", "K"); putGreek("κ", "k");
        putGreek("Λ", "L"); putGreek("λ", "l");
        putGreek("Μ", "M"); putGreek("μ", "m");
        putGreek("Ν", "N"); putGreek("ν", "n");
        putGreek("Ξ", "X"); putGreek("ξ", "x");
        putGreek("Ο", "O"); putGreek("ο", "o");
        putGreek("Π", "P"); putGreek("π", "p");
        putGreek("Ρ", "R"); putGreek("ρ", "r");
        putGreek("Σ", "S"); putGreek("σ", "s"); putGreek("ς", "s");
        putGreek("Τ", "T"); putGreek("τ", "t");
        putGreek("Υ", "Y"); putGreek("υ", "y");
        putGreek("Φ", "F"); putGreek("φ", "f");
        putGreek("Χ", "Ch"); putGreek("χ", "ch");
        putGreek("Ψ", "Ps"); putGreek("ψ", "ps");
        putGreek("Ω", "O"); putGreek("ω", "o");
    }

    private SpicyRomanizer() {
    }

    public static boolean canRomanizeLocally(String text, String wholeSongText, String language) {
        return canRomanizeLocally(text, SpicyTextDetection.detectPresentScripts(wholeSongText, language, ""), language);
    }

    public static boolean canRomanizeLocally(String text, List<SpicyTextDetection.Script> scripts, String language) {
        if (text == null || text.trim().isEmpty()) return false;
        return (scripts.contains(SpicyTextDetection.Script.JAPANESE) && SpicyJapaneseChineseProcessor.canRomanizeJapanese(text))
                || (scripts.contains(SpicyTextDetection.Script.CHINESE) && SpicyTextDetection.itemChineseTest(text))
                || (scripts.contains(SpicyTextDetection.Script.CYRILLIC) && SpicyTextDetection.itemCyrillicTest(text))
                || (scripts.contains(SpicyTextDetection.Script.KOREAN) && SpicyTextDetection.itemKoreanTest(text))
                || (scripts.contains(SpicyTextDetection.Script.GREEK) && SpicyTextDetection.itemGreekTest(text));
    }

    public static String romanizeLine(String text, String wholeSongText, String language, RomanizationOptions opts) {
        return romanizeLine(text, SpicyTextDetection.detectPresentScripts(wholeSongText, language, ""), language, opts);
    }

    public static String romanizeLine(String text, List<SpicyTextDetection.Script> scripts, String language, RomanizationOptions opts) {
        if (text == null || text.trim().isEmpty()) return text;
        if (opts == null) opts = RomanizationOptions.DEFAULTS;
        String result = Normalizer.normalize(text, Normalizer.Form.NFKC);
        boolean changed = false;

        for (SpicyTextDetection.Script script : scripts) {
            if (script == SpicyTextDetection.Script.JAPANESE && SpicyJapaneseChineseProcessor.canRomanizeJapanese(result)) {
                result = SpicyJapaneseChineseProcessor.romanizeJapaneseLine(result);
                changed = true;
            } else if (script == SpicyTextDetection.Script.CHINESE && SpicyTextDetection.itemChineseTest(result)) {
                result = SpicyJapaneseChineseProcessor.romanizeChineseLine(result, opts.chineseMode, opts.chineseTones);
                changed = true;
            } else if (script == SpicyTextDetection.Script.CYRILLIC && SpicyTextDetection.itemCyrillicTest(result)) {
                result = romanizeCyrillic(result, opts.cyrillicMode, opts.cyrillicKeepSigns);
                changed = true;
            } else if (script == SpicyTextDetection.Script.KOREAN && SpicyTextDetection.itemKoreanTest(result)) {
                result = romanizeKorean(result, koreanFollowSound(opts.koreanMode));
                changed = true;
            } else if (script == SpicyTextDetection.Script.GREEK && SpicyTextDetection.itemGreekTest(result)) {
                result = romanizeGreek(result);
                changed = true;
            }
        }

        return changed ? result : null;
    }

    /**
     * Port of Fork/Romanization.ts romanizeCyrillic():
     * transliterPkg.transliter(text, "bgn-pcgn") plus ASCII cleanup.
     *
     * {@code е} uses the BGN/PCGN positional ye rule from the previous Cyrillic
     * source character. Hard/soft signs are dropped at the source so Latin
     * apostrophes in mixed-script lines are preserved.
     */
    /** Cyrillic source-language modes (selects per-language letter values + rules). */
    public static final String CYRILLIC_RUSSIAN = "Russian";
    public static final String CYRILLIC_UKRAINIAN = "Ukrainian";

    public static String romanizeCyrillic(String text) {
        return romanizeCyrillic(text, CYRILLIC_RUSSIAN, false);
    }

    public static String romanizeCyrillic(String text, String cyrillicMode, boolean keepSigns) {
        if (text == null) return null;
        boolean ukrainian = CYRILLIC_UKRAINIAN.equalsIgnoreCase(cyrillicMode);
        StringBuilder out = new StringBuilder();
        int prevCyrillicCp = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCyrillicSource(cp)) {
                String mapped = mapCyrillic(cp, prevCyrillicCp, ukrainian, keepSigns);
                if (mapped != null && !mapped.isEmpty()) out.append(mapped);
                prevCyrillicCp = cp;
            } else {
                if (Character.isWhitespace(cp)) prevCyrillicCp = -1;
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return postProcessCyrillic(out.toString());
    }

    private static String mapCyrillic(int cp, int prevCyrillicCp, boolean ukrainian, boolean keepSigns) {
        // Hard/soft signs: drop (default) or keep as BGN/PCGN prime marks.
        if (cp == 'ъ' || cp == 'Ъ') return keepSigns ? "ʺ" : "";
        if (cp == 'ь' || cp == 'Ь') return keepSigns ? "ʹ" : "";
        if (ukrainian) {
            String u = ukrainianLetter(cp);
            if (u != null) return u;
            if (cp == 'е') return "e";   // Ukrainian е is always "e" (ye comes from є)
            if (cp == 'Е') return "E";
        } else {
            if (cp == 'е') return usesYe(prevCyrillicCp) ? "ye" : "e";
            if (cp == 'Е') return usesYe(prevCyrillicCp) ? "Ye" : "E";
        }
        String mapped = BGN_PCGN.get(cp);
        return mapped == null ? new String(Character.toChars(cp)) : mapped;
    }

    /** Ukrainian-specific BGN/PCGN values that differ from the shared (Russian) map. */
    private static String ukrainianLetter(int cp) {
        switch (cp) {
            case 'г': return "h";  case 'Г': return "H";   // Russian g
            case 'ґ': return "g";  case 'Ґ': return "G";   // Ukrainian-only letter
            case 'и': return "y";  case 'И': return "Y";   // Russian i
            case 'і': return "i";  case 'І': return "I";
            case 'ї': return "yi"; case 'Ї': return "Yi";
            case 'є': return "ye"; case 'Є': return "Ye";
            default: return null;
        }
    }

    private static boolean usesYe(int prevCyrillicCp) {
        return prevCyrillicCp < 0 || isRussianYeTrigger(prevCyrillicCp);
    }

    private static boolean isRussianYeTrigger(int cp) {
        switch (cp) {
            case 'а': case 'е': case 'ё': case 'и': case 'о': case 'у': case 'ы': case 'э': case 'ю': case 'я':
            case 'А': case 'Е': case 'Ё': case 'И': case 'О': case 'У': case 'Ы': case 'Э': case 'Ю': case 'Я':
            case 'й': case 'Й':
            case 'ъ': case 'Ъ': case 'ь': case 'Ь':
                return true;
            default:
                return false;
        }
    }

    private static boolean isCyrillicSource(int cp) {
        return (cp >= 0x0400 && cp <= 0x04FF) || (cp >= 0x0500 && cp <= 0x052F);
    }

    private static String postProcessCyrillic(String value) {
        return value
                .replace("Ë", "Yo")
                .replace("ë", "yo")
                .replace("ǵ", "g")
                .replace("Ǵ", "G")
                .replace("ḱ", "k")
                .replace("Ḱ", "K")
                .replace("ẑ", "dz")
                .replace("Ẑ", "Dz")
                .replace("ì", "i")
                .replace("đ", "dj")
                .replace("Đ", "Dj")
                .replace("ć", "c")
                .replace("Ć", "C");
    }

    /** "Pronunciation" (sound-based) Korean mode value; otherwise letter-by-letter. */
    public static final String KOREAN_PRONUNCIATION = "Pronunciation";

    public static boolean koreanFollowSound(String koreanMode) {
        return KOREAN_PRONUNCIATION.equalsIgnoreCase(koreanMode);
    }

    public static String romanizeKorean(String text) {
        return romanizeKorean(text, false);
    }

    public static String romanizeKorean(String text, boolean followSound) {
        if (text == null) return null;
        if (followSound) return SpicyKoreanG2P.romanize(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0xAC00 && cp <= 0xD7A3) {
                int s = cp - 0xAC00;
                int initial = s / 588;
                int vowel = (s % 588) / 28;
                int fin = s % 28;
                out.append(HANGUL_INITIAL[initial]).append(HANGUL_VOWEL[vowel]).append(HANGUL_FINAL[fin]);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    public static String romanizeGreek(String text) {
        if (text == null) return null;
        String normalized = stripGreekDiacritics(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            String mapped = GREEK.get(cp);
            if (mapped == null) out.appendCodePoint(cp);
            else out.append(mapped);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String stripGreekDiacritics(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }

    private static void put(String source, String target) {
        BGN_PCGN.put(source.codePointAt(0), target);
    }

    private static void putGreek(String source, String target) {
        GREEK.put(source.codePointAt(0), target);
    }
}
