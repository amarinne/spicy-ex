package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsParser;
import com.eza.spicyex.lyrics.LyricsRepository;
import com.eza.spicyex.lyrics.NativeLyricsSource;
import com.eza.spicyex.lyrics.SpicyManualTokenStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Owns parser/native-source setup and LyricsRepository construction for hook-hosted fetches. */
final class LyricsFetchCoordinator {
    private final OkHttpClient http;
    private final int processingVersion;
    private final LyricsParser lyricsParser;
    private final NativeLyricsSource nativeLyricsSource;
    private final Object inFlightLock = new Object();
    private final Map<String, InFlightFetch> inFlight = new HashMap<>();

    LyricsFetchCoordinator(
            OkHttpClient http,
            NativeLyricsSource.ContextProvider contextProvider,
            int processingVersion
    ) {
        this.http = http;
        this.processingVersion = processingVersion;
        lyricsParser = new LyricsParser(this::finalizeParsedDocument);
        nativeLyricsSource = new NativeLyricsSource(contextProvider, this::finalizeParsedDocument);
    }

    NativeLyricsSource nativeLyricsSource() {
        return nativeLyricsSource;
    }

    /** Retires any replayable provider operation for this track before an explicit source reload. */
    void invalidate(SpotifyTrack track) {
        String uri = track == null ? "" : safe(track.uri);
        synchronized (inFlightLock) {
            for (Map.Entry<String, InFlightFetch> entry : new ArrayList<>(inFlight.entrySet())) {
                if (!entry.getKey().startsWith(uri + "|")) continue;
                InFlightFetch operation = entry.getValue();
                inFlight.remove(entry.getKey());
                if (operation.expiry != null) operation.expiry.cancel(false);
                operation.callbacks.clear();
                operation.latest = null;
                if (operation.fetchFuture != null) operation.fetchFuture.cancel(false);
            }
        }
    }

    void fetchLyrics(
            Context context,
            SpotifyTrack track,
            int generation,
            NativeSpicyLyricsHook.LyricsResultCallback callback
    ) {
        Diagnostics.event("lyrics_fetch", "request_started",
                Diagnostics.context("enabled", "true"));
        NativeSpicyLyricsHook.dbg(
                "fetchLyrics",
                "start generation=" + generation + " track=" + (track == null ? "null" : safe(track.uri))
        );
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        boolean sendToken = config.get(Settings.SEND_TOKEN);
        // Source selection is owned by LyricsSourcePreferences toggles/order. The retired
        // global override must not bypass that policy.
        String sourceOverride = "Auto";
        String manualSpicyToken = SpicyManualTokenStore.load(context);
        boolean strictSpicy = false;
        if (strictSpicy && manualSpicyToken != null && !manualSpicyToken.trim().isEmpty()) {
            sendToken = true;
        }
        // M3: in-flight identity uses the non-secret token generation from the token store
        // (never the raw token, never a token-present boolean), so a fresh token generation
        // can never join an in-flight stale-token request. The snapshot binds the token text
        // to the generation used for the request and any later scoped invalidation.
        SpotifyTokenState.Authorized authorized = sendToken
                ? SpotifyTokenStore.authorization(System.currentTimeMillis())
                : null;
        String operationKey = fetchKey(track, sendToken, authorized)
                + "|source=" + sourceOverride
                + "|sel=" + selectionIdentity(context, track)
                + "|manual=" + (manualSpicyToken == null || manualSpicyToken.trim().isEmpty()
                ? "none" : Integer.toHexString(manualSpicyToken.hashCode()));
        InFlightFetch existing;
        LyricsDocument replay = null;
        boolean joined = false;
        synchronized (inFlightLock) {
            existing = inFlight.get(operationKey);
            if (existing != null) {
                joined = true;
                existing.callbacks.add(callback);
                if (existing.latest != null) replay = LyricsDocument.copyOf(existing.latest);
            } else {
                existing = new InFlightFetch(operationKey, authorized != null);
                existing.callbacks.add(callback);
                inFlight.put(operationKey, existing);
            }
        }
        if (replay != null) callback.onSuccess(replay);
        if (joined) return;
        InFlightFetch operation = existing;
        LyricsRepository repository = new LyricsRepository(
                http,
                lyricsParser,
                nativeLyricsSource,
                NativeRuntime.LYRICS_IO
        );
        String accessToken = authorized == null ? "" : authorized.token();
        if (strictSpicy && manualSpicyToken != null && !manualSpicyToken.trim().isEmpty()) {
            accessToken = manualSpicyToken.trim();
        }
        final boolean requestSendToken = sendToken;
        final String requestAccessToken = accessToken;
        int tokenGeneration = authorized == null ? TOKEN_GENERATION_NONE : authorized.generation();
        LyricsRepository.AuthRecovery authRecovery = authRecovery();
        operation.fetchFuture = NativeRuntime.LYRICS_IO.submit(() -> repository.fetchLyrics(
                context,
                track,
                generation,
                requestSendToken,
                requestAccessToken,
                tokenGeneration,
                authRecovery,
                new LyricsRepository.ResultCallback() {
                    @Override
                    public void onSuccess(LyricsDocument document) {
                        deliverSuccess(operation, document);
                    }

                    @Override
                    public void onError(String error) {
                        deliverError(operation, error);
                    }
                }));
    }

    private void deliverSuccess(InFlightFetch operation, LyricsDocument document) {
        Diagnostics.event("lyrics_fetch", "request_completed",
                Diagnostics.context("result", "success",
                        "provider", document == null ? "unknown" : document.provider,
                        "language", document == null ? "" : document.language,
                        "timingType", document == null ? "Unknown" : document.type));
        List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks;
        synchronized (inFlightLock) {
            if (inFlight.get(operation.key) != operation) return;
            operation.latest = LyricsDocument.copyOf(document);
            callbacks = new ArrayList<>(operation.callbacks);
            boolean cachePreview = "spicy_api_cache".equals(document == null ? "" : document.fetchSource)
                    || "apple_music_cache".equals(document == null ? "" : document.fetchSource);
            boolean baselineUpgrade = document != null && document.fetchSource != null
                    && document.fetchSource.startsWith("spotify_native") && operation.latestWasBaseline == false;
            operation.latestWasBaseline = true;
            if ((!cachePreview && !baselineUpgrade) || !operation.networkUpgradeExpected) {
                inFlight.remove(operation.key);
                if (operation.expiry != null) operation.expiry.cancel(false);
                operation.callbacks.clear();
                operation.latest = null;
                if (operation.fetchFuture != null) operation.fetchFuture.cancel(false);
            } else if (operation.expiry == null) {
                operation.expiry = NativeRuntime.LYRICS_IO.schedule(
                        () -> expire(operation), 20L, TimeUnit.SECONDS);
            }
        }
        for (NativeSpicyLyricsHook.LyricsResultCallback callback : callbacks) {
            callback.onSuccess(LyricsDocument.copyOf(document));
        }
    }

    private void deliverError(InFlightFetch operation, String error) {
        Diagnostics.event("lyrics_fetch", "request_completed",
                Diagnostics.context("result", "error"));
        List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks;
        synchronized (inFlightLock) {
            if (inFlight.remove(operation.key) != operation) return;
            if (operation.expiry != null) operation.expiry.cancel(false);
            callbacks = new ArrayList<>(operation.callbacks);
            operation.callbacks.clear();
        }
        for (NativeSpicyLyricsHook.LyricsResultCallback callback : callbacks) callback.onError(error);
    }

    private void expire(InFlightFetch operation) {
        synchronized (inFlightLock) {
            if (inFlight.get(operation.key) == operation) inFlight.remove(operation.key);
            operation.callbacks.clear();
            operation.latest = null;
        }
    }

    /** Sentinel token generation for requests issued without a usable token; never a real generation. */
    private static final int TOKEN_GENERATION_NONE = -1;

    /**
     * M3 seam: tombstones exactly the generation used by the rejected request and returns the
     * replacement authorization only when a newer usable generation already exists. With no newer
     * generation the repository proceeds to native/LRCLIB fallback, so an auth failure cannot loop.
     */
    private LyricsRepository.AuthRecovery authRecovery() {
        return rejectedGeneration -> {
            Diagnostics.event("lyrics_fetch", "auth_rejected",
                    Diagnostics.context("tokenGeneration", String.valueOf(rejectedGeneration)));
            SpotifyTokenStore.invalidate(rejectedGeneration);
            SpotifyTokenState.Authorized candidate =
                    SpotifyTokenStore.authorization(System.currentTimeMillis());
            SpotifyTokenState.Authorized newer =
                    retryAuthorizationAfterRejection(candidate, rejectedGeneration);
            return newer == null
                    ? null
                    : LyricsRepository.Authorization.of(newer.token(), newer.generation());
        };
    }

    /** Pure guard: a replacement is usable only when its generation is strictly newer. */
    static SpotifyTokenState.Authorized retryAuthorizationAfterRejection(
            SpotifyTokenState.Authorized candidate, int rejectedGeneration) {
        if (candidate == null) return null;
        if (candidate.generation() <= rejectedGeneration) return null;
        return candidate;
    }

    /**
     * Non-secret in-flight identity: track URI plus the token generation actually bound to the
     * request (or {@code none} when no usable token is sent). Token text never participates.
     */
    static String selectionIdentity(Context context, com.eza.spicyex.SpotifyTrack track) {
        try {
            String trackId = track == null || track.uri == null ? "" : track.uri;
            return com.eza.spicyex.lyrics.session.LyricsSourcePreferences
                    .selectionIdentity(context, trackId);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
    static String fetchKey(SpotifyTrack track, boolean sendToken, SpotifyTokenState.Authorized authorized) {
        String uri = track == null ? "" : safe(track.uri);
        boolean tokenUsable = sendToken && authorized != null;
        return uri + "|tokenGen=" + (tokenUsable ? String.valueOf(authorized.generation()) : "none");
    }

    private static final class InFlightFetch {
        final String key;
        final boolean networkUpgradeExpected;
        final List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks = new ArrayList<>();
        LyricsDocument latest;
        boolean latestWasBaseline;
        ScheduledFuture<?> expiry;
        java.util.concurrent.Future<?> fetchFuture;

        InFlightFetch(String key, boolean networkUpgradeExpected) {
            this.key = key;
            this.networkUpgradeExpected = networkUpgradeExpected;
        }
    }

    private void finalizeParsedDocument(Context context, LyricsDocument doc) {
        LyricsDocumentProcessor.finalizeParsedDocument(context, doc, processingVersion);
    }
}
