package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

/** Response credit uses the current document and its original retention clock. */
public final class AutoResponseCredit {
    public static final long OUTRO_VISIBLE_MS = 10_000;
    private AutoResponseCredit() { }

    public static boolean documentAllowed(LyricsDocument document, long nowMs) {
        return document != null && !SpicyOrgPolicy.expires(document, nowMs);
    }

    public static boolean packetAllowed(boolean orgSource, long fetchedAtMs, long nowMs) {
        return !orgSource || !SpicyOrgPolicy.expiredAt(fetchedAtMs, nowMs);
    }

    public static boolean outro(boolean synced, long lastVocalEndMs, long positionMs, boolean hasCredit) {
        return hasCredit && synced && lastVocalEndMs > 0
                && positionMs >= lastVocalEndMs + AutoLyricPresentation.OUTRO_HOLD_MS;
    }

    public static boolean hasCredit(String writers, java.util.List<String> labels) {
        return writers != null && !writers.trim().isEmpty() || labels != null && !labels.isEmpty();
    }

    /** Auto uses plain text because its credit surface does not accept interactions. */
    public static String text(String writers, java.util.List<String> labels, String separator) {
        StringBuilder text = new StringBuilder();
        if (writers != null && !writers.trim().isEmpty()) text.append("Written by: ").append(writers.trim());
        if (labels != null) for (int i = 0; i < labels.size(); i++) {
            if (text.length() > 0) text.append(i <= 1 ? separator : ", ");
            text.append(labels.get(i));
        }
        return text.toString();
    }

    /** One host-owned clock survives packet refreshes and view replacement for an outro episode. */
    public static final class Window {
        private String session;
        private long startedAt = -1;

        public boolean update(String session, boolean valid, boolean eligible, boolean synced,
                long lastVocalEndMs, long positionMs, boolean hasCredit, boolean presented, long nowMs) {
            // Missing or stale packets must not reset the episode clock or its session.
            if (!valid) return false;
            if (session != null && !java.util.Objects.equals(this.session, session)) {
                this.session = session;
                startedAt = -1;
            }
            if (synced && lastVocalEndMs > 0
                    && positionMs < lastVocalEndMs + AutoLyricPresentation.OUTRO_HOLD_MS) startedAt = -1;
            if (!eligible || !outro(synced, lastVocalEndMs, positionMs, hasCredit)) return false;
            // Allow the first mount, then start the clock when a matching credit view is visible.
            if (startedAt < 0) {
                if (!presented) return true;
                startedAt = nowMs;
            }
            return nowMs >= startedAt && nowMs - startedAt < OUTRO_VISIBLE_MS;
        }

        public long remainingMs(long nowMs) {
            return startedAt < 0 ? 0 : Math.max(0, OUTRO_VISIBLE_MS - Math.max(0, nowMs - startedAt));
        }
    }
}
