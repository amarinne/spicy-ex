package com.eza.spicyex.ambient;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.View;
import androidx.annotation.RequiresApi;

import com.eza.spicyex.xposed.XpLog;

/** A high-precision Kawase texture animated with the desktop Kawarp equations. All renderer state is UI-thread. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class AmbientArtworkBackgroundView extends View implements AmbientBackgroundLayer {
    private static final String TAG = "[SpotifyPlusAmbientView]";
    // Domain warp and output math ported from @kawarp/core. Keep float intermediates:
    // floating-point blur textures preserve smooth gradients without moving contours.
    private static final String AGSL =
            "uniform shader image;\n"
            + "uniform float2 resolution;\n"
            + "uniform float time;\n"
            + "uniform float dark;\n"
            + "uniform float brightness;\n"
            + "  float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n"
            + "  float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n"
            + "  float3 permute(float3 x) { return mod289(((x*34.0)+1.0)*x); }\n"
            + "\n"
            + "  float snoise(float2 v) {\n"
            + "    const float4 C = float4(0.211324865405187, 0.366025403784439,\n"
            + "                        -0.577350269189626, 0.024390243902439);\n"
            + "    float2 i  = floor(v + dot(v, C.yy));\n"
            + "    float2 x0 = v - i + dot(i, C.xx);\n"
            + "    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);\n"
            + "    float4 x12 = x0.xyxy + C.xxzz;\n"
            + "    x12.xy -= i1;\n"
            + "    i = mod289(i);\n"
            + "    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));\n"
            + "    float3 m = max(0.5 - float3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);\n"
            + "    m = m*m; m = m*m;\n"
            + "    float3 x = 2.0 * fract(p * C.www) - 1.0;\n"
            + "    float3 h = abs(x) - 0.5;\n"
            + "    float3 ox = floor(x + 0.5);\n"
            + "    float3 a0 = x - ox;\n"
            + "    m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);\n"
            + "    float3 g;\n"
            + "    g.x = a0.x * x0.x + h.x * x0.y;\n"
            + "    g.yz = a0.yz * x12.xz + h.yz * x12.yw;\n"
            + "    return 130.0 * dot(m, g);\n"
            + "  }\n"
            + "\n"
            + "float hash(float3 p) {\n"
            + "    p = fract(p * 0.1031);\n"
            + "    p += dot(p, p.zyx + 31.32);\n"
            + "    return fract((p.x + p.y) * p.z);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "\n"
            + "    float2 uv = p / resolution;\n"
            + "    float t = time * 0.05;\n"
            + "\n"
            + "    float2 center = uv - 0.5;\n"
            + "    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));\n"
            + "\n"
            + "    // Large-scale movement (slow, big blobs)\n"
            + "    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));\n"
            + "    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));\n"
            + "\n"
            + "    // Medium-scale detail (adds organic movement)\n"
            + "    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));\n"
            + "    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));\n"
            + "\n"
            + "    // Combine two octaves\n"
            + "    float2 warp = float2(\n"
            + "      n1 * 0.65 + n3 * 0.35,\n"
            + "      n2 * 0.65 + n4 * 0.35\n"
            + "    ) * centerWeight;\n"
            + "\n"
            + "    float2 warpedUV = uv + warp * 1.0;\n"
            + "    warpedUV = clamp(warpedUV, 0.0, 1.0);\n"
            + "\n"
            + "\n"
            + "    // Two 128px tiles retain the desktop tint-before-blur order for either dark setting.\n"
            + "    float2 samplePx = clamp(warpedUV * 128.0, 0.5, 127.5);\n"
            + "    samplePx.x += dark * 128.0;\n"
            + "    float3 c = float3(image.eval(samplePx).rgb);\n"
            + "    c *= 1.0 - dot(center, center) * 0.3;\n"
            + "    float gray = dot(c, float3(0.299, 0.587, 0.114));\n"
            + "    c = clamp(mix(float3(gray), c, mix(1.5, 0.75, dark)), 0.0, 1.0);\n"
            + "    // Desktop fullscreen CSS: normal saturate(2.5) brightness(.65), dark\n"
            + "    // brightness(.38) saturate(.9) contrast(.95). CSS saturation uses Rec.709.\n"
            + "    float cssGray = dot(c, float3(0.213, 0.715, 0.072));\n"
            + "    float3 normal = clamp(mix(float3(cssGray), c, 2.5), 0.0, 1.0) * 0.65;\n"
            + "    float3 dim = (mix(float3(cssGray), c, 0.9) * 0.38 - 0.5) * 0.95 + 0.5;\n"
            + "    c = mix(normal, dim, dark) * brightness;\n"
            + "    // Desktop hash/dither strength, moved after final darkening to retain output precision.\n"
            + "    // Zero brightness must stay exactly black.\n"
            + "    float noise = hash(float3(floor(p), 0.0));\n"
            + "    c += (noise - 0.5) * 0.008 * min(1.0, brightness * 255.0);\n"
            + "    return half4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private RuntimeShader shader;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fallback = new Paint(Paint.DITHER_FLAG);
    /** Kawarp's two-tile processed atlas. */
    private Bitmap texture;
    /** Original, aspect-preserving cover used only by image-backed Paper effects. */
    private Bitmap originalTexture;
    /** Shared 128x128 noise sampler for the noise-backed Paper effects, loaded lazily. */
    private Bitmap noiseTexture;
    private PaperShaderCatalog.Spec paperSpec;
    private boolean enabled = true, moving = true, playing = true, posted;
    private boolean forceDark;
    private long lastFrame;
    private double elapsedSeconds;
    private int colorA = Color.rgb(30,21,18), colorB = Color.rgb(16,15,16);
    private int[] paletteColors = {colorA, colorB};
    private final MeshGradientTransition meshTransition;
    private AmbientShaderEffect effect = AmbientShaderEffect.DEFAULT;
    /** Content-free record of the last candidate the platform refused; empty after a success. */
    private String rejectedEffect = "";
    private int lowWidth = 1, lowHeight = 1;
    private final Choreographer.FrameCallback frame = this::tick;
    /**
     * The AGSL noise shader's cost is per output pixel, so it's rendered into a much smaller
     * offscreen surface and upscaled (the blur/warp look hides the extra softness) instead of at
     * full view resolution every frame - a full-res fragment shader running continuously for the
     * whole lyrics session is a real sustained-heat source on older/weaker GPUs.
     */
    private static final float DEFAULT_RENDER_SCALE = 0.34f;
    /** User-configurable via {@link #setRenderScale}, backing the Background panel's Quality
     *  slider - lower trades softness for less sustained GPU/heat load, higher for crisper noise. */
    private float renderScale = DEFAULT_RENDER_SCALE;
    // A RenderNode is drawn on RenderThread/GPU like the main canvas, unlike a Bitmap-backed
    // Canvas (always CPU raster) which RuntimeShader/AGSL cannot draw into at all.
    private RenderNode offscreenNode;
    /** Freezes the animation (last frame stays visible) once the OS reports the device running
     *  hot, instead of continuing to add GPU load on top of whatever caused it. */
    private boolean thermalThrottled;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private float baseBrightness = 1f;

    public AmbientArtworkBackgroundView(Context context, boolean dark) {
        super(context);
        meshTransition = new MeshGradientTransition(MeshGradientShader.packColors(paletteColors),
                paletteColors.length, 0f, MeshGradientMotionProfile.NEUTRAL_SPEED, 0L);
        shader = buildShader(effect);
        paint.setShader(shader);
        updateFallback();
        setForceDark(dark);
    }

    /** The AGSL text for one selection; kept package-visible so the source contract is testable. */
    static String shaderSource(AmbientShaderEffect selection) {
        if (selection == AmbientShaderEffect.MESH_GRADIENT) return MeshGradientShader.SOURCE;
        if (selection == null || selection == AmbientShaderEffect.KAWARP) return AGSL;
        String source = paperSpec(selection).source;
        if (selection == AmbientShaderEffect.PULSING_BORDER) {
            // The upstream helper sizes a centered showcase object. Ambient lyrics need the
            // effect to hug the actual output surface, including portrait screens.
            source = source.replace("vec2 borderUV = ps_responsiveUV(fragCoord);",
                    "vec2 borderUV = vec2(fragCoord.x / u_resolution.x - 0.5,"
                            + " 0.5 - fragCoord.y / u_resolution.y);")
                    .replace("float canvasRatio = ps_responsiveBoxGivenSize().x / ps_responsiveBoxGivenSize().y;",
                            "float canvasRatio = u_resolution.x / max(u_resolution.y, 1.0);");
        } else if (selection == AmbientShaderEffect.GOD_RAYS) {
            // The upstream light source floats inside a centered tile. Behind lyrics the rays fall
            // from the middle of the top screen edge, measured in short-side units on any aspect.
            source = source.replace("vec2 shapeUv = ps_objectUV(fragCoord);",
                    "vec2 shapeUv = vec2(fragCoord.x - 0.5 * u_resolution.x, -fragCoord.y)"
                            + " / max(min(u_resolution.x, u_resolution.y), 1.0);");
        } else if (selection == AmbientShaderEffect.DOT_ORBIT) {
            // Upstream dots end in a one-pixel edge; behind lyrics they fade out from the center.
            source = source.replace("float edgeWidth = ps_pixelDerivative(1.0);",
                    "float edgeWidth = max(ps_pixelDerivative(1.0), 0.8 * radius);");
        } else if (selection == AmbientShaderEffect.METABALLS) {
            // Same for the blob outline: a wide threshold band lets neighbouring blobs melt.
            source = source.replace("float finalShape = smoothstep(0.4, 0.4 + edgeWidth, totalShape);",
                    "float finalShape = smoothstep(0.1, 0.75 + edgeWidth, totalShape);");
        }
        return source;
    }

    private static boolean isPaper(AmbientShaderEffect candidate) {
        return candidate != null && candidate != AmbientShaderEffect.KAWARP
                && candidate != AmbientShaderEffect.MESH_GRADIENT;
    }

    private static PaperShaderCatalog.Spec paperSpec(AmbientShaderEffect candidate) {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get(candidate.argument());
        if (spec == null) throw new IllegalArgumentException("Unknown Paper effect");
        return spec;
    }

    private RuntimeShader buildShader(AmbientShaderEffect candidate) {
        RuntimeShader next = new RuntimeShader(shaderSource(candidate));
        if (isPaper(candidate)) {
            PaperShaderCatalog.Spec spec = paperSpec(candidate);
            PaperShaderBinding.applyDefaultsAndPalette(next, spec, paletteColors);
            applyPaperGlobals(next, spec, lowWidth, lowHeight, livePixelRatio());
            applyPaperGrade(next, forceDark, baseBrightness);
            bindPaperInputs(next, spec);
        } else {
            next.setFloatUniform("resolution", lowWidth, lowHeight);
            next.setFloatUniform("time", (float) elapsedSeconds);
            applyNativeGrade(next, forceDark, baseBrightness);
            if (candidate == AmbientShaderEffect.MESH_GRADIENT) {
                applyMeshPalette(next);
                next.setFloatUniform("u_distortion", 0.8f);
                next.setFloatUniform("u_swirl", 0.1f);
                next.setFloatUniform("u_phase", meshTransition.phase());
                next.setFloatUniform("u_motion", meshTransition.speed());
                // Mesh Gradient ships with grain off; the upstream guards keep the work out of the
                // default frame without deleting the sourced maths.
                next.setFloatUniform("u_grainMixer", 0f);
                next.setFloatUniform("u_grainOverlay", 0f);
            } else {
                bindTexture(next);
            }
        }
        return next;
    }

    private void applyMeshPalette(RuntimeShader target) {
        target.setFloatUniform("u_colors", meshTransition.colors());
        target.setFloatUniform("u_colorsCount", meshTransition.count());
    }

    private void applyPaperPalette(RuntimeShader target, PaperShaderCatalog.Spec spec) {
        if (spec == null) return;
        for (java.util.Map.Entry<String, float[]> entry
                : PaperShaderBinding.paletteUniforms(spec, paletteColors).entrySet()) {
            Integer capacity = spec.colorCapacities.get(entry.getKey());
            float[] values = entry.getValue();
            if (capacity != null && capacity > 1) {
                target.setFloatUniform(entry.getKey(), values);
            } else {
                target.setFloatUniform(entry.getKey(),
                        values[0], values[1], values[2], values[3]);
            }
        }
    }

    /** Applies the imported sizing defaults plus the shared resolution/time/pixel-ratio contract. */
    private void applyPaperGlobals(RuntimeShader target, PaperShaderCatalog.Spec spec,
                                   int width, int height, float pixelRatio) {
        PaperShaderBinding.applyGlobals(target, spec, width, height, pixelRatio, elapsedSeconds);
        if (!spec.usesImageTexture || !hasOriginal()) {
            target.setFloatUniform("u_imageAspectRatio", 1f);
        }
    }

    private static void applyNativeGrade(RuntimeShader target, boolean dark, float brightness) {
        target.setFloatUniform("dark", dark ? 1f : 0f);
        target.setFloatUniform("brightness", brightness);
    }

    private static void applyPaperGrade(RuntimeShader target, boolean dark, float brightness) {
        target.setFloatUniform("ambient_dark", dark ? 1f : 0f);
        target.setFloatUniform("ambient_brightness", brightness);
    }

    /**
     * Switches the shader behind the same layer, scale, cadence and grade. A candidate the platform
     * refuses leaves the previous shader running: the failure is logged content-free and reported
     * once, and there is no automatic retry.
     */
    @Override
    public boolean setEffect(AmbientShaderEffect next) {
        if (next == null) return false;
        if (next == effect) {
            rejectedEffect = "";
            return true;
        }
        RuntimeShader replacement;
        try {
            replacement = buildShader(next);
        } catch (Throwable t) {
            rejectedEffect = next.argument();
            XpLog.log(TAG + " ambient effect rejected effect=" + next.argument());
            return false;
        }
        effect = next;
        paperSpec = isPaper(next) ? paperSpec(next) : null;
        rejectedEffect = "";
        shader = replacement;
        paint.setShader(shader);
        schedule();
        invalidate();
        return true;
    }

    /** Content-free effect state for the debug CLI and evidence logs. */
    public String effectStatus() {
        return "effect=" + effect.argument()
                + " rejected=" + (rejectedEffect.isEmpty() ? "none" : rejectedEffect);
    }

    @Override
    public boolean usesArtworkTexture() {
        if (isPaper(effect)) return paperSpec != null && paperSpec.usesImageTexture;
        return effect.requiresArtworkTexture();
    }

    @Override
    public boolean usesOriginalArtwork() {
        return isPaper(effect) && paperSpec != null && paperSpec.usesImageTexture;
    }

    private boolean hasOriginal() {
        return originalTexture != null && !originalTexture.isRecycled();
    }

    private void bindTexture(RuntimeShader target) {
        Bitmap source = texture;
        if (source == null || source.isRecycled()) return;
        target.setInputShader("image", PaperShaderBinding.linearTexture(source));
    }

    private void bindPaperInputs(RuntimeShader target, PaperShaderCatalog.Spec spec) {
        if (spec.usesNoiseTexture) {
            ensureNoise();
            PaperShaderBinding.bindNoise(target, noiseTexture);
        }
        Bitmap original = originalTexture;
        if (spec.usesImageTexture && original != null && !original.isRecycled()) {
            PaperShaderBinding.bindArtwork(target, original);
        }
    }

    private void ensureNoise() {
        if (noiseTexture == null || noiseTexture.isRecycled()) {
            noiseTexture = PaperShaderBinding.loadNoise();
        }
    }

    private boolean renderable() {
        if (isPaper(effect)) {
            return paperSpec != null
                    && (!paperSpec.usesImageTexture || hasOriginal());
        }
        return !effect.requiresArtworkTexture() || (texture != null && !texture.isRecycled());
    }

    private boolean effectAnimates() {
        if (isPaper(effect)) {
            return paperSpec != null && paperSpec.animated && paperSpec.speed != 0f;
        }
        return true;
    }

    private float livePixelRatio() {
        return getResources().getDisplayMetrics().density * renderScale;
    }

    private float snapshotPixelRatio(int width) {
        float density = getResources().getDisplayMetrics().density;
        int viewWidth = getWidth();
        if (viewWidth > 0) return density * width / viewWidth;
        return density * renderScale;
    }

    private void applyGrade() {
        if (shader == null) return;
        if (isPaper(effect)) applyPaperGrade(shader, forceDark, baseBrightness);
        else applyNativeGrade(shader, forceDark, baseBrightness);
    }

    public View asView() { return this; }
    public void setForceDark(boolean dark) {
        forceDark = dark;
        applyGrade();
        invalidate();
    }
    public void setDarkening(float brightness, ColorFilter filter) {
        baseBrightness = brightness;
        applyGrade();
        // Animated shader receives brightness directly; only the fallback canvas needs a filter.
        fallback.setColorFilter(filter);
        invalidate();
    }
    public void setPaletteColors(int[] colors) {
        if (colors == null || colors.length < 2) return;
        paletteColors = colors.clone();
        colorA = colors[0]; colorB = colors[colors.length-1];
        setMeshPaletteAndMotion(colors, meshTransition.phase(), meshTransition.speed(),
                meshTransition.generation() + 1L);
        updateFallback(); invalidate();
    }

    /** Starts one palette/profile handoff; callers provide a monotonically increasing track generation. */
    public boolean setMeshPaletteAndMotion(int[] colors, float phase, float speed, long generation) {
        if (colors == null || colors.length < 2 || generation < meshTransition.generation()) return false;
        float[] packed = MeshGradientShader.packColors(colors);
        boolean accepted = meshTransition.begin(generation, System.nanoTime(), phase, speed,
                packed, colors.length);
        if (accepted) {
            paletteColors = colors.clone();
            colorA = colors[0]; colorB = colors[colors.length - 1];
            updateFallback();
            invalidate();
        }
        if (accepted && effect == AmbientShaderEffect.MESH_GRADIENT) {
            if (animating()) meshTransition.advance(System.nanoTime());
            else meshTransition.finish();
            applyMeshPalette(shader);
            shader.setFloatUniform("u_phase", meshTransition.phase());
            shader.setFloatUniform("u_motion", meshTransition.speed());
        }
        if (accepted && isPaper(effect)) applyPaperPalette(shader, paperSpec);
        return accepted;
    }

    public boolean setMeshPalette(int[] colors, long generation) {
        return setMeshPaletteAndMotion(colors, meshTransition.phase(), meshTransition.speed(), generation);
    }
    public void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value; schedule();
    }
    public void setMotionEnabled(boolean value) {
        if (moving == value) return;
        moving = value;
        if (!moving) elapsedSeconds = 0;
        schedule(); invalidate();
    }
    public void pauseRendering() { enabled = false; schedule(); }
    public void resumeRendering() { enabled = true; schedule(); }

    /** Accepts a prepared, module-owned texture. No asynchronous upload can outlive this call. */
    public void updateImage(Bitmap prepared) {
        updateImage(prepared, null);
    }

    /**
     * Accepts the prepared normal/dark texture atlas. The profile is retained in the shared
     * handoff contract, but desktop Kawarp does not modulate brightness by artwork variance.
     * Mesh Gradient is palette-only, so the texture is retained for a later switch back but not
     * bound to the running shader.
     */
    public void updateImage(Bitmap prepared, AmbientArtworkProfile profile) {
        if (prepared == null || prepared.isRecycled()) return;
        texture = prepared;
        // The atlas and the Paper original are different representations; never sample one as
        // the other after an effect change.
        originalTexture = null;
        if (effect == AmbientShaderEffect.KAWARP) bindTexture(shader);
        // Dropping references lets RenderThread finish using the previous texture before collection.
        paint.setShader(shader);
        schedule(); invalidate();
    }

    /** Installs the owned, aspect-preserving cover sampled by Paper image effects. */
    @Override
    public void updateOriginalImage(Bitmap art) {
        if (art == null || art.isRecycled()) return;
        originalTexture = art;
        texture = null;
        if (isPaper(effect) && paperSpec != null && paperSpec.usesImageTexture) {
            PaperShaderBinding.bindArtwork(shader, art);
        }
        paint.setShader(shader);
        schedule(); invalidate();
    }

    /**
     * One frame of this background at {@code width} x {@code height}, for the lyric share card.
     * Renders with its own copy of the shader (so the live view's uniforms are never touched from
     * another thread) through an offscreen HardwareRenderer, and softens it like the live layer.
     * Null when the selected effect needs artwork and none exists yet, or the platform refuses.
     */
    public Bitmap snapshot(int width, int height) {
        Bitmap source = isPaper(effect) ? originalTexture : texture;
        if (effect.requiresArtworkTexture() && (source == null || source.isRecycled())) return null;
        boolean paperImage = isPaper(effect) && paperSpec != null && paperSpec.usesImageTexture;
        if (paperImage && (source == null || source.isRecycled())) return null;
        android.media.ImageReader reader = null;
        HardwareRenderer renderer = null;
        try {
            RuntimeShader copy = new RuntimeShader(shaderSource(effect));
            if (isPaper(effect)) {
                PaperShaderBinding.applyDefaultsAndPalette(copy, paperSpec, paletteColors);
                PaperShaderBinding.applyGlobals(copy, paperSpec, width, height,
                        snapshotPixelRatio(width), elapsedSeconds);
                applyPaperGrade(copy, false, baseBrightness);
                if (!paperSpec.usesImageTexture || source == null) {
                    copy.setFloatUniform("u_imageAspectRatio", 1f);
                }
                if (paperSpec.usesNoiseTexture) {
                    ensureNoise();
                    PaperShaderBinding.bindNoise(copy, noiseTexture);
                }
                if (paperImage) PaperShaderBinding.bindArtwork(copy, source);
            } else if (effect == AmbientShaderEffect.MESH_GRADIENT) {
                meshTransition.advance(System.nanoTime());
                applyMeshPalette(copy);
                copy.setFloatUniform("u_distortion", 0.8f);
                copy.setFloatUniform("u_swirl", 0.1f);
                copy.setFloatUniform("u_phase", meshTransition.phase());
                copy.setFloatUniform("u_motion", meshTransition.speed());
                copy.setFloatUniform("u_grainMixer", 0f);
                copy.setFloatUniform("u_grainOverlay", 0f);
                copy.setFloatUniform("resolution", width, height);
                copy.setFloatUniform("time", (float) elapsedSeconds);
                copy.setFloatUniform("dark", 0f);
                copy.setFloatUniform("brightness", baseBrightness);
            } else {
                BitmapShader input = new BitmapShader(source, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                copy.setInputShader("image", input);
                copy.setFloatUniform("resolution", width, height);
                copy.setFloatUniform("time", (float) elapsedSeconds);
                copy.setFloatUniform("dark", 0f);
                copy.setFloatUniform("brightness", baseBrightness);
            }
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setShader(copy);
            RenderNode node = new RenderNode("ambientSnapshot");
            node.setPosition(0, 0, width, height);
            node.setRenderEffect(RenderEffect.createBlurEffect(9f, 9f, Shader.TileMode.CLAMP));
            RecordingCanvas canvas = node.beginRecording();
            canvas.drawRect(0, 0, width, height, p);
            node.endRecording();
            reader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (android.media.Image image = reader.acquireNextImage()) {
                if (image == null) return null;
                android.hardware.HardwareBuffer buffer = image.getHardwareBuffer();
                if (buffer == null) return null;
                try {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer,
                            ColorSpace.get(ColorSpace.Named.SRGB));
                    return wrapped == null ? null : wrapped.copy(Bitmap.Config.ARGB_8888, false);
                } finally {
                    buffer.close();
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (renderer != null) renderer.destroy();
            if (reader != null) reader.close();
        }
    }

    public void release() {
        enabled = false; schedule();
        if (usesArtworkTexture()) {
            paint.setShader(null);
            Shader blank = new LinearGradient(0,0,1,1,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP);
            if (effect == AmbientShaderEffect.KAWARP) {
                shader.setInputShader("image", blank);
            } else if (paperSpec != null) {
                shader.setInputShader("u_image", blank);
            }
        }
        texture = null;
        originalTexture = null;
        noiseTexture = null;
        invalidate();
    }
    private boolean animating() {
        return enabled && moving && playing && !thermalThrottled && effectAnimates()
                && renderable() && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown()
                && com.eza.spicyex.ui.Motion.animationsEnabled();
    }
    private void schedule() {
        if (!animating()) {
            if (meshTransition.finish() && effect == AmbientShaderEffect.MESH_GRADIENT) {
                applyMeshPalette(shader);
                shader.setFloatUniform("u_phase", meshTransition.phase());
                shader.setFloatUniform("u_motion", meshTransition.speed());
                invalidate();
            }
            Choreographer.getInstance().removeFrameCallback(frame);
            posted = false; lastFrame = 0; return;
        }
        if (!posted) {
            posted = true;
            // ~20fps: slow drifting noise reads the same as at 33fps but wakes the GPU less often.
            // The beat itself is not tied to this rate (see stepPulse).
            Choreographer.getInstance().postFrameCallbackDelayed(frame, 50);
        }
    }
    private void tick(long now) {
        posted = false;
        if (!animating()) { lastFrame = 0; return; }
        if (lastFrame != 0) elapsedSeconds += Math.min(0.1, (now-lastFrame)/1e9);
        lastFrame = now;
        invalidate();
        schedule();
    }
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerThermalListener();
        schedule();
    }
    protected void onDetachedFromWindow() {
        unregisterThermalListener();
        Choreographer.getInstance().removeFrameCallback(frame);
        posted = false; lastFrame = 0;
        super.onDetachedFromWindow();
    }
    private void registerThermalListener() {
        if (thermalListener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        try {
            thermalListener = status -> {
                boolean hot = status >= PowerManager.THERMAL_STATUS_MODERATE;
                if (hot == thermalThrottled) return;
                thermalThrottled = hot;
                schedule();
            };
            pm.addThermalStatusListener(getContext().getMainExecutor(), thermalListener);
            thermalThrottled = pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        } catch (Throwable ignored) {
            thermalListener = null;
        }
    }
    private void unregisterThermalListener() {
        if (thermalListener == null) return;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try { pm.removeThermalStatusListener(thermalListener); } catch (Throwable ignored) { }
        }
        thermalListener = null;
        thermalThrottled = false;
    }
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) schedule();
    }
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed,visibility);
        if (frame != null) schedule();
    }
    /** Quality<->performance slider (Background panel), 0.15-1.0: lower renders the offscreen
     *  noise surface at a coarser resolution (softer, cheaper on the GPU), 1.0 is full view
     *  resolution. Re-sizes the offscreen surface immediately if already laid out. */
    public void setRenderScale(float scale) {
        float clamped = Math.max(0.15f, Math.min(1f, scale));
        if (clamped == renderScale) return;
        renderScale = clamped;
        if (getWidth() > 0 && getHeight() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        int lowW = Math.max(1, Math.round(w * renderScale));
        int lowH = Math.max(1, Math.round(h * renderScale));
        lowWidth = lowW;
        lowHeight = lowH;
        // Uniform tracks the offscreen surface's own size, not the view's - the shader's uv
        // mapping (p / resolution) only needs to span 0..1 across whatever it's drawn onto.
        if (isPaper(effect) && paperSpec != null) {
            PaperShaderBinding.applyGlobals(shader, paperSpec, lowW, lowH,
                    livePixelRatio(), elapsedSeconds);
        } else {
            shader.setFloatUniform("resolution", lowW, lowH);
        }
        if (offscreenNode == null) {
            offscreenNode = new RenderNode("ambientArtwork");
            // The compositing layer is what makes this node an actual lowW x lowH surface. Without
            // it, drawRenderNode just replays the rect under the parent's scale, so the shader ran
            // per full-resolution screen pixel on every frame the lyrics above it redrew. With it,
            // the shader runs only when onDraw re-records (the ~20fps tick); other frames reuse
            // the layer texture, bilinear-upscaled.
            offscreenNode.setUseCompositingLayer(true, null);
        }
        // Soften the low-resolution layer before it is upscaled. Unblurred, its warp edges and
        // dither stair-step and shimmer once magnified (the background "crawled"), while the
        // blurred lyric rows over it are smooth; a small blur at layer scale gives both the same
        // soft texture. Cheap: it runs on the small layer, and only when the layer is redrawn.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float radius = Math.max(1.5f, 3.2f * renderScale / DEFAULT_RENDER_SCALE);
            offscreenNode.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        }
        offscreenNode.setPosition(0, 0, lowW, lowH);
        updateFallback();
    }
    private void updateFallback() {
        fallback.setShader(new LinearGradient(0,0,Math.max(1,getWidth()),Math.max(1,getHeight()),
                colorA,colorB,Shader.TileMode.CLAMP));
    }
    protected void onDraw(Canvas canvas) {
        if (effect == AmbientShaderEffect.MESH_GRADIENT) {
            boolean paletteChanged = meshTransition.advance(System.nanoTime());
            shader.setFloatUniform("u_phase", meshTransition.phase());
            shader.setFloatUniform("u_motion", meshTransition.speed());
            if (paletteChanged) applyMeshPalette(shader);
        }
        if (renderable()) {
            if (isPaper(effect) && paperSpec != null) {
                shader.setFloatUniform("u_time", PaperShaderBinding.paperTime(paperSpec, elapsedSeconds));
            } else {
                shader.setFloatUniform("time", (float) elapsedSeconds);
            }
            if (offscreenNode != null && canvas.isHardwareAccelerated()) {
                int lowW = offscreenNode.getWidth(), lowH = offscreenNode.getHeight();
                RecordingCanvas recording = offscreenNode.beginRecording();
                recording.drawRect(0, 0, lowW, lowH, paint);
                offscreenNode.endRecording();
                canvas.save();
                canvas.scale(getWidth() / (float) lowW, getHeight() / (float) lowH);
                canvas.drawRenderNode(offscreenNode);
                canvas.restore();
                return;
            }
        }
        canvas.drawRect(0,0,getWidth(),getHeight(),renderable() ? paint : fallback);
    }
}
