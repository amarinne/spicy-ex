package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.app.Activity;
import android.content.Context;

import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsParser;
import com.eza.spicyex.lyrics.LyricsRepository;
import com.eza.spicyex.lyrics.NativeLyricsSource;

import okhttp3.OkHttpClient;

/** Owns parser/native-source setup and LyricsRepository construction for hook-hosted fetches. */
final class LyricsFetchCoordinator {
    private final OkHttpClient http;
    private final int processingVersion;
    private final LyricsParser lyricsParser;
    private final NativeLyricsSource nativeLyricsSource;

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

    void fetchLyrics(
            Activity activity,
            SpotifyTrack track,
            int generation,
            NativeSpicyLyricsHook.LyricsResultCallback callback
    ) {
        NativeSpicyLyricsHook.dbg(
                "fetchLyrics",
                "start generation=" + generation + " track=" + (track == null ? "null" : safe(track.uri))
        );
        LyricsRepository repository = new LyricsRepository(
                http,
                lyricsParser,
                nativeLyricsSource
        );
        repository.fetchLyrics(
                activity,
                track,
                generation,
                SpotifyPlusConfig.from(activity).get(Settings.SEND_TOKEN),
                References.accessToken,
                new LyricsRepository.ResultCallback() {
                    @Override
                    public void onSuccess(LyricsDocument document) {
                        callback.onSuccess(document);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }
                });
    }

    private void finalizeParsedDocument(Context context, LyricsDocument doc) {
        LyricsDocumentProcessor.finalizeParsedDocument(context, doc, processingVersion);
    }
}
