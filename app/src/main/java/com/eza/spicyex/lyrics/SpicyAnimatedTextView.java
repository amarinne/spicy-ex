package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * TextView with the Spicy sung/unsung karaoke gradient. The gradient position is in Spicy's
 * -20..100 coordinate space (-20 fully unsung, 100 fully sung); glow nudges the sung edge
 * toward full white.
 */
public class SpicyAnimatedTextView extends TextView {
    private float gradientPosition = -20f;
    private float glow = 0f;
    // Cached shader: rebuilding a LinearGradient on every frame for every word (with a software
    // layer) was a major source of scroll/animation jank. Rebuild only when an input changes.
    private Shader cachedShader;
    private float shaderPos = Float.NaN;
    private float shaderGlow = Float.NaN;
    private float shaderBrightness = Float.NaN;
    private float shaderOffset = Float.NaN;
    private boolean shaderVertical;
    private int shaderWidth = -1;

    // Words/letters use horizontal fill; line-level rows can switch to vertical fill by setting.
    private boolean verticalGradient;
    private boolean contentGradient;
    private int containerGradientWidth = -1;
    private float containerGradientOffsetX = 0f;
    // Word/letter views inside a GlowFlexbox get their continuous halo drawn by the parent (no seam).
    // Standalone rows (line-level main, secondary romaji/translation, live card) have no such parent,
    // so they draw their own soft halo here instead — gated by setSelfGlow(true).
    private boolean selfGlow;
    private float brightnessMultiplier = 1f;
    private final float density;

    public SpicyAnimatedTextView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
    }

    /** Enable a self-drawn blur halo (for rows NOT inside a GlowFlexbox). */
    public void setSelfGlow(boolean enabled) {
        this.selfGlow = enabled;
    }

    public void setVerticalGradient(boolean vertical) {
        if (this.verticalGradient == vertical) return;
        this.verticalGradient = vertical;
        cachedShader = null;
    }

    public void setContentGradient(boolean enabled) {
        if (this.contentGradient == enabled) return;
        this.contentGradient = enabled;
        cachedShader = null;
    }

    /** Current glow strength (0..1), read by the parent GlowFlexbox to draw a continuous glow. */
    public float getGlow() {
        return glow;
    }

    /**
     * Multiplies the shader's sung/unsung alpha ramp. This keeps static states such as translation
     * dimming inside the same render path as animated lyrics instead of fighting TextView alpha.
     */
    public void setBrightnessMultiplier(float multiplier) {
        float bounded = Math.max(0f, Math.min(1f, multiplier));
        if (Math.abs(this.brightnessMultiplier - bounded) < 0.01f) return;
        this.brightnessMultiplier = bounded;
        cachedShader = null;
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    public void setGradientPosition(float gradientPosition, float glow) {
        boolean hadContainerGradient = containerGradientWidth > 0;
        containerGradientWidth = -1;
        if (!hadContainerGradient
                && Math.abs(this.gradientPosition - gradientPosition) < 0.5f
                && Math.abs(this.glow - glow) < 0.03f) {
            return;
        }
        this.gradientPosition = gradientPosition;
        this.glow = glow;
        if (selfGlow) updateSelfGlow();
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    /**
     * Draw a horizontal gradient in an ancestor container's coordinate space. This keeps synthetic
     * attach-to-word rows visually equivalent to one line-level TextView instead of repeating the
     * wash independently inside every word view.
     */
    public void setContainerGradientPosition(float gradientPosition, float glow, int containerWidth, float offsetX) {
        int safeWidth = Math.max(1, containerWidth);
        if (Math.abs(this.gradientPosition - gradientPosition) < 0.5f
                && Math.abs(this.glow - glow) < 0.03f
                && this.containerGradientWidth == safeWidth
                && Math.abs(this.containerGradientOffsetX - offsetX) < 0.5f) {
            return;
        }
        this.gradientPosition = gradientPosition;
        this.glow = glow;
        this.containerGradientWidth = safeWidth;
        this.containerGradientOffsetX = offsetX;
        if (selfGlow) updateSelfGlow();
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    private void updateSelfGlow() {
        setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private Shader resolveShader(int extent) {
        boolean containerSpace = !verticalGradient && containerGradientWidth > 0;
        int contentWidth = !containerSpace && !verticalGradient && contentGradient ? contentWidthPx(extent) : extent;
        int shaderExtent = containerSpace ? containerGradientWidth : contentWidth;
        float offset = containerSpace ? containerGradientOffsetX : 0f;
        if (cachedShader != null && shaderExtent == shaderWidth
                && Math.abs(gradientPosition - shaderPos) < 0.5f
                && Math.abs(glow - shaderGlow) < 0.03f
                && Math.abs(brightnessMultiplier - shaderBrightness) < 0.01f
                && Math.abs(offset - shaderOffset) < 0.5f
                && verticalGradient == shaderVertical) {
            return cachedShader;
        }
        // Spicy CSS parity (Mixed.css): --gradient-alpha 0.85 (sung), --gradient-alpha-end 0.35
        // (unsung). glow nudges the sung edge toward full white (desktop does this via text-shadow).
        int startAlpha = Math.round(255f * (0.85f + 0.15f * Math.max(0f, Math.min(1f, glow))) * brightnessMultiplier);
        int endAlpha = Math.round(255f * 0.35f * brightnessMultiplier);
        int sungColor = Color.argb(startAlpha, 255, 255, 255);
        int unsungColor = Color.argb(endAlpha, 255, 255, 255);
        float origin = verticalGradient ? getPaddingTop() : getPaddingLeft() - offset;
        float far = origin + shaderExtent;
        float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
        if (verticalGradient) { y0 = origin; y1 = far; } else { x0 = origin; x1 = far; }
        if (gradientPosition <= -19.5f) {
            cachedShader = new LinearGradient(x0, y0, x1, y1,
                    new int[]{unsungColor, unsungColor}, null, Shader.TileMode.CLAMP);
        } else if (gradientPosition >= 99.5f) {
            cachedShader = new LinearGradient(x0, y0, x1, y1,
                    new int[]{sungColor, sungColor}, null, Shader.TileMode.CLAMP);
        } else {
            float p0 = Math.max(0f, Math.min(1f, gradientPosition / 100f));
            float p1 = Math.max(p0 + 0.001f, Math.min(1f, (gradientPosition + 20f) / 100f));
            cachedShader = new LinearGradient(x0, y0, x1, y1,
                    new int[]{sungColor, unsungColor}, new float[]{p0, p1}, Shader.TileMode.CLAMP);
        }
        shaderPos = gradientPosition;
        shaderGlow = glow;
        shaderBrightness = brightnessMultiplier;
        shaderWidth = shaderExtent;
        shaderOffset = offset;
        shaderVertical = verticalGradient;
        return cachedShader;
    }

    private int contentWidthPx(int fallback) {
        android.text.Layout layout = getLayout();
        if (layout == null || layout.getLineCount() <= 0) return fallback;
        float width = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            width = Math.max(width, layout.getLineWidth(i));
        }
        return Math.max(1, Math.min(fallback, Math.round(width)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = getPaint();
        Shader oldShader = paint.getShader();
        int oldColor = paint.getColor();
        int extent = verticalGradient
                ? Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom())
                : Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        // Drive ALL states through a shader, never paint.setColor(): TextView.onDraw resets the
        // paint color to mCurTextColor before drawing the layout, which would silently discard the
        // sung/unsung alpha and render every word uniformly. A shader survives that reset.
        if (selfGlow) drawSelfGlow(canvas);
        paint.setShader(resolveShader(extent));
        // Word rows usually get their continuous halo from GlowFlexbox. Standalone line/secondary
        // text draws a glyph-only halo above; avoid TextView.setShadowLayer with shaders because
        // some Android render paths blur the view rectangle.
        super.onDraw(canvas);
        paint.setShader(oldShader);
        paint.setColor(oldColor);
    }

    private void drawSelfGlow(Canvas canvas) {
        float g = Math.max(0f, Math.min(1f, glow));
        if (g <= 0.02f) return;
        Layout layout = getLayout();
        if (layout == null) return;
        TextPaint paint = getPaint();
        Shader savedShader = paint.getShader();
        int savedColor = paint.getColor();
        int alpha = Math.round(255f * Math.min(0.9f, 0.85f * g));
        int glowColor = Color.argb(alpha, 255, 255, 255);
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setShadowLayer((5f + 13f * g) * density, 0f, 0f, glowColor);
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.clearShadowLayer();
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }
}
