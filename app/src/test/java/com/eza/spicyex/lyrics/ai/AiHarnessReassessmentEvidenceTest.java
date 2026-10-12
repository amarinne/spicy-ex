package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Offline regressions for the defects identified in the harness reassessment.
 */
public class AiHarnessReassessmentEvidenceTest {
    private static AiLayerRunner.Args args(LayerKind layer, List<AiLine> rows,
                                           FakeAiProvider provider) {
        AiProviderConfig config = new AiProviderConfig(layer, null, "1",
                FakeAiProvider.DEFAULT_MODEL, layer == LayerKind.SOUND ? "Latin" : "en",
                layer == LayerKind.SOUND ? AiContract.SOUND_PROMPT_VERSION
                        : AiContract.PROMPT_VERSION, false);
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = new AiRunConfig(layer, "synthetic-digest", "synthetic-config", "fake",
                AiLyricContext.EMPTY, config, "", null, null);
        args.provider = provider;
        args.store = new FakeAiRecordStore();
        args.rows = rows;
        args.signal = new AiSignal();
        return args;
    }

    @Test public void genuineLayoutTokenTranslationRemainsAnExplicitUnresolvedFalseRejection() {
        AiRequestItem requested = new AiRequestItem("r0#aaaa", AiLineClass.ORDINARY,
                AiVoiceHint.BACKGROUND, "背景", null);
        AiResponseValidator.Partition result = AiResponseValidator.partition(
                AiResponseReader.readItems("{\"items\":[{\"id\":\"r0#aaaa\",\"t\":\"background\"}]}"),
                Collections.singletonList(requested), LayerKind.MEANING, "en");

        assertEquals("voice_hint_echo", result.failed.get(0).token);
        assertTrue(result.accepted.isEmpty());
    }

    @Test public void aTruncatedSingleRowNeverCreatesPartialRowChildren() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("", AiUsage.of(4, 2), AiFinishReason.LENGTH));
        AiRunOutcome result = AiLayerRunner.run(args(LayerKind.MEANING,
                Collections.singletonList(AiLine.of("r0#aaaa", "alpha / beta", null, false)),
                provider));

        assertEquals(AiRunOutcome.Kind.FAILED, result.kind);
        assertEquals("truncated", result.failureToken);
        assertTrue(result.record.items().isEmpty());
        assertEquals(1, provider.calls.size());
        assertEquals(4, result.record.inputTokens);
        assertEquals(2, result.record.outputTokens);
    }

    @Test public void soundFinalValidationPreservesIntentionalRowFallback() throws Exception {
        List<AiLine> rows = Arrays.asList(AiLine.of("r0#aaaa", "あ", null, false),
                AiLine.of("r1#bbbb", "い", null, false));
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"r0#aaaa\",\"t\":\"a\"},"
                        + "{\"id\":\"r1#bbbb\",\"t\":\"い\"}]}"),
                FakeAiProvider.body("{\"items\":[{\"id\":\"r1#bbbb\",\"t\":\"い\"}]}"));
        AiRunOutcome result = AiLayerRunner.run(args(LayerKind.SOUND, rows, provider));

        assertEquals(AiRunOutcome.Kind.COMPLETED, result.kind);
        assertTrue(result.hasOutput());
        assertEquals(AiPaidRecord.Status.PARTIAL, result.record.status);
        assertEquals(AiChunkRecord.Status.COMPLETED_FALLBACK, result.record.chunk("C0").status);
        assertEquals("a", result.record.item("r0#aaaa"));
        assertNull(result.record.item("r1#bbbb"));
        assertEquals(2, provider.calls.size());
        assertEquals(8, result.record.inputTokens);
        assertEquals(4, result.record.outputTokens);

        Method validate = AiSoundRun.class.getDeclaredMethod("validateStoredOutput",
                AiPaidRecord.class, List.class, String.class);
        validate.setAccessible(true);
        AiChunkFailure failure = (AiChunkFailure) validate.invoke(null, result.record, rows, "Latin");
        assertNull(failure);
    }
}
