package com.eza.spicyex.lyrics;

/**
 * The distance blur curve: how far a row is blurred by its distance from the active row.
 *
 * <p>The curve is pure, so the renderer and the migration coverage agree on what a stored
 * blur strength renders. Two families exist: the standard curve and the legacy soft curve the
 * retired "Slight" selector chose. They have different shapes, not just different magnitudes.
 */
public final class LyricsBlurCurve {
    private LyricsBlurCurve() {
    }

    /**
     * The blur radius in pixels for one row.
     *
     * @param distance how many rows away the row sits from the active one
     * @param emphasized whether the row is a dot line or a very short line (blurred less)
     * @param standard the standard distance curve; false is the legacy soft curve
     * @param apple the Apple-owned curve, which ramps differently
     * @param quality the quality multiplier over the curve
     */
    public static float blurPx(int distance, boolean emphasized, boolean standard, boolean apple,
            float quality) {
        if (apple) {
            // Apple only: let nearby rows dissolve into the ambient blur as focus advances. The
            // active row remains sharp; the first neighbour gets a restrained veil and the curve
            // grows smoothly with distance rather than switching on abruptly at row two.
            float max = standard ? (emphasized ? 7.0f : 10.0f) : (emphasized ? 3.2f : 4.8f);
            float curved = (float) Math.pow(Math.min(1f, distance / 3.2f), 0.72);
            return max * curved * quality;
        }
        if (standard) {
            float max = emphasized ? 5.0f : 8.0f;
            float curved = (float) Math.pow(Math.min(1f, distance / 4f), 0.75);
            return max * curved * quality;
        }
        if (distance <= 1) return 0f;
        float legacyMax = emphasized ? 1.0f : 1.8f;
        float weighted = legacyMax * Math.min(1f, distance / 4f);
        if (distance == 2) weighted *= 0.55f;
        return weighted * quality;
    }
}
