package com.eza.spicyex.sharecard;

import android.graphics.Bitmap;

/**
 * Code lines for rendering: the monochrome ink bitmap (white on transparent, or black if the
 * card multiplies it), the logo's box, and each bar's column and extent.
 */
final class CodeArt {
    final Bitmap ink;
    /** In code pixels; null when the image could not be read as logo + bars. */
    final android.graphics.Rect logo;
    final float[] barLeft, barRight, barTop, barBottom;
    final float midY, tallest;
    final int color;

    CodeArt(Bitmap ink, android.graphics.Rect logo, float[] barLeft, float[] barRight,
            float[] barTop, float[] barBottom, int color) {
        this.ink = ink;
        this.logo = logo;
        this.barLeft = barLeft;
        this.barRight = barRight;
        this.barTop = barTop;
        this.barBottom = barBottom;
        this.color = color;
        this.midY = logo == null ? ink.getHeight() / 2f : logo.exactCenterY();
        float max = 0f;
        for (int i = 0; i < barTop.length; i++) max = Math.max(max, barBottom[i] - barTop[i]);
        this.tallest = max;
    }
}
