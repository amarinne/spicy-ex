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

        boolean durable = true;
        for (AiPlannedChunk chunk : plan.chunks) {
            AiChunkRecord previous = record.chunk(chunk.id);
            if (previous != null && previous.isComplete()) continue;

            if (args.signal != null && args.signal.isAborted()) {
                return stop(store, config, record, args, durable, args.signal.reason());
            }

            AiChunkExecution execution;
            try {
                execution = AiChunkRuntime.executeChunk(chunkArgs(args, chunk, previous));
            } catch (AiCancelledException cancelled) {
                // Nothing new to record for this chunk — the attempt did not complete — but every
                // chunk before it did, and those are already in the record.
                return stop(store, config, record, args, durable, cancelled.reason);
            }

            record.account(previous, execution.record);
            record.putChunk(chunk.id, execution.record);
            record.lastAccessedAtMs = args.nowMs;

            if (!execution.ok) {
                record.status = AiPaidRecord.Status.FAILED;
                durable &= commit(store, config, record);
                return AiRunOutcome.failed(record, execution.failure, durable);
            }

            for (AiResponseItem item : execution.items) record.putItem(item.id, item.text);
            record.status = record.satisfies(plan)
                    ? AiPaidRecord.Status.COMPLETE : AiPaidRecord.Status.PARTIAL;
            // Written per chunk rather than once at the end: this is the write that makes a
            // resumed run cheaper than a fresh one.
            durable &= commit(store, config, record);
        }

        record.status = AiPaidRecord.Status.COMPLETE;
        durable &= commit(store, config, record);
        return AiRunOutcome.completed(record, durable);
    }

    private static AiRunOutcome stop(AiRecordStore store, AiRunConfig config, AiPaidRecord record,
                                     Args args, boolean durable, String reason) {
        // Partial, never failed: the run was stopped from outside, and the chunks it did finish
        // are valid answers that a resume must be allowed to keep.
        record.status = AiPaidRecord.Status.PARTIAL;
        record.lastAccessedAtMs = args.nowMs;
        boolean stored = commit(store, config, record) && durable;
        return AiRunOutcome.cancelled(record, stored, reason);
    }

    private static boolean commit(AiRecordStore store, AiRunConfig config, AiPaidRecord record) {
        return store == null || store.commit(config, record);
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
