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
    private static final String TAG = "[SpotifyPlusLyricsRepository]";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int NATIVE_LYRICS_RETRY_LIMIT = 4;
    private static final long NATIVE_LYRICS_RETRY_DELAY_MS = 125;

    // Tracks confirmed to have no lyrics from ANY source this session — shared across callers (the
    // in-player card and the fullscreen screen both fetch through here), so a no-lyric song isn't
    // re-queried (and re-billed against the Spicy quota) when the other surface opens. In-memory:
    // resets on process restart so a track that later gains lyrics is re-checked next launch.
    private static final int NO_LYRICS_LIMIT = 256;
    private static final java.util.Map<String, Boolean> NO_LYRICS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Boolean>(NO_LYRICS_LIMIT, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > NO_LYRICS_LIMIT;
                }
            });

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
        // Source toggles and order are the single source of truth. The legacy pinned
        // preference is intentionally ignored so a disabled provider can never be queried.
        com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode rankingMode =
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.rankingMode(context);
        java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder =
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.enabledSourceOrder(context);
        if (rankingMode == com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode.SOURCE_ORDER) {
            fetchOrderedSources(context, track, generation, enabledOrder, accessToken, callback);
            return;
        }
        if (enabledOrder.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        boolean remoteEnabled = isStepEnabled(enabledOrder,
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.APPLE_MUSIC,
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPICY);
        boolean nativeEnabled = enabledOrder.contains(
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPOTIFY);
        boolean lrclibEnabled = enabledOrder.contains(
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.LRCLIB);
        if (NO_LYRICS.containsKey(trackId)) {
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
                    NO_LYRICS.put(negId, Boolean.TRUE);
                    XpLog.log(TAG + " cached no-lyrics for id=" + negId + " (" + error + ")");
                }
                callback.onError(error);
            }
        };
        fetchSpicyLyricsFallback(context, track, generation, sendToken, accessToken,
                tokenGeneration, authRecovery, false, gated,
                remoteEnabled, nativeEnabled, lrclibEnabled);
    }

    /** Source-order Auto: first enabled source in user order that yields lyrics wins. */
    private void fetchOrderedSources(Context context, SpotifyTrack track, int generation,
                                     java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                     String accessToken, ResultCallback callback) {
        if (enabledOrder == null || enabledOrder.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        attemptOrderedSource(context, track, generation, enabledOrder, 0, accessToken, callback);
    }

    private void attemptOrderedSource(Context context, SpotifyTrack track, int generation,
                                      java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                      int index, String accessToken, ResultCallback callback) {
        if (index >= enabledOrder.size()) {
            callback.onError("No lyric source in order produced lyrics");
            return;
        }
        String source = labelFor(enabledOrder.get(index));
        fetchSingleSource(context, track, generation, source, accessToken, new ResultCallback() {
            @Override public void onSuccess(LyricsDocument document) {
                callback.onSuccess(document);
            }
            @Override public void onError(String error) {
                attemptOrderedSource(context, track, generation, enabledOrder, index + 1, accessToken, callback);
            }
        }, 0);
    }

    private static String labelFor(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source source) {
        if (source == null) return "Auto";
        switch (source) {
            case APPLE_MUSIC: return "Apple Music";
            case SPICY: return "Spicy";
            case SPOTIFY: return "Spotify";
            case LRCLIB: return "LRCLIB";
            default: return "Auto";
        }
    }

    private static boolean isStepEnabled(
            java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
            com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source... anyOf) {
        if (enabledOrder == null || anyOf == null) return false;
        for (com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source source : anyOf) {
            if (enabledOrder.contains(source)) return true;
        }
        return false;
    }

    private void fetchSingleSource(Context context, SpotifyTrack track, int generation,
                                   String source, String accessToken, ResultCallback callback,
                                   int nativeRetryCount) {
        if ("Apple Music".equals(source) || "Spicy".equals(source)) {
            Request request = "Spicy".equals(source)
                    ? SpicyLyricsRequestContract.buildLyricsRequest(trackIdFromUri(track.uri), accessToken)
                    : buildLenerdLyricsRequest(trackIdFromUri(track.uri));
            http.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    callback.onError(source + " source unavailable: " + safe(error.getMessage()));
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try (Response ignored = response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            callback.onError(source + " source unavailable: HTTP " + response.code());
                            return;
                        }
                        LyricsDocument document = parser.parseSpicyLyrics(
                                context, track, response.body().string(), false);
                        document.fetchSource = "Spicy".equals(source) ? "spicy_api" : "apple_music_lenerd";
                        document.selectedSource = source;
                        document.selectionMode = "strict";
                        document.selectionOverride = source;
                        if (document.lines.isEmpty() || LyricQualityRanker.score(document) == LyricQualityRanker.REJECT) {
                            callback.onError(source + " source unavailable: rejected candidate");
                            return;
                        }
                        callback.onSuccess(document);
                    } catch (Throwable parseError) {
                        callback.onError(source + " source unavailable: " + safe(parseError.getMessage()));
                    }
                }
            });
            return;
        }
        if ("Spotify".equals(source)) {
            LyricsDocument document = nativeLyricsProvider.getNativeLyricsDocument(track);
            if (document != null && !document.lines.isEmpty()) {
                document.selectedSource = "Spotify";
                document.selectionMode = "strict";
                document.selectionOverride = "Spotify";
                callback.onSuccess(document);
            } else if (nativeRetryCount < NATIVE_LYRICS_RETRY_LIMIT) {
                ioScheduler.schedule(() -> fetchSingleSource(context, track, generation, source,
                                accessToken, callback, nativeRetryCount + 1),
                        NATIVE_LYRICS_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            } else {
                callback.onError("Spotify source unavailable");
            }
            return;
        }
        if ("LRCLIB".equals(source)) {
            fetchLrclib(context, track, generation, new ResultCallback() {
                @Override public void onSuccess(LyricsDocument document) {
                    document.selectedSource = "LRCLIB";
                    document.selectionMode = "strict";
                    document.selectionOverride = "LRCLIB";
                    callback.onSuccess(document);
                }
                @Override public void onError(String error) {
                    callback.onError("LRCLIB source unavailable: " + safe(error));
                }
            }, "strict LRCLIB");
            return;
        }
        callback.onError("Unknown lyrics source");
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
            ResultCallback callback,
            boolean remoteEnabled,
            boolean nativeEnabled,
            boolean lrclibEnabled
    ) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) {
            callback.onError("Missing track id");
            return;
        }

        OkHttpClient spicyHttp = SpicyTransport.client(http, SpicyTransport.breaker(context));

        final boolean hasToken = hasUsableToken(sendToken, accessToken);
        final String cached = remoteEnabled ? LyricsResponseCache.get(context, trackId) : null;
        final LyricsProviderChain chain = new LyricsProviderChain(generation, cached);
        // Native Spotify lyrics are the baseline. They are read from the existing captured
        // document/cache and published immediately; Spicy may replace them only during the
        // bounded upgrade window when a usable token is available.
        LyricsDocument nativeBaseline = nativeEnabled
                ? nativeLyricsProvider.getNativeLyricsDocument(track) : null;
        if (nativeBaseline != null && nativeBaseline.lines != null && !nativeBaseline.lines.isEmpty()) {
            LyricsProviderChain.Decision baseline = chain.acceptNative(nativeBaseline);
            if (baseline.action == LyricsProviderChain.Action.DELIVER) {
                LyricsFetchDiagnosticsState.record("native", chain.candidatesSeen(), nativeBaseline, hasToken, false);
                callback.onSuccess(nativeBaseline);
            }
        }
        if (remoteEnabled && !isBlank(cached)) {
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

        Request request = buildLenerdLyricsRequest(trackId).newBuilder()
                .tag(SpicyTransport.Probe.class, authRetryUsed ? null : new SpicyTransport.Probe()).build();
        logSpicyWireRequest(request, tokenGeneration, authRetryUsed);

        if (!remoteEnabled) {
            if (!chain.deliveredCachedSynced()) {
                fetchNativeThenLrclib(context, track, generation, callback, 0,
                        "Remote source disabled", chain, hasToken, nativeEnabled, lrclibEnabled);
            }
            return;
        }
        spicyHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (chain.deliveredCachedSynced()) return;
                fetchNativeThenLrclib(context, track, generation, callback, 0,
                        (authRetryUsed && e instanceof SpicyCircuitBreaker.Suppressed
                                ? "Spicy auth rejected HTTP 401" : "Spicy upstream-error status 0: " + e.getMessage()), chain, hasToken,
                        nativeEnabled, lrclibEnabled);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (chain.deliveredCachedSynced()) return;
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                (response.code() == 429 ? "Spicy rate-limited HTTP 429" : "Spicy upstream-error HTTP " + response.code()), chain, hasToken,
                                nativeEnabled, lrclibEnabled);
                        return;
                    }
                    String raw = response.body().string();
                    logSpicyWireResponse(response.code(), raw);
                    // M3: an HTTP-200 envelope can still carry an inner result status of 401.
                    // Check the raw body first (the auth-error shape has no lyrics data, so the
                    // parser would only throw), then the parsed document (covers packed payloads
                    // whose status is invisible to a plain scan). Only token-bearing requests are
                    // treated as auth rejections; anonymous rejections have no generation to
                    // invalidate and must not retry.
                    JsonElement envelope = JsonParser.parseString(raw);
                    boolean isEnveloped = envelope.isJsonObject() && envelope.getAsJsonObject().has("queries");
                    Integer authRejection = isEnveloped && hasUsableToken(sendToken, accessToken)
                            ? innerSpicyAuthRejectionStatus(raw) : null;
                    JsonObject queryResult = isEnveloped ? SpicyQueryEnvelope.result(envelope) : null;
                    String queryFailure = isEnveloped ? SpicyNetworkDiagnostics.recordEnvelope(envelope, queryResult) : null;
                    if (isEnveloped && authRejection == null && (queryResult == null || queryFailure != null)) {
                        if (!chain.deliveredCachedSynced()) fetchNativeThenLrclib(context, track, generation,
                                callback, 0, queryFailure == null ? "Spicy operation 0 missing" : queryFailure, chain, hasToken,
                                nativeEnabled, lrclibEnabled);
                        return;
                    }
                    LyricsDocument doc = null;
                    if (authRejection == null) {
                        try {
                            doc = parser.parseSpicyLyrics(context, track, raw, false);
                        } catch (Throwable parseErr) {
                            if (chain.deliveredCachedSynced()) return;
                            XpLog.log(TAG + " parse failed: " + parseErr);
                            fetchNativeThenLrclib(context, track, generation, callback, 0,
                                    "Spicy parse failed: " + parseErr.getMessage(), chain, hasToken,
                                    nativeEnabled, lrclibEnabled);
                            return;
                        }
                        if (hasUsableToken(sendToken, accessToken) && isInnerSpicyAuthRejection(doc)) {
                            authRejection = doc.spicyQueryStatus;
                        }
                    }
                    if (authRejection != null) {
                        handleSpicyAuthRejection(context, track, generation, sendToken,
                                accessToken, tokenGeneration, authRecovery, authRetryUsed, callback,
                                chain, hasToken, authRejection, remoteEnabled, nativeEnabled, lrclibEnabled);
                        return;
                    }
                    LyricsProviderChain.Decision decision = chain.acceptSpicyNetwork(doc, raw);
                    if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                    if (doc.lines.isEmpty()) {
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Spicy lyrics empty", chain, hasToken, nativeEnabled, lrclibEnabled);
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
                                "Spicy response suspicious: " + safe(doc.spicyQualityReason), chain, hasToken,
                                nativeEnabled, lrclibEnabled);
                        return;
                    }
                    if (decision.action == LyricsProviderChain.Action.DELIVER) {
                        boolean cacheWrite = false;
                        if (decision.cacheDeliveredRaw) {
                            LyricsResponseCache.put(context, trackId, decision.rawToCache);
                            cacheWrite = true;
                        }
                        XpLog.log(TAG + " using Apple Music synced lyrics type=" + doc.type + " provider=" + doc.provider + " lines=" + doc.lines.size());
                        LyricsFetchDiagnosticsState.record("apple_music", chain.candidatesSeen(), doc, false, cacheWrite);
                        callback.onSuccess(doc);
                        return;
                    }
                    XpLog.log(TAG + " remote returned static type=" + doc.type + "; probing native synced upgrade");
                    fetchNativeThenLrclib(context, track, generation, callback, 0, "Spicy static", chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                } catch (java.util.concurrent.CancellationException cancelled) {
                    if (!chain.deliveredCachedSynced()) callback.onError("Spicy request cancelled");
                } catch (IOException networkFailure) {
                    SpicyNetworkDiagnostics.recordTransport("upstream-error", 0, null);
                    if (!chain.deliveredCachedSynced()) fetchNativeThenLrclib(context, track, generation,
                            callback, 0, "Spicy upstream-error status 0", chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                } catch (Throwable t) {
                    if (chain.deliveredCachedSynced()) return;
                    XpLog.log(TAG + " response handling failed: " + t);
                    fetchNativeThenLrclib(context, track, generation, callback, 0,
                            "Spicy response failed: " + t.getMessage(), chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                }
            }
        });
    }

    /**
     * M3: an inner Spicy result status of 401 rejects the token epoch this request was issued
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
            String rejectedToken,
            int rejectedTokenGeneration,
            AuthRecovery authRecovery,
            boolean authRetryUsed,
            ResultCallback callback,
            LyricsProviderChain chain,
            boolean hasToken,
            int rejectionStatus,
            boolean remoteEnabled,
            boolean nativeEnabled,
            boolean lrclibEnabled
    ) {
        XpLog.log(TAG + " inner Spicy auth rejection status=" + rejectionStatus
                + " tokenGeneration=" + rejectedTokenGeneration
                + (authRetryUsed ? " retryAlreadyUsed" : ""));
        Authorization replacement = resolveAfterAuthRejection(authRecovery, rejectedTokenGeneration);
        if (chain.deliveredCachedSynced()) return; // cached synced already delivered; nothing to do
        if (shouldRetryAuthorization(rejectedToken, rejectedTokenGeneration, authRetryUsed, replacement)) {
            XpLog.log(TAG + " retrying Spicy once with newer token generation="
                    + replacement.generation());
            fetchSpicyLyricsFallback(context, track, generation, sendToken,
                    replacement.token(), replacement.generation(), authRecovery, true, callback,
                    remoteEnabled, nativeEnabled, lrclibEnabled);
            return;
        }
        fetchNativeThenLrclib(context, track, generation, callback, 0,
                "Spicy auth rejected HTTP " + rejectionStatus, chain, hasToken,
                nativeEnabled, lrclibEnabled);
    }

    static Authorization resolveAfterAuthRejection(AuthRecovery recovery, int rejectedGeneration) {
        try {
            return recovery == null ? null : recovery.afterAuthRejection(rejectedGeneration);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException ignored) {
            // Preserve the original rejection when refresh fails.
            return null;
        }
    }

    static boolean shouldRetryAuthorization(String rejectedToken, int rejectedGeneration,
                                             boolean retryUsed, Authorization replacement) {
        return shouldRetryWithNewerGeneration(rejectedGeneration, retryUsed, replacement)
                && hasUsableToken(true, replacement.token()) && !replacement.token().equals(rejectedToken);
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
                && replacement.generation() > rejectedTokenGeneration;
    }

    /**
     * Scans a raw Spicy HTTP-200 body for an inner query-result status of 401 (the shape
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
        Integer status = SpicyQueryEnvelope.status(SpicyQueryEnvelope.result(root));
        return isAuthRejectionStatus(status) ? status : null;
    }

    /** Pure M3 seam: inner Spicy query status 401 on a parsed document means auth rejection. */
    static boolean isInnerSpicyAuthRejection(LyricsDocument doc) {
        return doc != null && isAuthRejectionStatus(doc.spicyQueryStatus);
    }

    private static boolean isAuthRejectionStatus(Integer status) {
        return status != null && status == 401;
    }

    private void probeSpicyVersionOnce(OkHttpClient spicyHttp) {
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

        spicyHttp.newCall(request).enqueue(new Callback() {
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
                new LyricsProviderChain(generation, null), false, true, true);
    }

    private void fetchNativeThenLrclib(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason,
                                       LyricsProviderChain chain, boolean tokenPresent) {
        fetchNativeThenLrclibWithStatic(context, track, generation, callback, nativeRetryCount,
                reason, chain, tokenPresent, true, true);
    }

    private void fetchNativeThenLrclib(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback, int nativeRetryCount, String reason,
                                       LyricsProviderChain chain, boolean tokenPresent,
                                       boolean nativeEnabled, boolean lrclibEnabled) {
        fetchNativeThenLrclibWithStatic(context, track, generation, callback, nativeRetryCount,
                reason, chain, tokenPresent, nativeEnabled, lrclibEnabled);
    }

    private void fetchNativeThenLrclibWithStatic(Context context, SpotifyTrack track, int generation,
                                                 ResultCallback callback, int nativeRetryCount, String reason,
                                                 LyricsProviderChain chain, boolean tokenPresent,
                                                 boolean nativeEnabled, boolean lrclibEnabled) {
        LyricsDocument nativeDoc = nativeEnabled
                ? nativeLyricsProvider.getNativeLyricsDocument(track) : null;
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

        if (nativeRetryCount < NATIVE_LYRICS_RETRY_LIMIT && nativeEnabled) {
            int nextRetry = nativeRetryCount + 1;
            XpLog.log(TAG + " waiting for native lyrics (" + safe(reason) + ") retry=" + nextRetry);
            ioScheduler.schedule(
                    () -> fetchNativeThenLrclibWithStatic(context, track, generation, callback, nextRetry,
                            reason, chain, tokenPresent, nativeEnabled, lrclibEnabled),
                    NATIVE_LYRICS_RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS);
            return;
        }

        chain.nativeMissAfterRetries(reason);
        if (chain.hasPendingStatic()) {
            if (!lrclibEnabled) {
                LyricsDocument spicyStatic = chain.pendingStatic();
                XpLog.log(TAG + " native absent and LRCLIB disabled; delivering Spicy static lines="
                        + spicyStatic.lines.size());
                LyricsFetchDiagnosticsState.record(sourceLabel(spicyStatic, "spicy"), chain.candidatesSeen(),
                        spicyStatic, tokenPresent, false);
                callback.onSuccess(spicyStatic);
                return;
            }
            XpLog.log(TAG + " native absent; probing LRCLIB against Spicy static lines=" + chain.pendingStatic().lines.size());
            fetchLrclibWithSpicyFallback(context, track, generation, callback, reason, chain, tokenPresent);
            return;
        }
        if (!lrclibEnabled) {
            XpLog.log(TAG + " native lyrics miss (" + safe(reason) + "); LRCLIB disabled");
            callback.onError(reason + "; native miss, LRCLIB disabled");
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
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        String cachedRaw = trackId.isEmpty() ? null : LyricsResponseCache.getLrclib(context, trackId);
        if (cachedRaw != null) {
            try {
                LyricsDocument doc = parser.parseLrclibLyrics(context, track, cachedRaw);
                doc.generation = generation;
                if (!doc.lines.isEmpty()) {
                    XpLog.log(TAG + " LRCLIB cache hit id=" + trackId + " lines=" + doc.lines.size());
                    chain.acceptLrclib(doc);
                    LyricsFetchDiagnosticsState.record("lrclib_cache", chain.candidatesSeen(), doc, tokenPresent, false);
                    callback.onSuccess(doc);
                    return;
                }
            } catch (Throwable t) {
                XpLog.log(TAG + " LRCLIB cache parse failed, refetching: " + t);
            }
        }
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
                    String rawBody = response.body().string();
                    LyricsDocument doc = parser.parseLrclibLyrics(context, track, rawBody);
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        reportLrclibError(chain, callback, reason + "; LRCLIB empty");
                        return;
                    }
                    if (!trackId.isEmpty()) LyricsResponseCache.putLrclib(context, trackId, rawBody);
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

    private static final boolean WIRE_DEBUG_CAPTURE = false;

    private static void logSpicyWireRequest(Request request, int generation, boolean retry) {
        if (!WIRE_DEBUG_CAPTURE || request == null) return;
        try {
            XpLog.log(TAG + " remote request url=" + request.url());
        } catch (Throwable ignored) { }
    }

    private static void logSpicyWireResponse(int status, String raw) {
        if (!WIRE_DEBUG_CAPTURE) return;
        XpLog.log(TAG + " remote response status=" + status + " bytes=" + (raw == null ? 0 : raw.length()));
    }

    static String buildSpicyLyricsQueryBody(String trackId) {
        return new String(SpicyLyricsRequestContract.buildLyricsQueryBytes(trackId),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean hasUsableToken(boolean sendToken, String accessToken) {
        return SpicyLyricsRequestContract.hasUsableToken(sendToken, accessToken);
    }

    private static final String LENERD_ENDPOINT_URL = "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/";

    static Request buildSpicyLyricsRequest(String trackId, String accessToken) {
        return SpicyLyricsRequestContract.buildLyricsRequest(trackId, accessToken);
    }

    static Request buildLenerdLyricsRequest(String trackId) {
        return new Request.Builder()
                .url(LENERD_ENDPOINT_URL + trackId)
                .get()
                .header("User-Agent", "SpotifyPlus-Mobile")
                .header("Accept", "application/json")
                .build();
    }

    private static String sourceLabel(LyricsDocument doc, String fallback) {
        String source = doc == null ? "" : safe(doc.fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("cache")) return "cache";
        if (source.contains("lrclib")) return "lrclib";
        if (source.contains("native")) return "native";
        // LyricsParser tags Apple-Music/Spicy-API static-lyrics results as fetchSource
        // "apple_music" - without this check they fell through to the "spicy" fallback below and
        // were misreported as coming from Spicy instead of Apple Music.
        if (source.contains("apple_music")) return "apple_music";
        if (source.contains("spicy")) return "spicy";
        return fallback;
    }

    public interface ResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

    /**
     * M3 seam implemented hook-side (the lyrics layer must never depend on hooks). Reports that an
     * inner Spicy 401 rejected the request issued under {@code rejectedTokenGeneration}; the
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
