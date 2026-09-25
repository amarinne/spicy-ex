package com.eza.spicyex.lyrics;

/**
 * Apple Music's held-note emphasis, per letter. Pure motion math, no views.
 *
 * <p>Apple does not simply enlarge a held word. A wave travels through its letters: each letter in
 * turn swells, lifts and glows, then relaxes, and while swollen the letters push away from the
 * word's centre - so the word visibly distorts around the point being sung instead of zooming as
 * one block. This follows AMLL's reproduction of it (amll-dev/applemusic-like-lyrics,
 * {@code dom/animation/emphasize}): the same qualification rule, strength curve, bell easing,
 * per-letter stagger and offsets.
 */
public final class LyricEmphasis {
    /** A word must be held at least this long to be emphasised. */
    public static final long MIN_HOLD_MS = 1000L;
    /** Letters start one after another across this share of the word's hold. */
    private static final float LETTER_SPREAD = 1f / 2.5f;
    private static final float SCALE_PER_AMOUNT = 0.1f;
    private static final float PUSH_EM_PER_AMOUNT = 0.03f;
    private static final float LIFT_EM_PER_AMOUNT = 0.025f;
    private static final float FLOAT_EM = 0.05f;
    /** The float leads the swell by this much, so the letter is already rising when it grows. */
    private static final long FLOAT_LEAD_MS = 400L;
    private static final float FLOAT_DURATION_SCALE = 1.4f;

    private static final CubicBezier EASE_IN = new CubicBezier(0.2f, 0.4f, 0.58f, 1f);
    private static final CubicBezier EASE_OUT = new CubicBezier(0.3f, 0f, 0.58f, 1f);

    /** Strength of one emphasised word; null from {@link #forWord} means no emphasis. */
    public static final class Params {
        public final float amount;
        public final float glow;
        public final float durationMs;

        Params(float amount, float glow, float durationMs) {
            this.amount = amount;
            this.glow = glow;
            this.durationMs = durationMs;
        }
    }

    /** One letter's emphasis at one moment. All offsets are in em; negative y is up. */
    public static final class Frame {
        public float scale = 1f;
        public float xEm;
        public float yEm;
        public float glow;

        void reset() {
            scale = 1f;
            xEm = 0f;
            yEm = 0f;
            glow = 0f;
        }
    }

    private LyricEmphasis() {
    }

    /**
     * @param text     the word as displayed
     * @param lastWord the line's final word, which Apple gives a stronger, longer swell
     * @return null unless the word qualifies: held for {@link #MIN_HOLD_MS}, and for non-CJK text
     *         two to seven letters long (longer words would distort into illegibility).
     */
    public static Params forWord(String text, long startMs, long endMs, boolean lastWord) {
        long held = endMs - startMs;
        if (text == null || held < MIN_HOLD_MS) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        if (!isCjk(trimmed)) {
            int letters = trimmed.codePointCount(0, trimmed.length());
            if (letters <= 1 || letters > 7) return null;
        }
        float du = held;
        float amount = shape(du / 2000f) * 0.6f;
        float glow = shape(du / 3000f) * 0.5f;
        if (lastWord) {
            amount *= 1.6f;
            glow *= 1.5f;
            du *= 1.2f;
        }
        return new Params(Math.min(1.2f, amount), Math.min(0.8f, glow), du);
    }

    /**
     * Writes letter {@code index} of {@code count}'s emphasis at {@code elapsedMs} since the word
     * started into {@code out}. Continuous in time, 0 before the letter's turn and after it.
     */
    public static void letterFrame(Params params, int index, int count, float elapsedMs,
                                   boolean backgroundLine, Frame out) {
        out.reset();
        if (params == null || count <= 0) return;
        float delay = params.durationMs * LETTER_SPREAD / count * index;
        float e = easing(clamp01((elapsedMs - delay) / params.durationMs));
        out.scale = 1f + e * SCALE_PER_AMOUNT * params.amount;
        out.xEm = -e * PUSH_EM_PER_AMOUNT * params.amount * (count / 2f - index);
        out.yEm = -e * LIFT_EM_PER_AMOUNT * params.amount;
        out.glow = e * params.glow;
        float floatT = clamp01((elapsedMs - (delay - FLOAT_LEAD_MS))
                / (params.durationMs * FLOAT_DURATION_SCALE));
        float lift = (float) Math.sin(floatT * Math.PI) * FLOAT_EM;
        out.yEm -= backgroundLine ? lift * 2f : lift;
    }

    /** Rise to a peak at the midpoint, then relax back to rest. */
    static float easing(float x) {
        if (x < 0.5f) return EASE_IN.solve(x / 0.5f);
        return 1f - EASE_OUT.solve((x - 0.5f) / 0.5f);
    }

    /** Short holds barely register (cubic); long ones keep growing, but slowly (square root). */
    private static float shape(float ratio) {
        return ratio > 1f ? (float) Math.sqrt(ratio) : ratio * ratio * ratio;
    }

    private static boolean isCjk(String text) {
        return SpicyTextDetection.hasCjkIdeograph(text)
                || SpicyTextDetection.hasKana(text)
                || SpicyTextDetection.itemKoreanTest(text);
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) timing function. */
    static final class CubicBezier {
        private final float cx, bx, ax, cy, by, ay;

        CubicBezier(float x1, float y1, float x2, float y2) {
            cx = 3f * x1;
            bx = 3f * (x2 - x1) - cx;
            ax = 1f - cx - bx;
            cy = 3f * y1;
            by = 3f * (y2 - y1) - cy;
            ay = 1f - cy - by;
        }

        float solve(float x) {
            if (x <= 0f) return 0f;
            if (x >= 1f) return 1f;
            float t = x;
            for (int i = 0; i < 8; i++) {
                float error = ((ax * t + bx) * t + cx) * t - x;
                if (Math.abs(error) < 1e-5f) break;
                float slope = (3f * ax * t + 2f * bx) * t + cx;
                if (Math.abs(slope) < 1e-6f) break;
                t -= error / slope;
            }
            t = clamp01(t);
            return ((ay * t + by) * t + cy) * t;
        }
    }
}
