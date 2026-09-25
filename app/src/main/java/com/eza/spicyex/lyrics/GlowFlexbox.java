package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.MaskFilter;
import android.graphics.Shader;
import android.text.Layout;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.flexbox.FlexboxLayout;

/**
 * Word-row container that draws the lyric blur-glow ONCE on its own canvas, across every animated
 * word, instead of each word view drawing its own {@code setShadowLayer}. A per-word shadow is
 * clipped to that word's box, so adjacent words showed faint rectangular seams; drawing every word's
 * glyph-shadow onto this shared canvas lets the halos blend continuously (the desktop gets this free
 * from rendering a whole line as one element). Only words with glow &gt; 0 contribute, so it tracks
 * the active karaoke position. The real (gradient) word text is drawn on top by super.dispatchDraw.
 */
public class GlowFlexbox extends FlexboxLayout {
    private boolean glowLayerEnabled = true;
    // Apple active-line drop shadow intensity (0 = off, the shared-path default).
    private float lineShadowAlpha;
    // Blur filters cached by quantized sigma; sigma animates every frame and BlurMaskFilter is
    // immutable, so allocating one per word per frame would churn. Shared with the selfGlow path
    // in SpicyAnimatedTextView; only touched from the UI thread. Bounded to 32 entries to cap
    // native memory (each BlurMaskFilter is a few hundred bytes).
    private static final SparseArray<BlurMaskFilter> blurCache = new SparseArray<>();
    private static final int MAX_BLUR_CACHE = 32;

    // Adaptive sectioning state, armed by LyricsRowViewFactory for wrapping word rows when the
    // setting is on. Default (disarmed) keeps plain greedy Flexbox wrapping.
    private boolean adaptiveSectioning;
    private boolean[] adaptiveForbiddenBreaks = new boolean[0];
    private int[][] adaptiveKeepTogetherGroups = new int[0][];
    private long adaptiveSignature = Long.MIN_VALUE; // sentinel: nothing planned yet
    private boolean adaptiveWrapApplied;

    public GlowFlexbox(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /**
     * Arms or disarms adaptive wrapBefore planning for this row. {@code forbiddenBreakAfter[i]}
     * forbids a line break between direct child i and i+1; {@code keepTogetherGroups} holds
     * inclusive {first, last} child-index pairs whose members may split only when the whole group
     * is wider than the row (emergency overflow rule). Safe to call before the first measure;
     * grouping changes force a re-plan on the next measure pass.
     */
    public void setAdaptiveSectioning(boolean enabled, boolean[] forbiddenBreakAfter,
                                      int[][] keepTogetherGroups) {
        adaptiveSectioning = enabled;
        adaptiveForbiddenBreaks = forbiddenBreakAfter == null
                ? new boolean[0] : forbiddenBreakAfter.clone();
        adaptiveKeepTogetherGroups = keepTogetherGroups == null
                ? new int[0][] : cloneGroups(keepTogetherGroups);
        adaptiveSignature = Long.MIN_VALUE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int childCount = getChildCount();
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (!adaptiveSectioning || childCount == 0 || available <= 0) {
            if (adaptiveWrapApplied) applyWrapBefore(null);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // Children are WRAP_CONTENT, so their measured widths are natural regardless of the
        // currently applied wrapBefore flags; this measure doubles as the planning width probe.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        long signature = adaptiveSignature(available, childCount);
        if (signature == adaptiveSignature) return; // width, count and widths unchanged
        adaptiveSignature = signature;
        boolean[] plan = AdaptiveBreakPlanner.plan(childOuterWidths(childCount), available,
                adaptiveForbiddenBreaks, adaptiveKeepTogetherGroups);
        if (applyWrapBefore(plan)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); // bounded second measure
        }
    }

    private long adaptiveSignature(int available, int childCount) {
        long h = 1125899906842597L;
        h = 31L * h + available;
        h = 31L * h + childCount;
        for (int i = 0; i < childCount; i++) h = 31L * h + getChildAt(i).getMeasuredWidth();
        return h;
    }

    private int[] childOuterWidths(int childCount) {
        int[] widths = new int[childCount];
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int margins = 0;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                margins = mlp.leftMargin + mlp.rightMargin;
            }
            widths[i] = child.getMeasuredWidth() + margins;
        }
        return widths;
    }

    /** Applies the plan to direct children and returns true when any flag changed. */
    private boolean applyWrapBefore(boolean[] plan) {
        boolean changed = false;
        boolean anySet = false;
        for (int i = 0; i < getChildCount(); i++) {
            boolean want = plan != null && i < plan.length && plan[i];
            ViewGroup.LayoutParams lp = getChildAt(i).getLayoutParams();
            if (lp instanceof FlexboxLayout.LayoutParams) {
                FlexboxLayout.LayoutParams flp = (FlexboxLayout.LayoutParams) lp;
                if (flp.isWrapBefore() != want) {
                    flp.setWrapBefore(want);
                    changed = true;
                }
            }
            if (want) anySet = true;
        }
        adaptiveWrapApplied = anySet;
        return changed;
    }

    private static int[][] cloneGroups(int[][] groups) {
        int[][] out = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) out[i] = groups[i] == null ? null : groups[i].clone();
        return out;
    }

    static BlurMaskFilter blurFilter(float sigma) {
        int key = Math.max(1, Math.round(sigma * 4f)); // quantize to 0.25px steps
        synchronized (blurCache) {
            BlurMaskFilter filter = blurCache.get(key);
            if (filter == null) {
                filter = new BlurMaskFilter(key / 4f, BlurMaskFilter.Blur.NORMAL);
                blurCache.put(key, filter);
                if (blurCache.size() > MAX_BLUR_CACHE) {
                    blurCache.remove(blurCache.keyAt(0));
                }
            }
            return filter;
        }
    }

    public static void clearBlurCache() {
        synchronized (blurCache) {
            blurCache.clear();
        }
    }

    public void setGlowLayerEnabled(boolean enabled) {
        glowLayerEnabled = enabled;
        invalidate();
    }

    /** Apple line-shadow intensity for the word container (0 = off). */
    public void setLineShadowIntensity(float intensity) {
        float clamped = Math.max(0f, Math.min(1f, intensity));
        if (clamped == lineShadowAlpha) return;
        lineShadowAlpha = clamped;
        invalidate();
    }

    static boolean shouldDrawGlow(boolean enabled, float glow) {
        return enabled && glow > 0.02f;
    }

    // ---- per-visual-line zoom -------------------------------------------------------------
    // A wrapped lyric used to zoom as one block: every visual line grew at once, and the last lines
    // swung sideways/downwards the most. Instead, only the visual line being sung zooms, about its
    // own start edge; the lines around it just make room (half the extra height each way). Applied
    // as a draw-time canvas transform, so it never fights the per-word scale/lift properties the
    // syllable animation writes onto the child views.
    private final java.util.IdentityHashMap<View, Integer> childLine = new java.util.IdentityHashMap<>();
    private float[] lineTop = new float[0];
    private float[] lineBottom = new float[0];
    private float[] lineLeft = new float[0];
    private float[] lineRight = new float[0];
    private float[] lineZoom = new float[0];
    private float[] lineShift = new float[0];
    private boolean lineZoomOpposite;
    private boolean lineZoomActive;
    private static final int[] ZOOM_LOC = new int[2];

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        rebuildVisualLines();
        rebuildWordTransforms();
    }

    private void rebuildVisualLines() {
        wordTransform.clear();
        childLine.clear();
        java.util.ArrayList<float[]> lines = new java.util.ArrayList<>();
        float[] current = null;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            float top = child.getTop();
            float bottom = child.getBottom();
            // A new flex line starts where a child begins below the current line's middle.
            if (current == null || top >= (current[0] + current[1]) / 2f) {
                current = new float[]{top, bottom, child.getLeft(), child.getRight()};
                lines.add(current);
            } else {
                current[0] = Math.min(current[0], top);
                current[1] = Math.max(current[1], bottom);
                current[2] = Math.min(current[2], child.getLeft());
                current[3] = Math.max(current[3], child.getRight());
            }
            childLine.put(child, lines.size() - 1);
        }
        int n = lines.size();
        if (lineZoom.length != n) {
            lineZoom = new float[n];
            java.util.Arrays.fill(lineZoom, 1f);
            lineShift = new float[n];
        }
        lineTop = new float[n];
        lineBottom = new float[n];
        lineLeft = new float[n];
        lineRight = new float[n];
        for (int i = 0; i < n; i++) {
            float[] line = lines.get(i);
            lineTop[i] = line[0];
            lineBottom[i] = line[1];
            lineLeft[i] = line[2];
            lineRight[i] = line[3];
        }
    }

    public int visualLineCount() {
        return lineTop.length;
    }

    /** Visual line holding {@code descendant} (any view inside one of this box's children), or -1. */
    public int visualLineOf(View descendant) {
        View view = descendant;
        while (view != null && view.getParent() != this) {
            Object parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        Integer line = view == null ? null : childLine.get(view);
        return line == null ? -1 : line;
    }

    /**
     * Eases each visual line's zoom toward its target: {@code target} for {@code activeLine},
     * {@code heldTarget} for the lines before it (already sung - they keep their zoom until the
     * whole lyric line ends, instead of shrinking back as singing moves down), 1 for the rest.
     * Every target is capped so the zoomed line keeps {@code marginPx} from the screen edge.
     */
    public void stepLineZoom(int activeLine, float target, float heldTarget, float deltaSeconds,
                             boolean opposite, float marginPx) {
        int n = lineZoom.length;
        if (n == 0) return;
        lineZoomOpposite = opposite;
        float k = 1f - (float) Math.exp(-Math.max(0.001f, deltaSeconds) * 11f);
        boolean changed = false;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            float want = i == activeLine ? target : i < activeLine ? heldTarget : 1f;
            if (want > 1f) want = Math.min(want, fitLineZoom(i, opposite, marginPx));
            float goal = Math.max(1f, want);
            float next = lineZoom[i] + (goal - lineZoom[i]) * k;
            if (Math.abs(next - goal) < 0.0005f) next = goal;
            if (next != lineZoom[i]) {
                lineZoom[i] = next;
                changed = true;
            }
            if (next != 1f) any = true;
        }
        if (!changed) return;
        // Lines above a zoomed line move up by half its extra height, lines below move down.
        for (int i = 0; i < n; i++) {
            float shift = 0f;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                float extra = (lineBottom[j] - lineTop[j]) * (lineZoom[j] - 1f) / 2f;
                shift += j < i ? extra : -extra;
            }
            lineShift[i] = shift;
        }
        lineZoomActive = any;
        invalidate();
    }

    public boolean hasLineZoom() {
        return lineZoomActive;
    }

    // ---- word emphasis ------------------------------------------------------------------------
    // A held (slow) word swells a little while it is sung, about its own centre. Its neighbours
    // stay exactly where they are: squeezing them to keep the line width made every word between
    // two held ones slide back and forth as the stress moved along. Draw-time only.
    private static final float WORD_EMPHASIS_MAX = 0.07f;
    private final java.util.IdentityHashMap<View, Float> wordEmphasis = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<View, float[]> wordTransform = new java.util.IdentityHashMap<>();
    private boolean wordEmphasisActive;

    /**
     * Eases each child's emphasis toward {@code target} (0..1) for the child holding
     * {@code activeDescendant} and 0 for every other one.
     */
    public void stepWordEmphasis(View activeDescendant, float target, float deltaSeconds) {
        View active = activeDescendant;
        while (active != null && active.getParent() != this) {
            Object parent = active.getParent();
            active = parent instanceof View ? (View) parent : null;
        }
        // Only a word that is a small part of its line: in text without spaces (Japanese, Chinese)
        // one "word" box can be a whole phrase or line, and swelling that is a line zoom.
        if (active != null && !emphasisEligible(active)) active = null;
        if (active == null && !wordEmphasisActive) return;
        float rise = 1f - (float) Math.exp(-Math.max(0.001f, deltaSeconds) / 0.12f);
        float fall = 1f - (float) Math.exp(-Math.max(0.001f, deltaSeconds) / 0.28f);
        boolean changed = false;
        boolean any = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Float currentBox = wordEmphasis.get(child);
            float current = currentBox == null ? 0f : currentBox;
            float goal = child == active ? Math.max(0f, Math.min(1f, target)) : 0f;
            float next = current + (goal - current) * (goal > current ? rise : fall);
            if (Math.abs(next - goal) < 0.002f) next = goal;
            if (next != current) {
                changed = true;
                if (next == 0f) wordEmphasis.remove(child); else wordEmphasis.put(child, next);
            }
            if (next != 0f) any = true;
        }
        if (!changed) return;
        wordEmphasisActive = any;
        rebuildWordTransforms();
        invalidate();
    }

    private boolean emphasisEligible(View child) {
        Integer line = childLine.get(child);
        if (line == null || line >= lineTop.length) return false;
        int members = 0;
        for (int i = 0; i < getChildCount(); i++) {
            Integer at = childLine.get(getChildAt(i));
            if (at != null && at.equals(line)) members++;
        }
        float lineWidth = lineRight[line] - lineLeft[line];
        return members >= 3 && lineWidth > 0f && child.getWidth() <= lineWidth * 0.4f;
    }

    /** Per child [translateX, scale]: the held word scales in place; nothing else moves. */
    private void rebuildWordTransforms() {
        wordTransform.clear();
        if (!wordEmphasisActive) return;
        for (java.util.Map.Entry<View, Float> entry : wordEmphasis.entrySet()) {
            wordTransform.put(entry.getKey(), new float[]{0f, 1f + WORD_EMPHASIS_MAX * entry.getValue()});
        }
    }

    /** Applies {@code child}'s word emphasis: moved along its line and scaled about its baseline. */
    private void applyWordTransform(Canvas canvas, View child) {
        if (!wordEmphasisActive) return;
        float[] t = wordTransform.get(child);
        if (t == null) return;
        // About its own centre and baseline: grows up and out evenly, never sinking below its
        // neighbours or shoving them.
        canvas.scale(t[1], t[1], (child.getLeft() + child.getRight()) / 2f, child.getBottom());
    }

    private float fitLineZoom(int line, boolean opposite, float marginPx) {
        View root = getRootView();
        int screenWidth = root == null ? 0 : root.getWidth();
        float span = lineRight[line] - lineLeft[line];
        if (screenWidth <= 0 || span <= 0f) return Float.MAX_VALUE;
        getLocationInWindow(ZOOM_LOC);
        float scale = getScaleX();
        float originX = ZOOM_LOC[0] - getPivotX() * (1f - scale);
        if (opposite) {
            float right = originX + lineRight[line] * scale;
            return (right - marginPx) / (span * scale);
        }
        float left = originX + lineLeft[line] * scale;
        return (screenWidth - marginPx - left) / (span * scale);
    }

    /** Applies {@code child}'s visual-line zoom to {@code canvas}; false when it has none. */
    private boolean applyLineTransform(Canvas canvas, View child) {
        if (!lineZoomActive) return false;
        Integer line = childLine.get(child);
        if (line == null || line >= lineZoom.length) return false;
        float zoom = lineZoom[line];
        float shift = lineShift[line];
        if (zoom == 1f && shift == 0f) return false;
        canvas.translate(0f, shift);
        if (zoom != 1f) {
            canvas.scale(zoom, zoom, lineZoomOpposite ? lineRight[line] : lineLeft[line],
                    (lineTop[line] + lineBottom[line]) / 2f);
        }
        return true;
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (!lineZoomActive && !wordEmphasisActive) return super.drawChild(canvas, child, drawingTime);
        int save = canvas.save();
        applyLineTransform(canvas, child);
        applyWordTransform(canvas, child);
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return result;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlowLayer(canvas, this);
        if (lineShadowAlpha > 0.02f) drawShadowLayer(canvas, this);
        super.dispatchDraw(canvas);
    }

    private void drawGlowLayer(Canvas canvas, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int save = canvas.save();
            if (parent == this) {
                applyLineTransform(canvas, child);
                applyWordTransform(canvas, child);
            }
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            if (child instanceof SpicyAnimatedTextView) {
                drawWordGlow(canvas, (SpicyAnimatedTextView) child, 0f, 0f);
            } else if (child instanceof ViewGroup) {
                drawGlowLayer(canvas, (ViewGroup) child);
            }
            canvas.restoreToCount(save);
        }
    }

    /** Apple shadow pass: blurred dark glyph copies beneath the word container's children. */
    private void drawShadowLayer(Canvas canvas, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int save = canvas.save();
            if (parent == this) {
                applyLineTransform(canvas, child);
                applyWordTransform(canvas, child);
            }
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            if (child instanceof SpicyAnimatedTextView) {
                drawWordShadow(canvas, (SpicyAnimatedTextView) child);
            } else if (child instanceof ViewGroup) {
                drawShadowLayer(canvas, (ViewGroup) child);
            }
            canvas.restoreToCount(save);
        }
    }

    private void drawWordShadow(Canvas canvas, SpicyAnimatedTextView tv) {
        Layout layout = tv.getLayout();
        if (layout == null) return;
        TextPaint paint = tv.getPaint();
        int savedColor = paint.getColor();
        Shader savedShader = paint.getShader();
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(55f * lineShadowAlpha);
        paint.setShader(null);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        paint.setMaskFilter(blurFilter(16f * paint.getTextSize() / 48f));
        int save = canvas.save();
        canvas.translate(tv.getTotalPaddingLeft(), tv.getTotalPaddingTop() + paint.getTextSize() * 0.06f);
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }

    private void drawWordGlow(Canvas canvas, SpicyAnimatedTextView tv, float x, float y) {        float g = Math.max(0f, Math.min(1f, tv.getGlow()));
        if (!shouldDrawGlow(glowLayerEnabled, g)) return;
        Layout layout = tv.getLayout();
        if (layout == null) return;
        TextPaint paint = tv.getPaint();
        int savedColor = paint.getColor();
        Shader savedShader = paint.getShader();
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(255f * 0.35f * g);
        int glowColor = Color.argb(alpha, 255, 255, 255);
        // CSS-equivalent of desktop's `text-shadow: 0 0 (4+2g)px rgba(255,255,255,.35g)`: a blurred
        // copy of the glyphs only — no sharp underlay. CSS blur radius r means Gaussian sigma r/2,
        // and desktop's r is 4-6px against a ~48px reference font, so sigma scales with text size.
        float sigma = (2f + g) * paint.getTextSize() / 48f;
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setMaskFilter(blurFilter(sigma));
        int save = canvas.save();
        canvas.translate(x + tv.getTotalPaddingLeft(), y + tv.getTotalPaddingTop());
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
}
