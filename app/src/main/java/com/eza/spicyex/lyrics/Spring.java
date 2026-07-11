package com.eza.spicyex.lyrics;

/** Closed-form damped spring (port of the Spicy 6 spring solver). */
public class Spring {
    private static final double EPS = 1e-5;
    private final float dampingRatio;
    private final float frequency;
    private float goal;
    private float position;
    private float velocity;

    public Spring(float startPosition, float frequency, float dampingRatio) {
        this.dampingRatio = dampingRatio;
        this.frequency = frequency;
        this.goal = startPosition;
        this.position = startPosition;
        this.velocity = 0f;
    }

    public void setGoal(float goal) {
        this.goal = goal;
    }

    public float step(float dt) {
        double d = dampingRatio;
        double f = frequency * (2d * Math.PI);
        double g = goal;
        double p = position;
        double v = velocity;
        if (d == 1d) {
            double q = Math.exp(-f * dt);
            double w = dt * q;
            double c0 = q + w * f;
            double c2 = q - w * f;
            double c3 = w * f * f;
            double o = p - g;
            p = o * c0 + v * w + g;
            v = v * c2 - o * c3;
        } else if (d < 1d) {
            double q = Math.exp(-d * f * dt);
            double c = Math.sqrt(1d - d * d);
            double i = Math.cos(dt * f * c);
            double j = Math.sin(dt * f * c);
            double z;
            if (c > EPS) z = j / c;
            else {
                double a = dt * f;
                z = a + ((a * a) * (c * c) * (c * c) / 20d - c * c) * (a * a * a) / 6d;
            }
            double y;
            if (f * c > EPS) y = j / (f * c);
            else {
                double b = f * c;
                y = dt + ((dt * dt) * (b * b) * (b * b) / 20d - b * b) * (dt * dt * dt) / 6d;
            }
            double o = p - g;
            p = (o * (i + z * d) + v * y) * q + g;
            v = (v * (i - z * d) - o * (z * f)) * q;
        } else {
            double c = Math.sqrt(d * d - 1d);
            double r1 = -f * (d + c);
            double r2 = -f * (d - c);
            double ec1 = Math.exp(r1 * dt);
            double ec2 = Math.exp(r2 * dt);
            double o = p - g;
            double co2 = (v - o * r1) / (2d * f * c);
            double co1 = ec1 * (o - co2);
            p = co1 + co2 * ec2 + g;
            v = co1 * r1 + co2 * ec2 * r2;
        }
        position = (float) p;
        velocity = (float) v;
        return position;
    }

    public boolean isAtRest(float positionEpsilon, float velocityEpsilon) {
        return Math.abs(position - goal) <= Math.max(0f, positionEpsilon)
                && Math.abs(velocity) <= Math.max(0f, velocityEpsilon);
    }

    public void snap(float value) {
        goal = value;
        position = value;
        velocity = 0f;
    }
}
