package com.eza.spicyex.lyrics.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An in-memory paid store, keyed exactly as the durable one is.
 *
 * <p>It round-trips every record through the real codec rather than handing back the same object.
 * A store that returned the live instance would let a test pass while the encoder silently dropped
 * a field, and the field most likely to be dropped is the one that makes a resume work.
 */
public final class FakeAiRecordStore implements AiRecordStore {

    private final Map<String, String> payloads = new LinkedHashMap<>();

    public int reads;
    public int commits;
    /** When true, every write is rejected — the store-is-full path. */
    public boolean rejectWrites;

    @Override public AiPaidRecord read(AiRunConfig config) {
        reads++;
        AiPaidRecord record = AiPaidRecordCodec.decode(payloads.get(key(config)));
        return record != null && config.matches(record) ? record : null;
    }

    @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
        commits++;
        if (rejectWrites) return false;
        payloads.put(key(config), AiPaidRecordCodec.encode(record));
        return true;
    }

    @Override public void forget(AiRunConfig config) {
        payloads.remove(key(config));
    }

    public boolean isEmpty() {
        return payloads.isEmpty();
    }

    /** The stored record as it would come back after a process restart. */
    public AiPaidRecord peek(AiRunConfig config) {
        return AiPaidRecordCodec.decode(payloads.get(key(config)));
    }

    private static String key(AiRunConfig config) {
        return config.recordIdentity().storageKey();
    }
}
