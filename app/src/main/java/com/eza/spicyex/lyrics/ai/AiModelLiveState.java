package com.eza.spicyex.lyrics.ai;

/** One process-local liveness result for the selected provider/model configuration. */
public final class AiModelLiveState {
    private enum State { UNKNOWN, CHECKING, LIVE, UNAVAILABLE }

    private static String identity = "";
    private static State state = State.UNKNOWN;

    private AiModelLiveState() {
    }

    /** Claims the one initialization probe for this configuration. */
    public static synchronized boolean begin(AiSettings settings) {
        String next = identityOf(settings);
        if (!next.equals(identity)) {
            identity = next;
            state = State.UNKNOWN;
        }
        if (settings == null || settings.readiness() != AiSettings.Readiness.READY
                || state != State.UNKNOWN) {
            return false;
        }
        state = State.CHECKING;
        return true;
    }

    public static synchronized void finish(AiSettings settings, boolean live) {
        if (!identityOf(settings).equals(identity)) return;
        state = live ? State.LIVE : State.UNAVAILABLE;
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
    }

    static String identityOf(AiSettings settings) {
        if (settings == null) return "";
        return settings.providerChoice() + '\n' + settings.endpoint() + '\n' + settings.modelName();
    }
}
