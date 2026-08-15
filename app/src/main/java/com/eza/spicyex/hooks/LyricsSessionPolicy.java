package com.eza.spicyex.hooks;

/** Pure state gate for current-track identity, demand, and stale-result rejection. */
final class LyricsSessionPolicy {
    private String trackUri = "";
    private int generation;
    private int pollingDemandCount;

    boolean adoptTrack(String nextTrackUri) {
        String next = nextTrackUri == null ? "" : nextTrackUri;
        if (next.equals(trackUri)) return false;
        trackUri = next;
        generation++;
        return true;
    }

    boolean accepts(int candidateGeneration, String candidateTrackUri) {
        return candidateGeneration == generation && trackUri.equals(candidateTrackUri);
    }

    boolean acquirePollingDemand() {
        pollingDemandCount++;
        return pollingDemandCount == 1;
    }

    boolean releasePollingDemand() {
        if (pollingDemandCount == 0) return false;
        pollingDemandCount--;
        return pollingDemandCount == 0;
    }

    boolean hasPollingDemand() {
        return pollingDemandCount > 0;
    }

    int pollingDemandCount() {
        return pollingDemandCount;
    }

    String trackUri() {
        return trackUri;
    }

    int generation() {
        return generation;
    }
}
