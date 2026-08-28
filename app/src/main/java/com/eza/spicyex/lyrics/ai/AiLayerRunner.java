package com.eza.spicyex.lyrics.ai;

import java.util.List;

/**
 * Runs one layer's document end to end: reuse, plan, call, validate, store.
 *
 * <p>This is where money is spent, so the order of its decisions is the design. It asks the store
 * before it asks a provider, it stores each chunk the moment that chunk validates, and it treats
 * every exit — cancelled, failed, finished — as a point at which what has already been bought must
 * be written down. A run that lost a completed chunk because the track changed would charge the
 * owner twice for the same lines, and they would have no way to know it had happened.
 *
 * <p>It deliberately owns no threading, no lifecycle and no Android types. The lane decides when to
 * run and on which executor; this decides what a run costs.
 */
public final class AiLayerRunner {

    /** Inputs for one run. */
    public static final class Args {
        public AiRunConfig config;
        public AiProvider provider;
        public AiRecordStore store;
        /** Every enumerable row of the document, sent or not. */
        public List<AiLine> rows;
        public AiSignal signal;
        public AiRunMonitor monitor;
        /** False permits exact stored reuse but forbids a new provider dispatch. */
        public boolean allowProviderRequest = true;
        /** False sends canonical source alone; true permits the layer's existing baseline in p. */
        public boolean useBaseline = true;
        /** Null uses {@link AiWait#DEFAULT}. Tests inject a wait that does not sleep. */
        public AiWait wait;
        /** Clock seam, so a record's timestamps are assertable. */
        public long nowMs = System.currentTimeMillis();
    }

    private AiLayerRunner() {
    }

    public static AiRunOutcome run(Args args) {
        AiRunConfig config = args.config;
        AiRecordStore store = args.store;

        // 1. Ask the store first. An identical finished answer must never be bought twice, and this
        //    is the check that makes revisiting a track free.
        AiPaidRecord stored = store == null ? null : store.read(config);
        if (stored != null && stored.isComplete()) return AiRunOutcome.reused(stored);

        // On-demand mode still checks the paid store so revisiting an accepted answer is free, but
        // it must not turn ordinary background processing into a billable request. Only an
        // explicit action or automatic mode may cross this boundary.
        if (!args.allowProviderRequest) return AiRunOutcome.nothingToDo();

        // 2. Plan before spending. Both refusals below happen with no call made.
        AiChunkPlan plan;
        try {
            plan = AiChunkPlanner.plan(plannerInput(args));
        } catch (AiOversizedException oversized) {
            return AiRunOutcome.failed(null, AiChunkFailure.of(AiFailureReason.OVERSIZED), true);
        }
        if (plan.isEmpty()) return AiRunOutcome.nothingToDo();

        AiPaidRecord record = stored != null ? stored : AiPaidRecord.begin(config, args.nowMs);
        // A record that failed before is resumable: its completed chunks stand, and only the failed
        // ones are reopened. An explicit retry is the only thing that reaches this line.
        record.reopenFailedChunks();

        try {
            boolean durable = store == null || stored != null;
            boolean anyChunkFellBack = false;
            for (AiPlannedChunk chunk : plan.chunks) {
                ChunkRunResult result = runChunk(args, plannerInput(args), store, config, record,
                        chunk, durable);
                durable = result.durable;
                anyChunkFellBack |= result.fellBack;
                if (result.cancelReason != null) {
                    return stop(store, config, record, args, durable, result.cancelReason);
                }
                if (result.failure != null) {
                    if (isStorageFailure(result.failure)) {
                        return AiRunOutcome.failed(record, result.failure, durable);
                    }
                    record.status = AiPaidRecord.Status.FAILED;
                    if (!commit(store, config, record)) {
                        return storageCommitFailure(record);
                    }
                    return AiRunOutcome.failed(record, result.failure, true);
                }
            }

            // A run whose every chunk is terminal is finished even when some rows fell back —
            // display composes those rows from the baseline layer. The record deliberately does
            // not become COMPLETE in that case: satisfies() stays strict, so a half-AI answer can
            // never be reused as if it were a full one.
            record.status = record.satisfies(plan) ? AiPaidRecord.Status.COMPLETE
                    : anyChunkFellBack ? AiPaidRecord.Status.PARTIAL : record.status;
            if (!commit(store, config, record)) return storageCommitFailure(record);
            return AiRunOutcome.completed(record, durable);
        } finally {
            if (store != null) store.release(config);
        }
    }

    /** Result of one original or hierarchical child chunk. */
    private static final class ChunkRunResult {
        final boolean durable;
        final boolean fellBack;
        final AiChunkFailure failure;
        final String cancelReason;

        ChunkRunResult(boolean durable, boolean fellBack, AiChunkFailure failure,
                       String cancelReason) {
            this.durable = durable;
            this.fellBack = fellBack;
            this.failure = failure;
            this.cancelReason = cancelReason;
        }

        static ChunkRunResult done(boolean durable, boolean fellBack) {
            return new ChunkRunResult(durable, fellBack, null, null);
        }
    }

    /**
     * Runs one chunk, recursively replacing a true-ceiling truncation with smaller planned work.
     * Every state transition is committed before moving on, so resume never repeats a parent or a
     * completed child that was already billed.
     */
    private static ChunkRunResult runChunk(Args args, AiChunkPlanner.Input plannerInput,
                                           AiRecordStore store, AiRunConfig config,
                                           AiPaidRecord record, AiPlannedChunk chunk,
                                           boolean durable) {
        AiChunkRecord previous = record.chunk(chunk.id);
        if (previous != null && previous.isTerminal()) {
            return ChunkRunResult.done(durable,
                    previous.status == AiChunkRecord.Status.COMPLETED_FALLBACK);
        }
        if (args.signal != null && args.signal.isAborted()) {
            return new ChunkRunResult(durable, false, null, args.signal.reason());
        }

        if (previous == null || previous.status != AiChunkRecord.Status.REPLANNED) {
            AiRecordStore.Reservation reservation = reserve(store, config, record);
            if (!reservation.accepted()) {
                return new ChunkRunResult(durable, false, storageFailure(reservation), null);
            }
            AiChunkExecution execution;
            try {
                execution = AiChunkRuntime.executeChunk(chunkArgs(args, chunk, previous));
            } catch (AiCancelledException cancelled) {
                return new ChunkRunResult(durable, false, null, cancelled.reason);
            }

            record.account(previous, execution.record);
            record.lastAccessedAtMs = args.nowMs;
            if (!execution.ok && execution.failure.reason == AiFailureReason.TRUNCATED
                    && chunk.items.size() > 1) {
                execution.record.status = AiChunkRecord.Status.REPLANNED;
                record.putChunk(chunk.id, execution.record);
                record.status = AiPaidRecord.Status.PARTIAL;
                if (!commit(store, config, record)) {
                    return new ChunkRunResult(false, false,
                            AiChunkFailure.of(AiFailureReason.STORAGE_UNAVAILABLE), null);
                }
                durable = true;
                previous = execution.record;
            } else {
                record.putChunk(chunk.id, execution.record);
                if (!execution.ok) return new ChunkRunResult(durable, false, execution.failure, null);
                for (AiResponseItem item : execution.items) record.putItem(item.id, item.text);
                boolean fellBack = !execution.fallbackRowIds.isEmpty();
                record.status = AiPaidRecord.Status.PARTIAL;
                if (!commit(store, config, record)) {
                    return new ChunkRunResult(false, fellBack,
                            AiChunkFailure.of(AiFailureReason.STORAGE_UNAVAILABLE), null);
                }
                return ChunkRunResult.done(true, fellBack);
            }
        }

        List<AiPlannedChunk> children = AiChunkPlanner.replan(plannerInput, chunk);
        if (children.isEmpty()) {
            previous.status = AiChunkRecord.Status.FAILED;
            previous.failure = AiChunkFailure.of(AiFailureReason.TRUNCATED);
            record.putChunk(chunk.id, previous);
            return new ChunkRunResult(durable, false, previous.failure, null);
        }

        boolean fellBack = false;
        for (AiPlannedChunk child : children) {
            ChunkRunResult childResult = runChunk(args, plannerInput, store, config, record, child,
                    durable);
            durable = childResult.durable;
            fellBack |= childResult.fellBack;
            if (childResult.cancelReason != null || childResult.failure != null) {
                return new ChunkRunResult(durable, fellBack, childResult.failure,
                        childResult.cancelReason);
            }
        }

        AiChunkRecord completedParent = record.chunk(chunk.id).copy();
        completedParent.status = fellBack ? AiChunkRecord.Status.COMPLETED_FALLBACK
                : AiChunkRecord.Status.COMPLETE;
        completedParent.failure = null;
        record.putChunk(chunk.id, completedParent);
        record.status = AiPaidRecord.Status.PARTIAL;
        if (!commit(store, config, record)) {
            return new ChunkRunResult(false, fellBack,
                    AiChunkFailure.of(AiFailureReason.STORAGE_UNAVAILABLE), null);
        }
        return ChunkRunResult.done(true, fellBack);
    }

    private static AiRunOutcome stop(AiRecordStore store, AiRunConfig config, AiPaidRecord record,
                                     Args args, boolean durable, String reason) {
        // Partial, never failed: the run was stopped from outside, and the chunks it did finish
        // are valid answers that a resume must be allowed to keep.
        record.status = AiPaidRecord.Status.PARTIAL;
        record.lastAccessedAtMs = args.nowMs;
        boolean stored = commit(store, config, record);
        return AiRunOutcome.cancelled(record, stored, reason);
    }

    private static boolean commit(AiRecordStore store, AiRunConfig config, AiPaidRecord record) {
        return store == null || store.commit(config, record);
    }

    private static AiRecordStore.Reservation reserve(AiRecordStore store, AiRunConfig config,
                                                      AiPaidRecord record) {
        return store == null ? AiRecordStore.Reservation.admitted()
                : store.reserve(config, AiStorageBudget.maxRecordBytes(record));
    }

    private static AiChunkFailure storageFailure(AiRecordStore.Reservation reservation) {
        return AiChunkFailure.of(reservation.status == AiRecordStore.Reservation.Status.FULL
                ? AiFailureReason.STORAGE_FULL : AiFailureReason.STORAGE_UNAVAILABLE);
    }

    private static boolean isStorageFailure(AiChunkFailure failure) {
        return failure != null && (failure.reason == AiFailureReason.STORAGE_FULL
                || failure.reason == AiFailureReason.STORAGE_UNAVAILABLE);
    }

    private static AiRunOutcome storageCommitFailure(AiPaidRecord record) {
        return AiRunOutcome.failed(record,
                AiChunkFailure.of(AiFailureReason.STORAGE_UNAVAILABLE), false);
    }

    private static AiChunkPlanner.Input plannerInput(Args args) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = args.rows;
        input.target = args.config.provider.targetLang;
        input.model = args.config.provider.model;
        input.context = args.config.context;
        input.instructions = args.config.instructions;
        input.layer = args.config.layer;
        input.useSoundBaseline = args.useBaseline;
        input.useMeaningBaseline = args.useBaseline;
        input.baselineRefinement = args.config.provider.baselineRefinement;
        return input;
    }

    private static AiChunkRuntime.Args chunkArgs(Args args, AiPlannedChunk chunk,
                                                 AiChunkRecord previous) {
        AiChunkRuntime.Args runtimeArgs = new AiChunkRuntime.Args();
        runtimeArgs.provider = args.provider;
        runtimeArgs.chunk = chunk;
        runtimeArgs.config = args.config.provider;
        runtimeArgs.signal = args.signal;
        runtimeArgs.previous = previous;
        runtimeArgs.wait = args.wait;
        runtimeArgs.monitor = args.monitor;
        return runtimeArgs;
    }
}
