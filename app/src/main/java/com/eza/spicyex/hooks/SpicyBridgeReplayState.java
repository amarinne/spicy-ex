package com.eza.spicyex.hooks;

/** Tracks delivery of one retained bridge payload across Binder connections. */
final class SpicyBridgeReplayState {
    private long nextRevision;
    private long retainedRevision;
    private long publishedRevision;

    long retainPayload() {
        nextRevision++;
        if (nextRevision == 0L) nextRevision++;
        retainedRevision = nextRevision;
        return retainedRevision;
    }

    long pendingRevision() {
        return retainedRevision != 0L && retainedRevision != publishedRevision
                ? retainedRevision : 0L;
    }

    void markPublished(long revision) {
        if (revision == retainedRevision) publishedRevision = revision;
    }

    void onConnectionOpened() {
        publishedRevision = 0L;
    }

    void clearPayload() {
        retainedRevision = 0L;
        publishedRevision = 0L;
    }

    static boolean shouldAwaitAutomaticReconnect(boolean bound) {
        return bound;
    }
}
