package com.eza.spicyex.beautifullyrics.entities;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.beautifullyrics.entities.lyrics.LineSyncedLyrics;
import com.eza.spicyex.beautifullyrics.entities.lyrics.LineVocal;
import com.eza.spicyex.beautifullyrics.entities.lyrics.ProviderLyrics;
import com.eza.spicyex.beautifullyrics.entities.lyrics.StaticSyncedLyrics;
import com.eza.spicyex.beautifullyrics.entities.lyrics.SyllableMetadata;
import com.eza.spicyex.beautifullyrics.entities.lyrics.SyllableSyncedLyrics;
import com.eza.spicyex.beautifullyrics.entities.lyrics.SyllableVocalSet;
import com.eza.spicyex.beautifullyrics.entities.lyrics.TextMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

public final class LyricsTranslator {
    private static final String PREFS_CACHE = "SpotifyPlusTranslationCache";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private LyricsTranslator() {
    }

    public static String maybeTranslateContent(Activity activity, String trackId, String type, String content) {
        SpotifyPlusConfig config = SpotifyPlusConfig.from(activity);
        if (!config.getBoolean(Settings.TRANSLATION_ENABLED.key,
                !"disabled".equalsIgnoreCase(config.get(Settings.TRANSLATION_BACKEND)))) return content;
        if (!config.showTranslationLyrics()) return content;

        String backend = config.getString(SpotifyPlusConfig.KEY_TRANSLATION_BACKEND, "google_unofficial");
        if (!"google_unofficial".equals(backend)) return content;

        try {
            Gson gson = new Gson();
            if ("Static".equals(type)) {
                StaticSyncedLyrics lyrics = gson.fromJson(content, StaticSyncedLyrics.class);
                translateStatic(activity, trackId, lyrics, config);
                return gson.toJson(lyrics);
            } else if ("Line".equals(type)) {
                LineSyncedLyrics lyrics = gson.fromJson(content, LineSyncedLyrics.class);
                translateLine(activity, trackId, lyrics, config);
                return gson.toJson(lyrics);
            } else if ("Syllable".equals(type)) {
                SyllableSyncedLyrics lyrics = gson.fromJson(content, SyllableSyncedLyrics.class);
                translateSyllable(activity, trackId, lyrics, config);
                return gson.toJson(lyrics);
            }
        } catch (Throwable ignored) {
        }

        return content;
    }

    public static void clearCache(Context context) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void translateStatic(Activity activity, String trackId, StaticSyncedLyrics lyrics, SpotifyPlusConfig config) throws IOException {
        if (lyrics == null || lyrics.lines == null) return;
        for (TextMetadata line : lyrics.lines) {
            if (line == null || isBlank(line.text) || !isBlank(line.translatedText)) continue;
            line.translatedText = translate(activity, trackId, line.text, config);
        }
    }

    private static void translateLine(Activity activity, String trackId, LineSyncedLyrics lyrics, SpotifyPlusConfig config) throws IOException {
        if (lyrics == null || lyrics.content == null) return;
        Gson gson = new Gson();
        List<Object> normalized = new ArrayList<>();
        for (Object item : lyrics.content) {
            JsonElement json = gson.toJsonTree(item);
            if (json.isJsonObject() && "Vocal".equalsIgnoreCase(json.getAsJsonObject().has("Type") ? json.getAsJsonObject().get("Type").getAsString() : "")) {
                LineVocal vocal = gson.fromJson(json, LineVocal.class);
                if (vocal != null && isBlank(vocal.translatedText) && !isBlank(vocal.text)) {
                    vocal.translatedText = translate(activity, trackId, vocal.text, config);
                }
                normalized.add(vocal);
            } else {
                normalized.add(item);
            }
        }
        lyrics.content = normalized;
    }

    private static void translateSyllable(Activity activity, String trackId, SyllableSyncedLyrics lyrics, SpotifyPlusConfig config) throws IOException {
        if (lyrics == null || lyrics.content == null) return;
        Gson gson = new Gson();
        List<Object> normalized = new ArrayList<>();
        for (Object item : lyrics.content) {
            JsonElement json = gson.toJsonTree(item);
            if (json.isJsonObject() && "Vocal".equalsIgnoreCase(json.getAsJsonObject().has("Type") ? json.getAsJsonObject().get("Type").getAsString() : "")) {
                SyllableVocalSet set = gson.fromJson(json, SyllableVocalSet.class);
                translateSyllableSet(activity, trackId, set, config);
                normalized.add(set);
            } else {
                normalized.add(item);
            }
        }
        lyrics.content = normalized;
    }

    private static void translateSyllableSet(Activity activity, String trackId, SyllableVocalSet set, SpotifyPlusConfig config) throws IOException {
        if (set == null || set.lead == null || set.lead.syllables == null) return;
        String line = joinSyllables(set.lead.syllables);
        if (isBlank(line)) return;
        String translated = translate(activity, trackId, line, config);
        if (isBlank(translated)) return;

        // MVP: attach translated full line to the final syllable so the renderer can show one passive line.
        for (SyllableMetadata syllable : set.lead.syllables) {
            if (syllable != null) syllable.translatedText = "";
        }
        for (int i = set.lead.syllables.size() - 1; i >= 0; i--) {
            SyllableMetadata syllable = set.lead.syllables.get(i);
            if (syllable != null) {
                syllable.translatedText = translated;
                break;
            }
        }
    }

    private static String joinSyllables(List<SyllableMetadata> syllables) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < syllables.size(); i++) {
            SyllableMetadata syllable = syllables.get(i);
            if (syllable == null) continue;
            if (i > 0 && !syllable.isPartOfWord) text.append(' ');
            text.append(syllable.text == null ? "" : syllable.text);
        }
        return text.toString();
    }

    private static String translate(Activity activity, String trackId, String text, SpotifyPlusConfig config) throws IOException {
        String target = config.getString(SpotifyPlusConfig.KEY_TRANSLATION_TARGET, Locale.getDefault().getLanguage());
        String source = SpotifyPlusConfig.SOURCE_LANGUAGE_MANUAL.equals(config.getString(SpotifyPlusConfig.KEY_SOURCE_LANGUAGE_MODE, SpotifyPlusConfig.SOURCE_LANGUAGE_AUTO))
                ? config.getString(SpotifyPlusConfig.KEY_SOURCE_LANGUAGE, "auto")
                : "auto";
        if ("other".equals(source)) source = "auto";

        SharedPreferences cache = activity.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        String cacheKey = sha256(trackId + "|google_unofficial|" + source + "|" + target + "|" + text);
        String cached = cache.getString(cacheKey, null);
        if (cached != null) return cached;

        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + Uri.encode(source)
                + "&tl=" + Uri.encode(target)
                + "&dt=t&q=" + Uri.encode(text);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            String body = response.body().string();
            String translated = parseGoogleResponse(body);
            if (!isBlank(translated)) {
                cache.edit().putString(cacheKey, translated).apply();
            }
            return translated;
        }
    }

    private static String parseGoogleResponse(String body) {
        JsonArray root = JsonParser.parseString(body).getAsJsonArray();
        JsonArray sentences = root.get(0).getAsJsonArray();
        StringBuilder out = new StringBuilder();
        for (JsonElement sentenceElement : sentences) {
            JsonArray sentence = sentenceElement.getAsJsonArray();
            if (sentence.size() > 0 && !sentence.get(0).isJsonNull()) {
                out.append(sentence.get(0).getAsString());
            }
        }
        return out.toString().trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Throwable ignored) {
            return String.valueOf(value.hashCode());
        }
    }
}
