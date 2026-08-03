package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Shared post-parse and post-processing document helpers. */
public final class LyricsDocumentProcessor {
    private LyricsDocumentProcessor() {
    }

    public static void finalizeParsedDocument(Context context, LyricsDocument doc, int processingVersion) {
        if (doc == null) return;
        LyricTimeline.rebalanceStaticTimings(doc);
        LyricTimeline.fillMissingEndTimes(doc.lines);
        applyProviderTranslations(context, doc);
        applyCachedGoogleEnhancements(context, doc, processingVersion);
        initProcessing(context, doc);
    }

    public static String collectText(LyricsDocument doc) {
        if (doc == null || doc.lines == null || doc.lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (LyricsLine line : doc.lines) {
            if (line == null || isBlank(line.text)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line.text);
        }
        return out.toString();
    }

    public static void applyProcessedCache(Context context, LyricsDocument doc, RomanizationOptions opts, int processingVersion) {
        ProcessedLyricsCache.apply(context, doc, opts, processingVersion);
    }

    public static void saveProcessedCache(Context context, LyricsDocument doc, RomanizationOptions opts, int processingVersion) {
        ProcessedLyricsCache.save(context, doc, opts, processingVersion);
    }

    private static void applyCachedGoogleEnhancements(Context context, LyricsDocument doc, int processingVersion) {
        if (context == null || doc == null || doc.lines == null) return;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        if (!"google_unofficial".equalsIgnoreCase(config.get(Settings.TRANSLATION_BACKEND))) return;
        String targetLang = config != null ? config.get(Settings.TRANSLATION_TARGET) : "en";
        String sourceLang = effectiveSourceLanguage(config, doc.language);
        boolean documentNeedsTranslation = SpicyProcessing.flagsFor(
                collectText(doc), sourceLang, targetLang).translationPending;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            if (FeatureAvailability.transliterationAvailable() && SpicyTextDetection.hasRomanizableScript(line.text)) {
                String cachedRomanized = LyricCaches.getProcessingValue(context, processingVersion,
                        LyricCaches.romanizationKey(doc.trackId, sourceLang, line.text));
                if (!isBlank(cachedRomanized) && !cachedRomanized.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(cachedRomanized)) {
                    line.romanizedText = cachedRomanized;
                }
            }
            if (FeatureAvailability.translationAvailable()) {
                if (!documentNeedsTranslation) continue;
                if (!SpicyProcessing.flagsFor(line.text, sourceLang, targetLang).translationPending) continue;
                String cachedTranslated = LyricCaches.getProcessingValue(context, processingVersion,
                        LyricCaches.translationKey(doc.trackId, sourceLang, targetLang, line.text));
                if (isBlank(line.translatedText) && !isBlank(cachedTranslated) && !GoogleEnhancer.sameText(cachedTranslated, line.text)) {
                    line.translatedText = cachedTranslated;
                }
            }
        }
    }

    private static void initProcessing(Context context, LyricsDocument doc) {
        if (doc == null) return;
        SpotifyPlusConfig config = context != null ? SpotifyPlusConfig.from(context) : null;
        String targetLang = config != null ? config.get(Settings.TRANSLATION_TARGET) : "en";
        String backend = config == null ? Settings.TRANSLATION_BACKEND.defaultValue
                : config.get(Settings.TRANSLATION_BACKEND);
        boolean translationEnabled = FeatureAvailability.translationAvailable()
                && config != null
                && config.get(Settings.TRANSLATION_ENABLED)
                && !"disabled".equalsIgnoreCase(backend);

        String fullText = collectText(doc);
        String sourceLang = effectiveSourceLanguage(config, doc.language);
        SpicyProcessing.ProcessingFlags flags = SpicyProcessing.flagsFor(fullText, sourceLang, targetLang);
        doc.processingVersion = flags.processingVersion;
        doc.romanizationPending = FeatureAvailability.transliterationAvailable() && flags.romanizationPending;
        doc.includesTranslation = translationEnabled && hasDisplayedTranslation(doc);
        doc.translationPending = translationEnabled
                && "google_unofficial".equalsIgnoreCase(backend)
                && hasGeneratedTranslationWork(doc, sourceLang, targetLang);
        doc.processingPending = doc.romanizationPending || doc.translationPending;
        doc.detectedScripts.clear();
        doc.detectedScripts.addAll(SpicyTextDetection.detectPresentScripts(fullText, doc.language, ""));
        doc.detectedChinese = doc.detectedScripts.contains(SpicyTextDetection.Script.CHINESE);
    }

    private static String effectiveSourceLanguage(SpotifyPlusConfig config, String documentLanguage) {
        if (config != null && "manual".equalsIgnoreCase(config.get(Settings.SOURCE_LANGUAGE_MODE))) {
            return config.get(Settings.SOURCE_LANGUAGE);
        }
        return documentLanguage;
    }

    private static void applyProviderTranslations(Context context, LyricsDocument doc) {
        if (context == null || doc == null) return;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        String backend = config.get(Settings.TRANSLATION_BACKEND);
        if (!FeatureAvailability.translationAvailable()
                || !config.get(Settings.TRANSLATION_ENABLED)
                || "disabled".equalsIgnoreCase(backend)) return;
        ProviderTranslationResolver.applyTranslations(doc, config.get(Settings.TRANSLATION_TARGET));
    }

    public static boolean hasGeneratedTranslationWork(LyricsDocument doc, String sourceLanguage, String targetLanguage) {
        if (doc == null || doc.lines == null) return false;
        if (!SpicyProcessing.flagsFor(collectText(doc), sourceLanguage, targetLanguage).translationPending) return false;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text) || !isBlank(line.translatedText)) continue;
            if (SpicyProcessing.flagsFor(line.text, sourceLanguage, targetLanguage).translationPending) return true;
        }
        return false;
    }

    public static boolean hasDisplayedTranslation(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            if (!isBlank(line.translatedText)) return true;
            if (line.backgroundLines == null) continue;
            for (BackgroundLine background : line.backgroundLines) {
                if (background != null && !isBlank(background.translatedText)) return true;
            }
        }
        return false;
    }

}
