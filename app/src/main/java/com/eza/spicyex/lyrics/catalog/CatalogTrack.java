package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;

import java.util.Objects;

/**
 * One cataloged track's metadata. The bare Spotify track ID is the only primary identity; the seat
 * is a {@link CatalogSelection}, never a column here.
 */
public final class CatalogTrack {
    public final String trackId;
    public final String uri;
    public final String title;
    public final String artists;
    public final String album;
    public final long durationMs;
    public final long lastSeenMs;

    public CatalogTrack(String trackId, String uri, String title, String artists, String album,
                        long durationMs, long lastSeenMs) {
        this.trackId = nz(trackId);
        this.uri = nz(uri);
        this.title = nz(title);
        this.artists = nz(artists);
        this.album = nz(album);
        this.durationMs = Math.max(0L, durationMs);
        this.lastSeenMs = Math.max(0L, lastSeenMs);
    }

    /** Metadata row for a playing track; null for anything the catalog does not identify. */
    public static CatalogTrack of(SpotifyTrack track, long nowMs) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (trackId.isEmpty()) return null;
        return new CatalogTrack(trackId, track.uri, track.title, track.artist, track.album,
                track.duration, nowMs);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CatalogTrack)) return false;
        CatalogTrack o = (CatalogTrack) other;
        return trackId.equals(o.trackId) && uri.equals(o.uri) && title.equals(o.title)
                && artists.equals(o.artists) && album.equals(o.album) && durationMs == o.durationMs
                && lastSeenMs == o.lastSeenMs;
    }

    @Override public int hashCode() {
        return Objects.hash(trackId, uri, title, artists, album, durationMs, lastSeenMs);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
