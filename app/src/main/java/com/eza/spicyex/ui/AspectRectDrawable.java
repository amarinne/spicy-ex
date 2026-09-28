package com.eza.spicyex.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A rounded rectangle outline of a given width : height, as large as fits the bounds - a preview
 * of a window shape (the picture-in-picture shape option).
 */
public final class AspectRectDrawable extends Drawable {
    private final float aspect;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** @param aspect width / height */
    public AspectRectDrawable(float aspect, int color, float density) {
        this.aspect = aspect > 0f ? aspect : 1f;
        this.density = density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density);
        paint.setColor(color);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float inset = paint.getStrokeWidth() / 2f + density;
        float maxW = b.width() - inset * 2f;
        float maxH = b.height() - inset * 2f;
        if (maxW <= 0f || maxH <= 0f) return;
        float w = maxW;
        float h = w / aspect;
        if (h > maxH) {
            h = maxH;
            w = h * aspect;
        }
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        float radius = 2.5f * density;
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
