package com.eza.spicyex.lyrics.ai;

/** One process-local liveness result for the selected provider/model configuration. */
public final class AiModelLiveState {
    private enum State { UNKNOWN, CHECKING, LIVE, UNAVAILABLE }

    /** A unique attempt binds both the request and its completion to the original settings. */
    public static final class Attempt {
        public final AiSettings.ProbeSnapshot snapshot;
        private final long revision;

        private Attempt(AiSettings.ProbeSnapshot snapshot, long revision) {
            this.snapshot = snapshot;
            this.revision = revision;
        }
    }

    private static String identity = "";
    private static State state = State.UNKNOWN;
    private static long revision;

    private AiModelLiveState() {
    }

    /** Claim the initialization probe, or supersede an earlier attempt for an explicit test. */
    public static synchronized Attempt begin(AiSettings settings, boolean explicit) {
        String next = identityOf(settings);
        if (!next.equals(identity)) {
            identity = next;
            state = State.UNKNOWN;
            revision++;
        }
        if (settings == null || (!explicit && state != State.UNKNOWN)) return null;
        AiSettings.ProbeSnapshot snapshot = settings.snapshotProbe();
        if (snapshot == null) return null;
        state = State.CHECKING;
        return new Attempt(snapshot, ++revision);
    }

    /** Publish on the settings thread. A stale completion must not change any visible state. */
    public static synchronized boolean finish(AiSettings settings, Attempt attempt,
                                             AiModelProbe.Result result) {
        if (attempt == null || attempt.revision != revision
                || !attempt.snapshot.identity.equals(identity)
                || !identityOf(settings).equals(identity)
                || settings.readiness() != AiSettings.Readiness.READY) return false;
        state = result != null && result.ok ? State.LIVE : State.UNAVAILABLE;
        revision++;
        if (result != null) {
            settings.recordProbeOutcome(attempt.snapshot, result);
            AiLastProbe.record(result);
        }
        return true;
    }

    public static synchronized boolean isLive(AiSettings settings) {
        return settings != null
                && settings.readiness() == AiSettings.Readiness.READY
                && identityOf(settings).equals(identity)
                && state == State.LIVE;
    }

    public static synchronized void invalidate() {
        identity = "";
        state = State.UNKNOWN;
        revision++;
    }

    static String identityOf(AiSettings settings) {
        if (settings == null) return "";
        return settings.providerChoice() + '\n' + settings.endpoint() + '\n' + settings.modelName();
    }
}
