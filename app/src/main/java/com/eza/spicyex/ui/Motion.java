package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.util.WeakHashMap;
import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Shared motion tokens and helpers for module chrome (settings panel, dialogs, popups).
 *
 * <p>Disciplines enforced here (see artifacts/settings-ui-motion-audit.md §5-§6):
 * one-shot animators under a hard duration cap; hardware layers only for the transition
 * ({@code withLayer()}, owned by ViewPropertyAnimator); terminal detection via
 * {@code setListener} because {@code withEndAction()} is dropped on cancel; a per-view exit
 * state machine whose continuation runs exactly once with a reason — UI follow-up is
 * suppressed on host detach or platform dismissal.
 */
public final class Motion {
    public static final int FAST = 90;
    public static final int BASE = 200;
    public static final int EXIT = 160;
    public static final int SLOW = 260;
    public static final int REVEAL = 150;
    public static final int SWAP = 240;
    public static final int BACKDROP = 600;

    /** Transition feel values; must match {@code Settings.TRANSITION_FEEL} allowed values. */
    public static final String FEEL_INSTANT = "Instant";
    public static final String FEEL_FAST = "Fast";
    public static final String FEEL_RELAXED = "Relaxed";

    private static final String PREFS_NAME = SpotifyPlusConfig.PREFS_NAME;
    private static final String KEY_TRANSITION_FEEL = "lyrics_transition_feel";

    private static volatile String feel = FEEL_FAST;
    private static volatile boolean initialized;

    /**
     * Loads the persisted transition feel and keeps it live. Safe to call repeatedly;
     * only the first call registers the preference listener.
     */
    public static void initialize(android.content.Context context) {
        if (context == null) return;
        try {
            android.content.Context app = context.getApplicationContext();
            android.content.SharedPreferences prefs =
                    app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
            feel = sanitizeFeel(prefs.getString(KEY_TRANSITION_FEEL, FEEL_FAST));
            if (!initialized) {
                initialized = true;
                prefs.registerOnSharedPreferenceChangeListener(
                        (changed, key) -> {
                            if (KEY_TRANSITION_FEEL.equals(key)) {
                                feel = sanitizeFeel(changed.getString(key, FEEL_FAST));
                            }
                        });
            }
        } catch (Throwable ignored) {
            // Runtime chrome must never crash Spotify during startup.
        }
    }

    /** Overrides the feel in tests or when the caller already holds the config value. */
    public static void setTransitionFeel(String value) {
        feel = sanitizeFeel(value);
    }

    private static String sanitizeFeel(String value) {
        if (FEEL_INSTANT.equals(value) || FEEL_RELAXED.equals(value)) return value;
        return FEEL_FAST;
    }

    /**
     * Whether the OS allows animations at all (reduced motion / animator scale).
     * Returns {@link ValueAnimator#areAnimatorsEnabled()}, or true if that call throws.
     */
    public static boolean systemAnimationsEnabled() {
        try {
            return ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Whether module chrome transitions may run. Instant feel skips them while
     * preserving playback-driven lyric timing, which never consults this gate.
     */
    public static boolean animationsEnabled() {
        return systemAnimationsEnabled() && !FEEL_INSTANT.equals(feel);
    }

    /**
     * Scales a token duration for the current feel. Relaxed keeps current values;
     * Fast halves them; Instant collapses to zero (callers normally skip instead).
     */
    public static int dur(int base) {
        if (FEEL_INSTANT.equals(feel)) return 0;
        if (FEEL_RELAXED.equals(feel)) return base;
        return Math.max(1, base / 2);
    }

    public static long dur(long base) {
        return dur((int) base);
    }

    public static Interpolator decel() {
        return DECEL;
    }

    public static Interpolator accel() {
        return ACCEL;
    }

    private static final Interpolator DECEL = new DecelerateInterpolator();
    private static final Interpolator ACCEL = new AccelerateInterpolator();

    /** Why an exit animation stopped; decides whether follow-up work may run. */
    public enum Reason { NORMAL_END, HOST_DETACH, PLATFORM_DISMISS }

    private interface ExitState {
        void cancel();
    }

    private static final WeakHashMap<View, ExitState> EXITS = new WeakHashMap<>();

    private Motion() {}

    /** Card entrance: fade + slight scale-up. Safe to interrupt with an exit at any time. */
    public static void enterCard(View view) {
        if (view == null) return;
        cancelExit(view);
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.setAlpha(0f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(dur(BASE)).setInterpolator(DECEL)
                .withLayer()
                .start();
    }

    /**
     * Animated exit; runs {@code continuation} exactly once when the transition terminates.
     *
     * @param liveness supplies NORMAL_END when the dialog/host is still alive and showing;
     *                 any false result downgrades the terminal to a suppress-follow-up reason
     */
    public static void exitCardThen(View view, Liveness liveness, Runnable continuation) {
        if (view == null) {
            if (continuation != null) continuation.run();
            return;
        }
        if (EXITS.get(view) != null) {
            return; // EXITING: repeated close taps / duplicate requests are no-ops
        }
        view.animate().cancel(); // ENTERING (or stray animator): enter carries no continuation
        Exit exit = new Exit(view, liveness, continuation);
        EXITS.put(view, exit);
        exit.start();
    }

    /** Press feedback on small controls, matching NativeIconButtons' scale language. */
    public static void pressScale(View view) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(FAST).start();
    }

    public static void releasePress(View view) {
        view.animate().scaleX(1f).scaleY(1f).setDuration(2 * FAST).start();
    }

    /** Supplies true only when the owning surface should run follow-up UI work. */
    public interface Liveness {
        boolean alive();
    }

    private static final class Exit implements ExitState {
        private final View view;
        private final Liveness liveness;
        private final Runnable continuation;
        private ViewPropertyAnimator animator;
        private Reason cancelReason;
        private boolean terminal;

        Exit(View view, Liveness liveness, Runnable continuation) {
            this.view = view;
            this.liveness = liveness;
            this.continuation = continuation;
        }

        void start() {
            view.addOnAttachStateChangeListener(detachGuard);
            animator = view.animate();
            animator.alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .setDuration(dur(EXIT)).setInterpolator(ACCEL)
                    .withLayer()
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationCancel(Animator animation) {
                            terminal(cancelReason == null ? Reason.PLATFORM_DISMISS : cancelReason);
                        }

                        @Override public void onAnimationEnd(Animator animation) {
                            // Fires after cancel as well; terminal() keeps this exactly-once.
                            terminal(liveness != null && !liveness.alive()
                                    ? Reason.HOST_DETACH : Reason.NORMAL_END);
                        }
                    })
                    .start();
        }

        private final View.OnAttachStateChangeListener detachGuard =
                new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {}

                    @Override public void onViewDetachedFromWindow(View v) {
                        cancel(Reason.HOST_DETACH);
                    }
                };

        /** Exactly-once terminal; safe to invoke from cancel, end, and detach paths alike. */
        private void terminal(Reason reason) {
            if (terminal) return;
            terminal = true;
            view.removeOnAttachStateChangeListener(detachGuard);
            view.animate().setListener(null);
            if (EXITS.get(view) == this) EXITS.remove(view);
            boolean normal = reason == Reason.NORMAL_END;
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setAlpha(normal ? 0f : 1f);
            if (normal && continuation != null) continuation.run();
        }

        @Override public void cancel() {
            cancel(Reason.HOST_DETACH);
        }

        private void cancel(Reason reason) {
            if (terminal) return;
            cancelReason = reason;
            ViewPropertyAnimator active = animator;
            if (active != null) active.cancel();
            terminal(reason);
        }
    }

    private static void cancelExit(View view) {
        ExitState state = EXITS.get(view);
        if (state != null) state.cancel();
    }
}
