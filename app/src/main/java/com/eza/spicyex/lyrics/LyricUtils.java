package com.eza.spicyex.lyrics;

/** Shared tiny helpers used across the lyrics + hook code (consolidated from per-file copies). */
public final class LyricUtils {
    private LyricUtils() {
    }

    /** True for null / empty / whitespace-only strings. */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Null-safe string: returns "" instead of null. */
    public static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Extract the bare track id from a Spotify "spotify:track:ID" URI; "" if not a track URI. */
    public static String trackIdFromUri(String uri) {
        if (uri == null) return "";
        String[] parts = uri.split(":");
        if (parts.length >= 3 && "track".equals(parts[1])) return parts[2];
        return "";
    }
}
