package com.eza.spicyex.lyrics;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.SpotifyPlusConfig;

import de.robv.android.xposed.XposedBridge;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Serialized cache for post-processed romanization/translation fields. */
public final class ProcessedLyricsCache {
    private static final String TAG = "[SpotifyPlusProcessedLyricsCache]";

    private ProcessedLyricsCache() {
    }

    public static void apply(Context context, LyricsDocument doc, RomanizationOptions opts, int processingVersion) {
        if (context == null || doc == null || doc.lines == null || doc.lines.isEmpty()) return;
        try {
            String raw = LyricCaches.getProcessedDocument(context, key(doc, opts, processingVersion));
            if (isBlank(raw)) return;
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root == null || Json.optDouble(root, -1, "version") != processingVersion) return;
            doc.includesRomanization = Json.optBoolean(root, false, "includesRomanization");
            doc.includesTranslation = Json.optBoolean(root, false, "includesTranslation");
            JsonArray lines = Json.optArray(root, "lines");
            if (lines == null) return;
            int count = Math.min(lines.size(), doc.lines.size());
            for (int i = 0; i < count; i++) {
                JsonElement element = lines.get(i);
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                LyricsLine line = doc.lines.get(i);
                if (line == null) continue;
                String text = Json.optString(item, "text");
                if (!safe(line.text).equals(text)) continue;
                String romanized = Json.optString(item, "romanizedText");
                if (!isBlank(romanized)) line.romanizedText = romanized;
                String translated = Json.optString(item, "translatedText");
                if (!isBlank(translated)) line.translatedText = translated;
                String cnMode = Json.optString(item, "chineseMode");
                if (!isBlank(cnMode)) line.chineseMode = normalizeChineseMode(cnMode);
                SpicyJapaneseChineseProcessor.JapaneseReading reading = LyricsParser.parseJapaneseReading(item);
                if (reading != null) line.japaneseReading = reading;
            }
            // Clear only the passes the cache actually contains. A partial cache (e.g. romanization
            // saved before slower network translation finished, or the screen closed mid-translation)
            // must still let the remaining pass run instead of marking everything done.
            if (doc.includesRomanization) doc.romanizationPending = false;
            if (doc.includesTranslation) doc.translationPending = false;
            doc.processingPending = doc.romanizationPending || doc.translationPending;
            XposedBridge.log(TAG + " applied track=" + doc.trackId + " lines=" + count
                    + " processingPending=" + doc.processingPending
                    + " roman=" + doc.includesRomanization + " trans=" + doc.includesTranslation);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " apply failed: " + t);
        }
    }

    public static void save(Context context, LyricsDocument doc, RomanizationOptions opts, int processingVersion) {
        if (context == null || doc == null || doc.lines == null || doc.lines.isEmpty()) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", processingVersion);
            root.addProperty("trackId", safe(doc.trackId));
            root.addProperty("language", safe(doc.language));
            root.addProperty("chineseMode", normalizeChineseMode(opts.chineseMode));
            if (doc.includesRomanization) root.addProperty("includesRomanization", true);
            if (doc.includesTranslation) root.addProperty("includesTranslation", true);
            JsonArray lines = new JsonArray();
            for (LyricsLine line : doc.lines) {
                JsonObject item = new JsonObject();
                item.addProperty("text", line == null ? "" : safe(line.text));
                if (line != null && !isBlank(line.romanizedText)) item.addProperty("romanizedText", line.romanizedText);
                if (line != null && !isBlank(line.translatedText)) item.addProperty("translatedText", line.translatedText);
                if (line != null && !isBlank(line.chineseMode)) item.addProperty("chineseMode", normalizeChineseMode(line.chineseMode));
                if (line != null && line.japaneseReading != null) item.add("JapaneseReading", japaneseReadingToJson(line.japaneseReading));
                lines.add(item);
            }
            root.add("lines", lines);
            LyricCaches.putProcessedDocument(context, key(doc, opts, processingVersion), root.toString());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " save failed: " + t);
        }
    }

    private static JsonObject japaneseReadingToJson(SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        JsonObject object = new JsonObject();
        object.addProperty("sourceText", safe(reading.sourceText));
        object.addProperty("romaji", safe(reading.romaji));
        JsonArray furigana = new JsonArray();
        if (reading.furigana != null) {
            for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : reading.furigana) {
                if (segment == null || isBlank(segment.reading)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("start", segment.start);
                item.addProperty("end", segment.end);
                item.addProperty("reading", segment.reading);
                furigana.add(item);
            }
        }
        object.add("furigana", furigana);
        return object;
    }

    private static String key(LyricsDocument doc, RomanizationOptions opts, int processingVersion) {
        return LyricCaches.processedDocumentKey(processingVersion,
                doc == null ? "" : doc.trackId,
                doc == null ? "" : doc.language,
                opts);
    }

    private static String normalizeChineseMode(String mode) {
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) return SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        return SpotifyPlusConfig.CHINESE_MODE_PINYIN;
    }

}
