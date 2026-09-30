package com.eza.spicyex.sharecard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.List;

/** One of the row's text views and where its text sits in the line. */
final class SourceRun {
    final TextView view;
    final int quoteStart;
    final int viewStart;
    final int length;

    SourceRun(TextView view, int quoteStart, int viewStart, int length) {
        this.view = view;
        this.quoteStart = quoteStart;
        this.viewStart = viewStart;
        this.length = length;
    }
}

/** A word in flight: its look in the list and on the card, and both places. */
final class FlyingWord {
    int order;
    Bitmap source;
    float sourceSize, sourceOriginX, sourceBaseline;
    float fromX, fromBaseline, fromScreenSize;
    Bitmap card;
    float cardSize, cardOriginX, cardBaseline;
    float toX, toBaseline, toScreenSize;
}

/** Draws every flying word; one view for all of them, redrawn per frame. */
final class FlightView extends View {
    static final long FLIGHT_MS = 640L;
    private final List<FlyingWord> words;
    private final float hop;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final PathInterpolator along = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    private final PathInterpolator down = new PathInterpolator(0.45f, 0f, 0.15f, 1f);
    long stagger;
    private float elapsed;

    FlightView(Context context, List<FlyingWord> words, float hop) {
        super(context);
        this.words = words;
        this.hop = hop;
    }

    void setElapsed(float elapsed) {
        this.elapsed = elapsed;
        invalidate();
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < words.size(); i++) {
            FlyingWord w = words.get(i);
            float t = Math.max(0f, Math.min(1f, (elapsed - w.order * stagger) / FLIGHT_MS));
            float ex = along.getInterpolation(t);
            float ey = down.getInterpolation(t);
            float x = w.fromX + (w.toX - w.fromX) * ex;
            // x leads and y follows, with a small lift mid-way: a gentle arc.
            float baseline = w.fromBaseline + (w.toBaseline - w.fromBaseline) * ey
                    - hop * (float) Math.sin(Math.PI * t);
            float size = w.fromScreenSize + (w.toScreenSize - w.fromScreenSize) * ex;
            float cardAlpha = smooth(0.12f, 0.7f, t);
            if (cardAlpha < 1f) draw(canvas, w.source, w.sourceOriginX, w.sourceBaseline,
                    size / w.sourceSize, x, baseline, 1f - smooth(0.2f, 0.8f, t));
            if (cardAlpha > 0f) draw(canvas, w.card, w.cardOriginX, w.cardBaseline,
                    size / w.cardSize, x, baseline, cardAlpha);
        }
    }

    private void draw(Canvas canvas, Bitmap bitmap, float originX, float originBaseline, float scale,
                      float x, float baseline, float alpha) {
        if (alpha <= 0f) return;
        float left = x - originX * scale;
        float top = baseline - originBaseline * scale;
        rect.set(left, top, left + bitmap.getWidth() * scale, top + bitmap.getHeight() * scale);
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(bitmap, null, rect, paint);
    }
}
