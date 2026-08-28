package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerFailure;

/**
 * How one run ended, in the terms a caller has to act on.
 *
 * <p>The distinctions here are the ones that decide what the owner sees and whether anything was
 * charged. {@link Kind#REUSED} and {@link Kind#NOTHING_TO_DO} cost nothing.
 * {@link Kind#CANCELLED} and {@link Kind#FAILED} may still have cost something, which is why both
 * carry the record: the chunks that completed before the run stopped are paid for, stored, and
 * resumable.
 */
public final class AiRunOutcome {

    public enum Kind {
        /** Every chunk validated in this run. The only kind that may be displayed as finished. */
        COMPLETED,
        /** An identical, complete, stored answer already existed. No provider call was made. */
        REUSED,
        /** The document had nothing to send. No call, no record, no cost. */
        NOTHING_TO_DO,
        /** Stopped part-way. Completed chunks are stored; a later run resumes from them. */
        CANCELLED,
        /** A chunk failed terminally. Earlier chunks remain stored and resumable. */
        FAILED
    }

    public final Kind kind;
    public final AiPaidRecord record;
    public final LayerFailure failure;
    /** The exact AI reason token, or empty. Retry policy reads this, not {@link #failure}. */
    public final String failureToken;
    /** Privacy-safe validation/provider rule, optionally followed by an opaque canonical row ID. */
    public final String failureDetail;
    /** False when durable accounting failed. Such output is never exposed as completed. */
    public final boolean durable;
    /** Cancellation reason token, or empty. */
    public final String cancelReason;

    private AiRunOutcome(Kind kind, AiPaidRecord record, LayerFailure failure, String failureToken,
                         String failureDetail, boolean durable, String cancelReason) {
        this.kind = kind;
        this.record = record;
        this.failure = failure == null ? LayerFailure.NONE : failure;
        this.failureToken = AiText.nz(failureToken);
        this.failureDetail = AiText.nz(failureDetail);
        this.durable = durable;
        this.cancelReason = AiText.nz(cancelReason);
    }

    static AiRunOutcome completed(AiPaidRecord record, boolean durable) {
        return new AiRunOutcome(Kind.COMPLETED, record, null, "", "", durable, "");
    }

    static AiRunOutcome reused(AiPaidRecord record) {
        return new AiRunOutcome(Kind.REUSED, record, null, "", "", true, "");
    }

    static AiRunOutcome nothingToDo() {
        return new AiRunOutcome(Kind.NOTHING_TO_DO, null, null, "", "", true, "");
    }

    static AiRunOutcome cancelled(AiPaidRecord record, boolean durable, String reason) {
        return new AiRunOutcome(Kind.CANCELLED, record, null, "", "", durable, reason);
    }

    static AiRunOutcome failed(AiPaidRecord record, AiChunkFailure failure, boolean durable) {
        return new AiRunOutcome(Kind.FAILED, record, AiLayerFailures.toLayerFailure(failure),
                failure == null ? "" : AiLayerFailures.token(failure.reason),
                failure == null ? "" : failure.detail, durable, "");
    }

    /** True when output was produced or recovered and is safe to display. */
    public boolean hasOutput() {
        return (kind == Kind.COMPLETED || kind == Kind.REUSED) && record != null;
    }

    /** True when the owner may have been charged for an outcome we cannot confirm. */
    public boolean mayHaveBilledUnconfirmed() {
        return kind == Kind.FAILED
                && AiFailureReason.DELIVERY_UNKNOWN.name().toLowerCase(java.util.Locale.ROOT)
                .equals(failureToken);
    }
}
