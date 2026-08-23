package com.eza.spicyex.lyrics.ai;

/**
 * The persisted outcome of one structured-output probe, and the reasoning allowance derived from
 * it.
 *
 * <p>{@code /models} on the OpenAI wire carries no token limits and no capability flags, so the
 * probe is the only place per-model output behavior can actually be observed: a fixed fixture goes
 * out, and {@code usage.completion_tokens} comes back saying how much the model spent — including
 * thinking. That one number is stored per provider scope alongside the identity it was measured
 * against, survives process restarts, and replaces the contract's default headroom when the
 * selected provider/model matches. Nothing here is sensitive: no credential ever enters the
 * record, and the fixture contains no listener data.
 */
public final class AiProbeMeasurement {
    /**
     * Completion tokens the fixture's visible reply can plausibly cost. A probe that spent at most
     * this simply answered; the excess over this figure is what the model spent on reasoning.
     * Generous on purpose — under-crediting a reasoner shrinks its budget back toward truncation.
     */
    public static final int REPLY_TOKEN_ESTIMATE = 64;

    /** Field separator; newlines cannot occur in any field (tokens are machine-safe). */
    private static final char SEPARATOR = '\n';
    private static final String NO_VALUE = "-";

    private AiProbeMeasurement() {
    }

    /** The stored form: endpoint host, model name, measured tokens ({@code -} when unreported),
     * and the probe's failure token (empty when it passed). */
    public static String encode(String endpointHost, String model, Integer completionTokens,
                                String failureToken) {
        return AiText.nz(endpointHost) + SEPARATOR + AiText.nz(model) + SEPARATOR
                + (completionTokens == null ? NO_VALUE : String.valueOf(Math.max(0,
                completionTokens.intValue()))) + SEPARATOR + AiText.nz(failureToken);
    }

    /** A stored record, or null when absent, malformed, or measured against another identity. */
    public static Parsed decode(String stored, String currentEndpointHost, String currentModel) {
        if (stored == null || stored.isEmpty()) return null;
        String[] fields = stored.split(Character.toString(SEPARATOR), -1);
        if (fields.length != 4) return null;
        if (!AiText.nz(currentEndpointHost).equals(fields[0])
                || !AiText.nz(currentModel).equals(fields[1])) {
            return null;
        }
        Integer tokens = null;
        if (!NO_VALUE.equals(fields[2])) {
            try {
                int parsed = Integer.parseInt(fields[2]);
                if (parsed > 0) tokens = parsed;
            } catch (NumberFormatException malformed) {
                // An unreadable figure is no figure.
            }
        }
        return new Parsed(fields[0], fields[1], tokens, fields[3]);
    }

    /**
     * The reasoning allowance implied by one probe spend: whatever the model burned beyond the
     * reply estimate, clamped into the configured output range. A shallow spender gets zero —
     * evidence beats the default, in both directions.
     */
    public static int allowanceFrom(int measuredCompletionTokens) {
        int excess = Math.max(0, measuredCompletionTokens - REPLY_TOKEN_ESTIMATE);
        return Math.min(excess, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    /** One decoded probe record. */
    public static final class Parsed {
        public final String endpointHost;
        public final String model;
        /** Measured completion tokens, or null when that probe reported no usage. */
        public final Integer completionTokens;
        public final String failureToken;

        Parsed(String endpointHost, String model, Integer completionTokens, String failureToken) {
            this.endpointHost = endpointHost;
            this.model = model;
            this.completionTokens = completionTokens;
            this.failureToken = failureToken;
        }
    }
}
