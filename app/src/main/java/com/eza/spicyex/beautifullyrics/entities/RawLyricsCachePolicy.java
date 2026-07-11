package com.eza.spicyex.beautifullyrics.entities;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RawLyricsCachePolicy {
    private static final String UPDATED_SUFFIX = ":updated";

    private RawLyricsCachePolicy() {
    }

    static Decision planWrite(
            Map<String, ?> stored,
            String writeKey,
            String writeValue,
            long now,
            long maxAgeMs,
            int maxEntries,
            long maxPayloadBytes
    ) {
        List<Entry> entries = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        Set<String> legacy = new HashSet<>();

        for (Map.Entry<String, ?> storedEntry : stored.entrySet()) {
            String key = storedEntry.getKey();
            Object value = storedEntry.getValue();
            if (key.endsWith(UPDATED_SUFFIX) || !(value instanceof String) || key.equals(writeKey)) {
                continue;
            }

            Object updatedValue = stored.get(key + UPDATED_SUFFIX);
            long updatedAt = updatedValue instanceof Long ? (Long) updatedValue : 0L;
            if (updatedAt > 0L && now - updatedAt > maxAgeMs) {
                removed.add(key);
                continue;
            }
            if (updatedAt <= 0L) {
                updatedAt = now;
                legacy.add(key);
            }
            entries.add(new Entry(key, utf8Bytes((String) value), updatedAt, false));
        }

        long writeBytes = utf8Bytes(writeValue);
        boolean retainWrite = writeBytes <= maxPayloadBytes && maxEntries > 0;
        if (retainWrite) {
            entries.add(new Entry(writeKey, writeBytes, now, true));
        }

        Collections.sort(entries, Comparator
                .comparing((Entry entry) -> entry.protectedWrite)
                .thenComparingLong(entry -> entry.updatedAt)
                .thenComparing(entry -> entry.key));

        long totalBytes = 0L;
        for (Entry entry : entries) totalBytes += entry.payloadBytes;
        int retainedEntries = entries.size();
        for (Entry entry : entries) {
            if (retainedEntries <= maxEntries && totalBytes <= maxPayloadBytes) break;
            if (entry.protectedWrite) continue;
            removed.add(entry.key);
            legacy.remove(entry.key);
            retainedEntries--;
            totalBytes -= entry.payloadBytes;
        }

        return new Decision(retainWrite, removed, legacy);
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static final class Decision {
        final boolean retainWrite;
        final Set<String> removedPayloadKeys;
        final Set<String> legacyPayloadKeys;

        Decision(boolean retainWrite, Set<String> removedPayloadKeys, Set<String> legacyPayloadKeys) {
            this.retainWrite = retainWrite;
            this.removedPayloadKeys = removedPayloadKeys;
            this.legacyPayloadKeys = legacyPayloadKeys;
        }
    }

    private static final class Entry {
        final String key;
        final long payloadBytes;
        final long updatedAt;
        final boolean protectedWrite;

        Entry(String key, long payloadBytes, long updatedAt, boolean protectedWrite) {
            this.key = key;
            this.payloadBytes = payloadBytes;
            this.updatedAt = updatedAt;
            this.protectedWrite = protectedWrite;
        }
    }
}
