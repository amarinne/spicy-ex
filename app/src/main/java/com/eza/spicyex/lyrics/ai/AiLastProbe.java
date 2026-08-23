package com.eza.spicyex.lyrics.ai;

/**
 * The most recent structured-output probe result in this process.
 *
 * <p>Process-local on purpose. The durable part of a probe — identity, measured spend, failure
 * token — lives in {@link AiProbeMeasurement} and survives restarts; the verbatim exchange does
 * not, because a trace belongs to the run that produced it. A diagnostic report composed in this
 * process attaches the real bytes; an older report simply carries the durable half. Safe to hold
 * and show verbatim because the fixture contains no listener data.
 */
public final class AiLastProbe {
    private static volatile AiModelProbe.Result last;

    private AiLastProbe() {
    }

    public static void record(AiModelProbe.Result result) {
        last = result;
    }

    public static AiModelProbe.Result get() {
        return last;
    }

    static synchronized void clearForTest() {
        last = null;
    }
}
