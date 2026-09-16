package com.eza.spicyex.beautifullyrics.entities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * Album-art ambient background ported from kawarp (`@kawarp/core`): a slow GPU domain-warp over a
 * heavily-softened cover, with a saturation lift and a touch of dithering to kill banding. Uses an
 * AGSL {@link RuntimeShader} (Android 13+/API 33), so the controller only attaches this on capable
 * devices. Older devices skip animated background instead of running the retired CPU blob fallback.
 *
 * <p>Rather than running kawarp's 8 Kawase blur passes per frame on mobile, the cover is downsampled
 * and box-blurred once to kawarp's blur sigma, then bilinearly upscaled by the shader — visually
 * equivalent at a fraction of the cost — leaving only the cheap per-frame warp on the GPU.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class KawarpBackgroundView extends View implements AmbientBackgroundLayer {
    // kawarp defaults, matched 1:1: warpIntensity 1, saturation 1.5, dithering 0.008. blurPasses 8
    // is emulated by the downsample + box blur below, tuned to the same sigma rather than eyeballed.
    private static final float WARP_INTENSITY = 1.0f;
    private static final float SATURATION = 1.5f;
    private static final float DITHER = 0.008f;
    // Match kawarp's BLUR_SIZE so our emulated blur lands at the same fraction of the source.
    private static final int SOFT_COVER_PX = 128; // blur base; box-blurred below for smoothness
    // 3 box passes at this radius give sigma ~12.5/128, matching kawarp's 8 Kawase passes
    // (sigma ~13/128). The old 0.18 factor blurred ~1.8x harder and left nothing to warp.
    private static final float BLUR_RADIUS_FRACTION = 0.095f;
    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / 30; // 30fps is plenty for slow warp

    // Faithful port of kawarp's DOMAIN_WARP + OUTPUT shaders (simplex-noise domain warp, vignette,
    // saturation, dithering). The 8 Kawase blur passes are replaced by the upfront downsample.
    private static final String AGSL =
            "uniform shader image;\n"
            + "uniform float2 iResolution;\n"
            + "uniform float iTime;\n"
            + "uniform float warpIntensity;\n"
            + "uniform float saturation;\n"
            + "uniform float dither;\n"
            + "uniform float3 tintColor;\n"
            + "uniform float tintIntensity;\n"
            + "uniform float contrast;\n"
            + "uniform float contrastPivot;\n"
            + "uniform float forceDarkAmount;\n"
            + "uniform float3 accentColorA;\n"
            + "uniform float3 accentColorB;\n"
            + "uniform float accentMix;\n"
            + "float3 mod289_3(float3 x){ return x - floor(x*(1.0/289.0))*289.0; }\n"
            + "float2 mod289_2(float2 x){ return x - floor(x*(1.0/289.0))*289.0; }\n"
            + "float3 permute(float3 x){ return mod289_3(((x*34.0)+1.0)*x); }\n"
            + "float snoise(float2 v){\n"
            + "  const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);\n"
            + "  float2 i = floor(v + dot(v, C.yy));\n"
            + "  float2 x0 = v - i + dot(i, C.xx);\n"
            + "  float2 i1 = (x0.x > x0.y) ? float2(1.0,0.0) : float2(0.0,1.0);\n"
            + "  float4 x12 = x0.xyxy + C.xxzz;\n"
            + "  x12.xy -= i1;\n"
            + "  i = mod289_2(i);\n"
            + "  float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));\n"
            + "  float3 m = max(0.5 - float3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);\n"
            + "  m = m*m; m = m*m;\n"
            + "  float3 x = 2.0*fract(p*C.www)-1.0;\n"
            + "  float3 h = abs(x)-0.5;\n"
            + "  float3 ox = floor(x+0.5);\n"
            + "  float3 a0 = x-ox;\n"
            + "  m *= 1.79284291400159 - 0.85373472095314*(a0*a0 + h*h);\n"
            + "  float3 g;\n"
            + "  g.x = a0.x*x0.x + h.x*x0.y;\n"
            + "  g.yz = a0.yz*x12.xz + h.yz*x12.yw;\n"
            + "  return 130.0*dot(m,g);\n"
            + "}\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float2 uv = fragCoord / iResolution;\n"
            + "  float t = iTime * 0.08;\n"
            + "  float2 center = uv - 0.5;\n"
            + "  float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));\n"
            + "  float n1 = snoise(uv*0.35 + float2(t, t*0.7));\n"
            + "  float n2 = snoise(uv*0.35 + float2(-t*0.8, t*0.5) + float2(50.0,50.0));\n"
            + "  float n3 = snoise(uv*0.9 + float2(t*1.2, -t) + float2(100.0,0.0));\n"
            + "  float n4 = snoise(uv*0.9 + float2(-t, t*1.1) + float2(0.0,100.0));\n"
            + "  float2 warp = float2(n1*0.65 + n3*0.35, n2*0.65 + n4*0.35) * centerWeight;\n"
            + "  float2 warpedUv = clamp(uv + warp * warpIntensity, 0.0, 1.0);\n"
            + "  half4 c = image.eval(warpedUv * iResolution);\n"
            + "  float vignette = 1.0 - dot(center, center) * 0.3;\n"
            + "  c.rgb *= half(vignette);\n"
            + "  half luma = dot(c.rgb, half3(0.299, 0.587, 0.114));\n"
            // Force-dark tone curve runs FIRST so the saturation/contrast lift below survives it.
            // A soft Reinhard shoulder replaces the old hard 0.42 clamp, which crushed local slope
            // to ~0.15 at the bright end and rendered every cover as the same flat grey.
            + "  float darkLuma = max(0.84 * float(luma) / (float(luma) + 1.0), 0.05);\n"
            + "  float lumaScale = mix(1.0, darkLuma / max(float(luma), 0.001), forceDarkAmount);\n"
            + "  c.rgb = clamp(c.rgb * half(lumaScale), 0.0, 1.0);\n"
            + "  half tonedLuma = luma * half(lumaScale);\n"
            + "  c.rgb = clamp(mix(half3(tonedLuma), c.rgb, half(saturation)), 0.0, 1.0);\n"
            // Pivot the contrast stretch at the tone curve's midpoint; a 0.5 pivot on a background
            // that now lives around 0.2-0.35 would just darken it instead of separating it.
            + "  half3 pivot = half3(half(contrastPivot));\n"
            + "  c.rgb = clamp((c.rgb - pivot) * half(contrast) + pivot, 0.0, 1.0);\n"
            + "  half accentField = half(smoothstep(-0.65, 0.75, n1 * 0.7 + n2 * 0.3));\n"
            + "  half3 accent = mix(half3(accentColorA), half3(accentColorB), accentField);\n"
            + "  c.rgb = mix(c.rgb, accent, half(accentMix));\n"
            + "  c.rgb = mix(c.rgb, half3(tintColor), half(tintIntensity));\n"
            + "  half n = half(fract(sin(dot(fragCoord, float2(12.9898, 78.233)) + iTime) * 43758.5453));\n"
            + "  c.rgb += (n - 0.5) * dither;\n"
            + "  return c;\n"
            + "}\n";

    private final RuntimeShader shader;
    private final Paint paint = new Paint();
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private Bitmap softCover;
    private final Matrix coverMatrix = new Matrix();
    private boolean forceDark;
    private float lowContrastAccentMix = 0f;
    private int fallbackColorA = Color.rgb(46, 18, 26);
    private int fallbackColorB = Color.rgb(16, 26, 46);
    private int fallbackW = -1;
    private int fallbackH = -1;
    private boolean rendering = true;
    private long startNanos = 0;
    private long lastFrameNanos = 0;
    private long lastTimeUpdateNanos = 0;
    private float shaderTimeSeconds = 0f;
    private float speedMultiplier = 1f;
    private float targetSpeedMultiplier = 1f;
    private boolean motionEnabled = true;
    private boolean playing = true;
    private boolean frameCallbackPosted;
    // Beat reactivity: a percussive pulse timed to the track's own tempo (from Spotify's
    // audio-features endpoint - there is no raw audio signal available to analyze directly),
    // applied as a transient boost on top of the shader's steady-state warp intensity so the
    // background visibly "kicks" on each beat instead of just flowing at a constant rate.
    private static final float BEAT_BOOST = 0.65f;
    private static final float BEAT_DECAY = 8f;
    private float beatIntervalSeconds = 0f;
    // Real audio level (AudioReactiveController, a Visualizer on Spotify's own session) when
    // available; the BPM pulse below is the fallback for whenever it isn't (attach failure, no
    // session yet). Whichever is stronger wins on any given frame rather than picking one source
    // outright, so a real transient always shows through even with a tempo guess also ticking.
    private volatile float audioLevel = 0f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameCallbackPosted = false;
            if (!rendering) return;
            if (startNanos == 0) startNanos = frameTimeNanos;
            if (frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NS) {
                lastFrameNanos = frameTimeNanos;
                if (softCover != null) invalidate();
            }
            if (targetSpeedMultiplier == 0f && speedMultiplier < 0.01f) {
                speedMultiplier = 0f;
                return;
            }
            postFrameCallbackIfNeeded();
        }
    };

    public KawarpBackgroundView(Context context, boolean forceDark) {
        super(context);
        shader = new RuntimeShader(AGSL);
        shader.setFloatUniform("warpIntensity", WARP_INTENSITY);
        shader.setFloatUniform("dither", DITHER);
        shader.setFloatUniform("tintColor", 0.025f, 0.022f, 0.03f);
        shader.setFloatUniform("accentColorA", 0.18f, 0.07f, 0.10f);
        shader.setFloatUniform("accentColorB", 0.06f, 0.10f, 0.18f);
        setForceDark(forceDark);
        // No explicit hardware layer: we invalidate every frame, so a cached layer would just be
        // re-uploaded each tick (the lag we saw). The window canvas is already HW-accelerated.
    }

    @Override
    public View asView() {
        return this;
    }

    public void setForceDark(boolean forceDark) {
        this.forceDark = forceDark;
        // In-place uniform update: toggling dark mode must not recreate the view or drop shader
        // input, which caused blank backgrounds until the screen was reopened.
        shader.setFloatUniform("saturation", forceDark ? 1.08f : SATURATION);
        shader.setFloatUniform("tintIntensity", forceDark ? 0.18f : 0.0f);
        shader.setFloatUniform("contrast", forceDark ? 1.35f : 1.0f);
        shader.setFloatUniform("contrastPivot", forceDark ? 0.26f : 0.5f);
        shader.setFloatUniform("forceDarkAmount", forceDark ? 1.0f : 0.0f);
        applyAccentMix();
        invalidate();
    }

    /** Tempo of the current track in BPM; 0 (or any non-positive value) disables the beat pulse
     *  and falls back to the plain steady-state warp. */
    public void setBeatTempoBpm(float bpm) {
        beatIntervalSeconds = bpm > 0f ? 60f / bpm : 0f;
    }

    /** 0..1 real audio level for this instant; see AudioReactiveController. Safe to call from any
     *  thread (read on the next draw only). */
    public void setAudioLevel(float level0to1) {
        audioLevel = Math.max(0f, Math.min(1f, level0to1));
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        float target = motionEnabled && playing ? 1f : 0f;
        if (target == targetSpeedMultiplier) {
            // The shell re-asserts play state from updateState() on every frame tick. Resetting the
            // clock for those idempotent calls keeps every draw at the first sample and freezes the
            // shader at t=0.
            if (target > 0f) postFrameCallbackIfNeeded();
            return;
        }
        targetSpeedMultiplier = target;
        // Rebase only on a real resume so paused wall time cannot jump the shader clock.
        if (playing) {
            lastTimeUpdateNanos = 0L;
            postFrameCallbackIfNeeded();
        } else if (rendering) {
            postFrameCallbackIfNeeded();
        }
    }

    /** Selects moving Kawarp or the original frozen t=0 texture. */
    public void setMotionEnabled(boolean enabled) {
        if (motionEnabled == enabled) return;
        motionEnabled = enabled;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameCallbackPosted = false;
        lastTimeUpdateNanos = 0L;
        shaderTimeSeconds = 0f;
        targetSpeedMultiplier = enabled && playing ? 1f : 0f;
        speedMultiplier = targetSpeedMultiplier;
        if (targetSpeedMultiplier > 0f) postFrameCallbackIfNeeded();
        invalidate();
    }

    public void setPaletteColors(int[] colors) {
        if (colors == null || colors.length < 2) return;
        int colorA = colors[0];
        int colorB = ensurePaletteSeparation(colors[1], colorA);
        fallbackColorA = colorA;
        fallbackColorB = colorB;
        fallbackW = -1;
        fallbackH = -1;
        setFloatColor("accentColorA", colorA);
        setFloatColor("accentColorB", colorB);
        applyAccentMix();
        invalidate();
    }

    @Override
    public void updateImage(Bitmap art) {
        if (art == null) return;
        Bitmap soft = downsampleCover(art);
        lowContrastAccentMix = coverContrast(soft) < 0.11f ? 0.14f : 0.055f;
        BitmapShader bmp = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bmp.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        post(() -> {
            applyCoverMatrix(bmp, soft);
            softCover = soft;
            shader.setInputShader("image", bmp);
            applyAccentMix();
            invalidate();
        });
    }

    @Override
    public void pauseRendering() {
        rendering = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameCallbackPosted = false;
    }

    @Override
    public void resumeRendering() {
        if (rendering && lastFrameNanos != 0) return;
        rendering = true;
        lastTimeUpdateNanos = 0L;
        if (targetSpeedMultiplier > 0f || speedMultiplier >= 0.01f) {
            postFrameCallbackIfNeeded();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (rendering && (targetSpeedMultiplier > 0f || speedMultiplier >= 0.01f)) {
            postFrameCallbackIfNeeded();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameCallbackPosted = false;
        super.onDetachedFromWindow();
    }

    private void postFrameCallbackIfNeeded() {
        if (!rendering || frameCallbackPosted || !isAttachedToWindow()) return;
        frameCallbackPosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (softCover != null) {
            BitmapShader bmp = new BitmapShader(softCover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bmp.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            applyCoverMatrix(bmp, softCover);
            shader.setInputShader("image", bmp);
        }
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (softCover == null) {
            drawFallback(canvas, w, h);
            return;
        }
        try {
            advanceShaderTime();
            shader.setFloatUniform("iResolution", (float) w, (float) h);
            shader.setFloatUniform("iTime", shaderTimeSeconds);
            float reactivity = Math.max(beatPulse(), speedMultiplier >= 0.01f ? audioLevel : 0f);
            shader.setFloatUniform("warpIntensity", WARP_INTENSITY * (1f + BEAT_BOOST * reactivity));
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        } catch (Throwable ignored) {
            drawFallback(canvas, w, h);
        }
    }

    /**
     * Stretch the softened bitmap across the whole view, matching kawarp (which renders the cover
     * into a square BLUR_SIZE FBO and samples it over the full canvas). A center-crop "cover" fit
     * showed only the middle ~45% column of the art on a portrait phone — the flattest part of an
     * already-blurred image — which is what made the mobile background read as one uniform wash.
     * The aspect distortion is invisible at this blur level.
     */
    private void applyCoverMatrix(BitmapShader bmp, Bitmap soft) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        coverMatrix.reset();
        coverMatrix.setScale(w / (float) soft.getWidth(), h / (float) soft.getHeight());
        bmp.setLocalMatrix(coverMatrix);
    }

    private static Bitmap downsampleCover(Bitmap art) {
        int aw = Math.max(1, art.getWidth());
        int ah = Math.max(1, art.getHeight());
        float scale = SOFT_COVER_PX / (float) Math.max(aw, ah);
        int tw = Math.max(1, Math.round(aw * scale));
        int th = Math.max(1, Math.round(ah * scale));
        Bitmap small = Bitmap.createScaledBitmap(art, tw, th, true);
        // Real Gaussian-ish blur (3 box passes) on the medium bitmap so its texels vary SMOOTHLY.
        // Upscaling a tiny 24px image bilinearly produced faceted/jagged gradients; a blurred 128px
        // base reads as a smooth wash once the shader linearly upsamples + warps it. The radius is
        // tuned to kawarp's Kawase sigma, not "as soft as possible" — over-blurring leaves the warp
        // nothing to displace and the whole background stops appearing to move.
        Bitmap blurred = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        boxBlur(blurred, Math.max(1, Math.round(SOFT_COVER_PX * BLUR_RADIUS_FRACTION)), 3);
        return blurred;
    }

    private void applyAccentMix() {
        float mix = lowContrastAccentMix > 0f ? lowContrastAccentMix : 0.07f;
        shader.setFloatUniform("accentMix", forceDark ? Math.min(0.24f, mix + 0.07f) : mix);
    }

    private void setFloatColor(String name, int color) {
        shader.setFloatUniform(name,
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f);
    }

    private static float coverContrast(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return 1f;
        int stepX = Math.max(1, bmp.getWidth() / 12);
        int stepY = Math.max(1, bmp.getHeight() / 12);
        float min = 1f;
        float max = 0f;
        for (int y = 0; y < bmp.getHeight(); y += stepY) {
            for (int x = 0; x < bmp.getWidth(); x += stepX) {
                int c = bmp.getPixel(x, y);
                float l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
        }
        return max - min;
    }

    private void drawFallback(Canvas canvas, int w, int h) {
        advanceShaderTime();
        if (fallbackW != w || fallbackH != h || fallbackPaint.getShader() == null) {
            fallbackPaint.setShader(new LinearGradient(0, 0, w, h,
                    fallbackColorA, fallbackColorB, Shader.TileMode.CLAMP));
            fallbackW = w;
            fallbackH = h;
        }
        canvas.drawRect(0, 0, w, h, fallbackPaint);
    }

    /** 1 right at each beat onset, decaying exponentially until the next one - a percussive
     *  "kick" shape rather than a smooth sine, so it reads as a beat rather than a slow wobble.
     *  0 whenever there's no tempo yet or the shader clock isn't advancing (paused). */
    private float beatPulse() {
        if (beatIntervalSeconds <= 0f || speedMultiplier < 0.01f) return 0f;
        float phase = (shaderTimeSeconds % beatIntervalSeconds) / beatIntervalSeconds;
        return (float) Math.exp(-phase * BEAT_DECAY);
    }

    private void advanceShaderTime() {
        long now = lastFrameNanos == 0 ? System.nanoTime() : lastFrameNanos;
        if (lastTimeUpdateNanos == 0) {
            lastTimeUpdateNanos = now;
            return;
        }
        float dt = Math.max(0f, Math.min(0.1f, (now - lastTimeUpdateNanos) / 1_000_000_000f));
        lastTimeUpdateNanos = now;
        float follow = 1f - (float) Math.exp(-dt * 4.5f);
        speedMultiplier += (targetSpeedMultiplier - speedMultiplier) * follow;
        shaderTimeSeconds += dt * speedMultiplier;
    }

    private static int ensurePaletteSeparation(int color, int reference) {
        float[] c = new float[3];
        float[] r = new float[3];
        Color.colorToHSV(color, c);
        Color.colorToHSV(reference, r);
        float hueDelta = Math.abs(c[0] - r[0]);
        hueDelta = Math.min(hueDelta, 360f - hueDelta);
        if (hueDelta >= 22f || Math.abs(c[2] - r[2]) >= 0.12f) return color;
        c[0] = (r[0] + 42f) % 360f;
        c[1] = Math.min(0.70f, Math.max(c[1], r[1]) + 0.10f);
        c[2] = Math.max(0.14f, Math.min(0.46f, r[2] + (forceDarkReference(r[2]) ? 0.14f : -0.14f)));
        return Color.HSVToColor(c);
    }

    private static boolean forceDarkReference(float value) {
        return value < 0.32f;
    }

    /** Separable box blur done in-place (cheap on the ~96px base, run once per track). */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 1 || h <= 1 || radius < 1) return;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int p = 0; p < passes; p++) {
            boxBlurPass(px, tmp, w, h, radius, true);  // horizontal -> tmp
            boxBlurPass(tmp, px, h, w, radius, true);   // vertical (transposed) -> px
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    // Blurs `src` along rows into `dst` AND transposes, so calling it twice blurs both axes.
    private static void boxBlurPass(int[] src, int[] dst, int w, int h, int radius, boolean transpose) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, r = 0, g = 0, b = 0;
            for (int x = -radius; x <= radius; x++) {
                int c = src[row + Math.max(0, Math.min(w - 1, x))];
                a += (c >>> 24); r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                int outC = ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                dst[x * h + y] = outC; // transposed write
                int add = src[row + Math.min(w - 1, x + radius + 1)];
                int sub = src[row + Math.max(0, x - radius)];
                a += (add >>> 24) - (sub >>> 24);
                r += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }
}
