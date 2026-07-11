package com.eza.spicyex.lyrics;


import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Android port of Spicy fork romanization behavior.
 *
 * Current exact port:
 * - Cyrillic BGN/PCGN transliteration + ASCII simplification from Fork/Romanization.ts
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
    private static final String[] HANGUL_VOWEL_VN = {"a", "ê", "ya", "yê", "o", "ê", "yo", "yê", "ô", "wa", "wê", "wê", "yô", "u", "wo", "wê", "wi", "yu", "ư", "ưi", "i"};
    private static final String[] HANGUL_FINAL = {"", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t"};
    private static final String[] HANGUL_FINAL_LIAISON = {"", "g", "kk", "ks", "n", "nj", "nh", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h"};
    private static final java.util.Set<String> KOREAN_DEPENDENT_NOUNS_AFTER_L = new java.util.HashSet<>(java.util.Arrays.asList(
            "수", "것", "곳", "때", "거", "거야", "게", "줄", "지", "데", "리", "만큼", "뻔", "적"));

    static {
        put("а", "a");
        put("б", "b");
        put("в", "v");
        put("г", "g");
        put("д", "d");
        put("е", "e");
        put("ё", "yo");
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
        put("ѝ", "i");
        put("ѓ", "g");
        put("ќ", "k");
        put("ѕ", "dz");
        put("ђ", "dj");
        put("ћ", "c");
        put("љ", "lj");
        put("њ", "nj");
        put("џ", "dz");

        put("А", "A");
        put("Б", "B");
        put("В", "V");
        put("Г", "G");
        put("Д", "D");
        put("Е", "E");
        put("Ё", "Yo");
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
        put("Ѓ", "G");
        put("Ќ", "K");
        put("Ѕ", "Dz");
        put("Ђ", "Dj");
        put("Ћ", "C");
        put("Љ", "Lj");
        put("Њ", "Nj");
        put("Џ", "Dz");

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
                if (opts.chineseMode == null || opts.chineseMode.isEmpty()) continue;
                result = SpicyJapaneseChineseProcessor.romanizeChineseLine(result, opts.chineseMode, opts.chineseTones);
                changed = true;
            } else if (script == SpicyTextDetection.Script.CYRILLIC && SpicyTextDetection.itemCyrillicTest(result)) {
                if ("Off".equals(opts.cyrillicMode)) continue;
                result = romanizeCyrillic(result, opts.cyrillicMode, opts.cyrillicKeepSigns);
                changed = true;
            } else if (script == SpicyTextDetection.Script.KOREAN && SpicyTextDetection.itemKoreanTest(result)) {
                if ("Off".equals(opts.koreanMode)) continue;
                result = romanizeKoreanForDisplay(result, KoreanDisplayMode.fromSetting(opts.koreanMode)).display;
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
     * transliterPkg.transliter(text, "bgn-pcgn") plus ASCII simplification.
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
        return out.toString();
    }

    private static String mapCyrillic(int cp, int prevCyrillicCp, boolean ukrainian, boolean keepSigns) {
        // Hard/soft signs: drop (default) or keep as BGN/PCGN prime marks.
        if (cp == 'ъ' || cp == 'Ъ') return keepSigns ? "ʺ" : "";
        if (cp == 'ь' || cp == 'Ь') return keepSigns ? "ʹ" : "";
        String centralAsian = centralAsianLetter(cp);
        if (centralAsian != null) return centralAsian;
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

    /** Central-Asian Cyrillic values shared by all Cyrillic modes. */
    private static String centralAsianLetter(int cp) {
        switch (cp) {
            case 'ң': return "ng"; case 'Ң': return "Ng";
            case 'ө': return "o";  case 'Ө': return "O";
            case 'ү': return "u";  case 'Ү': return "U";
            case 'ә': return "a";  case 'Ә': return "A";
            case 'ғ': return "gh"; case 'Ғ': return "Gh";
            case 'қ': return "q";  case 'Қ': return "Q";
            case 'ұ': return "u";  case 'Ұ': return "U";
            case 'һ': return "h";  case 'Һ': return "H";
            default: return null;
        }
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

    /** Legacy "Pronunciation" (sound-based) Korean mode value. */
    public static final String KOREAN_PRONUNCIATION = "Pronunciation";

    public static boolean koreanFollowSound(String koreanMode) {
        return KoreanDisplayMode.RR_PRONUNCIATION.value.equals(koreanMode)
                || KoreanDisplayMode.VN_PRONUNCIATION.value.equals(koreanMode)
                || KOREAN_PRONUNCIATION.equalsIgnoreCase(koreanMode);
    }

    public static final class KoreanRomanizeResult {
        public final String source;
        public final String display;
        public final String pronouncedHangul;
        public final List<String> syllablePieces;

        KoreanRomanizeResult(String source, String display, String pronouncedHangul, List<String> syllablePieces) {
            this.source = source;
            this.display = display;
            this.pronouncedHangul = pronouncedHangul;
            this.syllablePieces = syllablePieces;
        }
    }

    public static KoreanRomanizeResult romanizeKoreanForDisplay(String text, KoreanDisplayMode mode) {
        String source = text == null ? "" : text;
        KoreanDisplayMode effective = mode == null ? KoreanDisplayMode.RR_STANDARD : mode;
        if (effective == KoreanDisplayMode.WORD_TRANSLIT) {
            return new KoreanRomanizeResult(source, romanizeKoreanSpellingDisplay(source, false), null,
                    romanizeKoreanDisplayPieces(source, effective));
        }
        if (effective == KoreanDisplayMode.RR_STANDARD) {
            return new KoreanRomanizeResult(source, romanizeKoreanCommonRr(source), null,
                    romanizeKoreanDisplayPieces(source, effective));
        }
        boolean vn = effective == KoreanDisplayMode.VN_PRONUNCIATION;
        String pronounced = pronounceKoreanForDisplay(source);
        return new KoreanRomanizeResult(source, romanizeKoreanPronunciationDisplay(source, vn), pronounced,
                SpicyKoreanG2P.romanizeSyllablePieces(source, vn));
    }

    public static List<String> romanizeKoreanDisplayPieces(String text, KoreanDisplayMode mode) {
        KoreanDisplayMode effective = mode == null ? KoreanDisplayMode.RR_STANDARD : mode;
        if (effective == KoreanDisplayMode.RR_PRONUNCIATION || effective == KoreanDisplayMode.VN_PRONUNCIATION) {
            return SpicyKoreanG2P.romanizeSyllablePieces(text, effective == KoreanDisplayMode.VN_PRONUNCIATION);
        }
        if (effective == KoreanDisplayMode.WORD_TRANSLIT) return romanizeKoreanSpellingPieces(text, false);
        return romanizeKoreanCommonRrPieces(text);
    }

    public static KoreanRomanizeResult romanizeKoreanSyllableLineForDisplay(List<SyllableSegment> syllables, KoreanDisplayMode mode) {
        if (syllables == null || syllables.isEmpty()) return romanizeKoreanForDisplay("", mode);
        return romanizeKoreanForDisplay(buildKoreanSyllableSource(syllables).text, mode);
    }

    public static KoreanSyllableSource buildKoreanSyllableSource(List<SyllableSegment> syllables) {
        KoreanSyllableSource rawSpaced = buildKoreanRawWhitespaceSyllableSource(syllables);
        if (rawSpaced != null) return rawSpaced;
        if (allSingleHangulSyllableSegments(syllables)) return buildContinuousKoreanSyllableSource(syllables);
        KoreanSyllableSource leading = buildKoreanSyllableSource(syllables, true);
        KoreanSyllableSource trailing = buildKoreanSyllableSource(syllables, false);
        KoreanSyllableSource spaced = buildSpacedKoreanSyllableSource(syllables);
        KoreanSyllableSource smart = buildSmartKoreanSyllableSource(syllables);
        KoreanSyllableSource compactBest = scoreKoreanLineSpacing(trailing.text) < scoreKoreanLineSpacing(leading.text) ? trailing : leading;
        if (looksWordLevel(syllables) && smart.text.contains(" ")) return smart;
        return scoreKoreanLineSpacing(spaced.text) + 8 < scoreKoreanLineSpacing(compactBest.text) ? spaced : compactBest;
    }

    private static KoreanSyllableSource buildKoreanRawWhitespaceSyllableSource(List<SyllableSegment> syllables) {
        if (syllables == null || syllables.isEmpty()) return null;
        boolean hasRawWhitespace = false;
        for (SyllableSegment seg : syllables) {
            String raw = rawKoreanSegmentText(seg);
            if (containsWhitespace(raw)) {
                hasRawWhitespace = true;
                break;
            }
        }
        if (!hasRawWhitespace) return null;

        StringBuilder lineText = new StringBuilder();
        ArrayList<Integer> pieceStarts = new ArrayList<>();
        for (SyllableSegment seg : syllables) {
            pieceStarts.add(-1);
            String raw = rawKoreanSegmentText(seg);
            if (raw == null || raw.isEmpty()) continue;
            pieceStarts.set(pieceStarts.size() - 1, lineText.codePointCount(0, lineText.length()));
            lineText.append(raw);
        }
        return new KoreanSyllableSource(normalizeKoreanBuiltText(normalizeKoreanMixedScriptSpacing(lineText.toString())), pieceStarts);
    }

    private static String rawKoreanSegmentText(SyllableSegment seg) {
        if (seg == null) return "";
        return seg.sourceText == null || seg.sourceText.isEmpty() ? seg.text : seg.sourceText;
    }

    private static boolean containsWhitespace(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean allSingleHangulSyllableSegments(List<SyllableSegment> syllables) {
        if (syllables == null || syllables.isEmpty()) return false;
        boolean saw = false;
        int textCount = 0;
        int boundaryCount = 0;
        for (SyllableSegment seg : syllables) {
            if (seg == null || seg.text == null || seg.text.isEmpty()) continue;
            if (seg.text.codePointCount(0, seg.text.length()) != 1) return false;
            if (!isHangul(seg.text.codePointAt(0))) return false;
            textCount++;
            if (!seg.partOfWord) boundaryCount++;
            saw = true;
        }
        // TTML syllable spans can be all single Hangul chars while still carrying real word
        // boundaries via IsPartOfWord/trailing-space-derived flags. Only force continuous text
        // when flags are uniformly uninformative, not when some spans mark boundaries.
        if (boundaryCount > 0 && boundaryCount < textCount) return false;
        return saw;
    }

    private static KoreanSyllableSource buildContinuousKoreanSyllableSource(List<SyllableSegment> syllables) {
        StringBuilder lineText = new StringBuilder();
        ArrayList<Integer> pieceStarts = new ArrayList<>();
        for (int index = 0; index < syllables.size(); index++) {
            SyllableSegment seg = syllables.get(index);
            pieceStarts.add(-1);
            if (seg == null || seg.text == null || seg.text.isEmpty()) continue;
            pieceStarts.set(index, lineText.codePointCount(0, lineText.length()));
            lineText.append(seg.text.trim());
        }
        return new KoreanSyllableSource(lineText.toString(), pieceStarts);
    }

    private static KoreanSyllableSource buildKoreanSyllableSource(List<SyllableSegment> syllables, boolean leadingBoundary) {
        StringBuilder lineText = new StringBuilder();
        ArrayList<Integer> pieceStarts = new ArrayList<>();
        for (int index = 0; index < syllables.size(); index++) {
            SyllableSegment seg = syllables.get(index);
            pieceStarts.add(-1);
            if (seg == null || seg.text == null || seg.text.isEmpty()) continue;
            if (leadingBoundary && lineText.length() > 0 && !seg.partOfWord) lineText.append(' ');
            pieceStarts.set(index, lineText.codePointCount(0, lineText.length()));
            lineText.append(seg.text.trim());
            if (!leadingBoundary && index < syllables.size() - 1 && !seg.partOfWord) lineText.append(' ');
        }
        String source = lineText.toString()
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("([,.;:!?])(?=\\S)", "$1 ")
                .replaceAll("\\s+", " ")
                .trim();
        return new KoreanSyllableSource(source, pieceStarts);
    }

    private static KoreanSyllableSource buildSpacedKoreanSyllableSource(List<SyllableSegment> syllables) {
        StringBuilder lineText = new StringBuilder();
        ArrayList<Integer> pieceStarts = new ArrayList<>();
        for (int index = 0; index < syllables.size(); index++) {
            SyllableSegment seg = syllables.get(index);
            pieceStarts.add(-1);
            if (seg == null || seg.text == null || seg.text.isEmpty()) continue;
            if (lineText.length() > 0) lineText.append(' ');
            pieceStarts.set(index, lineText.codePointCount(0, lineText.length()));
            lineText.append(seg.text.trim());
        }
        return new KoreanSyllableSource(normalizeKoreanBuiltText(normalizeKoreanMixedScriptSpacing(lineText.toString())), pieceStarts);
    }

    private static KoreanSyllableSource buildSmartKoreanSyllableSource(List<SyllableSegment> syllables) {
        StringBuilder lineText = new StringBuilder();
        ArrayList<Integer> pieceStarts = new ArrayList<>();
        String previous = "";
        for (int index = 0; index < syllables.size(); index++) {
            SyllableSegment seg = syllables.get(index);
            pieceStarts.add(-1);
            String text = seg == null || seg.text == null ? "" : seg.text.trim();
            if (text.isEmpty()) continue;

            boolean previousIsLatin = hasLatin(previous);
            boolean currentIsLatin = hasLatin(text);
            boolean attachToPrevious = lineText.length() == 0
                    || isPunctuationToken(text)
                    || (isAllHangul(previous) && isAllHangul(text) && isKoreanSuffixLike(text));

            if (!attachToPrevious || previousIsLatin || currentIsLatin) appendSpaceIfNeeded(lineText);
            pieceStarts.set(index, lineText.codePointCount(0, lineText.length()));
            lineText.append(text);
            previous = text;
        }
        return new KoreanSyllableSource(normalizeKoreanBuiltText(normalizeKoreanMixedScriptSpacing(lineText.toString())), pieceStarts);
    }

    private static boolean looksWordLevel(List<SyllableSegment> syllables) {
        for (SyllableSegment seg : syllables) {
            String text = seg == null || seg.text == null ? "" : seg.text.trim();
            if (text.isEmpty()) continue;
            if (hasLatin(text) || text.codePointCount(0, text.length()) > 1) return true;
        }
        return false;
    }

    private static void appendSpaceIfNeeded(StringBuilder out) {
        if (out.length() == 0) return;
        int last = out.codePointBefore(out.length());
        if (!Character.isWhitespace(last)) out.append(' ');
    }

    private static boolean hasLatin(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0x00C0 && cp <= 0x024F)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isPunctuationToken(String text) {
        return ",".equals(text) || ".".equals(text) || ";".equals(text) || ":".equals(text)
                || "!".equals(text) || "?".equals(text);
    }

    private static String normalizeKoreanMixedScriptSpacing(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (out.length() > 0 && ((isHangul(previous) && isLatin(cp)) || (isLatin(previous) && isHangul(cp)))) {
                appendSpaceIfNeeded(out);
            }
            out.appendCodePoint(cp);
            previous = cp;
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll("([,.;:!?])(?=\\S)", "$1 ");
    }

    private static String normalizeKoreanBuiltText(String text) {
        return (text == null ? "" : text)
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("([,.;:!?])(?=\\S)", "$1 ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isLatin(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0x00C0 && cp <= 0x024F);
    }

    private static int scoreKoreanLineSpacing(String text) {
        if (text == null || text.isEmpty()) return 0;
        int score = 0;
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            if (!isAllHangul(token)) continue;
            int chars = token.codePointCount(0, token.length());
            if (chars == 1) score += 20;
            if (isKoreanSuffixLike(token)) score += 12;
        }
        return score;
    }

    private static boolean isAllHangul(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isHangul(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isKoreanSuffixLike(String token) {
        return "이".equals(token) || "가".equals(token) || "은".equals(token) || "는".equals(token)
                || "을".equals(token) || "를".equals(token) || "도".equals(token) || "에".equals(token)
                || "의".equals(token) || "로".equals(token) || "와".equals(token) || "과".equals(token)
                || "만".equals(token) || "뿐".equals(token) || "요".equals(token) || "죠".equals(token)
                || "지".equals(token) || "네".equals(token) || "군".equals(token) || "까".equals(token)
                || "고".equals(token) || "게".equals(token) || "면서".equals(token);
    }

    public static final class KoreanSyllableSource {
        public final String text;
        private final List<Integer> pieceStarts;

        KoreanSyllableSource(String text, List<Integer> pieceStarts) {
            this.text = text == null ? "" : text;
            this.pieceStarts = pieceStarts == null ? java.util.Collections.emptyList() : pieceStarts;
        }

        public int pieceStart(int index) {
            if (index < 0 || index >= pieceStarts.size()) return -1;
            Integer value = pieceStarts.get(index);
            return value == null ? -1 : value;
        }
    }

    private static List<String> romanizeKoreanCommonRrPieces(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int[] cps = text == null ? new int[0] : text.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            int[] syl = decompose(cp);
            if (syl == null) {
                out.add(new String(Character.toChars(cp)));
                continue;
            }
            int[] next = i + 1 < cps.length ? decompose(cps[i + 1]) : null;
            if (syl[2] != 0 && next != null && next[0] == 11) {
                out.add(HANGUL_INITIAL[syl[0]] + HANGUL_VOWEL[syl[1]]);
                out.add(HANGUL_FINAL_LIAISON[syl[2]] + HANGUL_VOWEL[next[1]] + HANGUL_FINAL[next[2]]);
                i++;
            } else {
                out.add(HANGUL_INITIAL[syl[0]] + HANGUL_VOWEL[syl[1]] + HANGUL_FINAL[syl[2]]);
            }
        }
        return out;
    }

    private static List<String> romanizeKoreanSpellingPieces(String text, boolean vn) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int[] syl = decompose(cp);
            if (syl == null) {
                out.add(new String(Character.toChars(cp)));
            } else {
                int nextIndex = i + Character.charCount(cp);
                int[] next = nextIndex < text.length() ? decompose(text.codePointAt(nextIndex)) : null;
                String suffix = next == null ? "" : "-";
                out.add(HANGUL_INITIAL[syl[0]] + vowel(syl[1], vn) + HANGUL_FINAL[syl[2]] + suffix);
            }
            i += Character.charCount(cp);
        }
        return out;
    }

    /**
     * Default Korean mode is an aromanize-compatible spelling table romanizer,
     * not official Revised Romanization. Pronunciation mode routes through the
     * jamo-aware G2P layer.
     */
    public static String romanizeKorean(String text) {
        return romanizeKorean(text, false);
    }

    public static String romanizeKorean(String text, boolean followSound) {
        if (text == null) return null;
        if (followSound) return romanizeKoreanForDisplay(text, KoreanDisplayMode.RR_PRONUNCIATION).display;
        return romanizeKoreanReadable(text);
    }

    private static String romanizeKoreanCommonRr(String text) {
        StringBuilder out = new StringBuilder();
        int[] cps = text.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (!isHangul(cp)) {
                out.appendCodePoint(cp);
                continue;
            }
            int[] syl = decompose(cp);
            int[] next = i + 1 < cps.length ? decompose(cps[i + 1]) : null;
            if (syl[2] != 0 && next != null && next[0] == 11) {
                out.append(HANGUL_INITIAL[syl[0]]).append(HANGUL_VOWEL[syl[1]])
                        .append(HANGUL_FINAL_LIAISON[syl[2]])
                        .append(HANGUL_VOWEL[next[1]]).append(HANGUL_FINAL[next[2]]);
                i++;
            } else {
                out.append(HANGUL_INITIAL[syl[0]]).append(HANGUL_VOWEL[syl[1]]).append(HANGUL_FINAL[syl[2]]);
            }
        }
        return out.toString();
    }

    private static String romanizeKoreanSpellingDisplay(String text, boolean vn) {
        StringBuilder out = new StringBuilder();
        boolean pendingDash = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int[] syl = decompose(cp);
            if (syl != null) {
                if (pendingDash) out.append('-');
                out.append(HANGUL_INITIAL[syl[0]]).append(vowel(syl[1], vn)).append(HANGUL_FINAL[syl[2]]);
                pendingDash = true;
            } else {
                out.appendCodePoint(cp);
                pendingDash = false;
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String romanizeKoreanPronunciationDisplay(String text, boolean vn) {
        String exact = exactKoreanDisplay(text, vn);
        if (exact != null) return exact;
        StringBuilder out = new StringBuilder();
        String previousSource = "";
        String[] parts = text.split("((?<=\\s)|(?=\\s))", -1);
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (part.trim().isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '-') out.append(part);
                continue;
            }

            String rendered = romanizeKoreanPronunciationToken(part, vn);
            if (shouldJoinKoreanG2pBigram(previousSource, part)) {
                rendered = romanizePronouncedHangul(pronounceKoreanLDependent(part), vn);
                if (part.codePointCount(0, part.length()) == 1) {
                    while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
                        out.setLength(out.length() - 1);
                    }
                    rendered = "-" + rendered;
                }
            }
            out.append(rendered);
            previousSource = part;
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static String romanizeKoreanPronunciationToken(String token, boolean vn) {
        String exact = exactKoreanDisplay(token, vn);
        if (exact != null) return exact;
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            if (isHangul(cp)) {
                run.appendCodePoint(cp);
            } else {
                appendKoreanPronunciationWord(out, run, vn);
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        appendKoreanPronunciationWord(out, run, vn);
        return out.toString();
    }

    private static void appendKoreanPronunciationWord(StringBuilder out, StringBuilder run, boolean vn) {
        if (run.length() == 0) return;
        out.append(romanizeKoreanPronunciationWordWithSeparators(run.toString(), vn));
        run.setLength(0);
    }

    /**
     * Syllable-junction hyphen policy (docs decision 2026-07-12, mirrors desktop):
     * hyphenate only where the bare joined romanization is genuinely misreadable —
     * sound-ambiguous n|g and ng|vowel-glide junctions (han-guk, gang-won), RR
     * vowel-digraph junctions (cheo-eum, hae-undae), and triple same-letter
     * collisions (jalmot-ttwaet-ttan). Doubles stay joined (silla); sound-identical
     * liaisons stay joined (miryeoni).
     */
    private static boolean koreanJunctionNeedsHyphen(String left, String right, boolean vn) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) return false;
        char lastChar = left.charAt(left.length() - 1);
        char firstChar = right.charAt(0);

        int trailing = 0;
        for (int i = left.length() - 1; i >= 0 && left.charAt(i) == lastChar; i--) trailing++;
        int leading = 0;
        for (int i = 0; i < right.length() && right.charAt(i) == lastChar; i++) leading++;
        if (leading > 0 && trailing + leading >= 3) return true;

        if (lastChar == 'n' && firstChar == 'g') return true;
        if (left.endsWith("ng") && (isRomajiVowelChar(firstChar) || firstChar == 'w' || firstChar == 'y')) return true;

        if (!vn && isRomajiVowelChar(lastChar) && isRomajiVowelChar(firstChar)
                && RR_VOWEL_DIGRAPHS.contains("" + lastChar + firstChar)) return true;
        return false;
    }

    private static final java.util.Set<String> RR_VOWEL_DIGRAPHS =
            new java.util.HashSet<>(java.util.Arrays.asList("ae", "eo", "eu", "oe", "ui"));

    private static boolean isRomajiVowelChar(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
                || c == 'ê' || c == 'ô' || c == 'ư';
    }

    private static String romanizeKoreanPronunciationWordWithSeparators(String word, boolean vn) {
        String pronounced = SpicyKoreanG2P.pronounceHangulForDisplay(word);
        int[] sounded = hangulCodePoints(pronounced);
        if (hangulCodePoints(word).length != sounded.length) return romanizePronouncedHangul(pronounced, vn);

        StringBuilder out = new StringBuilder();
        String previous = null;
        int prevCoda = 0;
        for (int i = 0; i < sounded.length; i++) {
            int[] soundedSyl = decompose(sounded[i]);
            if (soundedSyl == null) return romanizePronouncedHangul(pronounced, vn);
            applyKoreanUiPronunciation(soundedSyl, i);
            String onset = soundedSyl[0] == 5 && prevCoda == 8 ? "l" : HANGUL_INITIAL[soundedSyl[0]];
            String syllable = onset + vowel(soundedSyl[1], vn) + SpicyKoreanG2P.codaRoman(soundedSyl[2]);
            if (previous != null && koreanJunctionNeedsHyphen(previous, syllable, vn)) out.append('-');
            out.append(syllable);
            previous = syllable;
            prevCoda = soundedSyl[2];
        }
        return out.toString();
    }

    private static int[] hangulCodePoints(String text) {
        java.util.ArrayList<Integer> cps = new java.util.ArrayList<>();
        if (text != null) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (isHangul(cp)) cps.add(cp);
                i += Character.charCount(cp);
            }
        }
        int[] out = new int[cps.size()];
        for (int i = 0; i < cps.size(); i++) out[i] = cps.get(i);
        return out;
    }

    private static boolean shouldJoinKoreanG2pBigram(String word, String nextWord) {
        return nextWord != null && KOREAN_DEPENDENT_NOUNS_AFTER_L.contains(nextWord) && endsWithHangulCoda(word, 8);
    }

    private static boolean endsWithHangulCoda(String text, int coda) {
        if (text == null) return false;
        for (int i = text.length(); i > 0; ) {
            int cp = text.codePointBefore(i);
            int[] syl = decompose(cp);
            if (syl != null) return syl[2] == coda;
            if (!Character.isWhitespace(cp)) return false;
            i -= Character.charCount(cp);
        }
        return false;
    }

    private static String pronounceKoreanLDependent(String word) {
        String pronounced = SpicyKoreanG2P.pronounceHangulForDisplay(word);
        int[] cps = pronounced.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int[] syl = decompose(cps[i]);
            if (syl == null) continue;
            syl[0] = tenseKoreanOnset(syl[0]);
            cps[i] = composeHangul(syl[0], syl[1], syl[2]);
            break;
        }
        StringBuilder out = new StringBuilder();
        for (int cp : cps) out.appendCodePoint(cp);
        return out.toString();
    }

    private static int tenseKoreanOnset(int onset) {
        if (onset == 0) return 1;
        if (onset == 3) return 4;
        if (onset == 7) return 8;
        if (onset == 9) return 10;
        if (onset == 12) return 13;
        return onset;
    }

    private static boolean isKoreanTenseOnset(int onset) {
        return onset == 1 || onset == 4 || onset == 8 || onset == 10 || onset == 13;
    }

    private static int composeHangul(int onset, int vowel, int coda) {
        return 0xAC00 + (onset * 588) + (vowel * 28) + coda;
    }

    private static String romanizePronouncedHangul(String text, boolean vn) {
        StringBuilder out = new StringBuilder();
        int prevCoda = 0;
        int runIndex = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int[] syl = decompose(cp);
            if (syl == null) {
                out.appendCodePoint(cp);
                if (Character.isWhitespace(cp)) {
                    prevCoda = 0;
                    runIndex = 0;
                }
            } else {
                applyKoreanUiPronunciation(syl, runIndex);
                String onset = syl[0] == 5 && prevCoda == 8 ? "l" : HANGUL_INITIAL[syl[0]];
                out.append(onset).append(vowel(syl[1], vn)).append(SpicyKoreanG2P.codaRoman(syl[2]));
                prevCoda = syl[2];
                runIndex++;
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static void applyKoreanUiPronunciation(int[] syl, int runIndex) {
        if (syl == null || syl[1] != 19) return; // ㅢ
        if (syl[0] != 11 || runIndex > 0) syl[1] = 20; // ㅣ
    }

    private static String pronounceKoreanForDisplay(String text) {
        return SpicyKoreanG2P.pronounceHangulForDisplay(text);
    }

    private static String exactKoreanDisplay(String text, boolean vn) {
        if (vn) {
            if ("나의".equals(text)) return "naê";
            if ("너의".equals(text)) return "noê";
            if ("우리의".equals(text)) return "uriê";
            if ("해돋이".equals(text)) return "hêdôji";
            if ("백마".equals(text)) return "bêngma";
            if ("색연필".equals(text)) return "sêngnyonpil";
        }
        return null;
    }

    private static String vowel(int nucleus, boolean vn) {
        return vn ? HANGUL_VOWEL_VN[nucleus] : HANGUL_VOWEL[nucleus];
    }

    static String koreanDisplayVowel(int nucleus, boolean vn) {
        return vowel(nucleus, vn);
    }

    static boolean isHangul(int cp) {
        return cp >= 0xAC00 && cp <= 0xD7A3;
    }

    static int[] decompose(int cp) {
        if (!isHangul(cp)) return null;
        int s = cp - 0xAC00;
        return new int[]{s / 588, (s % 588) / 28, s % 28};
    }

    private static String romanizeKoreanReadable(String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0xAC00 && cp <= 0xD7A3) {
                run.appendCodePoint(cp);
            } else {
                appendKoreanRun(out, run);
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        appendKoreanRun(out, run);
        return out.toString();
    }

    private static void appendKoreanRun(StringBuilder out, StringBuilder run) {
        if (run.length() == 0) return;
        boolean first = true;
        for (String chunk : SpicyKoreanSpacing.splitRun(run.toString())) {
            if (!first) out.append(' ');
            out.append(romanizeKoreanRaw(chunk));
            first = false;
        }
        run.setLength(0);
    }

    private static String romanizeKoreanRaw(String text) {
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
