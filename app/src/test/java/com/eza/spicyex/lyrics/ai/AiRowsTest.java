package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What actually gets sent, and — more expensively — what does not.
 *
 * <p>Every row sent is a row billed, so the interesting assertions are the exclusions: a line
 * already legible in the target orthography, and a line the deterministic engine has spent years
 * learning to read correctly. Getting either wrong costs money and, in the second case, quality.
 */
public class AiRowsTest {

    private static final String LATIN = AiContract.ORTHOGRAPHY_LATIN;

    private static LyricsDocument document(String... texts) {
        LyricsDocument document = new LyricsDocument();
        long start = 0L;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = start;
            line.endMs = start + 1_000L;
            document.lines.add(line);
            start += 1_000L;
        }
        return document;
    }

    private static CanonicalBase baseOf(LyricsDocument document) {
        return CanonicalBase.fromDocument("spotify:track:a", document);
    }

    private static SoundArtifact soundArtifact(CanonicalBase base, SoundEntry... entries) {
        return new SoundArtifact(base.digest, "sound-config",
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "local", "contract", 0L),
                Arrays.asList(entries), false);
    }

    /** A reading with timed units — evidence only the deterministic pipeline can produce. */
    private static SoundEntry deterministic(String rowId, String display) {
        List<TimedReadingUnit> timed = Collections.singletonList(
                new TimedReadingUnit("span-0", new TextRange(0, 1), display, "group-0"));
        RenderPlan plan = new RenderPlan(rowId, Collections.<CanonicalSpanMapping>emptyList(),
                Collections.<ReadingUnit>emptyList(), timed, display, "");
        return SoundEntry.plan(rowId, "romaji", plan);
    }

    private static List<String> sentIds(List<AiLine> rows) {
        List<String> ids = new ArrayList<>();
        for (AiLine row : rows) if (row.isSent()) ids.add(row.id);
        return ids;
    }

    // --- Meaning ------------------------------------------------------------

    @Test
    public void everyNonStructuralRowIsSentIncludingShortSameScriptOnes() {
        LyricsDocument document = document("Te quiero", "yeah", "[Chorus]", "OK");
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forMeaning(base, document);

        assertEquals("all four rows stay in the document", 4, rows.size());
        assertEquals("only the section heading is withheld", 3, sentIds(rows).size());
        assertEquals(AiLineClass.STRUCTURAL, rows.get(2).lineClass);
        assertTrue("a short same-script line is exactly the code-switching case", rows.get(3).isSent());
    }

    @Test
    public void rowIdsComeFromTheCanonicalBase() {
        LyricsDocument document = document("uno", "dos");
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forMeaning(base, document);

        assertEquals(base.rows.get(0).rowId, rows.get(0).id);
        assertEquals(base.rows.get(1).rowId, rows.get(1).id);
    }

    @Test
    public void anOppositeAlignedRowCarriesTheAlternateVoiceHint() {
        LyricsDocument document = document("first", "second");
        document.lines.get(1).oppositeAligned = true;
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forMeaning(base, document);

        assertEquals(AiVoiceHint.PRIMARY, rows.get(0).voice);
        assertEquals(AiVoiceHint.ALTERNATE, rows.get(1).voice);
    }

    @Test
    public void aDocumentThatNoLongerMatchesTheBaseGivesNoHintRatherThanAWrongOne() {
        LyricsDocument document = document("first", "second");
        CanonicalBase base = baseOf(document);
        // The document has been re-parsed into a different shape since the base was taken.
        LyricsDocument reparsed = document("totally", "different", "shape");

        List<AiLine> rows = AiRows.forMeaning(base, reparsed);

        assertNull(rows.get(0).voice);
        assertNull(rows.get(1).voice);
    }

    @Test
    public void meaningRowsNeverCarryAReadingAsBaseline() {
        LyricsDocument document = document("歌");
        CanonicalBase base = baseOf(document);

        AiLine row = AiRows.forMeaning(base, document).get(0);

        assertNull(row.baselineText);
        assertNull(row.baselineProvenance);
    }

    @Test
    public void meaningRefinementCarriesTheGoogleDraftOnlyWhenSelected() {
        LyricsDocument document = document("Hola");
        CanonicalBase base = baseOf(document);
        MeaningArtifact google = new MeaningArtifact(base.digest, "meaning-config",
                new LayerProvenance(LayerAuthority.MACHINE, "google_unofficial", "contract", 0L),
                Collections.singletonList(new MeaningEntry(
                        base.rows.get(0).rowId, "Hello", "en")), false);

        AiLine refined = AiRows.forMeaning(base, document, google, true).get(0);
        AiLine scratch = AiRows.forMeaning(base, document, google, false).get(0);

        assertEquals("Hello", refined.baselineText);
        assertEquals("google", refined.baselineProvenance);
        assertNull(scratch.baselineText);
    }

    // --- Sound --------------------------------------------------------------

    @Test
    public void aLineAlreadyLegibleInTheTargetOrthographyIsNotSent() {
        LyricsDocument document = document("already latin");
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forSound(base, document, null, LATIN, true);

        assertEquals(1, rows.size());
        assertTrue("nothing to respell means nothing to buy", sentIds(rows).isEmpty());
        assertFalse(AiRows.hasWork(rows));
    }

    @Test
    public void aRowTheDeterministicEngineCoveredIsNotSent() {
        LyricsDocument document = document("歌", "うた");
        CanonicalBase base = baseOf(document);
        SoundArtifact existing = soundArtifact(base,
                deterministic(base.rows.get(0).rowId, "uta"));

        List<AiLine> rows = AiRows.forSound(base, document, existing, LATIN, true);

        assertEquals("the covered row keeps what the engine produced",
                Collections.singletonList(base.rows.get(1).rowId), sentIds(rows));
    }

    @Test
    public void aGoogleFilledRowIsStillAGapAndItsTextBecomesTheBaseline() {
        LyricsDocument document = document("歌");
        CanonicalBase base = baseOf(document);
        String rowId = base.rows.get(0).rowId;
        SoundArtifact existing = soundArtifact(base, SoundEntry.line(rowId, "uta", "romaji"));

        AiLine row = AiRows.forSound(base, document, existing, LATIN, true).get(0);

        assertTrue("Google is the lowest tier; replacing it is what AI is for", row.isSent());
        assertEquals("uta", row.baselineText);
        assertEquals("google", row.baselineProvenance);
    }

    @Test
    public void aiOnlyModeSendsTheSourceWithoutTheExistingReading() {
        LyricsDocument document = document("歌");
        CanonicalBase base = baseOf(document);
        String rowId = base.rows.get(0).rowId;
        SoundArtifact existing = soundArtifact(base, SoundEntry.line(rowId, "uta", "romaji"));

        AiLine row = AiRows.forSound(base, document, existing, LATIN, false).get(0);

        assertTrue(row.isSent());
        assertNull("AI-only mode is what makes a fair comparison possible", row.baselineText);
    }

    @Test
    public void coveredRowsStayInTheDocumentSoTheDigestDescribesTheWholeSong() {
        LyricsDocument document = document("already latin", "歌");
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forSound(base, document, null, LATIN, true);

        assertEquals("both rows are enumerated", 2, rows.size());
        assertEquals(1, sentIds(rows).size());
        assertTrue(AiRows.hasWork(rows));
    }

    @Test
    public void aStructuralRowIsNeverASoundGap() {
        LyricsDocument document = document("[Chorus]", "♪");
        CanonicalBase base = baseOf(document);

        List<AiLine> rows = AiRows.forSound(base, document, null, LATIN, true);

        assertTrue(sentIds(rows).isEmpty());
        assertEquals(AiLineClass.STRUCTURAL, rows.get(0).lineClass);
    }

    // --- coverage rules -----------------------------------------------------

    @Test
    public void latinInsideANonLatinLineIsNotItselfAGap() {
        assertFalse(AiSoundCoverage.needsRespelling("OK", LATIN));
        assertTrue(AiSoundCoverage.needsRespelling("歌 OK", LATIN));
    }

    @Test
    public void aKanaTargetWantsKanjiRespelledButNotKana() {
        assertFalse(AiSoundCoverage.needsRespelling("うた", AiContract.ORTHOGRAPHY_KANA));
        assertTrue(AiSoundCoverage.needsRespelling("歌", AiContract.ORTHOGRAPHY_KANA));
    }

    @Test
    public void aBaselineStillInTheWrongScriptIsNotWorthSending() {
        SoundEntry wrongScript = SoundEntry.line("r0", "歌", "romaji");
        assertNull(AiSoundCoverage.baselineFor(wrongScript, LATIN));
    }

    @Test
    public void aDeterministicReadingIsNeverOfferedAsABaseline() {
        assertNull("a row with one is not a gap and is not in the request at all",
                AiSoundCoverage.baselineFor(deterministic("r0", "uta"), LATIN));
    }
}
