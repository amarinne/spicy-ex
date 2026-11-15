package com.eza.spicyex.lyrics.providers;

import java.util.List;

/**
 * Walks a search's word-synced candidates in rank order, then falls back to the top hit's line
 * lyrics. QQ Music and NetEase share this budget and order; only their fetch call differs.
 */
final class WordLyricChain {
    /** Word-synced candidates one search may try before using the line fallback. */
    static final int MAX_ATTEMPTS = 3;

    interface Fetch {
        void fetch(long songId, Runnable next);
    }

    private WordLyricChain() {
    }

    static void run(List<Long> ids, Fetch fetch, Runnable lineFallback) {
        run(ids, 0, fetch, lineFallback);
    }

    private static void run(List<Long> ids, int index, Fetch fetch, Runnable lineFallback) {
        if (index >= ids.size()) {
            lineFallback.run();
            return;
        }
        fetch.fetch(ids.get(index), () -> run(ids, index + 1, fetch, lineFallback));
    }
}
