package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Static blurred-cover ambient fallback for devices without AGSL RuntimeShader support. */
public final class StaticBlurCoverBackgroundView extends FrameLayout implements AmbientBackgroundLayer {
    private static final int SOFT_COVER_PX = 96;
    private ImageView current;
    private ImageView previous;

    public StaticBlurCoverBackgroundView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(18, 16, 18));
    }

    @Override
    public View asView() {
        return this;
    }

    @Override
    public void updateImage(Bitmap art) {
        if (art == null) return;
        Bitmap soft = downsampleCover(art);
        post(() -> showSoftCover(soft));
    }

    @Override
    public void pauseRendering() {
    }

    @Override
    public void resumeRendering() {
    }

    private void showSoftCover(Bitmap soft) {
        if (soft == null) return;
        if (current != null) {
            previous = current;
        }
        current = new ImageView(getContext());
        current.setScaleType(ImageView.ScaleType.CENTER_CROP);
        current.setImageDrawable(new BitmapDrawable(getResources(), soft));
        current.setAlpha(0f);
        current.setTranslationX(getWidth() <= 0 ? 0f : getWidth() * 0.08f);
        current.setScaleX(1.25f);
        current.setScaleY(1.25f);
        current.setColorFilter(Color.argb(78, 255, 255, 255));
        addView(current, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        current.animate().alpha(1f).translationX(0f).setDuration(850).start();
        if (previous != null) {
            ImageView old = previous;
            old.animate().alpha(0f).translationX(-(getWidth() <= 0 ? 0f : getWidth() * 0.08f)).setDuration(850)
                    .withEndAction(() -> removeView(old)).start();
        }
    }

    private static Bitmap downsampleCover(Bitmap art) {
        int aw = Math.max(1, art.getWidth());
        int ah = Math.max(1, art.getHeight());
        float scale = SOFT_COVER_PX / (float) Math.max(aw, ah);
        int tw = Math.max(1, Math.round(aw * scale));
        int th = Math.max(1, Math.round(ah * scale));
        Bitmap small = Bitmap.createScaledBitmap(art, tw, th, true);
        Bitmap blurred = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        boxBlur(blurred, Math.max(1, Math.round(SOFT_COVER_PX * 0.18f)), 3);
        return blurred;
    }

    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 1 || h <= 1 || radius < 1) return;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int p = 0; p < passes; p++) {
            boxBlurPass(px, tmp, w, h, radius);
            boxBlurPass(tmp, px, h, w, radius);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxBlurPass(int[] src, int[] dst, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, r = 0, g = 0, b = 0;
            for (int x = -radius; x <= radius; x++) {
                int c = src[row + clamp(x, 0, w - 1)];
                a += Color.alpha(c);
                r += Color.red(c);
                g += Color.green(c);
                b += Color.blue(c);
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = Color.argb(a / span, r / span, g / span, b / span);
                int remove = src[row + clamp(x - radius, 0, w - 1)];
                int add = src[row + clamp(x + radius + 1, 0, w - 1)];
                a += Color.alpha(add) - Color.alpha(remove);
                r += Color.red(add) - Color.red(remove);
                g += Color.green(add) - Color.green(remove);
                b += Color.blue(add) - Color.blue(remove);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
