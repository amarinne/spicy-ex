package com.eza.spicyex.lyrics;

/** Projects one sentence's progress onto its visual lines in reading order. */
final class SentenceFill {
    private SentenceFill() { }

    static float lineGradient(float gradient, float totalWidth, float precedingWidth, float lineWidth) {
        float progress = (gradient - LyricAnimations.GRADIENT_UNSUNG) / LyricAnimations.GRADIENT_RANGE;
        float local = (Math.max(0f, Math.min(1f, progress)) * totalWidth - precedingWidth)
                / Math.max(1f, lineWidth);
        return LyricAnimations.gradientPosition(Math.max(0f, Math.min(1f, local)));
    }
}
