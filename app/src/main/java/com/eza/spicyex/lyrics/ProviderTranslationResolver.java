package com.eza.spicyex.lyrics;

import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Selects source-provided translations only when their language is compatible with the target. */
public final class ProviderTranslationResolver {
    private static final String SIMPLIFIED_ONLY = "这来时个们为国发后里东说车门体边头书见长万与云无广电乐龙汉马风开关听话爱让从对学实进会样还现点动过当应产种经认条达处气华场亲线该飞给众许变记仅办务权张声岁买卖带阶际导叶阳单难选连艺区极运历标识钟岛湾够顾礼旧习";
    private static final String TRADITIONAL_ONLY = "這來時個們為國發後裡東說車門體邊頭書見長萬與雲無廣電樂龍漢馬風開關聽話愛讓從對學實進會樣還現點動過當應產種經認條達處氣華場親線該飛給眾許變記僅辦務權張聲歲買賣帶階際導葉陽單難選連藝區極運歷標識鐘島灣夠顧禮舊習";

    private ProviderTranslationResolver() {
    }

    public static int applyTranslations(LyricsDocument document, String targetLanguage) {
        if (document == null || document.lines == null) return 0;
        int applied = 0;
        for (LyricsLine line : document.lines) {
            if (line == null) continue;
            if (isBlank(line.translatedText)) {
                String value = resolve(line.text, line.providerTranslatedText,
                        line.providerTranslationLanguage, targetLanguage);
                if (!isBlank(value)) {
                    line.translatedText = value;
                    applied++;
                }
            }
            if (line.backgroundLines == null) continue;
            for (BackgroundLine background : line.backgroundLines) {
                if (background == null || !isBlank(background.translatedText)) continue;
                String value = resolve(background.text, background.providerTranslatedText,
                        background.providerTranslationLanguage, targetLanguage);
                if (!isBlank(value)) {
                    background.translatedText = value;
                    applied++;
                }
            }
        }
        return applied;
    }

    public static String resolve(String sourceText, String providerText,
                                 String declaredLanguage, String targetLanguage) {
        if (!GoogleEnhancer.shouldDisplayTranslation(sourceText, providerText)) return "";
        String target = normalizeTarget(targetLanguage);
        if (target.isEmpty()) return "";
        String declared = normalizeLanguageTag(declaredLanguage);
        if (!declared.isEmpty()) return declaredMatches(providerText, declared, target) ? providerText.trim() : "";

        String inferred = inferLanguage(providerText);
        if (!inferred.isEmpty()) return inferred.equals(target) ? providerText.trim() : "";
        if (!containsLatinLetter(providerText)) return "";
        if ("en".equals(target)) return providerText.trim();
        String compact = providerText.replaceAll("[^\\p{L}\\s']", " ").replaceAll("\\s+", " ").trim();
        return compact.length() >= 12 && LatinLanguageGate.lineLooksTargetLatin(compact, target)
                ? providerText.trim() : "";
    }

    static String normalizeLanguageTag(String language) {
        String value = safe(language).trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        if (value.equals("zh-cn") || value.equals("zh-sg") || value.equals("zh-hans") || value.equals("cmn-hans")) {
            return "zh-Hans";
        }
        if (value.equals("zh-tw") || value.equals("zh-hk") || value.equals("zh-mo")
                || value.equals("zh-hant") || value.equals("cmn-hant")) {
            return "zh-Hant";
        }
        if (value.equals("zh") || value.equals("zho") || value.equals("chi")
                || value.equals("cmn") || value.equals("yue")) return "zh";
        int separator = value.indexOf('-');
        if (separator == 2) return value.substring(0, 2);
        String iso2 = SpicyProcessing.toIso2(value);
        if (iso2.length() == 2) return iso2;
        switch (value) {
            case "ara": return "ar";
            case "heb": return "he";
            case "fas":
            case "per": return "fa";
            case "tha": return "th";
            case "ces":
            case "cze": return "cs";
            case "slk":
            case "slo": return "sk";
            case "hun": return "hu";
            case "ron":
            case "rum": return "ro";
            default: return iso2;
        }
    }

    private static boolean declaredMatches(String text, String declared, String target) {
        if (declared.equals(target)) return true;
        if (!"zh".equals(declared)) return false;
        String inferred = inferHanLanguage(text);
        return !inferred.isEmpty() && inferred.equals(target);
    }

    private static String normalizeTarget(String language) {
        String normalized = normalizeLanguageTag(language);
        if ("zh".equals(normalized)) return "zh-Hans";
        return normalized;
    }

    private static String inferLanguage(String text) {
        if (containsRange(text, 0x3040, 0x30ff) || containsRange(text, 0x31f0, 0x31ff)) return "ja";
        if (containsRange(text, 0xac00, 0xd7af)) return "ko";
        String han = inferHanLanguage(text);
        if (!han.isEmpty()) return han;
        if (containsRange(text, 0x0370, 0x03ff)) return "el";
        if (containsRange(text, 0x0e00, 0x0e7f)) return "th";
        if (containsRange(text, 0x0590, 0x05ff)) return "he";
        if (containsRange(text, 0x0600, 0x06ff)) return "ar";
        if (containsRange(text, 0x0980, 0x09ff)) return "bn";
        if (containsRange(text, 0x0b80, 0x0bff)) return "ta";
        return "";
    }

    private static String inferHanLanguage(String text) {
        if (isBlank(text)) return "";
        boolean simplified = containsAny(text, SIMPLIFIED_ONLY);
        boolean traditional = containsAny(text, TRADITIONAL_ONLY);
        if (simplified && !traditional) return "zh-Hans";
        if (traditional && !simplified) return "zh-Hant";
        return "";
    }

    private static boolean containsLatinLetter(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsRange(String text, int start, int end) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= start && cp <= end) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsAny(String text, String candidates) {
        if (text == null || candidates == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (candidates.indexOf(text.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
