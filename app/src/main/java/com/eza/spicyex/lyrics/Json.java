package com.eza.spicyex.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * Lenient Gson accessors used across the lyric-source parsers: try several key spellings, treat
 * JSON null / wrong-type / parse failure as "absent" and fall back. Upstream payloads (Spicy,
 * Spotify color-lyrics, native DB, LRCLIB) drift in casing and shape, so every read goes through
 * these instead of {@code object.get(...)}.
 */
public final class Json {

    private Json() {
    }

    /** First present, non-null value among {@code keys}, else null. */
    public static JsonElement optElement(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) return object.get(key);
        }
        return null;
    }

    public static JsonArray optArray(JsonObject object, String... keys) {
        JsonElement element = optElement(object, keys);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    public static JsonObject optObject(JsonObject object, String... keys) {
        JsonElement element = optElement(object, keys);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static String optString(JsonObject object, String... keys) {
        JsonElement element = optElement(object, keys);
        if (element == null) return "";
        try {
            return element.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static double optDouble(JsonObject object, double fallback, String... keys) {
        JsonElement element = optElement(object, keys);
        if (element == null) return fallback;
        try {
            return element.getAsDouble();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /**
     * First present, non-null, numeric value among {@code keys} as a {@link Long}, or null when no
     * key is present or the value is not numeric.
     *
     * <p>Use this instead of {@link #optDouble} for timing fields. {@code optDouble} cannot tell
     * "the payload omitted this field" from "the time is 0", so a caller given a fallback of 0
     * silently reads a missing timestamp as midnight and pins the line to the start of the song.
     */
    public static Long optLongOrNull(JsonObject object, String... keys) {
        JsonElement element = optElement(object, keys);
        if (element == null) return null;
        try {
            return element.getAsLong();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean optBoolean(JsonObject object, boolean fallback, String... keys) {
        JsonElement element = optElement(object, keys);
        if (element == null) return fallback;
        try {
            return element.getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** Recursively search an element tree for the first non-blank value at any of {@code keys}. */
    public static String findFirstString(JsonElement element, String... keys) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String direct = optString(object, keys);
            if (!isBlank(direct)) return direct;
            for (String key : object.keySet()) {
                String found = findFirstString(object.get(key), keys);
                if (!isBlank(found)) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String found = findFirstString(child, keys);
                if (!isBlank(found)) return found;
            }
        }
        return "";
    }

}
