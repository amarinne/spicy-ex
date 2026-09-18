package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.lyrics.session.DetectionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detection results for provider-translation text, keyed by the text itself.
 *
 * <p>A provider translation is not a canonical row, so row detection cannot vouch for it. The
 * session detects these strings as auxiliary inputs and persists them under a text-hash key; this
 * store serves the synchronous lookup used by {@link ProviderTranslationResolver}. The in-memory
 * layer is bounded and purely an optimization — durable records survive eviction and restarts.
 */
public final class ProviderTextDetectionStore {
    private static final int MEMORY_CAPACITY = 512;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, DetectionResult> MEMORY =
            new LinkedHashMap<String, DetectionResult>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DetectionResult> eldest) {
                    return size() > MEMORY_CAPACITY;
                }
            };

    private ProviderTextDetectionStore() {
    }

    public static TextDetectionLookup lookup(Context context) {
        return text -> get(context, text);
    }

    /** Memory first, then the durable record. A miss means the text simply has no detection yet. */
    public static DetectionResult get(Context context, String text) {
        if (isBlank(text)) return null;
        synchronized (LOCK) {
            DetectionResult known = MEMORY.get(text);
            if (known != null) return known;
        }
        DetectionResult stored = ProcessedLyricsCache.restoreProviderDetection(context, text);
        if (stored != null) remember(text, stored);
        return stored;
    }

    /** Persists one auxiliary detection; called only by the detection session. */
    public static void put(Context context, String text, DetectionResult result) {
        if (isBlank(text) || result == null) return;
        remember(text, result);
        ProcessedLyricsCache.saveProviderDetection(context, text, result);
    }

    /** Drops the in-memory layer only; durable records are untouched. */
    public static void trimMemory() {
        synchronized (LOCK) {
            MEMORY.clear();
        }
    }

    private static void remember(String text, DetectionResult result) {
        synchronized (LOCK) {
            MEMORY.put(text, result);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
