package com.eza.spicyex.lyrics;

import java.util.Locale;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Android port of Spicy fetchLyrics.ts / Fork/Translation.ts processing gates.
 * Emits decisions only. Renderer/platform own presentation.
 */
public final class SpicyProcessing {
    // v17: do not invent Japanese word spaces from packed timing flags.
    public static final int PROCESSING_VERSION = 17;

    private SpicyProcessing() {
    }

    public static boolean hasRomanizationWorkQuick(String text) {
        return SpicyTextDetection.hasRomanizableScript(text);
    }

    public static boolean hasTranslationWorkQuick(String text, String targetLang) {
        if (isBlank(text) || isBlank(targetLang)) return false;
        if (hasObviousNonTargetScript(text, targetLang)) return true;
        if ("en".equalsIgnoreCase(targetLang)) {
            if (SpicyTextDetection.hasRomanizableScript(text) || hasNonAsciiLatin(text)) return true;
            if (looksLikeLatinLyricLine(text)) return true;
            return lineLooksNonTargetLatin(text, targetLang);
        }
        return true;
    }

    public static boolean shouldTranslateLine(String text, String sourceLang, String targetLang) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || "♪".equals(trimmed)) return false;

        String sourceIso2 = toIso2(sourceLang);
        boolean sourceMatchesTarget = targetLang != null
                && (targetLang.equalsIgnoreCase(sourceIso2) || targetLang.equalsIgnoreCase(safe(sourceLang)));

        if (!sourceMatchesTarget) return true;
        if (hasObviousNonTargetScript(trimmed, targetLang)) return true;
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
        ProcessingFlags flags = new ProcessingFlags();
        flags.processingVersion = PROCESSING_VERSION;
        flags.romanizationPending = hasRomanizationWorkQuick(text);
        flags.translationPending = hasUsableSourceHint(sourceLang) && shouldTranslateLine(text, sourceLang, targetLang)
                || hasTranslationWorkQuick(text, targetLang);
        flags.processingPending = flags.romanizationPending || flags.translationPending;
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

    private static boolean lineLooksNonTargetLatin(String text, String targetLang) {
        if (!isLatinTarget(targetLang)) return false;
        String compact = text.replaceAll("[^\\p{L}\\s']", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() < 24) return false;
        return LatinLanguageGate.lineLooksNonTargetLatin(compact, targetLang);
    }

    private static boolean looksLikeLatinLyricLine(String text) {
        String compact = text == null ? "" : text.replaceAll("[^\\p{L}\\s']", " ").replaceAll("\\s+", " ").trim();
        return compact.length() >= 12 && compact.indexOf(' ') > 0;
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

    static String toIso2(String sourceLang) {
        String source = safe(sourceLang).toLowerCase(Locale.ROOT);
        if (source.length() == 2) return source;
        switch (source) {
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
