package com.eza.spicyex.sharecard;

import android.graphics.Bitmap;

import java.util.List;

/** One lyric line (and its translation) as set on the card: its image and where it sits. */
final class Piece {
    final int id;
    final Bitmap bitmap;
    final float x;
    final float y;
    final float size;
    /** Each wrapped line in the image (the lyric's, then its translation's): left, right,
     *  top, bottom - so a re-alignment can slide every line on its own. */
    final float[] lineLeft, lineRight, lineTop, lineBottom;

    Piece(int id, Bitmap bitmap, float x, float y, float size, List<float[]> lines) {
        this.id = id;
        this.bitmap = bitmap;
        this.x = x;
        this.y = y;
        this.size = size;
        int n = lines.size();
        lineLeft = new float[n];
        lineRight = new float[n];
        lineTop = new float[n];
        lineBottom = new float[n];
        for (int i = 0; i < n; i++) {
            float[] line = lines.get(i);
            lineLeft[i] = line[0];
            lineRight[i] = line[1];
            lineTop[i] = line[2];
            lineBottom[i] = line[3];
        }
    }
}
