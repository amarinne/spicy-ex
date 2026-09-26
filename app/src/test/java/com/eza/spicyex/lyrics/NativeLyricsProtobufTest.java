package com.eza.spicyex.lyrics;

import static org.junit.Assert.*;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogCandidate;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class NativeLyricsProtobufTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:abc123", 0, "", 0, null, 180000, false);

    // Spotify's generated protobuf fields, without a dependency on Spotify's APK.
    static final class LyricsLine {
        private final String words_ = "A complete line";
        private final long startTimeMs_ = 1000;
        private final long endTimeMs_ = 4000;
    }
    static final class LyricsResponse {
        private final List<LyricsLine> lines_ = Arrays.asList(new LyricsLine());
        private final String language_ = "en";
        private final String provider_ = "musixmatch";
    }
    static final class Language { private final String code_ = "ja"; }
    static final class LyricsV3Response {
        private final List<LyricsLine> lines_ = Arrays.asList(new LyricsLine());
        private final Language language_ = new Language();
    }

    private static LyricsDocument capture(Object message, String tag) {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicReference<LyricsDocument> result = new AtomicReference<>();
        source.addNativeListener((id, doc) -> result.set(doc));
        source.captureCandidate(TRACK, message, new Object[0], tag);
        return result.get();
    }

    @Test public void readsProtobufFieldsAndKeepsMetadataAcrossAccessorVisits() {
        LyricsResponse message = new LyricsResponse();
        LyricsDocument first = capture(message, "ColorLyricsResponse#p");
        LyricsDocument revisit = capture(message, "LyricsResponse#w");
        assertNotNull(first);
        assertEquals("en", first.language);
        assertEquals("Spotify (through Musixmatch)", first.provider);
        assertEquals("Line", first.type);
        assertEquals("A complete line", first.lines.get(0).text);
        assertEquals(1000, first.lines.get(0).startMs);
        assertEquals(4000, first.lines.get(0).endMs);
        assertEquals("spotify_native_model", first.fetchSource);
        CatalogCandidate a = CatalogAdapters.buildCandidate(CatalogSource.SourceId.SPOTIFY_NATIVE,
                TRACK, first, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, "abc123", "", 1, 1);
        CatalogCandidate b = CatalogAdapters.buildCandidate(CatalogSource.SourceId.SPOTIFY_NATIVE,
                TRACK, revisit, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, "abc123", "", 1, 2);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.candidateId, b.candidateId);
    }

    @Test public void rejectsBareListWithMissingMetadata() {
        assertNull(capture(new LyricsResponse().lines_, "LyricsResponse#w"));
    }

    @Test public void readsNestedV3Language() {
        assertEquals("ja", capture(new LyricsV3Response(), "LyricsWrapperResponse").language);
    }
}
