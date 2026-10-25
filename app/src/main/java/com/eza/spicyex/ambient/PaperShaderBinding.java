package com.eza.spicyex.ambient;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform and texture binding for the unmodified Paper Shaders AGSL catalog. Every Paper shader
 * shares the ambient grade and resolution/time/sizing contract already used by Kawarp and Mesh.
 */
final class PaperShaderBinding {
    private static final String NOISE = "paper-shaders/ps_noise.png";

    private PaperShaderBinding() {}

    /** Sets the imported defaults, then replaces color declarations with the current palette. */
    static void applyDefaultsAndPalette(RuntimeShader target, PaperShaderCatalog.Spec spec,
                                        int[] palette) {
        Map<String, float[]> colors = paletteUniforms(spec, palette);
        for (Map.Entry<String, float[]> entry : spec.uniforms.entrySet()) {
            String name = entry.getKey();
            float[] values = colors.containsKey(name) ? colors.get(name)
                    : PaperShaderTuning.valueForImage(spec.id, name, entry.getValue(),
                            spec.usesImageTexture);
            Integer capacity = spec.colorCapacities.get(name);
            setUniform(target, name, values, capacity != null && capacity > 1);
        }
    }

    /**
     * Distributes the ambient palette across color declarations in catalog order. Alpha from the
     * imported default is retained per slot, so transparent unused entries and translucent
     * foregrounds keep their source alpha.
     */
    static Map<String, float[]> paletteUniforms(PaperShaderCatalog.Spec spec, int[] palette) {
        Map<String, float[]> result = new LinkedHashMap<>();
        int slotOffset = 0;
        for (Map.Entry<String, Integer> entry : spec.colorCapacities.entrySet()) {
            int capacity = entry.getValue();
            float[] defaults = spec.uniforms.get(entry.getKey());
            float[] values = defaults == null ? new float[capacity * 4] : defaults.clone();
            if (palette != null && palette.length > 0) {
                for (int slot = 0; slot < capacity; slot++) {
                    int color = palette[(slotOffset + slot) % palette.length];
                    values[slot * 4] = ((color >> 16) & 0xff) / 255f;
                    values[slot * 4 + 1] = ((color >> 8) & 0xff) / 255f;
                    values[slot * 4 + 2] = (color & 0xff) / 255f;
                }
            }
            result.put(entry.getKey(), values);
            slotOffset += capacity;
        }
        return result;
    }

    /**
     * Applies the shared globals. {@code pixelRatio} is pixels per density-independent pixel for
     * the surface the shader actually draws into, matching the upstream drawable contract.
     */
    static void applyGlobals(RuntimeShader target, PaperShaderCatalog.Spec spec,
                             int width, int height, float pixelRatio, double elapsedSeconds) {
        target.setFloatUniform("u_resolution", width, height);
        target.setFloatUniform("u_pixelRatio", pixelRatio);
        target.setFloatUniform("u_time", (float) (elapsedSeconds * spec.speed));
    }

    static float paperTime(PaperShaderCatalog.Spec spec, double elapsedSeconds) {
        return (float) (elapsedSeconds * spec.speed);
    }

    static void bindArtwork(RuntimeShader target, Bitmap artwork) {
        if (artwork == null || artwork.isRecycled()) return;
        target.setInputShader("u_image", linearTexture(artwork));
        target.setFloatUniform("u_imageSize", artwork.getWidth(), artwork.getHeight());
        target.setFloatUniform("u_imageAspectRatio",
                artwork.getWidth() / (float) artwork.getHeight());
    }

    static void bindNoise(RuntimeShader target, Bitmap noise) {
        if (noise == null || noise.isRecycled()) return;
        target.setInputBuffer("u_noiseTexture", linearTexture(noise));
        target.setFloatUniform("u_noiseTextureSize", noise.getWidth(), noise.getHeight());
    }

    static BitmapShader linearTexture(Bitmap bitmap) {
        BitmapShader input = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return input;
    }

    /** Loads through the module classloader, never Spotify's Resources or density scaling. */
    static Bitmap loadNoise() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        try (InputStream input = PaperShaderBinding.class.getClassLoader()
                .getResourceAsStream(NOISE)) {
            if (input == null) throw new IllegalStateException("Paper noise texture unavailable");
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            if (decoded == null) throw new IllegalStateException("Paper noise texture unavailable");
            return decoded;
        } catch (IOException e) {
            throw new IllegalStateException("Paper noise texture unavailable", e);
        }
    }

    private static void setUniform(RuntimeShader target, String name, float[] values,
                                   boolean array) {
        if (array) {
            target.setFloatUniform(name, values);
            return;
        }
        switch (values.length) {
            case 1:
                target.setFloatUniform(name, values[0]);
                break;
            case 2:
                target.setFloatUniform(name, values[0], values[1]);
                break;
            case 3:
                target.setFloatUniform(name, values[0], values[1], values[2]);
                break;
            case 4:
                target.setFloatUniform(name, values[0], values[1], values[2], values[3]);
                break;
            default:
                throw new IllegalArgumentException("Unsupported Paper uniform width");
        }
    }
}
