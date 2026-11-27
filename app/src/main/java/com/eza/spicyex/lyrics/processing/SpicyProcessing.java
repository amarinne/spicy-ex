package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.language.SpicyTextDetection;

import java.util.Locale;

import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiLineClass;
import com.eza.spicyex.lyrics.ai.AiLineClassifier;

import java.util.List;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import com.eza.spicyex.lyrics.LyricUtils;

/**
 * Android port of Spicy fetchLyrics.ts / Fork/Translation.ts processing gates.
 * Emits decisions only. Renderer/platform own presentation.
 */
public final class SpicyProcessing {
    // v19: target-compatible provider translations are separated from generated translations.
    public static final int PROCESSING_VERSION = 20;

    private SpicyProcessing() {
    }

    public static boolean hasRomanizationWorkQuick(String text) {
        return SpicyTextDetection.hasNonLatinLetter(text);
    }

    public static boolean hasTranslationWorkQuick(String text, String targetLang) {
        if (isBlank(text) || isBlank(targetLang)) return false;
        if (hasObviousNonTargetScript(text, targetLang)) return true;
        if ("en".equalsIgnoreCase(targetLang)) {
            if (SpicyTextDetection.hasRomanizableScript(text) || hasNonAsciiLatin(text)) return true;
            if (looksLikeLatinLyricLine(text)) return !looksClearlyEnglish(text);
            return lineLooksNonTargetLatin(text, targetLang);
        }
        return true;
    }

    public static boolean shouldTranslateLine(String text, String sourceLang, String targetLang) {
        return shouldTranslateLine(text, sourceLang, targetLang, null);
    }

    /** Translation gate that prefers a session detection result over detector or script guessing. */
    public static boolean shouldTranslateLine(String text, String sourceLang, String targetLang,
                                              DetectionResult detection) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.codePoints().noneMatch(Character::isLetter)
                || isBlank(targetLang)) return false;
        targetLang = toIso2(targetLang);
        if (hasObviousNonTargetScript(trimmed, targetLang)) return true;
        if (detection != null && detection.hasLanguage()) {
            // Detector truth wins over the source hint: a hint can be wrong or stale, and the
            // detector was already paid for this row.
            return !targetLang.equalsIgnoreCase(detection.language);
        }

        if (detection != null) return true; // Uncertainty or backend failure is never proof of sameness.

        String sourceIso2 = toIso2(sourceLang);
        boolean sourceMatchesTarget = targetLang != null
                && (targetLang.equalsIgnoreCase(sourceIso2) || targetLang.equalsIgnoreCase(safe(sourceLang)));

        if (!sourceMatchesTarget) return true;
        return lineLooksNonTargetLatin(trimmed, targetLang);
    }

    public static void markProcessedWithoutBackground(ProcessingFlags flags) {
        if (flags == null) return;
        flags.processingVersion = PROCESSING_VERSION;
        flags.processingPending = false;
        flags.romanizationPending = false;
        flags.translationPending = false;
    }

    public static ProcessingFlags flagsFor(String text, String targetLang) {
        return flagsFor(text, "", targetLang);
    }

    public static ProcessingFlags flagsFor(String text, String sourceLang, String targetLang) {
        return flagsFor(text, sourceLang, targetLang, null);
    }

    /**
     * Gate decisions with a session-owned detection result.
     *
     * <p>When {@code detection} carries a language, the translation gate trusts it and never calls
     * the detector: the session already paid for detection once per canonical row, and the result
     * is durable. A missing result uses scripts/hints; an uncertain result keeps translation eligible.
     */
    public static ProcessingFlags flagsFor(String text, String sourceLang, String targetLang,
                                           DetectionResult detection) {
        ProcessingFlags flags = new ProcessingFlags();
        flags.processingVersion = PROCESSING_VERSION;
        flags.romanizationPending = hasRomanizationWorkQuick(text);
        flags.translationPending = detection != null || hasUsableSourceHint(sourceLang)
                ? shouldTranslateLine(text, sourceLang, targetLang, detection)
                : hasTranslationWorkQuick(text, targetLang);
        flags.processingPending = flags.romanizationPending || flags.translationPending;
        flags.detectedChinese = detection != null
                && detection.hasLanguage() && "zh".equals(detection.language);
        if (!flags.processingPending) markProcessedWithoutBackground(flags);
        return flags;
    }

    public static final class ProcessingFlags {
        public int processingVersion;
        public boolean processingPending;
        public boolean romanizationPending;
        public boolean translationPending;
        public boolean includesRomanization;
        public boolean includesTranslation;
        public boolean detectedChinese;
    }

    /**
     * Fewest confidently non-target rows that justify an automatic AI request when no row is in a
     * non-target script. One row is not enough: detector errors on short English rows ("I want
     * you" as Walloon, "Boom, boom, boom" as Tswana) are common.
     */
    static final int AUTOMATIC_AI_MIN_FOREIGN_ROWS = 2;
    /**
     * Fewest non-target rows, as a share of the rows with a confident language. Derived from the
     * X14 paid ledger (2026-11-19, 897 English-target songs): 2 rows and 10% skipped 251 of 339
     * fully-copied English songs and lost 2 songs whose only foreign text was one phrase. The
     * Google lane still translates those rows.
     */
    static final double AUTOMATIC_AI_MIN_FOREIGN_SHARE = 0.10;

    /**
     * Whether a document gives an automatic AI request real translation work.
     *
     * <p>The row gate treats an uncertain detection as work, which is right for Google but makes
     * one {@code SCRIPT_ONLY} row send a whole English song to a paid model. This gate trusts only
     * confident evidence: a row in a non-target script, or enough confidently non-target rows. A
     * document with no confident detection at all (romanized lyrics) stays eligible. Ad-lib rows
     * never count. Explicit requests do not use this gate.
     */
    public static boolean hasConfidentTranslationWork(List<LyricsLine> lines, String targetLang) {
        if (lines == null || isBlank(targetLang)) return false;
        String target = toIso2(targetLang);
        int confident = 0;
        int foreign = 0;
        for (LyricsLine line : lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            String text = line.text.trim();
            if (text.codePoints().noneMatch(Character::isLetter)) continue;
            if (AiLineClassifier.classify(text) != AiLineClass.ORDINARY) continue;
            if (hasObviousNonTargetScript(text, target)
                    || isLatinTarget(target) && hasNonLatinLetter(text)) {
                return true;
            }
            DetectionResult detection = line.detection;
            if (detection == null || !detection.hasLanguage()) continue;
            confident++;
            if (!target.equalsIgnoreCase(detection.language)) foreign++;
        }
        if (confident == 0) return true;
        return foreign >= AUTOMATIC_AI_MIN_FOREIGN_ROWS
                && foreign >= AUTOMATIC_AI_MIN_FOREIGN_SHARE * confident;
    }

    private static boolean hasNonLatinLetter(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObviousNonTargetScript(String text, String targetLang) {
        String target = safe(targetLang).toLowerCase(Locale.ROOT);
        if (SpicyTextDetection.hasCjkIdeograph(text) && !(target.startsWith("zh") || target.equals("ja"))) return true;
        if (containsKana(text) && !target.equals("ja")) return true;
        if (SpicyTextDetection.itemKoreanTest(text) && !target.equals("ko")) return true;
        if (SpicyTextDetection.itemCyrillicTest(text) && !(target.equals("ru") || target.equals("uk") || target.equals("bg") || target.equals("sr") || target.equals("mk") || target.equals("be"))) return true;
        if (SpicyTextDetection.itemGreekTest(text) && !target.equals("el")) return true;
        if (SpicyTextDetection.itemDevanagariTest(text) && !(target.equals("hi") || target.equals("mr") || target.equals("ne") || target.equals("sa"))) return true;
        if (SpicyTextDetection.itemGurmukhiTest(text) && !target.equals("pa")) return true;
        return SpicyTextDetection.itemBengaliTest(text) && !(target.equals("bn") || target.equals("as"));
    }

    /**
     * Script-based fallback, used only when no session detection result exists.
     *
     * <p>Non-ASCII Latin characters are direct evidence that the line is not English; longer ASCII
     * lines fall back to the small English function-word heuristic. No detector is consulted here:
     * detection belongs to {@code LyricsDetectionSession}, and a row without a durable result must
     * not re-enter the model from a gate.
     */
    private static boolean lineLooksNonTargetLatin(String text, String targetLang) {
        if (!isLatinTarget(targetLang)) return false;
        if (hasNonAsciiLatin(text)) return true;
        String compact = text == null ? "" : text.replaceAll("[^\\p{L}\\s']", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() < 24) return false;
        return !looksClearlyEnglish(compact);
    }

    private static boolean looksLikeLatinLyricLine(String text) {
        String compact = text == null ? "" : text.replaceAll("[^\\p{L}\\s']", " ").replaceAll("\\s+", " ").trim();
        return compact.length() >= 12 && compact.indexOf(' ') > 0;
    }

    private static boolean looksClearlyEnglish(String text) {
        String compact = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z'\\s]", " ").replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) return false;
        int matches = 0;
        for (String word : compact.split(" ")) {
            switch (word) {
                case "i": case "you": case "the": case "this": case "that": case "is": case "are":
                case "am": case "my": case "your": case "we": case "they": case "it": case "to":
                case "and": case "of": case "in": case "for": case "with": case "know": case "song":
                case "already": case "english": case "love": case "me": case "be": case "not":
                    matches++;
                    break;
                default:
                    break;
            }
        }
        return matches >= 3;
    }

    private static boolean isLatinTarget(String targetLang) {
        String target = safe(targetLang).toLowerCase(Locale.ROOT);
        return target.equals("en") || target.equals("es") || target.equals("fr") || target.equals("de")
                || target.equals("it") || target.equals("pt") || target.equals("nl") || target.equals("pl")
                || target.equals("sv") || target.equals("da") || target.equals("no") || target.equals("fi")
                || target.equals("tr") || target.equals("id") || target.equals("ms") || target.equals("vi");
    }

    private static boolean hasNonAsciiLatin(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x00C0 && cp <= 0x024F) || (cp >= 0x1E00 && cp <= 0x1EFF)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasUsableSourceHint(String sourceLang) {
        String source = safe(sourceLang).toLowerCase(Locale.ROOT);
        return !source.isEmpty() && !"unknown".equals(source) && !"auto".equals(source);
    }

    private static boolean containsKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0x3040 && cp <= 0x30FF) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static String toIso2(String sourceLang) {
        String source = safe(sourceLang).trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (source.length() == 2) return source;
        if (source.length() > 2 && source.charAt(2) == '-') return source.substring(0, 2);
        switch (source) {
            case "english": return "en";
            case "spanish": return "es";
            case "french": return "fr";
            case "german": return "de";
            case "italian": return "it";
            case "portuguese": return "pt";
            case "japanese": return "ja";
            case "korean": return "ko";
            case "chinese": return "zh";
            case "eng": return "en";
            case "spa": return "es";
            case "fra":
            case "fre": return "fr";
            case "deu":
            case "ger": return "de";
            case "ita": return "it";
            case "por": return "pt";
            case "nld":
            case "dut": return "nl";
            case "pol": return "pl";
            case "swe": return "sv";
            case "dan": return "da";
            case "nor": return "no";
            case "fin": return "fi";
            case "tur": return "tr";
            case "ind": return "id";
            case "msa":
            case "may": return "ms";
            case "vie": return "vi";
            case "rus": return "ru";
            case "ukr": return "uk";
            case "bul": return "bg";
            case "srp": return "sr";
            case "mkd": return "mk";
            case "bel": return "be";
            case "ell":
            case "gre": return "el";
            case "hin": return "hi";
            case "pan":
            case "pun": return "pa";
            case "ben": return "bn";
            case "mar": return "mr";
            case "tam": return "ta";
            case "tel": return "te";
            case "urd": return "ur";
            case "guj": return "gu";
            case "kan": return "kn";
            case "mal": return "ml";
            case "jpn": return "ja";
            case "kor": return "ko";
            case "cmn":
            case "yue":
            case "zho":
            case "chi": return "zh";
            default: return source;
        }
    }

}
