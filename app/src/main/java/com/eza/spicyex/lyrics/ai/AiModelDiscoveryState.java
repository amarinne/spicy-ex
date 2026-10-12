package com.eza.spicyex.lyrics.ai;

/** Settings-thread ownership of model discovery and its resulting picker. */
public final class AiModelDiscoveryState {
    public static final class Attempt {
        public final AiSettings.DiscoverySnapshot snapshot;
        public final AiSignal signal = new AiSignal();
        private final long revision;
        private boolean finished;

        private Attempt(AiSettings.DiscoverySnapshot snapshot, long revision) {
            this.snapshot = snapshot;
            this.revision = revision;
        }
    }

    private long revision;
    private AiSignal activeSignal;

    public Attempt begin(AiSettings settings) {
        if (settings == null || !settings.isEnabled()) return null;
        AiSettings.DiscoverySnapshot snapshot = settings.snapshotDiscovery();
        if (snapshot == null) return null;
        if (activeSignal != null) activeSignal.abort("model_discovery_superseded");
        Attempt attempt = new Attempt(snapshot, ++revision);
        activeSignal = attempt.signal;
        return attempt;
    }

    /** A completion or timeout can publish at most once, against its captured configuration. */
    public boolean finish(AiSettings settings, Attempt attempt) {
        if (attempt == null || attempt.revision != revision || attempt.finished) return false;
        attempt.finished = true;
        activeSignal = null;
        return isCurrent(settings, attempt);
    }

    /** Recheck when the picker is clicked: configuration can change after its list is displayed. */
    public boolean canSelect(AiSettings settings, Attempt attempt) {
        return isCurrent(settings, attempt) && attempt.finished && !attempt.signal.isAborted();
    }

    private boolean isCurrent(AiSettings settings, Attempt attempt) {
        return attempt != null && attempt.revision == revision && settings != null
                && settings.isEnabled() && attempt.snapshot.matches(settings);
    }
}
