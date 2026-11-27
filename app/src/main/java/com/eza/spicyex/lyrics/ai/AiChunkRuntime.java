package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Runs one chunk, and owns every decision about how often it may be paid for.
 *
 * <p>Two attempts total, across structural repair and rate-limit retry. That budget is the whole
 * cost control at this level, so it is counted here rather than inside a provider: an adapter that
 * retried on its own would double a bill nobody authorized.
 *
 * <p>The asymmetry between failure kinds is deliberate. A malformed response was delivered and can
 * be asked for again. A rate limit says exactly when to come back. But a network error, a deadline,
 * or a 5xx <em>after dispatch</em> leaves us genuinely unable to tell whether the request was
 * served and billed — so that one is never retried automatically, and it is reported as unknown
 * rather than as failed.
 *
 * <p>Every attempt is accounted, including the ones that produced nothing usable. When usage goes
 * unreported the estimate is deliberately conservative and the record is marked estimated, because
 * a spend figure that flatters itself is worse than one that admits it is approximate.
 */
public final class AiChunkRuntime {

    /** One execution's inputs. */
    public static final class Args {
        public AiProvider provider;
        public AiPlannedChunk chunk;
        public AiProviderConfig config;
        public AiSignal signal;
        public AiRunMonitor monitor;
        /** A prior record to resume, or null to start one. A complete chunk is never resent. */
        public AiChunkRecord previous;
        /** Null uses {@link AiWait#DEFAULT}. */
        public AiWait wait;
        /** Zero derives it from the dispatched output budget; tests override it. */
        public long deadlineMs = 0L;
    }

    private static Timer deadlineTimer;

    /**
     * The output cap for the first attempt of a chunk: the planned estimate (visible output plus
     * its reasoning allowance), never below {@link AiContract#MIN_CALL_OUTPUT_TOKENS}, never above
     * the configuration's absolute bound.
     *
     * <p>The bound itself, not the planner's estimate. A cap is a ceiling, not a charge — the same
     * argument that sizes {@link AiContract#PROBE_OUTPUT_TOKENS}. Output is billed by what the
     * model produced, so asking for less than the ceiling saves nothing and manufactures the
     * truncation that hierarchical re-planning then has to recover from, at the price of extra
     * billed calls for smaller child chunks.
     *
     * <p>It previously dispatched the estimate so escalation had headroom to climb into, which had
     * the effect exactly backwards: a 58-row song estimated at 2860 tokens truncated there,
     * escalated to 5720, truncated again, and gave up terminally — with the 8192 the endpoint
     * allowed never once requested, because one escalation exhausts {@link AiContract#MAX_ATTEMPTS}.
     *
     * <p>The estimate keeps its real job, which is planning: it decides where chunk boundaries fall
     * so a document that cannot fit one call is split before anything is sent. A length finish at
     * the true ceiling means the chunk is too large, and the answer to that is a smaller chunk, not
     * a larger cap.
     */
    static int initialOutputBudget(AiPlannedChunk chunk, int absoluteBound) {
        return Math.max(AiContract.MIN_CALL_OUTPUT_TOKENS, absoluteBound);
    }

    private AiChunkRuntime() {
    }

    public static AiChunkExecution executeChunk(Args args) {
        AiWait wait = args.wait == null ? AiWait.DEFAULT : args.wait;
        if (args.previous != null && args.previous.isComplete()) {
            throw new IllegalStateException("completed chunk must not be resent");
        }
        AiChunkRecord record = args.previous == null
                ? AiChunkRecord.forChunk(args.chunk) : args.previous.copy();
        record.fallbackRowIds.clear();
        long accountedTokens = 0L;
        /** The cap this chunk asks for: the ceiling, because a cap is not a charge. */
        int outputBudget = initialOutputBudget(args.chunk, args.config.callOutputTokens());
        int initialAttempts = record.attempts;
        AiChunkFailure repairFailure = null;

        while (record.attempts < AiContract.MAX_ATTEMPTS) {
            int maxOutputTokens = outputBudget;
            try {
                if (args.signal != null) args.signal.throwIfAborted();
            } catch (AiCancelledException cancelled) {
                if (record.attempts == initialAttempts) throw cancelled;
                // The prior result arrived. Only its unsent repair or rate-limit retry was cancelled.
                record.status = AiChunkRecord.Status.FAILED;
                record.failure = repairFailure;
                return AiChunkExecution.cancelled(record.failure, record, accountedTokens,
                        cancelled.reason);
            }
            record.attempts++;

            AiProviderRequest request = new AiProviderRequest(args.chunk.context,
                    args.config.targetLang, args.chunk.instructions, args.chunk.items);
            AiProviderConfig callConfig = callConfig(args, request, record.repairs > 0,
                    maxOutputTokens);
            int estimatedCallInput = Math.max(args.chunk.estimatedInputTokens,
                    AiChunkPlanner.ceilHalf(AiText.utf8Bytes(callConfig.systemPrompt())
                            + AiText.utf8Bytes(request.toJson())));
            long reservation = (long) estimatedCallInput + maxOutputTokens;

            AiProviderResult result;
            try {
                result = callOnce(args, request, callConfig, record.attempts);
            } catch (Throwable dispatched) {
                // The call left; whether it was served and billed is not knowable from here.
                accountedTokens += reservation;
                estimateUsage(record, estimatedCallInput, maxOutputTokens);
                return fail(record, AiChunkFailure.of(AiFailureReason.DELIVERY_UNKNOWN),
                        accountedTokens);
            }

            if (result == null || !result.ok) {
                AiProviderFailure failure = result == null
                        ? AiProviderFailure.protocol("no_result") : result.failure;

                if (failure.kind == AiProviderFailure.Kind.PROTOCOL
                        && record.attempts < AiContract.MAX_ATTEMPTS) {
                    accountedTokens += reservation;
                    estimateUsage(record, estimatedCallInput, maxOutputTokens);
                    record.repairs++;
                    repairFailure = mapFailure(failure);
                    continue;
                }
                if (failure.kind == AiProviderFailure.Kind.RATE_LIMITED
                        && record.attempts < AiContract.MAX_ATTEMPTS) {
                    // Declined before generation: nothing to charge.
                    try {
                        wait.await(retryAfterMs(failure), args.signal);
                    } catch (RuntimeException cancelled) {
                        // The attempt was still charged; report it rather than losing it.
                        return fail(record, mapFailure(failure), accountedTokens);
                    }
                    repairFailure = mapFailure(failure);
                    continue;
                }
                if (!declinedBeforeGeneration(failure)) {
                    accountedTokens += reservation;
                    estimateUsage(record, estimatedCallInput, maxOutputTokens);
                }
                return fail(record, mapFailure(failure), accountedTokens);
            }

            int input = result.usage.input == null
                    ? estimatedCallInput : result.usage.input;
            int output = result.usage.output == null ? maxOutputTokens : result.usage.output;
            accountedTokens += (long) input + output;
            record.inputTokens += input;
            record.outputTokens += output;
            record.usageEstimated |= !result.usage.isComplete();

            if (result.finish == AiFinishReason.LENGTH) {
                // The cap dispatched is already the ceiling this configuration allows, so there is
                // no larger one to ask for and re-sending the same chunk would truncate again at
                // the same place. Truncation here means the chunk is too large for one call, and
                // the answer to that is a smaller chunk — the planner's job, not a second billed
                // attempt. The truncated attempt was billed and stays accounted; this typed result
                // is the layer runner's handoff to deterministic hierarchical re-planning.
                return fail(record, AiChunkFailure.of(AiFailureReason.TRUNCATED), accountedTokens);
            }
            if (result.finish != AiFinishReason.STOP) {
                return fail(record, AiChunkFailure.of(AiFailureReason.PROVIDER_REFUSED),
                        accountedTokens);
            }

            try {
                List<Object> raw = AiTextWire.readItems(result.rawText, args.chunk.items,
                        args.config.layer);
                AiResponseValidator.Partition partition = AiResponseValidator.partition(raw,
                        args.chunk.items, args.config.layer, args.config.targetLang);
                if (!partition.structural.isEmpty()) {
                    // Identity violations poison the whole answer: route them through the same
                    // repair-or-terminal decision a malformed body gets.
                    throw new AiProtocolException(partition.structural);
                }

                List<AiResponseItem> acceptedRows = new ArrayList<>(partition.accepted);
                List<AiResponseValidator.RowFailure> stillFailed =
                        new ArrayList<>(partition.failed);

                if (!stillFailed.isEmpty()) {
                    if (record.attempts < AiContract.MAX_ATTEMPTS) {
                        record.repairs++;
                        TargetedReAsk reAsk = targetedReAsk(args, stillFailed, maxOutputTokens,
                                accountedTokens, record, acceptedRows);
                        accountedTokens = reAsk.accountedTokens;
                        acceptedRows = reAsk.acceptedRows;
                        stillFailed = reAsk.stillFailed;
                    }
                    // Out of budget: the kept rows stand and the rest fall back to the baseline
                    // layer. The chunk is finished, not complete.
                }

                if (stillFailed.isEmpty()) {
                    record.status = AiChunkRecord.Status.COMPLETE;
                    record.failure = null;
                    return AiChunkExecution.accepted(acceptedRows, record, accountedTokens);
                }
                if (acceptedRows.isEmpty()) {
                    // Nothing survived either attempt, so there is no partial answer to keep: this
                    // is the terminal protocol failure it would have been without partitioning.
                    AiResponseValidator.RowFailure first = stillFailed.get(0);
                    return fail(record, new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                            first.token + ":" + first.rowId), accountedTokens);
                }
                record.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
                record.failure = null;
                record.fallbackRowIds.addAll(rowIdsOf(stillFailed));
                return AiChunkExecution.accepted(acceptedRows, record, accountedTokens,
                        rowIdsOf(stillFailed));
            } catch (AiProtocolException invalid) {
                if (record.attempts < AiContract.MAX_ATTEMPTS) {
                    record.repairs++;
                    repairFailure = new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                            invalid.getMessage());
                    continue;
                }
                return fail(record, new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                        invalid.getMessage()), accountedTokens);
            }
        }
        throw new IllegalStateException("unreachable attempt state");
    }

    private static long retryAfterMs(AiProviderFailure failure) {
        long asked = failure.retryAfterMs == null
                ? AiContract.RETRY_AFTER_DEFAULT_MS : failure.retryAfterMs;
        return Math.min(asked, AiContract.RETRY_AFTER_CAP_MS);
    }

    /**
     * One monitored, deadline-bounded provider call. Accounting stays with the caller: only it
     * knows whether a throw means terminal unknown-delivery or a tolerable re-ask miss.
     */
    private static AiProviderResult callOnce(Args args, AiProviderRequest request,
                                             AiProviderConfig callConfig, int attemptNumber) {
        if (args.monitor != null) {
            args.monitor.onAttempt(args.chunk.id, attemptNumber,
                    args.provider.monitorPayload(request, callConfig));
        }
        AiSignal callSignal = new AiSignal();
        AiSignal.Registration linked = AiSignal.link(args.signal, callSignal);
        TimerTask deadline = scheduleDeadline(callSignal, args.deadlineMs > 0L
                ? args.deadlineMs : AiContract.callDeadlineMs(callConfig.maxOutputTokens));
        try {
            AiProviderResult result = args.provider.generateChunk(request, callConfig, callSignal,
                    streamListener(args, request, attemptNumber));
            // Reported for every returned attempt, including the ones the runtime then rejects as
            // truncated or malformed. Those are the attempts whose reasoning is worth reading: the
            // owner was billed for the thinking either way, and it is what says whether the model
            // misread the request or simply ran out of room.
            if (args.monitor != null && result != null && result.hasReasoning()) {
                args.monitor.onReasoning(args.chunk.id, attemptNumber, result.reasoning);
            }
            return result;
        } finally {
            if (deadline != null) deadline.cancel();
            linked.remove();
        }
    }

    /**
     * Forwards a streamed attempt to the monitor: the reasoning so far, and every finished line
     * (Meaning text wire) or closed JSON item (Sound) that already passes the row checks. Only
     * finished items are parsed, so a half-written row never reaches the screen; an unreadable
     * prefix simply waits for more text.
     */
    private static AiProvider.StreamListener streamListener(final Args args,
                                                            final AiProviderRequest request,
                                                            final int attemptNumber) {
        if (args.monitor == null) return null;
        final com.eza.spicyex.lyrics.session.LayerKind layer = args.config.layer;
        final int totalRows = uniqueRowCount(request.items);
        return new AiProvider.StreamListener() {
            private int shown;

            @Override public void onText(String textSoFar) {
                try {
                    List<Object> parsed;
                    if (AiTextWire.usesText(layer)) {
                        int end = textSoFar.lastIndexOf('\n');
                        if (end < 0) return;
                        parsed = AiTextWire.readItems(textSoFar.substring(0, end),
                                request.items, layer);
                    } else {
                        parsed = AiResponseReader.readPartialItems(textSoFar);
                        if (parsed.isEmpty()) return;
                    }
                    AiResponseValidator.Partition partial = AiResponseValidator.partition(
                            parsed, request.items, layer, args.config.targetLang);
                    if (!partial.structural.isEmpty() || partial.accepted.size() <= shown) return;
                    shown = partial.accepted.size();
                    args.monitor.onStreamProgress(args.chunk.id, shown, totalRows);
                    args.monitor.onPartialRows(args.chunk.id, partial.accepted);
                } catch (AiProtocolException notYet) {
                    // Preamble only so far.
                }
            }

            @Override public void onReasoning(String reasoningSoFar) {
                args.monitor.onReasoning(args.chunk.id, attemptNumber, reasoningSoFar);
            }
        };
    }

    private static int uniqueRowCount(List<AiRequestItem> items) {
        if (items == null || items.isEmpty()) return 0;
        Set<String> rowIds = new HashSet<>();
        for (AiRequestItem item : items) {
            if (item != null) rowIds.add(AiContract.rowIdOf(item.id));
        }
        return rowIds.size();
    }

    /** One targeted re-ask's outcome: what the second answer added and what still failed. */
    private static final class TargetedReAsk {
        final List<AiResponseItem> acceptedRows;
        final List<AiResponseValidator.RowFailure> stillFailed;
        final long accountedTokens;

        TargetedReAsk(List<AiResponseItem> acceptedRows,
                      List<AiResponseValidator.RowFailure> stillFailed, long accountedTokens) {
            this.acceptedRows = acceptedRows;
            this.stillFailed = stillFailed;
            this.accountedTokens = accountedTokens;
        }
    }

    /**
     * Re-asks exactly the rows that failed shape rules, once.
     *
     * <p>The first answer's good rows are already paid for and valid; throwing them away because a
     * different row came back malformed would bill twice for one chunk. The re-ask is a served
     * call like any other: it spends an attempt from the shared budget, carries the repair
     * instruction (the prior response did violate the contract — for these rows), and its usage is
     * accounted whatever it returns. Rows that fail again fall back to the baseline layer rather
     * than failing the chunk.
     */
    private static TargetedReAsk targetedReAsk(Args args,
                                               List<AiResponseValidator.RowFailure> failed,
                                               int maxOutputTokens, long accountedTokens,
                                               AiChunkRecord record,
                                               List<AiResponseItem> acceptedRows) {
        Set<String> failedRowIds = new HashSet<>();
        for (AiResponseValidator.RowFailure failure : failed) failedRowIds.add(failure.rowId);
        List<AiRequestItem> reAskItems = new ArrayList<>();
        for (AiRequestItem item : args.chunk.items) {
            if (failedRowIds.contains(AiContract.rowIdOf(item.id))) reAskItems.add(item);
        }

        if (reAskItems.isEmpty()) {
            return new TargetedReAsk(acceptedRows, failed, accountedTokens);
        }

        record.attempts++;
        AiProviderRequest reAskRequest = new AiProviderRequest(args.chunk.context,
                args.config.targetLang, args.chunk.instructions, reAskItems);
        AiProviderConfig reAskConfig = callConfig(args, reAskRequest, true, maxOutputTokens);
        int estimatedCallInput = Math.max(args.chunk.estimatedInputTokens,
                AiChunkPlanner.ceilHalf(AiText.utf8Bytes(reAskConfig.systemPrompt())
                        + AiText.utf8Bytes(reAskRequest.toJson())));
        long reservation = (long) estimatedCallInput + maxOutputTokens;

        AiProviderResult retry;
        try {
            retry = callOnce(args, reAskRequest, reAskConfig, record.attempts);
        } catch (Throwable dispatchedAgain) {
            // Unknown delivery on the re-ask: charge it conservatively and let the good rows stand.
            accountedTokens += reservation;
            estimateUsage(record, estimatedCallInput, maxOutputTokens);
            return new TargetedReAsk(acceptedRows, failed, accountedTokens);
        }

        if (retry == null) {
            accountedTokens += reservation;
            estimateUsage(record, estimatedCallInput, maxOutputTokens);
            return new TargetedReAsk(acceptedRows, failed, accountedTokens);
        }

        int input = retry.usage.input == null ? estimatedCallInput
                : retry.usage.input;
        int output = retry.usage.output == null ? maxOutputTokens : retry.usage.output;
        accountedTokens += (long) input + output;
        record.inputTokens += input;
        record.outputTokens += output;
        record.usageEstimated |= !retry.usage.isComplete();

        if (!retry.ok || retry.finish != AiFinishReason.STOP) {
            // The provider refused, truncated, or rate-limited the re-ask. The original rows stay;
            // the failed ones fall back rather than turning into a chunk failure now that money
            // has been spent on an answer half-held.
            return new TargetedReAsk(acceptedRows, failed, accountedTokens);
        }

        try {
            List<Object> raw = AiTextWire.readItems(retry.rawText, reAskItems,
                    args.config.layer);
            AiResponseValidator.Partition second = AiResponseValidator.partition(raw,
                    reAskItems, args.config.layer, args.config.targetLang);
            if (!second.structural.isEmpty()) {
                // The subset answer broke id integrity: none of its rows are trustworthy.
                return new TargetedReAsk(acceptedRows, failed, accountedTokens);
            }
            List<AiResponseItem> merged = new ArrayList<>(acceptedRows);
            merged.addAll(second.accepted);
            return new TargetedReAsk(merged, second.failed, accountedTokens);
        } catch (AiProtocolException unreadable) {
            return new TargetedReAsk(acceptedRows, failed, accountedTokens);
        }
    }

    private static List<String> rowIdsOf(List<AiResponseValidator.RowFailure> failures) {
        List<String> ids = new ArrayList<>(failures.size());
        for (AiResponseValidator.RowFailure failure : failures) ids.add(failure.rowId);
        return ids;
    }

    /** The repair prompt never makes a fitting request cross the transport or model input bound. */
    private static AiProviderConfig callConfig(Args args, AiProviderRequest request, boolean repair,
                                                int outputTokens) {
        AiProviderConfig config = args.config.forCall(repair, outputTokens);
        int bytes = AiText.utf8Bytes(config.systemPrompt()) + AiText.utf8Bytes(request.toJson());
        if (bytes > AiContract.MAX_REQUEST_BYTES
                || (config.model != null && AiChunkPlanner.ceilHalf(bytes) > config.model.inputTokenLimit)) {
            return args.config.forCall(false, outputTokens);
        }
        return config;
    }

    /**
     * Charges the deliberately conservative estimate: this chunk's estimated input plus the full
     * configured output cap. It overstates a short answer on purpose — the alternative is a run
     * that spent real money and reports nothing.
     */
    private static void estimateUsage(AiChunkRecord record, int inputTokens,
                                      int maxOutputTokens) {
        record.usageEstimated = true;
        record.inputTokens += inputTokens;
        record.outputTokens += maxOutputTokens;
    }

    private static AiChunkExecution fail(AiChunkRecord record, AiChunkFailure failure,
                                         long accountedTokens) {
        record.status = AiChunkRecord.Status.FAILED;
        record.failure = failure;
        return AiChunkExecution.rejected(failure, record, accountedTokens);
    }

    /**
     * True when the provider answered with a refusal that precedes generation: a key, quota,
     * model, request, or busy rejection. Such a call bills nothing, so charging the full output
     * cap for it (1.71M phantom tokens on X14 by 2026-11-18) only distorts the record.
     */
    static boolean declinedBeforeGeneration(AiProviderFailure failure) {
        if (failure == null) return false;
        switch (failure.kind) {
            case AUTH:
            case QUOTA:
            case MODEL_UNAVAILABLE:
            case REQUEST_REJECTED:
            case RATE_LIMITED:
                return true;
            default:
                return false;
        }
    }

    private static AiChunkFailure mapFailure(AiProviderFailure failure) {
        switch (failure.kind) {
            case AUTH:
                return new AiChunkFailure(AiFailureReason.AUTH_REJECTED, failure.status,
                        failure.detail);
            case QUOTA:
                return new AiChunkFailure(AiFailureReason.QUOTA_EXHAUSTED, failure.status,
                        failure.detail);
            case RATE_LIMITED:
                return new AiChunkFailure(AiFailureReason.RATE_LIMITED, failure.status,
                        failure.detail);
            case DELIVERY_UNKNOWN:
                return new AiChunkFailure(AiFailureReason.DELIVERY_UNKNOWN, failure.status,
                        failure.detail.isEmpty() && failure.status == 0
                                ? causeToken(failure.cause) : failure.detail);
            case REQUEST_REJECTED:
                return new AiChunkFailure(AiFailureReason.REQUEST_REJECTED, failure.status,
                        failure.detail);
            case OVERSIZED: return AiChunkFailure.of(AiFailureReason.OVERSIZED);
            case MODEL_UNAVAILABLE:
                return new AiChunkFailure(AiFailureReason.MODEL_UNAVAILABLE, failure.status,
                        failure.detail);
            default:
                return new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0, failure.detail);
        }
    }

    /** Status-0 delivery failures keep why the transfer ended: a deadline or a network error. */
    private static String causeToken(AiProviderFailure.Cause cause) {
        if (cause == AiProviderFailure.Cause.TIMEOUT) return "timeout";
        if (cause == AiProviderFailure.Cause.NETWORK) return "network";
        return "";
    }

    private static synchronized TimerTask scheduleDeadline(final AiSignal callSignal, long deadlineMs) {
        if (deadlineMs <= 0L) return null;
        if (deadlineTimer == null) deadlineTimer = new Timer("ai-call-deadline", true);
        TimerTask task = new TimerTask() {
            @Override public void run() {
                callSignal.abort("timeout");
            }
        };
        deadlineTimer.schedule(task, deadlineMs);
        return task;
    }
}
