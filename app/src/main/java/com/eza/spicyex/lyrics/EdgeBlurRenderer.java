package com.eza.spicyex.lyrics;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.RenderNode;

/** Hardware-only surfaces for the top and bottom blur bands, sized for one band geometry. */
@TargetApi(33)
final class EdgeBlurRenderer {
    interface Content { void draw(Canvas canvas); }

    /** Readout text sits over this; the pyramid's coarsest level caps the radius at 48px. */
    static final float MAX_RADIUS_DP = 14f;

    private final int width, height;
    private final int[] lengths;
    private final float radius;
    private final RenderNode[] sources = new RenderNode[2];
    private final EdgeBlurPyramid[] pyramids = new EdgeBlurPyramid[2];

    EdgeBlurRenderer(int w, int h, int top, int bottom, float density) {
        width = w;
        height = h;
        lengths = new int[]{top, bottom};
        radius = MAX_RADIUS_DP * density;
        // Overfetch past the band so the blur near its inner boundary samples real content.
        int padding = (int) Math.ceil(radius * 3f) + 2;
        for (int edge = 0; edge < 2; edge++) {
            if (lengths[edge] <= 0) continue;
            int captured = Math.min(h, lengths[edge] + padding);
            sources[edge] = new RenderNode("Lyric edge source");
            sources[edge].setPosition(0, 0, width, captured);
            pyramids[edge] = new EdgeBlurPyramid(width, captured, lengths[edge]);
        }
    }

    boolean fits(int w, int h, int top, int bottom) {
        return w == width && h == height && top == lengths[0] && bottom == lengths[1];
    }

    /** Draws each band over the visible area; scale fades the radius in or out with its dock. */
    void draw(Canvas canvas, int scrollY, float topScale, float bottomScale, Content content) {
        for (int edge = 0; edge < 2; edge++) {
            if (pyramids[edge] == null) continue;
            Canvas source = sources[edge].beginRecording();
            if (edge == 1) {
                // Mirror the bottom band so both pyramids treat y = 0 as the outer edge.
                source.translate(0, height);
                source.scale(1, -1);
            }
            source.translate(0, -scrollY);
            content.draw(source);
            sources[edge].endRecording();
            int save = canvas.save();
            canvas.translate(0, scrollY + (edge == 0 ? 0 : height));
            if (edge == 1) canvas.scale(1, -1);
            canvas.clipRect(0, 0, width, lengths[edge]);
            pyramids[edge].draw(canvas, sources[edge], radius * (edge == 0 ? topScale : bottomScale));
            canvas.restoreToCount(save);
        }
    }

    void release() {
        for (int edge = 0; edge < 2; edge++) {
            if (sources[edge] != null) sources[edge].discardDisplayList();
            if (pyramids[edge] != null) pyramids[edge].release();
        }
    }
}
