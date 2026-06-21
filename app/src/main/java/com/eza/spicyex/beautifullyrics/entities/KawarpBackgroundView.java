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
 * devices and falls back to {@link AnimatedBackgroundView} elsewhere.
 *
 * <p>Rather than running kawarp's 8 Kawase blur passes per frame on mobile, the cover is downsampled
 * once to a tiny bitmap and bilinearly upscaled by the shader — visually equivalent to a strong blur
 * at a fraction of the cost — leaving only the cheap per-frame warp on the GPU.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class KawarpBackgroundView extends View implements AmbientBackgroundLayer {
    // kawarp defaults: warpIntensity 1, saturation 1.5, dithering 0.008. blurPasses 8 is emulated by
    // the aggressive downsample below (smaller = softer / more abstract).
    private static final float WARP_INTENSITY = 1.18f;
    private static final float SATURATION = 1.5f;
    private static final float DITHER = 0.008f;
    private static final int SOFT_COVER_PX = 96; // blur base; box-blurred below for smoothness
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
            + "  float2 warpedUv = clamp(uv + warp * warpIntensity * 0.22, 0.0, 1.0);\n"
            + "  half4 c = image.eval(warpedUv * iResolution);\n"
            + "  float vignette = 1.0 - dot(center, center) * 0.3;\n"
            + "  c.rgb *= half(vignette);\n"
            + "  half luma = dot(c.rgb, half3(0.299, 0.587, 0.114));\n"
            + "  c.rgb = clamp(mix(half3(luma), c.rgb, half(saturation)), 0.0, 1.0);\n"
            + "  c.rgb = clamp((c.rgb - half3(0.5)) * half(contrast) + half3(0.5), 0.0, 1.0);\n"
            + "  float darkLuma = (float(luma) < 0.22) ? max(float(luma) * 0.96 + 0.018, 0.055) : min(float(luma), min(0.42, 0.07 + 0.34 * (1.0 - exp(-2.55 * float(luma)))));\n"
            + "  float lumaScale = mix(1.0, darkLuma / max(float(luma), 0.001), forceDarkAmount);\n"
            + "  c.rgb = clamp(c.rgb * half(lumaScale), 0.0, 1.0);\n"
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

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!rendering) return;
            if (startNanos == 0) startNanos = frameTimeNanos;
            if (frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NS) {
                lastFrameNanos = frameTimeNanos;
                if (softCover != null) invalidate();
            }
            Choreographer.getInstance().postFrameCallback(this);
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
        shader.setFloatUniform("contrast", forceDark ? 1.18f : 1.0f);
        shader.setFloatUniform("forceDarkAmount", forceDark ? 1.0f : 0.0f);
        applyAccentMix();
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
    }

    @Override
    public void resumeRendering() {
        if (rendering && lastFrameNanos != 0) return;
        rendering = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (rendering) Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
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
            shader.setFloatUniform("iResolution", (float) w, (float) h);
            shader.setFloatUniform("iTime", (startNanos == 0 ? 0f : (lastFrameNanos - startNanos) / 1_000_000_000f));
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        } catch (Throwable ignored) {
            drawFallback(canvas, w, h);
        }
    }

    /** Scale + center-crop (cover) the tiny softened bitmap to fill the view, in view-pixel space. */
    private void applyCoverMatrix(BitmapShader bmp, Bitmap soft) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float scale = Math.max(w / (float) soft.getWidth(), h / (float) soft.getHeight());
        float dx = (w - soft.getWidth() * scale) * 0.5f;
        float dy = (h - soft.getHeight() * scale) * 0.5f;
        coverMatrix.reset();
        coverMatrix.setScale(scale, scale);
        coverMatrix.postTranslate(dx, dy);
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
        // Upscaling a tiny 24px image bilinearly produced faceted/jagged gradients; a blurred 96px
        // base reads as a smooth wash once the shader linearly upsamples + warps it.
        Bitmap blurred = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        boxBlur(blurred, Math.max(1, Math.round(SOFT_COVER_PX * 0.18f)), 3);
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
        if (fallbackW != w || fallbackH != h || fallbackPaint.getShader() == null) {
            fallbackPaint.setShader(new LinearGradient(0, 0, w, h,
                    fallbackColorA, fallbackColorB, Shader.TileMode.CLAMP));
            fallbackW = w;
            fallbackH = h;
        }
        canvas.drawRect(0, 0, w, h, fallbackPaint);
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
