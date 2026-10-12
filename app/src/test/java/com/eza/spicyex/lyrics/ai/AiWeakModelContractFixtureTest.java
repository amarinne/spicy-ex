package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Synthetic responses exercise the real parser, validator, planner, and offline runtime. */
public class AiWeakModelContractFixtureTest {
    private static AiRequestItem segment(int index, String source) {
        return new AiRequestItem(AiContract.segmentId("r0#aaaa", index),
                AiLineClass.ORDINARY, AiVoiceHint.PRIMARY, source, null);
    }

    private static AiResponseValidator.Partition partition(LayerKind layer,
                                                           List<AiRequestItem> requested,
                                                           String... texts) {
        AiProviderRequest request = new AiProviderRequest(AiLyricContext.EMPTY, "Kana", "",
                requested);
        return AiResponseValidator.partition(AiResponseReader.readItems(
                FakeAiProvider.itemsJson(request, Arrays.asList(texts))), requested, layer, "Kana");
    }

    @Test public void readableLatinSegmentAfterKanaDoesNotNeedRespelling() {
        AiResponseValidator.Partition result = partition(LayerKind.SOUND,
                Arrays.asList(segment(0, "あ"), segment(1, "OK")), "ア", "OK");
        assertTrue(result.clean());
        assertEquals("ア / OK", result.accepted.get(0).text);
    }

    @Test public void laterNonLatinSegmentStillRequiresTheRequestedScript() {
        AiResponseValidator.Partition result = partition(LayerKind.SOUND,
                Arrays.asList(segment(0, "OK"), segment(1, "あ")), "OK", "a");
        assertEquals(0, result.accepted.size());
        assertEquals("target_orthography_mismatch", result.failed.get(0).token);
        assertEquals("r0#aaaa", result.failed.get(0).rowId);
    }

    @Test public void sourceChecksFollowSegmentIndicesWhenRequestOrderChanges() {
        AiResponseValidator.Partition result = partition(LayerKind.SOUND,
                Arrays.asList(segment(1, "OK"), segment(0, "あ")), "OK", "ア");
        assertTrue(result.clean());
        assertEquals("ア / OK", result.accepted.get(0).text);
    }

    @Test public void genuineMetadataWordInLaterMeaningSegmentRemainsValid() {
        AiResponseValidator.Partition result = partition(LayerKind.MEANING,
                Arrays.asList(segment(0, "hola"), segment(1, "primary")), "hello", "primary");
        assertTrue(result.clean());
        assertEquals("hello / primary", result.accepted.get(0).text);
    }

    @Test public void joiningSegmentEdgesCannotCreateAnExtraDelimiter() {
        AiResponseValidator.Partition result = partition(LayerKind.MEANING,
                Arrays.asList(segment(0, "uno"), segment(1, "dos")), "x /", "/ y");
        assertEquals(0, result.accepted.size());
        assertEquals("delimiter_mismatch", result.failed.get(0).token);
        assertEquals("r0#aaaa", result.failed.get(0).rowId);
    }

    @Test public void metadataEchoInLaterSegmentNamesTheCanonicalRowForRepair() {
        AiResponseValidator.Partition result = partition(LayerKind.MEANING,
                Arrays.asList(segment(0, "hola"), segment(1, "mundo")), "hello", "primary");
        assertEquals("voice_hint_echo", result.failed.get(0).token);
        assertEquals("r0#aaaa", result.failed.get(0).rowId);
    }

    @Test public void kanaLongVowelMarkIsValidInFullAndHalfWidthReadings() {
        for (String reading : Arrays.asList("カー", "ｶｰ")) {
            AiResponseValidator.Partition result = partition(LayerKind.SOUND,
                    Collections.singletonList(new AiRequestItem("r0#aaaa",
                            AiLineClass.ORDINARY, null, "か", null)), reading);
            assertTrue(result.clean());
        }
    }

    @Test public void kanaLongVowelMarkCannotStandInForAReadingOrPermitWrongScript() {
        for (String reading : Arrays.asList("ー", "ｰ", "ー한", "ｰあ한")) {
            AiResponseValidator.Partition result = partition(LayerKind.SOUND,
                    Collections.singletonList(new AiRequestItem("r0#aaaa",
                            AiLineClass.ORDINARY, null, "か", null)), reading);
            assertEquals("target_orthography_mismatch", result.failed.get(0).token);
        }
    }

    @Test public void laterSegmentFailureReasksItsRowAndAccountsBothAttempts() {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = Collections.singletonList(new AiLine("r0#aaaa", AiLineClass.ORDINARY,
                AiSendDisposition.SENT, "hola / mundo", AiVoiceHint.PRIMARY, false, null, null));
        input.target = "en";
        input.model = FakeAiProvider.DEFAULT_MODEL;
        FakeAiProvider provider = new FakeAiProvider(
                (request, config, signal) -> AiProviderResult.ok(
                        FakeAiProvider.itemsJson(request, Arrays.asList("hello", "primary")),
                        AiUsage.of(4, 2), AiFinishReason.STOP, 1L),
                (request, config, signal) -> AiProviderResult.ok(
                        FakeAiProvider.itemsJson(request, Arrays.asList("hello", "world")),
                        AiUsage.of(4, 2), AiFinishReason.STOP, 1L));
        AiChunkRuntime.Args args = new AiChunkRuntime.Args();
        args.provider = provider;
        args.chunk = AiChunkPlanner.plan(input).chunks.get(0);
        args.config = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        args.signal = new AiSignal();

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertTrue(result.ok);
        assertEquals("hello / world", result.items.get(0).text);
        assertEquals(2, provider.calls.size());
        assertEquals(Arrays.asList("r0#aaaa~0", "r0#aaaa~1"),
                Arrays.asList(provider.calls.get(1).request.items.get(0).id,
                        provider.calls.get(1).request.items.get(1).id));
        assertEquals(12L, result.accountedTokens);
        assertEquals(1, result.record.repairs);
    }
}
