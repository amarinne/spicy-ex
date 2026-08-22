package com.eza.spicyex.lyrics.ai;

/**
 * A layout-only hint about which voice a row belongs to.
 *
 * <p>It exists so wording stays continuous across a duet or a background line. It is evidence
 * about page layout, not about people: nothing downstream may read a singer's identity, gender, or
 * relationships out of it, and the prompt says so explicitly. A row with no role evidence carries
 * no hint at all rather than a guessed one.
 */
public enum AiVoiceHint {
    PRIMARY("primary"),
    ALTERNATE("alternate"),
    BACKGROUND("background");

    public final String token;

    AiVoiceHint(String token) {
        this.token = token;
    }

    /** Null-safe token for wire and digest payloads, where an absent hint is explicit null. */
    public static String tokenOf(AiVoiceHint hint) {
        return hint == null ? null : hint.token;
    }
}
