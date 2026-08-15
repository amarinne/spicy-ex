package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsParser;
import com.eza.spicyex.lyrics.LyricsRepository;
import com.eza.spicyex.lyrics.NativeLyricsSource;

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
        String operationKey = fetchKey(track,
                SpotifyPlusConfig.from(context).get(Settings.SEND_TOKEN), References.accessToken);
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
                existing = new InFlightFetch(operationKey,
                        SpotifyPlusConfig.from(context).get(Settings.SEND_TOKEN)
                                && References.accessToken != null
                                && !References.accessToken.trim().isEmpty());
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
        boolean sendToken = SpotifyPlusConfig.from(context).get(Settings.SEND_TOKEN);
        String accessToken = References.accessToken;
        NativeRuntime.LYRICS_IO.execute(() -> repository.fetchLyrics(
                context,
                track,
                generation,
                sendToken,
                accessToken,
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
            if (operation.expiry != null) operation.expiry.cancel(false);
            boolean cachePreview = "spicy_api_cache".equals(document == null ? "" : document.fetchSource);
            if (!cachePreview || !operation.networkUpgradeExpected) {
                inFlight.remove(operation.key);
                operation.callbacks.clear();
                operation.latest = null;
            } else {
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

    private static String fetchKey(SpotifyTrack track, boolean sendToken, String token) {
        String uri = track == null ? "" : safe(track.uri);
        return uri + "|token=" + (sendToken && token != null && !token.trim().isEmpty());
    }

    private static final class InFlightFetch {
        final String key;
        final boolean networkUpgradeExpected;
        final List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks = new ArrayList<>();
        LyricsDocument latest;
        ScheduledFuture<?> expiry;

        InFlightFetch(String key, boolean networkUpgradeExpected) {
            this.key = key;
            this.networkUpgradeExpected = networkUpgradeExpected;
        }
    }

    private void finalizeParsedDocument(Context context, LyricsDocument doc) {
        LyricsDocumentProcessor.finalizeParsedDocument(context, doc, processingVersion);
    }
}
