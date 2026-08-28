package com.eza.spicyex.lyrics.ai;

/** Typed result of one explicit request to reuse or generate an AI-derived lyric layer. */
public enum AiRequestStartResult {
    STARTED("started"),
    INVALID_CONTEXT("invalid_context"),
    NOT_CONFIGURED("not_configured"),
    ALREADY_IN_FLIGHT("already_in_flight"),
    NOTHING_TO_DO("nothing_to_do");

    public final String token;

    AiRequestStartResult(String token) {
        this.token = token;
    }

    public boolean started() {
        return this == STARTED;
    }
}
