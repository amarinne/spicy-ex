package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricResponseReader;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.AmllTtmlParser;

import java.io.InputStream;

import okhttp3.ResponseBody;
import okio.Okio;

/** Reads an owner-selected TTML file before any catalog mutation. */
public final class LocalLyricsImport {
    private LocalLyricsImport() {}

    /** The same byte ceiling applies to file streams and provider responses. Closes the stream. */
    public static CatalogCandidate read(SpotifyTrack track, InputStream input, long now) throws Exception {
        if (input == null) throw new IllegalArgumentException("Could not read TTML file");
        String raw = LyricResponseReader.read(ResponseBody.create(null, -1,
                Okio.buffer(Okio.source(input))), LyricResponseReader.LYRIC_BYTES);
        String id = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (id.isEmpty()) throw new IllegalArgumentException("Unsupported track");
        LyricsDocument doc = AmllTtmlParser.parseLocal(raw, id, track.duration);
        doc.fetchSource = "local_ttml";
        doc.provider = "Local";
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(CatalogSource.SourceId.LOCAL,
                track, doc, CatalogSource.MatchMethod.USER_IMPORT, "ttml", raw, 1, now);
        if (candidate == null) throw new IllegalArgumentException("TTML has no usable lyrics");
        return candidate;
    }
}
