package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.*;
import com.eza.spicyex.lyrics.session.*;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Composes a proven partial purchase through the run owner and real Meaning selection policy. */
public class AiMeaningPartialSelectionTest {
    @After public void restoreStore() { AiRecordStores.installFactoryForTest(null); }

    @Test public void fallbackOutsideRequiredRowsDoesNotHideUsableMeaningInAnyFlow() {
        check(false);
    }

    @Test public void missingRequiredRowStillCannotBecomeCompleteMeaning() {
        check(true);
    }

    private static void check(boolean requiredFallback) {
        LyricsDocument document = new LyricsDocument(); document.language = "ru";
        for (String text : Arrays.asList("один", "два")) {
            LyricsLine line = new LyricsLine(); line.text = text; document.lines.add(line);
        }
        if (!requiredFallback) {
            document.lines.get(1).providerTranslatedText = "two";
            document.lines.get(1).providerTranslationLanguage = "en";
        }
        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:fixture", document);
        MeaningArtifact google = new MeaningArtifact(base.digest, "google",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "google", 1),
                Arrays.asList(new MeaningEntry(base.rows.get(0).rowId, "Google one", "en"),
                        new MeaningEntry(base.rows.get(1).rowId, "Google two", "en")), false);
        Set<String> required = LyricsMeaningLane.requiredRowIds(base, document, "ru", "en");
        assertEquals(requiredFallback ? 2 : 1, required.size());
        for (String flow : Arrays.asList(AiSettings.TRANSLATION_PIPELINE_AI_ONLY,
                AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, AiSettings.TRANSLATION_PIPELINE_GOOGLE_DRAFT)) {
            boolean draft = flow.equals(AiSettings.TRANSLATION_PIPELINE_GOOGLE_DRAFT);
            SettingsStore preferences = new SettingsStore(new FakeAndroidContext());
            preferences.put(Settings.AI_ENABLED, true); preferences.put(Settings.AI_MODEL_GEMINI, "fixture-model");
            AiSettings settings = new AiSettings(preferences, null);
            AiRecordStores.installFactoryForTest(ignored -> new AiRecordStore() {
                public AiPaidRecord read(AiRunConfig config) {
                    List<AiLine> rows = AiRows.forMeaning(base, document, draft ? google : null, draft);
                    AiChunkPlanner.Input input = new AiChunkPlanner.Input(); input.rows = rows;
                    input.layer = LayerKind.MEANING; input.target = "en";
                    input.model = config.provider.model;
                    input.useMeaningBaseline = draft; input.baselineRefinement = draft;
                    AiPlannedChunk planned = AiChunkPlanner.plan(input).chunks.get(0);
                    AiChunkRecord chunk = AiChunkRecord.forChunk(planned);
                    chunk.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
                    chunk.fallbackRowIds.add(base.rows.get(1).rowId);
                    AiPaidRecord record = AiPaidRecord.begin(config, 1);
                    record.putItem(base.rows.get(0).rowId, "one"); record.putChunk(planned.id, chunk);
                    return record;
                }
                public Reservation reserve(AiRunConfig config, long max) { throw new AssertionError("no request"); }
                public boolean commit(AiRunConfig config, AiPaidRecord record) { throw new AssertionError("no write"); }
                public void release(AiRunConfig config) { throw new AssertionError("no dispatch"); }
                public void forget(AiRunConfig config) { throw new AssertionError("no deletion"); }
            });
            AiMeaningRun.Result result = AiMeaningRun.run(null, settings, base, document,
                    draft ? google : null, draft, "en", true, new AiSignal(), null);
            assertTrue(flow, result.hasArtifact());
            assertFalse(flow, result.outcome.record.isComplete());
            MeaningArtifact selected = draft
                    ? MeaningDisplaySelector.selectWithFallback(base, required, result.artifact, google)
                    : MeaningDisplaySelector.select(base, required, result.artifact, null);
            if (!requiredFallback) assertSame(flow, result.artifact, selected);
            else if (draft) assertSame(flow, google, selected);
            else assertNull(flow, selected);
        }
    }
}
