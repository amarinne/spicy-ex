package com.eza.spicyex.hooks;

/** Pure ordering gate for asynchronous surface document preparation and commit. */
final class LyricsSurfaceDocumentGate {
    static final class Candidate {
        private final int lifecycle;
        private final long sequence;
        private final String trackId;

        Candidate(int lifecycle, long sequence, String trackId) {
            this.lifecycle = lifecycle;
            this.sequence = sequence;
            this.trackId = trackId;
        }
    }

    private int lifecycle;
    private long sequence;
    private long currentSequence;
    private boolean active;

    void start() {
        lifecycle++;
        active = true;
        currentSequence = 0L;
    }

    void stop() {
        lifecycle++;
        active = false;
        currentSequence = 0L;
    }

    Candidate offer(String trackId) {
        long next = ++sequence;
        currentSequence = next;
        return new Candidate(lifecycle, next, safe(trackId));
    }

    boolean accepts(Candidate candidate, String currentTrackId) {
        return active && candidate != null
                && candidate.lifecycle == lifecycle
                && candidate.sequence == currentSequence
                && candidate.trackId.equals(safe(currentTrackId));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
