package com.eza.spicyex.hooks;

/** Pure state gate for current-track identity, demand, and stale-result rejection. */
final class LyricsSessionPolicy {
    private String trackUri = "";
    private int generation;
    private boolean backgroundDemand;

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

    void setBackgroundDemand(boolean enabled) {
        backgroundDemand = enabled;
    }

    boolean hasBackgroundDemand() {
        return backgroundDemand;
    }

    String trackUri() {
        return trackUri;
    }

    int generation() {
        return generation;
    }
}
