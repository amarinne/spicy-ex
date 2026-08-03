package com.eza.spicyex.lyrics;

import com.eza.spicyex.Diagnostics;

import java.util.List;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Session-local, privacy-safe snapshot of last lyric fetch arbitration. */
public final class LyricsFetchDiagnosticsState {
    private static volatile Snapshot last = new Snapshot(
            "none", "none", "unknown", "", "Unknown", "", "", false,
            "unknown", false, "unknown", false, 0, 0L);

    private LyricsFetchDiagnosticsState() {
    }

    public static void record(String sourceChosen, List<String> candidatesSeen, LyricsDocument chosen,
                              boolean tokenPresent, boolean cacheWrite) {
        String status = chosen == null || chosen.spicyQueryStatus == null
                ? "unknown"
                : String.valueOf(chosen.spicyQueryStatus);
        String poison = chosen == null || isBlank(chosen.spicyQualityReason)
                ? "ok"
                : safeStatus(chosen.spicyQualityReason);
        last = new Snapshot(
                safeStatus(sourceChosen),
                joinCandidates(candidatesSeen),
                chosen == null ? "unknown" : safeStatus(chosen.provider),
                chosen == null ? "" : safeStatus(chosen.language),
                chosen == null ? "Unknown" : safeStatus(chosen.type),
                safeStatus(SpicyVersionProbeState.spicyVersionSent),
                safeStatus(SpicyVersionProbeState.spicyLatestVersion),
                tokenPresent,
                status,
                chosen != null && chosen.spicyPackedPayload,
                chosen != null && chosen.spicyPoisoned ? poison : "ok",
                cacheWrite,
                candidatesSeen == null ? 0 : candidatesSeen.size(),
                System.currentTimeMillis());
        Diagnostics.event("lyrics_fetch", "provider_arbitration",
                Diagnostics.context(
                        "source", sourceChosen,
                        "provider", chosen == null ? "unknown" : chosen.provider,
                        "status", status,
                        "language", chosen == null ? "" : chosen.language,
                        "timingType", chosen == null ? "Unknown" : chosen.type,
                        "cache", cacheWrite ? "write" : "no_write"));
    }

    public static Snapshot get() {
        return last;
    }

    private static String joinCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (String candidate : candidates) {
            String safe = safeStatus(candidate);
            if (safe.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(safe);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String safeStatus(String value) {
        if (value == null) return "";
        return value.replaceAll("[^0-9A-Za-z._:+,-]", "_");
    }

    public static final class Snapshot {
        public final String sourceChosen;
        public final String candidatesSeen;
        public final String provider;
        public final String language;
        public final String typeChosen;
        public final String spicyVersionSent;
        public final String spicyLatestVersion;
        public final boolean tokenPresent;
        public final String spicyQueryStatus;
        public final boolean packedPayload;
        public final String poisonResult;
        public final boolean cacheWrite;
        public final int candidateCount;
        public final long recordedAtMs;

        private Snapshot(String sourceChosen, String candidatesSeen, String provider, String language,
                         String typeChosen,
                         String spicyVersionSent, String spicyLatestVersion, boolean tokenPresent,
                         String spicyQueryStatus, boolean packedPayload, String poisonResult,
                         boolean cacheWrite, int candidateCount, long recordedAtMs) {
            this.sourceChosen = sourceChosen;
            this.candidatesSeen = candidatesSeen;
            this.provider = provider;
            this.language = language;
            this.typeChosen = typeChosen;
            this.spicyVersionSent = spicyVersionSent;
            this.spicyLatestVersion = spicyLatestVersion;
            this.tokenPresent = tokenPresent;
            this.spicyQueryStatus = spicyQueryStatus;
            this.packedPayload = packedPayload;
            this.poisonResult = poisonResult;
            this.cacheWrite = cacheWrite;
            this.candidateCount = candidateCount;
            this.recordedAtMs = recordedAtMs;
        }
    }
}
