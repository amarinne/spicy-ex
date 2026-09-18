package com.eza.spicyex.beautifullyrics.entities;

import android.graphics.*;
import android.util.Half;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Worker-thread preprocessing. Never modifies or recycles a borrowed source bitmap. */
public final class AmbientArtworkTexture {
    public static final int SIZE = 128;
    private AmbientArtworkTexture() {}

    public static final class Prepared {
        /** Two 128x128 F16 tiles: normal and dark. Owned by the module, not the source. */
        public final Bitmap bitmap;
        public final AmbientArtworkProfile profile;
        Prepared(Bitmap bitmap, AmbientArtworkProfile profile) {
            this.bitmap = bitmap;
            this.profile = profile;
        }
    }

    public static Bitmap prepare(Bitmap source) {
        return prepareWithProfile(source).bitmap;
    }

    public static Prepared prepareWithProfile(Bitmap source) {
        Bitmap small = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[SIZE * SIZE];
        try {
            Canvas canvas = new Canvas(small);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(source, null, new Rect(0, 0, SIZE, SIZE),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        } finally {
            small.recycle();
        }
        float[] normal = kawase(pixels, SIZE, SIZE, false);
        float[] dark = kawase(pixels, SIZE, SIZE, true);
        // Only profile analysis is quantized; the displayed texture retains fractional channels.
        int[] profilePixels = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            profilePixels[i] = 0xff000000 | (Math.round(normal[3*i] * 255) << 16)
                    | (Math.round(normal[3*i+1] * 255) << 8) | Math.round(normal[3*i+2] * 255);
        }
        Bitmap atlas = Bitmap.createBitmap(SIZE * 2, SIZE, Bitmap.Config.RGBA_F16);
        // Explicit sRGB encoding matches WebGL's texture arithmetic (no implicit linearization).
        atlas.setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        ByteBuffer buffer = ByteBuffer.allocate(atlas.getByteCount()).order(ByteOrder.nativeOrder());
        for (int y = 0; y < SIZE; y++) {
            buffer.position(y * atlas.getRowBytes());
            for (int tile = 0; tile < 2; tile++) {
                float[] rgb = tile == 0 ? normal : dark;
                for (int x = 0; x < SIZE; x++) {
                    int i = 3 * (y * SIZE + x);
                    buffer.putShort(Half.toHalf(rgb[i]));
                    buffer.putShort(Half.toHalf(rgb[i+1]));
                    buffer.putShort(Half.toHalf(rgb[i+2]));
                    buffer.putShort(Half.toHalf(1f));
                }
            }
        }
        buffer.rewind();
        atlas.copyPixelsFromBuffer(buffer);
        return new Prepared(atlas, AmbientArtworkProfile.fromPixels(profilePixels));
    }

    /** Desktop tint-before-blur and eight four-corner passes at offsets 0.5 through 7.5. */
    static float[] kawase(int[] pixels, int width, int height, boolean dark) {
        float[] a = new float[pixels.length * 3], b = new float[a.length];
        for (int i = 0; i < pixels.length; i++) {
            float r = ((pixels[i] >> 16) & 255) / 255f;
            float g = ((pixels[i] >> 8) & 255) / 255f;
            float blue = (pixels[i] & 255) / 255f;
            float luma = r * 0.299f + g * 0.587f + blue * 0.114f;
            float t = Math.min(1f, luma / 0.5f);
            float tint = dark ? (1f - t*t*(3f - 2f*t)) * 0.38f : 0f;
            a[3*i] = r + (0.025f-r)*tint;
            a[3*i+1] = g + (0.022f-g)*tint;
            a[3*i+2] = blue + (0.03f-blue)*tint;
        }
        for (int pass = 0; pass < 8; pass++) {
            float offset = pass + 0.5f;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                for (int c = 0; c < 3; c++) {
                    b[3*(y*width+x)+c] = 0.25f * (
                            sample(a,width,height,x-offset,y-offset,c)
                            + sample(a,width,height,x+offset,y-offset,c)
                            + sample(a,width,height,x-offset,y+offset,c)
                            + sample(a,width,height,x+offset,y+offset,c));
                }
            }
            float[] swap = a; a = b; b = swap;
        }
        return a;
    }

    private static float sample(float[] rgb, int width, int height, float x, float y, int c) {
        x = Math.max(0f, Math.min(width-1, x));
        y = Math.max(0f, Math.min(height-1, y));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(width-1, x0+1), y1 = Math.min(height-1, y0+1);
        float dx = x-x0, dy = y-y0;
        float top = rgb[3*(y0*width+x0)+c]*(1f-dx) + rgb[3*(y0*width+x1)+c]*dx;
        float bottom = rgb[3*(y1*width+x0)+c]*(1f-dx) + rgb[3*(y1*width+x1)+c]*dx;
        return top*(1f-dy) + bottom*dy;
    }
}
