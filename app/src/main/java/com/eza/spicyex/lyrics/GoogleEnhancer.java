package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Google Translate-backed romanization and translation enhancer. */
public final class GoogleEnhancer {
    private static final int GOOGLE_REQUEST_RETRIES = 1;
    private static final long GOOGLE_REQUEST_MIN_INTERVAL_MS = 150L;
    private static final long GOOGLE_REQUEST_RETRY_DELAY_MS = 1000L;
    private static final Object GOOGLE_REQUEST_LOCK = new Object();
    private static final Pattern BATCH_MARKER_PATTERN = Pattern.compile("\\[\\[SPX_(\\d{3})\\]\\]");
    private static long lastGoogleRequestAtMs = 0L;

    private GoogleEnhancer() {
    }

    public static Enhancement enhanceLine(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            String targetLang,
            String text,
            boolean needRomanize,
            boolean needTranslate
    ) {
        Enhancement result = new Enhancement();
        if (isBlank(text) || (!needRomanize && !needTranslate)) return result;

        String source = LyricCaches.sourceLanguageForCache(sourceLang);
        String target = isBlank(targetLang) ? "en" : targetLang;
        String romanKey = LyricCaches.romanizationKey(trackId, sourceLang, text);
        String translateKey = LyricCaches.translationKey(trackId, sourceLang, target, text);
        String cachedRomanized = needRomanize ? LyricCaches.getProcessingValue(context, processingVersion, romanKey) : null;
        if (!isBlank(cachedRomanized) && SpicyTextDetection.hasRomanizableScript(cachedRomanized)) cachedRomanized = null;
        String cachedTranslated = needTranslate ? LyricCaches.getProcessingValue(context, processingVersion, translateKey) : null;
        if ((!needRomanize || cachedRomanized != null) && (!needTranslate || cachedTranslated != null)) {
            result.romanized = cachedRomanized == null ? "" : cachedRomanized;
            result.translated = cachedTranslated == null ? "" : cachedTranslated;
            return result;
        }

        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + Uri.encode(source)
                + "&tl=" + Uri.encode(target)
                + "&dt=t" + (needRomanize ? "&dt=rm" : "")
                + "&q=" + Uri.encode(text);
        Request request = new Request.Builder().url(url).get().build();
        String body = executeRequestBody(http, request);
        if (isBlank(body)) {
            result.romanized = cachedRomanized == null ? "" : cachedRomanized;
            result.translated = cachedTranslated == null ? "" : cachedTranslated;
            return result;
        }
        String parsedRomanized = needRomanize ? parseRomanization(body) : "";
        if (!isBlank(parsedRomanized) && SpicyTextDetection.hasRomanizableScript(parsedRomanized)) parsedRomanized = "";
        result.romanized = firstNonBlank(cachedRomanized, parsedRomanized);
        result.translated = firstNonBlank(cachedTranslated, needTranslate ? parseTranslation(body) : "");
        if (!shouldDisplayTranslation(text, result.translated)) result.translated = "";
        if (needRomanize && !isBlank(result.romanized)) LyricCaches.putProcessingValue(context, processingVersion, romanKey, result.romanized);
        if (needTranslate && !isBlank(result.translated)) LyricCaches.putProcessingValue(context, processingVersion, translateKey, result.translated);
        return result;
    }

    public static BatchResult translateBatch(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            String targetLang,
            List<BatchLine> lines
    ) {
        BatchResult result = new BatchResult();
        if (lines == null || lines.isEmpty()) return result;

        String target = isBlank(targetLang) ? "en" : targetLang;
        List<BatchLine> pending = new ArrayList<>();
        for (BatchLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            String cached = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(trackId, sourceLang, target, line.text));
            if (!isBlank(cached) && shouldDisplayTranslation(line.text, cached)) {
                result.translations.put(line.index, cached);
                result.cachedIndices.add(line.index);
            } else {
                pending.add(line);
            }
        }
        if (pending.isEmpty()) return result;

        String source = LyricCaches.sourceLanguageForCache(sourceLang);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) query.append('\n');
            query.append(marker(i)).append(' ').append(pending.get(i).text);
        }

        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + Uri.encode(source)
                + "&tl=" + Uri.encode(target)
                + "&dt=t&q=" + Uri.encode(query.toString());
        Request request = new Request.Builder().url(url).get().build();
        String body = executeRequestBody(http, request);
        if (isBlank(body)) return result;

        Map<Integer, String> parsed = parseBatchTranslation(body);
        for (int i = 0; i < pending.size(); i++) {
            BatchLine line = pending.get(i);
            String translated = parsed.get(i);
            if (isBlank(translated)) continue;
            translated = stripMarkerEcho(translated, i).trim();
            if (!shouldDisplayTranslation(line.text, translated)) continue;
            result.translations.put(line.index, translated);
            LyricCaches.putProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(trackId, sourceLang, target, line.text), translated);
        }
        return result;
    }

    private static String executeRequestBody(OkHttpClient http, Request request) {
        if (http == null || request == null) return null;
        for (int attempt = 0; attempt <= GOOGLE_REQUEST_RETRIES; attempt++) {
            throttleGoogleRequest();
            try (Response response = http.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
                if (response.code() != 429 && response.code() < 500) return null;
            } catch (IOException ignored) {
            }
            if (attempt < GOOGLE_REQUEST_RETRIES) quietSleep(GOOGLE_REQUEST_RETRY_DELAY_MS);
        }
        return null;
    }

    private static void throttleGoogleRequest() {
        synchronized (GOOGLE_REQUEST_LOCK) {
            long now = SystemClock.elapsedRealtime();
            long waitMs = lastGoogleRequestAtMs + GOOGLE_REQUEST_MIN_INTERVAL_MS - now;
            if (waitMs > 0L) quietSleep(waitMs);
            lastGoogleRequestAtMs = SystemClock.elapsedRealtime();
        }
    }

    private static void quietSleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String parseTranslation(String body) {
        try {
            JsonArray root = JsonParser.parseString(body).getAsJsonArray();
            JsonArray sentences = root.get(0).getAsJsonArray();
            StringBuilder out = new StringBuilder();
            for (JsonElement element : sentences) {
                if (!element.isJsonArray()) continue;
                JsonArray sentence = element.getAsJsonArray();
                if (sentence.size() > 0 && !sentence.get(0).isJsonNull()) {
                    out.append(sentence.get(0).getAsString());
                }
            }
            return out.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static Map<Integer, String> parseBatchTranslation(String body) {
        Map<Integer, String> result = new LinkedHashMap<>();
        String translated = parseTranslation(body);
        if (isBlank(translated)) return result;
        Matcher matcher = BATCH_MARKER_PATTERN.matcher(translated);
        int current = -1;
        int textStart = -1;
        while (matcher.find()) {
            if (current >= 0 && textStart >= 0) {
                String value = translated.substring(textStart, matcher.start()).trim();
                if (!isBlank(value)) result.put(current, value);
            }
            try {
                current = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                current = -1;
            }
            textStart = matcher.end();
        }
        if (current >= 0 && textStart >= 0) {
            String value = translated.substring(textStart).trim();
            if (!isBlank(value)) result.put(current, value);
        }
        return result;
    }

    private static String parseRomanization(String body) {
        try {
            JsonArray root = JsonParser.parseString(body).getAsJsonArray();
            JsonArray sentences = root.get(0).getAsJsonArray();
            for (JsonElement element : sentences) {
                if (!element.isJsonArray()) continue;
                JsonArray sentence = element.getAsJsonArray();
                if (sentence.size() > 3 && !sentence.get(3).isJsonNull()) {
                    String value = sentence.get(3).getAsString();
                    if (!isBlank(value)) return value.trim();
                }
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private static String marker(int index) {
        return String.format(java.util.Locale.US, "[[SPX_%03d]]", index);
    }

    private static String stripMarkerEcho(String text, int index) {
        if (text == null) return "";
        return text.replace(marker(index), "").trim();
    }

    public static boolean sameText(String a, String b) {
        return normalizeCompare(a).equals(normalizeCompare(b));
    }

    public static boolean shouldDisplayTranslation(String source, String translated) {
        return !isBlank(translated)
                && !sameText(source, translated)
                && !looksLikeRomanizationEcho(source, translated);
    }

    static boolean looksLikeRomanizationEcho(String source, String translated) {
        if (isBlank(source) || isBlank(translated)) return false;
        String out = normalizeCompare(translated);
        if (isBlank(out)) return false;
        for (String candidate : romanizationCandidates(source)) {
            if (!isBlank(candidate) && out.equals(normalizeCompare(candidate))) return true;
        }
        return false;
    }

    private static List<String> romanizationCandidates(String source) {
        ArrayList<String> out = new ArrayList<>();
        if (SpicyTextDetection.itemCyrillicTest(source)) {
            out.add(SpicyRomanizer.romanizeCyrillic(source, SpicyRomanizer.CYRILLIC_RUSSIAN, false));
            out.add(SpicyRomanizer.romanizeCyrillic(source, SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
        }
        if (SpicyTextDetection.itemGreekTest(source)) out.add(SpicyRomanizer.romanizeGreek(source));
        if (SpicyTextDetection.itemKoreanTest(source)) {
            out.add(SpicyRomanizer.romanizeKorean(source));
            out.add(SpicyKoreanG2P.romanize(source));
        }
        if (SpicyTextDetection.itemChineseTest(source) && !SpicyTextDetection.hasKana(source)) {
            out.add(SpicyJapaneseChineseProcessor.romanizeChineseLine(source, "pinyin", false));
        }
        if (SpicyTextDetection.hasKana(source)) out.add(SpicyJapaneseChineseProcessor.romanizeJapaneseLine(source));
        return out;
    }

    private static String normalizeCompare(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ң', 'n')
                .replace('ŋ', 'n')
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')
                .replace('—', '-')
                .replace("…", "...");
        return normalized
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static final class BatchLine {
        public final int index;
        public final String text;

        public BatchLine(int index, String text) {
            this.index = index;
            this.text = text == null ? "" : text;
        }
    }

    public static final class BatchResult {
        public final Map<Integer, String> translations = new LinkedHashMap<>();
        public final Set<Integer> cachedIndices = new HashSet<>();
    }

    public static final class Enhancement {
        public String romanized = "";
        public String translated = "";
    }
}
