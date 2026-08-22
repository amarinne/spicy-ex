package com.eza.spicyex.lyrics.ai;

import java.util.List;
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
        public long deadlineMs = AiContract.CALL_DEADLINE_MS;
    }

    private static Timer deadlineTimer;

    private AiChunkRuntime() {
    }

    public static AiChunkExecution executeChunk(Args args) {
        AiWait wait = args.wait == null ? AiWait.DEFAULT : args.wait;
        if (args.previous != null && args.previous.isComplete()) {
            throw new IllegalStateException("completed chunk must not be resent");
        }
        AiChunkRecord record = args.previous == null
                ? AiChunkRecord.forChunk(args.chunk) : args.previous.copy();
        long accountedTokens = 0L;

        while (record.attempts < AiContract.MAX_ATTEMPTS) {
            int maxOutputTokens = args.config.callOutputTokens();
            long reservation = (long) args.chunk.estimatedInputTokens + maxOutputTokens;
            if (args.signal != null) args.signal.throwIfAborted();
            record.attempts++;

            AiProviderConfig callConfig = args.config.forCall(record.repairs > 0, maxOutputTokens);
            AiProviderRequest request = new AiProviderRequest(args.chunk.context,
                    args.config.targetLang, args.chunk.instructions, args.chunk.items);
            if (args.monitor != null) {
                args.monitor.onAttempt(args.chunk.id, record.attempts,
                        args.provider.monitorPayload(request, callConfig));
            }

            AiProviderResult result;
            AiSignal callSignal = new AiSignal();
            AiSignal.Registration linked = AiSignal.link(args.signal, callSignal);
            TimerTask deadline = scheduleDeadline(callSignal, args.deadlineMs);
            try {
                result = args.provider.generateChunk(request, callConfig, callSignal);
            } catch (Throwable dispatched) {
                // The call left; whether it was served and billed is not knowable from here.
                accountedTokens += reservation;
                estimateUsage(record, args.chunk, maxOutputTokens);
                return fail(record, AiChunkFailure.of(AiFailureReason.DELIVERY_UNKNOWN),
                        accountedTokens);
            } finally {
                if (deadline != null) deadline.cancel();
                linked.remove();
            }

            if (result == null || !result.ok) {
                AiProviderFailure failure = result == null
                        ? AiProviderFailure.protocol("no_result") : result.failure;

                if (failure.kind == AiProviderFailure.Kind.PROTOCOL
                        && record.attempts < AiContract.MAX_ATTEMPTS) {
                    accountedTokens += reservation;
                    estimateUsage(record, args.chunk, maxOutputTokens);
                    record.repairs++;
                    continue;
                }
                if (failure.kind == AiProviderFailure.Kind.RATE_LIMITED
                        && record.attempts < AiContract.MAX_ATTEMPTS) {
                    accountedTokens += reservation;
                    estimateUsage(record, args.chunk, maxOutputTokens);
                    try {
                        wait.await(retryAfterMs(failure), args.signal);
                    } catch (RuntimeException cancelled) {
                        // The attempt was still charged; report it rather than losing it.
                        return fail(record, AiChunkFailure.of(AiFailureReason.RATE_LIMITED),
                                accountedTokens);
                    }
                    continue;
                }
                accountedTokens += reservation;
                estimateUsage(record, args.chunk, maxOutputTokens);
                return fail(record, mapFailure(failure), accountedTokens);
            }

            int input = result.usage.input == null
                    ? args.chunk.estimatedInputTokens : result.usage.input;
            int output = result.usage.output == null ? maxOutputTokens : result.usage.output;
            accountedTokens += (long) input + output;
            record.inputTokens += input;
            record.outputTokens += output;
            record.usageEstimated |= !result.usage.isComplete();

            if (result.finish == AiFinishReason.LENGTH) {
                return fail(record, AiChunkFailure.of(AiFailureReason.TRUNCATED), accountedTokens);
            }
            if (result.finish != AiFinishReason.STOP) {
                return fail(record, AiChunkFailure.of(AiFailureReason.PROVIDER_REFUSED),
                        accountedTokens);
            }

            try {
                List<Object> raw = AiResponseReader.readItems(result.rawText);
                List<AiResponseItem> items = AiResponseValidator.validate(raw, args.chunk.items,
                        args.config.layer, args.config.targetLang);
                record.status = AiChunkRecord.Status.COMPLETE;
                record.failure = null;
                return AiChunkExecution.accepted(items, record, accountedTokens);
            } catch (AiProtocolException invalid) {
                if (record.attempts < AiContract.MAX_ATTEMPTS) {
                    record.repairs++;
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
     * Charges the deliberately conservative estimate: this chunk's estimated input plus the full
     * configured output cap. It overstates a short answer on purpose — the alternative is a run
     * that spent real money and reports nothing.
     */
    private static void estimateUsage(AiChunkRecord record, AiPlannedChunk chunk,
                                      int maxOutputTokens) {
        record.usageEstimated = true;
        record.inputTokens += chunk.estimatedInputTokens;
        record.outputTokens += maxOutputTokens;
    }

    private static AiChunkExecution fail(AiChunkRecord record, AiChunkFailure failure,
                                         long accountedTokens) {
        record.status = AiChunkRecord.Status.FAILED;
        record.failure = failure;
        return AiChunkExecution.rejected(failure, record, accountedTokens);
    }

    private static AiChunkFailure mapFailure(AiProviderFailure failure) {
        switch (failure.kind) {
            case AUTH: return AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
            case QUOTA: return AiChunkFailure.of(AiFailureReason.QUOTA_EXHAUSTED);
            case RATE_LIMITED: return AiChunkFailure.of(AiFailureReason.RATE_LIMITED);
            case DELIVERY_UNKNOWN:
                return new AiChunkFailure(AiFailureReason.DELIVERY_UNKNOWN, failure.status, "");
            case REQUEST_REJECTED:
                return new AiChunkFailure(AiFailureReason.REQUEST_REJECTED, failure.status, "");
            case OVERSIZED: return AiChunkFailure.of(AiFailureReason.OVERSIZED);
            case MODEL_UNAVAILABLE: return AiChunkFailure.of(AiFailureReason.MODEL_UNAVAILABLE);
            default:
                return new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0, failure.detail);
        }
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
