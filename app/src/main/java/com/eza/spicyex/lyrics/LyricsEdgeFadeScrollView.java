package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.widget.ScrollView;

/** ScrollView with desktop-style content edge fade, independent of platform fading-edge quirks. */
public final class LyricsEdgeFadeScrollView extends ScrollView {
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fadePx;

    public LyricsEdgeFadeScrollView(Context context) {
        super(context);
        float density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        fadePx = Math.round(34f * density);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || fadePx <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        int fade = Math.min(fadePx, h / 3);
        maskPaint.setShader(new LinearGradient(0, 0, 0, fade,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, fade, maskPaint);
        maskPaint.setShader(new LinearGradient(0, h - fade, 0, h,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h - fade, w, h, maskPaint);
        maskPaint.setShader(null);
        canvas.restoreToCount(save);
    }
}
