package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.View;
import androidx.annotation.RequiresApi;

/** A high-precision Kawase texture animated with the desktop Kawarp equations. All renderer state is UI-thread. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class AmbientArtworkBackgroundView extends View implements AmbientBackgroundLayer {
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

    private final RuntimeShader shader = new RuntimeShader(AGSL);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fallback = new Paint(Paint.DITHER_FLAG);
    private Bitmap texture;
    private boolean enabled = true, moving = true, playing = true, posted;
    private long lastFrame;
    private double elapsedSeconds;
    private int colorA = Color.rgb(30,21,18), colorB = Color.rgb(16,15,16);
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

    public AmbientArtworkBackgroundView(Context context, boolean dark) {
        super(context);
        shader.setFloatUniform("brightness", 1f);
        setForceDark(dark);
    }

    public View asView() { return this; }
    public void setForceDark(boolean dark) {
        shader.setFloatUniform("dark", dark ? 1f : 0f);
        invalidate();
    }
    public void setDarkening(float brightness, ColorFilter filter) {
        shader.setFloatUniform("brightness", brightness);
        // Animated shader receives brightness directly; only the fallback canvas needs a filter.
        fallback.setColorFilter(filter);
        invalidate();
    }
    public void setPaletteColors(int[] colors) {
        if (colors == null || colors.length < 2) return;
        colorA = colors[0]; colorB = colors[colors.length-1];
        updateFallback(); invalidate();
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
     */
    public void updateImage(Bitmap prepared, AmbientArtworkProfile profile) {
        if (prepared == null || prepared.isRecycled()) return;
        BitmapShader input = new BitmapShader(prepared, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        shader.setInputShader("image", input);
        texture = prepared;
        // Dropping references lets RenderThread finish using the previous texture before collection.
        paint.setShader(shader);
        schedule(); invalidate();
    }

    public void release() {
        enabled = false; schedule();
        paint.setShader(null);
        shader.setInputShader("image", new LinearGradient(0,0,1,1,Color.BLACK,Color.BLACK,Shader.TileMode.CLAMP));
        texture = null;
        invalidate();
    }
    private boolean animating() {
        return enabled && moving && playing && !thermalThrottled && texture != null
                && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
    }
    private void schedule() {
        if (!animating()) {
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
        // Uniform tracks the offscreen surface's own size, not the view's - the shader's uv
        // mapping (p / resolution) only needs to span 0..1 across whatever it's drawn onto.
        shader.setFloatUniform("resolution", lowW, lowH);
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
        if (texture != null) {
            shader.setFloatUniform("time", (float) elapsedSeconds);
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
        canvas.drawRect(0,0,getWidth(),getHeight(),texture == null ? fallback : paint);
    }
}
