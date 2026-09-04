package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.eza.spicyex.xposed.XpLog;
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
            Context context,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            String accessToken,
            int tokenGeneration,
            AuthRecovery authRecovery,
            ResultCallback callback
    ) {
        String uri = track == null ? "" : safe(track.uri);
        String trackId = trackIdFromUri(uri);
        if (trackId.isEmpty()) {
            if (uri.startsWith("spotify:local:")) {
                XpLog.log(TAG + " skipping fetch: local file uri=" + safe(uri));
                callback.onError("Lyrics unavailable for local files");
            } else if (uri.startsWith("spotify:episode:")) {
                XpLog.log(TAG + " skipping fetch: episode uri=" + safe(uri));
                callback.onError("Lyrics unavailable for podcasts/episodes");
            } else {
                XpLog.log(TAG + " skipping fetch: unsupported uri=" + safe(uri));
                callback.onError("Lyrics unavailable for this media");
            }
            return;
        }
        if (NO_LYRICS.contains(trackId)) {
            XpLog.log(TAG + " skip fetch: no lyrics from any source this session, id=" + trackId);
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
                    XpLog.log(TAG + " cached no-lyrics for id=" + negId + " (" + error + ")");
                }
                callback.onError(error);
            }
        };
        fetchSpicyLyricsFallback(context, track, generation, sendToken, accessToken,
                tokenGeneration, authRecovery, false, gated);
    }

    private void fetchSpicyLyricsFallback(
            Context context,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            String accessToken,
            int tokenGeneration,
            AuthRecovery authRecovery,
            boolean authRetryUsed,
            ResultCallback callback
    ) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) {
            callback.onError("Missing track id");
            return;
        }

        probeSpicyVersionOnce();

        final boolean hasToken = hasUsableToken(sendToken, accessToken);
        final String cached = LyricsResponseCache.get(context, trackId);
        final LyricsProviderChain chain = new LyricsProviderChain(generation, cached);
        if (!isBlank(cached)) {
            try {
                LyricsDocument doc = parser.parseSpicyLyrics(context, track, cached, true);
                LyricsProviderChain.Decision decision = chain.acceptCached(doc);
                if (doc.spicyPoisoned) {
                    XpLog.log(TAG + " warning: ignored suspicious cached Spicy response reason="
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
                XpLog.log(TAG + " cached parse failed: " + t);
            }
        }

        if (!hasToken) {
            if (chain.deliveredCachedSynced()) return;
            fetchNativeThenLrclib(context, track, generation, callback, 0,
                    "Spicy token unavailable", chain, false);
            return;
        }
        Request request = buildSpicyLyricsRequest(trackId, accessToken);

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (chain.deliveredCachedSynced()) return;
                fetchNativeThenLrclib(context, track, generation, callback, 0,
                        "Spicy network failed: " + e.getMessage(), chain, hasToken);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (chain.deliveredCachedSynced()) return;
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Spicy API HTTP " + response.code(), chain, hasToken);
                        return;
                    }
                    String raw = response.body().string();
                    // M3: an HTTP-200 envelope can still carry an inner result status of 401/403.
                    // Check the raw body first (the auth-error shape has no lyrics data, so the
                    // parser would only throw), then the parsed document (covers packed payloads
                    // whose status is invisible to a plain scan). Only token-bearing requests are
                    // treated as auth rejections; anonymous rejections have no generation to
                    // invalidate and must not retry.
                    Integer authRejection = hasUsableToken(sendToken, accessToken)
                            ? innerSpicyAuthRejectionStatus(raw) : null;
                    LyricsDocument doc = null;
                    if (authRejection == null) {
                        try {
                            doc = parser.parseSpicyLyrics(context, track, raw, false);
                        } catch (Throwable parseErr) {
                            if (chain.deliveredCachedSynced()) return;
                            XpLog.log(TAG + " parse failed: " + parseErr);
                            fetchNativeThenLrclib(context, track, generation, callback, 0,
                                    "Spicy parse failed: " + parseErr.getMessage(), chain, hasToken);
                            return;
                        }
                        if (hasUsableToken(sendToken, accessToken) && isInnerSpicyAuthRejection(doc)) {
                            authRejection = doc.spicyQueryStatus;
                        }
                    }
                    if (authRejection != null) {
                        handleSpicyAuthRejection(context, track, generation, sendToken,
                                tokenGeneration, authRecovery, authRetryUsed, callback,
                                chain, hasToken, authRejection);
                        return;
                    }
                    LyricsProviderChain.Decision decision = chain.acceptSpicyNetwork(doc, raw);
                    if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                    if (doc.lines.isEmpty()) {
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Spicy lyrics empty", chain, hasToken);
                        return;
                    }
                    if (doc.spicyPoisoned) {
                        XpLog.log(TAG + " warning: rejected suspicious Spicy response reason="
                                + safe(doc.spicyQualityReason)
                                + " status=" + (doc.spicyQueryStatus == null ? "unknown" : doc.spicyQueryStatus)
                                + " format=" + safe(doc.spicyFormat)
                                + " packed=" + doc.spicyPackedPayload
                                + " type=" + safe(doc.type));
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Spicy response suspicious: " + safe(doc.spicyQualityReason), chain, hasToken);
                        return;
                    }
                    if (decision.action == LyricsProviderChain.Action.DELIVER) {
                        boolean cacheWrite = false;
                        if (decision.cacheDeliveredRaw) {
                            LyricsResponseCache.put(context, trackId, decision.rawToCache);
                            cacheWrite = true;
                        }
                        XpLog.log(TAG + " using Spicy synced lyrics type=" + doc.type + " provider=" + doc.provider + " lines=" + doc.lines.size());
                        LyricsFetchDiagnosticsState.record("spicy", chain.candidatesSeen(), doc, hasToken, cacheWrite);
                        callback.onSuccess(doc);
                        return;
                    }
                    XpLog.log(TAG + " Spicy returned static type=" + doc.type + "; probing native synced upgrade");
                    fetchNativeThenLrclib(context, track, generation, callback, 0, "Spicy static", chain, hasToken);
                } catch (Throwable t) {
                    if (chain.deliveredCachedSynced()) return;
                    XpLog.log(TAG + " response handling failed: " + t);
                    fetchNativeThenLrclib(context, track, generation, callback, 0,
                            "Spicy response failed: " + t.getMessage(), chain, hasToken);
                }
            }
        });
    }

    /**
     * M3: an inner Spicy result status of 401/403 rejects the token epoch this request was issued
     * under. The coordinator-owned {@link AuthRecovery} seam tombstones exactly that generation via
     * the token store and returns a replacement authorization only when a newer generation already
     * exists. One retry maximum; with no newer generation the request proceeds to the normal
     * native/LRCLIB fallback instead of looping.
     */
    private void handleSpicyAuthRejection(
            Context context,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            int rejectedTokenGeneration,
            AuthRecovery authRecovery,
            boolean authRetryUsed,
            ResultCallback callback,
            LyricsProviderChain chain,
            boolean hasToken,
            int rejectionStatus
    ) {
        XpLog.log(TAG + " inner Spicy auth rejection status=" + rejectionStatus
                + " tokenGeneration=" + rejectedTokenGeneration
                + (authRetryUsed ? " retryAlreadyUsed" : ""));
        Authorization replacement = authRecovery == null
                ? null : authRecovery.afterAuthRejection(rejectedTokenGeneration);
        if (chain.deliveredCachedSynced()) return; // cached synced already delivered; nothing to do
        if (shouldRetryWithNewerGeneration(rejectedTokenGeneration, authRetryUsed, replacement)) {
            XpLog.log(TAG + " retrying Spicy once with newer token generation="
                    + replacement.generation());
            fetchSpicyLyricsFallback(context, track, generation, sendToken,
                    replacement.token(), replacement.generation(), authRecovery, true, callback);
            return;
        }
        fetchNativeThenLrclib(context, track, generation, callback, 0,
                "Spicy auth rejected HTTP " + rejectionStatus, chain, hasToken);
    }

    /**
     * Pure M3 retry guard: retry at most once, only against a genuinely different (newer) token
     * generation. A null replacement (no newer generation exists) or an already-used retry routes
     * the request to the native/LRCLIB fallback, so an auth failure can never loop.
     */
    static boolean shouldRetryWithNewerGeneration(
            int rejectedTokenGeneration, boolean authRetryUsed, Authorization replacement) {
        return replacement != null
                && !authRetryUsed
                && replacement.generation() != rejectedTokenGeneration;
    }

    /**
     * Scans a raw Spicy HTTP-200 body for an inner query-result status of 401/403 (the shape
     * {@code {"queries":[{"result":{"status":401,...}}]}}). Returns the rejecting status, or null
     * when the body is absent, malformed, or carries no rejecting inner status. Statuses outside
     * the query-result objects are deliberately ignored to avoid false positives.
     */
    static Integer innerSpicyAuthRejectionStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return innerAuthRejectionInQueries(JsonParser.parseString(raw));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer innerAuthRejectionInQueries(JsonElement root) {
        if (root == null || !root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        JsonArray queries = Json.optArray(object, "queries", "Queries");
        if (queries != null) {
            for (JsonElement queryElement : queries) {
                Integer rejection = innerAuthRejectionInResult(queryElement);
                if (rejection != null) return rejection;
            }
            return null;
        }
        return innerAuthRejectionInResult(object);
    }

    private static Integer innerAuthRejectionInResult(JsonElement queryElement) {
        if (queryElement == null || !queryElement.isJsonObject()) return null;
        JsonObject result = Json.optObject(queryElement.getAsJsonObject(), "result", "Result");
        if (result == null) return null;
        Integer status = optStatusInteger(result);
        return isAuthRejectionStatus(status) ? status : null;
    }

    private static Integer optStatusInteger(JsonObject result) {
        JsonElement element = Json.optElement(result, "httpStatus", "HttpStatus", "status", "Status");
        if (element == null) return null;
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Pure M3 seam: inner Spicy query status 401/403 on a parsed document means auth rejection. */
    static boolean isInnerSpicyAuthRejection(LyricsDocument doc) {
        return doc != null && isAuthRejectionStatus(doc.spicyQueryStatus);
    }

    private static boolean isAuthRejectionStatus(Integer status) {
        return status != null && (status == 401 || status == 403);
    }

    private void probeSpicyVersionOnce() {
        final String requestVersion = SpicyLyricsRequestContract.UPSTREAM_VERSION;
        if (!SpicyVersionProbeState.beginProbe(requestVersion)) return;

        RequestBody body = RequestBody.create(
                SpicyVersionProbeState.buildExtVersionQueryBody(requestVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                JSON);
        Request request = new Request.Builder()
                .url(SpicyLyricsRequestContract.SPICY_QUERY_URL)
                .post(body)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("X-mode", "2")
                .header("Origin", SpicyLyricsRequestContract.SPICY_ORIGIN)
                .header("Referer", SpicyLyricsRequestContract.SPICY_ORIGIN + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
                .header("SpicyLyrics-Version", requestVersion)
                .header("User-Agent", SpicyLyricsRequestContract.SPICY_USER_AGENT)
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
                        XpLog.log(TAG + " warning: Spicy client version outdated sent="
                                + SpicyVersionProbeState.spicyVersionSent
                                + " latest=" + SpicyVersionProbeState.spicyLatestVersion);
                    }
                } catch (Throwable t) {
                    SpicyVersionProbeState.recordFailure("response_failed:" + t.getClass().getSimpleName());
                }
            }
        });
    }

    private void fetchNativeThenLrclib(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason) {
        fetchNativeThenLrclib(context, track, generation, callback, nativeRetryCount, reason,
                new LyricsProviderChain(generation, null), false);
    }

    private void fetchNativeThenLrclib(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason,
                                       LyricsProviderChain chain, boolean tokenPresent) {
        fetchNativeThenLrclibWithStatic(context, track, generation, callback, nativeRetryCount,
                reason, chain, tokenPresent);
    }

    private void fetchNativeThenLrclibWithStatic(Context context, SpotifyTrack track, int generation,
                                                 ResultCallback callback, int nativeRetryCount, String reason,
                                                 LyricsProviderChain chain, boolean tokenPresent) {
        LyricsDocument nativeDoc = nativeLyricsProvider.getNativeLyricsDocument(track);
        if (nativeDoc != null && !nativeDoc.lines.isEmpty()) {
            LyricsProviderChain.Decision decision = chain.acceptNative(nativeDoc);
            if (chain.hasPendingStatic()) {
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                if (decision.document == nativeDoc) {
                    XpLog.log(TAG + " using native lyrics (" + safe(reason) + ") type=" + nativeDoc.type
                            + " provider=" + nativeDoc.provider + " lines=" + nativeDoc.lines.size()
                            + " score=" + LyricQualityRanker.score(nativeDoc));
                    LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
                    callback.onSuccess(nativeDoc);
                } else {
                    LyricsDocument spicyStatic = chain.pendingStatic();
                    boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                    XpLog.log(TAG + " keeping Spicy static over native static score="
                            + LyricQualityRanker.score(spicyStatic) + " nativeScore=" + LyricQualityRanker.score(nativeDoc));
                    LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                    callback.onSuccess(spicyStatic);
                }
                return;
            }
            if (LyricsProviderChain.isSyncedType(nativeDoc.type)) {
                XpLog.log(TAG + " using native synced lyrics (" + safe(reason) + ") type=" + nativeDoc.type
                        + " provider=" + nativeDoc.provider + " lines=" + nativeDoc.lines.size());
                LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
                callback.onSuccess(nativeDoc);
                return;
            }
            XpLog.log(TAG + " using native static lyrics (" + safe(reason) + ") lines=" + nativeDoc.lines.size());
            LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeDoc, tokenPresent, false);
            callback.onSuccess(nativeDoc);
            return;
        }

        if (nativeRetryCount < NATIVE_LYRICS_RETRY_LIMIT) {
            int nextRetry = nativeRetryCount + 1;
            XpLog.log(TAG + " waiting for native lyrics (" + safe(reason) + ") retry=" + nextRetry);
            ioScheduler.schedule(
                    () -> fetchNativeThenLrclibWithStatic(context, track, generation, callback, nextRetry,
                            reason, chain, tokenPresent),
                    NATIVE_LYRICS_RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS);
            return;
        }

        chain.nativeMissAfterRetries(reason);
        if (chain.hasPendingStatic()) {
            XpLog.log(TAG + " native absent; probing LRCLIB against Spicy static lines=" + chain.pendingStatic().lines.size());
            fetchLrclibWithSpicyFallback(context, track, generation, callback, reason, chain, tokenPresent);
            return;
        }
        XpLog.log(TAG + " native lyrics miss (" + safe(reason) + "); falling back to LRCLIB");
        fetchLrclib(context, track, generation, callback, reason, chain, tokenPresent);
    }

    private void fetchLrclibWithSpicyFallback(Context context, SpotifyTrack track, int generation,
                                              ResultCallback callback, String reason, LyricsProviderChain chain,
                                              boolean tokenPresent) {
        fetchLrclib(context, track, generation, new ResultCallback() {
            @Override
            public void onSuccess(LyricsDocument lrclibDoc) {
                LyricsProviderChain.Decision decision = chain.acceptLrclib(lrclibDoc);
                LyricsDocument spicyStatic = chain.pendingStatic();
                if (decision.document == lrclibDoc) {
                    XpLog.log(TAG + " using LRCLIB lyrics over Spicy static type=" + lrclibDoc.type
                            + " lines=" + lrclibDoc.lines.size()
                            + " score=" + LyricQualityRanker.score(lrclibDoc)
                            + " spicyScore=" + LyricQualityRanker.score(spicyStatic));
                    LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), lrclibDoc, tokenPresent, false);
                    callback.onSuccess(lrclibDoc);
                    return;
                }
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                XpLog.log(TAG + " native/LRCLIB lower ranked; delivering Spicy static lines="
                        + spicyStatic.lines.size() + " score=" + LyricQualityRanker.score(spicyStatic));
                LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                callback.onSuccess(spicyStatic);
            }

            @Override
            public void onError(String error) {
                LyricsProviderChain.Decision decision = chain.acceptLrclibError(error);
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                LyricsDocument spicyStatic = chain.pendingStatic();
                boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                XpLog.log(TAG + " LRCLIB miss; delivering Spicy static lines=" + spicyStatic.lines.size());
                LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(), spicyStatic, tokenPresent, cacheWrite);
                callback.onSuccess(spicyStatic);
            }
        }, reason, chain, tokenPresent);
    }

    private static boolean cacheChosenRaw(Context context, SpotifyTrack track, String raw) {
        if (isBlank(raw)) return false;
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) return false;
        LyricsResponseCache.put(context, trackId, raw);
        return true;
    }

    private void fetchLrclib(Context context, SpotifyTrack track, int generation, ResultCallback callback, String reason) {
        fetchLrclib(context, track, generation, callback, reason, new LyricsProviderChain(generation, null), false);
    }

    private void fetchLrclib(Context context, SpotifyTrack track, int generation, ResultCallback callback,
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
                    LyricsDocument doc = parser.parseLrclibLyrics(context, track, response.body().string());
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
                    XpLog.log(TAG + " LRCLIB parse failed: " + t);
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

    static String buildSpicyLyricsQueryBody(String trackId) {
        return new String(SpicyLyricsRequestContract.buildLyricsQueryBytes(trackId),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean hasUsableToken(boolean sendToken, String accessToken) {
        return SpicyLyricsRequestContract.hasUsableToken(sendToken, accessToken);
    }

    static Request buildSpicyLyricsRequest(String trackId, String accessToken) {
        return SpicyLyricsRequestContract.buildLyricsRequest(trackId, accessToken);
    }

    private static String sourceLabel(LyricsDocument doc, String fallback) {
        String source = doc == null ? "" : safe(doc.fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("cache")) return "cache";
        if (source.contains("lrclib")) return "lrclib";
        if (source.contains("native")) return "native";
        if (source.contains("spicy")) return "spicy";
        return fallback;
    }

    public interface ResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

    /**
     * M3 seam implemented hook-side (the lyrics layer must never depend on hooks). Reports that an
     * inner Spicy 401/403 rejected the request issued under {@code rejectedTokenGeneration}; the
     * implementation tombstones exactly that generation in the token store and returns a
     * replacement {@link Authorization} only when a newer usable generation already exists, or
     * {@code null} to let the caller proceed to the native/LRCLIB fallback.
     */
    public interface AuthRecovery {
        Authorization afterAuthRejection(int rejectedTokenGeneration);
    }

    /**
     * Immutable authorization snapshot for one retried request. The token text is request-only:
     * it never enters keys, logs, errors, or {@code toString}.
     */
    public static final class Authorization {
        private final String token;
        private final int generation;

        private Authorization(String token, int generation) {
            this.token = token == null ? "" : token;
            this.generation = generation;
        }

        public static Authorization of(String token, int generation) {
            return new Authorization(token, generation);
        }

        /** Token text for the retried request header; never logged, keyed, or stringified. */
        public String token() {
            return token;
        }

        /** Non-secret generation identifying this token epoch. */
        public int generation() {
            return generation;
        }

        /** Token-free diagnostics; the text never appears here. */
        @Override
        public String toString() {
            return "Authorization{generation=" + generation
                    + ", tokenLength=" + token.length() + "}";
        }
    }

    public interface Parser {
        LyricsDocument parseSpicyLyrics(Context context, SpotifyTrack track, String raw, boolean fromCache);
        LyricsDocument parseLrclibLyrics(Context context, SpotifyTrack track, String body);
    }

    public interface NativeLyricsProvider {
        LyricsDocument getNativeLyricsDocument(SpotifyTrack track);
    }
}
