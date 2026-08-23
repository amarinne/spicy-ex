package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiModelProbeTest {

    private static AiProviderConfig config() {
        return new AiProviderConfig(LayerKind.MEANING, "https://api.example.com", "openai-v1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
    }

    /**
     * The probe budget has to hold a reasoning model's thinking, not just the tokens the reply
     * occupies. A cap sized to the reply alone reported every reasoning model as unable to produce
     * structured output — the DeepSeek failure in issue #5.
     */
    @Test
    public void probeBudgetLeavesRoomForModelsThatThinkBeforeAnswering() {
        FakeAiProvider provider = new FakeAiProvider();

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertTrue(result.ok);
        assertEquals(1, provider.calls.size());
        assertEquals(AiContract.PROBE_OUTPUT_TOKENS, provider.calls.get(0).config.maxOutputTokens);
        // Measured: the highest-spending model that passed the survey used 989 completion tokens
        // on this fixture, and one exceeded 1,024. The budget has to clear the observed range.
        assertTrue("the probe budget must clear the measured reasoning spend",
                AiContract.PROBE_OUTPUT_TOKENS > 1024);
    }

    /** A one-word Latin ping cannot fail the rules that actually break on a real song. */
    @Test
    public void probeExercisesTheRulesARealDocumentWouldExercise() {
        FakeAiProvider provider = new FakeAiProvider();

        AiModelProbe.probe(provider, config(), null);

        List<AiRequestItem> sent = provider.calls.get(0).request.items;
        assertEquals(4, sent.size());

        boolean nonLatinSegmentedRow = false;
        boolean adlib = false;
        boolean alreadyEnglish = false;
        for (AiRequestItem item : sent) {
            if (item.lineClass == AiLineClass.ADLIB) adlib = true;
            if (item.id.startsWith("P0~") && !AiText.isLatin(item.source.codePointAt(0))) {
                nonLatinSegmentedRow = true;
            }
            if (item.lineClass == AiLineClass.ORDINARY
                    && AiText.isLatin(item.source.codePointAt(0))) {
                alreadyEnglish = true;
            }
        }
        assertTrue(nonLatinSegmentedRow);
        assertTrue(adlib);
        assertTrue(alreadyEnglish);
    }

    /** Two probe runs must be comparable, so the fixture cannot drift between them. */
    @Test
    public void everyProbeSendsTheSameFixture() {
        FakeAiProvider first = new FakeAiProvider();
        FakeAiProvider second = new FakeAiProvider();

        AiModelProbe.probe(first, config(), null);
        AiModelProbe.probe(second, config(), null);

        assertEquals(first.calls.get(0).requestJson, second.calls.get(0).requestJson);
    }

    /** The token says a model failed; only the bytes say why, and a report needs why. */
    @Test
    public void aFailingProbeKeepsTheWholeExchange() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"P0\",\"t\":\"the night bre",
                        AiUsage.of(120, AiContract.PROBE_OUTPUT_TOKENS), AiFinishReason.LENGTH));

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertFalse(result.ok);
        assertEquals("finish:length", result.failure);
        assertTrue(result.trace.hasRequest());
        assertTrue(result.trace.request.contains("P0"));
        assertTrue(result.trace.hasResponse());
        assertEquals(AiFinishReason.LENGTH, result.trace.finish);
        assertEquals(Integer.valueOf(AiContract.PROBE_OUTPUT_TOKENS), result.trace.usage.output);
    }

    /** Nothing in the fixture comes from the song on screen, which is what makes the trace safe. */
    @Test
    public void theTraceCarriesOnlyTheFixture() {
        FakeAiProvider provider = new FakeAiProvider();

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        for (AiRequestItem item : AiModelProbe.fixture()) {
            assertTrue(result.trace.request.contains(item.id));
        }
    }

    @Test
    public void aWellFormedButOffContractAnswerIsStillRejected() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"P9\",\"t\":\"hello\"}]}"));

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertFalse(result.ok);
        assertTrue(result.failure.startsWith("protocol:"));
    }

    /**
     * Since segments travel pre-split, a model that answers the old joined-row ids is not answering
     * the request: the id set it owes is the segmented one, and an unexpected id fails the chunk.
     */
    @Test
    public void aModelThatAnswersRowIdsInsteadOfSegmentIdsIsRejected() {
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.ok(
                        "{\"items\":[{\"id\":\"P0\",\"t\":\"yoru ga akeru / shizuka ni\"},"
                                + "{\"id\":\"P1\",\"t\":\"and we kept walking\"},"
                                + "{\"id\":\"P2\",\"t\":\"oh oh oh\"}]}",
                        AiUsage.of(120, 40), AiFinishReason.STOP, 128L);
            }
        });

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertFalse(result.ok);
        assertTrue(result.failure,
                result.failure.startsWith("protocol:id_set_mismatch:unexpected:P0"));
    }

    /** A model that answers every segment id exactly once passes, whatever it says per segment. */
    @Test
    public void aModelThatAnswersEachSegmentIdIsAccepted() {
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.ok(
                        "{\"items\":[{\"id\":\"P0~0\",\"t\":\"the night breaks\"},"
                                + "{\"id\":\"P0~1\",\"t\":\"quietly\"},"
                                + "{\"id\":\"P1\",\"t\":\"and we kept walking\"},"
                                + "{\"id\":\"P2\",\"t\":\"oh oh oh\"}]}",
                        AiUsage.of(120, 40), AiFinishReason.STOP, 128L);
            }
        });

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertTrue(result.failure, result.ok);
    }

    @Test
    public void aRejectedKeyIsReportedAsTheProviderFailureItIs() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.failure(AiProviderFailure.auth()));

        AiModelProbe.Result result = AiModelProbe.probe(provider, config(), null);

        assertFalse(result.ok);
        assertEquals("provider:auth", result.failure);
    }
}
