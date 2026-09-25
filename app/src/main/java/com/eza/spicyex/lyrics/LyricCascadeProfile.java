package com.eza.spicyex.lyrics;

/**
 * Per-row timing for the Apple-style row cascade: pure motion math, no views.
 *
 * <p>Every row in the wave starts displaced by exactly the scroll delta the ScrollView has already
 * carried the content by, so the column is visually untouched on the frame the wave starts, and
 * springs back to rest from there. Apple Music's lyric scroll is that and nothing more: one gently
 * damped spring shared by every line, with each line on the trailing side of the move released a
 * little after the one before it. The motion is carried by the stagger alone - rows never get a
 * launch kick or a per-row spring of their own, both of which were tried and read as the column
 * snapping forward, sagging backward first, or wobbling as separate pieces.
 *
 * <p>The stagger shrinks geometrically with distance, so the rows right behind the focused line
 * visibly peel off one after another while the far ones bunch up and follow as a group instead of
 * trailing the song by most of a second.
 */
public final class LyricCascadeProfile {
    /** Each further trailing row waits this fraction of the previous row's extra delay. */
    static final float STAGGER_DECAY = 0.9f;

    public final float delaySeconds;
    public final float frequencyHz;
    public final float damping;

    private LyricCascadeProfile(float delaySeconds, float frequencyHz, float damping) {
        this.delaySeconds = delaySeconds;
        this.frequencyHz = frequencyHz;
        this.damping = damping;
    }

    /**
     * @param trailingRows rows between this one and the focused line, measured in the direction the
     *                     wave travels: positive for rows the move leaves behind (below the focus
     *                     on a forward advance), zero or negative for the focus and every row on
     *                     the leading side, which all leave at once.
     */
    public static LyricCascadeProfile forRow(
            float trailingRows,
            float frequencyHz,
            float damping,
            float staggerSeconds,
            float maxDelaySeconds
    ) {
        float n = Math.max(0f, trailingRows);
        float stagger = Math.max(0f, staggerSeconds);
        float delay = stagger * (1f - (float) Math.pow(STAGGER_DECAY, n)) / (1f - STAGGER_DECAY);
        delay = Math.min(Math.max(0f, maxDelaySeconds), delay);
        return new LyricCascadeProfile(delay,
                Math.max(0.2f, frequencyHz),
                Math.min(1f, Math.max(0.1f, damping)));
    }
}
