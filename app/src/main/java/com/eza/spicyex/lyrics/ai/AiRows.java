package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ReadingLanguagePolicy;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the canonical base into the rows the protocol sends.
 *
 * <p>Row identity comes from {@link CanonicalBase} and nothing else, because that is what the paid
 * record is keyed by and what an accepted answer is addressed to. The parsed document is consulted
 * only for evidence the canonical projection does not carry — currently the layout voice hint —
 * and is never read for text: canonical text is the source of truth, and taking it from a document
 * that derived layers have written into is how a translation ends up being translated again.
 *
 * <p><b>Known limitation, deliberate for now.</b> {@code CanonicalBase} creates one row per lyric
 * line, so background vocals — which live inside a line rather than beside it — have no stable row
 * ID and cannot be addressed by the protocol. Desktop sends them. Giving them canonical rows
 * changes the canonical digest and therefore invalidates every stored base and derived artifact on
 * every device, so it is a decision to take deliberately rather than a detail to slip in here.
 * Until then AI covers lead lines only.
 */
public final class AiRows {

    private AiRows() {
    }

    /**
     * Meaning rows: every canonical row, classified from its own text.
     *
     * <p>Every non-structural row is sent, including short same-script ones. Filtering by "does
     * this look like it needs translating" is exactly what hides code-switching from the model,
     * which is one of the cases AI is here for.
     */
    public static List<AiLine> forMeaning(CanonicalBase base, LyricsDocument document) {
        return forMeaning(base, document, null, false);
    }

    /** Meaning rows with an optional Google Translate draft carried as {@code p}. */
    public static List<AiLine> forMeaning(CanonicalBase base, LyricsDocument document,
                                          MeaningArtifact baseline, boolean useBaseline) {
        List<AiLine> rows = new ArrayList<>();
        if (base == null) return rows;
        for (CanonicalRow row : base.rows) {
            if (row == null) continue;
            MeaningEntry draft = baseline == null ? null : baseline.meaning(row.rowId);
            String baselineText = useBaseline && draft != null ? draft.text : null;
            rows.add(AiLine.withBaseline(row.rowId, row.text, voiceOf(document, row), false,
                    baselineText, baselineText == null ? null : "google"));
        }
        return rows;
    }

    /**
     * Sound rows: every canonical row, with the rows the local pipeline already covered marked as
     * not-to-send.
     *
     * <p>Covered and already-legible rows stay in the list so the document digest describes the
     * whole document — a record produced when three rows were gaps must not be reused after a
     * reading-mode change turned one of them into five.
     *
     * @param existing what the Sound lane produced for this base, or null before it has run
     * @param orthography target orthography the readings are wanted in
     * @param useBaseline layered mode; false sends canonical source alone
     */
    public static List<AiLine> forSound(CanonicalBase base, LyricsDocument document,
                                        SoundArtifact existing, String orthography,
                                        boolean useBaseline) {
        List<AiLine> rows = new ArrayList<>();
        if (base == null) return rows;
        for (CanonicalRow row : base.rows) {
            if (row == null) continue;
            SoundEntry entry = existing == null ? null : existing.sound(row.rowId);
            AiLineClass lineClass = AiLineClassifier.classify(row.text);
            boolean unresolvedHan = unresolvedHan(document, row);
            boolean gap = !unresolvedHan && lineClass != AiLineClass.STRUCTURAL
                    && AiSoundCoverage.isGap(row.text, entry, orthography);
            String baseline = useBaseline && gap
                    ? AiSoundCoverage.baselineFor(entry, orthography) : null;
            rows.add(new AiLine(row.rowId, lineClass,
                    gap ? AiSendDisposition.SENT : AiSendDisposition.STRUCTURAL,
                    row.text, voiceOf(document, row), true, baseline,
                    AiSoundCoverage.baselineProvenance(baseline)));
        }
        return rows;
    }

    private static boolean unresolvedHan(LyricsDocument document, CanonicalRow row) {
        LyricsLine line = lineAt(document, row);
        return line != null && line.detection != null
                && ReadingLanguagePolicy.unresolvedHan(line.text, line.detection);
    }

    /** True when at least one row would be sent. Nothing to send means nothing to bill. */
    public static boolean hasWork(List<AiLine> rows) {
        if (rows == null) return false;
        for (AiLine row : rows) if (row != null && row.isSent()) return true;
        return false;
    }

    /**
     * Layout-only voice hint from the parsed line, or null when there is no role evidence.
     *
     * <p>Read from the document rather than the base because the canonical projection does not
     * record alignment. It is a rendering fact, not a claim about who is singing, and the prompt
     * says so: it may carry pronoun and register continuity, never invented identity.
     */
    private static AiVoiceHint voiceOf(LyricsDocument document, CanonicalRow row) {
        LyricsLine line = lineAt(document, row);
        if (line == null) return null;
        return line.oppositeAligned ? AiVoiceHint.ALTERNATE : AiVoiceHint.PRIMARY;
    }

    /**
     * The parsed line for a canonical row, matched by index and confirmed by text.
     *
     * <p>The text check is what makes the index safe: if the document has been re-parsed into a
     * different shape, the row is left without a hint rather than picking up a neighbour's.
     */
    private static LyricsLine lineAt(LyricsDocument document, CanonicalRow row) {
        if (document == null || row == null) return null;
        if (row.index < 0 || row.index >= document.lines.size()) return null;
        LyricsLine line = document.lines.get(row.index);
        if (line == null) return null;
        return row.text.equals(AiText.nz(line.text)) ? line : null;
    }
}
