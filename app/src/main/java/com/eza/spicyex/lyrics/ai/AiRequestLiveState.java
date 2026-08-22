package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.EnumMap;

/** Process-local monitor data for the current and previous failed paid attempt per layer. */
public final class AiRequestLiveState {
    public enum Phase { NONE, PREPARING, RUNNING, COMPLETE, FAILED, CANCELLED }

    public static final class Attempt {
        public final Phase phase;
        public final String payload;
        public final String failureToken;
        public final int httpStatus;
        public final String failureDetail;

        private Attempt(Phase phase, String payload, String failureToken, int httpStatus,
                        String failureDetail) {
            this.phase = phase == null ? Phase.NONE : phase;
            this.payload = nz(payload);
            this.failureToken = nz(failureToken);
            this.httpStatus = httpStatus;
            this.failureDetail = nz(failureDetail);
        }

        public boolean hasPayload() {
            return !payload.isEmpty();
        }

        public boolean isFailure() {
            return phase == Phase.FAILED;
        }
    }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(AttemptEmpty.VALUE, AttemptEmpty.VALUE);

        public final Attempt current;
        public final Attempt previousFailure;

        private Snapshot(Attempt current, Attempt previousFailure) {
            this.current = current == null ? AttemptEmpty.VALUE : current;
            this.previousFailure = previousFailure == null ? AttemptEmpty.VALUE : previousFailure;
        }
    }

    private static final class Entry {
        String canonicalDigest = "";
        String runId = "";
        Attempt current = AttemptEmpty.VALUE;
        Attempt previousFailure = AttemptEmpty.VALUE;
    }

    private static final class AttemptEmpty {
        static final Attempt VALUE = new Attempt(Phase.NONE, "", "", 0, "");
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
        entry.current = new Attempt(Phase.PREPARING, "", "", 0, "");
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
        entry.current = new Attempt(Phase.RUNNING, payload.toString(), "", 0, "");
    }

    public static synchronized void fail(LayerKind layer, String canonicalDigest, String runId,
                                         String token, int httpStatus, String detail) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null) return;
        entry.current = new Attempt(Phase.FAILED, entry.current.payload, token, httpStatus, detail);
    }

    public static synchronized void complete(LayerKind layer, String canonicalDigest, String runId) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null || entry.current.isFailure()) return;
        entry.current = new Attempt(Phase.COMPLETE, entry.current.payload, "", 0, "");
    }

    public static synchronized void cancel(LayerKind layer, String canonicalDigest, String runId) {
        Entry entry = matching(layer, canonicalDigest, runId);
        if (entry == null) return;
        entry.current = new Attempt(Phase.CANCELLED, entry.current.payload, "", 0, "");
    }

    public static synchronized Snapshot snapshot(LayerKind layer, String canonicalDigest) {
        if (layer == null) return Snapshot.EMPTY;
        Entry entry = ENTRIES.get(layer);
        if (entry == null || !nz(canonicalDigest).equals(entry.canonicalDigest)) {
            return Snapshot.EMPTY;
        }
        return new Snapshot(entry.current, entry.previousFailure);
    }

    static synchronized void clearForTest() {
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
