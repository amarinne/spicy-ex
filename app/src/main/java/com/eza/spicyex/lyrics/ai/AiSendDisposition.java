package com.eza.spicyex.lyrics.ai;

/** Whether an enumerated row travels to the provider, and if not, why not. */
public enum AiSendDisposition {
    SENT("sent"),
    /** Restored locally, byte-exact, and never trusted to the model. */
    STRUCTURAL("structural"),
    SKIPPED("skipped");

    public final String token;

    AiSendDisposition(String token) {
        this.token = token;
    }
}
