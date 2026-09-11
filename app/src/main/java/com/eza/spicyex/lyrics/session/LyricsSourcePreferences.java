package com.eza.spicyex.lyrics.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Shared source-selection preferences used by fullscreen and now-playing lyrics. */
public final class LyricsSourcePreferences {
    public enum Source {
        APPLE_MUSIC("apple"), SPICY("spicy"), SPOTIFY("spotify"), LRCLIB("lrclib");
        public final String id;
        Source(String id) { this.id = id; }
        public static Source parse(String value) {
            if (value == null) return null;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (Source source : values()) if (source.id.equals(v) || source.name().toLowerCase(Locale.ROOT).equals(v)) return source;
            if ("apple_music".equals(v) || "aml".equals(v) || "lenerd".equals(v)) return APPLE_MUSIC;
            return null;
        }
        @Override public String toString() { return id; }
    }

    public enum RankingMode {
        AUTO("auto"), SOURCE_ORDER("order");
        public final String id;
        RankingMode(String id) { this.id = id; }
        public static RankingMode parse(String value) {
            if (value == null) return AUTO;
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (RankingMode mode : values()) if (mode.id.equals(v) || mode.name().toLowerCase(Locale.ROOT).equals(v)) return mode;
            if ("auto".equals(v)) return AUTO;
            if ("source order".equals(v)) return SOURCE_ORDER;
            // Legacy three-way ranking collapsed to Auto: both old automatic modes
            // prioritized content over position, so they migrate to AUTO.
            if ("smart ranking".equals(v) || "smart_ranking".equals(v) || "smart".equals(v)) return AUTO;
            if ("sync type".equals(v) || "sync_type".equals(v) || "sync".equals(v)) return AUTO;
            return AUTO;
        }
    }

    private static final String PREFS = "SpotifyPlusLyricsSourceSelection";
    private static final String MODE = "ranking_mode";
    private static final String ORDER = "source_order";
    private static final String ENABLED_PREFIX = "source_enabled_";
    private static final String OVERRIDE_PREFIX = "override_";
    private static final String OVERRIDE_ORDER = "override_order";
    private static final int MAX_OVERRIDES = 200;
    private static final List<Source> DEFAULT_ORDER = Collections.unmodifiableList(
            java.util.Arrays.asList(Source.APPLE_MUSIC, Source.SPICY, Source.SPOTIFY, Source.LRCLIB));

    private LyricsSourcePreferences() {}

    public static RankingMode rankingMode(Context context) {
        if (context == null) return RankingMode.AUTO;
        return RankingMode.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "auto"));
    }

    public static void setRankingMode(Context context, RankingMode mode) {
        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE, (mode == null ? RankingMode.AUTO : mode).id).apply();
    }

    public static List<Source> sourceOrder(Context context) {
        if (context == null) return DEFAULT_ORDER;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ORDER, "");
        return parseOrder(raw);
    }

    public static void setSourceOrder(Context context, List<Source> order) {
        if (context == null) return;
        List<Source> normalized = normalizeOrder(order);
        StringBuilder out = new StringBuilder();
        for (Source source : normalized) { if (out.length() > 0) out.append(','); out.append(source.id); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ORDER, out.toString()).apply();
    }

    public static boolean sourceEnabled(Context context, Source source) {
        if (source == null || source == Source.SPICY) return false;
        if (context == null) return true;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_PREFIX + source.id, true);
    }

    public static void setSourceEnabled(Context context, Source source, boolean enabled) {
        if (context == null || source == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED_PREFIX + source.id, enabled).apply();
    }

    public static List<Source> enabledSourceOrder(Context context) {
        List<Source> result = new ArrayList<>();
        for (Source source : sourceOrder(context)) if (sourceEnabled(context, source)) result.add(source);
        return Collections.unmodifiableList(result);
    }

    /** Returns null for Auto (no track-specific override). */
    public static Source trackOverride(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return null;
        Source source = Source.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(OVERRIDE_PREFIX + Digests.sha256(trackId), null));
        return source;
    }

    /** Selecting null/Auto removes the override. Overrides are bounded to the 200 most recent tracks. */
    public static void setTrackOverride(Context context, String trackId, Source source) {
        if (context == null || trackId == null || trackId.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = OVERRIDE_PREFIX + Digests.sha256(trackId);
        synchronized (LyricsSourcePreferences.class) {
            SharedPreferences.Editor editor = prefs.edit();
            if (source == null) editor.remove(key); else editor.putString(key, source.id);
            LinkedHashMap<String, Long> recency = parseRecency(prefs.getString(OVERRIDE_ORDER, ""));
            recency.remove(key);
            if (source != null) recency.put(key, System.currentTimeMillis());
            while (recency.size() > MAX_OVERRIDES) {
                String eldest = recency.keySet().iterator().next();
                recency.remove(eldest);
                editor.remove(eldest);
            }
            StringBuilder order = new StringBuilder();
            for (String k : recency.keySet()) { if (order.length() > 0) order.append(','); order.append(k); }
            editor.putString(OVERRIDE_ORDER, order.toString()).apply();
        }
    }

    /** String convenience overload used by settings/UI adapters; "auto" clears the override. */
    public static void setTrackOverride(Context context, String trackId, String source) {
        setTrackOverride(context, trackId, Source.parse(source));
    }

    /** Effective source request: an override is strict, otherwise automatic ranking applies. */
    public static Source effectiveOverride(Context context, String trackId) {
        return trackOverride(context, trackId);
    }

    public static Source sourceForTrack(Context context, String trackId) {
        return trackOverride(context, trackId);
    }

    public static String selectionIdentity(Context context, String trackId) {
        RankingMode mode = rankingMode(context);
        List<Source> order = sourceOrder(context);
        Source override = trackOverride(context, trackId);
        StringBuilder raw = new StringBuilder(mode.id).append('|');
        for (Source source : order) raw.append(source.id).append(',');
        raw.append('|');
        for (Source source : Source.values()) raw.append(source.id).append('=').append(sourceEnabled(context, source)).append(',');
        raw.append('|').append(override == null ? "auto" : override.id);
        if (context != null) {
            try {
                com.eza.spicyex.SpotifyPlusConfig config = com.eza.spicyex.SpotifyPlusConfig.from(context);
                String strict = config.get(com.eza.spicyex.Settings.LYRICS_SOURCE_OVERRIDE);
                String token = com.eza.spicyex.lyrics.SpicyManualTokenStore.load(context);
                raw.append("|strict=").append(strict == null ? "Auto" : strict);
                raw.append("|token=").append(token == null || token.isEmpty() ? "none" : Integer.toHexString(token.hashCode()));
            } catch (Throwable ignored) { }
        }
        return Digests.sha256(raw.toString());
    }

    public static boolean cacheCompatible(String cachedIdentity, String currentIdentity) {
        return cachedIdentity != null && currentIdentity != null && cachedIdentity.equals(currentIdentity);
    }

    /** True when the user has explicitly configured source selection in the dedicated store. */
    public static boolean hasExplicitSelection(Context context) {
        if (context == null) return false;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(MODE) || p.contains(ORDER) || !p.getString(OVERRIDE_ORDER, "").isEmpty()) return true;
        for (Source source : Source.values()) if (p.contains(ENABLED_PREFIX + source.id)) return true;
        return false;
    }

    public static List<Source> defaultOrder() { return DEFAULT_ORDER; }

    private static List<Source> parseOrder(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_ORDER;
        List<Source> result = new ArrayList<>();
        for (String part : raw.split(",")) { Source source = Source.parse(part); if (source != null && !result.contains(source)) result.add(source); }
        return result.isEmpty() ? DEFAULT_ORDER : normalizeOrder(result);
    }
    private static List<Source> normalizeOrder(List<Source> order) {
        List<Source> result = new ArrayList<>();
        if (order != null) for (Source source : order) if (source != null && !result.contains(source)) result.add(source);
        for (Source source : DEFAULT_ORDER) if (!result.contains(source)) result.add(source);
        return Collections.unmodifiableList(result);
    }
    private static LinkedHashMap<String, Long> parseRecency(String raw) {
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        if (raw != null) for (String row : raw.split(",")) { if (!row.startsWith(OVERRIDE_PREFIX)) continue; map.put(row, 0L); }
        return map;
    }
}
