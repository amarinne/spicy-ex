package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.language.ReadingLanguagePolicy;
import com.eza.spicyex.lyrics.language.ScriptClassifier;

import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * What a transliteration tap may do to the Sound layer, decided without a view.
 *
 * <p>Two decisions live here because both were previously only visible in a 3,000-line host view:
 * whether a cycle may run at all, and whether a cycle must first clear an AI reading it is about to
 * replace. The second is the one that used to be missed — a stale green AI marker stayed on the chip
 * while the local pass was still running.
 */
public final class SoundModeCyclePolicy {

    private SoundModeCyclePolicy() {
    }

    /**
     * @param transliterationEnabled the layer's own visibility; an unknown config counts as enabled
     *                              because the tap that reached us was already routed to the layer
     */
    public static boolean mayCycle(boolean transliterationEnabled) {
        return transliterationEnabled;
    }

    /** The chip and its action consume the same session-owned row language. */
    public static String readingLanguage(AppliedLine active, LyricsDocument document) {
        String language = readingLanguage(active);
        if (!language.isEmpty()) return language;
        if (document != null) {
            for (AppliedLine row : document.appliedLines) {
                language = readingLanguage(row);
                if (!language.isEmpty()) return language;
            }
        }
        return "";
    }

    private static String readingLanguage(AppliedLine row) {
        if (row == null || row.dotLine || row.bgLine) return "";
        String language = ReadingLanguagePolicy.layoutLanguage(row);
        if ("ja".equals(language) || "zh".equals(language) || "ko".equals(language)) return language;
        ScriptClassifier.ScriptClass script = ScriptClassifier.classify(row.text);
        if (script == ScriptClassifier.ScriptClass.CYRILLIC) return "cy";
        if (script == ScriptClassifier.ScriptClass.GREEK) return "el";
        return "";
    }

    /**
     * A local mode cycle replaces any previously accepted AI reading, so the old authority is
     * cleared before repainting: accepted, still in flight, and failed are all states the chip can
     * still be showing.
     */
    public static boolean clearsStaleAiReading(LyricsDocument document) {
        if (document == null) return false;
        return document.readingFromAi || document.readingAiPending
                || !safe(document.readingAiFailureToken).isEmpty();
    }
}
