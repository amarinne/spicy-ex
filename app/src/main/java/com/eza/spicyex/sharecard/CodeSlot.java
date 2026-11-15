package com.eza.spicyex.sharecard;

import android.graphics.Bitmap;
import android.graphics.RectF;

/** Where a design puts the Spotify Code: its right edge, vertical centre and width. */
final class CodeSlot {
    final float right, centreY, width;
    final boolean paper;

    CodeSlot(float right, float centreY, float width, boolean paper) {
        this.right = right;
        this.centreY = centreY;
        this.width = width;
        this.paper = paper;
    }

    RectF frame(Bitmap code) {
        float height = width * code.getHeight() / Math.max(1f, code.getWidth());
        return new RectF(right - width, centreY - height / 2f, right, centreY + height / 2f);
    }
}
