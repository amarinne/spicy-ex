package com.eza.spicyex.lyrics;

/**
 * Pure touch-arbitration state machine for the fullscreen track-info artwork.
 *
 * <p>Exactly one action per interaction (spec E′ §6): a tap reveals the overlay, a double-tap
 * toggles play/pause exactly once, a horizontal drag drives the art translation, and anything else
 * resets. All Android inputs (slop, timeouts) are constructor parameters and time comes from an
 * injected clock, so this class is unit-testable with no framework dependency.
 *
 * <p>Callers feed raw touch progress: {@link #onDown(long)}, {@link #onMove(float, float)},
 * {@link #onUp()}, {@link #onCancel()}. Each call returns the single output the caller must honor;
 * {@link Output#DRAG_UPDATE} carries the live displacement via {@link #dragDxPx()}.
 */
public final class ArtGestureArbiter {
    /** Minimal clock so tests control time. */
    public interface Clock {
        long nowMs();
    }

    public enum Output {
        NONE,
        /** First tap finished inside slop: reveal the overlay play button. */
        REVEAL,
        /** Second tap inside the double-tap window: toggle play/pause exactly once. */
        TOGGLE,
        /** Horizontal drag in progress: apply {@link #dragDxPx()} as art translationX. */
        DRAG_UPDATE,
        /** Released past the left threshold: commit next track. */
        COMMIT_NEXT,
        /** Released past the right threshold: commit previous track. */
        COMMIT_PREV,
        /** Released inside the thresholds: animate the art back to rest. */
        SPRING_BACK,
        /** Cancelled/rotated/track-changed: snap art to rest and dismiss the overlay. */
        RESET
    }

    private enum State { IDLE, TAP_ARMED, DRAGGING }

    private final float touchSlopPx;
    private final long doubleTapTimeoutMs;
    private final float commitThresholdPx;
    private final Clock clock;

    private State state = State.IDLE;
    private long doubleTapArmedUntilMs = -1;
    private boolean secondTap;
    private float dragDxPx;

    public ArtGestureArbiter(float touchSlopPx, long doubleTapTimeoutMs, float commitThresholdPx,
            Clock clock) {
        this.touchSlopPx = Math.max(1f, touchSlopPx);
        this.doubleTapTimeoutMs = Math.max(0L, doubleTapTimeoutMs);
        this.commitThresholdPx = Math.max(1f, commitThresholdPx);
        this.clock = clock;
    }

    public Output onDown(long nowMs) {
        state = State.TAP_ARMED;
        secondTap = isDoubleTapCandidate(nowMs);
        dragDxPx = 0f;
        return Output.NONE;
    }

    /** True while a DOWN would continue a double-tap (used to protect toggle from cancel taps). */
    public boolean isDoubleTapCandidate(long nowMs) {
        return nowMs <= doubleTapArmedUntilMs;
    }

    public Output onMove(float dxTotalPx, float dyTotalPx) {
        if (state != State.TAP_ARMED && state != State.DRAGGING) return Output.NONE;
        if (state == State.DRAGGING) {
            dragDxPx = dxTotalPx;
            return Output.DRAG_UPDATE;
        }
        if (Math.abs(dxTotalPx) <= touchSlopPx && Math.abs(dyTotalPx) <= touchSlopPx) {
            return Output.NONE;
        }
        doubleTapArmedUntilMs = -1;
        if (Math.abs(dxTotalPx) > Math.abs(dyTotalPx)) {
            state = State.DRAGGING;
            dragDxPx = dxTotalPx;
            return Output.DRAG_UPDATE;
        }
        state = State.IDLE;
        return Output.NONE;
    }

    public Output onUp() {
        if (state == State.DRAGGING) {
            state = State.IDLE;
            doubleTapArmedUntilMs = -1;
            if (dragDxPx <= -commitThresholdPx) return Output.COMMIT_NEXT;
            if (dragDxPx >= commitThresholdPx) return Output.COMMIT_PREV;
            return Output.SPRING_BACK;
        }
        if (state != State.TAP_ARMED) return Output.NONE;
        state = State.IDLE;
        if (secondTap) {
            secondTap = false;
            doubleTapArmedUntilMs = -1;
            return Output.TOGGLE;
        }
        doubleTapArmedUntilMs = clock.nowMs() + doubleTapTimeoutMs;
        return Output.REVEAL;
    }

    public Output onCancel() {
        return reset();
    }

    /** External reset (rotation, track change, tap outside): snap visuals to rest. */
    public Output reset() {
        state = State.IDLE;
        secondTap = false;
        doubleTapArmedUntilMs = -1;
        dragDxPx = 0f;
        return Output.RESET;
    }

    /** Live displacement for {@link Output#DRAG_UPDATE}; 0 otherwise. */
    public float dragDxPx() {
        return state == State.DRAGGING ? dragDxPx : 0f;
    }
}
