package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.SoundEntry;

/**
 * Which rows the deterministic pipeline left for AI to fill, and which it already answered.
 *
 * <p>Two independent questions, and conflating them is the mistake this class exists to prevent.
 * <b>Does the row need respelling at all?</b> — a line already written in the target orthography
 * needs nothing from anyone. <b>Did the local pipeline cover it?</b> — if it did, it keeps the row
 * under L1 and no AI request is made for it, automatically or otherwise.
 *
 * <p>Answering only the second question would send every already-Latin line to a model for no
 * reason and bill for it. Answering only the first would let a model overwrite curated readings.
 */
public final class AiSoundCoverage {

    private AiSoundCoverage() {
    }

    /**
     * True when the source text contains letters the reader could not read in {@code orthography}.
     *
     * <p>Latin is treated as universally readable regardless of target: a Latin-script word inside
     * a Japanese line is not a gap for a reader of romaji, and marking it as one would ask a model
     * to respell text that is already legible.
     */
    public static boolean needsRespelling(String sourceText, String orthography) {
        if (!AiContract.isKnownOrthography(orthography)) return false;
        String text = AiText.nz(sourceText);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            if (AiText.isLatin(cp)) continue;
            if (!AiText.isTargetScript(cp, orthography)) return true;
        }
        return false;
    }

    /**
     * True when AI may be asked for this row.
     *
     * <p>A row qualifies only when it needs respelling <em>and</em> the deterministic pipeline did
     * not cover it. A Google whole-line fallback does not count as coverage: it is the lowest tier,
     * and replacing it is exactly what the AI tier is for.
     *
     * @param existing what the Sound lane already produced for this row, or null if nothing did
     */
    public static boolean isGap(String sourceText, SoundEntry existing, String orthography) {
        if (!needsRespelling(sourceText, orthography)) return false;
        if (AiSoundOverlay.hasDeterministicCoverage(existing)) return false;
        return true;
    }

    /**
     * The reading to send as {@code p}, or null when there is nothing worth sending.
     *
     * <p>Only a Google-tier line is offered as a baseline. A deterministic reading is never sent,
     * because a row that has one is not a gap and is not in the request at all; and a baseline that
     * still needs respelling is no help — it would anchor the model to text in the wrong script.
     */
    public static String baselineFor(SoundEntry existing, String orthography) {
        if (existing == null || AiSoundOverlay.hasDeterministicCoverage(existing)) return null;
        String display = AiText.nz(existing.displayText);
        if (display.isEmpty() || needsRespelling(display, orthography)) return null;
        return display;
    }

    /** Provenance token for a baseline produced by {@link #baselineFor}. */
    public static String baselineProvenance(String baseline) {
        return baseline == null ? null : "google";
    }
}
