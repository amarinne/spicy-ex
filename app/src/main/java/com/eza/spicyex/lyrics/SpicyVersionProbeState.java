package com.eza.spicyex.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Session-local, privacy-safe state for the Spicy client-version probe. */
public final class SpicyVersionProbeState {
    private static final AtomicBoolean PROBE_STARTED = new AtomicBoolean(false);
    private static final Pattern VERSION_TEXT = Pattern.compile("\\d+(?:\\.\\d+)+(?:[-+][0-9A-Za-z.-]+)?");

    public static volatile String spicyVersionSent = "";
    public static volatile String spicyLatestVersion = "";
    public static volatile boolean spicyVersionOutdated = false;
    public static volatile String lastVersionProbeStatus = "not_started";

    private SpicyVersionProbeState() {
    }

    public static boolean beginProbe(String sentVersion) {
        if (!PROBE_STARTED.compareAndSet(false, true)) return false;
        spicyVersionSent = safeStatus(sentVersion);
        spicyLatestVersion = "";
        spicyVersionOutdated = false;
        lastVersionProbeStatus = "in_progress";
        return true;
    }

    public static void recordSuccess(String sentVersion, String latestVersion) {
        spicyVersionSent = safeStatus(sentVersion);
        spicyLatestVersion = safeStatus(latestVersion);
        spicyVersionOutdated = isNewerVersion(spicyLatestVersion, spicyVersionSent);
        lastVersionProbeStatus = spicyVersionOutdated ? "outdated" : "ok";
    }

    public static void recordFailure(String status) {
        spicyVersionOutdated = false;
        lastVersionProbeStatus = safeStatus(status);
    }

    static String buildExtVersionQueryBody(String version) {
        return "{\"queries\":[{\"operation\":\"ext_version\"}],\"client\":{\"version\":\"" + escapeJson(version) + "\"}}";
    }

    static String parseLatestVersion(String raw) {
        if (isBlank(raw)) return "";
        try {
            return findVersion(JsonParser.parseString(raw));
        } catch (Throwable ignored) {
            return "";
        }
    }

    static boolean isNewerVersion(String latestVersion, String sentVersion) {
        int[] latest = versionParts(latestVersion);
        int[] sent = versionParts(sentVersion);
        if (latest.length == 0 || sent.length == 0) return false;
        int max = Math.max(latest.length, sent.length);
        for (int i = 0; i < max; i++) {
            int a = i < latest.length ? latest[i] : 0;
            int b = i < sent.length ? sent[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static String findVersion(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            String value = "";
            try {
                value = element.getAsString();
            } catch (Throwable ignored) {
                return "";
            }
            return VERSION_TEXT.matcher(value).matches() ? value : "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String direct = firstVersionField(object);
            if (!isBlank(direct)) return direct;

            JsonObject result = Json.optObject(object, "result", "Result");
            JsonElement data = result == null ? null : Json.optElement(result, "data", "Data");
            String fromData = findVersion(data);
            if (!isBlank(fromData)) return fromData;

            for (String key : object.keySet()) {
                String found = findVersion(object.get(key));
                if (!isBlank(found)) return found;
            }
            return "";
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                String found = findVersion(child);
                if (!isBlank(found)) return found;
            }
        }
        return "";
    }

    private static String firstVersionField(JsonObject object) {
        String[] keys = {"version", "Version", "latestVersion", "LatestVersion", "latest", "Latest", "ext_version", "extVersion"};
        for (String key : keys) {
            String value = Json.optString(object, key);
            if (VERSION_TEXT.matcher(value).matches()) return value;
        }
        return "";
    }

    private static int[] versionParts(String version) {
        if (isBlank(version)) return new int[0];
        String numeric = version.trim().split("[-+]", 2)[0];
        String[] pieces = numeric.split("\\.");
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                parts[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException ignored) {
                return new int[0];
            }
        }
        return parts;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safeStatus(String value) {
        if (value == null) return "";
        return value.replaceAll("[^0-9A-Za-z._:+-]", "_");
    }
}
