package com.eza.spicyex.lyrics.ai;

/**
 * Token usage as the provider reported it.
 *
 * <p>Either figure may be absent, and absent is not zero — a call that did not report its usage
 * still billed for it. The runtime substitutes a deliberately conservative estimate and marks the
 * record estimated, so a display can say "approximately" instead of quietly under-reporting spend.
 */
public final class AiUsage {
    public static final AiUsage UNREPORTED = new AiUsage(null, null);

    public final Integer input;
    public final Integer output;

    public AiUsage(Integer input, Integer output) {
        this.input = input;
        this.output = output;
    }

    public static AiUsage of(int input, int output) {
        return new AiUsage(input, output);
    }

    public boolean isComplete() {
        return input != null && output != null;
    }
}
