package com.eza.spicyex.lyrics;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * Pure JVM query planning and candidate selection for LRCLIB.
 *
 * <p>Spotify titles often carry version markers ({@code "allo allo (Remaster)"}) while the synced
 * lyric record sits under the bare title ({@code "Allo Allo"}). Querying the raw title only can
 * therefore deliver a plain-only remaster record while a synced original exists at the same
 * duration. The planner emits a small ordered query plan (raw, then normalized, then free-text)
 * and picks the best merged candidate with duration awareness.
 *
 * <p>Android-free: safe for unit tests.
 */
public final class LrclibQueryPlanner {
    /** Max |candidate - track| duration gap (seconds) before a candidate is treated as junk. */
    static final double MAX_DURATION_DELTA_SEC = 30.0;
    /** Polite gap between follow-up LRCLIB queries (their docs ask 200-500 ms for batches). */
    public static final long FOLLOW_UP_DELAY_MS = 250L;

    /**
     * Trailing version marker: {@code " (Remaster)"}, {@code " [Remix 2011]"},
     * {@code " - Remastered"} (case-insensitive). Only remaster/remix family markers are
     * stripped; live/acoustic/demo markers are kept because they usually denote a different
     * recording with different timing.
     */
    private static final Pattern TRAILING_MARKER = Pattern.compile(
            "[\\s\\-–—]*[\\(\\[](.*?)[\\)\\]]\\s*$|\\s+[\\-–—]\\s*(.*?)\\s*$");

    private static final Pattern VERSION_MARKER_CONTENT = Pattern.compile(
            "^(the\\s+)?(re-?master(?:ed)?|remix)(\\s+\\d{4})?$",
            Pattern.CASE_INSENSITIVE);

    private LrclibQueryPlanner() {}

    /** Bare title with a trailing remaster/remix marker removed; original when nothing applies. */
    public static String normalizedTitle(String title) {
        if (isBlank(title)) return title == null ? "" : title;
        String current = title.trim();
        // Strip at most one marker layer per pass, outside-in, so
        // "Song (Remix) (Remastered 2011)" still collapses without touching the core title.
        for (int pass = 0; pass < 2; pass++) {
            Matcher trailing = TRAILING_MARKER.matcher(current);
            if (!trailing.find()) return current;
            String inner = trailing.group(1) != null ? trailing.group(1) : trailing.group(2);
            if (inner == null || !VERSION_MARKER_CONTENT.matcher(inner.trim()).matches()) return current;
            String stripped = current.substring(0, trailing.start()).trim();
            if (stripped.isEmpty()) return current;
            current = stripped;
        }
        return current;
    }

    /** Ordered query titles: raw first (exact remaster records win ties), then normalized. */
    public static List<String> queryTitles(String rawTitle) {
        String raw = rawTitle == null ? "" : rawTitle.trim();
        String normalized = normalizedTitle(raw);
        if (raw.isEmpty()) return Collections.singletonList("");
        if (normalized.isEmpty() || normalized.equalsIgnoreCase(raw)) {
            return Collections.singletonList(raw);
        }
        List<String> out = new ArrayList<>(2);
        out.add(raw);
        out.add(normalized);
        return Collections.unmodifiableList(out);
    }

    /**
     * Index of the best candidate, or -1 when nothing is usable. Candidates are expected in
     * query-provenance order (raw results first) which breaks residual ties. Prefers synced
     * lyrics, then smallest duration gap, and rejects empty records and absurd durations.
     */
    public static int pickBest(List<JsonObject> candidates, double trackDurationSec) {
        if (candidates == null || candidates.isEmpty()) return -1;
        int best = -1;
        boolean bestSynced = false;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            JsonObject candidate = candidates.get(i);
            if (candidate == null) continue;
            String synced = Json.optString(candidate, "syncedLyrics");
            String plain = Json.optString(candidate, "plainLyrics");
            if (isBlank(synced) && isBlank(plain)) continue;
            boolean syncedAvailable = !isBlank(synced);
            double duration = Json.optDouble(candidate, -1d, "duration");
            double delta;
            if (trackDurationSec > 0 && duration > 0) {
                delta = Math.abs(duration - trackDurationSec);
                if (delta > MAX_DURATION_DELTA_SEC) continue;
            } else {
                delta = Double.MAX_VALUE;
            }
            if (best < 0
                    || (syncedAvailable && !bestSynced)
                    || (syncedAvailable == bestSynced && delta < bestDelta)) {
                best = i;
                bestSynced = syncedAvailable;
                bestDelta = delta;
            }
        }
        return best;
    }

    /** Free-text fallback query ({@code q=title artist}) using the normalized title. */
    public static String freeTextQuery(String rawTitle, String artist) {
        String title = normalizedTitle(rawTitle);
        if (isBlank(title)) title = rawTitle == null ? "" : rawTitle.trim();
        String who = artist == null ? "" : artist.trim();
        return (title + (who.isEmpty() ? "" : " " + who)).trim().toLowerCase(Locale.ROOT);
    }
}
