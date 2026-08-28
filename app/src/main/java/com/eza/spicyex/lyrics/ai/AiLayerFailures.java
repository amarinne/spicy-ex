package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerFailure;

import java.util.Locale;

/**
 * Translates an AI failure into the lane-level classification the session already speaks.
 *
 * <p>The mapping is deliberately lossy in one direction only. {@link LayerFailure.Reason} is a
 * coarse vocabulary shared by every layer, so several AI reasons collapse onto one of its values —
 * but the precise reason always survives in {@link LayerFailure#detail} as its own token. Anything
 * that decides whether to retry, what to charge, or what to tell the owner must read that token,
 * never the coarse reason: {@code delivery_unknown} and {@code truncated} both look like ordinary
 * failures from the lane's side, and only one of them may have been billed.
 */
public final class AiLayerFailures {

    private AiLayerFailures() {
    }

    /** @return a lane failure carrying the coarse reason, the exact AI token, and any HTTP status */
    public static LayerFailure toLayerFailure(AiChunkFailure failure) {
        if (failure == null || failure.reason == null) return LayerFailure.NONE;
        return new LayerFailure(reasonOf(failure.reason), token(failure.reason), failure.status);
    }

    /** The AI reason as a stable lowercase token, safe to log and to put in a diagnostic report. */
    public static String token(AiFailureReason reason) {
        return reason == null ? "" : reason.name().toLowerCase(Locale.ROOT);
    }

    private static LayerFailure.Reason reasonOf(AiFailureReason reason) {
        switch (reason) {
            case RATE_LIMITED:
                return LayerFailure.Reason.RATE_LIMITED;

            // The response arrived and could not be used. Both are re-askable in principle; the
            // two-attempt cap has already decided whether asking again was worth it.
            case PROTOCOL_INVALID:
            case TRUNCATED:
                return LayerFailure.Reason.MALFORMED;

            // The provider answered, and its answer was no.
            case AUTH_REJECTED:
            case QUOTA_EXHAUSTED:
            case REQUEST_REJECTED:
                return LayerFailure.Reason.CLIENT_ERROR;

            // Dispatched, outcome unknown, possibly billed. TIMEOUT is the closest coarse value:
            // we stopped waiting without learning what happened. Only the token distinguishes this
            // from a clean failure, and only an explicit warned retry may follow it.
            case DELIVERY_UNKNOWN:
                return LayerFailure.Reason.TIMEOUT;

            // Nothing ran, or nothing could. No call was made and nothing was billed.
            case NO_CREDENTIAL:
            case BASELINE_UNAVAILABLE:
            case MODEL_UNAVAILABLE:
            case PROVIDER_REFUSED:
            case STORAGE_FULL:
            case STORAGE_UNAVAILABLE:
            case OVERSIZED:
            default:
                return LayerFailure.Reason.UNAVAILABLE;
        }
    }

    /**
     * True when the failure leaves it genuinely unknown whether the owner was charged.
     *
     * <p>The one case where a retry must be an explicit, warned, owner-authorized action rather
     * than anything automatic.
     */
    public static boolean mayHaveBilled(AiFailureReason reason) {
        return reason == AiFailureReason.DELIVERY_UNKNOWN;
    }
}
