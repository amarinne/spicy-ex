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
        return score + lineSanityBonus(doc);
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
                if (sync == Sync.LINE) return 6000;
                if (sync == Sync.STATIC) return 2500;
            }
            if (sync == Sync.STATIC) return 1500;
            if (sync == Sync.SYLLABLE) return 3500;
            if (sync == Sync.LINE) return 3400;
            return 1000;
        }

        if (source == Source.NATIVE) {
            if (sync == Sync.SYLLABLE) return 5000;
            if (sync == Sync.LINE) return 5000;
            if (sync == Sync.STATIC) return 3000;
            return 2000;
        }

        if (source == Source.LRCLIB) {
            if (sync == Sync.SYLLABLE || sync == Sync.LINE) return 4000;
            if (sync == Sync.STATIC) return 2000;
            return 1000;
        }

        if (sync == Sync.SYLLABLE) return 1200;
        if (sync == Sync.LINE) return 1100;
        if (sync == Sync.STATIC) return 500;
        return 0;
    }

    public static boolean prefer(LyricsDocument candidate, LyricsDocument currentBest) {
        return score(candidate) > score(currentBest);
    }

    private static int lineSanityBonus(LyricsDocument doc) {
        if (doc.lines == null || doc.lines.isEmpty()) return -1000;
        return Math.min(doc.lines.size(), 200);
    }

    private static Source sourceOf(String fetchSource, String provider) {
        String source = LyricsDocument.safe(fetchSource).toLowerCase(java.util.Locale.US);
        if (source.contains("lrclib")) return Source.LRCLIB;
        if (source.contains("spotify_native") || source.contains("native spotify")) return Source.NATIVE;
        if (source.contains("spicy")) return Source.SPICY;

        String providerLabel = LyricsDocument.safe(provider).toLowerCase(java.util.Locale.US);
        if (providerLabel.contains("lrclib")) return Source.LRCLIB;
        if (providerLabel.contains("native spotify") || providerLabel.contains("musixmatch")) {
            return Source.NATIVE;
        }
        if (providerLabel.contains("spicy")) return Source.SPICY;
        return Source.UNKNOWN;
    }

    private static Sync syncOf(String type) {
        if ("Syllable".equalsIgnoreCase(type)) return Sync.SYLLABLE;
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
        LINE,
        STATIC,
        UNKNOWN
    }
}
