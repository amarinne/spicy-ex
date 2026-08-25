package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.EnumMap;

/** Process-local monitor data for recent paid attempts per layer. */
public final class AiRequestLiveState {
    public enum Phase { NONE, PREPARING, RUNNING, COMPLETE, FAILED, CANCELLED }

    /**
     * How much accumulated reasoning one attempt may retain.
     *
     * <p>A trace is prose written by a model that was asked to think, and on a long document across
     * several chunks it can outgrow the request bodies beside it many times over. This state is
     * process-local and read by a dialog, so the bound is about not holding an unbounded string for
     * the life of the process rather than about any wire limit; the oldest text is the part dropped,
     * because the end of a trace is where the model says what it concluded.
     */
    private static final int MAX_REASONING_CHARS = 96_000;

    public static final class Attempt {
        public final Phase phase;
        public final String payload;
        /** Thinking the model returned for this run, empty when it returned none. */
        public final String reasoning;
        public final String failureToken;
        public final int httpStatus;
        public final String failureDetail;

        private Attempt(Phase phase, String payload, String reasoning, String failureToken,
                        int httpStatus, String failureDetail) {
            this.phase = phase == null ? Phase.NONE : phase;
            this.payload = nz(payload);
            this.reasoning = nz(reasoning);
            this.failureToken = nz(failureToken);
            this.httpStatus = httpStatus;
            this.failureDetail = nz(failureDetail);
        }

        /** The same attempt in a new phase; both retained texts carry over unchanged. */
        private Attempt settled(Phase next, String failureToken, int httpStatus,
                                String failureDetail) {
            return new Attempt(next, payload, reasoning, failureToken, httpStatus, failureDetail);
        }

        public boolean hasPayload() {
            return !payload.isEmpty();
        }

        public boolean hasReasoning() {
            return !reasoning.isEmpty();
        }

        public boolean isFailure() {
            return phase == Phase.FAILED;
        }
    }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(AttemptEmpty.VALUE, AttemptEmpty.VALUE);

        public final Attempt current;
        public final Attempt previousFailure;
        public final Attempt lastSettled;

        private Snapshot(Attempt current, Attempt previousFailure) {
            this(current, previousFailure, AttemptEmpty.VALUE);
        }

        private Snapshot(Attempt current, Attempt previousFailure, Attempt lastSettled) {
            this.current = current == null ? AttemptEmpty.VALUE : current;
            this.previousFailure = previousFailure == null ? AttemptEmpty.VALUE : previousFailure;
            this.lastSettled = lastSettled == null ? AttemptEmpty.VALUE : lastSettled;
        }
    }

    private static final class Entry {
        String canonicalDigest = "";
        String runId = "";
        Attempt current = AttemptEmpty.VALUE;
        Attempt previousFailure = AttemptEmpty.VALUE;
        Attempt lastSettled = AttemptEmpty.VALUE;
    }

    private static final class AttemptEmpty {
        static final Attempt VALUE = new Attempt(Phase.NONE, "", "", "", 0, "");
    }

    private static final EnumMap<LayerKind, Entry> ENTRIES = new EnumMap<>(LayerKind.class);

    private AiRequestLiveState() {
    }

    public static synchronized void begin(LayerKind layer, String canonicalDigest, String runId) {
        if (layer == null) return;
        Entry entry = entry(layer);
        if (entry.current.isFailure()) entry.previousFailure = entry.current;
        entry.canonicalDigest = nz(canonicalDigest);
        entry.runId = nz(runId);
        entry.current = new Attempt(Phase.PREPARING, "", "", "", 0, "");
    }

    public static synchronized void attempt(LayerKind layer, String canonicalDigest, String runId,
                                            String chunkId, int attempt, String wirePayload) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null) return;
        StringBuilder payload = new StringBuilder(entry.current.payload);
        if (payload.length() > 0) payload.append("\n\n");
        payload.append("Attempt ").append(Math.max(1, attempt));
        String chunk = nz(chunkId);
        if (!chunk.isEmpty()) payload.append(" · ").append(chunk);
        payload.append('\n').append(nz(wirePayload));
        entry.current = new Attempt(Phase.RUNNING, payload.toString(),
                entry.current.reasoning, "", 0, "");
    }

    /**
     * The reasoning an attempt answered with, appended under the same heading its body carried.
     *
     * <p>Leaves the phase alone rather than asserting RUNNING. A trace lands when a call returns,
     * which is inside the run and always before the lane settles it, so the phase here is whatever
     * the run already reached and this has no business changing it. Whether the attempt then
     * succeeded or failed is decided above; both keep their reasoning, and the failing ones are
     * where reading it matters most.
     */
    public static synchronized void reasoning(LayerKind layer, String canonicalDigest, String runId,
                                              String chunkId, int attempt, String trace) {
        Entry entry = matching(layer, canonicalDigest, runId);
        String text = nz(trace).trim();
        if (entry == null || text.isEmpty()) return;
        StringBuilder reasoning = new StringBuilder(entry.current.reasoning);
        if (reasoning.length() > 0) reasoning.append("\n\n");
        reasoning.append("Attempt ").append(Math.max(1, attempt));
        String chunk = nz(chunkId);
        if (!chunk.isEmpty()) reasoning.append(" \u00b7 ").append(chunk);
        reasoning.append('\n').append(text);
        entry.current = new Attempt(entry.current.phase, entry.current.payload,
                bounded(reasoning), entry.current.failureToken, entry.current.httpStatus,
                entry.current.failureDetail);
    }

    /** Keeps the newest {@link #MAX_REASONING_CHARS}; a clipped trace says so at its head. */
    private static String bounded(StringBuilder reasoning) {
        if (reasoning.length() <= MAX_REASONING_CHARS) return reasoning.toString();
        return "\u2026 (earlier reasoning dropped)\n"
                + reasoning.substring(reasoning.length() - MAX_REASONING_CHARS);
    }

    public static synchronized void fail(LayerKind layer, String canonicalDigest, String runId,
                                         String token, int httpStatus, String detail) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null) return;
        entry.current = entry.current.settled(Phase.FAILED, token, httpStatus, detail);
        if (entry.current.hasPayload()) entry.lastSettled = entry.current;
    }

    public static synchronized void complete(LayerKind layer, String canonicalDigest, String runId) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null || entry.current.isFailure()) return;
        entry.current = entry.current.settled(Phase.COMPLETE, "", 0, "");
        if (entry.current.hasPayload()) entry.lastSettled = entry.current;
    }

    public static synchronized void cancel(LayerKind layer, String canonicalDigest, String runId) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null) return;
        entry.current = entry.current.settled(Phase.CANCELLED, "", 0, "");
        if (entry.current.hasPayload()) entry.lastSettled = entry.current;
    }

    public static synchronized Snapshot snapshot(LayerKind layer, String canonicalDigest) {
        if (layer == null) return Snapshot.EMPTY;
        Entry entry = ENTRIES.get(layer);
        if (entry == null || !nz(canonicalDigest).equals(entry.canonicalDigest)) {
            return Snapshot.EMPTY;
        }
        return new Snapshot(entry.current, entry.previousFailure, entry.lastSettled);
    }

    /** Digest-independent recent state for an explicit user-triggered diagnostic report. */
    public static synchronized Snapshot diagnosticSnapshot(LayerKind layer) {
        Entry entry = layer == null ? null : ENTRIES.get(layer);
        return entry == null ? Snapshot.EMPTY
                : new Snapshot(entry.current, entry.previousFailure, entry.lastSettled);
    }

    /**
     * The most recent failed attempt for a layer, whatever document it belonged to.
     *
     * <p>Digest-independent, so a diagnostic report can answer "why did AI fail last" without
     * knowing which song was on screen when it did. The current attempt wins over the retained
     * previous one; both are read-only views.
     */
    public static synchronized Attempt lastFailure(LayerKind layer) {
        Entry entry = layer == null ? null : ENTRIES.get(layer);
        if (entry == null) return AttemptEmpty.VALUE;
        if (entry.current.isFailure()) return entry.current;
        return entry.previousFailure.isFailure() ? entry.previousFailure : AttemptEmpty.VALUE;
    }

    /** Test hook: resets all live state. Public because report-factory tests run cross-package. */
    public static synchronized void clearForTest() {
        ENTRIES.clear();
    }

    private static Entry entry(LayerKind layer) {
        Entry entry = ENTRIES.get(layer);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(layer, entry);
        }
        return entry;
    }

    private static Entry matching(LayerKind layer, String canonicalDigest, String runId) {
        if (layer == null) return null;
        Entry entry = ENTRIES.get(layer);
        return entry != null
                && nz(canonicalDigest).equals(entry.canonicalDigest)
                && nz(runId).equals(entry.runId) ? entry : null;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
