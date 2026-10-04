package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.lyrics.LyricsDocument;

/** Origin rules apply to every catalogue returned through the SpicyLyrics.org API. */
public final class SpicyOrgPolicy {
    public static final long REFRESH_AFTER_MS = 21L * 24 * 60 * 60 * 1000;
    public static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private SpicyOrgPolicy() {}

    public static boolean isRestricted(LyricsDocument document) {
        return document != null && document.fetchSource != null
                && document.fetchSource.startsWith("spicy_org");
    }

    public static boolean expires(LyricsDocument document, long nowMs) {
        return isRestricted(document) && expiredAt(document.spicyOrgFetchedAtMs, nowMs);
    }

    /** Refresh due responses remain usable until the hard retention deadline. */
    public static boolean needsRefresh(LyricsDocument document, long nowMs) {
        return isRestricted(document) && refreshDueAt(document.spicyOrgFetchedAtMs, nowMs);
    }

    public static boolean refreshDueAt(long fetchedAtMs, long nowMs) {
        return fetchedAtMs <= 0 || nowMs < fetchedAtMs || nowMs - fetchedAtMs >= REFRESH_AFTER_MS;
    }

    public static boolean expiredAt(long fetchedAtMs, long nowMs) {
        return fetchedAtMs <= 0 || nowMs < fetchedAtMs || nowMs - fetchedAtMs >= RETENTION_MS;
    }
}
