package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The run loop's job is to spend as little as the answer costs, and to never lose what was bought.
 *
 * <p>Almost every assertion here is really about money: that a finished answer is not bought twice,
 * that a completed chunk survives the run being interrupted, that a resume does not resend what it
 * already holds, and that an attempt which failed is still counted because it still billed.
 */
public class AiLayerRunnerTest {

    private static final AiWait NO_SLEEP = new AiWait() {
        @Override public void await(long millis, AiSignal signal) {
            if (signal != null) signal.throwIfAborted();
        }
    };

    // --- helpers ------------------------------------------------------------

    private static List<AiLine> rows(int count) {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(AiLine.of("r" + i, "line " + i, null, false));
        return rows;
    }

    /** Enough rows to force the planner past its single-call bound and into several chunks. */
    private static List<AiLine> chunkedRows() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 129; i++) rows.add(AiLine.of("r" + i, "line " + i, null, false));
        return rows;
    }

    private static AiRunConfig config() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        return new AiRunConfig(LayerKind.MEANING, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
    }

    private static AiLayerRunner.Args args(List<AiLine> rows, AiProvider provider,
                                           AiRecordStore store) {
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config();
        args.provider = provider;
        args.store = store;
        args.rows = rows;
        args.wait = NO_SLEEP;
        args.nowMs = 1_000L;
        return args;
    }

    // --- not spending -------------------------------------------------------

    @Test
    public void aStoredCompleteAnswerIsReusedWithoutCallingTheProvider() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider();
        assertEquals(AiRunOutcome.Kind.COMPLETED,
                AiLayerRunner.run(args(rows(3), provider, store)).kind);
        int callsAfterFirstRun = provider.calls.size();

        AiRunOutcome second = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(AiRunOutcome.Kind.REUSED, second.kind);
        assertEquals("a stored answer must not be bought again",
                callsAfterFirstRun, provider.calls.size());
        assertTrue(second.hasOutput());
    }

    @Test
    public void onDemandBackgroundWorkMayReuseButCannotDispatch() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider();
        AiLayerRunner.Args blocked = args(rows(3), provider, store);
        blocked.allowProviderRequest = false;

        AiRunOutcome absent = AiLayerRunner.run(blocked);

        assertEquals(AiRunOutcome.Kind.NOTHING_TO_DO, absent.kind);
        assertTrue(provider.calls.isEmpty());
        assertTrue(store.isEmpty());

        assertEquals(AiRunOutcome.Kind.COMPLETED,
                AiLayerRunner.run(args(rows(3), provider, store)).kind);
        int paidCalls = provider.calls.size();
        AiLayerRunner.Args reuseOnly = args(rows(3), provider, store);
        reuseOnly.allowProviderRequest = false;

        AiRunOutcome reused = AiLayerRunner.run(reuseOnly);

        assertEquals(AiRunOutcome.Kind.REUSED, reused.kind);
        assertEquals(paidCalls, provider.calls.size());
    }

    @Test
    public void aDocumentWithNothingToSendCostsNothingAndStoresNothing() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider();
        List<AiLine> structural = Collections.singletonList(
                AiLine.of("r0", "[Chorus]", null, false));

        AiRunOutcome outcome = AiLayerRunner.run(args(structural, provider, store));

        assertEquals(AiRunOutcome.Kind.NOTHING_TO_DO, outcome.kind);
        assertTrue(provider.calls.isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    public void aDocumentPastItsBoundIsRefusedBeforeAnyCall() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider();
        List<AiLine> tooMany = new ArrayList<>();
        for (int i = 0; i < 513; i++) tooMany.add(AiLine.of("r" + i, "x", null, false));

        AiRunOutcome outcome = AiLayerRunner.run(args(tooMany, provider, store));

        assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
        assertEquals("oversized", outcome.failureToken);
        assertTrue(provider.calls.isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    public void terminalProtocolFailureKeepsItsPrivacySafeRule() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("{\"items\":[]}"),
                FakeAiProvider.body("{\"items\":[]}"));

        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
        assertEquals("protocol_invalid", outcome.failureToken);
        assertEquals("id_set_mismatch:missing:r0", outcome.failureDetail);
    }

    // --- keeping what was bought --------------------------------------------

    @Test
    public void everyChunkIsStoredAsItCompletesRatherThanOnlyAtTheEnd() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.run(args(chunkedRows(), new FakeAiProvider(), store));

        AiPaidRecord stored = store.peek(config());
        assertNotNull(stored);
        assertEquals(AiPaidRecord.Status.COMPLETE, stored.status);
        assertEquals(3, stored.chunks().size());
        assertEquals(129, stored.items().size());
    }

    @Test
    public void aRunStoppedPartWayKeepsTheChunksItFinished() {
        final AiSignal signal = new AiSignal();
        FakeAiRecordStore store = new FakeAiRecordStore();
        // Answer the first chunk, then cancel from outside before the second is dispatched.
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal callSignal) {
                AiProviderResult result = AiProviderResult.ok(FakeAiProvider.echo(request),
                        AiUsage.of(10, 10), AiFinishReason.STOP, 64L);
                signal.abort("track_change");
                return result;
            }
        });

        AiLayerRunner.Args args = args(chunkedRows(), provider, store);
        args.signal = signal;
        AiRunOutcome outcome = AiLayerRunner.run(args);

        assertEquals(AiRunOutcome.Kind.CANCELLED, outcome.kind);
        assertEquals("track_change", outcome.cancelReason);
        assertEquals("only the first chunk should have been dispatched", 1, provider.calls.size());

        AiPaidRecord stored = store.peek(config());
        assertNotNull("a cancelled run must still store what it paid for", stored);
        assertEquals(AiPaidRecord.Status.PARTIAL, stored.status);
        assertEquals(64, stored.items().size());
        assertTrue(stored.chunk("C0").isComplete());
    }

    @Test
    public void aResumeSendsOnlyTheChunksItDoesNotAlreadyHold() {
        final AiSignal signal = new AiSignal();
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider first = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal callSignal) {
                AiProviderResult result = AiProviderResult.ok(FakeAiProvider.echo(request),
                        AiUsage.of(10, 10), AiFinishReason.STOP, 64L);
                signal.abort("track_change");
                return result;
            }
        });
        AiLayerRunner.Args firstArgs = args(chunkedRows(), first, store);
        firstArgs.signal = signal;
        AiLayerRunner.run(firstArgs);

        FakeAiProvider second = new FakeAiProvider();
        AiRunOutcome resumed = AiLayerRunner.run(args(chunkedRows(), second, store));

        assertEquals(AiRunOutcome.Kind.COMPLETED, resumed.kind);
        assertEquals("the completed chunk must not be resent", 2, second.calls.size());
        assertEquals(129, resumed.record.items().size());
    }

    @Test
    public void aFailedChunkStopsTheRunAndLeavesTheEarlierOnesStored() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider(
                new FakeAiProvider.Step() {
                    @Override public AiProviderResult answer(AiProviderRequest request,
                                                             AiProviderConfig config,
                                                             AiSignal signal) {
                        return AiProviderResult.ok(FakeAiProvider.echo(request),
                                AiUsage.of(10, 10), AiFinishReason.STOP, 64L);
                    }
                },
                new FakeAiProvider.Step() {
                    @Override public AiProviderResult answer(AiProviderRequest request,
                                                             AiProviderConfig config,
                                                             AiSignal signal) {
                        return AiProviderResult.failed(AiProviderFailure.auth());
                    }
                });

        AiRunOutcome outcome = AiLayerRunner.run(args(chunkedRows(), provider, store));

        assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
        assertEquals("auth_rejected", outcome.failureToken);
        assertEquals(LayerFailure.Reason.CLIENT_ERROR, outcome.failure.reason);

        AiPaidRecord stored = store.peek(config());
        assertEquals(AiPaidRecord.Status.FAILED, stored.status);
        assertTrue("the chunk that succeeded is still paid for", stored.chunk("C0").isComplete());
        assertEquals(64, stored.items().size());
    }

    @Test
    public void anExplicitRetryReopensOnlyTheFailedChunks() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider failing = new FakeAiProvider(
                new FakeAiProvider.Step() {
                    @Override public AiProviderResult answer(AiProviderRequest request,
                                                             AiProviderConfig config,
                                                             AiSignal signal) {
                        return AiProviderResult.ok(FakeAiProvider.echo(request),
                                AiUsage.of(10, 10), AiFinishReason.STOP, 64L);
                    }
                },
                new FakeAiProvider.Step() {
                    @Override public AiProviderResult answer(AiProviderRequest request,
                                                             AiProviderConfig config,
                                                             AiSignal signal) {
                        return AiProviderResult.failed(AiProviderFailure.quota());
                    }
                });
        AiLayerRunner.run(args(chunkedRows(), failing, store));

        FakeAiProvider retry = new FakeAiProvider();
        AiRunOutcome outcome = AiLayerRunner.run(args(chunkedRows(), retry, store));

        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertEquals("the completed chunk must not be bought again", 2, retry.calls.size());
    }

    // --- accounting ---------------------------------------------------------

    @Test
    public void aResumeCountsOnlyWhatTheNewAttemptAdded() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider();
        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(10, outcome.record.inputTokens);
        assertEquals(10, outcome.record.outputTokens);
        assertFalse(outcome.record.usageEstimated);
    }

    @Test
    public void unreportedUsageIsEstimatedAndSaysSo() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.ok(FakeAiProvider.echo(request), AiUsage.UNREPORTED,
                        AiFinishReason.STOP, 64L);
            }
        });

        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertTrue("an unknown cost must never be recorded as zero",
                outcome.record.inputTokens > 0 && outcome.record.outputTokens > 0);
        assertTrue(outcome.record.usageEstimated);
    }

    @Test
    public void aFailedAttemptIsStillCountedBecauseItStillBilled() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.failed(AiProviderFailure.quota());
            }
        });

        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
        assertTrue(outcome.record.inputTokens > 0);
        assertTrue(outcome.record.usageEstimated);
    }

    // --- durability ---------------------------------------------------------

    @Test
    public void aResultThatCouldNotBeStoredIsStillReturnedButNotClaimedAsSaved() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        store.rejectWrites = true;

        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), new FakeAiProvider(), store));

        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertTrue(outcome.hasOutput());
        assertFalse("a rejected write must not be reported as durable", outcome.durable);
        assertNull(store.peek(config()));
    }

    @Test
    public void deliveryUnknownIsFlaggedAsPossiblyBilled() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        FakeAiProvider provider = new FakeAiProvider(new FakeAiProvider.Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.failed(
                        AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.NETWORK, 0));
            }
        });

        AiRunOutcome outcome = AiLayerRunner.run(args(rows(3), provider, store));

        assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
        assertEquals("delivery_unknown", outcome.failureToken);
        assertTrue(outcome.mayHaveBilledUnconfirmed());
        assertEquals(LayerFailure.Reason.TIMEOUT, outcome.failure.reason);
        assertEquals("a delivery-unknown result is never retried automatically",
                1, provider.calls.size());
    }
}
