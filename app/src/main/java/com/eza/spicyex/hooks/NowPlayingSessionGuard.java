package com.eza.spicyex.hooks;

/** Pure stale-delivery guard for Now Playing session document commits. */
final class NowPlayingSessionGuard {
    private NowPlayingSessionGuard() {
    }

    static boolean matchesCurrentTrack(String sessionTrackId, String liveTrackId, String displayedTrackId) {
        if (isBlank(sessionTrackId) || !sessionTrackId.equals(liveTrackId)) return false;
        return isBlank(displayedTrackId) || sessionTrackId.equals(displayedTrackId);
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
