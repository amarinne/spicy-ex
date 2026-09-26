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
import java.util.ArrayList;
import java.util.List;
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

/** Fetch/fallback coordinator for remote lyrics (Apple Music + Spotify native + LRCLIB). */
public final class LyricsRepository {
    private static final String TAG = "[SpotifyPlusLyricsRepository]";
    private static final int NATIVE_LYRICS_RETRY_LIMIT = 4;
    private static final long NATIVE_LYRICS_RETRY_DELAY_MS = 125;
    // QQ Music's search cgi throttles with an inline {"code":2001,...} (empty results, HTTP 200)
    // rather than a transport error - observed to be transient per-query noise, not a real "no
    // match": the identical request can flip between 2001 and a normal hit call to call with no
    // change in query or device. A short retry clears most of these before giving up for real.
    private static final int QQ_SEARCH_RETRY_LIMIT = 2;
    private static final long QQ_SEARCH_RETRY_DELAY_MS = 500;

    // Tracks confirmed to have no lyrics from ANY source this session — shared across callers (the
    // in-player card and the fullscreen screen both fetch through here), so a no-lyric song isn't
    // re-queried (and re-billed against the remote quota) when the other surface opens. In-memory:
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
        if (enabledOrder.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        if (NO_LYRICS.containsKey(trackId)) {
            XpLog.log(TAG + " skip fetch: no lyrics from any source this session, id=" + trackId);
            callback.onError("Lyrics unavailable (cached no-result)");
            return;
        }
        // Karaoke/off-vocal versions have no lyrics of their own: search for the original.
        SpotifyTrack searchTrack = track;
        try {
            if (com.eza.spicyex.SpotifyPlusConfig.from(context)
                    .get(com.eza.spicyex.Settings.KARAOKE_ORIGINAL_LYRICS)) {
                searchTrack = KaraokeTitles.forLyricsSearch(track);
            }
        } catch (Throwable ignored) {
        }
        // Both Auto and Source Order modes now respect enabled sources
        fetchOrderedSources(context, searchTrack, generation, enabledOrder, accessToken, callback);
    }

    /** Source-order Auto: walks enabled sources in order. A word-level (Syllable) hit wins
     *  immediately; a lower-fidelity hit (Line/Static) is kept only as a fallback while later
     *  sources are still tried, so a Line-only source landing first in the list can't silently
     *  starve a later one (e.g. QQ's word-level QRC) of ever being attempted. Costs a bit of
     *  latency on tracks whose first hit isn't already Syllable, in exchange for actually earning
     *  the "Auto" name instead of being a plain first-match Source Order. */
    private void fetchOrderedSources(Context context, SpotifyTrack track, int generation,
                                     java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                     String accessToken, ResultCallback callback) {
        if (enabledOrder == null || enabledOrder.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        attemptOrderedSource(context, track, generation, enabledOrder, 0, accessToken, callback,
                new java.util.ArrayList<>(), null, null);
    }

    private void attemptOrderedSource(Context context, SpotifyTrack track, int generation,
                                      java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                      int index, String accessToken, ResultCallback callback,
                                      java.util.List<String> attempted,
                                      LyricsDocument bestSoFar, String bestSourceSoFar) {
        if (index >= enabledOrder.size()) {
            if (bestSoFar != null) {
                LyricsFetchDiagnosticsState.record(diagnosticsSourceId(bestSourceSoFar), attempted, bestSoFar,
                        accessToken != null, false);
                callback.onSuccess(bestSoFar);
                return;
            }
            // Every enabled source failed this round - the settings-panel diagnostics snapshot
            // must still move, otherwise it keeps showing whichever source last actually won
            // (frequently Apple, since it's first in the default order) even once that source
            // stops being tried at all - the mismatch this fix is for.
            LyricsFetchDiagnosticsState.record("none", attempted, null, accessToken != null, false);
            callback.onError("No lyric source in order produced lyrics");
            return;
        }
        String source = labelFor(enabledOrder.get(index));
        attempted.add(source);
        fetchSingleSource(context, track, generation, source, accessToken, new ResultCallback() {
            @Override public void onSuccess(LyricsDocument document) {
                if ("Syllable".equalsIgnoreCase(document.type)) {
                    LyricsFetchDiagnosticsState.record(diagnosticsSourceId(source), attempted, document,
                            accessToken != null, false);
                    callback.onSuccess(document);
                    return;
                }
                // Keep the best candidate seen so far, rather than the first non-syllable hit.
                // The old first-hit rule let Apple Music's Line result permanently beat a later
                // QQ Word/Line result even when QQ was the better candidate for this track.
                LyricsDocument keptBest = bestSoFar == null
                        || LyricQualityRanker.prefer(document, bestSoFar) ? document : bestSoFar;
                String keptSource = keptBest == document ? source : bestSourceSoFar;
                attemptOrderedSource(context, track, generation, enabledOrder, index + 1, accessToken, callback,
                        attempted, keptBest, keptSource);
            }
            @Override public void onError(String error) {
                attemptOrderedSource(context, track, generation, enabledOrder, index + 1, accessToken, callback,
                        attempted, bestSoFar, bestSourceSoFar);
            }
        }, 0);
    }

    /** Maps a fetchSingleSource() label to the short id LyricsFetchDiagnosticsState/the settings
     *  panel expect (matching what sourceOrigin() already recognizes for cache display). */
    private static String diagnosticsSourceId(String label) {
        if ("Apple Music".equals(label)) return "apple_music";
        if ("Spicy".equals(label)) return "spicy";
        if ("Spotify".equals(label)) return "native";
        if ("LRCLIB".equals(label)) return "lrclib";
        if ("NetEase".equals(label)) return "netease";
        if ("QQ Music".equals(label)) return "qq_music";
        if ("Musixmatch".equals(label)) return "musixmatch";
        return "unknown";
    }

    private static String labelFor(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source source) {
        if (source == null) return "Auto";
        switch (source) {
            case APPLE_MUSIC: return "Apple Music";
            case SPICY: return "Spicy";
            case SPOTIFY: return "Spotify";
            case AMLL: return "AMLL";
            case LRCLIB: return "LRCLIB";
            case NETEASE: return "NetEase";
            case QQ_MUSIC: return "QQ Music";
            case MUSIXMATCH: return "Musixmatch";
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
            // "Spicy" is a retired legacy alias: it resolves to the same Apple Music (Lenerd)
            // endpoint so old persisted strict selections keep working without hitting
            // the retired spicylyrics.org remote.
            Request request = buildLenerdLyricsRequest(trackIdFromUri(track.uri));
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
                        document.fetchSource = "apple_music_lenerd";
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
        if ("NetEase".equals(source)) {
            fetchNetease(context, track, generation, new ResultCallback() {
                @Override public void onSuccess(LyricsDocument document) {
                    document.selectedSource = "NetEase";
                    document.selectionMode = "strict";
                    document.selectionOverride = "NetEase";
                    callback.onSuccess(document);
                }
                @Override public void onError(String error) {
                    callback.onError("NetEase source unavailable: " + safe(error));
                }
            });
            return;
        }
        if ("Musixmatch".equals(source)) {
            fetchMusixmatch(context, track, generation, new ResultCallback() {
                @Override public void onSuccess(LyricsDocument document) {
                    document.selectedSource = "Musixmatch";
                    document.selectionMode = "strict";
                    document.selectionOverride = "Musixmatch";
                    callback.onSuccess(document);
                }
                @Override public void onError(String error) {
                    callback.onError("Musixmatch source unavailable: " + safe(error));
                }
            });
            return;
        }
        if ("QQ Music".equals(source)) {
            fetchQqMusic(context, track, generation, new ResultCallback() {
                @Override public void onSuccess(LyricsDocument document) {
                    document.selectedSource = "QQ Music";
                    document.selectionMode = "strict";
                    document.selectionOverride = "QQ Music";
                    callback.onSuccess(document);
                }
                @Override public void onError(String error) {
                    callback.onError("QQ Music source unavailable: " + safe(error));
                }
            });
            return;
        }
        if ("AMLL".equals(source)) {
            fetchStrictAmll(context, track, generation, callback);
            return;
        }
        callback.onError("Unknown lyrics source");
    }

    /** Strict per-track AMLL: direct Spotify-ID TTML first, title search second, no fallback. */
    private void fetchStrictAmll(Context context, SpotifyTrack track, int generation,
                                 ResultCallback callback) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (!trackId.isEmpty()) {
            Request direct = new Request.Builder()
                    .url("https://amll-ttml-db.stevexmh.net/spotify/" + trackId + "?format=ttml")
                    .get()
                    .header("User-Agent", "SpotifyPlus-Mobile")
                    .header("Accept", "application/xml, text/xml, text/plain;q=0.9")
                    .build();
            http.newCall(direct).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    fetchStrictAmllSearch(context, track, generation, callback);
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try (Response ignored = response) {
                        if (response.isSuccessful() && response.body() != null) {
                            String body = response.body().string();
                            if (looksLikeTtml(body)) {
                                try {
                                    callback.onSuccess(strictAmllDocument(
                                            context, track, generation, body));
                                    return;
                                } catch (Throwable parseError) {
                                    XpLog.log(TAG + " strict AMLL direct parse failed: "
                                            + parseError);
                                }
                            }
                        }
                        fetchStrictAmllSearch(context, track, generation, callback);
                    }
                }
            });
            return;
        }
        fetchStrictAmllSearch(context, track, generation, callback);
    }

    private void fetchStrictAmllSearch(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback) {
        List<String> queries = new ArrayList<>();
        for (String title : LrclibQueryPlanner.queryTitles(
                safe(track == null ? "" : track.title))) {
            if (!isBlank(title) && !queries.contains(title)) queries.add(title);
        }
        fetchStrictAmllSearchQuery(context, track, generation, callback, queries, 0);
    }

    private void fetchStrictAmllSearchQuery(Context context, SpotifyTrack track, int generation,
                                            ResultCallback callback, List<String> queries,
                                            int index) {
        if (index >= queries.size()) {
            callback.onError("AMLL source unavailable: no match");
            return;
        }
        String payload = "{\"query\":\"" + queries.get(index).replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"type\":\"title\"}";
        Request request = new Request.Builder()
                .url("https://amlldb.bikonoo.com/api/search-lyrics")
                .post(okhttp3.RequestBody.create(payload,
                        okhttp3.MediaType.get("application/json; charset=utf-8")))
                .header("User-Agent", "SpotifyPlus-Mobile")
                .header("Accept", "application/json")
                .build();
        final int nextIndex = index + 1;
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fetchStrictAmllSearchQuery(context, track, generation, callback, queries,
                        nextIndex);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    String file = "";
                    if (response.isSuccessful() && response.body() != null) {
                        file = matchAmllSearchResult(response.body().string(), track);
                    }
                    if (isBlank(file)) {
                        fetchStrictAmllSearchQuery(context, track, generation, callback,
                                queries, nextIndex);
                        return;
                    }
                    Request raw = new Request.Builder()
                            .url("https://amlldb.bikonoo.com/raw-lyrics/" + file)
                            .get()
                            .header("User-Agent", "SpotifyPlus-Mobile")
                            .header("Accept", "application/xml, text/xml, text/plain;q=0.9")
                            .build();
                    http.newCall(raw).enqueue(new Callback() {
                        @Override public void onFailure(Call call, IOException e) {
                            fetchStrictAmllSearchQuery(context, track, generation, callback,
                                    queries, nextIndex);
                        }

                        @Override public void onResponse(Call call, Response response)
                                throws IOException {
                            try (Response ignored = response) {
                                if (response.isSuccessful() && response.body() != null) {
                                    String body = response.body().string();
                                    if (looksLikeTtml(body)) {
                                        try {
                                            callback.onSuccess(strictAmllDocument(
                                                    context, track, generation, body));
                                            return;
                                        } catch (Throwable parseError) {
                                            XpLog.log(TAG + " strict AMLL raw parse failed: "
                                                    + parseError);
                                        }
                                    }
                                }
                                fetchStrictAmllSearchQuery(context, track, generation, callback,
                                        queries, nextIndex);
                            }
                        }
                    });
                }
            }
        });
    }

    private LyricsDocument strictAmllDocument(Context context, SpotifyTrack track, int generation,
                                              String ttml) {
        LyricsDocument document = parser.parseAmllTtml(context, track, ttml);
        document.generation = generation;
        if (document.lines.isEmpty()
                || LyricQualityRanker.score(document) == LyricQualityRanker.REJECT) {
            throw new IllegalStateException("rejected candidate");
        }
        document.selectedSource = "AMLL";
        document.selectionMode = "strict";
        document.selectionOverride = "AMLL";
        return document;
    }

    private void fetchRemoteLyricsFallback(
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

        OkHttpClient remoteHttp = SpicyTransport.client(http, SpicyTransport.breaker(context));

        final boolean hasToken = hasUsableToken(sendToken, accessToken);
        final String cached = remoteEnabled ? LyricsResponseCache.get(context, trackId) : null;
        final LyricsProviderChain chain = new LyricsProviderChain(generation, cached);
        // Native Spotify lyrics are the baseline. They are read from the existing captured
        // document/cache and published immediately; the remote source may replace them only during the
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
                    XpLog.log(TAG + " warning: ignored suspicious cached remote response reason="
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
        logRemoteWireRequest(request, tokenGeneration, authRetryUsed);

        if (!remoteEnabled) {
            if (!chain.deliveredCachedSynced()) {
                fetchNativeThenLrclib(context, track, generation, callback, 0,
                        "Remote source disabled", chain, hasToken, nativeEnabled, lrclibEnabled);
            }
            return;
        }
        remoteHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (chain.deliveredCachedSynced()) return;
                fetchNativeThenLrclib(context, track, generation, callback, 0,
                        (authRetryUsed && e instanceof SpicyCircuitBreaker.Suppressed
                                ? "Apple Music auth rejected HTTP 401" : "Apple Music upstream-error status 0: " + e.getMessage()), chain, hasToken,
                        nativeEnabled, lrclibEnabled);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (chain.deliveredCachedSynced()) return;
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                (response.code() == 429 ? "Apple Music rate-limited HTTP 429" : "Apple Music upstream-error HTTP " + response.code()), chain, hasToken,
                                nativeEnabled, lrclibEnabled);
                        return;
                    }
                    String raw = response.body().string();
                    logRemoteWireResponse(response.code(), raw);
                    // M3: an HTTP-200 envelope can still carry an inner result status of 401.
                    // Check the raw body first (the auth-error shape has no lyrics data, so the
                    // parser would only throw), then the parsed document (covers packed payloads
                    // whose status is invisible to a plain scan). Only token-bearing requests are
                    // treated as auth rejections; anonymous rejections have no generation to
                    // invalidate and must not retry.
                    JsonElement envelope = JsonParser.parseString(raw);
                    boolean isEnveloped = envelope.isJsonObject() && envelope.getAsJsonObject().has("queries");
                    Integer authRejection = isEnveloped && hasUsableToken(sendToken, accessToken)
                            ? innerRemoteAuthRejectionStatus(raw) : null;
                    JsonObject queryResult = isEnveloped ? SpicyQueryEnvelope.result(envelope) : null;
                    String queryFailure = isEnveloped ? SpicyNetworkDiagnostics.recordEnvelope(envelope, queryResult) : null;
                    if (isEnveloped && authRejection == null && (queryResult == null || queryFailure != null)) {
                        if (!chain.deliveredCachedSynced()) fetchNativeThenLrclib(context, track, generation,
                                callback, 0, queryFailure == null ? "Apple Music operation 0 missing" : queryFailure, chain, hasToken,
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
                                    "Apple Music parse failed: " + parseErr.getMessage(), chain, hasToken,
                                    nativeEnabled, lrclibEnabled);
                            return;
                        }
                        if (hasUsableToken(sendToken, accessToken) && isInnerRemoteAuthRejection(doc)) {
                            authRejection = doc.spicyQueryStatus;
                        }
                    }
                    if (authRejection != null) {
                        handleRemoteAuthRejection(context, track, generation, sendToken,
                                accessToken, tokenGeneration, authRecovery, authRetryUsed, callback,
                                chain, hasToken, authRejection, remoteEnabled, nativeEnabled, lrclibEnabled);
                        return;
                    }
                    LyricsProviderChain.Decision decision = chain.acceptRemoteNetwork(doc, raw);
                    if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                    if (doc.lines.isEmpty()) {
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Apple Music lyrics empty", chain, hasToken, nativeEnabled, lrclibEnabled);
                        return;
                    }
                    if (doc.spicyPoisoned) {
                        XpLog.log(TAG + " warning: rejected suspicious remote response reason="
                                + safe(doc.spicyQualityReason)
                                + " status=" + (doc.spicyQueryStatus == null ? "unknown" : doc.spicyQueryStatus)
                                + " format=" + safe(doc.spicyFormat)
                                + " packed=" + doc.spicyPackedPayload
                                + " type=" + safe(doc.type));
                        fetchNativeThenLrclib(context, track, generation, callback, 0,
                                "Apple Music response suspicious: " + safe(doc.spicyQualityReason), chain, hasToken,
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
                    fetchNativeThenLrclib(context, track, generation, callback, 0, "Apple Music static", chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                } catch (java.util.concurrent.CancellationException cancelled) {
                    if (!chain.deliveredCachedSynced()) callback.onError("Apple Music request cancelled");
                } catch (IOException networkFailure) {
                    SpicyNetworkDiagnostics.recordTransport("upstream-error", 0, null);
                    if (!chain.deliveredCachedSynced()) fetchNativeThenLrclib(context, track, generation,
                            callback, 0, "Apple Music upstream-error status 0", chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                } catch (Throwable t) {
                    if (chain.deliveredCachedSynced()) return;
                    XpLog.log(TAG + " response handling failed: " + t);
                    fetchNativeThenLrclib(context, track, generation, callback, 0,
                            "Apple Music response failed: " + t.getMessage(), chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                }
            }
        });
    }

    /**
     * M3: an inner remote result status of 401 rejects the token epoch this request was issued
     * under. The coordinator-owned {@link AuthRecovery} seam tombstones exactly that generation via
     * the token store and returns a replacement authorization only when a newer generation already
     * exists. One retry maximum; with no newer generation the request proceeds to the normal
     * native/LRCLIB fallback instead of looping.
     */
    private void handleRemoteAuthRejection(
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
        XpLog.log(TAG + " inner remote auth rejection status=" + rejectionStatus
                + " tokenGeneration=" + rejectedTokenGeneration
                + (authRetryUsed ? " retryAlreadyUsed" : ""));
        Authorization replacement = resolveAfterAuthRejection(authRecovery, rejectedTokenGeneration);
        if (chain.deliveredCachedSynced()) return; // cached synced already delivered; nothing to do
        if (shouldRetryAuthorization(rejectedToken, rejectedTokenGeneration, authRetryUsed, replacement)) {
            XpLog.log(TAG + " retrying remote once with newer token generation="
                    + replacement.generation());
            fetchRemoteLyricsFallback(context, track, generation, sendToken,
                    replacement.token(), replacement.generation(), authRecovery, true, callback,
                    remoteEnabled, nativeEnabled, lrclibEnabled);
            return;
        }
        fetchNativeThenLrclib(context, track, generation, callback, 0,
                "Apple Music auth rejected HTTP " + rejectionStatus, chain, hasToken,
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
     * Scans a raw remote HTTP-200 body for an inner query-result status of 401 (the shape
     * {@code {"queries":[{"result":{"status":401,...}}]}}). Returns the rejecting status, or null
     * when the body is absent, malformed, or carries no rejecting inner status. Statuses outside
     * the query-result objects are deliberately ignored to avoid false positives.
     */
    static Integer innerRemoteAuthRejectionStatus(String raw) {
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

    /** Pure M3 seam: inner remote query status 401 on a parsed document means auth rejection. */
    static boolean isInnerRemoteAuthRejection(LyricsDocument doc) {
        return doc != null && isAuthRejectionStatus(doc.spicyQueryStatus);
    }

    private static boolean isAuthRejectionStatus(Integer status) {
        return status != null && status == 401;
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
                    LyricsDocument remoteStatic = chain.pendingStatic();
                    boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                    XpLog.log(TAG + " keeping remote static over native static score="
                            + LyricQualityRanker.score(remoteStatic) + " nativeScore=" + LyricQualityRanker.score(nativeDoc));
                    LyricsFetchDiagnosticsState.record(sourceLabel(remoteStatic, "apple_music"), chain.candidatesSeen(), remoteStatic, tokenPresent, cacheWrite);
                    callback.onSuccess(remoteStatic);
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
                LyricsDocument remoteStatic = chain.pendingStatic();
                XpLog.log(TAG + " native absent and LRCLIB disabled; delivering remote static lines="
                        + remoteStatic.lines.size());
                LyricsFetchDiagnosticsState.record(sourceLabel(remoteStatic, "apple_music"), chain.candidatesSeen(),
                        remoteStatic, tokenPresent, false);
                callback.onSuccess(remoteStatic);
                return;
            }
            XpLog.log(TAG + " native absent; probing AMLL/LRCLIB against remote static lines=" + chain.pendingStatic().lines.size());
            fetchAmllStageOrLrclib(context, track, generation, callback, reason, chain, tokenPresent,
                    lrclibEnabled, true);
            return;
        }
        if (!lrclibEnabled) {
            XpLog.log(TAG + " native lyrics miss (" + safe(reason) + "); LRCLIB disabled");
            callback.onError(reason + "; native miss, LRCLIB disabled");
            return;
        }
        XpLog.log(TAG + " native lyrics miss (" + safe(reason) + "); falling back to AMLL/LRCLIB");
        fetchAmllStageOrLrclib(context, track, generation, callback, reason, chain, tokenPresent,
                lrclibEnabled, false);
    }

    /**
     * Word-level AMLL TTML sits between native and LRCLIB: it beats held Apple statics and
     * LRCLIB lines on sync tier, but never delays them — a miss falls straight through to the
     * existing LRCLIB stage. The toggle is read here (not threaded) so a mid-fetch settings
     * change applies to the next stage, and an AMLL miss never surfaces as an error.
     */
    private void fetchAmllStageOrLrclib(Context context, SpotifyTrack track, int generation,
                                        ResultCallback callback, String reason,
                                        LyricsProviderChain chain, boolean tokenPresent,
                                        boolean lrclibEnabled, boolean rankAgainstStatic) {
        boolean amllEnabled = com.eza.spicyex.lyrics.session.LyricsSourcePreferences
                .enabledSourceOrder(context).contains(
                        com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.AMLL);
        if (!amllEnabled) {
            fetchLrclibStage(context, track, generation, callback, reason, chain, tokenPresent,
                    rankAgainstStatic);
            return;
        }
        fetchAmllDirect(context, track, generation, callback, reason, chain, tokenPresent,
                lrclibEnabled, rankAgainstStatic);
    }

    private void fetchLrclibStage(Context context, SpotifyTrack track, int generation,
                                  ResultCallback callback, String reason, LyricsProviderChain chain,
                                  boolean tokenPresent, boolean rankAgainstStatic) {
        if (rankAgainstStatic) {
            fetchLrclibWithRemoteFallback(context, track, generation, callback, reason, chain,
                    tokenPresent);
        } else {
            fetchLrclib(context, track, generation, callback, reason, chain, tokenPresent);
        }
    }

    private void fetchAmllDirect(Context context, SpotifyTrack track, int generation,
                                 ResultCallback callback, String reason, LyricsProviderChain chain,
                                 boolean tokenPresent, boolean lrclibEnabled, boolean rankAgainstStatic) {
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) {
            fetchAmllSearch(context, track, generation, callback, reason, chain, tokenPresent,
                    lrclibEnabled, rankAgainstStatic);
            return;
        }
        Request request = new Request.Builder()
                .url("https://amll-ttml-db.stevexmh.net/spotify/" + trackId + "?format=ttml")
                .get()
                .header("User-Agent", "SpotifyPlus-Mobile")
                .header("Accept", "application/xml, text/xml, text/plain;q=0.9")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fetchAmllSearch(context, track, generation, callback, reason, chain, tokenPresent,
                        lrclibEnabled, rankAgainstStatic);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (looksLikeTtml(body)) {
                            try {
                                deliverAmll(context, track, generation, callback, chain,
                                        tokenPresent, body);
                                return;
                            } catch (Throwable parseError) {
                                XpLog.log(TAG + " AMLL direct parse failed: " + parseError);
                            }
                        }
                    }
                    fetchAmllSearch(context, track, generation, callback, reason, chain,
                            tokenPresent, lrclibEnabled, rankAgainstStatic);
                }
            }
        });
    }

    private void fetchAmllSearch(Context context, SpotifyTrack track, int generation,
                                 ResultCallback callback, String reason, LyricsProviderChain chain,
                                 boolean tokenPresent, boolean lrclibEnabled, boolean rankAgainstStatic) {
        List<String> queries = new ArrayList<>();
        for (String title : LrclibQueryPlanner.queryTitles(safe(track == null ? "" : track.title))) {
            if (!isBlank(title) && !queries.contains(title)) queries.add(title);
        }
        fetchAmllSearchQuery(context, track, generation, callback, reason, chain, tokenPresent,
                lrclibEnabled, rankAgainstStatic, queries, 0);
    }

    private void fetchAmllSearchQuery(Context context, SpotifyTrack track, int generation,
                                      ResultCallback callback, String reason,
                                      LyricsProviderChain chain, boolean tokenPresent,
                                      boolean lrclibEnabled, boolean rankAgainstStatic,
                                      List<String> queries, int index) {
        if (index >= queries.size()) {
            XpLog.log(TAG + " AMLL miss; falling back to LRCLIB");
            fetchLrclibStage(context, track, generation, callback, reason, chain, tokenPresent,
                    rankAgainstStatic);
            return;
        }
        String payload = "{\"query\":\"" + queries.get(index).replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"type\":\"title\"}";
        Request request = new Request.Builder()
                .url("https://amlldb.bikonoo.com/api/search-lyrics")
                .post(okhttp3.RequestBody.create(payload,
                        okhttp3.MediaType.get("application/json; charset=utf-8")))
                .header("User-Agent", "SpotifyPlus-Mobile")
                .header("Accept", "application/json")
                .build();
        final int nextIndex = index + 1;
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fetchAmllSearchQuery(context, track, generation, callback, reason, chain,
                        tokenPresent, lrclibEnabled, rankAgainstStatic, queries, nextIndex);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String file = matchAmllSearchResult(response.body().string(), track);
                        if (!isBlank(file)) {
                            fetchAmllRaw(context, track, generation, callback, reason, chain,
                                    tokenPresent, lrclibEnabled, rankAgainstStatic, queries,
                                    nextIndex, file);
                            return;
                        }
                    }
                    fetchAmllSearchQuery(context, track, generation, callback, reason, chain,
                            tokenPresent, lrclibEnabled, rankAgainstStatic, queries, nextIndex);
                }
            }
        });
    }

    private void fetchAmllRaw(Context context, SpotifyTrack track, int generation,
                              ResultCallback callback, String reason, LyricsProviderChain chain,
                              boolean tokenPresent, boolean lrclibEnabled, boolean rankAgainstStatic,
                              List<String> queries, int nextIndex, String file) {
        Request request = new Request.Builder()
                .url("https://amlldb.bikonoo.com/raw-lyrics/" + file)
                .get()
                .header("User-Agent", "SpotifyPlus-Mobile")
                .header("Accept", "application/xml, text/xml, text/plain;q=0.9")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fetchAmllSearchQuery(context, track, generation, callback, reason, chain,
                        tokenPresent, lrclibEnabled, rankAgainstStatic, queries, nextIndex);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (looksLikeTtml(body)) {
                            try {
                                deliverAmll(context, track, generation, callback, chain,
                                        tokenPresent, body);
                                return;
                            } catch (Throwable parseError) {
                                XpLog.log(TAG + " AMLL raw parse failed: " + parseError);
                            }
                        }
                    }
                    fetchAmllSearchQuery(context, track, generation, callback, reason, chain,
                            tokenPresent, lrclibEnabled, rankAgainstStatic, queries, nextIndex);
                }
            }
        });
    }

    private void deliverAmll(Context context, SpotifyTrack track, int generation,
                             ResultCallback callback, LyricsProviderChain chain,
                             boolean tokenPresent, String ttml) {
        LyricsDocument doc = parser.parseAmllTtml(context, track, ttml);
        doc.generation = generation;
        if (doc.lines.isEmpty()) throw new IllegalStateException("AMLL lyrics empty");
        LyricsProviderChain.Decision decision = chain.acceptAmll(doc);
        if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
        if (decision.action == LyricsProviderChain.Action.DELIVER) {
            boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
            if (decision.document != doc && decision.document != null) {
                XpLog.log(TAG + " keeping remote static over AMLL score="
                        + LyricQualityRanker.score(decision.document) + " amllScore="
                        + LyricQualityRanker.score(doc));
            } else {
                XpLog.log(TAG + " using AMLL lyrics type=" + doc.type
                        + " lines=" + doc.lines.size());
            }
            LyricsDocument delivered = decision.document != null ? decision.document : doc;
            LyricsFetchDiagnosticsState.record(sourceLabel(delivered, "amll"),
                    chain.candidatesSeen(), delivered, tokenPresent, cacheWrite);
            callback.onSuccess(delivered);
            return;
        }
        throw new IllegalStateException("AMLL candidate rejected");
    }

    /** First usable search hit: title match required, artist overlap preferred, file present. */
    static String matchAmllSearchResult(String body, SpotifyTrack track) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Throwable bad) {
            return "";
        }
        if (root == null || !root.isJsonArray()) return "";
        String title = track == null ? "" : safe(track.title).toLowerCase(java.util.Locale.US);
        String artist = track == null ? "" : safe(track.artist).toLowerCase(java.util.Locale.US);
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject result = element.getAsJsonObject();
            String file = Json.optString(result, "file");
            if (isBlank(file) || file.contains("..") || file.contains("/")) continue;
            List<String> titles = new ArrayList<>();
            titles.add(Json.optString(result, "title"));
            com.google.gson.JsonArray more = Json.optArray(result, "titles");
            if (more != null) {
                for (JsonElement extra : more) {
                    if (extra.isJsonPrimitive()) titles.add(extra.getAsString());
                }
            }
            boolean titleHit = false;
            for (String candidate : titles) {
                String c = safe(candidate).toLowerCase(java.util.Locale.US);
                if (c.isEmpty() || title.isEmpty()) continue;
                if (c.equals(title) || c.contains(title) || title.contains(c)) {
                    titleHit = true;
                    break;
                }
            }
            if (!titleHit) continue;
            List<String> artists = new ArrayList<>();
            artists.add(Json.optString(result, "artist"));
            com.google.gson.JsonArray moreArtists = Json.optArray(result, "artists");
            if (moreArtists != null) {
                for (JsonElement extra : moreArtists) {
                    if (extra.isJsonPrimitive()) artists.add(extra.getAsString());
                }
            }
            boolean artistHit = artist.isEmpty();
            for (String candidate : artists) {
                String c = safe(candidate).toLowerCase(java.util.Locale.US);
                if (c.isEmpty()) continue;
                if (c.equals(artist) || c.contains(artist) || artist.contains(c)) {
                    artistHit = true;
                    break;
                }
            }
            if (artistHit) return file.trim();
        }
        return "";
    }

    static boolean looksLikeTtml(String body) {
        return body != null && body.matches("(?s)\\s*(<\\?xml[^>]*>\\s*)?<tt[\\s>].*");
    }

    private void fetchLrclibWithRemoteFallback(Context context, SpotifyTrack track, int generation,
                                               ResultCallback callback, String reason, LyricsProviderChain chain,
                                               boolean tokenPresent) {
        fetchLrclib(context, track, generation, new ResultCallback() {
            @Override
            public void onSuccess(LyricsDocument lrclibDoc) {
                LyricsProviderChain.Decision decision = chain.acceptLrclib(lrclibDoc);
                LyricsDocument remoteStatic = chain.pendingStatic();
                if (decision.document == lrclibDoc) {
                    XpLog.log(TAG + " using LRCLIB lyrics over remote static type=" + lrclibDoc.type
                            + " lines=" + lrclibDoc.lines.size()
                            + " score=" + LyricQualityRanker.score(lrclibDoc)
                            + " remoteScore=" + LyricQualityRanker.score(remoteStatic));
                    LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), lrclibDoc, tokenPresent, false);
                    callback.onSuccess(lrclibDoc);
                    return;
                }
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                XpLog.log(TAG + " native/LRCLIB lower ranked; delivering remote static lines="
                        + remoteStatic.lines.size() + " score=" + LyricQualityRanker.score(remoteStatic));
                LyricsFetchDiagnosticsState.record(sourceLabel(remoteStatic, "apple_music"), chain.candidatesSeen(), remoteStatic, tokenPresent, cacheWrite);
                callback.onSuccess(remoteStatic);
            }

            @Override
            public void onError(String error) {
                LyricsProviderChain.Decision decision = chain.acceptLrclibError(error);
                if (decision.action == LyricsProviderChain.Action.SUPPRESS) return;
                LyricsDocument remoteStatic = chain.pendingStatic();
                boolean cacheWrite = cacheChosenRaw(context, track, decision.rawToCache);
                XpLog.log(TAG + " LRCLIB miss; delivering remote static lines=" + remoteStatic.lines.size());
                LyricsFetchDiagnosticsState.record(sourceLabel(remoteStatic, "apple_music"), chain.candidatesSeen(), remoteStatic, tokenPresent, cacheWrite);
                callback.onSuccess(remoteStatic);
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

    // --- Musixmatch (ported from Lyricify Lyrics Helper's Musixmatch provider, Apache-2.0) ---
    // The Android app's API: it serves the word-level "richsync" body, and unlike the desktop
    // API is not known to answer with a different track's lyrics.
    private static final String MXM_BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String MXM_APP_ID = "android-player-v1.0";
    private static final String MXM_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13)";
    private static final long MXM_CAPTCHA_BACKOFF_MS = 10 * 60 * 1000L;
    private static volatile String mxmToken;
    private static volatile long mxmBlockedUntilMs;

    private void fetchMusixmatch(Context context, SpotifyTrack track, int generation,
                                 ResultCallback callback) {
        if (System.currentTimeMillis() < mxmBlockedUntilMs) {
            callback.onError("Musixmatch paused after a captcha");
            return;
        }
        ioScheduler.execute(() -> {
            try {
                JsonObject found = mxmFindTrack(track);
                if (found == null) {
                    callback.onError("Musixmatch: no matching track");
                    return;
                }
                long trackId = found.get("track_id").getAsLong();
                String body = mxmGet("macro.subtitles.get?namespace=lyrics_richsynched"
                        + "&optional_calls=track.richsync&subtitle_format=lrc"
                        + "&track_id=" + trackId + "&f_subtitle_length_max_deviation=40");
                if (body == null) {
                    callback.onError("Musixmatch: no lyrics response");
                    return;
                }
                LyricsDocument document = parser.parseMusixmatchLyrics(context, track, body);
                if (document == null || document.lines.isEmpty()) {
                    callback.onError("Musixmatch empty");
                    return;
                }
                callback.onSuccess(document);
            } catch (Throwable t) {
                callback.onError("Musixmatch failed: " + safe(t.getMessage()));
            }
        });
    }

    /** track.search, then the first hit whose title and artist actually match and whose length
     *  is within a few seconds - Musixmatch returns loosely related tracks for most queries. */
    private JsonObject mxmFindTrack(SpotifyTrack track) throws IOException {
        StringBuilder query = new StringBuilder("track.search?page_size=10&page=1&s_track_rating=desc");
        query.append("&q_track=").append(Uri.encode(safe(track.title)));
        query.append("&q_artist=").append(Uri.encode(safe(track.artist)));
        long durationSec = Math.max(0, track.duration) / 1000L;
        if (durationSec > 0) query.append("&q_duration=").append(durationSec);
        String body = mxmGet(query.toString());
        if (body == null) return null;
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement listElement = root.getAsJsonObject("message").getAsJsonObject("body").get("track_list");
        if (listElement == null || !listElement.isJsonArray()) return null;
        String wantTitle = mxmNormalize(track.title);
        String wantArtist = mxmNormalize(track.artist);
        for (JsonElement item : listElement.getAsJsonArray()) {
            if (!item.isJsonObject() || !item.getAsJsonObject().has("track")) continue;
            JsonObject candidate = item.getAsJsonObject().getAsJsonObject("track");
            String title = mxmNormalize(Json.optString(candidate, "track_name"));
            String artist = mxmNormalize(Json.optString(candidate, "artist_name"));
            boolean titleOk = !title.isEmpty() && (title.contains(wantTitle) || wantTitle.contains(title));
            boolean artistOk = wantArtist.isEmpty() || artist.contains(wantArtist) || wantArtist.contains(artist);
            long length = candidate.has("track_length") ? candidate.get("track_length").getAsLong() : 0L;
            boolean lengthOk = durationSec <= 0 || length <= 0 || Math.abs(length - durationSec) <= 4;
            if (titleOk && artistOk && lengthOk && candidate.has("track_id")) return candidate;
        }
        return null;
    }

    private static String mxmNormalize(String value) {
        String lower = safe(value).toLowerCase(java.util.Locale.ROOT);
        // Drop bracketed qualifiers ("(Remastered 2011)", "[feat. X]") and punctuation.
        lower = lower.replaceAll("[(\\[].*?[)\\]]", " ").replaceAll("[\\p{Punct}\\s]+", " ");
        return lower.trim();
    }

    /** One API call with the cached user token; renews it once on a 401 "renew". */
    private String mxmGet(String call) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = mxmEnsureToken();
            if (token == null) return null;
            String url = MXM_BASE + call + "&usertoken=" + Uri.encode(token) + "&format=json"
                    + "&app_id=" + MXM_APP_ID + "&t=" + java.util.UUID.randomUUID().toString().replace("-", "");
            String body = mxmHttp(url);
            if (body == null) return null;
            JsonObject header = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("message").getAsJsonObject("header");
            int status = header.has("status_code") ? header.get("status_code").getAsInt() : 0;
            String hint = Json.optString(header, "hint");
            if (status == 200 || status == 404) return body;
            if (status == 401 && "captcha".equalsIgnoreCase(hint)) {
                mxmBlockedUntilMs = System.currentTimeMillis() + MXM_CAPTCHA_BACKOFF_MS;
                return null;
            }
            if (status == 401 && "renew".equalsIgnoreCase(hint)) {
                mxmToken = null;
                continue;
            }
            return null;
        }
        return null;
    }

    private String mxmEnsureToken() throws IOException {
        String token = mxmToken;
        if (mxmTokenUsable(token)) return token;
        synchronized (LyricsRepository.class) {
            if (mxmTokenUsable(mxmToken)) return mxmToken;
            String body = mxmHttp(MXM_BASE + "token.get?user_language=en&app_id=" + MXM_APP_ID
                    + "&t=" + java.util.UUID.randomUUID().toString().replace("-", ""));
            if (body == null) return null;
            JsonObject message = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("message");
            JsonObject header = message.getAsJsonObject("header");
            if ("captcha".equalsIgnoreCase(Json.optString(header, "hint"))) {
                mxmBlockedUntilMs = System.currentTimeMillis() + MXM_CAPTCHA_BACKOFF_MS;
                return null;
            }
            JsonElement bodyElement = message.get("body");
            String fresh = bodyElement != null && bodyElement.isJsonObject()
                    ? Json.optString(bodyElement.getAsJsonObject(), "user_token") : null;
            if (!mxmTokenUsable(fresh)) return null;
            mxmToken = fresh;
            return fresh;
        }
    }

    private static boolean mxmTokenUsable(String token) {
        if (token == null || token.trim().isEmpty() || "null".equals(token)) return false;
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) != '0') return true;
        return false;
    }

    private String mxmHttp(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", MXM_USER_AGENT)
                .header("Cookie", "AWSELB=0; AWSELBCORS=0")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) return null;
            String body = response.body().string();
            return body.isEmpty() ? null : body;
        }
    }

    private void fetchLrclib(Context context, SpotifyTrack track, int generation, ResultCallback callback, String reason) {
        fetchLrclib(context, track, generation, callback, reason, new LyricsProviderChain(generation, null), false);
    }

    private void fetchLrclib(Context context, SpotifyTrack track, int generation, ResultCallback callback,
                             String reason, LyricsProviderChain chain, boolean tokenPresent) {
        // A replay of a track that already resolved through LRCLIB answers from the raw search
        // response it left behind; only an absent or unusable entry costs the network again.
        if (deliverCachedLrclib(context, track, generation, callback, chain, tokenPresent)) return;
        // Remaster-suffixed titles miss synced originals when queried verbatim, and single
        // responses mix duplicate durations (including junk). Query raw, then normalized, then
        // free-text, merging candidates in provenance order; stop early once a usable synced
        // record is in hand so popular tracks still cost one request.
        fetchLrclibVariant(context, track, generation, callback, reason, chain, tokenPresent,
                lrclibQueryUrls(track), 0, new JsonArray());
    }

    /**
     * @return true when the stored response produced lines and the callback already fired;
     *         false means the caller has to go to the network
     */
    private boolean deliverCachedLrclib(Context context, SpotifyTrack track, int generation,
                                        ResultCallback callback,
                                        LyricsProviderChain chain, boolean tokenPresent) {
        if (context == null) return false;
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) return false;
        LyricsDocument doc = parseCachedLrclibRaw(parser, context, track,
                LyricsResponseCache.getLrclib(context, trackId));
        if (doc == null) return false;
        doc.generation = generation;
        chain.acceptLrclib(doc);
        LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), doc, tokenPresent, false);
        XpLog.log(TAG + " LRCLIB cached raw hit lines=" + doc.lines.size());
        callback.onSuccess(doc);
        return true;
    }

    /**
     * Parses a stored LRCLIB raw payload into a deliverable document.
     *
     * <p>Usability is decided here rather than by trusting the cache: a missing, corrupt, or
     * line-less entry returns null so the network path still runs underneath it instead of
     * reporting an LRCLIB failure for data the provider no longer stands behind.
     */
    static LyricsDocument parseCachedLrclibRaw(Parser parser, Context context,
                                               SpotifyTrack track, String raw) {
        if (parser == null || isBlank(raw)) return null;
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray() || root.getAsJsonArray().size() == 0) return null;
            LyricsDocument doc = parser.parseLrclibLyrics(context, track, raw);
            if (doc == null || doc.lines == null || doc.lines.isEmpty()) return null;
            return doc;
        } catch (Throwable t) {
            XpLog.log(TAG + " LRCLIB cached raw unusable", t);
            return null;
        }
    }

    /** Writes the merged search response that LRCLIB answered this track with. Never throws. */
    private static void cacheLrclibRaw(Context context, SpotifyTrack track, JsonArray merged) {
        if (context == null || merged == null) return;
        String trackId = trackIdFromUri(track == null ? "" : track.uri);
        if (trackId.isEmpty()) return;
        try {
            LyricsResponseCache.putLrclib(context, trackId, merged.toString());
        } catch (Throwable t) {
            // A cache miss only costs the network again; it must not discard a document that
            // has already parsed.
            XpLog.log(TAG + " LRCLIB raw cache write failed", t);
        }
    }

    private static List<String> lrclibQueryUrls(SpotifyTrack track) {
        List<String> urls = new ArrayList<>();
        for (String title : LrclibQueryPlanner.queryTitles(safe(track.title))) {
            urls.add("https://lrclib.net/api/search?track_name="
                    + Uri.encode(title)
                    + "&artist_name=" + Uri.encode(safe(track.artist))
                    + "&album_name=" + Uri.encode(safe(track.album)));
        }
        String freeText = LrclibQueryPlanner.freeTextQuery(safe(track.title), safe(track.artist));
        if (!freeText.isEmpty()) urls.add("https://lrclib.net/api/search?q=" + Uri.encode(freeText));
        return urls;
    }

    private void fetchLrclibVariant(Context context, SpotifyTrack track, int generation,
                                    ResultCallback callback, String reason, LyricsProviderChain chain,
                                    boolean tokenPresent, List<String> urls, int index, JsonArray merged) {
        if (index >= urls.size()) {
            if (merged.size() == 0) {
                reportLrclibError(chain, callback, reason + "; LRCLIB empty");
            } else {
                deliverMergedLrclib(context, track, generation, callback, reason, chain,
                        tokenPresent, merged);
            }
            return;
        }
        Request request = new Request.Builder()
                .url(urls.get(index))
                .get()
                .header("User-Agent", "SpotifyPlus MobileLyrics/1.1")
                .build();
        final int nextIndex = index + 1;
        final boolean lastVariant = nextIndex >= urls.size();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (lastVariant && merged.size() == 0) {
                    reportLrclibError(chain, callback, reason + "; LRCLIB failed: " + e.getMessage());
                } else {
                    scheduleLrclibVariant(context, track, generation, callback, reason, chain,
                            tokenPresent, urls, nextIndex, merged);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        appendLrclibCandidates(merged, response.body().string());
                        if (hasUsableSyncedLrclib(merged, track)) {
                            deliverMergedLrclib(context, track, generation, callback, reason,
                                    chain, tokenPresent, merged);
                            return;
                        }
                    }
                    if (lastVariant) {
                        if (merged.size() == 0) {
                            reportLrclibError(chain, callback, reason + "; LRCLIB HTTP "
                                    + response.code());
                        } else {
                            deliverMergedLrclib(context, track, generation, callback, reason,
                                    chain, tokenPresent, merged);
                        }
                    } else {
                        scheduleLrclibVariant(context, track, generation, callback, reason, chain,
                                tokenPresent, urls, nextIndex, merged);
                    }
                } catch (Throwable t) {
                    XpLog.log(TAG + " LRCLIB variant failed: " + t);
                    if (lastVariant && merged.size() == 0) {
                        reportLrclibError(chain, callback, reason + "; LRCLIB parse failed: "
                                + t.getMessage());
                    } else if (!lastVariant) {
                        scheduleLrclibVariant(context, track, generation, callback, reason, chain,
                                tokenPresent, urls, nextIndex, merged);
                    } else {
                        deliverMergedLrclib(context, track, generation, callback, reason, chain,
                                tokenPresent, merged);
                    }
                }
            }
        });
    }

    /**
     * NetEase Cloud Music, added as an extra opt-in source (Lyricify-style: more candidate
     * catalogs means fewer tracks with no synced lyrics at all). Uses NetEase's legacy
     * {@code /api/search/get} and {@code /api/song/lyric} routes, which still answer with plain
     * JSON — unlike their newer {@code /weapi/...} routes, these don't require request encryption,
     * so no auth/signing is needed. Best-effort only: on any failure this simply reports an error
     * and the caller (manual "strict" pick, or Source-order ranking) moves on.
     */
    private void fetchNetease(Context context, SpotifyTrack track, int generation, ResultCallback callback) {
        String query = (safe(track == null ? null : track.title) + " "
                + safe(track == null ? null : track.artist)).trim();
        // limit=20, not 8: NetEase lists a song's reissues and regional editions as separate hits
        // and only some of them carry word-level lyrics, so a short page regularly cut off the
        // only entry that had any. Matches the page size Lyricify uses.
        String url = "https://music.163.com/api/search/get?s=" + Uri.encode(query)
                + "&type=1&offset=0&limit=20";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("NetEase search failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("NetEase search HTTP " + response.code());
                        return;
                    }
                    java.util.List<NeteaseSongRanker.Candidate> songs =
                            rankNeteaseSongs(response.body().string(), track);
                    if (songs.isEmpty()) {
                        callback.onError("NetEase empty");
                        return;
                    }
                    fetchNeteaseLyric(context, track, generation, songs, callback);
                } catch (Throwable t) {
                    callback.onError("NetEase search parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    /** Tries NetEase's word-level ("YRC") endpoint across the ranked hits, then falls back to the
     *  plain line-level one for the best hit. Word-level content is per-recording and is only
     *  discoverable by asking, so the walk is what turns "this track has karaoke timing somewhere"
     *  into actually rendering it. A YRC-specific failure is never surfaced; the fallback IS the
     *  error handling, exactly as on the QQ side. */
    private void fetchNeteaseLyric(Context context, SpotifyTrack track, int generation,
                                    java.util.List<NeteaseSongRanker.Candidate> songs,
                                    ResultCallback callback) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (NeteaseSongRanker.Candidate candidate : songs) {
            if (!candidate.supportsWordLyrics()) continue;
            if (!ids.contains(candidate.id)) ids.add(candidate.id);
            if (ids.size() >= NeteaseSongRanker.MAX_WORD_LYRIC_ATTEMPTS) break;
        }
        String lineId = String.valueOf(songs.get(0).id);
        Runnable lineFallback =
                () -> fetchNeteaseLyricById(context, track, generation, lineId, callback);
        if (ids.isEmpty()) {
            lineFallback.run();
            return;
        }
        tryNeteaseWordLyricChain(context, track, generation, ids, 0, callback, lineFallback);
    }

    private void tryNeteaseWordLyricChain(Context context, SpotifyTrack track, int generation,
                                           java.util.List<Long> ids, int index,
                                           ResultCallback callback, Runnable lineFallback) {
        if (index >= ids.size()) {
            lineFallback.run();
            return;
        }
        fetchNeteaseWordLyric(context, track, generation, ids.get(index), callback,
                () -> tryNeteaseWordLyricChain(context, track, generation, ids, index + 1,
                        callback, lineFallback));
    }

    /** NetEase's word-level lyrics are only served over their signed "eapi" transport - the plain
     *  web endpoint below returns line-level LRC and nothing else. See {@link NeteaseEapi}. */
    private void fetchNeteaseWordLyric(Context context, SpotifyTrack track, int generation,
                                        long songId, ResultCallback callback, Runnable fallback) {
        String apiPath = "/api/song/lyric/v1";
        String payload = "{\"id\":\"" + songId + "\",\"cp\":\"false\",\"lv\":\"0\",\"kv\":\"0\","
                + "\"tv\":\"0\",\"rv\":\"0\",\"yv\":\"0\",\"ytv\":\"0\",\"yrv\":\"0\","
                + "\"csrf_token\":\"\",\"header\":" + NeteaseEapi.headerJson() + "}";
        String params = NeteaseEapi.params(apiPath, payload);
        if (params == null) {
            fallback.run();
            return;
        }
        Request request = new Request.Builder()
                .url("https://interface3.music.163.com/eapi/song/lyric/v1")
                .post(new okhttp3.FormBody.Builder().add("params", params).build())
                .header("User-Agent", NeteaseEapi.USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", NeteaseEapi.cookieHeader())
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fallback.run();
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fallback.run();
                        return;
                    }
                    LyricsDocument doc = parser.parseNeteaseWordLyrics(
                            context, track, response.body().string());
                    if (doc == null || doc.lines.isEmpty()) {
                        fallback.run();
                        return;
                    }
                    doc.generation = generation;
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    fallback.run();
                }
            }
        });
    }

    private void fetchNeteaseLyricById(Context context, SpotifyTrack track, int generation,
                                       String songId, ResultCallback callback) {
        String url = "https://music.163.com/api/song/lyric?id=" + Uri.encode(songId) + "&lv=1&kv=1&tv=1";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("NetEase lyric failed: " + safe(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("NetEase lyric HTTP " + response.code());
                        return;
                    }
                    LyricsDocument doc = parser.parseNeteaseLyrics(context, track, response.body().string());
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        callback.onError("NetEase empty");
                        return;
                    }
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    callback.onError("NetEase parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    /** Every acceptable search hit, best first - see {@link NeteaseSongRanker}. The old version of
     *  this picked purely on runtime closeness with a flat penalty for a mismatched artist and
     *  compared the title not at all, so any song of roughly the right length won. */
    private static java.util.List<NeteaseSongRanker.Candidate> rankNeteaseSongs(
            String body, SpotifyTrack track) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return java.util.Collections.emptyList();
        JsonObject result = Json.optObject(root.getAsJsonObject(), "result");
        JsonArray songs = result == null ? null : Json.optArray(result, "songs");
        return NeteaseSongRanker.rank(songs,
                track == null ? null : track.title,
                track == null ? null : track.artist,
                track == null ? null : track.album,
                track == null ? 0 : track.duration);
    }


    // --- QQ Music ---

    private void fetchQqMusic(Context context, SpotifyTrack track, int generation, ResultCallback callback) {
        fetchQqMusic(context, track, generation, callback, 0);
    }

    private void fetchQqMusic(Context context, SpotifyTrack track, int generation, ResultCallback callback,
                               int retryCount) {
        String query = (safe(track == null ? null : track.title) + " "
                + safe(track == null ? null : track.artist)).trim();
        String data = "{\"music.search.SearchCgiService\":{\"method\":\"DoSearchForQQMusicDesktop\","
                + "\"module\":\"music.search.SearchCgiService\","
                // 20, not 10: QQ lists a song's other pressings as separate hits (and as nested
                // "grp" entries under them), and word-level lyrics are attached per hit, so a
                // short page regularly cut off the only entry that had any.
                + "\"param\":{\"num_per_page\":\"20\",\"page_num\":\"1\","
                + "\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"search_type\":\"0\"}}}";
        Request request = new Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(RequestBody.create(data, MediaType.parse("application/json")))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("QQ Music search failed: " + safe(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("QQ Music search HTTP " + response.code());
                        return;
                    }
                    String body = response.body().string();
                    int searchCode = qqSearchServiceCode(body);
                    java.util.List<QqSongRanker.Candidate> songs = searchCode == 0
                            ? rankQqSongs(body, track) : java.util.Collections.emptyList();
                    if (songs.isEmpty()) {
                        if (searchCode != 0 && retryCount < QQ_SEARCH_RETRY_LIMIT) {
                            ioScheduler.schedule(
                                    () -> fetchQqMusic(context, track, generation, callback, retryCount + 1),
                                    QQ_SEARCH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                            return;
                        }
                        callback.onError("QQ Music empty" + (searchCode != 0 ? " (code " + searchCode + ")" : ""));
                        return;
                    }
                    fetchQqMusicLyric(context, track, generation, songs, callback);
                } catch (Throwable t) {
                    callback.onError("QQ Music search parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    /** Non-zero here means the search cgi rejected the request itself (commonly 2001, QQ's
     *  anti-abuse throttle) rather than genuinely finding no match - distinct from a normal,
     *  real empty result which reports 0. */
    private static int qqSearchServiceCode(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return 0;
            JsonObject service = Json.optObject(root.getAsJsonObject(), "music.search.SearchCgiService");
            return service == null ? 0 : (int) Json.optDouble(service, 0d, "code");
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Tries QQ's word-level ("QRC") endpoint first - the same karaoke-timed data Lyricify uses -
     *  and falls back to the plain line-level LRC endpoint whenever no acceptable hit has a numeric
     *  id, every word-level request fails, or the decrypt/parse comes back empty (private/VIP-only
     *  tracks, format drift, etc.). Never surfaces a QRC-specific error to the caller; the fallback
     *  IS the error handling.
     *
     *  <p>The word-level attempt walks the ranked hits rather than stopping at the best one. QQ
     *  lists the same song repeatedly (album cut, single, live, regional release) and word-level
     *  content is attached per entry, so the top hit regularly has none while an equally-valid
     *  sibling does. Giving up after one attempt is what left tracks that do have karaoke timing
     *  rendering as a plain line-synced document. */
    private void fetchQqMusicLyric(Context context, SpotifyTrack track, int generation,
                                    java.util.List<QqSongRanker.Candidate> songs, ResultCallback callback) {
        java.util.List<Long> wordIds = new java.util.ArrayList<>();
        for (QqSongRanker.Candidate candidate : songs) {
            if (!candidate.supportsWordLyrics()) continue;
            if (!wordIds.contains(candidate.id)) wordIds.add(candidate.id);
            if (wordIds.size() >= QqSongRanker.MAX_WORD_LYRIC_ATTEMPTS) break;
        }
        String lineMid = songs.get(0).mid;
        Runnable lineFallback = () -> fetchQqLineLyric(context, track, generation, lineMid, callback);
        if (wordIds.isEmpty()) {
            lineFallback.run();
            return;
        }
        tryQqWordLyricChain(context, track, generation, wordIds, 0, callback, lineFallback);
    }

    private void tryQqWordLyricChain(Context context, SpotifyTrack track, int generation,
                                      java.util.List<Long> ids, int index, ResultCallback callback,
                                      Runnable lineFallback) {
        if (index >= ids.size()) {
            lineFallback.run();
            return;
        }
        fetchQqWordLyric(context, track, generation, ids.get(index), callback,
                () -> tryQqWordLyricChain(context, track, generation, ids, index + 1, callback,
                        lineFallback));
    }

    private void fetchQqWordLyric(Context context, SpotifyTrack track, int generation, long songId,
                                   ResultCallback callback, Runnable fallback) {
        RequestBody form = new okhttp3.FormBody.Builder()
                .add("version", "15")
                .add("miniversion", "82")
                .add("lrctype", "4")
                .add("musicid", String.valueOf(songId))
                .build();
        Request request = new Request.Builder()
                .url("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg")
                .post(form)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://c.y.qq.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fallback.run();
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        fallback.run();
                        return;
                    }
                    String raw = response.body().string();
                    LyricsDocument doc = parser.parseQqWordLyrics(context, track, raw);
                    if (doc == null || doc.lines.isEmpty()) {
                        fallback.run();
                        return;
                    }
                    doc.generation = generation;
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    fallback.run();
                }
            }
        });
    }

    private void fetchQqLineLyric(Context context, SpotifyTrack track, int generation,
                                    String songMid, ResultCallback callback) {
        String callbackName = "MusicJsonCallback_lrc";
        long pcachetime = System.currentTimeMillis();
        String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                + "?callback=" + callbackName
                + "&pcachetime=" + pcachetime
                + "&songmid=" + Uri.encode(songMid)
                + "&g_tk=5381&jsonpCallback=" + callbackName
                + "&loginUin=0&hostUin=0&format=jsonp&inCharset=utf8&outCharset=utf8"
                + "&notice=0&platform=yqq&needNewCode=0";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("QQ Music lyric failed: " + safe(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("QQ Music lyric HTTP " + response.code());
                        return;
                    }
                    String raw = response.body().string();
                    // Strip JSONP wrapper
                    if (raw.startsWith(callbackName + "(")) {
                        raw = raw.substring(callbackName.length() + 1);
                        if (raw.endsWith(")")) raw = raw.substring(0, raw.length() - 1);
                    }
                    LyricsDocument doc = parser.parseQqMusicLyrics(context, track, raw);
                    doc.generation = generation;
                    if (doc.lines.isEmpty()) {
                        callback.onError("QQ Music empty");
                        return;
                    }
                    callback.onSuccess(doc);
                } catch (Throwable t) {
                    callback.onError("QQ Music lyric parse failed: " + safe(t.getMessage()));
                }
            }
        });
    }

    /** Every acceptable search hit, best first - see {@link QqSongRanker} for how they are ordered
     *  and which are rejected outright. */
    private static java.util.List<QqSongRanker.Candidate> rankQqSongs(String body, SpotifyTrack track) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return java.util.Collections.emptyList();
        // Response is nested under music.search.SearchCgiService.data.body.song.list
        JsonObject obj = root.getAsJsonObject();
        JsonObject service = Json.optObject(obj, "music.search.SearchCgiService");
        JsonObject data = service == null ? null : Json.optObject(service, "data");
        JsonObject bodyObj = data == null ? null : Json.optObject(data, "body");
        JsonObject songObj = bodyObj == null ? null : Json.optObject(bodyObj, "song");
        JsonArray list = songObj == null ? null : Json.optArray(songObj, "list");
        return QqSongRanker.rank(list,
                track == null ? null : track.title,
                track == null ? null : track.artist,
                track == null ? null : track.album,
                track == null ? 0 : track.duration);
    }

    private void scheduleLrclibVariant(Context context, SpotifyTrack track, int generation,
                                       ResultCallback callback, String reason,
                                       LyricsProviderChain chain, boolean tokenPresent,
                                       List<String> urls, int nextIndex, JsonArray merged) {
        try {
            ioScheduler.schedule(() -> fetchLrclibVariant(context, track, generation, callback,
                            reason, chain, tokenPresent, urls, nextIndex, merged),
                    LrclibQueryPlanner.FOLLOW_UP_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable scheduled) {
            fetchLrclibVariant(context, track, generation, callback, reason, chain, tokenPresent,
                    urls, nextIndex, merged);
        }
    }

    private static void appendLrclibCandidates(JsonArray merged, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonObject()) merged.add(element.getAsJsonObject());
            }
        } else if (root.isJsonObject()) {
            merged.add(root.getAsJsonObject());
        }
    }

    private static boolean hasUsableSyncedLrclib(JsonArray merged, SpotifyTrack track) {
        List<JsonObject> candidates = new ArrayList<>(merged.size());
        for (JsonElement element : merged) {
            if (element.isJsonObject()) candidates.add(element.getAsJsonObject());
        }
        double trackDurationSec = track == null || track.duration <= 0 ? -1d : track.duration / 1000d;
        int pick = LrclibQueryPlanner.pickBest(candidates, trackDurationSec);
        return pick >= 0 && !isBlank(Json.optString(candidates.get(pick), "syncedLyrics"));
    }

    /** Package-private so the one-request-one-callback contract is testable. */
    void deliverMergedLrclib(Context context, SpotifyTrack track, int generation,
                             ResultCallback callback, String reason,
                             LyricsProviderChain chain, boolean tokenPresent, JsonArray merged) {
        // One request, one callback. Parsing decides success vs error; consumer delivery happens
        // outside the parsing catch so a throwing consumer can never trigger a second callback,
        // nor let its exception escape as a parse failure.
        LyricsDocument doc;
        try {
            doc = parser.parseLrclibLyrics(context, track, merged.toString());
            doc.generation = generation;
            if (doc.lines.isEmpty()) {
                reportLrclibError(chain, callback, reason + "; LRCLIB empty");
                return;
            }
            cacheLrclibRaw(context, track, merged);
            chain.acceptLrclib(doc);
        } catch (Throwable t) {
            XpLog.log(TAG + " LRCLIB delivery failed: " + t);
            reportLrclibError(chain, callback, reason + "; LRCLIB parse failed: " + t.getMessage());
            return;
        }
        try {
            LyricsFetchDiagnosticsState.record("lrclib", chain.candidatesSeen(), doc, tokenPresent, false);
        } catch (Throwable ignored) {
        }
        try {
            callback.onSuccess(doc);
        } catch (Throwable t) {
            XpLog.log(TAG + " LRCLIB success consumer threw: " + t);
        }
    }

    private static void reportLrclibError(LyricsProviderChain chain, ResultCallback callback, String error) {
        try {
            if (chain != null && !chain.hasPendingStatic()) {
                try {
                    chain.acceptLrclibError(error);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            callback.onError(error);
        } catch (Throwable t) {
            XpLog.log(TAG + " LRCLIB error consumer threw: " + t);
        }
    }

    private static final boolean WIRE_DEBUG_CAPTURE = false;

    private static void logRemoteWireRequest(Request request, int generation, boolean retry) {
        if (!WIRE_DEBUG_CAPTURE || request == null) return;
        try {
            XpLog.log(TAG + " remote request url=" + request.url());
        } catch (Throwable ignored) { }
    }

    private static void logRemoteWireResponse(int status, String raw) {
        if (!WIRE_DEBUG_CAPTURE) return;
        XpLog.log(TAG + " remote response status=" + status + " bytes=" + (raw == null ? 0 : raw.length()));
    }

    static boolean hasUsableToken(boolean sendToken, String accessToken) {
        return sendToken && !isBlank(accessToken) && !"0".equals(accessToken);
    }

    private static final String LENERD_ENDPOINT_URL = "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/";

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
        if (source.contains("netease")) return "netease";
        if (source.contains("qq")) return "qq_music";
        if (source.contains("musixmatch")) return "musixmatch";
        if (source.contains("amll")) return "amll";
        if (source.contains("native")) return "native";
        if (source.contains("spicy")) return "apple_music";
        if (source.contains("apple_music")) return "apple_music";
        return fallback;
    }

    public interface ResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

    /**
     * M3 seam implemented hook-side (the lyrics layer must never depend on hooks). Reports that an
     * inner remote 401 rejected the request issued under {@code rejectedTokenGeneration}; the
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
        LyricsDocument parseNeteaseLyrics(Context context, SpotifyTrack track, String body);

        /** @return null when the response has no word-level ("YRC") content to use. */
        LyricsDocument parseNeteaseWordLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseQqMusicLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseQqWordLyrics(Context context, SpotifyTrack track, String body);

        /** Musixmatch macro.subtitles.get: richsync, else LRC, else plain; null if unusable. */
        LyricsDocument parseMusixmatchLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseAmllTtml(Context context, SpotifyTrack track, String ttml);
    }

    public interface NativeLyricsProvider {
        LyricsDocument getNativeLyricsDocument(SpotifyTrack track);
    }
}
