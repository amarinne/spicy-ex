package com.eza.spicyex.lyrics;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * A ScrollView that rubber-bands at its ends the way iOS lists do, instead of stopping dead.
 *
 * <p>The ScrollView itself never scrolls past its range. The stretch is a separate offset (this
 * view's translation), driven by the finger distance travelled past the edge through UIKit's
 * rubber-band curve, {@code (1 - 1 / (x * 0.55 / d + 1)) * d}: it follows the finger closely at
 * first and gets steadily heavier, with no hard stop. That distance is kept as a float and the
 * whole gesture is measured in screen coordinates, so the stretch moves continuously rather than
 * in rounded steps. On release, and when a fling runs into an edge carrying speed, a critically
 * damped spring brings the offset home - the fling hands the spring its velocity, so the list
 * visibly carries past the end before settling back.
 *
 * <p>While part of a drag is being spent on the stretch, the ScrollView is fed coordinates with
 * that part withheld, so it neither scrolls during the stretch nor jumps when the finger turns
 * back into the content.
 *
 * <p>Apple Music style only. {@link #setElasticEnabled(boolean)} off makes every override delegate
 * straight to {@link ScrollView} and lifts the end limit, so the other animation styles keep the
 * plain scrolling they had before this class existed.
 */
public class ElasticScrollView extends ScrollView {
    private static final float RUBBER_BAND = 0.55f;
    /** Spring response, UIKit-style: the time the settle mostly takes. */
    private static final float SPRING_RESPONSE_SEC = 0.4f;
    private static final float OMEGA = (float) (2d * Math.PI / SPRING_RESPONSE_SEC);
    private static final float MAX_BOUNCE_VELOCITY = 5000f;

    /** Off for every animation style but Apple Music: no rubber band, no end limit. */
    private boolean elasticEnabled = true;

    /** Finger distance spent past an edge: positive = pulled down at the top. */
    private float pull;
    /** Finger travel withheld from ScrollView during this gesture. */
    private float withheld;
    private float lastRawY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float localMinusRaw;
    private boolean touching;

    private float offset;
    private float springVelocity;
    private boolean springing;
    private long lastSpringNanos;

    private boolean flinging;
    /** Furthest scroll position the content may rest at; below the real range when the owner
     *  wants the end short of the last pixel (the last lyric at its focus point). */
    private int scrollEndLimit = Integer.MAX_VALUE;
    private float scrollVelocity;
    private long lastScrollNanos;

    /** Scroll speed (px/s) the list still had when a finger landed on it mid-fling. */
    private float carryVelocity;
    private final int maxFlingVelocity;

    public ElasticScrollView(Context context) {
        super(context);
        maxFlingVelocity = android.view.ViewConfiguration.get(context).getScaledMaximumFlingVelocity();
    }

    /**
     * Content dissolving into the background over the top and bottom {@code px} of the visible
     * area, rather than being cut at the edge. Drawn as an alpha mask over this view's own layer,
     * so it fades whatever is behind the lyrics in, per pixel - each visual line on its own as it
     * crosses - and costs nothing when both are 0.
     */
    public void setEdgeFade(int topPx, int bottomPx) {
        topPx = Math.max(0, topPx);
        bottomPx = Math.max(0, bottomPx);
        if (topPx == edgeFadeTop && bottomPx == edgeFadeBottom) return;
        edgeFadeTop = topPx;
        edgeFadeBottom = bottomPx;
        invalidate();
    }

    private int edgeFadeTop;
    private int edgeFadeBottom;
    private android.graphics.Paint edgeFadePaint;
    private android.graphics.LinearGradient edgeFadeShader;
    private final android.graphics.Matrix edgeFadeMatrix = new android.graphics.Matrix();

    @Override
    public void draw(android.graphics.Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if ((edgeFadeTop <= 0 && edgeFadeBottom <= 0) || w <= 0 || h <= 0) {
            super.draw(canvas);
            return;
        }
        if (edgeFadePaint == null) {
            edgeFadePaint = new android.graphics.Paint();
            edgeFadePaint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_OUT));
            // A unit-length ramp, stretched to the current length by the shader matrix. Eased
            // rather than linear: the line thins out gently first and only vanishes right at
            // the edge, which reads softer than an even fade.
            edgeFadeShader = new android.graphics.LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0xFF000000, 0xB0000000, 0x60000000, 0x24000000, 0x08000000, 0x00000000},
                    new float[]{0f, 0.18f, 0.4f, 0.62f, 0.82f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            edgeFadePaint.setShader(edgeFadeShader);
        }
        int top = getScrollY();
        int save = canvas.saveLayer(0, top, w, top + h, null);
        super.draw(canvas);
        if (edgeFadeTop > 0) {
            edgeFadeMatrix.setScale(1f, edgeFadeTop);
            edgeFadeMatrix.postTranslate(0f, top);
            edgeFadeShader.setLocalMatrix(edgeFadeMatrix);
            canvas.drawRect(0, top, w, top + edgeFadeTop, edgeFadePaint);
        }
        if (edgeFadeBottom > 0) {
            // The same ramp flipped, its edge at the bottom.
            edgeFadeMatrix.setScale(1f, -edgeFadeBottom);
            edgeFadeMatrix.postTranslate(0f, top + h);
            edgeFadeShader.setLocalMatrix(edgeFadeMatrix);
            canvas.drawRect(0, top + h - edgeFadeBottom, w, top + h, edgeFadePaint);
        }
        canvas.restoreToCount(save);
    }

    /** A finger landing on a list that is still coasting remembers how fast it was going. */
    private void captureCarry(MotionEvent ev) {
        long age = System.nanoTime() - lastScrollNanos;
        carryVelocity = lastScrollNanos != 0L && age < 70_000_000L ? scrollVelocity : 0f;
    }

    /** Apple Music enables the rubber band and the end limit; every other style turns both off. */
    public void setElasticEnabled(boolean enabled) {
        if (elasticEnabled == enabled) return;
        elasticEnabled = enabled;
        if (!enabled) {
            if (offset != 0f) setOffset(0f);
            springing = false;
            flinging = false;
            scrollEndLimit = Integer.MAX_VALUE;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) captureCarry(ev);
        if (!elasticEnabled) return super.onInterceptTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) beginGesture(ev);
        else if (action == MotionEvent.ACTION_POINTER_UP) onActivePointerUp(ev);
        // Like ScrollView interception, POINTER_DOWN does not select the newest pointer here;
        // onTouchEvent applies the newest-pointer rule. Just keep the active baseline current.
        else lastRawY = activeRawY(ev);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) captureCarry(ev);
        if (!elasticEnabled) return super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(ev);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // The other hand takes over like ScrollView's newest-pointer rule: measure from
            // where the finger now is, or its distance from the old one is read as one huge
            // step (the list jumped, or the pull was lost).
            onActivePointerDown(ev);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            onActivePointerUp(ev);
        } else if (action == MotionEvent.ACTION_MOVE) {
            float activeRaw = activeRawY(ev);
            float dy = activeRaw - lastRawY;
            lastRawY = activeRaw;
            trackPull(dy);
        } else {
            lastRawY = activeRawY(ev);
        }
        float originalY = ev.getY();
        // setLocation pins pointer 0 and shifts every pointer by the same delta, so the
        // compensated value must stay pointer-0 based: the active finger then reads as
        // rawActive + localMinusRaw - withheld and gaps are preserved. Feeding active raw
        // here would inject the inter-finger distance into ScrollView.
        ev.setLocation(ev.getX(), compensatedPointerZeroY(ev.getRawY(), localMinusRaw, withheld));
        boolean handled;
        try {
            handled = super.onTouchEvent(ev);
        } finally {
            ev.setLocation(ev.getX(), originalY);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            pull = 0f;
            withheld = 0f;
            if (offset != 0f) startSpring(0f);
        }
        return handled;
    }

    private void beginGesture(MotionEvent ev) {
        touching = true;
        flinging = false;
        springing = false;
        springVelocity = 0f;
        withheld = 0f;
        activePointerId = ev.getPointerId(0);
        lastRawY = ev.getRawY();
        // Screen-space from here on: the translation below moves this view under the finger.
        localMinusRaw = ev.getY() - ev.getRawY();
        pull = inverseRubber(offset);
    }

    // minSdk 27 has no MotionEvent.getRawY(int), so another finger's screen Y is rebuilt from
    // its local Y plus this event's raw/local gap, which is identical for every pointer.
    static float rawForIndex(float yAtIndex, float rawAtZero, float yAtZero) {
        return yAtIndex + rawAtZero - yAtZero;
    }

    // ScrollView hands the gesture to a surviving pointer when the active one lifts: index 1
    // when index 0 leaves, otherwise index 0.
    static int survivorIndexForPointerUp(int actionIndex, int pointerCount) {
        if (pointerCount <= 1) return 0;
        return actionIndex == 0 ? 1 : 0;
    }

    // Pointer-0 based event translation: setLocation(x, y) pins pointer 0 at (x, y) and moves
    // every other pointer by the same delta, so this keeps every pointer gap intact while
    // withholding the stretched part from ScrollView.
    static float compensatedPointerZeroY(float rawAtZero, float localMinusRaw, float withheld) {
        return rawAtZero + localMinusRaw - withheld;
    }

    private float rawYFor(MotionEvent ev, int pointerIndex) {
        return rawForIndex(ev.getY(pointerIndex), ev.getRawY(), ev.getY(0));
    }

    private float activeRawY(MotionEvent ev) {
        int index = ev.findPointerIndex(activePointerId);
        if (index < 0) {
            index = 0;
            if (ev.getPointerCount() > 0) activePointerId = ev.getPointerId(0);
        }
        return rawYFor(ev, index);
    }

    private void onActivePointerDown(MotionEvent ev) {
        int index = ev.getActionIndex();
        activePointerId = ev.getPointerId(index);
        lastRawY = rawYFor(ev, index);
    }

    private void onActivePointerUp(MotionEvent ev) {
        int actionIndex = ev.getActionIndex();
        if (ev.getPointerId(actionIndex) == activePointerId) {
            int survivor = survivorIndexForPointerUp(actionIndex, ev.getPointerCount());
            if (survivor >= ev.getPointerCount()) survivor = 0;
            activePointerId = ev.getPointerId(survivor);
        }
        lastRawY = activeRawY(ev);
    }

    /** Splits a finger step between the stretch and ordinary scrolling. */
    private void trackPull(float dy) {
        int y = getScrollY();
        int range = endRange();
        float side = pull != 0f ? Math.signum(pull)
                : (y <= 0 && dy > 0) ? 1f
                : (y >= range && dy < 0) ? -1f : 0f;
        if (side == 0f) return;
        float next = pull + dy;
        float spent;
        if (next * side < 0f) {
            // Turned back past the edge: the rest of this step scrolls the content.
            spent = -pull;
            pull = 0f;
        } else {
            spent = dy;
            pull = next;
        }
        withheld += spent;
        setOffset(rubber(pull));
    }

    /** True while the list is pulled past an end or springing back from one. */
    public boolean isStretched() {
        return springing || Math.abs(offset) > 0.5f;
    }

    @Override
    public void fling(int velocityY) {
        // Swiping again while the list is still coasting adds to its speed, as a list does under
        // alternating hands, instead of starting over from the new swipe's speed alone.
        float carry = carryVelocity;
        carryVelocity = 0f;
        if (carry != 0f && Math.signum(carry) == Math.signum((float) velocityY)) {
            float boosted = velocityY + carry * 0.6f;
            velocityY = Math.round(Math.max(-maxFlingVelocity, Math.min(maxFlingVelocity, boosted)));
        }
        flinging = elasticEnabled;
        super.fling(velocityY);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        long now = System.nanoTime();
        if (lastScrollNanos != 0L) {
            float dt = Math.max(1e-3f, (now - lastScrollNanos) / 1e9f);
            float v = (t - oldt) / dt;
            // A long gap means a fresh motion: do not average it with the last one's speed.
            scrollVelocity = scrollVelocity == 0f || dt > 0.1f ? v : scrollVelocity * 0.4f + v * 0.6f;
        }
        lastScrollNanos = now;
        if (!elasticEnabled) return;
        if (!flinging || touching) return;
        int range = endRange();
        boolean hitTop = t <= 0 && oldt > 0 && scrollVelocity < 0f;
        boolean hitBottom = t >= range && oldt < range && scrollVelocity > 0f;
        if (hitTop || hitBottom) {
            flinging = false;
            springVelocity = Math.max(-MAX_BOUNCE_VELOCITY,
                    Math.min(MAX_BOUNCE_VELOCITY, -scrollVelocity));
            startSpring(springVelocity);
        }
    }

    private void startSpring(float velocity) {
        springVelocity = velocity;
        lastSpringNanos = 0L;
        if (springing) return;
        springing = true;
        postOnAnimation(this::stepSpring);
    }

    private void stepSpring() {
        if (!springing || touching) {
            springing = false;
            return;
        }
        long now = System.nanoTime();
        float dt = lastSpringNanos == 0L ? 1f / 60f
                : Math.max(1e-4f, Math.min(0.05f, (now - lastSpringNanos) / 1e9f));
        lastSpringNanos = now;
        // Exact step of a critically damped spring toward 0.
        float x = offset;
        float v = springVelocity;
        float e = (float) Math.exp(-OMEGA * dt);
        float c = v + OMEGA * x;
        float nextX = (x + c * dt) * e;
        float nextV = (v - OMEGA * c * dt) * e;
        springVelocity = nextV;
        if (Math.abs(nextX) < 0.3f && Math.abs(nextV) < 4f) {
            setOffset(0f);
            springing = false;
            return;
        }
        setOffset(nextX);
        postOnAnimation(this::stepSpring);
    }

    private void setOffset(float value) {
        offset = value;
        setTranslationY(value);
    }

    private float rubber(float distance) {
        float d = Math.max(1f, getHeight());
        float x = Math.abs(distance);
        return Math.signum(distance) * (1f - 1f / (x * RUBBER_BAND / d + 1f)) * d;
    }

    private float inverseRubber(float stretched) {
        float d = Math.max(1f, getHeight());
        float s = Math.min(Math.abs(stretched), d * 0.999f);
        return Math.signum(stretched) * (d / (d - s) - 1f) * d / RUBBER_BAND;
    }

    /** A no-op while the rubber band is off: the end limit is Apple Music's alone. */
    public void setScrollEndLimit(int limit) {
        if (!elasticEnabled) return;
        scrollEndLimit = Math.max(0, limit);
        if (getScrollY() > endRange() && !touching) super.scrollTo(getScrollX(), endRange());
    }

    @Override
    public void scrollTo(int x, int y) {
        if (!elasticEnabled) {
            super.scrollTo(x, y);
            return;
        }
        super.scrollTo(x, Math.min(y, endRange()));
    }

    /** Drags and flings arrive here; clamp them at the end limit like at the real end. */
    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        if (elasticEnabled) {
            int end = endRange();
            if (scrollY > end) {
                scrollY = end;
                clampedY = true;
            }
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
    }

    /**
     * Aborts every in-flight motion so the owner can snap elsewhere immediately (tap Follow
     * while a fling is still coasting). Uses only public APIs: the native OverScroller is
     * really aborted, not just our flags. Works with the rubber band on or off.
     */
    public void stopScrolling() {
        // AOSP ScrollView.smoothScrollBy/To starts a new scroll when outside ANIMATED_SCROLL_GAP
        // (250ms), replacing the running fling, and aborts the scroller plus jumps when called
        // again inside the gap. Two successive zero-distance calls therefore deterministically
        // cancel momentum with no movement.
        super.smoothScrollBy(0, 0);
        super.smoothScrollBy(0, 0);
        flinging = false;
        carryVelocity = 0f;
        scrollVelocity = 0f;
        lastScrollNanos = 0L;
        springing = false;
        springVelocity = 0f;
        if (offset != 0f) setOffset(0f);
        pull = 0f;
        // Withheld offsets only exist to feed the ScrollView shifted coordinates mid-gesture;
        // clearing them under a finger would jump the content, so only drop them when idle.
        if (!touching) withheld = 0f;
    }

    private int endRange() {
        return Math.min(scrollRange(), scrollEndLimit);
    }

    private int scrollRange() {
        if (getChildCount() == 0) return 0;
        return Math.max(0, getChildAt(0).getHeight()
                - (getHeight() - getPaddingTop() - getPaddingBottom()));
    }
}
