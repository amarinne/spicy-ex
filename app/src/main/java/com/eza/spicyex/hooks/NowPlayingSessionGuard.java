package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.LyricUtils;

/** Pure stale-delivery guard for Now Playing session document commits. */
final class NowPlayingSessionGuard {
    private NowPlayingSessionGuard() {
    }

    static boolean matchesCurrentTrack(String sessionTrackId, String liveTrackId, String displayedTrackId) {
        if (LyricUtils.isBlank(sessionTrackId) || !sessionTrackId.equals(liveTrackId)) return false;
        return LyricUtils.isBlank(displayedTrackId) || sessionTrackId.equals(displayedTrackId);
    }

    static boolean projectionIsStale(
            boolean running,
            long expectedRevision,
            long currentRevision,
            int expectedGeneration,
            int currentGeneration,
            String expectedTrackId,
            String currentTrackId
    ) {
        return !running || expectedRevision != currentRevision
                || expectedGeneration != currentGeneration
                || !expectedTrackId.equals(currentTrackId);
    }

    /** Checks lifecycle eligibility only; the merge operation validates canonical identity. */
    static boolean mayMergeMountedProjection(
            boolean authoritativeReplayRequired,
            boolean loadedTrackMatches,
            boolean cardMounted,
            boolean artworkMounted
    ) {
        return !authoritativeReplayRequired && loadedTrackMatches && cardMounted
                && artworkMounted;
    }

}
