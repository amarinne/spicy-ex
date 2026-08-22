package com.eza.spicyex.lyrics.ai;

/** Why a chunk did not produce accepted output. One token per distinct thing to tell the owner. */
public enum AiFailureReason {
    NO_CREDENTIAL,
    BASELINE_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    AUTH_REJECTED,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    /** Dispatched, outcome unknown, possibly billed. Only an explicit warned retry may follow. */
    DELIVERY_UNKNOWN,
    PROTOCOL_INVALID,
    REQUEST_REJECTED,
    /** The provider declined. Read from its finish state, never guessed from the text. */
    PROVIDER_REFUSED,
    /** Output hit the cap mid-document. */
    TRUNCATED,
    OVERSIZED
}
