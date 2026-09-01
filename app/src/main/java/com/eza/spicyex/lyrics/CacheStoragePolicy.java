package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central retention policy for the six owned lyric/AI caches.
 *
 * <p>The user picks one total logical-payload budget ("Cache size"); it is allocated across the
 * stores by fixed shares, not handed to each store independently:
 * paid AI 50% (receives the integer rounding remainder), Sound 15%, Meaning 10%, Google
 * processing values 10%, canonical lyric source 10%, raw provider lyric responses 5%.
 *
 * <p>{@link #UNLIMITED} is a deliberate sentinel, not a very large budget: quota consumers must
 * treat it as "no byte or entry-count eviction", and every size comparison in this class is
 * written so no arithmetic can overflow into a false eviction.
 *
 * <p>This class changes retention and capacity only. Cache identity rules (canonical digest,
 * provider, model, prompt contract, target language, reading configuration) are untouched.
 */
public final class CacheStoragePolicy {
    public static final String OPTION_32_MB = "32 MB";
    public static final String OPTION_128_MB = "128 MB";
    public static final String OPTION_512_MB = "512 MB";
    public static final String OPTION_1024_MB = "1024 MB";
    public static final String OPTION_NO_LIMIT = "No limit";
    public static final String DEFAULT_OPTION = OPTION_128_MB;
    public static final List<String> OPTIONS = Arrays.asList(
            OPTION_32_MB, OPTION_128_MB, OPTION_512_MB, OPTION_1024_MB, OPTION_NO_LIMIT);

    /** Deliberate unlimited sentinel: never use a "large" number as unlimited. */
    public static final long UNLIMITED = Long.MAX_VALUE;

    private static final long KIB = 1024L;

    private CacheStoragePolicy() {
    }

    // --- Pure parsing and allocation (package-visible for orchestrator tests) ---------------

    /** Parses one stored setting value into a total byte budget, or {@link #UNLIMITED}. */
    static long totalBudgetBytes(String stored) {
        if (stored == null) return totalBudgetBytes(DEFAULT_OPTION);
        String value = stored.trim();
        if (value.isEmpty()) return totalBudgetBytes(DEFAULT_OPTION);
        if (OPTION_NO_LIMIT.equalsIgnoreCase(value)) return UNLIMITED;
        String upper = value.toUpperCase(Locale.ROOT);
        int split = upper.lastIndexOf(' ');
        long multiplier;
        if (split <= 0) {
            multiplier = 1L;
            split = upper.length();
        } else {
            String unit = upper.substring(split + 1).trim();
            if ("B".equals(unit)) multiplier = 1L;
            else if ("KB".equals(unit)) multiplier = KIB;
            else if ("MB".equals(unit)) multiplier = KIB * KIB;
            else if ("GB".equals(unit)) multiplier = KIB * KIB * KIB;
            else return totalBudgetBytes(DEFAULT_OPTION);
        }
        long amount;
        try {
            amount = Long.parseLong(upper.substring(0, split).trim());
        } catch (NumberFormatException ignored) {
            return totalBudgetBytes(DEFAULT_OPTION);
        }
        if (amount < 0L) return totalBudgetBytes(DEFAULT_OPTION);
        return saturatingMultiply(amount, multiplier);
    }

    static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (Long.MAX_VALUE / left < right) return Long.MAX_VALUE;
        return left * right;
    }

    static long saturatingAdd(long left, long right) {
        long a = Math.max(0L, left);
        long b = Math.max(0L, right);
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static long share(long totalBytes, int percent) {
        if (totalBytes == UNLIMITED) return UNLIMITED;
        long total = Math.max(0L, totalBytes);
        return saturatingMultiply(total, percent) / 100L;
    }

    /** Sound artifacts: 15% of the total budget. */
    public static long soundQuota(long totalBytes) {
        return share(totalBytes, 15);
    }

    /** Meaning artifacts: 10% of the total budget. */
    public static long meaningQuota(long totalBytes) {
        return share(totalBytes, 10);
    }

    /** Google processing values: 10% of the total budget. */
    public static long googleQuota(long totalBytes) {
        return share(totalBytes, 10);
    }

    /** Canonical lyric source: 10% of the total budget. */
    public static long canonicalQuota(long totalBytes) {
        return share(totalBytes, 10);
    }

    /** Raw provider lyric responses: 5% of the total budget. */
    public static long rawResponseQuota(long totalBytes) {
        return share(totalBytes, 5);
    }

    /**
     * Paid AI artifacts: 50% of the total budget plus the integer rounding remainder left by the
     * floor-rounded shares above.
     */
    public static long paidAiQuota(long totalBytes) {
        if (totalBytes == UNLIMITED) return UNLIMITED;
        long total = Math.max(0L, totalBytes);
        long others = 0L;
        others = saturatingAdd(others, soundQuota(total));
        others = saturatingAdd(others, meaningQuota(total));
        others = saturatingAdd(others, googleQuota(total));
        others = saturatingAdd(others, canonicalQuota(total));
        others = saturatingAdd(others, rawResponseQuota(total));
        return Math.max(0L, total - others);
    }

    // --- Android accessors -----------------------------------------------------------------

    /** Reads the stored "Cache size" setting from Spotify-side preferences. */
    public static long totalBudget(Context context) {
        if (context == null) return totalBudgetBytes(DEFAULT_OPTION);
        try {
            String stored = context.getSharedPreferences(
                    SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(Settings.CACHE_SIZE.key, Settings.CACHE_SIZE.defaultValue);
            return totalBudgetBytes(Settings.CACHE_SIZE.coerce(stored));
        } catch (Throwable t) {
            return totalBudgetBytes(DEFAULT_OPTION);
        }
    }

    /**
     * Combined logical-payload bytes across the six owned stores, for the settings panel usage
     * display. Counts stored payloads only (UTF-8 string bytes, SQLite {@code raw_bytes}); order
     * keys, timestamps, reservations, and the legacy paid-AI migration XML are never counted.
     * A corrupt or unreadable store contributes zero, never a failure.
     */
    public static long storedTotal(Context context) {
        if (context == null) return 0L;
        long total = 0L;
        total = saturatingAdd(total, LyricCaches.soundStoreUsageBytes(context));
        total = saturatingAdd(total, LyricCaches.meaningStoreUsageBytes(context));
        total = saturatingAdd(total, LyricCaches.googleStoreUsageBytes(context));
        total = saturatingAdd(total, CanonicalSourceCache.usageBytes(context));
        total = saturatingAdd(total, LyricsResponseCache.usageBytes(context));
        total = saturatingAdd(total, AIPaidArtifactCache.usageBytes(context));
        return total;
    }

    /** Sums UTF-8 payload bytes of one preference-backed cache, skipping its order key. */
    public static long preferenceStoreUsage(Context context, String prefsName, String orderKey) {
        if (context == null) return 0L;
        try {
            Map<String, ?> all = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .getAll();
            if (all == null || all.isEmpty()) return 0L;
            long total = 0L;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (!(entry.getValue() instanceof String)) continue;
                if (entry.getKey() != null && entry.getKey().equals(orderKey)) continue;
                total = saturatingAdd(total,
                        ((String) entry.getValue()).getBytes(StandardCharsets.UTF_8).length);
            }
            return total;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // --- Formatting ------------------------------------------------------------------------

    /** Compact binary size: B, KB, MB, GB; one decimal only when it carries information. */
    public static String formatBytes(long bytes) {
        long safe = Math.max(0L, bytes);
        if (safe < KIB) return safe + " B";
        double kb = safe / (double) KIB;
        if (kb < KIB) return unit(kb, "KB");
        double mb = kb / (double) KIB;
        if (mb < KIB) return unit(mb, "MB");
        return unit(mb / (double) KIB, "GB");
    }

    private static String unit(double value, String suffix) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (rounded >= 1024.0) {
            return unit(rounded / KIB, suffix.equals("KB") ? "MB" : "GB");
        }
        if (rounded == Math.floor(rounded)) {
            return String.format(Locale.US, "%.0f %s", rounded, suffix);
        }
        return String.format(Locale.US, "%.1f %s", rounded, suffix);
    }
}
