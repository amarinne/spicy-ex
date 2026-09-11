package com.eza.spicyex.lyrics;

/** Pure source-quality scorer for lyric candidate arbitration. */
public final class LyricQualityRanker {
    public static final int REJECT = Integer.MIN_VALUE;

    private LyricQualityRanker() {
    }

    public static int score(LyricsDocument doc) {
        if (doc == null) return REJECT;
        int score = score(doc.fetchSource, doc.type, doc.spicyPoisoned, doc.provider, doc.spicyPackedPayload);
        if (score == REJECT) return REJECT;
        // Provider identity is only a weak prior. Penalize malformed timing so a
        // healthy candidate from another source can win automatic arbitration.
        return score + lineSanityBonus(doc) + timingHealthAdjustment(doc);
    }

    public static int score(String fetchSource, String type, boolean poisoned, String provider) {
        return score(fetchSource, type, poisoned, provider, false);
    }

    public static int score(String fetchSource, String type, boolean poisoned, String provider, boolean spicyPackedPayload) {
        if (poisoned) return REJECT;

        Source source = sourceOf(fetchSource, provider);
        Sync sync = syncOf(type);

        if (source == Source.SPICY) {
            if (spicyPackedPayload) {
                if (sync == Sync.SYLLABLE) return 7000;
                if (sync == Sync.WORD) return 6500;
                if (sync == Sync.LINE) return 6000;
                if (sync == Sync.STATIC) return 2500;
            }
            if (sync == Sync.STATIC) return 1500;
            if (sync == Sync.SYLLABLE) return 3500;
            if (sync == Sync.WORD) return 3450;
            if (sync == Sync.LINE) return 3400;
            return 1000;
        }

        if (source == Source.NATIVE) {
            if (sync == Sync.SYLLABLE) return 5000;
            if (sync == Sync.WORD) return 5000;
            if (sync == Sync.LINE) return 5000;
            if (sync == Sync.STATIC) return 3000;
            return 2000;
        }

        if (source == Source.LRCLIB) {
            if (sync == Sync.SYLLABLE || sync == Sync.WORD || sync == Sync.LINE) return 4000;
            if (sync == Sync.STATIC) return 2000;
            return 1000;
        }

        if (sync == Sync.SYLLABLE) return 1200;
        if (sync == Sync.WORD) return 1150;
        if (sync == Sync.LINE) return 1100;
        if (sync == Sync.STATIC) return 500;
        return 0;
    }

    public static boolean prefer(LyricsDocument candidate, LyricsDocument currentBest) {
        return score(candidate) > score(currentBest);
    }

    /**
     * Auto-ranking comparison, kept deliberately simple: sync level wins first (syllable >
     * word > line > static/none), ties break by source (Apple Music > Spotify > LRCLIB >
     * unknown). Poisoned candidates never win. Source order mode uses {@link #prefer} via
     * fetch position instead.
     */
    public static boolean preferAuto(LyricsDocument candidate, LyricsDocument currentBest) {
        if (candidate == null || candidate.spicyPoisoned) return false;
        if (currentBest == null || currentBest.spicyPoisoned) return true;
        int left = syncLevel(candidate.type);
        int right = syncLevel(currentBest.type);
        if (left != right) return left > right;
        return sourceTier(candidate) > sourceTier(currentBest);
    }

    /** Source tiebreak tier: Apple Music (any fetcher) > Spotify > LRCLIB > unknown. */
    static int sourceTier(LyricsDocument doc) {
        if (doc == null) return -1;
        String hay = (LyricsDocument.safe(doc.fetchSource) + " " + LyricsDocument.safe(doc.provider))
                .toLowerCase(java.util.Locale.US);
        if (hay.contains("apple") || hay.contains("spicy") || hay.contains("aml")
                || hay.contains("lenerd")) {
            return 3;
        }
        if (hay.contains("spotify") || hay.contains("native") || hay.contains("musixmatch")) {
            return 2;
        }
        if (hay.contains("lrclib")) return 1;
        return 0;
    }

    /** Sync-level rank: syllable (3) > word (2) > line (1) > static (0) > unknown (-1). */
    public static int syncLevel(String type) {
        if ("Syllable".equalsIgnoreCase(type)) return 3;
        if ("Word".equalsIgnoreCase(type)) return 2;
        if ("Line".equalsIgnoreCase(type)) return 1;
        if ("Static".equalsIgnoreCase(type)) return 0;
        return -1;
    }

    private static int lineSanityBonus(LyricsDocument doc) {
        if (doc.lines == null || doc.lines.isEmpty()) return -1000;
        return Math.min(doc.lines.size(), 200);
    }

    private static int timingHealthAdjustment(LyricsDocument doc) {
        if (doc.lines == null || doc.lines.isEmpty() || "Static".equalsIgnoreCase(doc.type)) return 0;
        int invalid = 0;
        long previous = Long.MIN_VALUE;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.startMs < 0 || line.endMs < line.startMs) invalid++;
            if (line != null && line.startMs + 250 < previous) invalid++;
            if (line != null) previous = Math.max(previous, line.startMs);
        }
        return invalid == 0 ? 0 : -Math.min(5000, invalid * 1200);
    }

    private static Source sourceOf(String fetchSource, String provider) {
        String source = LyricsDocument.safe(fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("lrclib")) return Source.LRCLIB;
        if (source.contains("spotify_native") || source.contains("native spotify")) return Source.NATIVE;
        if (source.contains("spicy") || source.contains("apple") || source.contains("lenerd")) return Source.SPICY;

        String providerLabel = LyricsDocument.safe(provider).toLowerCase(java.util.Locale.US);
        if (providerLabel.contains("lrclib")) return Source.LRCLIB;
        if (providerLabel.contains("native spotify") || providerLabel.contains("musixmatch")) {
            return Source.NATIVE;
        }
        if (providerLabel.contains("spicy") || providerLabel.contains("apple") || providerLabel.contains("lenerd")) return Source.SPICY;
        return Source.UNKNOWN;
    }

    private static Sync syncOf(String type) {
        if ("Syllable".equalsIgnoreCase(type)) return Sync.SYLLABLE;
        if ("Word".equalsIgnoreCase(type)) return Sync.WORD;
        if ("Line".equalsIgnoreCase(type)) return Sync.LINE;
        if ("Static".equalsIgnoreCase(type)) return Sync.STATIC;
        return Sync.UNKNOWN;
    }

    private enum Source {
        SPICY,
        NATIVE,
        LRCLIB,
        UNKNOWN
    }

    private enum Sync {
        SYLLABLE,
        WORD,
        LINE,
        STATIC,
        UNKNOWN
    }
}
