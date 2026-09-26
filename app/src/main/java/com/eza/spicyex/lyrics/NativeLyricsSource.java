package com.eza.spicyex.lyrics;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.SyllableCanonicalizer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.eza.spicyex.xposed.XpLog;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;
import com.eza.spicyex.Diagnostics;

/** Spotify-native lyrics source backed by captured models and Spotify's local lyrics_db. */
public final class NativeLyricsSource implements LyricsRepository.NativeLyricsProvider {
    private static final String TAG = "[SpotifyPlusNativeLyricsSource]";
    private static final boolean DEBUG_LOGGING = false;
    private static final int CACHE_LIMIT = 24;
    private static final long DB_MISS_CACHE_MS = 2000L;

    private final Object lock = new Object();
    private final LinkedHashMap<String, LyricsDocument> byTrack = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> dbMisses = new LinkedHashMap<>();
    private final java.util.Set<NativeListener> listeners = new java.util.HashSet<>();
    private final ContextProvider contextProvider;
    private final LyricsParser.Finalizer finalizer;

    public NativeLyricsSource(ContextProvider contextProvider, LyricsParser.Finalizer finalizer) {
        this.contextProvider = contextProvider;
        this.finalizer = finalizer;
    }

    /** Fired whenever a native capture lands, so the session need not poll for it. */
    public interface NativeListener {
        void onNativeCaptured(String trackId, LyricsDocument document);
    }

    public void addNativeListener(NativeListener listener) {
        if (listener == null) return;
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public void removeNativeListener(NativeListener listener) {
        if (listener == null) return;
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    public void captureCandidate(SpotifyTrack track, Object candidate, Object[] ctorArgs, String sourceTag) {
        // Bare lists omit the owning message's language and provider metadata.
        if (candidate instanceof java.util.Collection) return;
        dbg("captureCandidate", "source=" + safe(sourceTag) + " class=" + (candidate == null ? "null" : candidate.getClass().getName()) + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
        try {
            LyricsDocument doc = buildNativeLyricsDocument(track, candidate, ctorArgs, sourceTag);
            if (doc == null || doc.lines.isEmpty()) {
                noteUnparsed(candidate, sourceTag);
                return;
            }
            store(doc);
        } catch (Throwable t) {
            Diagnostics.warn(TAG, "native lyrics capture failed source=" + sourceTag, t);
        }
    }

    /**
     * Compatibility fallback for clients that expose a JSON color-lyrics HTTP response.
     * Current Spotify builds are captured through their parsed protobuf messages instead.
     */
    public void captureColorLyricsResponse(String trackId, String json) {
        try {
            if (isBlank(json)) return;
            if (isBlank(trackId)) return;
            String array = extractLinesArray(json);
            if (array == null) return;
            JsonArray arr = JsonParser.parseString(array).getAsJsonArray();
            if (arr == null || arr.size() == 0) return;
            LyricsDocument doc = new LyricsDocument();
            doc.trackId = trackId;
            doc.fetchSource = "spotify_native_color_lyrics";
            doc.provider = firstNonBlank(nativeProviderLabel(colorLyricsProvider(json)),
                    "Spotify (through Musixmatch)");
            doc.language = colorLyricsLanguage(json);
            parseJsonLineElements(arr, doc);
            if (doc.lines.isEmpty()) return;
            finalizeParsedDocument(doc);
            store(doc);
        } catch (Throwable t) {
            Diagnostics.warn(TAG, "captureColorLyricsResponse", t);
        }
    }

    private static String colorLyricsProvider(String json) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"(?:displayName|name)\"\\s*:\\s*\"([^\"]{1,40})\"")
                    .matcher(json);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String colorLyricsLanguage(String json) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"(?:isoLanguageCode|languageCode|lang)\"\\s*:\\s*\"([A-Za-z-]{2,8})\"")
                    .matcher(json);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static final java.util.Map<String, Integer> UNPARSED_COUNTS = new java.util.HashMap<>();

    /** Throttled parse-miss trace: a hooked candidate that yields no document. */
    private static void noteUnparsed(Object candidate, String sourceTag) {
        try {
            String key = safe(sourceTag) + "|" + (candidate == null ? "null"
                    : candidate.getClass().getName());
            synchronized (UNPARSED_COUNTS) {
                int seen = UNPARSED_COUNTS.containsKey(key) ? UNPARSED_COUNTS.get(key) : 0;
                if (seen >= 3) return;
                UNPARSED_COUNTS.put(key, seen + 1);
            }
            XpLog.log(TAG + " native candidate unparsed source=" + safe(sourceTag) + " class=" + key);
        } catch (Throwable ignored) {
        }
    }

    private static int count(String text, char target) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) n++;
        }
        return n;
    }

    /** Digit-run count: {@code digitsOnly=true} counts runs, false counts their total length. */
    private static int countRuns(String text, boolean digitsOnly) {
        int runs = 0;
        int total = 0;
        boolean inRun = false;
        for (int i = 0; i < text.length(); i++) {
            boolean isDigit = Character.isDigit(text.charAt(i));
            if (isDigit && !inRun) {
                runs++;
                inRun = true;
            } else if (isDigit) {
                total++;
            } else {
                inRun = false;
            }
        }
        return digitsOnly ? runs : total;
    }

    private static final java.util.regex.Pattern JSON_KEY_FINDER =
            java.util.regex.Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]{0,24})\"\\s*:");

    @Override
    public LyricsDocument getNativeLyricsDocument(SpotifyTrack track) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        dbg("getNativeLyricsDocument", "trackId=" + trackId);
        if (trackId.isEmpty()) return null;
        synchronized (lock) {
            LyricsDocument doc = byTrack.get(trackId);
            if (doc != null) return LyricsDocument.copyOf(doc);
            Long missedAt = dbMisses.get(trackId);
            if (missedAt != null && SystemClock.elapsedRealtime() - missedAt < DB_MISS_CACHE_MS) return null;
            dbMisses.remove(trackId);
        }
        LyricsDocument fromDb = readNativeLyricsFromDb(track);
        if (fromDb != null && !fromDb.lines.isEmpty()) {
            store(fromDb);
            return fromDb;
        }
        synchronized (lock) {
            dbMisses.put(trackId, SystemClock.elapsedRealtime());
            while (dbMisses.size() > CACHE_LIMIT) dbMisses.remove(dbMisses.keySet().iterator().next());
        }
        return null;
    }

    private void store(LyricsDocument doc) {
        dbg("store", "doc=" + (doc == null ? "null" : doc.trackId + "/" + doc.type + "/" + doc.lines.size()));
        if (doc == null || doc.lines.isEmpty()) return;
        String trackId = safe(doc.trackId).trim();
        if (trackId.isEmpty()) return;
        synchronized (lock) {
            dbMisses.remove(trackId);
            LyricsDocument existing = byTrack.get(trackId);
            if (existing != null && nativeLyricsScore(existing) > nativeLyricsScore(doc)) return;
            byTrack.put(trackId, LyricsDocument.copyOf(doc));
            while (byTrack.size() > CACHE_LIMIT) {
                String eldest = byTrack.keySet().iterator().next();
                byTrack.remove(eldest);
            }
        }
        XpLog.log(TAG + " captured native lyrics track=" + trackId
                + " type=" + doc.type
                + " provider=" + doc.provider
                + " lines=" + doc.lines.size()
                + " source=" + doc.fetchSource);
        notifyNativeListeners(trackId, doc);
    }

    private void notifyNativeListeners(String trackId, LyricsDocument doc) {
        java.util.Set<NativeListener> snapshot;
        synchronized (lock) {
            if (listeners.isEmpty()) return;
            snapshot = new java.util.HashSet<>(listeners);
        }
        LyricsDocument copy = LyricsDocument.copyOf(doc);
        for (NativeListener listener : snapshot) {
            try {
                listener.onNativeCaptured(trackId, LyricsDocument.copyOf(copy));
            } catch (Throwable t) {
                Diagnostics.warn(TAG, "native listener failed", t);
            }
        }
    }

    private LyricsDocument readNativeLyricsFromDb(SpotifyTrack track) {
        if (track == null) return null;
        String uri = safe(track.uri);
        String trackId = trackIdFromUri(uri);
        if (trackId.isEmpty()) return null;
        Context ctx = contextProvider == null ? null : contextProvider.context();
        if (ctx == null) return null;
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            java.io.File dbFile = ctx.getDatabasePath("lyrics_db");
            if (dbFile == null || !dbFile.exists()) return null;
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            String[] keys = uri.startsWith("spotify:track:")
                    ? new String[]{uri, trackId}
                    : new String[]{"spotify:track:" + trackId, trackId};
            for (String key : keys) {
                cursor = db.rawQuery(
                        "SELECT lines, syncStatus, language, provider FROM lyrics_entities WHERE track_id = ? LIMIT 1",
                        new String[]{key});
                if (cursor != null && cursor.moveToFirst()) {
                    LyricsDocument doc = parseNativeDbLyrics(trackId,
                            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), track);
                    cursor.close();
                    cursor = null;
                    if (doc != null && !doc.lines.isEmpty()) {
                        XpLog.log(TAG + " native DB read hit track=" + trackId + " type=" + doc.type + " lines=" + doc.lines.size());
                        return doc;
                    }
                }
                if (cursor != null) { cursor.close(); cursor = null; }
            }
            XpLog.log(TAG + " native DB read miss track=" + trackId);
            return null;
        } catch (Throwable t) {
            Diagnostics.warn(TAG, "native DB read failed", t);
            return null;
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private LyricsDocument parseNativeDbLyrics(String trackId, String linesJson, String syncStatus,
                                               String language, String providerJson, SpotifyTrack track) {
        if (isBlank(linesJson)) return null;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(linesJson).getAsJsonArray();
        } catch (Throwable t) {
            Diagnostics.warn(TAG, "parseNativeDbLyrics", t);
            return null;
        }
        String ss = syncStatus == null ? "" : syncStatus.toUpperCase(Locale.ROOT);
        boolean synced = ss.contains("LINE") || ss.contains("SYLLABLE") || ss.contains("WORD");
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackId;
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "spotify_native_db";
        doc.type = synced ? "Line" : "Static";
        doc.language = isBlank(language) ? "unknown" : language;
        String providerName = "";
        try {
            if (!isBlank(providerJson)) {
                JsonObject pj = JsonParser.parseString(providerJson).getAsJsonObject();
                providerName = Json.optString(pj, "displayName", "name");
            }
        } catch (Throwable ignored) {}
        doc.provider = firstNonBlank(nativeProviderLabel(isBlank(providerName) ? "musixmatch" : providerName),
                "Spotify (through Musixmatch)");
        long staticCursor = 0;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String words = Json.optString(o, "words", "Words", "text", "Text");
            long startMs = (long) Json.optDouble(o, 0d, "startTimeInMs", "startTimeMs", "startTime", "StartTime");
            boolean blank = isBlank(words) || words.matches("^[♪♫♬♩\\s]*$");
            LyricsLine line = new LyricsLine();
            if (blank) {
                if (!synced) continue;
                line.interlude = true;
                line.startMs = Math.max(0, startMs);
                doc.lines.add(line);
                continue;
            }
            line.text = words;
            if (synced) {
                line.startMs = Math.max(0, startMs);
                line.endMs = 0;
            } else {
                line.startMs = staticCursor;
                line.endMs = staticCursor + 3500;
                staticCursor += 3500;
            }
            doc.lines.add(line);
        }
        if (doc.lines.isEmpty()) return null;
        finalizeParsedDocument(doc);
        return doc;
    }

    /**
     * Shape-based whole-track parse for obfuscated Spotify lyrics entities (e.g. the
     * {@code p.b2l} DAO model on current builds): one String field carries the track URI,
     * another carries the lines JSON array, recognizable by content rather than by field
     * or class name, so renames do not break capture. Returns null when no field holds a
     * usable lines array.
     */
    private LyricsDocument buildJsonFieldDocument(SpotifyTrack track, Object candidate,
                                                  Object[] ctorArgs, String sourceTag) {
        if (candidate == null) return null;
        String foundTrack = "";
        String linesJson = null;
        java.util.List<String> strings = new java.util.ArrayList<>();
        for (Field field : allFields(candidate.getClass())) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                String value = safe((String) field.get(candidate));
                if (!value.isEmpty()) strings.add(value);
            } catch (Throwable ignored) {
            }
        }
        if (ctorArgs != null) {
            // Traced DAO calls carry the query key (usually the track URI) in their args.
            for (Object arg : ctorArgs) {
                if (arg instanceof String && !((String) arg).isEmpty()) strings.add((String) arg);
            }
        }
        for (String value : strings) {
            if (foundTrack.isEmpty()) {
                String trimmed = value.trim();
                String fromUri = trackIdFromUri(trimmed);
                if (!fromUri.isEmpty()) foundTrack = fromUri;
                else if (looksLikeTrackId(trimmed)) foundTrack = trimmed;
                else {
                    String embedded = findEmbeddedTrackId(trimmed);
                    if (!embedded.isEmpty()) foundTrack = embedded;
                }
            }
            if (linesJson == null && !value.isEmpty() && value.charAt(0) == '[') {
                try {
                    JsonArray arr = JsonParser.parseString(value).getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonObject()) linesJson = value;
                } catch (Throwable ignored) {
                }
            }
            if (linesJson == null && value.length() >= 64) {
                // Room stores this payload encoded; try the plain encodings and keep the one
                // that yields a lines array.
                String decoded = decodeLinesPayload(value);
                if (decoded != null) linesJson = decoded;
            }
            if (!foundTrack.isEmpty() && linesJson != null) break;
        }
        if (linesJson == null) return null;
        String trackId = !foundTrack.isEmpty() ? foundTrack : firstNonBlank(
                nativeTrackIdCandidate(track),
                extractTrackIdFromObjects(new Object[]{candidate}));
        if (trackId.isEmpty()) return null;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(linesJson).getAsJsonArray();
        } catch (Throwable t) {
            Diagnostics.warn(TAG, "buildJsonFieldDocument", t);
            return null;
        }
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackId;
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "spotify_native_model";
        doc.provider = "Spotify (through Musixmatch)";
        doc.language = "unknown";
        parseJsonLineElements(arr, doc);
        if (doc.lines.isEmpty()) return null;
        finalizeParsedDocument(doc);
        return doc;
    }

    /**
     * Decodes an encoded lines payload into a JSON array string, or null. Tries the encodings
     * Spotify's offline lyrics store uses (base64, base64+zlib, base64+gzip) and unwraps a
     * wrapper object by finding its first array-of-objects member. Nothing is logged but the
     * winning encoding.
     */
    private static String decodeLinesPayload(String value) {
        String array = extractLinesArray(value);
        if (array != null) return array;
        for (int flags : new int[]{android.util.Base64.DEFAULT,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP}) {
            byte[] raw;
            try {
                raw = android.util.Base64.decode(value, flags);
            } catch (Throwable ignored) {
                continue;
            }
            if (raw == null || raw.length < 8) continue;
            array = extractLinesArray(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            if (array != null) return array;
            String inflated = inflate(raw);
            if (inflated != null) {
                array = extractLinesArray(inflated);
                if (array != null) return array;
            }
            String gunzipped = gunzip(raw);
            if (gunzipped != null) {
                array = extractLinesArray(gunzipped);
                if (array != null) return array;
            }
        }
        return null;
    }

    /** Returns the JSON text of a lines array, unwrapping one or two object levels. */
    private static String extractLinesArray(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.charAt(0) == '[') return isObjectArray(trimmed) ? trimmed : null;
        if (trimmed.charAt(0) != '{') return null;
        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (!root.isJsonObject()) return null;
            String direct = findObjectArray(root.getAsJsonObject());
            if (direct != null) return direct;
            for (java.util.Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonObject()) continue;
                String nested = findObjectArray(value.getAsJsonObject());
                if (nested != null) return nested;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String findObjectArray(JsonObject object) {
        for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonArray()) continue;
            String text = value.toString();
            if (isObjectArray(text)) return text;
        }
        return null;
    }

    private static boolean isObjectArray(String json) {
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            return arr.size() > 0 && arr.get(0).isJsonObject();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String inflate(byte[] raw) {
        try {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(raw);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length * 3);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) break;
                out.write(buffer, 0, n);
            }
            inflater.end();
            return out.size() == 0 ? null
                    : new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String gunzip(byte[] raw) {
        try (java.util.zip.GZIPInputStream input =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length * 3);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.size() == 0 ? null
                    : new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String findEmbeddedTrackId(String text) {
        if (text == null) return "";
        java.util.regex.Matcher matcher = TRACK_ID_FINDER.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static final java.util.regex.Pattern TRACK_ID_FINDER =
            java.util.regex.Pattern.compile("(?:^|[^A-Za-z0-9])([A-Za-z0-9]{22})(?:[^A-Za-z0-9]|$)");

    /** Shared element loop for Spotify lines JSON: words plus optional start times. */
    private static void parseJsonLineElements(JsonArray arr, LyricsDocument doc) {
        if (arr == null || doc == null) return;
        long staticCursor = 0;
        boolean anyTimed = false;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String words = Json.optString(o, "words", "Words", "text", "Text");
            long startMs = (long) Json.optDouble(o, 0d, "startTimeInMs", "startTimeMs",
                    "startTime", "StartTime");
            if (startMs > 0) anyTimed = true;
            boolean blank = isBlank(words) || words.matches("^[\u266A\u266B\u266C\u2669\\s]*$");
            LyricsLine line = new LyricsLine();
            if (blank) {
                if (startMs <= 0) continue;
                line.interlude = true;
                line.startMs = Math.max(0, startMs);
                doc.lines.add(line);
                continue;
            }
            line.text = words;
            if (startMs > 0) {
                line.startMs = Math.max(0, startMs);
                line.endMs = 0;
            } else {
                line.startMs = staticCursor;
                line.endMs = staticCursor + 3500;
                staticCursor += 3500;
            }
            doc.lines.add(line);
        }
        doc.type = anyTimed ? "Line" : "Static";
        if (!anyTimed) {
            for (int i = doc.lines.size() - 1; i >= 0; i--) {
                if (doc.lines.get(i).interlude) doc.lines.remove(i);
            }
        }
        if (!doc.lines.isEmpty()) {
            int firstVocal = LyricTimeline.firstNonInterludeIndex(doc.lines);
            LyricsLine anchor = firstVocal >= 0 ? doc.lines.get(firstVocal) : doc.lines.get(0);
            doc.startTimeMs = Math.max(0, anchor.startMs);
        }
    }

    private LyricsDocument buildNativeLyricsDocument(SpotifyTrack track, Object candidate, Object[] ctorArgs, String sourceTag) {
        dbg("buildNativeLyricsDocument", "source=" + safe(sourceTag) + " candidate=" + (candidate == null ? "null" : candidate.getClass().getName()));
        LyricsDocument embedded = buildJsonFieldDocument(track, candidate, ctorArgs, sourceTag);
        if (embedded != null && !embedded.lines.isEmpty()) return embedded;
        List<?> rawLines = readNativeLinesContainer(candidate, ctorArgs);
        if (rawLines == null || rawLines.isEmpty()) return null;

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = firstNonBlank(
                nativeTrackIdCandidate(track),
                extractTrackIdFromObjects(ctorArgs),
                extractTrackIdFromObject(candidate)
        );
        if (doc.trackId.isEmpty()) return null;
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "spotify_native_model";
        doc.provider = firstNonBlank(
                nativeProviderLabel(readProviderCandidate(candidate, ctorArgs)),
                "Spotify (through Musixmatch)"
        );
        doc.language = firstNonBlank(readLanguageCandidate(candidate, ctorArgs), "unknown");
        parseNativeLineList(rawLines, doc);
        if (doc.lines.isEmpty()) return null;
        finalizeParsedDocument(doc);
        return doc;
    }

    private void finalizeParsedDocument(LyricsDocument doc) {
        if (finalizer != null) finalizer.finalizeParsedDocument(contextProvider == null ? null : contextProvider.context(), doc);
    }

    private static void parseNativeLineList(List<?> rawLines, LyricsDocument doc) {
        dbg("parseNativeLineList", "rawLines=" + (rawLines == null ? 0 : rawLines.size()) + " track=" + (doc == null ? "null" : doc.trackId));
        long staticCursor = 0;
        boolean anyTimed = false;
        boolean anySyllables = false;
        for (Object raw : rawLines) {
            LyricsLine line = parseNativeLine(raw);
            if (line == null) continue;
            if (line.interlude) {
                doc.lines.add(line);
                continue;
            }
            if (isBlank(line.text)) continue;
            if (!line.syllables.isEmpty()) anySyllables = true;
            if (line.startMs > 0 || line.endMs > 0) anyTimed = true;
            if (line.startMs <= 0 && line.endMs <= 0 && line.syllables.isEmpty()) {
                line.startMs = staticCursor;
                line.endMs = staticCursor + 3500;
                staticCursor += 3500;
            } else {
                staticCursor = Math.max(staticCursor, Math.max(line.endMs, line.startMs));
            }
            doc.lines.add(line);
        }
        doc.type = anySyllables ? "Syllable" : anyTimed ? "Line" : "Static";
        if (!isSyncedType(doc.type)) {
            for (int i = doc.lines.size() - 1; i >= 0; i--) {
                if (doc.lines.get(i).interlude) doc.lines.remove(i);
            }
        }
        if (!doc.lines.isEmpty()) {
            int firstVocal = LyricTimeline.firstNonInterludeIndex(doc.lines);
            LyricsLine anchor = firstVocal >= 0 ? doc.lines.get(firstVocal) : doc.lines.get(0);
            doc.startTimeMs = Math.max(0, anchor.startMs);
        }
    }

    private static LyricsLine parseNativeLine(Object raw) {
        dbg("parseNativeLine", "class=" + (raw == null ? "null" : raw.getClass().getName()));
        if (raw == null) return null;
        LyricsLine line = new LyricsLine();
        line.text = readPreferredText(raw);
        line.startMs = readLongField(raw, "start", "starttime", "time");
        line.endMs = readLongField(raw, "end", "endtime");
        if (isBlank(line.text)) {
            if (line.startMs > 0) {
                line.interlude = true;
                return line;
            }
            return null;
        }
        line.oppositeAligned = readBooleanField(raw, "opposite", "right", "rtl");
        List<?> rawSyllables = readListFieldByElementHint(raw, "Syllable");
        if (rawSyllables != null) {
            parseNativeSyllables(rawSyllables, line);
        }
        if (!line.syllables.isEmpty()) {
            if (line.startMs <= 0) line.startMs = Math.max(0, line.syllables.get(0).startMs);
            if (line.endMs <= 0) line.endMs = Math.max(line.startMs, line.syllables.get(line.syllables.size() - 1).endMs);
        }
        return line;
    }

    private static void parseNativeSyllables(List<?> rawSyllables, LyricsLine line) {
        if (rawSyllables == null || line == null || isBlank(line.text)) return;
        ArrayList<SyllableSegment> parsed = new ArrayList<>();
        int charCursor = 0;
        for (int i = 0; i < rawSyllables.size(); i++) {
            Object rawSyllable = rawSyllables.get(i);
            if (rawSyllable == null) continue;
            SyllableSegment seg = parseNativeSyllable(rawSyllable);
            if (seg == null) continue;
            if (isBlank(seg.text)) {
                int count = readIntField(rawSyllable, "character", "count", "length");
                if (count <= 0) count = readSecondIntField(rawSyllable);
                if (count > 0 && charCursor < line.text.length()) {
                    int end = Math.min(line.text.length(), charCursor + count);
                    seg.text = line.text.substring(charCursor, end);
                    charCursor = end;
                }
            }
            if (isBlank(seg.text)) continue;
            parsed.add(seg);
        }
        for (int i = 0; i < parsed.size(); i++) {
            SyllableSegment seg = parsed.get(i);
            long nextStart = (i + 1 < parsed.size()) ? parsed.get(i + 1).startMs : 0;
            if (seg.endMs <= seg.startMs && nextStart > seg.startMs) seg.endMs = nextStart;
            if (seg.endMs <= seg.startMs) seg.endMs = seg.startMs + 180;
            seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
        }
        CanonicalLine canonical = SyllableCanonicalizer.canonicalize(
                "native-" + line.startMs + "-" + line.endMs, line.text, parsed);
        line.text = SyllableCanonicalizer.displayText(canonical, parsed);
        line.syllables.addAll(parsed);
    }

    private static SyllableSegment parseNativeSyllable(Object raw) {
        dbg("parseNativeSyllable", "class=" + (raw == null ? "null" : raw.getClass().getName()));
        if (raw == null) return null;
        SyllableSegment seg = new SyllableSegment();
        seg.text = readPreferredText(raw);
        seg.startMs = readLongField(raw, "start", "starttime", "time");
        seg.endMs = readLongField(raw, "end", "endtime");
        seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
        return seg;
    }

    private static int nativeLyricsScore(LyricsDocument doc) {
        if (doc == null) return -1;
        int score = isSyncedType(doc.type) ? 1000 : 0;
        if ("Syllable".equalsIgnoreCase(doc.type)) score += 200;
        else if ("Line".equalsIgnoreCase(doc.type)) score += 100;
        score += doc.lines == null ? 0 : Math.min(doc.lines.size(), 200);
        return score;
    }

    private static List<?> readNativeLinesContainer(Object candidate, Object[] ctorArgs) {
        dbg("readNativeLinesContainer", "candidate=" + (candidate == null ? "null" : candidate.getClass().getName()) + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
        if (candidate instanceof List && !((List<?>) candidate).isEmpty()) return (List<?>) candidate;
        if (ctorArgs != null && ctorArgs.length > 1 && ctorArgs[1] instanceof List) {
            List<?> list = (List<?>) ctorArgs[1];
            if (!list.isEmpty()) return list;
        }
        List<?> fromObject = readListFieldByElementHint(candidate, "Line");
        if (fromObject != null && !fromObject.isEmpty()) return fromObject;
        if (ctorArgs == null) return null;
        for (Object arg : ctorArgs) {
            if (!(arg instanceof List)) continue;
            List<?> list = (List<?>) arg;
            if (list.isEmpty()) continue;
            Object first = list.get(0);
            if (first != null && first.getClass().getName().contains("Line")) return list;
        }
        // Obfuscated builds rename the line class, so a name hint cannot find it: accept any
        // list whose elements are structured objects that look like a lyric line.
        List<?> structural = readStructuralLineList(candidate);
        if (structural != null) return structural;
        if (ctorArgs != null) {
            for (Object arg : ctorArgs) {
                if (arg == candidate) continue;
                List<?> fromArgs = readStructuralLineList(arg);
                if (fromArgs != null) return fromArgs;
            }
        }
        return null;
    }

    /** The largest list field whose elements carry text or timing fields, by shape. */
    private static List<?> readStructuralLineList(Object value) {
        if (value == null) return null;
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (!list.isEmpty() && looksLikeLineObject(list.get(0))) return list;
            return null;
        }
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object raw = field.get(value);
                if (!(raw instanceof List)) continue;
                List<?> list = (List<?>) raw;
                if (list.isEmpty() || !looksLikeLineObject(list.get(0))) continue;
                if (list.size() > 1) return list;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * A line-like object: it exposes at least one String field and at least one numeric or
     * nested-list field, which separates lyric lines from unrelated payloads.
     */
    private static boolean looksLikeLineObject(Object candidate) {
        if (candidate == null) return false;
        if (candidate instanceof String || candidate instanceof Number) return false;
        boolean text = false;
        boolean timing = false;
        for (Field field : allFields(candidate.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type == String.class) text = true;
            else if (type == long.class || type == Long.class || type == int.class
                    || type == Integer.class || type == double.class || type == float.class
                    || List.class.isAssignableFrom(type)) timing = true;
        }
        return text && timing;
    }

    private static String extractTrackIdFromObjects(Object[] values) {
        dbg("extractTrackIdFromObjects", "values=" + (values == null ? 0 : values.length));
        if (values == null) return "";
        for (Object value : values) {
            String found = extractTrackIdFromObject(value);
            if (!isBlank(found)) return found;
        }
        return "";
    }

    private static String extractTrackIdFromObject(Object value) {
        dbg("extractTrackIdFromObject", "class=" + (value == null ? "null" : value.getClass().getName()));
        if (value == null) return "";
        if (value instanceof String) {
            String text = safe((String) value).trim();
            String fromUri = trackIdFromUri(text);
            if (!fromUri.isEmpty()) return fromUri;
            if (looksLikeTrackId(text)) return text;
            return "";
        }
        String direct = readStringFieldByHints(value, "track", "uri", "id");
        String fromUri = trackIdFromUri(direct);
        if (!fromUri.isEmpty()) return fromUri;
        if (looksLikeTrackId(direct)) return direct;
        for (Field field : allFields(value.getClass())) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                String text = safe((String) field.get(value)).trim();
                fromUri = trackIdFromUri(text);
                if (!fromUri.isEmpty()) return fromUri;
                if (looksLikeTrackId(text)) return text;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String readProviderCandidate(Object candidate, Object[] ctorArgs) {
        dbg("readProviderCandidate", "candidate=" + (candidate == null ? "null" : candidate.getClass().getName()) + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
        String direct = firstNonBlank(
                readStringFieldByHints(candidate, "provider", "source", "credit"),
                readStringFieldByHints(namedFieldValue(candidate, "provider", "source", "credit"), "name", "value")
        );
        if (!direct.isEmpty()) return direct;
        if (ctorArgs == null) return "";
        for (Object arg : ctorArgs) {
            if (arg == null) continue;
            String className = arg.getClass().getName();
            if (className.contains("Provider")) {
                String value = readStringFieldByHints(arg, "name", "value", "provider", "source");
                if (!value.isEmpty()) return value;
                return safe(arg.toString());
            }
            if (arg instanceof String) {
                String value = safe((String) arg).trim();
                if (value.equalsIgnoreCase("spotify") || value.equalsIgnoreCase("musixmatch") || value.equalsIgnoreCase("mxm")) return value;
            }
        }
        return "";
    }

    private static String readLanguageCandidate(Object candidate, Object[] ctorArgs) {
        dbg("readLanguageCandidate", "candidate=" + (candidate == null ? "null" : candidate.getClass().getName()) + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
        String direct = firstNonBlank(
                readStringFieldByHints(candidate, "language", "lang", "locale"),
                readStringFieldByHints(namedFieldValue(candidate, "language", "lang", "locale"), "code"));
        if (!direct.isEmpty()) return direct;
        if (ctorArgs == null) return "";
        for (Object arg : ctorArgs) {
            if (!(arg instanceof String)) continue;
            String value = safe((String) arg).trim();
            if (looksLikeLanguageTag(value)) return value;
        }
        return "";
    }

    private static String readPreferredText(Object value) {
        dbg("readPreferredText", "class=" + (value == null ? "null" : value.getClass().getName()));
        if (value == null) return "";
        String direct = readStringFieldByHints(value, "text", "words", "content", "line");
        if (!direct.isEmpty()) return direct;
        for (Field field : allFields(value.getClass())) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                String text = safe((String) field.get(value)).trim();
                if (text.isEmpty()) continue;
                if (trackIdFromUri(text).length() > 0 || looksLikeTrackId(text) || looksLikeLanguageTag(text)) continue;
                return text;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static long readLongField(Object value, String... hints) {
        dbg("readLongField", "class=" + (value == null ? "null" : value.getClass().getName()));
        if (value == null) return 0;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!matchesAnyHint(name, hints)) continue;
            Long read = readLongValue(field, value);
            if (read != null) return read;
        }
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Long read = readLongValue(field, value);
            if (read != null && read >= 0) return read;
        }
        return 0;
    }

    private static boolean readBooleanField(Object value, String... hints) {
        dbg("readBooleanField", "class=" + (value == null ? "null" : value.getClass().getName()));
        if (value == null) return false;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!(field.getType() == boolean.class || field.getType() == Boolean.class)) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!matchesAnyHint(name, hints)) continue;
            try {
                field.setAccessible(true);
                Object raw = field.get(value);
                if (raw instanceof Boolean) return (Boolean) raw;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int readIntField(Object value, String... hints) {
        if (value == null) return 0;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!matchesAnyHint(name, hints)) continue;
            Long read = readLongValue(field, value);
            if (read != null) return read.intValue();
        }
        return 0;
    }

    private static int readSecondIntField(Object value) {
        if (value == null) return 0;
        int seen = 0;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Long read = readLongValue(field, value);
            if (read == null) continue;
            if (seen++ == 1) return read.intValue();
        }
        return 0;
    }

    private static List<?> readListFieldByElementHint(Object value, String elementHint) {
        dbg("readListFieldByElementHint", "class=" + (value == null ? "null" : value.getClass().getName()) + " hint=" + safe(elementHint));
        if (value == null) return null;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object raw = field.get(value);
                if (!(raw instanceof List)) continue;
                List<?> list = (List<?>) raw;
                if (list.isEmpty()) continue;
                Object first = list.get(0);
                if (first == null || safe(first.getClass().getName()).contains(elementHint)) return list;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object namedFieldValue(Object value, String... hints) {
        if (value == null) return null;
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!matchesAnyHint(name, hints)) continue;
            try {
                field.setAccessible(true);
                return field.get(value);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readStringFieldByHints(Object value, String... hints) {
        if (value == null) return "";
        for (Field field : allFields(value.getClass())) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!matchesAnyHint(name, hints)) continue;
            try {
                field.setAccessible(true);
                String text = safe((String) field.get(value)).trim();
                if (!text.isEmpty()) return text;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static List<Field> allFields(Class<?> type) {
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) fields.add(field);
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean matchesAnyHint(String value, String... hints) {
        if (isBlank(value) || hints == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (!isBlank(hint) && lower.contains(hint.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Long readLongValue(Field field, Object owner) {
        try {
            field.setAccessible(true);
            Object raw = field.get(owner);
            if (raw instanceof Long) return (Long) raw;
            if (raw instanceof Integer) return ((Integer) raw).longValue();
            if (raw instanceof Short) return ((Short) raw).longValue();
            if (raw instanceof Double) return Math.round((Double) raw);
            if (raw instanceof Float) return (long) Math.round((Float) raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean looksLikeTrackId(String value) {
        if (isBlank(value)) return false;
        return value.matches("[A-Za-z0-9]{22}");
    }

    private static boolean looksLikeLanguageTag(String value) {
        if (isBlank(value)) return false;
        return value.matches("[A-Za-z]{2,3}([-_][A-Za-z]{2,4})?");
    }

    private static boolean isSyncedType(String type) {
        return "Line".equalsIgnoreCase(type) || "Syllable".equalsIgnoreCase(type);
    }

    private static String nativeTrackIdCandidate(SpotifyTrack track) {
        return trackIdFromUri(track == null ? "" : track.uri);
    }

    private static String nativeProviderLabel(String raw) {
        String value = safe(raw).trim();
        if (value.isEmpty()) return "";
        if (value.equalsIgnoreCase("musixmatch")) return "Spotify (through Musixmatch)";
        if (value.equalsIgnoreCase("spotify")) return "Spotify";
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private static void dbg(String function, String message) {
        if (!DEBUG_LOGGING) return;
        XpLog.log(TAG + " " + function + "() " + safe(message));
    }

    public interface ContextProvider {
        Context context();
    }
}
