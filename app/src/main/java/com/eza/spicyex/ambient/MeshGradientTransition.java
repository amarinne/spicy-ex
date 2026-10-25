package com.eza.spicyex.ambient;

/** Allocation-free palette and motion interpolation for one Mesh Gradient handoff. */
public final class MeshGradientTransition {
    public static final long DURATION_MS = 650L;
    private static final int FLOAT_COUNT = MeshGradientShader.MAX_COLORS * 4;

    private final float[] current = new float[FLOAT_COUNT];
    private final float[] from = new float[FLOAT_COUNT];
    private final float[] target = new float[FLOAT_COUNT];
    private int count;
    private float phase;
    private float phaseFrom;
    private float phaseTarget;
    private float speed = MeshGradientMotionProfile.NEUTRAL_SPEED;
    private float speedFrom = speed;
    private float speedTarget = speed;
    private long generation = Long.MIN_VALUE;
    private long startedNanos;
    private boolean active;

    public MeshGradientTransition(float[] initial, int initialCount, float initialPhase,
                                  float initialSpeed, long initialGeneration) {
        if (initial != null) System.arraycopy(initial, 0, current, 0, Math.min(initial.length, FLOAT_COUNT));
        count = clampCount(initialCount);
        phase = phaseFrom = phaseTarget = initialPhase;
        speed = speedFrom = speedTarget = initialSpeed;
        generation = initialGeneration;
    }

    /** Starts or retargets a handoff. Older generations cannot overwrite a newer track. */
    public boolean begin(long nextGeneration, long nowNanos, float nextPhase, float nextSpeed,
                         float[] nextColors, int nextCount) {
        if (nextGeneration < generation) return false;
        advance(nowNanos);
        System.arraycopy(current, 0, from, 0, current.length);
        for (int i = 0; i < target.length; i++) target[i] = 0f;
        if (nextColors != null) {
            System.arraycopy(nextColors, 0, target, 0, Math.min(nextColors.length, target.length));
        }
        count = clampCount(nextCount);
        phaseFrom = phase;
        speedFrom = speed;
        float phaseDelta = nextPhase - phase;
        while (phaseDelta > Math.PI) phaseDelta -= MeshGradientMotionProfile.TWO_PI;
        while (phaseDelta < -Math.PI) phaseDelta += MeshGradientMotionProfile.TWO_PI;
        phaseTarget = phase + phaseDelta;
        speedTarget = clamp(nextSpeed, MeshGradientMotionProfile.MIN_SPEED,
                MeshGradientMotionProfile.MAX_SPEED);
        generation = nextGeneration;
        startedNanos = nowNanos;
        active = true;
        return true;
    }

    public boolean advance(long nowNanos) {
        if (!active) return false;
        long elapsedMs = Math.max(0L, (nowNanos - startedNanos) / 1_000_000L);
        float linear = Math.min(1f, elapsedMs / (float) DURATION_MS);
        float eased = linear * linear * (3f - 2f * linear);
        for (int i = 0; i < current.length; i++) {
            current[i] = from[i] + (target[i] - from[i]) * eased;
        }
        phase = phaseFrom + (phaseTarget - phaseFrom) * eased;
        speed = speedFrom + (speedTarget - speedFrom) * eased;
        if (linear >= 1f) {
            active = false;
            System.arraycopy(target, 0, current, 0, current.length);
            phase = phaseTarget;
            speed = speedTarget;
        }
        return true;
    }

    /** Settles a handoff when playback, motion, or visibility cannot provide animation frames. */
    public boolean finish() {
        if (!active) return false;
        System.arraycopy(target, 0, current, 0, current.length);
        phase = phaseTarget;
        speed = speedTarget;
        active = false;
        return true;
    }

    public boolean accepts(long expectedGeneration) {
        return expectedGeneration == generation;
    }

    public float[] colors() { return current; }
    public int count() { return count; }
    public float phase() { return phase; }
    public float speed() { return speed; }
    public long generation() { return generation; }
    public boolean active() { return active; }

    private static int clampCount(int value) {
        return Math.max(0, Math.min(MeshGradientShader.MAX_COLORS, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
