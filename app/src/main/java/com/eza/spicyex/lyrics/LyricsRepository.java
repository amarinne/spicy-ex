package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.lyrics.catalog.AcquisitionScope;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/** Fetch/fallback coordinator for remote lyrics (Apple Music + Spotify native + LRCLIB). */
public final class LyricsRepository {
    private static final String TAG = "[SpotifyPlusLyricsRepository]";
    private static final int NATIVE_LYRICS_RETRY_LIMIT = 4;
    private static final long NATIVE_LYRICS_RETRY_DELAY_MS = 125;

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

    /**
     * Automatic acquisition for one track. The catalog's {@link AcquisitionScope} names the
     * sources this visit may ask; a source the planner held back (disabled, or answered recently)
     * is never requested here. Every source commits its own outcome to the catalog.
     */
    public void fetchLyrics(
            Context context,
            SpotifyTrack track,
            int generation,
            boolean sendToken,
            String accessToken,
            int tokenGeneration,
            AuthRecovery authRecovery,
            AcquisitionScope scope,
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
        if (scope == null || scope.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        if (scope.sourceOrderMode) {
            java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> order =
                    new java.util.ArrayList<>();
            for (CatalogSource.SourceId source : scope.sources) {
                com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source mapped =
                        CatalogPolicy.preferenceSource(source);
                if (mapped != null) order.add(mapped);
            }
            fetchOrderedSources(context, track, generation, order, accessToken,
                    scope.karaokeOriginalLyrics, callback);
            return;
        }
        boolean remoteEnabled = scope.allows(CatalogSource.SourceId.APPLE);
        boolean nativeEnabled = scope.allows(CatalogSource.SourceId.SPOTIFY_NATIVE);
        boolean lrclibEnabled = scope.allows(CatalogSource.SourceId.LRCLIB);
        boolean amllEnabled = scope.allows(CatalogSource.SourceId.AMLL);
        if (!remoteEnabled && !nativeEnabled && !lrclibEnabled && !amllEnabled) {
            callback.onError("No automatic lyric source is due");
            return;
        }
        fetchRemoteLyricsFallback(context, track, generation, sendToken, accessToken,
                tokenGeneration, authRecovery, false, callback,
                remoteEnabled, nativeEnabled, lrclibEnabled, amllEnabled);
    }

    /** Fetches exactly one picker source. This path never falls through to another provider. */
    public void fetchSource(Context context, SpotifyTrack track, int generation,
                            com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source source,
                            boolean karaokeOriginalLyrics,
                            ResultCallback callback) {
        if (source == null || callback == null) return;
        fetchSingleSource(context, track, generation, labelFor(source), "",
                karaokeOriginalLyrics, callback, 0);
    }

    /** Source-order Auto: first enabled source in user order that yields lyrics wins. */
    private void fetchOrderedSources(Context context, SpotifyTrack track, int generation,
                                     java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                     String accessToken, boolean karaokeOriginalLyrics,
                                     ResultCallback callback) {
        if (enabledOrder == null || enabledOrder.isEmpty()) {
            callback.onError("All lyric sources disabled");
            return;
        }
        attemptOrderedSource(context, track, generation, enabledOrder, 0, accessToken,
                karaokeOriginalLyrics, callback);
    }

    private void attemptOrderedSource(Context context, SpotifyTrack track, int generation,
                                      java.util.List<com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source> enabledOrder,
                                      int index, String accessToken, boolean karaokeOriginalLyrics,
                                      ResultCallback callback) {
        if (index >= enabledOrder.size()) {
            callback.onError("No lyric source in order produced lyrics");
            return;
        }
        String source = labelFor(enabledOrder.get(index));
        fetchSingleSource(context, track, generation, source, accessToken,
                karaokeOriginalLyrics, new ResultCallback() {
            @Override public void onSuccess(LyricsDocument document) {
                callback.onSuccess(document);
            }
            @Override public void onError(String error) {
                if (enabledOrder.get(index) == com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.APPLE_MUSIC
                        || enabledOrder.get(index) == com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPICY) {
                    CatalogAdapters.recordError(context, CatalogSource.SourceId.APPLE, track, error);
                }
                attemptOrderedSource(context, track, generation, enabledOrder, index + 1,
                        accessToken, karaokeOriginalLyrics, callback);
            }
        }, 0);
    }

    private static String labelFor(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source source) {
        if (source == null) return "Auto";
        switch (source) {
            case APPLE_MUSIC: return "Apple Music";
            case SPICY: return "Spicy";
            case SPOTIFY: return "Spotify";
            case AMLL: return "AMLL";
            case LRCLIB: return "LRCLIB";
            case QQ: return "QQ Music";
            case NETEASE: return "NetEase";
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
                                   String source, String accessToken,
                                   boolean karaokeOriginalLyrics, ResultCallback callback,
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
                        String raw = response.body().string();
                        LyricsDocument document = parser.parseSpicyLyrics(context, track, raw, false);
                        document.fetchSource = "apple_music_lenerd";
                        document.selectedSource = source;
                        document.selectionMode = "strict";
                        document.selectionOverride = source;
                        if (document.lines.isEmpty() || LyricQualityRanker.score(document) == LyricQualityRanker.REJECT) {
                            callback.onError(source + " source unavailable: rejected candidate");
                            return;
                        }
                        CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.APPLE,
                                track, document, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID,
                                trackIdFromUri(track == null ? "" : track.uri), raw,
                                CatalogAdapters.APPLE_ADAPTER_REVISION);
                        callback.onSuccess(document);
                    } catch (Throwable parseError) {
                        callback.onError(source + " source unavailable: " + safe(parseError.getMessage()));
                    }
                }
            });
            return;
        }
        if ("Spotify".equals(source)) {
            // Explicit Spotify use is a one-shot local read: it adopts whatever lyrics Spotify
            // already fetched for this track (captured model or lyrics_db) and never performs a
            // network request or a retry wait. A miss is a terminal NOT_FOUND outcome.
            LyricsDocument document = nativeLyricsProvider.getNativeLyricsDocument(track);
            if (document != null && !document.lines.isEmpty()) {
                document.selectedSource = "Spotify";
                document.selectionMode = "strict";
                document.selectionOverride = "Spotify";
                CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.SPOTIFY_NATIVE,
                        track, document, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID,
                        trackIdFromUri(track == null ? "" : track.uri), "",
                        CatalogAdapters.SPOTIFY_NATIVE_ADAPTER_REVISION);
                callback.onSuccess(document);
            } else {
                callback.onError("Spotify has no lyrics for this track");
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
        if ("AMLL".equals(source)) {
            fetchStrictAmll(context, track, generation, callback);
            return;
        }
        if ("QQ Music".equals(source)) {
            new QqMusicAdapter(http, parser, ioScheduler).fetch(context, track, generation,
                    karaokeOriginalLyrics,
                    new ResultCallback() {
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
        if ("NetEase".equals(source)) {
            new NeteaseAdapter(http, parser).fetch(context, track, generation,
                    karaokeOriginalLyrics,
                    new ResultCallback() {
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
                                    LyricsDocument directDoc = strictAmllDocument(
                                            context, track, generation, body);
                                    CatalogAdapters.recordSuccess(context,
                                            CatalogSource.SourceId.AMLL, track, directDoc,
                                            CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, trackId,
                                            body, CatalogAdapters.AMLL_ADAPTER_REVISION);
                                    callback.onSuccess(directDoc);
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
            CatalogAdapters.recordError(context, CatalogSource.SourceId.AMLL, track,
                    "AMLL source unavailable: no match");
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
                    final String matchedFile = file;
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
                                            LyricsDocument searchDoc = strictAmllDocument(
                                                    context, track, generation, body);
                                            CatalogAdapters.recordSuccess(context,
                                                    CatalogSource.SourceId.AMLL, track, searchDoc,
                                                    CatalogSource.MatchMethod.STRONG_SEARCH,
                                                    matchedFile, body,
                                                    CatalogAdapters.AMLL_ADAPTER_REVISION);
                                            callback.onSuccess(searchDoc);
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
            boolean lrclibEnabled,
            boolean amllEnabled
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
        chain.amllAllowed = amllEnabled;
        chain.lrclibAllowed = lrclibEnabled;
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
                appleMiss(context, track, generation, callback, 0,
                        (authRetryUsed && e instanceof SpicyCircuitBreaker.Suppressed
                                ? "Apple Music auth rejected HTTP 401" : "Apple Music upstream-error status 0: " + e.getMessage()), chain, hasToken,
                        nativeEnabled, lrclibEnabled);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (chain.deliveredCachedSynced()) return;
                        appleMiss(context, track, generation, callback, 0,
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
                        if (!chain.deliveredCachedSynced()) appleMiss(context, track, generation,
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
                            appleMiss(context, track, generation, callback, 0,
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
                        appleMiss(context, track, generation, callback, 0,
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
                        appleMiss(context, track, generation, callback, 0,
                                "Apple Music response suspicious: " + safe(doc.spicyQualityReason), chain, hasToken,
                                nativeEnabled, lrclibEnabled);
                        return;
                    }
                    CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.APPLE, track,
                            doc, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, trackId, raw,
                            CatalogAdapters.APPLE_ADAPTER_REVISION);
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
                    if (!chain.deliveredCachedSynced()) appleMiss(context, track, generation,
                            callback, 0, "Apple Music upstream-error status 0", chain, hasToken,
                            nativeEnabled, lrclibEnabled);
                } catch (Throwable t) {
                    if (chain.deliveredCachedSynced()) return;
                    XpLog.log(TAG + " response handling failed: " + t);
                    appleMiss(context, track, generation, callback, 0,
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
                    remoteEnabled, nativeEnabled, lrclibEnabled, chain.amllAllowed);
            return;
        }
        appleMiss(context, track, generation, callback, 0,
                "Apple Music auth rejected HTTP " + rejectionStatus, chain, hasToken,
                nativeEnabled, lrclibEnabled);
    }

    /** Apple did not deliver: commit its outcome, then continue the fallback chain. */
    private void appleMiss(Context context, SpotifyTrack track, int generation,
                           ResultCallback callback, int nativeRetryCount, String reason,
                           LyricsProviderChain chain, boolean tokenPresent,
                           boolean nativeEnabled, boolean lrclibEnabled) {
        CatalogAdapters.recordError(context, CatalogSource.SourceId.APPLE, track, reason);
        fetchNativeThenLrclib(context, track, generation, callback, nativeRetryCount, reason,
                chain, tokenPresent, nativeEnabled, lrclibEnabled);
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
            CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.SPOTIFY_NATIVE, track,
                    nativeDoc, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID,
                    trackIdFromUri(track == null ? "" : track.uri), "",
                    CatalogAdapters.SPOTIFY_NATIVE_ADAPTER_REVISION);
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
        boolean fallbackAllowed = lrclibEnabled || chain.amllAllowed;
        if (chain.hasPendingStatic()) {
            if (!fallbackAllowed) {
                LyricsDocument remoteStatic = chain.pendingStatic();
                XpLog.log(TAG + " native absent and no fallback source due; delivering remote static lines="
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
        if (!fallbackAllowed) {
            XpLog.log(TAG + " native lyrics miss (" + safe(reason) + "); no fallback source due");
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
        if (!chain.amllAllowed) {
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
        if (!chain.lrclibAllowed) {
            // The plan held LRCLIB back (disabled, or it answered recently).
            if (rankAgainstStatic && chain.hasPendingStatic()) {
                LyricsDocument remoteStatic = chain.pendingStatic();
                LyricsFetchDiagnosticsState.record(sourceLabel(remoteStatic, "apple_music"),
                        chain.candidatesSeen(), remoteStatic, tokenPresent, false);
                callback.onSuccess(remoteStatic);
            } else {
                callback.onError(reason + "; AMLL miss, LRCLIB not due");
            }
            return;
        }
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
                                        tokenPresent, body,
                                        CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, trackId);
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
            CatalogAdapters.recordError(context, CatalogSource.SourceId.AMLL, track,
                    "AMLL no match");
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
                                        tokenPresent, body,
                                        CatalogSource.MatchMethod.STRONG_SEARCH, file);
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
                             boolean tokenPresent, String ttml,
                             CatalogSource.MatchMethod method, String providerItemId) {
        LyricsDocument doc = parser.parseAmllTtml(context, track, ttml);
        doc.generation = generation;
        if (doc.lines.isEmpty()) throw new IllegalStateException("AMLL lyrics empty");
        CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.AMLL, track, doc, method,
                providerItemId, ttml, CatalogAdapters.AMLL_ADAPTER_REVISION);
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
                CatalogAdapters.recordError(context, CatalogSource.SourceId.LRCLIB, track,
                        reason + "; LRCLIB empty");
                reportLrclibError(chain, callback, reason + "; LRCLIB empty");
                return;
            }
            cacheLrclibRaw(context, track, merged);
            chain.acceptLrclib(doc);
            CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.LRCLIB, track, doc,
                    CatalogSource.MatchMethod.STRONG_SEARCH, "", merged.toString(),
                    CatalogAdapters.LRCLIB_ADAPTER_REVISION);
        } catch (Throwable t) {
            XpLog.log(TAG + " LRCLIB delivery failed: " + t);
            CatalogAdapters.recordError(context, CatalogSource.SourceId.LRCLIB, track,
                    reason + "; LRCLIB parse failed: " + t.getMessage());
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
        LyricsDocument parseAmllTtml(Context context, SpotifyTrack track, String ttml);
        LyricsDocument parseNeteaseLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseNeteaseWordLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseQqMusicLyrics(Context context, SpotifyTrack track, String body);
        LyricsDocument parseQqWordLyrics(Context context, SpotifyTrack track, String rawResponse);
    }

    public interface NativeLyricsProvider {
        LyricsDocument getNativeLyricsDocument(SpotifyTrack track);
    }
}
