package com.eza.spicyex.lyrics;

import android.content.Context;

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
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            if (SpicyTextDetection.hasRomanizableScript(line.text)) {
                String cachedRomanized = LyricCaches.getProcessingValue(context, processingVersion,
                        LyricCaches.romanizationKey(doc.trackId, doc.language, line.text));
                if (!isBlank(cachedRomanized) && !cachedRomanized.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(cachedRomanized)) {
                    line.romanizedText = cachedRomanized;
                }
            }
            String cachedTranslated = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(doc.trackId, doc.language, targetLang, line.text));
            if (isBlank(line.translatedText) && !isBlank(cachedTranslated) && !GoogleEnhancer.sameText(cachedTranslated, line.text)) {
                line.translatedText = cachedTranslated;
            }
        }
    }

    private static void initProcessing(Context context, LyricsDocument doc) {
        if (doc == null) return;
        SpotifyPlusConfig config = context != null ? SpotifyPlusConfig.from(context) : null;
        String targetLang = config != null ? config.get(Settings.TRANSLATION_TARGET) : "en";
        boolean translationEnabled = config == null || config.getBoolean(
                Settings.TRANSLATION_ENABLED.key,
                !"disabled".equalsIgnoreCase(config.get(Settings.TRANSLATION_BACKEND))
        );

        String fullText = collectText(doc);
        SpicyProcessing.ProcessingFlags flags = SpicyProcessing.flagsFor(fullText, targetLang);
        doc.processingVersion = flags.processingVersion;
        doc.processingPending = flags.processingPending;
        doc.romanizationPending = flags.romanizationPending;
        doc.translationPending = translationEnabled && flags.translationPending;
        doc.detectedScripts.clear();
        doc.detectedScripts.addAll(SpicyTextDetection.detectPresentScripts(fullText, doc.language, ""));
        doc.detectedChinese = doc.detectedScripts.contains(SpicyTextDetection.Script.CHINESE);
    }

}
