package com.eza.spicyex.lyrics;

import java.util.List;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Session-local, privacy-safe snapshot of last lyric fetch arbitration. */
public final class LyricsFetchDiagnosticsState {
    private static volatile Snapshot last = new Snapshot(
            "none", "none", "Unknown", "", "", false, "unknown", false, "unknown", false);

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
                chosen == null ? "Unknown" : safeStatus(chosen.type),
                safeStatus(SpicyVersionProbeState.spicyVersionSent),
                safeStatus(SpicyVersionProbeState.spicyLatestVersion),
                tokenPresent,
                status,
                chosen != null && chosen.spicyPackedPayload,
                chosen != null && chosen.spicyPoisoned ? poison : "ok",
                cacheWrite);
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
        public final String typeChosen;
        public final String spicyVersionSent;
        public final String spicyLatestVersion;
        public final boolean tokenPresent;
        public final String spicyQueryStatus;
        public final boolean packedPayload;
        public final String poisonResult;
        public final boolean cacheWrite;

        private Snapshot(String sourceChosen, String candidatesSeen, String typeChosen,
                         String spicyVersionSent, String spicyLatestVersion, boolean tokenPresent,
                         String spicyQueryStatus, boolean packedPayload, String poisonResult,
                         boolean cacheWrite) {
            this.sourceChosen = sourceChosen;
            this.candidatesSeen = candidatesSeen;
            this.typeChosen = typeChosen;
            this.spicyVersionSent = spicyVersionSent;
            this.spicyLatestVersion = spicyLatestVersion;
            this.tokenPresent = tokenPresent;
            this.spicyQueryStatus = spicyQueryStatus;
            this.packedPayload = packedPayload;
            this.poisonResult = poisonResult;
            this.cacheWrite = cacheWrite;
        }
    }
}
