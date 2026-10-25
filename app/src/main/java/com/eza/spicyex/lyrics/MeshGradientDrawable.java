package com.eza.spicyex.lyrics;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.PathInterpolator;

import com.eza.spicyex.ambient.AmbientArtworkPalette;

/**
 * The "artwork colours" lyrics background: a soft mesh of colour fields derived from the track's
 * colour, over a dark base with a light vignette - instead of a flat two-stop diagonal gradient.
 *
 * <p>Rendered once per colour change into a small bitmap and drawn bilinear-upscaled: the fields
 * are soft by design, so the upscale costs nothing visually, and every frame the lyrics redraw
 * pays for one texture draw rather than several full-screen radial gradients. A track change
 * crossfades from the previous mesh.
 */
public final class MeshGradientDrawable extends Drawable {
    private static final int MESH_W = 108;
    private static final int MESH_H = 228;
    private static final long CROSSFADE_MS = 600L;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private Bitmap current;
    private Bitmap previous;
    private float blend = 1f;
    private int[] colors;
    private ValueAnimator fade;
    private int alpha = 255;

    public MeshGradientDrawable(int[] seedColors) {
        colors = seedColors == null ? null : seedColors.clone();
        current = render(colors);
    }

    /** {@code colors}: [bright, main, base] as from {@link LyricVisuals#spicyColorBackgroundColors}. */
    public void setColors(int[] next) {
        if (next == null || next.length == 0) return;
        if (colors != null && java.util.Arrays.equals(colors, next)) return;
        colors = next.clone();
        previous = current;
        current = render(colors);
        if (fade != null) fade.cancel();
        blend = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(CROSSFADE_MS);
        fade.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        fade.addUpdateListener(a -> {
            blend = (float) a.getAnimatedValue();
            if (blend >= 1f) previous = null;
            invalidateSelf();
        });
        fade.start();
    }

    /**
     * Colours taken from the artwork itself: several distinct fields plus a base. Replaces the
     * single-colour derivation, which left most covers as one dark tint.
     */
    public void setArtworkColors(int[] fields, int base) {
        if (fields == null || fields.length == 0) return;
        int[] packed = new int[fields.length + 1];
        System.arraycopy(fields, 0, packed, 0, fields.length);
        packed[fields.length] = base;
        packed = java.util.Arrays.copyOf(packed, packed.length + 1);
        packed[packed.length - 1] = ARTWORK_MARKER;
        setColors(packed);
    }

    /** Appended to an artwork palette so render() can tell it from a [bright, main, base] seed. */
    private static final int ARTWORK_MARKER = 0x00ABCDEF;

    private static Bitmap render(int[] palette) {
        Bitmap bitmap = Bitmap.createBitmap(MESH_W, MESH_H, Bitmap.Config.ARGB_8888);
        if (palette == null || palette.length == 0) return bitmap;
        if (palette[palette.length - 1] == ARTWORK_MARKER && palette.length >= 3) {
            return renderArtwork(bitmap, java.util.Arrays.copyOf(palette, palette.length - 2),
                    palette[palette.length - 2]);
        }
        int bright = palette[0];
        int main = palette.length > 1 ? palette[1] : bright;
        int base = palette.length > 2 ? palette[2] : Color.rgb(18, 18, 18);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(mix(base, main, 0.35f));
        // Fields placed like light falling across the screen: the brightest high on one side,
        // hue-shifted neighbours of the main colour elsewhere, a deep shade at the bottom.
        field(canvas, 0.18f, 0.10f, 0.78f, bright, 0.95f);
        field(canvas, 0.96f, 0.32f, 0.70f, shift(main, 24f, 1.05f, 1.18f), 0.85f);
        field(canvas, 0.06f, 0.62f, 0.72f, shift(main, -30f, 1.10f, 0.95f), 0.80f);
        field(canvas, 0.70f, 0.72f, 0.60f, shift(bright, 14f, 0.90f, 0.85f), 0.55f);
        field(canvas, 0.40f, 1.02f, 0.80f, shift(main, -8f, 1.0f, 0.55f), 0.90f);
        // Vignette: settles the edges so the lyrics sit on a calmer centre.
        Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        vignette.setShader(new RadialGradient(MESH_W * 0.5f, MESH_H * 0.45f, MESH_H * 0.72f,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, MESH_W, MESH_H, vignette);
        return bitmap;
    }

    private static Bitmap renderArtwork(Bitmap bitmap, int[] fields, int base) {
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(base);
        // Up to five fields; the most prominent colour gets the largest, highest one.
        float[][] spots = {
                {0.20f, 0.12f, 0.80f, 0.95f},
                {0.95f, 0.36f, 0.72f, 0.85f},
                {0.05f, 0.64f, 0.74f, 0.80f},
                {0.78f, 0.80f, 0.62f, 0.70f},
                {0.42f, 1.02f, 0.78f, 0.75f},
        };
        for (int i = 0; i < spots.length; i++) {
            int color = fields[i % fields.length];
            if (i >= fields.length) color = shift(color, 18f * (i - fields.length + 1), 1f, 0.8f);
            field(canvas, spots[i][0], spots[i][1], spots[i][2], color, spots[i][3]);
        }
        Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        vignette.setShader(new RadialGradient(MESH_W * 0.5f, MESH_H * 0.45f, MESH_H * 0.72f,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, MESH_W, MESH_H, vignette);
        return bitmap;
    }

    /**
     * Samples the artwork into the pure palette extractor. The returned colours are the cover's
     * own dominant hues and neutral light/dark fields; no saturation or brightness is imposed here.
     * Null only for a missing or fully recycled bitmap (the single-colour fallback then applies).
     */
    public static int[] extractPalette(Bitmap art, int max) {
        if (art == null || art.isRecycled()) return null;
        Bitmap small = Bitmap.createScaledBitmap(art, 32, 32, true);
        int[] pixels = new int[32 * 32];
        try {
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32);
        } finally {
            if (small != art) small.recycle();
        }
        return AmbientArtworkPalette.extract(pixels, max);
    }

    /** One soft colour field: full colour at its centre fading out over {@code radius} (of width). */
    private static void field(Canvas canvas, float cx, float cy, float radius, int color, float strength) {
        float r = radius * MESH_H;
        int a = Math.round(255 * strength);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(cx * MESH_W, cy * MESH_H, r,
                new int[]{Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(Math.round(a * 0.45f), Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx * MESH_W, cy * MESH_H, r, paint);
    }

    private static int shift(int color, float hueDegrees, float saturation, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hueDegrees + 360f) % 360f;
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * saturation));
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * value));
        return Color.HSVToColor(hsv);
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        Bitmap old = previous;
        if (old != null && blend < 1f) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(old, null, bounds, paint);
            paint.setAlpha(Math.round(alpha * blend));
        } else {
            paint.setAlpha(alpha);
        }
        if (current != null) canvas.drawBitmap(current, null, bounds, paint);
    }

    @Override
    public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
