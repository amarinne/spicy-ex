package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

/** Compact surfaces replace lyrics with a bounded credit episode after the final vocal. */
public final class ResponseCreditOutro {
    public static final long HOLD_MS = 700;
    public static final long VISIBLE_MS = 10_000;
    private ResponseCreditOutro() { }

    public static boolean outro(boolean synced, long lastVocalEndMs, long positionMs, boolean hasCredit) {
        return hasCredit && synced && lastVocalEndMs > 0 && positionMs >= lastVocalEndMs + HOLD_MS;
    }

    public static long lastVocalEndMs(LyricsDocument document) {
        long end = 0;
        if (document != null) for (AppliedLine row : document.appliedLines) {
            if (row == null || row.dotLine) continue;
            end = Math.max(end, LyricTimeline.fillEndMs(row));
            if (row.sourceLine != null) for (BackgroundLine background : row.sourceLine.backgroundLines) {
                if (background != null) end = Math.max(end, background.endMs);
            }
        }
        return end;
    }

    public static final class DocumentWindow {
        private final Window window = new Window();

        public boolean update(LyricsDocument document, long positionMs, boolean presented, long nowMs) {
            if (document == null) return false;
            String session = document.trackId + ":" + document.catalogCandidateId + ":"
                    + document.fetchSource + ":" + document.spicyOrgFetchedAtMs;
            boolean hasCredit = !SpicyOrgAttribution.credits(document).isEmpty()
                    || document.songWriters != null && !document.songWriters.trim().isEmpty();
            return window.update(session, true, !SpicyOrgPolicy.expires(document, System.currentTimeMillis()),
                    !LyricsRenderMode.isStatic(document), lastVocalEndMs(document), positionMs,
                    hasCredit, presented, nowMs);
        }
    }

    /** The clock survives document refreshes and stops even when playback is paused. */
    public static class Window {
        private String session;
        private long startedAt = -1;

        public boolean update(String session, boolean valid, boolean eligible, boolean synced,
                long lastVocalEndMs, long positionMs, boolean hasCredit, boolean presented, long nowMs) {
            if (!valid) return false;
            if (session != null && !java.util.Objects.equals(this.session, session)) {
                this.session = session;
                startedAt = -1;
            }
            if (synced && lastVocalEndMs > 0 && positionMs < lastVocalEndMs + HOLD_MS) startedAt = -1;
            if (!eligible || !outro(synced, lastVocalEndMs, positionMs, hasCredit)) return false;
            if (startedAt < 0) {
                if (!presented) return true;
                startedAt = nowMs;
            }
            return nowMs >= startedAt && nowMs - startedAt < VISIBLE_MS;
        }

        public long remainingMs(long nowMs) {
            return startedAt < 0 ? 0 : Math.max(0, VISIBLE_MS - Math.max(0, nowMs - startedAt));
        }
    }
}
