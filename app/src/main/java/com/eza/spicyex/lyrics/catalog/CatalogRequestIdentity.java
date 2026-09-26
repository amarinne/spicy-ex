package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

/**
 * In-flight identity for one provider request. Every inflight fetch is keyed by the track, the
 * source, the query revision, and that source's own auth epoch — never by another provider's
 * credential state. In particular a credential-free Apple request carries no Spotify token
 * generation, so an Apple upgrade can never be suppressed by token absence.
 */
public final class CatalogRequestIdentity {
    /** Auth epoch for sources that need no credential. */
    public static final String EPOCH_CREDENTIAL_FREE = "none";
    /** Auth epoch for local sources (Spotify native model/DB): no network identity at all. */
    public static final String EPOCH_LOCAL = "local";
    /** Sentinel token generation for requests issued without a usable Spotify token. */
    public static final int TOKEN_GENERATION_NONE = -1;

    private CatalogRequestIdentity() {
    }

    /**
     * @param bareTrackId bare Spotify track ID; empty yields "" and the caller must not fetch
     * @param sourceId    requesting source; null yields ""
     * @param queryRevision opaque revision of what is asked (selection/config shape); null → ""
     * @param authEpoch   that source's own credential epoch; null → {@link #EPOCH_CREDENTIAL_FREE}
     */
    public static String key(String bareTrackId, SourceId sourceId, String queryRevision,
                             String authEpoch) {
        if (bareTrackId == null || bareTrackId.isEmpty() || sourceId == null) return "";
        return bareTrackId + "|" + sourceId.id
                + "|rev=" + (queryRevision == null ? "" : queryRevision)
                + "|auth=" + (authEpoch == null ? EPOCH_CREDENTIAL_FREE : authEpoch);
    }

    /**
     * The auth epoch for one source given the Spotify token generation bound to this fetch
     * ({@link #TOKEN_GENERATION_NONE} when no usable token is sent). Only token-bound sources
     * observe the token; Apple, AMLL, LRCLIB, QQ, and NetEase requests are credential-free and
     * native reads are local.
     */
    public static String authEpoch(SourceId sourceId, int spotifyTokenGeneration) {
        if (sourceId == null) return EPOCH_CREDENTIAL_FREE;
        switch (sourceId) {
            case SPOTIFY_NATIVE:
                return EPOCH_LOCAL;
            case APPLE:
            case AMLL:
            case LRCLIB:
            case QQ:
            case NETEASE:
                return EPOCH_CREDENTIAL_FREE;
            default:
                return EPOCH_CREDENTIAL_FREE;
        }
    }

    /**
     * Whether a native baseline delivery must keep its operation open for a later upgrade.
     * The upgrade window follows Apple availability, never Spotify token presence: a
     * credential-free Apple request can still upgrade a native baseline.
     */
    public static boolean upgradeExpected(boolean tokenBound, boolean appleEnabled) {
        return tokenBound || appleEnabled;
    }
}
