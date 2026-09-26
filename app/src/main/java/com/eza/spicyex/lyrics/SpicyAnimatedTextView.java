package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * TextView with the Spicy sung/unsung karaoke gradient. The gradient position is in Spicy's
 * -40..100 coordinate space (-40 fully unsung, 100 fully sung); glow nudges the sung edge
 * toward full white.
 */
public class SpicyAnimatedTextView extends TextView {
    private float gradientPosition = LyricAnimations.GRADIENT_UNSUNG;
    private float glow = 0f;
    // Cached shader: rebuilding a LinearGradient on every frame for every word (with a software
    // layer) was a major source of scroll/animation jank. Rebuild only when an input changes.
    private Shader cachedShader;
    private float shaderPos = Float.NaN;
    private float shaderGlow = Float.NaN;
    private float shaderBrightness = Float.NaN;
    private float shaderOffset = Float.NaN;
    private boolean shaderVertical;
    private boolean shaderRtl;
    private int shaderWidth = -1;
    // Apple line shadow under the active line (0 = off, the shared-path default).
    private float lineShadowAlpha;

    // Words/letters use horizontal fill; line-level rows can switch to vertical fill by setting.
    private boolean verticalGradient;
    private boolean contentGradient;
    private int containerGradientWidth = -1;
    private float containerGradientOffsetX = 0f;
    private int containerGradientHeight = -1;
    private float containerGradientOffsetY = 0f;
    // Word/letter views inside a GlowFlexbox get their continuous halo drawn by the parent (no seam).
    // Standalone rows (line-level main, secondary romaji/translation, live card) have no such parent,
    // so they draw their own soft halo here instead — gated by setSelfGlow(true).
    private boolean selfGlow;
    private float brightnessMultiplier = 1f;

    public SpicyAnimatedTextView(Context context) {
        super(context);
    }

    /**
     * Without the theme's default TextView style. Resolving that style (AssetManager.applyStyle)
     * was most of the cost of building a lyric row: CJK lyrics get one view per character, and
     * the caller sets size, colour and typeface explicitly anyway.
     */
    public static SpicyAnimatedTextView unstyled(Context context) {
        return new SpicyAnimatedTextView(context, null, 0, 0);
    }

    private SpicyAnimatedTextView(Context context, android.util.AttributeSet attrs, int defStyleAttr,
                                  int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Enable a self-drawn blur halo (for rows NOT inside a GlowFlexbox). */
    public void setSelfGlow(boolean enabled) {
        this.selfGlow = enabled;
    }

    /*
     * The karaoke edge is a fixed physical size, in em, wherever it is drawn - Apple Music (and
     * AMLL, which reproduces it) use a soft edge about half a line tall. It used to be a
     * percentage of the span it crossed (40%, forced to 64% on the active Apple line), so on a
     * line-synced row - most non-English songs only have line timing - the edge was smeared across
     * half the line and the point actually being sung could not be seen.
     *
     * The edge has two parts: a short "hot" run at the sung position, drawn at full brightness so
     * the current point reads as a point, and a soft fade ahead of it into the unsung colour, which
     * keeps the hint of where the sweep is heading.
     */
    /** Full-brightness run just behind the sung position, in em. */
    private static final float FILL_HOT_EM = 0.3f;
    /** Soft fade from the sung position into the unsung colour, in em. */
    private static final float FILL_FADE_EM = 0.55f;

    /** Apple line shadow intensity (0 = off). */
    public void setLineShadow(float intensity) {
        float clamped = Math.max(0f, Math.min(1f, intensity));
        if (clamped == lineShadowAlpha) return;
        lineShadowAlpha = clamped;
        invalidate();
    }

    public void setVerticalGradient(boolean vertical) {
        if (this.verticalGradient == vertical) return;
        this.verticalGradient = vertical;
        cachedShader = null;
    }

    public boolean usesVerticalGradient() {
        return verticalGradient;
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
        boolean hadContainerGradient = containerGradientWidth > 0 || containerGradientHeight > 0;
        containerGradientWidth = -1;
        containerGradientHeight = -1;
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
        this.containerGradientHeight = -1;
        if (selfGlow) updateSelfGlow();
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }


    /** Draw a vertical gradient in an ancestor row's coordinate space across all stacked lines. */
    public void setContainerVerticalGradientPosition(float gradientPosition, float glow,
                                                     int containerHeight, float offsetY) {
        int safeHeight = Math.max(1, containerHeight);
        if (Math.abs(this.gradientPosition - gradientPosition) < 0.5f
                && Math.abs(this.glow - glow) < 0.03f
                && this.containerGradientHeight == safeHeight
                && Math.abs(this.containerGradientOffsetY - offsetY) < 0.5f) {
            return;
        }
        this.gradientPosition = gradientPosition;
        this.glow = glow;
        this.containerGradientWidth = -1;
        this.containerGradientHeight = safeHeight;
        this.containerGradientOffsetY = offsetY;
        if (selfGlow) updateSelfGlow();
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    private void updateSelfGlow() {
        setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private Shader resolveShader(int extent) {
        boolean horizontalContainerSpace = !verticalGradient && containerGradientWidth > 0;
        boolean verticalContainerSpace = verticalGradient && containerGradientHeight > 0;
        boolean horizontalRtl = !verticalGradient && getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int contentWidth = !horizontalContainerSpace && !verticalGradient && contentGradient ? contentWidthPx(extent) : extent;
        int shaderExtent = horizontalContainerSpace
                ? containerGradientWidth
                : verticalContainerSpace ? containerGradientHeight : contentWidth;
        float offset = horizontalContainerSpace
                ? containerGradientOffsetX
                : verticalContainerSpace ? containerGradientOffsetY : 0f;
        if (cachedShader != null && shaderExtent == shaderWidth
                && Math.abs(gradientPosition - shaderPos) < 0.5f
                && Math.abs(glow - shaderGlow) < 0.03f
                && Math.abs(brightnessMultiplier - shaderBrightness) < 0.01f
                && Math.abs(offset - shaderOffset) < 0.5f
                && verticalGradient == shaderVertical
                && horizontalRtl == shaderRtl) {
            return cachedShader;
        }
        // Spicy CSS parity (Mixed.css): --gradient-alpha 0.85 (sung), --gradient-alpha-end 0.35
        // (unsung). glow nudges the sung edge toward full white (desktop does this via text-shadow).
        // Use the current text color as the base for the gradient so the fill adapts to the
        // text color (white on dark, dark on light, or any custom color).
        int textColor = getCurrentTextColor();
        int baseR = Color.red(textColor);
        int baseG = Color.green(textColor);
        int baseB = Color.blue(textColor);
        int startAlpha = Math.round(255f * (0.85f + 0.15f * Math.max(0f, Math.min(1f, glow))) * brightnessMultiplier);
        int endAlpha = Math.round(255f * 0.35f * brightnessMultiplier);
        int sungColor = Color.argb(startAlpha, baseR, baseG, baseB);
        int hotColor = Color.argb(Math.round(255f * brightnessMultiplier), baseR, baseG, baseB);
        int unsungColor = Color.argb(endAlpha, baseR, baseG, baseB);
        float origin = verticalGradient
                ? (verticalContainerSpace ? -offset : getPaddingTop())
                : getPaddingLeft() - offset;
        float far = origin + shaderExtent;
        if (gradientPosition <= LyricAnimations.GRADIENT_UNSUNG + 0.5f) {
            cachedShader = solidShader(unsungColor);
        } else if (gradientPosition >= 99.5f) {
            cachedShader = solidShader(sungColor);
        } else {
            float textSize = Math.max(1f, getTextSize());
            float hot = FILL_HOT_EM * textSize;
            float fade = FILL_FADE_EM * textSize;
            float progress = (gradientPosition - LyricAnimations.GRADIENT_UNSUNG)
                    / LyricAnimations.GRADIENT_RANGE;
            // Distance of the sung position from the leading edge: starts one fade-width before
            // the text so nothing is lit at 0, and reaches the far end at 1.
            float edge = -fade + (shaderExtent + fade) * Math.max(0f, Math.min(1f, progress));
            float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
            if (verticalGradient) {
                y0 = origin + edge - hot;
                y1 = origin + edge + fade;
            } else if (horizontalRtl) {
                x0 = far - edge + hot;
                x1 = far - edge - fade;
            } else {
                x0 = origin + edge - hot;
                x1 = origin + edge + fade;
            }
            cachedShader = new LinearGradient(x0, y0, x1, y1,
                    new int[]{sungColor, hotColor, unsungColor},
                    new float[]{0f, hot / (hot + fade), 1f}, Shader.TileMode.CLAMP);
        }
        shaderPos = gradientPosition;
        shaderGlow = glow;
        shaderBrightness = brightnessMultiplier;
        shaderWidth = shaderExtent;
        shaderOffset = offset;
        shaderVertical = verticalGradient;
        shaderRtl = horizontalRtl;
        return cachedShader;
    }

    private static Shader solidShader(int color) {
        return new LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        cachedShader = null;
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
        if (lineShadowAlpha > 0.02f) drawLineShadow(canvas);
        paint.setShader(resolveShader(extent));
        // Word rows usually get their continuous halo from GlowFlexbox. Standalone line/secondary
        // text draws a glyph-only halo above; avoid TextView.setShadowLayer with shaders because
        // some Android render paths blur the view rectangle.
        FuriganaText.FuriganaSpan.onBeginDraw();
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
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(255f * 0.35f * g);
        int glowColor = Color.argb(alpha, 255, 255, 255);
        // Same render as GlowFlexbox: desktop's `text-shadow: 0 0 (4+2g)px rgba(255,255,255,.35g)`
        // — a blurred copy of the glyphs only, sigma scaled to the ~48px desktop reference font.
        float sigma = (2f + g) * paint.getTextSize() / 48f;
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setMaskFilter(GlowFlexbox.blurFilter(sigma));
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
        try {
            FuriganaText.FuriganaSpan.onBeginDraw();
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }

    /** Apple active-line drop shadow: a blurred dark copy of the glyphs beneath the text. */
    private void drawLineShadow(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;
        TextPaint paint = getPaint();
        Shader savedShader = paint.getShader();
        int savedColor = paint.getColor();
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(55f * lineShadowAlpha);
        paint.setShader(null);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        paint.setMaskFilter(GlowFlexbox.blurFilter(16f * paint.getTextSize() / 48f));
        int save = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop() + paint.getTextSize() * 0.06f);
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }
}
