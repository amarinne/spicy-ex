package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import java.io.IOException;

import de.robv.android.xposed.XposedBridge;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/** Fetch/fallback coordinator for native Spicy lyrics. */
public final class LyricsRepository {
    private static final String TAG = "[SpotifyPlusSpicyRepository]";
    private static final String SPICY_QUERY_URL = "https://api.spicylyrics.org/query";
    // Must track the current Spicy Lyrics client version — api.spicylyrics.org rejects outdated
    // clients with a "please update spicy lyrics" payload (gated on client.version / SpicyLyrics-Version).
    // Request schema (verified against Spikerko/spicy-lyrics 6.1.1 src/utils/API/Query.ts) is unchanged.
    private static final String SPICY_VERSION = "6.1.1";
    private static final String SPICY_LEGACY_NO_TOKEN_VERSION = "6.1.1";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int NATIVE_LYRICS_RETRY_LIMIT = 4;
    private static final long NATIVE_LYRICS_RETRY_DELAY_MS = 125;

    // Tracks confirmed to have no lyrics from ANY source this session — shared across callers (the
    // in-player card and the fullscreen screen both fetch through here), so a no-lyric song isn't
    // re-queried (and re-billed against the Spicy quota) when the other surface opens. In-memory:
    // resets on process restart so a track that later gains lyrics is re-checked next launch.
    private static final java.util.Set<String> NO_LYRICS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final OkHttpClient http;
    private final Parser parser;
    private final NativeLyricsProvider nativeLyricsProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LyricsRepository(OkHttpClient http, Parser parser, NativeLyricsProvider nativeLyricsProvider) {
        this.http = http;
        this.parser = parser;
        this.nativeLyricsProvider = nativeLyricsProvider;
    }

    public void fetchLyrics(
            Activity activity,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            String accessToken,
            ResultCallback callback
    ) {
        String uri = track == null ? "" : safe(track.uri);
        String trackId = trackIdFromUri(uri);
        if (trackId.isEmpty()) {
            if (uri.startsWith("spotify:local:")) {
                XposedBridge.log(TAG + " skipping fetch: local file uri=" + safe(uri));
                callback.onError("Lyrics unavailable for local files");
            } else if (uri.startsWith("spotify:episode:")) {
                XposedBridge.log(TAG + " skipping fetch: episode uri=" + safe(uri));
                callback.onError("Lyrics unavailable for podcasts/episodes");
            } else {
                XposedBridge.log(TAG + " skipping fetch: unsupported uri=" + safe(uri));
                callback.onError("Lyrics unavailable for this media");
            }
            return;
        }
        if (NO_LYRICS.contains(trackId)) {
            XposedBridge.log(TAG + " skip fetch: no lyrics from any source this session, id=" + trackId);
            callback.onError("Lyrics unavailable (cached no-result)");
            return;
        }
        final String negId = trackId;
        ResultCallback gated = new ResultCallback() {
            @Override
            public void onSuccess(LyricsDocument document) {
                NO_LYRICS.remove(negId);
                callback.onSuccess(document);
            }

            @Override
            public void onError(String error) {
                // Remember genuine "no lyrics anywhere" (LRCLIB returned no match) so neither surface
                // re-queries it. NOT transient network/server failures (those should retry):
                //   not-found  -> "LRCLIB empty", "no LRCLIB result", "LRCLIB HTTP 404"
                //   transient  -> "LRCLIB failed: <io>", "LRCLIB HTTP 5xx"
                if (LyricsFetchErrors.isDurableNoLyrics(error)) {
                    NO_LYRICS.add(negId);
                    XposedBridge.log(TAG + " cached no-lyrics for id=" + negId + " (" + error + ")");
                }
                callback.onError(error);
            }
        };
        fetchSpicyLyricsFallback(activity, track, generation, sendToken, accessToken, gated);
    }

    private void fetchSpicyLyricsFallback(
            Activity activity,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            String accessToken,
            ResultCallback callback
    ) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) {
            callback.onError("Missing track id");
            return;
        }

        final String cached = LyricsResponseCache.get(activity, trackId);
        final boolean[] deliveredCached = {false};
        final boolean[] deliveredCachedSynced = {false};
        if (!isBlank(cached)) {
            try {
                LyricsDocument doc = parser.parseSpicyLyrics(activity, track, cached, true);
                doc.generation = generation;
                if (!doc.lines.isEmpty()) {
                    deliveredCached[0] = true;
                    deliveredCachedSynced[0] = isSyncedType(doc.type);
                    callback.onSuccess(doc);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " cached parse failed: " + t);
            }
        }

        boolean hasToken = sendToken && !isBlank(accessToken);
        String requestVersion = hasToken ? SPICY_VERSION : SPICY_LEGACY_NO_TOKEN_VERSION;
        String bodyJson = "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"" + escapeJson(trackId) + "\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"" + requestVersion + "\"}}";
        RequestBody body = RequestBody.create(bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), JSON);
        Request request = new Request.Builder()
                .url(SPICY_QUERY_URL)
                .post(body)
                .header("SpicyLyrics-WebAuth", "Bearer " + (hasToken ? accessToken : "0"))
                .header("SpicyLyrics-Version", requestVersion)
                .header("Origin", "https://xpui.app.spotify.com")
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (deliveredCachedSynced[0]) return;
                fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy network failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (deliveredCachedSynced[0]) return;
                        fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy API HTTP " + response.code());
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc;
                    try {
                        doc = parser.parseSpicyLyrics(activity, track, raw, false);
                    } catch (Throwable parseErr) {
                        if (deliveredCachedSynced[0]) return;
                        XposedBridge.log(TAG + " parse failed: " + parseErr);
                        fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy parse failed: " + parseErr.getMessage());
                        return;
                    }
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        if (deliveredCachedSynced[0]) return;
                        fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy lyrics empty");
                        return;
                    }
                    boolean sameAsCache = deliveredCached[0] && raw.equals(cached);
                    if (!sameAsCache) LyricsResponseCache.put(activity, trackId, raw);
                    if (isSyncedType(doc.type)) {
                        if (sameAsCache) return;
                        XposedBridge.log(TAG + " using Spicy synced lyrics type=" + doc.type + " provider=" + doc.provider + " lines=" + doc.lines.size());
                        callback.onSuccess(doc);
                        return;
                    }
                    if (deliveredCachedSynced[0]) return;
                    XposedBridge.log(TAG + " Spicy returned static type=" + doc.type + "; probing native synced upgrade");
                    fetchNativeThenLrclibWithStatic(activity, track, generation, callback, 0, doc, sameAsCache, "Spicy static");
                } catch (Throwable t) {
                    if (deliveredCachedSynced[0]) return;
                    XposedBridge.log(TAG + " response handling failed: " + t);
                    fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy response failed: " + t.getMessage());
                }
            }
        });
    }

    private void fetchNativeThenLrclib(Activity activity, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason) {
        fetchNativeThenLrclibWithStatic(activity, track, generation, callback, nativeRetryCount, null, false, reason);
    }

    private void fetchNativeThenLrclibWithStatic(Activity activity, SpotifyTrack track, int generation,
                                                 ResultCallback callback, int nativeRetryCount,
                                                 LyricsDocument spicyStatic, boolean staticAlreadyShown, String reason) {
        LyricsDocument nativeDoc = nativeLyricsProvider.getNativeLyricsDocument(track);
        if (nativeDoc != null && !nativeDoc.lines.isEmpty()) {
            nativeDoc.generation = generation;
            if (isSyncedType(nativeDoc.type)) {
                XposedBridge.log(TAG + " using native synced lyrics (" + safe(reason) + ") type=" + nativeDoc.type
                        + " provider=" + nativeDoc.provider + " lines=" + nativeDoc.lines.size());
                callback.onSuccess(nativeDoc);
                return;
            }
            if (spicyStatic != null) {
                if (staticAlreadyShown) return;
                XposedBridge.log(TAG + " native static available but keeping Spicy static (equal tier, prefer Spicy)");
                callback.onSuccess(spicyStatic);
                return;
            }
            XposedBridge.log(TAG + " using native static lyrics (" + safe(reason) + ") lines=" + nativeDoc.lines.size());
            callback.onSuccess(nativeDoc);
            return;
        }

        if (nativeRetryCount < NATIVE_LYRICS_RETRY_LIMIT) {
            int nextRetry = nativeRetryCount + 1;
            XposedBridge.log(TAG + " waiting for native lyrics (" + safe(reason) + ") retry=" + nextRetry);
            mainHandler.postDelayed(
                    () -> fetchNativeThenLrclibWithStatic(activity, track, generation, callback, nextRetry, spicyStatic, staticAlreadyShown, reason),
                    NATIVE_LYRICS_RETRY_DELAY_MS);
            return;
        }

        if (spicyStatic != null) {
            if (staticAlreadyShown) return;
            XposedBridge.log(TAG + " native absent; delivering Spicy static lines=" + spicyStatic.lines.size());
            callback.onSuccess(spicyStatic);
            return;
        }
        XposedBridge.log(TAG + " native lyrics miss (" + safe(reason) + "); falling back to LRCLIB");
        fetchLrclib(activity, track, generation, callback, reason);
    }

    private void fetchLrclib(Activity activity, SpotifyTrack track, int generation, ResultCallback callback, String reason) {
        String url = "https://lrclib.net/api/search?track_name="
                + Uri.encode(safe(track.title))
                + "&artist_name=" + Uri.encode(safe(track.artist))
                + "&album_name=" + Uri.encode(safe(track.album));
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "SpotifyPlus MobileLyrics/1.1")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(reason + "; LRCLIB failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError(reason + "; LRCLIB HTTP " + response.code());
                        return;
                    }
                    LyricsDocument doc = parser.parseLrclibLyrics(activity, track, response.body().string());
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        callback.onError(reason + "; LRCLIB empty");
                        return;
                    }
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    callback.onError(reason + "; LRCLIB parse failed: " + t.getMessage());
                    XposedBridge.log(TAG + " LRCLIB parse failed: " + t);
                }
            }
        });
    }

    private static boolean isSyncedType(String type) {
        return "Line".equalsIgnoreCase(type) || "Syllable".equalsIgnoreCase(type);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public interface ResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

    public interface Parser {
        LyricsDocument parseSpicyLyrics(Activity activity, SpotifyTrack track, String raw, boolean fromCache);
        LyricsDocument parseLrclibLyrics(Activity activity, SpotifyTrack track, String body);
    }

    public interface NativeLyricsProvider {
        LyricsDocument getNativeLyricsDocument(SpotifyTrack track);
    }
}
