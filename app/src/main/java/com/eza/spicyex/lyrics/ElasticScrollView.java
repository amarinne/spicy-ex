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
 */
public class ElasticScrollView extends ScrollView {
    private static final float RUBBER_BAND = 0.55f;
    /** Spring response, UIKit-style: the time the settle mostly takes. */
    private static final float SPRING_RESPONSE_SEC = 0.4f;
    private static final float OMEGA = (float) (2d * Math.PI / SPRING_RESPONSE_SEC);
    private static final float MAX_BOUNCE_VELOCITY = 5000f;

    /** Finger distance spent past an edge: positive = pulled down at the top. */
    private float pull;
    /** Finger travel withheld from ScrollView during this gesture. */
    private float withheld;
    private float lastRawY;
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

    public ElasticScrollView(Context context) {
        super(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) beginGesture(ev);
        else lastRawY = ev.getRawY();
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        float rawY = ev.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(ev);
        } else if (action == MotionEvent.ACTION_MOVE && ev.getPointerCount() == 1) {
            float dy = rawY - lastRawY;
            lastRawY = rawY;
            trackPull(dy);
        }
        float originalY = ev.getY();
        ev.setLocation(ev.getX(), rawY + localMinusRaw - withheld);
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
        lastRawY = ev.getRawY();
        // Screen-space from here on: the translation below moves this view under the finger.
        localMinusRaw = ev.getY() - ev.getRawY();
        pull = inverseRubber(offset);
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

    @Override
    public void fling(int velocityY) {
        flinging = true;
        super.fling(velocityY);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        long now = System.nanoTime();
        if (lastScrollNanos != 0L) {
            float dt = Math.max(1e-3f, (now - lastScrollNanos) / 1e9f);
            float v = (t - oldt) / dt;
            scrollVelocity = scrollVelocity == 0f ? v : scrollVelocity * 0.4f + v * 0.6f;
        }
        lastScrollNanos = now;
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

    public void setScrollEndLimit(int limit) {
        scrollEndLimit = Math.max(0, limit);
        if (getScrollY() > endRange() && !touching) super.scrollTo(getScrollX(), endRange());
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, Math.min(y, endRange()));
    }

    /** Drags and flings arrive here; clamp them at the end limit like at the real end. */
    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        int end = endRange();
        if (scrollY > end) {
            scrollY = end;
            clampedY = true;
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
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
