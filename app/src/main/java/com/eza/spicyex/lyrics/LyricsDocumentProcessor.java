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
        String targetLang = config != null ? config.get(Settings.TRANSLATION_TARGET) : "en";
        String sourceLang = effectiveSourceLanguage(config, doc.language);
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
        boolean translationEnabled = FeatureAvailability.translationAvailable()
                && (config == null || config.getBoolean(
                Settings.TRANSLATION_ENABLED.key,
                !"disabled".equalsIgnoreCase(config.get(Settings.TRANSLATION_BACKEND))
        ));

        String fullText = collectText(doc);
        String sourceLang = effectiveSourceLanguage(config, doc.language);
        SpicyProcessing.ProcessingFlags flags = SpicyProcessing.flagsFor(fullText, sourceLang, targetLang);
        doc.processingVersion = flags.processingVersion;
        doc.romanizationPending = FeatureAvailability.transliterationAvailable() && flags.romanizationPending;
        doc.translationPending = translationEnabled && flags.translationPending;
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

}
