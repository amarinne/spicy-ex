package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private static final String SPICY_ORIGIN = "https://xpui.app.spotify.com";
    private static final String SPICY_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Spotify/1.2.63 Chrome/132.0.6834.210 Electron/34.3.1 Safari/537.36";
    // Must track the current Spicy Lyrics client version — api.spicylyrics.org rejects outdated
    // clients with a "please update spicy lyrics" payload (gated on client.version / SpicyLyrics-Version).
    // Request schema (verified against Spikerko/spicy-lyrics 6.1.1 src/utils/API/Query.ts) is unchanged.
    private static final String SPICY_VERSION = "6.1.1";
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
    private final ScheduledExecutorService ioScheduler;

    public LyricsRepository(OkHttpClient http, Parser parser, NativeLyricsProvider nativeLyricsProvider,
                            ScheduledExecutorService ioScheduler) {
        this.http = http;
        this.parser = parser;
        this.nativeLyricsProvider = nativeLyricsProvider;
        this.ioScheduler = ioScheduler;
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

        probeSpicyVersionOnce();

        final boolean hasToken = hasUsableToken(sendToken, accessToken);
        final String cached = LyricsResponseCache.get(activity, trackId);
        final LyricsProviderChain chain = new LyricsProviderChain(generation, cached);
        if (!isBlank(cached)) {
            try {
                LyricsDocument doc = parser.parseSpicyLyrics(activity, track, cached, true);
                LyricsProviderChain.Decision decision = chain.acceptCached(doc);
                if (doc.spicyPoisoned) {
                    XposedBridge.log(TAG + " warning: ignored suspicious cached Spicy response reason="
                            + safe(doc.spicyQualityReason)
                            + " status=" + (doc.spicyQueryStatus == null ? "unknown" : doc.spicyQueryStatus)
                            + " format=" + safe(doc.spicyFormat)
                            + " packed=" + doc.spicyPackedPayload
                            + " type=" + safe(doc.type));
                } else if (decision.action == LyricsProviderChain.Action.DELIVER) {
                    LyricsFetchDiagnosticsState.record("cache", chain.candidatesSeen(), doc, hasToken, false);
                    callback.onSuccess(doc);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " cached parse failed: " + t);
            }
        }

        if (!hasToken) {
            if (chain.deliveredCachedSynced()) return;
            fetchNativeThenLrclib(activity, track, generation, callback, 0,
                    "Spicy token unavailable", chain, false);
            return;
        }
        String requestVersion = SPICY_VERSION;
        Request request = buildSpicyLyricsRequest(trackId, requestVersion, accessToken);

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (chain.deliveredCachedSynced()) return;
                fetchNativeThenLrclib(activity, track, generation, callback, 0,
                        "Spicy network failed: " + e.getMessage(), chain, hasToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (chain.deliveredCachedSynced()) return;
                        fetchNativeThenLrclib(activity, track, generation, callback, 0,
                                "Spicy API HTTP " + response.code(), chain, hasToken);
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc;
                    try {
                        doc = parser.parseSpicyLyrics(activity, track, raw, false);
                    } catch (Throwable parseErr) {
                        if (chain.deliveredCachedSynced()) return;
                        XposedBridge.log(TAG + " parse failed: " + parseErr);
                        fetchNativeThenLrclib(activity, track, generation, callback, 0,
                                "Spicy parse failed: " + parseErr.getMessage(), chain, hasToken);
                        return;
                    }
                    LyricsProviderChain.Decision decision = chain.acceptSpicyNetwork(doc, raw);
                    if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                    if (doc.lines.isEmpty()) {
                        fetchNativeThenLrclib(activity, track, generation, callback, 0,
                                "Spicy lyrics empty", chain, hasToken);
                        return;
                    }
                    if (doc.spicyPoisoned) {
                        XposedBridge.log(TAG + " warning: rejected suspicious Spicy response reason="
                                + safe(doc.spicyQualityReason)
                                + " status=" + (doc.spicyQueryStatus == null ? "unknown" : doc.spicyQueryStatus)
                                + " format=" + safe(doc.spicyFormat)
                                + " packed=" + doc.spicyPackedPayload
                                + " type=" + safe(doc.type));
                        fetchNativeThenLrclib(activity, track, generation, callback, 0,
                                "Spicy response suspicious: " + safe(doc.spicyQualityReason), chain, hasToken);
                        return;
                    }
                    if (decision.action == LyricsProviderChain.Action.DELIVER) {
                        boolean cacheWrite = false;
                        if (decision.cacheDeliveredRaw) {
                            LyricsResponseCache.put(activity, trackId, decision.rawToCache);
                            cacheWrite = true;
                        }
                        XposedBridge.log(TAG + " using Spicy synced lyrics type=" + doc.type + " provider=" + doc.provider + " lines=" + doc.lines.size());
                        LyricsFetchDiagnosticsState.record("spicy", chain.candidatesSeen(), doc, hasToken, cacheWrite);
                        callback.onSuccess(doc);
                        return;
                    }
                    XposedBridge.log(TAG + " Spicy returned static type=" + doc.type + "; probing native synced upgrade");
                    fetchNativeThenLrclib(activity, track, generation, callback, 0, "Spicy static", chain, hasToken);
                } catch (Throwable t) {
                    if (chain.deliveredCachedSynced()) return;
                    XposedBridge.log(TAG + " response handling failed: " + t);
                    fetchNativeThenLrclib(activity, track, generation, callback, 0,
                            "Spicy response failed: " + t.getMessage(), chain, hasToken);
                }
            }
        });
    }

    private void probeSpicyVersionOnce() {
        final String requestVersion = SPICY_VERSION;
        if (!SpicyVersionProbeState.beginProbe(requestVersion)) return;

        RequestBody body = RequestBody.create(
                SpicyVersionProbeState.buildExtVersionQueryBody(requestVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                JSON);
        Request request = new Request.Builder()
                .url(SPICY_QUERY_URL)
                .post(body)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("Origin", SPICY_ORIGIN)
                .header("Referer", SPICY_ORIGIN + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
                .header("SpicyLyrics-Version", requestVersion)
                .header("User-Agent", SPICY_USER_AGENT)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String type = e == null ? "unknown" : e.getClass().getSimpleName();
                SpicyVersionProbeState.recordFailure("network_failed:" + type);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        SpicyVersionProbeState.recordFailure("http_" + response.code());
                        return;
                    }
                    String latest = SpicyVersionProbeState.parseLatestVersion(response.body().string());
                    if (isBlank(latest)) {
                        SpicyVersionProbeState.recordFailure("parse_failed");
                        return;
                    }
                    SpicyVersionProbeState.recordSuccess(requestVersion, latest);
                    if (SpicyVersionProbeState.spicyVersionOutdated) {
                        XposedBridge.log(TAG + " warning: Spicy client version outdated sent="
                                + SpicyVersionProbeState.spicyVersionSent
                                + " latest=" + SpicyVersionProbeState.spicyLatestVersion);
                    }
                } catch (Throwable t) {
                    SpicyVersionProbeState.recordFailure("response_failed:" + t.getClass().getSimpleName());
                }
            }
        });
    }

    private void fetchNativeThenLrclib(Activity activity, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason) {
        fetchNativeThenLrclib(activity, track, generation, callback, nativeRetryCount, reason,
                new LyricsProviderChain(generation, null), false);
    }

    private void fetchNativeThenLrclib(Activity activity, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason,
                                       LyricsProviderChain chain, boolean tokenPresent) {
        fetchNativeThenLrclibWithStatic(activity, track, generation, callback, nativeRetryCount,
                reason, chain, tokenPresent);
    }

    private void fetchNativeThenLrclibWithStatic(Activity activity, SpotifyTrack track, int generation,
                                                 ResultCallback callback, int nativeRetryCount, String reason,
                                                 LyricsProviderChain chain, boolean tokenPresent) {
        LyricsDocument nativeDoc = nativeLyricsProvider.getNativeLyricsDocument(track);
        if (nativeDoc != null && !nativeDoc.lines.isEmpty()) {
            LyricsProviderChain.Decision decision = chain.acceptNative(nativeDoc);
            if (chain.hasPendingStatic()) {
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                if (decision.document == nativeDoc) {
                    XposedBridge.log(TAG + " using native lyrics (" + safe(reason) + ") type=" + nativeDoc.type
                            + " provider=" + nativeDoc.provider + " lines=" + nativeDoc.lines.size()
                            + " score=" + LyricQualityRanker.score(nativeDoc));
                    LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
                    callback.onSuccess(nativeDoc);
                } else {
                    LyricsDocument spicyStatic = chain.pendingStatic();
                    boolean cacheWrite = cacheChosenRaw(activity, track, decision.rawToCache);
                    XposedBridge.log(TAG + " keeping Spicy static over native static score="
                            + LyricQualityRanker.score(spicyStatic) + " nativeScore=" + LyricQualityRanker.score(nativeDoc));
                    LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                    callback.onSuccess(spicyStatic);
                }
                return;
            }
            if (LyricsProviderChain.isSyncedType(nativeDoc.type)) {
                XposedBridge.log(TAG + " using native synced lyrics (" + safe(reason) + ") type=" + nativeDoc.type
                        + " provider=" + nativeDoc.provider + " lines=" + nativeDoc.lines.size());
                LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
                callback.onSuccess(nativeDoc);
                return;
            }
            XposedBridge.log(TAG + " using native static lyrics (" + safe(reason) + ") lines=" + nativeDoc.lines.size());
            LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
            callback.onSuccess(nativeDoc);
            return;
        }

        if (nativeRetryCount < NATIVE_LYRICS_RETRY_LIMIT) {
            int nextRetry = nativeRetryCount + 1;
            XposedBridge.log(TAG + " waiting for native lyrics (" + safe(reason) + ") retry=" + nextRetry);
            ioScheduler.schedule(
                    () -> fetchNativeThenLrclibWithStatic(activity, track, generation, callback, nextRetry,
                            reason, chain, tokenPresent),
                    NATIVE_LYRICS_RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS);
            return;
        }

        chain.nativeMissAfterRetries(reason);
        if (chain.hasPendingStatic()) {
            XposedBridge.log(TAG + " native absent; probing LRCLIB against Spicy static lines=" + chain.pendingStatic().lines.size());
            fetchLrclibWithSpicyFallback(activity, track, generation, callback, reason, chain, tokenPresent);
            return;
        }
        XposedBridge.log(TAG + " native lyrics miss (" + safe(reason) + "); falling back to LRCLIB");
        fetchLrclib(activity, track, generation, callback, reason, chain, tokenPresent);
    }

    private void fetchLrclibWithSpicyFallback(Activity activity, SpotifyTrack track, int generation,
                                              ResultCallback callback, String reason, LyricsProviderChain chain,
                                              boolean tokenPresent) {
        fetchLrclib(activity, track, generation, new ResultCallback() {
            @Override
            public void onSuccess(LyricsDocument lrclibDoc) {
                LyricsProviderChain.Decision decision = chain.acceptLrclib(lrclibDoc);
                LyricsDocument spicyStatic = chain.pendingStatic();
                if (decision.document == lrclibDoc) {
                    XposedBridge.log(TAG + " using LRCLIB lyrics over Spicy static type=" + lrclibDoc.type
                            + " lines=" + lrclibDoc.lines.size()
                            + " score=" + LyricQualityRanker.score(lrclibDoc)
                            + " spicyScore=" + LyricQualityRanker.score(spicyStatic));
                    LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), lrclibDoc, tokenPresent, false);
                    callback.onSuccess(lrclibDoc);
                    return;
                }
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                boolean cacheWrite = cacheChosenRaw(activity, track, decision.rawToCache);
                XposedBridge.log(TAG + " native/LRCLIB lower ranked; delivering Spicy static lines="
                        + spicyStatic.lines.size() + " score=" + LyricQualityRanker.score(spicyStatic));
                LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                callback.onSuccess(spicyStatic);
            }

            @Override
            public void onError(String error) {
                LyricsProviderChain.Decision decision = chain.acceptLrclibError(error);
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                LyricsDocument spicyStatic = chain.pendingStatic();
                boolean cacheWrite = cacheChosenRaw(activity, track, decision.rawToCache);
                XposedBridge.log(TAG + " LRCLIB miss; delivering Spicy static lines=" + spicyStatic.lines.size());
                LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                callback.onSuccess(spicyStatic);
            }
        }, reason, chain, tokenPresent);
    }

    private static boolean cacheChosenRaw(Activity activity, SpotifyTrack track, String raw) {
        if (isBlank(raw)) return false;
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) return false;
        LyricsResponseCache.put(activity, trackId, raw);
        return true;
    }

    private void fetchLrclib(Activity activity, SpotifyTrack track, int generation, ResultCallback callback, String reason) {
        fetchLrclib(activity, track, generation, callback, reason, new LyricsProviderChain(generation, null), false);
    }

    private void fetchLrclib(Activity activity, SpotifyTrack track, int generation, ResultCallback callback,
                             String reason, LyricsProviderChain chain, boolean tokenPresent) {
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
                reportLrclibError(chain, callback, reason + "; LRCLIB failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        reportLrclibError(chain, callback, reason + "; LRCLIB HTTP " + response.code());
                        return;
                    }
                    LyricsDocument doc = parser.parseLrclibLyrics(activity, track, response.body().string());
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        reportLrclibError(chain, callback, reason + "; LRCLIB empty");
                        return;
                    }
                    chain.acceptLrclib(doc);
                    LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), doc, tokenPresent, false);
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    reportLrclibError(chain, callback, reason + "; LRCLIB parse failed: " + t.getMessage());
                    XposedBridge.log(TAG + " LRCLIB parse failed: " + t);
                }
            }
        });
    }

    private static void reportLrclibError(LyricsProviderChain chain, ResultCallback callback, String error) {
        if (chain != null && !chain.hasPendingStatic()) {
            chain.acceptLrclibError(error);
        }
        callback.onError(error);
    }

    static String buildSpicyLyricsQueryBody(String trackId, String version) {
        return "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"" + escapeJson(trackId) + "\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"" + escapeJson(version) + "\"}}";
    }

    static boolean hasUsableToken(boolean sendToken, String accessToken) {
        return sendToken && !isBlank(accessToken) && !"0".equals(accessToken);
    }

    static Request buildSpicyLyricsRequest(String trackId, String requestVersion, String accessToken) {
        RequestBody body = RequestBody.create(
                buildSpicyLyricsQueryBody(trackId, requestVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                JSON);
        Request.Builder builder = new Request.Builder()
                .url(SPICY_QUERY_URL)
                .post(body)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("Origin", SPICY_ORIGIN)
                .header("Referer", SPICY_ORIGIN + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
                .header("SpicyLyrics-Version", requestVersion)
                .header("User-Agent", SPICY_USER_AGENT);
        if (hasUsableToken(true, accessToken)) builder.header("SpicyLyrics-WebAuth", "Bearer " + accessToken);
        return builder.build();
    }

    private static String sourceLabel(LyricsDocument doc, String fallback) {
        String source = doc == null ? "" : safe(doc.fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("cache")) return "cache";
        if (source.contains("lrclib")) return "lrclib";
        if (source.contains("native")) return "native";
        if (source.contains("spicy")) return "spicy";
        return fallback;
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
