package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The runtime is where money is actually spent, so these pin the spending rules rather than the
 * happy path: how many times one chunk may be paid for, which failures may be retried at all, and
 * that a call whose outcome is unknown is reported as unknown instead of quietly repeated.
 */
public class AiChunkRuntimeTest {

    private static final AiModelDescriptor MODEL = new AiModelDescriptor(
            "fake-model", "1", 32_768, 4_096, Collections.singletonList("generateContent"));

    private static final AiProviderConfig CONFIG = new AiProviderConfig(LayerKind.MEANING, null,
            "1", MODEL, "en", AiContract.PROMPT_VERSION, false);

    private static AiPlannedChunk chunk(String steering) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = Collections.singletonList(new AiLine("S0", AiLineClass.ORDINARY,
                AiSendDisposition.SENT, "hola", null, false, null, null));
        input.target = "en";
        input.model = MODEL;
        input.instructions = steering;
        return AiChunkPlanner.plan(input).chunks.get(0);
    }

    private static AiChunkRuntime.Args args(AiProvider provider, AiPlannedChunk chunk) {
        AiChunkRuntime.Args args = new AiChunkRuntime.Args();
        args.provider = provider;
        args.chunk = chunk;
        args.config = CONFIG;
        args.signal = new AiSignal();
        return args;
    }

    private static String answer(String text) {
        return "{\"items\":[{\"id\":\"S0\",\"t\":\"" + text + "\"}]}";
    }

    // --- attempts and repair -------------------------------------------------

    @Test
    public void arepairResendsByteIdenticalBytesAndTheSecondAttemptIsTheLast() {
        AiPlannedChunk steered = chunk(" Preserve names. ");
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[]}"),
                FakeAiProvider.body(answer("hello")));

        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, steered));

        assertTrue(result.ok);
        assertEquals(2, result.record.attempts);
        assertEquals(1, result.record.repairs);
        assertEquals(2, provider.calls.size());
        assertEquals(provider.calls.get(0).requestJson, provider.calls.get(1).requestJson);
        assertEquals(steered.requestJson, provider.calls.get(0).requestJson);
        assertEquals("Preserve names.", provider.calls.get(0).request.instructions);
        assertFalse("the first attempt is not a repair", provider.calls.get(0).config.repair);
        assertTrue("the second attempt carries the repair instruction",
                provider.calls.get(1).config.repair);
        assertTrue(provider.calls.get(1).config.systemPrompt()
                .startsWith(AiContract.REPAIR_PROMPT));
    }

    @Test
    public void unchangedOutputIsAcceptedWithoutARepair() {
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body(answer("hola")));
        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
        assertTrue(result.ok);
        assertEquals(1, result.record.attempts);
        assertEquals(0, result.record.repairs);
        assertEquals(AiChunkRecord.Status.COMPLETE, result.record.status);
    }

    @Test
    public void aProviderReportedProtocolFailureGetsOneRepair() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.failure(AiProviderFailure.protocol("invalid_json")),
                FakeAiProvider.body(answer("hello")));
        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
        assertTrue(result.ok);
        assertEquals(1, result.record.repairs);
        assertEquals(provider.calls.get(0).requestJson, provider.calls.get(1).requestJson);
    }

    @Test
    public void aterminalProtocolFailureNamesTheRowThatFailed() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body(answer("hello / world")),
                FakeAiProvider.body(answer("hello / world")));
        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
        assertFalse(result.ok);
        assertEquals(AiFailureReason.PROTOCOL_INVALID, result.failure.reason);
        assertEquals("delimiter_mismatch:S0", result.failure.detail);
        assertEquals(2, provider.calls.size());
    }

    // --- terminal failures ---------------------------------------------------

    @Test
    public void deliveryUnknownAndRefusalAreTerminalWithoutRetry() {
        // Truncation is deliberately absent: with escalation headroom left it is a budget
        // failure that retries once, and without headroom it is pinned by
        // atruncationAtTheAbsoluteBoundIsTerminalWithoutAWastedCall above.
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{FakeAiProvider.failure(AiProviderFailure.deliveryUnknown(
                AiProviderFailure.Cause.NETWORK, 0)), AiFailureReason.DELIVERY_UNKNOWN});
        cases.add(new Object[]{FakeAiProvider.body("{\"items\":[]}", AiUsage.of(1, 1),
                AiFinishReason.SAFETY), AiFailureReason.PROVIDER_REFUSED});
        cases.add(new Object[]{FakeAiProvider.body("{\"items\":[]}", AiUsage.of(1, 1),
                AiFinishReason.OTHER), AiFailureReason.PROVIDER_REFUSED});
        cases.add(new Object[]{FakeAiProvider.failure(AiProviderFailure.auth()),
                AiFailureReason.AUTH_REJECTED});
        cases.add(new Object[]{FakeAiProvider.failure(AiProviderFailure.quota()),
                AiFailureReason.QUOTA_EXHAUSTED});
        cases.add(new Object[]{FakeAiProvider.failure(AiProviderFailure.requestRejected(400)),
                AiFailureReason.REQUEST_REJECTED});
        cases.add(new Object[]{FakeAiProvider.failure(AiProviderFailure.modelUnavailable()),
                AiFailureReason.MODEL_UNAVAILABLE});

        for (Object[] testCase : cases) {
            FakeAiProvider provider = new FakeAiProvider((FakeAiProvider.Step) testCase[0]);
            AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
            assertFalse(result.ok);
            assertEquals(testCase[1], result.failure.reason);
            assertEquals("a terminal failure must not be retried", 1, provider.calls.size());
            assertTrue("a failed attempt is still accounted", result.accountedTokens > 0);
        }
    }

    @Test
    public void acallThatNeverReturnsIsUnknownDeliveryAndIsChargedConservatively() {
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.neverReturns());
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.deadlineMs = 1L;
        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertFalse(result.ok);
        assertEquals(AiFailureReason.DELIVERY_UNKNOWN, result.failure.reason);
        assertEquals("never retried: it may already have been served and billed",
                1, result.record.attempts);
        assertTrue(result.record.usageEstimated);
        assertTrue(result.accountedTokens > 0);
    }

    // --- rate limiting -------------------------------------------------------

    @Test
    public void arateLimitRetriesOnceCapsTheWaitAndCountsAbsentUsageConservatively() {
        final List<Long> waits = new ArrayList<>();
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.failure(AiProviderFailure.rateLimited(90_000L)),
                FakeAiProvider.body(answer("hello"), AiUsage.UNREPORTED, AiFinishReason.STOP));
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.wait = new AiWait() {
            @Override public void await(long millis, AiSignal signal) {
                waits.add(millis);
            }
        };

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertTrue(result.ok);
        assertEquals(Collections.singletonList(30_000L), waits);
        assertEquals(2, result.record.attempts);
        assertTrue(result.record.usageEstimated);
        assertTrue(result.accountedTokens > 1_000L);
    }

    @Test
    public void arateLimitWithoutARetryAfterWaitsTheDefaultSecond() {
        final List<Long> waits = new ArrayList<>();
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.failure(AiProviderFailure.rateLimited(null)),
                FakeAiProvider.body(answer("hello")));
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.wait = new AiWait() {
            @Override public void await(long millis, AiSignal signal) {
                waits.add(millis);
            }
        };
        AiChunkRuntime.executeChunk(args);
        assertEquals(Collections.singletonList(1_000L), waits);
    }

    @Test
    public void cancellingDuringTheRetryWaitStillReportsTheChargedAttempt() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.failure(AiProviderFailure.rateLimited(30_000L)));
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.wait = new AiWait() {
            @Override public void await(long millis, AiSignal signal) {
                throw new AiCancelledException("track_change");
            }
        };

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertFalse(result.ok);
        assertEquals(AiFailureReason.RATE_LIMITED, result.failure.reason);
        assertEquals(1, result.record.attempts);
        assertTrue(result.accountedTokens > 0);
    }

    // --- cancellation and resume ---------------------------------------------

    @Test
    public void anAlreadyCancelledRunNeverDispatches() {
        FakeAiProvider provider = new FakeAiProvider();
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.signal.abort("track_change");
        try {
            AiChunkRuntime.executeChunk(args);
            fail("expected a cancelled run to stop before dispatch");
        } catch (AiCancelledException cancelled) {
            assertEquals("track_change", cancelled.reason);
        }
        assertTrue(provider.calls.isEmpty());
    }

    @Test
    public void cancellingTheRunAbortsTheCallInFlight() {
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.neverReturns());
        final AiChunkRuntime.Args args = args(provider, chunk(null));
        args.deadlineMs = 5_000L;
        Thread canceller = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                args.signal.abort("track_change");
            }
        });
        canceller.start();

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertFalse(result.ok);
        assertEquals(AiFailureReason.DELIVERY_UNKNOWN, result.failure.reason);
        assertEquals(1, provider.calls.size());
    }

    @Test
    public void acompletedChunkIsNeverResent() {
        AiChunkRuntime.Args args = args(new FakeAiProvider(), chunk(null));
        AiChunkRecord done = AiChunkRecord.forChunk(args.chunk);
        done.status = AiChunkRecord.Status.COMPLETE;
        args.previous = done;
        try {
            AiChunkRuntime.executeChunk(args);
            fail("expected a completed chunk to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("must not be resent"));
        }
    }

    @Test
    public void aresumedChunkReplaysTheSameBytesAndKeepsItsEarlierAccounting() {
        AiPlannedChunk chunk = chunk(null);
        AiChunkRecord failed = AiChunkRecord.forChunk(chunk);
        failed.status = AiChunkRecord.Status.FAILED;
        failed.attempts = 1;
        failed.inputTokens = 40;
        failed.outputTokens = 20;
        failed.usageEstimated = true;
        failed.failure = AiChunkFailure.of(AiFailureReason.DELIVERY_UNKNOWN);

        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body(answer("hello")));
        AiChunkRuntime.Args args = args(provider, chunk);
        args.previous = failed;

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertTrue(result.ok);
        assertEquals(chunk.requestJson, provider.calls.get(0).requestJson);
        assertEquals("the earlier attempt is not forgotten", 2, result.record.attempts);
        assertTrue(result.record.inputTokens > 40);
        assertTrue("earlier estimation stays visible", result.record.usageEstimated);
        assertEquals(AiChunkRecord.Status.COMPLETE, result.record.status);
        assertEquals("the caller's record is not mutated in place",
                AiChunkRecord.Status.FAILED, failed.status);
    }

    @Test
    public void aresumedChunkThatHasAlreadySpentItsAttemptsGetsNoMore() {
        AiPlannedChunk chunk = chunk(null);
        AiChunkRecord exhausted = AiChunkRecord.forChunk(chunk);
        exhausted.status = AiChunkRecord.Status.FAILED;
        exhausted.attempts = AiContract.MAX_ATTEMPTS;

        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body(answer("hello")));
        AiChunkRuntime.Args args = args(provider, chunk);
        args.previous = exhausted;
        try {
            AiChunkRuntime.executeChunk(args);
            fail("expected the exhausted attempt budget to stop the run");
        } catch (IllegalStateException expected) {
            assertTrue(provider.calls.isEmpty());
        }
    }

    // --- accounting -----------------------------------------------------------

    @Test
    public void reportedUsageIsRecordedAsReportedAndNotMarkedEstimated() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body(answer("hello"), AiUsage.of(11, 7), AiFinishReason.STOP));
        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
        assertTrue(result.ok);
        assertEquals(11, result.record.inputTokens);
        assertEquals(7, result.record.outputTokens);
        assertFalse(result.record.usageEstimated);
        assertEquals(18L, result.accountedTokens);
    }

    @Test
    public void ahalfReportedUsageIsTreatedAsUnreported() {
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body(answer("hello"),
                new AiUsage(11, null), AiFinishReason.STOP));
        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));
        assertTrue(result.ok);
        assertTrue(result.record.usageEstimated);
        assertEquals("the dispatched output budget stands in for the missing figure",
                AiChunkRuntime.initialOutputBudget(chunk(null), CONFIG.callOutputTokens()),
                result.record.outputTokens);
    }

    // --- row-level acceptance -------------------------------------------------

    private static AiPlannedChunk twoRowChunk() {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = Arrays.asList(
                new AiLine("S0", AiLineClass.ORDINARY, AiSendDisposition.SENT, "hola", null,
                        false, null, null),
                new AiLine("S1", AiLineClass.ORDINARY, AiSendDisposition.SENT, "mundo", null,
                        false, null, null));
        input.target = "en";
        input.model = MODEL;
        return AiChunkPlanner.plan(input).chunks.get(0);
    }

    @Test
    public void aValidRowIsKeptAndOnlyTheFailedRowIsReasked() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\"}]}"),
                FakeAiProvider.body("{\"items\":[{\"id\":\"S1\",\"t\":\"world\"}]}"));

        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, twoRowChunk()));

        assertTrue(result.ok);
        assertEquals(AiChunkRecord.Status.COMPLETE, result.record.status);
        assertTrue(result.fallbackRowIds.isEmpty());
        assertEquals(2, result.record.attempts);
        assertEquals(1, result.record.repairs);
        assertEquals("both rows land in the merged output", 2, result.items.size());
        assertEquals("the re-ask carries exactly the failed row",
                "{\"context\":{\"title\":null,\"artists\":[],\"album\":null},"
                        + "\"target\":\"en\",\"items\":"
                        + "[{\"id\":\"S1\",\"c\":\"ordinary\",\"v\":null,\"s\":\"mundo\"}]}",
                provider.calls.get(1).requestJson);
    }

    @Test
    public void arowThatFailsAgainFallsBackInsteadOfFailingTheChunk() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\"},"
                        + "{\"id\":\"S1\",\"t\":\"a / b\"}]}"),
                FakeAiProvider.body("{\"items\":[{\"id\":\"S1\",\"t\":\"c / d\"}]}"));

        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, twoRowChunk()));

        assertTrue("one bad row must not discard its billed neighbour", result.ok);
        assertEquals(AiChunkRecord.Status.COMPLETED_FALLBACK, result.record.status);
        assertEquals(Collections.singletonList("S1"), result.fallbackRowIds);
        assertEquals(1, result.items.size());
        assertEquals("S0", result.items.get(0).id);
        assertEquals(2, provider.calls.size());
        assertTrue(result.accountedTokens > 0);
    }

    @Test
    public void anIdentityViolationStillRepairsTheWholeChunk() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\"},"
                        + "{\"id\":\"S9\",\"t\":\"intruder\"}]}"),
                FakeAiProvider.body(answer("hola")));

        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));

        assertTrue(result.ok);
        assertEquals("an unexpected id is a whole-response failure, so the full chunk is resent",
                2, provider.calls.size());
        assertEquals(chunk(null).requestJson, provider.calls.get(1).requestJson);
        assertEquals(2, result.record.attempts);
    }

    // --- truncation and escalation -------------------------------------------

    /**
     * The dispatched cap is the ceiling, so a truncation has nowhere larger to go.
     *
     * <p>This replaces an assertion that a truncation retried at double the cap. That ladder only
     * existed because the estimate was dispatched instead of the ceiling, and it made things worse
     * on real documents: a 58-row song started at 2860, truncated, climbed to 5720, truncated
     * again, and failed terminally without ever requesting the 8192 the endpoint allowed — one
     * escalation exhausts the shared attempt budget.
     */
    @Test
    public void aTruncationIsTerminalBecauseTheCapWasAlreadyTheCeiling() {
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[]}", AiUsage.of(40, 900),
                        AiFinishReason.LENGTH));

        AiChunkExecution result = AiChunkRuntime.executeChunk(args(provider, chunk(null)));

        assertFalse(result.ok);
        assertEquals(AiFailureReason.TRUNCATED, result.failure.reason);
        assertEquals("no second attempt at a cap that cannot grow", 1, provider.calls.size());
        assertEquals(CONFIG.callOutputTokens(), provider.calls.get(0).config.maxOutputTokens);
        assertEquals("the truncated attempt is still billed", 900, result.record.outputTokens);
    }

    @Test
    public void atruncationAtTheAbsoluteBoundIsTerminalWithoutAWastedCall() {
        AiModelDescriptor small = new AiModelDescriptor("small", "1", 32_768,
                AiContract.MIN_CALL_OUTPUT_TOKENS, Collections.singletonList("generateContent"));
        AiProviderConfig config = new AiProviderConfig(LayerKind.MEANING, null, "1", small,
                "en", AiContract.PROMPT_VERSION, false);
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[]}", AiUsage.of(10, 512),
                        AiFinishReason.LENGTH));
        AiChunkRuntime.Args args = args(provider, chunk(null));
        args.config = config;

        AiChunkExecution result = AiChunkRuntime.executeChunk(args);

        assertFalse(result.ok);
        assertEquals(AiFailureReason.TRUNCATED, result.failure.reason);
        assertEquals("no headroom means no second bill", 1, provider.calls.size());
        assertTrue(result.accountedTokens > 0);
    }

    /** A cap is a ceiling, not a charge: every call asks for the full bound it is allowed. */
    @Test
    public void everyCallDispatchesTheFullCeilingItIsAllowed() {
        FakeAiProvider provider = new FakeAiProvider();

        AiChunkRuntime.executeChunk(args(provider, chunk(null)));

        assertEquals(CONFIG.callOutputTokens(), provider.calls.get(0).config.maxOutputTokens);
        assertEquals(CONFIG.callOutputTokens(),
                AiChunkRuntime.initialOutputBudget(chunk(null), CONFIG.callOutputTokens()));
    }

    @Test
    public void theOutputCapIsTheLowerOfTheContractAndTheModel() {
        assertEquals(4_096, CONFIG.callOutputTokens());
        AiModelDescriptor generous = new AiModelDescriptor("big", "1", 1_000_000, 65_536,
                Collections.singletonList("generateContent"));
        AiProviderConfig config = new AiProviderConfig(LayerKind.MEANING, null, "1", generous,
                "en", AiContract.PROMPT_VERSION, false);
        assertEquals(AiContract.MAX_CONFIGURED_OUTPUT_TOKENS, config.callOutputTokens());
    }

    /**
     * The deadline has to outlast the work it is timing.
     *
     * <p>A flat minute expired while a reasoning model was still producing a full document, and a
     * deadline that fires after dispatch is DELIVERY_UNKNOWN — possibly billed, never retried
     * automatically, and the owner has to be asked. It scales with the budget now.
     */
    @Test
    public void theCallDeadlineScalesWithTheOutputBudget() {
        long small = AiContract.callDeadlineMs(AiContract.MIN_CALL_OUTPUT_TOKENS);
        long full = AiContract.callDeadlineMs(AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);

        assertTrue("a bigger budget must be given longer", full > small);
        assertTrue("a full budget must outlast the old flat minute", full > 60_000L);
        assertEquals("and it stays bounded", AiContract.MAX_CALL_DEADLINE_MS, full);
        assertTrue("a small budget still gets the base allowance",
                small >= AiContract.CALL_DEADLINE_BASE_MS);
    }

    /**
     * No transport timeout may bind before the deadline the runtime is enforcing.
     *
     * <p>A completion is not streamed, so the read timeout is a flat ceiling on generation time
     * rather than a stall detector. When it sat below the deadline it was the real limit, and the
     * failure it produced was DELIVERY_UNKNOWN rather than a deliberate cancellation.
     */
    @Test
    public void noTransportTimeoutBindsBeforeTheRuntimeDeadline() {
        long longest = AiContract.callDeadlineMs(AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);

        assertTrue("the derived deadline must never exceed the transport backstop",
                longest <= AiContract.MAX_CALL_DEADLINE_MS);
        assertTrue("a slow provider must get minutes, not one",
                AiContract.callDeadlineMs(2_000) > 120_000L);
    }
}
