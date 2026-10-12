package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Interrupted writes and repairs must preserve paid ownership before empty-record recovery. */
public class AiRetainedInterruptionSafetyTest {
    private static AiLayerRunner.Args args(int count, int allowance, AiRecordStore store) {
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        AiModelDescriptor model = new AiModelDescriptor("fake-model", "1", 32768, 8192,
                Collections.emptyList(), allowance);
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1", model,
                "en", AiContract.PROMPT_VERSION, false);
        args.explicitRetry = true; // Recovery in these fixtures represents an explicit owner retry.
        args.config = new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        args.rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.rows.add(AiLine.of("r" + i, "hello world line " + i, null, false));
        }
        args.store = store;
        args.provider = new FakeAiProvider();
        args.useBaseline = false;
        return args;
    }

    private static AiChunkPlanner.Input input(AiLayerRunner.Args args) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = args.rows;
        input.target = args.config.targetLang;
        input.layer = args.config.layer;
        input.model = args.config.provider.model;
        return input;
    }

    private static void longRows(AiLayerRunner.Args args, int bytes) {
        String source = String.join("", Collections.nCopies(bytes, "x"));
        for (int i = 0; i < args.rows.size(); i++) {
            args.rows.set(i, AiLine.of("r" + i, source, null, false));
        }
    }

    private static void refusesReadOnly(AiLayerRunner.Args args, FakeAiRecordStore store,
                                        AiFailureReason reason) {
        String before = AiPaidRecordCodec.encode(store.peek(args.config));
        int commits = store.commits, reservations = store.reservations;
        for (boolean allow : new boolean[]{false, true, true}) {
            args.allowProviderRequest = allow;
            AiRunOutcome outcome = AiLayerRunner.run(args);
            assertEquals(AiRunOutcome.Kind.FAILED, outcome.kind);
            assertEquals(AiLayerFailures.token(reason), outcome.failureToken);
            assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
            assertEquals(commits, store.commits);
            assertEquals(reservations, store.reservations);
            assertEquals(before, AiPaidRecordCodec.encode(store.peek(args.config)));
        }
    }

    @Test public void lostFinalTruncationWriteNormalizesWithoutRepurchasingPaidSiblings() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiRecordStore interrupted = new AiRecordStore() {
            int commits;
            @Override public AiPaidRecord read(AiRunConfig config) { return store.read(config); }
            @Override public Reservation reserve(AiRunConfig config, long bytes) {
                return store.reserve(config, bytes);
            }
            @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
                return ++commits != 4 && store.commit(config, record);
            }
            @Override public void release(AiRunConfig config) { store.release(config); }
            @Override public void forget(AiRunConfig config) { store.forget(config); }
        };
        AiLayerRunner.Args first = args(129, -1, interrupted);
        first.rows.set(128, AiLine.of("r128", "one / two", null, false));
        first.provider = new FakeAiProvider(
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.echo(request),
                        AiUsage.of(10, 10), AiFinishReason.STOP, 1),
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.echo(request),
                        AiUsage.of(10, 10), AiFinishReason.STOP, 1),
                FakeAiProvider.body("", AiUsage.of(7, 3), AiFinishReason.LENGTH));
        assertEquals("storage_unavailable", AiLayerRunner.run(first).failureToken);
        AiPaidRecord held = store.peek(first.config);
        assertEquals(128, held.items().size());
        assertEquals(AiChunkRecord.Status.REPLANNED, held.chunk("C2").status);
        Map<String, String> paid = held.items();
        AiLayerRunner.Args resume = args(129, -1, store);
        resume.rows = first.rows;
        resume.allowProviderRequest = false;
        String before = AiPaidRecordCodec.encode(held);
        int commits = store.commits, reservations = store.reservations;
        assertEquals(AiRunOutcome.Kind.NOTHING_TO_DO, AiLayerRunner.run(resume).kind);
        assertEquals(before, AiPaidRecordCodec.encode(store.peek(first.config)));
        assertEquals(commits, store.commits);
        resume.allowProviderRequest = true;
        AiRunOutcome normalized = AiLayerRunner.run(resume);
        assertEquals("truncated", normalized.failureToken);
        assertEquals(AiChunkRecord.Status.FAILED, normalized.record.chunk("C2").status);
        assertEquals(paid, normalized.record.items());
        assertEquals(27, normalized.record.inputTokens);
        assertEquals(23, normalized.record.outputTokens);
        assertEquals(reservations, store.reservations);
        assertTrue(((FakeAiProvider) resume.provider).calls.isEmpty());
        AiRunOutcome retry = AiLayerRunner.run(resume);
        assertEquals(AiRunOutcome.Kind.COMPLETED, retry.kind);
        assertEquals(129, retry.record.items().size());
        for (Map.Entry<String, String> item : paid.entrySet()) {
            assertEquals(item.getValue(), retry.record.item(item.getKey()));
        }
        assertEquals(held.chunk("C0").ids, retry.record.chunk("C0").ids);
        assertEquals(held.chunk("C1").ids, retry.record.chunk("C1").ids);
        assertEquals(10, retry.record.chunk("C0").inputTokens);
        assertEquals(10, retry.record.chunk("C1").inputTokens);
        assertEquals(17, retry.record.chunk("C2").inputTokens);
        assertEquals(37, retry.record.inputTokens);
        assertEquals(33, retry.record.outputTokens);
        assertEquals(1, ((FakeAiProvider) resume.provider).calls.size());
        assertEquals(held.chunk("C2").ids,
                ((FakeAiProvider) resume.provider).calls.get(0).request.items.stream()
                        .map(item -> item.id).collect(java.util.stream.Collectors.toList()));
        assertEquals(AiRunOutcome.Kind.REUSED, AiLayerRunner.run(resume).kind);
    }

    @Test public void emptyPredispatchCancellationCanReplanOnlyAfterTheProviderGate() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args first = args(100, -1, store);
        longRows(first, 60);
        first.signal = new AiSignal();
        first.signal.abort("track_change");
        assertEquals(AiRunOutcome.Kind.CANCELLED, AiLayerRunner.run(first).kind);
        AiPaidRecord empty = store.peek(first.config);
        assertTrue(empty.chunks().isEmpty());
        assertEquals(0, empty.inputTokens + empty.outputTokens);
        assertFalse(empty.usageEstimated);
        assertNull("Predispatch cancellation must not bind an unused plan", empty.planning);
        assertTrue(((FakeAiProvider) first.provider).calls.isEmpty());
        // A valid unused snapshot from the previous harness remains safe to replace.
        empty.planning = AiPlanningSnapshot.capture(first.config.provider.model);
        store.commit(first.config, empty);
        AiLayerRunner.Args changed = args(100, 4000, store);
        changed.rows = first.rows;
        changed.allowProviderRequest = false;
        String before = AiPaidRecordCodec.encode(store.peek(first.config));
        int commits = store.commits, reservations = store.reservations;
        assertEquals(AiRunOutcome.Kind.NOTHING_TO_DO, AiLayerRunner.run(changed).kind);
        assertEquals(before, AiPaidRecordCodec.encode(store.peek(first.config)));
        assertEquals(commits, store.commits);
        assertEquals(reservations, store.reservations);
        changed.allowProviderRequest = true;
        AiRunOutcome outcome = AiLayerRunner.run(changed);
        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertEquals(2, ((FakeAiProvider) changed.provider).calls.size());
        assertEquals(64, outcome.record.chunk("C0").ids.size());
        assertEquals(36, outcome.record.chunk("C1").ids.size());
        assertEquals(4000, outcome.record.planning.reasoningAllowanceTokens);
        assertEquals(empty.createdAtMs, outcome.record.createdAtMs);
        assertTrue(first.config.matches(outcome.record));
    }

    @Test public void cancelledRepairRetainsServedAttemptBeforeAnyEmptyRecordRecovery() {
        for (int delivered = 0; delivered < 3; delivered++) {
            boolean estimated = delivered == 0;
            int reportedInput = delivered == 2 ? 0 : 7;
            int reportedOutput = delivered == 2 ? 0 : 3;
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args first = args(100, -1, store);
            longRows(first, 60);
            first.signal = new AiSignal();
            first.provider = new FakeAiProvider((request, config, signal) -> {
                first.signal.abort("track_change");
                return estimated ? AiProviderResult.failed(AiProviderFailure.protocol("invalid_json"))
                        : AiProviderResult.ok("{}", AiUsage.of(reportedInput, reportedOutput),
                                AiFinishReason.STOP, 1);
            });
            assertEquals(AiRunOutcome.Kind.CANCELLED, AiLayerRunner.run(first).kind);
            assertEquals(1, ((FakeAiProvider) first.provider).calls.size());
            AiPaidRecord held = store.peek(first.config);
            assertEquals(1, held.chunks().size());
            assertEquals(AiChunkRecord.Status.FAILED, held.chunk("C0").status);
            assertEquals(AiFailureReason.PROTOCOL_INVALID, held.chunk("C0").failure.reason);
            assertEquals(1, held.chunk("C0").attempts);
            assertEquals(1, held.chunk("C0").repairs);
            assertEquals(estimated, held.usageEstimated);
            assertEquals(held.chunk("C0").inputTokens, held.inputTokens);
            assertEquals(held.chunk("C0").outputTokens, held.outputTokens);
            if (delivered == 2) assertEquals(0, held.inputTokens);
            else assertTrue(held.inputTokens > 0);
            assertEquals(estimated ? 8192 : reportedOutput, held.outputTokens);
            AiLayerRunner.Args changed = args(100, 4000, store);
            changed.rows = first.rows;
            refusesReadOnly(changed, store, AiFailureReason.OVERSIZED);
            AiLayerRunner.Args retry = args(100, -1, store);
            retry.rows = first.rows;
            AiRunOutcome completed = AiLayerRunner.run(retry);
            assertEquals(AiRunOutcome.Kind.COMPLETED, completed.kind);
            assertEquals(held.inputTokens + 10, completed.record.inputTokens);
            assertEquals(held.outputTokens + 10, completed.record.outputTokens);
            assertEquals(1, ((FakeAiProvider) retry.provider).calls.size());
        }
    }

    @Test public void cancellationBetweenRateLimitAttemptsRetainsItsKnownFailureAndEstimate() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(3, -1, store);
        args.signal = new AiSignal();
        args.wait = (millis, signal) -> signal.abort("track_change");
        args.provider = new FakeAiProvider(FakeAiProvider.failure(AiProviderFailure.rateLimited(1L)));
        AiRunOutcome outcome = AiLayerRunner.run(args);
        assertEquals(AiRunOutcome.Kind.CANCELLED, outcome.kind);
        AiPaidRecord held = store.peek(args.config);
        assertEquals(AiFailureReason.RATE_LIMITED, held.chunk("C0").failure.reason);
        assertEquals(1, held.chunk("C0").attempts);
        assertEquals(0, held.chunk("C0").repairs);
        assertTrue(held.usageEstimated);
        assertEquals(8192, held.outputTokens);
        assertEquals(1, ((FakeAiProvider) args.provider).calls.size());
    }

    @Test public void noChildReplannedNodeStillRejectsOrphanDescendantsAndOutput() {
        for (boolean descendant : new boolean[]{false, true}) {
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args args = args(1, -1, store);
            args.rows.set(0, AiLine.of("r0", "one / two", null, false));
            AiPlannedChunk root = AiChunkPlanner.plan(input(args)).chunks.get(0);
            assertTrue(AiChunkPlanner.replan(input(args), root).isEmpty());
            AiPaidRecord held = AiPaidRecord.begin(args.config, 1);
            held.planning = AiPlanningSnapshot.capture(args.config.provider.model);
            AiChunkRecord replanned = AiChunkRecord.forChunk(root);
            replanned.status = AiChunkRecord.Status.REPLANNED;
            held.putChunk("C0", replanned);
            if (descendant) {
                held.putChunk("C0.0", new AiChunkRecord(Collections.singletonList("r0~0"), ""));
            } else {
                held.putItem("r0", "unowned");
            }
            store.commit(args.config, held);
            refusesReadOnly(args, store, AiFailureReason.PROTOCOL_INVALID);
        }
    }

    @Test public void unfinishedReplannedParentCannotIgnoreACorruptLaterChildResult() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(8, -1, store);
        AiChunkPlanner.Input input = input(args);
        AiPlannedChunk root = AiChunkPlanner.plan(input).chunks.get(0);
        List<AiPlannedChunk> children = AiChunkPlanner.replan(input, root);
        assertEquals(2, children.size());
        AiPaidRecord held = AiPaidRecord.begin(args.config, 1);
        held.planning = AiPlanningSnapshot.capture(input.model);
        AiChunkRecord parent = AiChunkRecord.forChunk(root);
        parent.status = AiChunkRecord.Status.REPLANNED;
        held.putChunk("C0", parent);
        AiChunkRecord invalid = AiChunkRecord.forChunk(children.get(1));
        invalid.status = AiChunkRecord.Status.COMPLETE; // Its claimed output was never recorded.
        held.putChunk("C0.1", invalid);
        store.commit(args.config, held);
        refusesReadOnly(args, store, AiFailureReason.PROTOCOL_INVALID);
    }

    @Test public void outputBesideUnfinishedWorkMustHaveExactlyATerminalOwner() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(129, -1, store);
        AiChunkPlan plan = AiChunkPlanner.plan(input(args));
        AiPaidRecord held = AiPaidRecord.begin(args.config, 1);
        held.planning = AiPlanningSnapshot.capture(args.config.provider.model);
        AiChunkRecord paid = AiChunkRecord.forChunk(plan.chunks.get(0));
        paid.status = AiChunkRecord.Status.COMPLETE;
        held.putChunk("C0", paid);
        for (String id : paid.ids) held.putItem(id, "paid");
        AiChunkRecord failed = AiChunkRecord.forChunk(plan.chunks.get(1));
        failed.status = AiChunkRecord.Status.FAILED;
        failed.failure = AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
        held.putChunk("C1", failed);
        held.putItem("r128", "unowned");
        store.commit(args.config, held);
        refusesReadOnly(args, store, AiFailureReason.PROTOCOL_INVALID);
    }

    @Test public void everyOutstandingChildMustFitBeforeAnyFailureIsReopenedOrWritten() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(8, -1, store);
        // Equal source sizes keep two four-row children. A later baseline enlarges only input.
        String baseline = String.join("", Collections.nCopies(1500, "a"));
        args.rows.set(7, AiLine.withBaseline("r7", "hello world line 7", null, false,
                baseline, "google"));
        args.useBaseline = true;
        AiChunkPlanner.Input input = input(args);
        input.useMeaningBaseline = true;
        AiPlannedChunk root = AiChunkPlanner.plan(input).chunks.get(0);
        List<AiPlannedChunk> children = AiChunkPlanner.replan(input, root);
        assertEquals(2, children.size());
        assertTrue(children.get(1).estimatedInputTokens > children.get(0).estimatedInputTokens);
        AiPaidRecord held = AiPaidRecord.begin(args.config, 1);
        held.planning = AiPlanningSnapshot.capture(input.model);
        AiChunkRecord parent = AiChunkRecord.forChunk(root);
        parent.status = AiChunkRecord.Status.REPLANNED;
        held.putChunk("C0", parent);
        for (AiPlannedChunk child : children) {
            AiChunkRecord failed = AiChunkRecord.forChunk(child);
            failed.status = AiChunkRecord.Status.FAILED;
            failed.failure = AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
            held.putChunk(child.id, failed);
        }
        store.commit(args.config, held);
        AiModelDescriptor smaller = new AiModelDescriptor("fake-model", "1",
                children.get(0).estimatedInputTokens, 8192, Collections.emptyList());
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1", smaller,
                "en", AiContract.PROMPT_VERSION, false);
        args.explicitRetry = true; // Recovery in these fixtures represents an explicit owner retry.
        args.config = new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        refusesReadOnly(args, store, AiFailureReason.OVERSIZED);
    }

    @Test public void pinnedMismatchCannotUseTheSnapshotFreeRecoveryLane() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args original = args(100, -1, store);
        AiPaidRecord held = AiPaidRecord.begin(original.config, 1);
        AiChunkRecord failed = AiChunkRecord.forChunk(AiChunkPlanner.plan(input(original)).chunks.get(0));
        failed.status = AiChunkRecord.Status.FAILED;
        failed.failure = AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
        held.putChunk("C0", failed);
        AiLayerRunner.Args changed = args(100, 4000, store);
        held.planning = AiPlanningSnapshot.capture(changed.config.provider.model);
        assertEquals(2, AiChunkPlanner.plan(input(changed)).chunks.size());
        store.commit(original.config, held);
        refusesReadOnly(changed, store, AiFailureReason.PROTOCOL_INVALID);
    }

    @Test public void recordedRecoveryEnforcesRootBytesAndSingleRootOutputBounds() {
        for (boolean single : new boolean[]{false, true}) {
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args args = args(single ? 120 : 65, 0, store);
            longRows(args, single ? 100 : 130);
            AiChunkPlan current = AiChunkPlanner.plan(input(args));
            assertEquals(single ? 2 : 1, current.chunks.size());
            AiPaidRecord held = AiPaidRecord.begin(args.config, 1);
            List<String> ids = new ArrayList<>();
            for (AiLine row : args.rows) ids.add(row.id);
            held.putChunk("C0", new AiChunkRecord(single ? ids : ids.subList(0, 64), ""));
            if (!single) held.putChunk("C1", new AiChunkRecord(ids.subList(64, ids.size()), ""));
            store.commit(args.config, held);
            refusesReadOnly(args, store, AiFailureReason.PROTOCOL_INVALID);
        }
    }

    @Test public void onlyValidEmptyUnspentPartialRecordsMayReplaceUnusedPlanning() {
        for (int evidence = 0; evidence < 7; evidence++) {
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args original = args(100, -1, store);
            longRows(original, 60);
            AiPaidRecord held = AiPaidRecord.begin(original.config, 1);
            held.planning = AiPlanningSnapshot.capture(original.config.provider.model);
            switch (evidence) {
                case 0: held.inputTokens = 1; break;
                case 1: held.outputTokens = 1; break;
                case 2: held.usageEstimated = true; break;
                case 3: held.status = AiPaidRecord.Status.FAILED; break;
                case 4: held.planning = AiPlanningSnapshot.INVALID; break;
                case 5: held.putChunk("C0", AiChunkRecord.forChunk(
                        AiChunkPlanner.plan(input(original)).chunks.get(0))); break;
                default:
                    AiChunkRecord failed = AiChunkRecord.forChunk(
                            AiChunkPlanner.plan(input(original)).chunks.get(0));
                    failed.status = AiChunkRecord.Status.FAILED;
                    failed.failure = AiChunkFailure.of(AiFailureReason.DELIVERY_UNKNOWN);
                    held.putChunk("C0", failed);
            }
            store.commit(original.config, held);
            AiLayerRunner.Args changed = args(100, 4000, store);
            changed.rows = original.rows;
            refusesReadOnly(changed, store, evidence == 4 ? AiFailureReason.PROTOCOL_INVALID
                    : AiFailureReason.OVERSIZED);
        }
    }
}
