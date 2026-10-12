package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

/** Response credit uses the current document and its original retention clock. */
public final class AutoResponseCredit {
    public static final long OUTRO_VISIBLE_MS = com.eza.spicyex.lyrics.ResponseCreditOutro.VISIBLE_MS;
    private AutoResponseCredit() { }

    public static boolean documentAllowed(LyricsDocument document, long nowMs) {
        return document != null && !SpicyOrgPolicy.expires(document, nowMs);
    }

    public static boolean packetAllowed(boolean orgSource, long fetchedAtMs, long nowMs) {
        return !orgSource || !SpicyOrgPolicy.expiredAt(fetchedAtMs, nowMs);
    }

    public static boolean outro(boolean synced, long lastVocalEndMs, long positionMs, boolean hasCredit) {
        return com.eza.spicyex.lyrics.ResponseCreditOutro.outro(synced, lastVocalEndMs, positionMs, hasCredit);
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
    public static final class Window extends com.eza.spicyex.lyrics.ResponseCreditOutro.Window { }
}
