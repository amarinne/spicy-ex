package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Display-time repeat reconciliation and its guardrails.
 *
 * <p>The weak model breaks duplicate lines (7–12% of repeat groups in lab runs versus
 * 0.3–0.4% for strong models), and any model can drift between chunks. The stored record keeps
 * exactly what the provider returned; only the display borrows the first accepted wording for
 * every otherwise-identical repeat, and only under the non-creative presets, whose creative
 * siblings deliberately vary repeats.
 */
public class AiRepeatReconciliationTest {

    /** A repeated lyric line; the base repeats it at three positions across two chunks. */
    private static final String REPEAT_SOURCE = "\"Same old song\" ( MP3 ) - la";
    private static final String REPEAT_OUTPUT = "moonlight over the river";

    /** The two creative preset names this reconciliation is required to leave alone. */
    private static final String CREATIVE = "Creative";

    private static AiRunConfig config(String instructions) {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        return new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake", AiLyricContext.EMPTY,
                provider, instructions, null, null);
    }

    private static LyricsDocument document(String... texts) {
        LyricsDocument document = new LyricsDocument();
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            document.lines.add(line);
        }
        return document;
    }

    /** A base whose rows 0, 2, and 4 are one repeated line; every other row is its own text. */
    private static CanonicalBase repeatBase() {
        return CanonicalBase.fromDocument("spotify:track:repeat", document(REPEAT_SOURCE,
                "Ordinarily different words", REPEAT_SOURCE, "The middle section is elsewhere",
                REPEAT_SOURCE));
    }

    /** A base where every row says something different. */
    private static CanonicalBase variedBase() {
        return CanonicalBase.fromDocument("spotify:track:varied", document("first line",
                "second line", "third line", "fourth line", "fifth line"));
    }

    /**
     * A complete record built over two chunks: the first covering the base's three leading rows
     * (which include the second repeat), the second the trailing rows (which include the third).
     */
    private static AiPaidRecord completeRecord(AiRunConfig config, CanonicalBase base,
                                               String... outputs) {
        AiPaidRecord record = AiPaidRecord.begin(config, 1L);
        record.status = AiPaidRecord.Status.COMPLETE;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < base.rows.size(); i++) {
            ids.add(base.rows.get(i).rowId);
            record.putItem(base.rows.get(i).rowId, outputs[i]);
        }
        AiChunkRecord chunkA = new AiChunkRecord(ids.subList(0, 3), "");
        chunkA.status = AiChunkRecord.Status.COMPLETE;
        AiChunkRecord chunkB = new AiChunkRecord(ids.subList(3, ids.size()), "");
        chunkB.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", chunkA);
        record.putChunk("C1", chunkB);
        return record;
    }

    /**
     * Enumerated rows for the base, giving every repeated occurrence the same voice so nothing
     * except source text separates the group.
     */
    private static List<AiLine> rows(CanonicalBase base, String voiceSource, AiVoiceHint voice) {
        List<AiLine> rows = new ArrayList<>();
        for (com.eza.spicyex.lyrics.session.CanonicalRow row : base.rows) {
            AiVoiceHint hint = voiceSource.equals(row.text) ? voice : AiVoiceHint.BACKGROUND;
            rows.add(new AiLine(row.rowId, AiLineClass.ORDINARY, AiSendDisposition.SENT,
                    row.text, hint, false, null, null));
        }
        return rows;
    }

    private static String displayed(MeaningEntry entry) {
        return entry == null ? null : entry.text;
    }

    @Test
    public void threeIdenticalRepeatsAcrossTwoChunksAllDisplayTheFirstAcceptedOutput() {
        CanonicalBase base = repeatBase();
        List<AiLine> rows = rows(base, REPEAT_SOURCE, AiVoiceHint.PRIMARY);
        AiRunConfig config = config("");
        AiPaidRecord record = completeRecord(config, base,
                REPEAT_OUTPUT,                     // first accepted occurrence
                "no connection to the others",
                REPEAT_OUTPUT + " today",          // weak-model variation inside chunk one
                "another independent line",
                REPEAT_OUTPUT + " nearby");        // cross-chunk drift
        Map<String, String> storedBefore = new LinkedHashMap<>(record.items());

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        for (int index : new int[]{0, 2, 4}) {
            assertEquals("repeat row " + index, REPEAT_OUTPUT,
                    displayed(artifact.meaning(base.rows.get(index).rowId)));
        }
        // Rows outside the repeat group keep their own stored output.
        assertEquals("no connection to the others",
                displayed(artifact.meaning(base.rows.get(1).rowId)));
        assertEquals("another independent line",
                displayed(artifact.meaning(base.rows.get(3).rowId)));
        // The stored record stays raw: display is a projection, identity is untouched.
        assertEquals(storedBefore, record.items());
        assertEquals(REPEAT_OUTPUT + " today", record.item(base.rows.get(2).rowId));
        assertEquals(REPEAT_OUTPUT + " nearby", record.item(base.rows.get(4).rowId));
    }

    @Test
    public void nonIdenticalSourcesAreProjectedExactlyAsStored() {
        CanonicalBase base = variedBase();
        List<AiLine> rows = rows(base, "", AiVoiceHint.PRIMARY);
        AiRunConfig config = config("");
        AiPaidRecord record = completeRecord(config, base,
                "alpha", "beta", "gamma", "delta", "epsilon");

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        String[] expected = {"alpha", "beta", "gamma", "delta", "epsilon"};
        for (int i = 0; i < base.rows.size(); i++) {
            assertEquals(expected[i], displayed(artifact.meaning(base.rows.get(i).rowId)));
        }
    }

    @Test
    public void identicalWordsWithDifferentVoiceDoNotBorrowEachOthersWording() {
        CanonicalBase base = repeatBase();
        // Row 0 is a background voice, rows 2 and 4 are primary: the same words, different parts
        // of the song, so only 2 and 4 form one repeat group.
        AiVoiceHint[] voices = {AiVoiceHint.BACKGROUND, AiVoiceHint.BACKGROUND,
                AiVoiceHint.PRIMARY, AiVoiceHint.BACKGROUND, AiVoiceHint.PRIMARY};
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < base.rows.size(); i++) {
            com.eza.spicyex.lyrics.session.CanonicalRow row = base.rows.get(i);
            rows.add(new AiLine(row.rowId, AiLineClass.ORDINARY, AiSendDisposition.SENT,
                    row.text, voices[i], false, null, null));
        }
        AiRunConfig config = config("");
        AiPaidRecord record = completeRecord(config, base,
                "first wording", "ignored", "second wording", "ignored", "third wording");

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        // Row 0 is the lone background-voice occurrence: its wording stands alone.
        assertEquals("first wording", displayed(artifact.meaning(base.rows.get(0).rowId)));
        // Rows 2 and 4 share one primary-voice repeat group and both show the first of the two.
        assertEquals("second wording", displayed(artifact.meaning(base.rows.get(2).rowId)));
        assertEquals("second wording", displayed(artifact.meaning(base.rows.get(4).rowId)));
    }

    @Test
    public void creativeInstructionsKeepTheStoredOutputs() {
        String creative = com.eza.spicyex.lyrics.ai.AiPresets.instructions(
                com.eza.spicyex.lyrics.session.LayerKind.MEANING, CREATIVE);
        CanonicalBase base = repeatBase();
        List<AiLine> rows = rows(base, REPEAT_SOURCE, AiVoiceHint.PRIMARY);
        AiRunConfig config = config(creative);
        AiPaidRecord record = completeRecord(config, base,
                "first wording", "ignored", "second wording", "ignored", "third wording");

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        // Every variation survives exactly as returned: creative repeats vary on purpose.
        assertEquals("first wording", displayed(artifact.meaning(base.rows.get(0).rowId)));
        assertEquals("second wording", displayed(artifact.meaning(base.rows.get(2).rowId)));
        assertEquals("third wording", displayed(artifact.meaning(base.rows.get(4).rowId)));
    }

    @Test
    public void aByteIdenticalSourceEchoStillDropsAndIsDisplayedNowhere() {
        CanonicalBase base = repeatBase();
        List<AiLine> rows = rows(base, REPEAT_SOURCE, AiVoiceHint.PRIMARY);
        // Row 0 comes back byte-identical to its source: accepted, but never displayed. Its
        // repeats display that same projected echo, so the drop rule hides the whole group —
        // the honest presentation when the model returned the original line and paid nothing.
        AiRunConfig config = config("");
        AiPaidRecord record = completeRecord(config, base,
                REPEAT_SOURCE, "ignored", "second wording", "ignored", "third wording");

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        assertNull(artifact.meaning(base.rows.get(0).rowId));
        assertNull(artifact.meaning(base.rows.get(2).rowId));
        assertNull(artifact.meaning(base.rows.get(4).rowId));
    }

    @Test
    public void aTranslatedFirstOccurrenceKeepsItsRepeatsOutOfTheEchoDrop() {
        CanonicalBase base = repeatBase();
        List<AiLine> rows = rows(base, REPEAT_SOURCE, AiVoiceHint.PRIMARY);
        // The mirror case: the first occurrence was translated, so its repeats display that
        // translation even where one of them individually echoed the source.
        AiRunConfig config = config("");
        AiPaidRecord record = completeRecord(config, base,
                REPEAT_OUTPUT, "ignored", REPEAT_SOURCE, "ignored", "third wording");

        MeaningArtifact artifact = AiMeaningRun.artifactOf(base, config, record, rows);

        assertEquals(REPEAT_OUTPUT, displayed(artifact.meaning(base.rows.get(0).rowId)));
        assertEquals(REPEAT_OUTPUT, displayed(artifact.meaning(base.rows.get(2).rowId)));
        assertEquals(REPEAT_OUTPUT, displayed(artifact.meaning(base.rows.get(4).rowId)));
    }
}
