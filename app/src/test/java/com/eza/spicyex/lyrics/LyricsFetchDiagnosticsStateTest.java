package com.eza.spicyex.lyrics;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LyricsFetchDiagnosticsStateTest {
    @Test
    public void cachedCanonicalSourceDisplaysItsNativeOrigin() {
        LyricsDocument document = document("spotify_native_model:lyrics_view", "Spotify");

        LyricsFetchDiagnosticsState.recordCached(document);

        LyricsFetchDiagnosticsState.Snapshot snapshot = LyricsFetchDiagnosticsState.get();
        assertEquals("cache", snapshot.sourceChosen);
        assertEquals("native", snapshot.cacheSource);
        assertEquals("cache (native)", snapshot.displayedSourceChosen());
        assertEquals("none", snapshot.candidatesSeen);
    }

    @Test
    public void rawRemoteCacheDisplaysItsRemoteOrigin() {
        LyricsDocument document = document("apple_music_cache", "Musixmatch");

        LyricsFetchDiagnosticsState.record("cache", Collections.singletonList("apple_music"),
                document, true, false);

        LyricsFetchDiagnosticsState.Snapshot snapshot = LyricsFetchDiagnosticsState.get();
        assertEquals("apple_music", snapshot.cacheSource);
        assertEquals("cache (apple_music)", snapshot.displayedSourceChosen());
    }

    @Test
    public void legacySpicyCacheMapsToRemoteOrigin() {
        LyricsDocument document = document("spicy_api_cache", "Spicy Lyrics");

        LyricsFetchDiagnosticsState.record("cache", Collections.singletonList("apple_music"),
                document, true, false);

        LyricsFetchDiagnosticsState.Snapshot snapshot = LyricsFetchDiagnosticsState.get();
        assertEquals("apple_music", snapshot.cacheSource);
        assertEquals("cache (apple_music)", snapshot.displayedSourceChosen());
    }

    @Test
    public void freshSourceClearsPriorCacheOrigin() {
        LyricsDocument cached = document("lrclib", "LRCLIB");
        LyricsFetchDiagnosticsState.recordCached(cached);

        LyricsFetchDiagnosticsState.record("native", Collections.singletonList("native"),
                document("spotify_native_db", "Spotify"), false, false);

        LyricsFetchDiagnosticsState.Snapshot snapshot = LyricsFetchDiagnosticsState.get();
        assertEquals("native", snapshot.sourceChosen);
        assertEquals("", snapshot.cacheSource);
        assertEquals("native", snapshot.displayedSourceChosen());
    }

    @Test
    public void lrclibWinnerKeepsRemoteNetworkStatus() {
        SpicyNetworkDiagnostics.spicyQueryStatus = 401;
        SpicyNetworkDiagnostics.reason = "Apple Music auth rejected HTTP 401";
        try {
            LyricsFetchDiagnosticsState.record("lrclib", Collections.singletonList("lrclib"),
                    document("lrclib", "LRCLIB"), true, false);

            assertEquals("401", LyricsFetchDiagnosticsState.get().spicyQueryStatus);
        } finally {
            SpicyNetworkDiagnostics.spicyQueryStatus = null;
            SpicyNetworkDiagnostics.reason = "";
        }
    }

    private static LyricsDocument document(String fetchSource, String provider) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = fetchSource;
        document.provider = provider;
        document.type = "Line";
        return document;
    }
}
