package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * The like acknowledgement: a small firework around a heart (or star), drawn once, then removed.
 *
 * <p>Built on the pattern the well-known like buttons share (Twitter's heart, LikeButton's
 * CircleView + DotsView): a bubble of colour pops out and hollows into a ring, the icon springs
 * in on top of it, and sparks fly out. Here the sparks are a real little firework - each has its
 * own speed, drag and a touch of gravity, is drawn as a short streak along its path, twinkles,
 * and burns out - and nothing ever holds still: after landing, the heart beats twice (lub-dub)
 * and rises with a slight sway while it fades.
 *
 * <p>The big form is the double-tap one, around the finger. The small form plays around the
 * like button (which is the icon there): the pop and the sparks, no heart of its own.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstView extends View {
    private static final long BIG_MS = 1500L;
    private static final long SMALL_MS = 820L;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon;
    private int[] glitterPalette;
    private final boolean big;
    private final float cx;
    private final float cy;
    private final float radius;
    private final float density;
    private final int accent;
    private final float sway;
    private final Spark[] sparks;
    /** The second wave: glitter shed by the heart as it rises, so the scene never settles. */
    private final float[][] glitter;
    private float t;

    /** One firework spark: launch angle and speed, drag, colour, size, twinkle. */
    private static final class Spark {
        float angle;
        float speed;   // px per second at launch
        float drag;    // per second
        float delay;   // fraction of the whole
        float life;    // fraction of the whole
        float width;   // px
        int color;
        float twinkle; // phase offset
        boolean ember; // a round, slower spark; the rest are streaks
    }

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the pop and sparks alone
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstView(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        Random random = new Random();
        this.sway = (random.nextBoolean() ? 1f : -1f) * (0.6f + 0.4f * random.nextFloat());
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        // Soft, light tints: a pastel pink (or butter gold) rather than the saturated system
        // colour, so the burst reads as light rather than paint.
        int top = star ? Color.rgb(255, 232, 150) : Color.rgb(255, 150, 175);
        int bottom = star ? Color.rgb(255, 204, 92) : Color.rgb(255, 104, 138);
        this.accent = star ? Color.rgb(255, 222, 120) : Color.rgb(255, 138, 165);
        // Warm, related colours - a firework in the icon's own family, never a rainbow.
        int[] palette = star
                ? new int[]{Color.rgb(255, 244, 196), Color.rgb(255, 226, 130), Color.rgb(255, 206, 150),
                        Color.WHITE}
                : new int[]{Color.rgb(255, 170, 190), Color.rgb(255, 196, 212), Color.rgb(255, 205, 170),
                        Color.rgb(255, 234, 170), Color.WHITE};

        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setShader(new LinearGradient(0f, 3f, 0f, 21f, top, bottom, Shader.TileMode.CLAMP));
        sheenPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setShader(new LinearGradient(0f, 3f, 0f, 13f, 0x50FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);
        bubblePaint.setColor(accent);
        glowPaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(accent, 0x44), withAlpha(accent, 0x14), withAlpha(accent, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);

        // Two rings of sparks (LikeButton's idea), jittered so it reads as a burst, not a clock
        // face: an inner, quicker ring of streaks and an outer, wider one, plus a few embers.
        int count = big ? 22 : 12;
        float reach = radius * (big ? 2.1f : 1.9f);
        sparks = new Spark[count];
        float offset = random.nextFloat() * 360f;
        for (int i = 0; i < count; i++) {
            Spark s = new Spark();
            boolean outer = i % 2 == 0;
            s.ember = i % 5 == 4;
            s.angle = (float) Math.toRadians(offset + i * (360f / count) + (random.nextFloat() - 0.5f) * 14f);
            s.drag = s.ember ? 3.2f : 4.6f;
            // Speed so the spark coasts out to about its ring's radius (distance = v / drag).
            float distance = reach * (outer ? 1f : 0.72f) * (0.85f + 0.3f * random.nextFloat());
            s.speed = distance * s.drag;
            s.delay = (big ? 0.10f : 0.06f) + (outer ? 0f : 0.02f) + random.nextFloat() * 0.03f;
            s.life = (big ? 0.46f : 0.8f) * (s.ember ? 1.15f : 1f) * (0.85f + 0.3f * random.nextFloat());
            s.width = density * (s.ember ? 2.6f : outer ? 2.2f : 1.7f) * (big ? 1f : 0.8f);
            s.color = palette[random.nextInt(palette.length)];
            s.twinkle = random.nextFloat() * 6.28f;
            sparks[i] = s;
        }
        int shed = big ? 14 : 0;
        glitter = new float[shed][];
        for (int i = 0; i < shed; i++) {
            // {born at, x offset, y offset, drift x px/s, fall px/s, size px, colour index, twinkle}
            // Born around the heart's edge (not over it), fanning outward.
            double around = random.nextFloat() * Math.PI * 2;
            float ring = radius * (0.85f + 0.35f * random.nextFloat());
            glitter[i] = new float[]{
                    0.34f + 0.5f * i / Math.max(1, shed - 1) + random.nextFloat() * 0.03f,
                    (float) Math.cos(around) * ring,
                    (float) Math.sin(around) * ring * 0.9f,
                    (random.nextFloat() - 0.5f) * radius * 0.8f,
                    radius * (0.25f + 0.35f * random.nextFloat()),
                    density * (1.1f + 1.3f * random.nextFloat()),
                    random.nextInt(palette.length),
                    random.nextFloat() * 6.28f};
        }
        glitterPalette = palette;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the burst to {@code parent} and plays it; it removes itself at the end. */
    public void play(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(big ? BIG_MS : SMALL_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstView.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        float seconds = t * (big ? BIG_MS : SMALL_MS) / 1000f;
        drawBubble(canvas);
        drawSparks(canvas, seconds);
        if (big) {
            drawHeart(canvas);
            drawGlitter(canvas, seconds);
        }
    }

    /**
     * The pop: a disc of colour grows out, then is hollowed from the inside into a thin ring
     * that vanishes as it reaches full size.
     */
    private void drawBubble(Canvas canvas) {
        float grow = phase(t, 0f, big ? 0.13f : 0.3f);
        float hollow = phase(t, big ? 0.06f : 0.16f, big ? 0.24f : 0.45f);
        if (grow <= 0f || hollow >= 1f) return;
        float outer = radius * (big ? 1.2f : 1.05f) * decelerate(grow);
        float inner = outer * decelerate(hollow);
        float thickness = outer - inner;
        if (thickness <= 0.5f) return;
        bubblePaint.setStyle(Paint.Style.STROKE);
        bubblePaint.setStrokeWidth(thickness);
        bubblePaint.setAlpha(Math.round(255 * (0.5f + 0.35f * (1f - hollow))));
        canvas.drawCircle(cx, cy, inner + thickness / 2f, bubblePaint);
    }

    private void drawSparks(Canvas canvas, float seconds) {
        float total = (big ? BIG_MS : SMALL_MS) / 1000f;
        float gravity = radius * 1.4f;
        for (Spark s : sparks) {
            float local = (t - s.delay) / s.life;
            if (local <= 0f || local >= 1f) continue;
            float time = (t - s.delay) * total;
            // Linear drag with a little gravity, in closed form: fast out, easing to a hang,
            // then a slight fall - how a firework spark moves.
            float decay = (float) Math.exp(-s.drag * time);
            // Launched from the rim of the pop, not its centre: never streaks across the icon.
            float travel = radius * 0.55f + s.speed / s.drag * (1f - decay);
            float fall = gravity * time * time * 0.5f;
            float dx = (float) Math.cos(s.angle) * travel;
            float dy = (float) Math.sin(s.angle) * travel + fall;
            float x = cx + dx;
            float y = cy + dy;
            // Burning out: full, then shrinking and fading over the last half of its life.
            float burn = local < 0.45f ? 1f : 1f - (local - 0.45f) / 0.55f;
            float twinkle = local < 0.5f ? 1f
                    : 0.65f + 0.35f * (float) Math.sin(seconds * 38f + s.twinkle);
            int alpha = Math.round(255 * burn * twinkle);
            if (alpha <= 2) continue;
            sparkPaint.setColor(s.color);
            sparkPaint.setAlpha(alpha);
            if (s.ember) {
                sparkPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, s.width * (0.4f + 0.6f * burn), sparkPaint);
            } else {
                // A streak along the direction of travel, long while fast, short as it slows.
                float speedNow = s.speed * decay;
                float tail = Math.min(radius * 0.55f, speedNow * 0.035f) + s.width;
                float vx = (float) Math.cos(s.angle) * speedNow;
                float vy = (float) Math.sin(s.angle) * speedNow + gravity * time;
                float len = (float) Math.hypot(vx, vy);
                if (len < 0.001f) continue;
                sparkPaint.setStyle(Paint.Style.STROKE);
                sparkPaint.setStrokeWidth(s.width * (0.5f + 0.5f * burn));
                canvas.drawLine(x - vx / len * tail, y - vy / len * tail, x, y, sparkPaint);
            }
        }
    }

    /** Glitter from where the heart was at its birth, drifting out and down, twinkling. */
    private void drawGlitter(Canvas canvas, float seconds) {
        float total = BIG_MS / 1000f;
        float life = 0.26f;
        sparkPaint.setStyle(Paint.Style.FILL);
        for (float[] g : glitter) {
            float local = (t - g[0]) / life;
            if (local <= 0f || local >= 1f) continue;
            float age = (t - g[0]) * total;
            float[] from = heartCentre(g[0]);
            float x = from[0] + g[1] + g[3] * age;
            float y = from[1] + g[2] + g[4] * age;
            float glint = 0.55f + 0.45f * (float) Math.sin(seconds * 30f + g[7]);
            float fade = local < 0.2f ? local / 0.2f : 1f - (local - 0.2f) / 0.8f;
            int alpha = Math.round(255 * fade * glint);
            if (alpha <= 2) continue;
            sparkPaint.setColor(glitterPalette[(int) g[6]]);
            sparkPaint.setAlpha(alpha);
            float r = g[5] * (0.6f + 0.4f * fade);
            // A four-point glint rather than a dot: two thin crossing diamonds.
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(45f * (float) Math.sin(seconds * 3f + g[7]));
            canvas.drawCircle(0f, 0f, r * 0.55f, sparkPaint);
            sparkPaint.setStyle(Paint.Style.STROKE);
            sparkPaint.setStrokeWidth(r * 0.45f);
            canvas.drawLine(-r * 1.8f, 0f, r * 1.8f, 0f, sparkPaint);
            canvas.drawLine(0f, -r * 1.8f, 0f, r * 1.8f, sparkPaint);
            sparkPaint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
    }

    /** Where the heart's centre is at time {@code at}: its rise and sway. */
    private float[] heartCentre(float at) {
        float rise = radius * 1.0f * (float) Math.pow(phase(at, 0.2f, 1f), 1.6f);
        float drift = radius * 0.18f * sway * (float) Math.sin(Math.PI * 1.4f * phase(at, 0.2f, 1f));
        return new float[]{cx + drift, cy - rise};
    }

    /**
     * The heart: springs in over the pop, beats twice, and keeps rising with a slight sway while
     * it fades - never a still frame.
     */
    private void drawHeart(Canvas canvas) {
        // In as the pop hollows, so the heart is never lost inside a disc of its own colour.
        float in = phase(t, 0.11f, 0.40f);
        if (in <= 0f) return;
        float scale = 0.3f + 0.7f * spring(in);
        // Lub-dub: two quick beats after it lands.
        scale *= 1f + beat(t, 0.38f) * 0.10f + beat(t, 0.50f) * 0.07f;
        // Rising and swaying the whole time, gently at first, faster as it goes.
        float[] centre = heartCentre(t);
        float angle = 6f * sway * (float) Math.sin(Math.PI * 1.4f * phase(t, 0.25f, 1f));
        float fade = phase(t, 0.68f, 1f);
        float alpha = Math.min(1f, in * 4f) * (1f - fade * fade);
        scale *= 1f - 0.18f * fade;
        if (alpha <= 0.003f) return;

        float hx = centre[0];
        float hy = centre[1];
        glowPaint.setAlpha(Math.round(255 * alpha));
        float glow = radius * 1.5f * scale;
        canvas.save();
        canvas.translate(hx, hy);
        canvas.scale(glow, glow);
        canvas.drawCircle(0f, 0f, 1f, glowPaint);
        canvas.restore();

        float size = radius * 2f * scale;
        canvas.save();
        canvas.translate(hx, hy);
        canvas.rotate(angle);
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);
        canvas.save();
        canvas.translate(0f, 0.7f);
        shadowPaint.setAlpha(Math.round(0x30 * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();
        iconPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, iconPaint);
        sheenPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, sheenPaint);
        canvas.restore();
    }

    /** A single heartbeat pulse centred at {@code at}: up quickly, down a little slower. */
    private static float beat(float t, float at) {
        float d = t - at;
        if (d < -0.035f || d > 0.07f) return 0f;
        if (d < 0f) return 1f - (d / -0.035f) * (d / -0.035f);
        float p = d / 0.07f;
        return (1f - p) * (1f - p);
    }

    private static float phase(float t, float start, float end) {
        if (t <= start) return 0f;
        if (t >= end) return 1f;
        return (t - start) / (end - start);
    }

    private static float decelerate(float p) {
        return 1f - (1f - p) * (1f - p);
    }

    /** A lively spring from 0 to 1 (about 15% overshoot), settled by the end. */
    private static float spring(float p) {
        double damping = 0.45;
        double omega = 13.0;
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
