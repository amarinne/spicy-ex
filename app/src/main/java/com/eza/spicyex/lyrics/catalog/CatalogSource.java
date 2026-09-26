package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.LyricUtils;

import java.util.Locale;

/**
 * Catalog vocabulary: source identities, timing levels, match methods, provider states, and
 * selection modes shared by acquisition, selection, and rendering.
 *
 * <p>Direct Musixmatch is deliberately absent: there is no Musixmatch source ID, adapter, setting,
 * or rank. Spotify native stays {@code spotify_native}; its credit text may still name Musixmatch
 * because that is information inside Spotify's payload, not a second route.
 */
public final class CatalogSource {
    private CatalogSource() {
    }

    /**
     * Provider identity. Declaration order is the automatic tie-break order: Apple, Spotify
     * native, AMLL, LRCLIB, QQ, NetEase.
     */
    public enum SourceId {
        APPLE("apple"),
        SPOTIFY_NATIVE("spotify_native"),
        AMLL("amll"),
        LRCLIB("lrclib"),
        QQ("qq"),
        NETEASE("netease");

        public final String id;

        SourceId(String id) {
            this.id = id;
        }

        public static SourceId parse(String value) {
            if (value == null) return null;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (SourceId source : values()) {
                if (source.id.equals(v) || source.name().toLowerCase(Locale.ROOT).equals(v)) {
                    return source;
                }
            }
            return null;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** Actual timing a candidate carries. Ordinal is comparison rank: lower wins. */
    public enum TimingLevel {
        SYLLABLE,
        WORD,
        LINE,
        UNSYNCED;

        public static TimingLevel fromDocumentType(String type) {
            if ("Syllable".equalsIgnoreCase(type)) return SYLLABLE;
            if ("Word".equalsIgnoreCase(type)) return WORD;
            if ("Line".equalsIgnoreCase(type)) return LINE;
            return UNSYNCED;
        }

        /**
         * Whether a seat at this timing is good enough to stop searching.
         *
         * <p>The threshold is {@link #WORD}, not {@link #LINE}. Apple is asked first and is the
         * strongest source, so a track where Apple did not reach {@link #SYLLABLE} has nothing
         * better than {@link #WORD} behind it, and word-level spans are near-equivalent to
         * syllable-level for karaoke fill. Stopping at {@code LINE} instead parked the majority of
         * tracks on line timing and made the four-level ranking a three-level one: a
         * {@code LINE} seat sealed the search, so a {@code WORD} or {@code SYLLABLE} document from
         * another source was never requested. Devices showed the same song at different quality
         * purely because a different source happened to answer first.
         *
         * <p>{@link #LINE} and {@link #UNSYNCED} therefore keep the search open. They are still
         * ranked against each other and against better documents; they just do not end it.
         */
        public boolean isFinalQuality() {
            return this == SYLLABLE || this == WORD;
        }
    }

    /** How a candidate was matched to the track. */
    public enum MatchMethod {
        EXACT_SPOTIFY_ID,
        EXACT_PROVIDER_MAPPING,
        KARAOKE_SUBSTITUTION,
        STRONG_SEARCH,
        /** Weak matches are rejected by the resolver, never stored as playable. */
        WEAK;
    }

    /**
     * Persisted per-track per-source state. {@code CHECKING} is intentionally absent: in-flight
     * work lives in memory only and never touches the database.
     */
    public enum ProviderStatus {
        NOT_CHECKED,
        AVAILABLE,
        NOT_FOUND,
        TRANSIENT_ERROR,
        REJECTED,
        NEEDS_REFRESH,
        DISABLED;

        public static ProviderStatus parse(String value) {
            if (value == null) return null;
            try {
                return ProviderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** Whether the track follows the automatic winner or a manual pick. */
    public enum SelectionMode {
        AUTO,
        MANUAL;

        public static SelectionMode parse(String value) {
            if (value == null) return AUTO;
            try {
                return SelectionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return AUTO;
            }
        }
    }

    /**
     * The catalog's only track identity: the bare Spotify track ID. Accepts a full URI
     * ({@code spotify:track:xxx}) or an already-bare ID; anything else (episodes, local files,
     * empty input) yields "" and the caller must skip the catalog for it.
     */
    public static String bareTrackId(String uriOrId) {
        if (uriOrId == null) return "";
        String input = uriOrId.trim();
        if (input.isEmpty()) return "";
        if (input.contains(":")) return LyricUtils.trackIdFromUri(input);
        if (input.contains(" ") || input.contains("/")) return "";
        return input;
    }

    /**
     * Maps a legacy {@code fetchSource} (plus provider credit when the source is ambiguous) to a
     * catalog source. The retired {@code spicy} route maps to Apple; a direct-Musixmatch value
     * maps to null so the caller falls back to Auto instead of storing a dead route.
     */
    public static SourceId inferSourceId(String fetchSource, String provider) {
        String v = fetchSource == null ? "" : fetchSource.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "unknown".equals(v)) {
            v = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        }
        // Parsers tag deliveries with a route suffix (apple_music_cache, amll_ttml,
        // spotify_native_model:tag, qq_music); the prefix names the source.
        if (v.startsWith("amll")) return SourceId.AMLL;
        if (v.startsWith("apple") || v.equals("aml") || v.startsWith("lenerd")
                || v.startsWith("spicy")) {
            return SourceId.APPLE;
        }
        if (v.startsWith("spotify") || v.startsWith("native")) return SourceId.SPOTIFY_NATIVE;
        if (v.startsWith("lrclib")) return SourceId.LRCLIB;
        if (v.startsWith("qq")) return SourceId.QQ;
        if (v.startsWith("netease")) return SourceId.NETEASE;
        return null;
    }
}
