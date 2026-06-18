package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;

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
        if (needRomanize && !isBlank(result.romanized)) LyricCaches.putProcessingValue(context, processingVersion, romanKey, result.romanized);
        if (needTranslate && !isBlank(result.translated)) LyricCaches.putProcessingValue(context, processingVersion, translateKey, result.translated);
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

    public static final class Enhancement {
        public String romanized = "";
        public String translated = "";
    }
}
