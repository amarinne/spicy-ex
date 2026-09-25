package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;
import static com.eza.spicyex.hooks.NativeLyricsUtils.trackIdFromUri;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.R;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

/** Owns Spotify activity takeover, entry injection, keepalive, and native shell root mount. */
final class LyricsActivityTakeoverHook {
    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";
    private static final int TAG_NATIVE_SPICY_ROOT = 0x53504C53; // SPLS
    private static final int TAG_EXTRA_LYRICS_BUTTON = 0x53504C58; // SPLX
    private static final int TAG_MINI_PLAYER_LYRICS_BUTTON = 0x53504C4D; // SPLM
    private static final int TAG_COVERED_SIBLINGS = 0x53504C43; // SPLC
    private static final long KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS = 3500L;
    private static final long[] EXTRA_INJECTION_DELAYS_MS = {450L, 950L, 1400L, 2400L};
    private static final long MINI_PLAYER_STEADY_RETRY_MS = 3000L;
    private static final long MINI_PLAYER_STEADY_RETRY_MAX_MS = 10 * 60 * 1000L;

    private static final WeakHashMap<Activity, Long> EXPLICIT_LYRICS_EXIT_UNTIL_MS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> KEEP_LYRICS_ACTIVITY_UNTIL_MS = new WeakHashMap<>();
    // Exact-class method lookups bypass subclass overrides, so one Activity-level
    // back hook is not enough (see ensureLegacyBackCoverage). Each declaring
    // class+method pair is covered once per process.
    private static final Set<String> LEGACY_BACK_COVERED =
            Collections.synchronizedSet(new HashSet<>());
    // A back key observed at dispatch means a shortly-following finish is
    // user-initiated even when no onBackPressed override ran (a view consumed
    // the key and finished directly). Consumed-without-finish presses expire.
    private static final WeakHashMap<Activity, Long> BACK_PRESS_UNTIL_MS = new WeakHashMap<>();
    private static final long BACK_PRESS_EXIT_WINDOW_MS = 2500L;
    // Armed by our own entry button right before launching the lyrics activity; consumed when we mount.
    // Spotify's native lyric card launches the same activity without arming this, so it stays native.
    private static volatile boolean takeoverArmed = false;
    // True while our native lyrics screen is the active session. Survives activity recreation
    // (rotation/config change) so we re-mount instead of falling back to Spotify's native screen;
    // cleared on an explicit exit or a real (non-config-change) destroy.
    private static volatile boolean nativeLyricsSessionActive = false;

    private final NativeSpicyLyricsHook host;
    private final NowPlayingInjector nowPlayingInjector;
    private final WeakHashMap<Activity, ExtraInjectionRetry> extraInjectionRetries = new WeakHashMap<>();
    private final WeakHashMap<Activity, OnBackInvokedCallback> backCallbacks = new WeakHashMap<>();

    LyricsActivityTakeoverHook(NativeSpicyLyricsHook host, NowPlayingInjector nowPlayingInjector) {
        this.host = host;
        this.nowPlayingInjector = nowPlayingInjector;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookLyricsActivityLifecycle");
        XpHooks.findAfter(Activity.class, "onCreate", "takeover:Activity#onCreate", param -> {
            Activity activity = (Activity) param.thisObject;
            if (isLyricsFullscreenActivity(activity)) {
                // No system-back registration here: native Spotify screens stay untouched.
                // Registration happens only after takeover ownership is established.
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    XpLog.log(NativeSpicyLyricsHook.TAG
                            + " lyrics activity onCreate (takeover) " + activity.getClass().getName());
                }
                // else: opened via Spotify's native lyric card - leave Spotify's screen untouched.
            } else {
                scheduleExtraLyricsButtonInjection(activity);
            }
        }, android.os.Bundle.class);

        XpHooks.findAfter(Activity.class, "onResume", "takeover:Activity#onResume", param -> {
            Activity activity = (Activity) param.thisObject;
            References.setCurrentActivity(activity);
            if (isLyricsFullscreenActivity(activity)) {
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    ensureLegacyBackCoverage(activity);
                }
                // else: native lyric card opened Spotify's own screen - do not take over.
            } else {
                scheduleExtraLyricsButtonInjection(activity);
            }
        });

        XpHooks.findAfter(Activity.class, "onWindowFocusChanged", "takeover:Activity#onWindowFocusChanged",
                param -> {
                    if (!((boolean) param.args[0])) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!isLyricsFullscreenActivity(activity)) return;
                    // Explicit exit must not rearm while the old root still exists: focus
                    // transitions during the exit animation would otherwise remount/re-register.
                    if (isExplicitLyricsExit(activity)) {
                        unregisterSystemBackCallback(activity);
                        return;
                    }
                    if (activateNativeTakeover(activity)) {
                        ensureSystemBackCallback(activity);
                        ensureLegacyBackCoverage(activity);
                        mountNativeSpicyRoot(activity);
                    }
                }, boolean.class);

        XpHooks.findBefore(Activity.class, "onDestroy", "takeover:Activity#onDestroy", param -> {
            Activity activity = (Activity) param.thisObject;
            cancelExtraLyricsButtonInjection(activity);
            nowPlayingInjector.destroy(activity);
            if (!isLyricsFullscreenActivity(activity)) return;
            unregisterSystemBackCallback(activity);
            // Keep takeover state through every lyrics-page destroy. Spotify may report a
            // track-change rotation as a normal destroy, and the old content root can already
            // be detached before this hook runs. Non-lyrics onResume and explicit back clear it.
            removeNativeSpicyRoot(activity);
        });

        XpHooks.findAfter(Activity.class, "onPause", "takeover:Activity#onPause", param -> {
            Activity activity = (Activity) param.thisObject;
            cancelExtraLyricsButtonInjection(activity);
            nowPlayingInjector.stop(activity); // quiet the now-playing card ticker
        });

        XpHooks.findBefore(Activity.class, "onBackPressed", "takeover:Activity#onBackPressed", param -> {
            handleLegacyActivityBack((Activity) param.thisObject, param);
        });

        // Back keys reach views before any onBackPressed override runs: a view that
        // consumes KEYCODE_BACK and finishes directly would otherwise bypass the
        // explicit-exit marking while still hitting the finish suppression below.
        // Record only; Spotify's handling runs untouched.
        XpHooks.findBefore(Activity.class, "dispatchKeyEvent", "takeover:Activity#dispatchKeyEvent", param -> {
            handleActivityKeyForBack((Activity) param.thisObject, param);
        }, KeyEvent.class);

        XpHooks.findBefore(Activity.class, "finish", "takeover:Activity#finish", param -> {
            Activity activity = (Activity) param.thisObject;
            // A back press observed at dispatch promotes this finish to an
            // explicit exit (clearing the session) instead of suppressing it.
            if (isLyricsFullscreenActivity(activity) && consumeRecentBackPress(activity)) {
                markExplicitLyricsExit(activity);
            }
            if (!shouldKeepLyricsActivityOpen(activity)) return;
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " suppressed non-explicit lyrics activity finish to keep native renderer open");
            param.setResult(null);
        });
    }

    boolean isNativeSpicyEnabled(Activity activity) {
        try {
            return SpotifyPlusConfig.from(activity).get(Settings.NATIVE_SPICY_ENABLED);
        } catch (Throwable ignored) {
            return true;
        }
    }

    void markExplicitLyricsExit(Activity activity) {
        if (activity == null) return;
        nativeLyricsSessionActive = false; // user is leaving - end the session (next open stays native)
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            EXPLICIT_LYRICS_EXIT_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + 1200);
        }
        // End owned-back handling immediately so the exit animation/focus transition
        // cannot rearm it while the old root still exists. Destroy also unregisters.
        unregisterSystemBackCallback(activity);
    }

    private boolean isExplicitLyricsExit(Activity activity) {
        if (activity == null) return false;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Legacy back path shared by every onBackPressed declaring class (see
    // ensureLegacyBackCoverage). Legacy path (API <33, or predictive back
    // off): the dispatcher callback never runs, so finish explicitly instead
    // of relying on Spotify's onBackPressed to finish. Inactive native
    // screens fall through untouched.
    private void handleLegacyActivityBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null) return;
        if (!isLyricsFullscreenActivity(activity)) {
            if (nowPlayingInjector.consumeArtworkBack(activity)) param.setResult(null);
            return;
        }
        // An override that calls super would otherwise exit twice; the first
        // firing already marked the exit and finished.
        if (isExplicitLyricsExit(activity)) {
            param.setResult(null);
            return;
        }
        if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) return;
        markExplicitLyricsExit(activity);
        activity.finish();
        param.setResult(null);
    }

    // getDeclaredMethod lookups are exact-class only, so an onBackPressed
    // override in the concrete lyrics activity (or its androidx base) bypasses
    // the Activity-level hook while its finish() still hits our suppression:
    // back then does nothing and the screen looks stuck. Cover each declaring
    // class once, lazily, from an activity instance that carries the host
    // classloader. Misses fail silent and keep the old behavior.
    private void ensureLegacyBackCoverage(Activity activity) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        coverHostMethod(activity.getClass(), "onBackPressed");
        coverHostMethod(activity.getClass(), "dispatchKeyEvent");
        Class<?> component = XpReflect.findClassIfExists(
                "androidx.activity.ComponentActivity", activity.getClass().getClassLoader());
        coverHostMethod(component, "onBackPressed");
        coverHostMethod(component, "dispatchKeyEvent");
    }

    private void coverHostMethod(Class<?> clazz, String method) {
        if (clazz == null || clazz == Activity.class) return;
        String key = clazz.getName() + "#" + method;
        synchronized (LEGACY_BACK_COVERED) {
            if (!LEGACY_BACK_COVERED.add(key)) return;
        }
        try {
            Class<?>[] params = "dispatchKeyEvent".equals(method)
                    ? new Class<?>[]{KeyEvent.class} : new Class<?>[0];
            clazz.getDeclaredMethod(method, params);
        } catch (NoSuchMethodException missing) {
            return;
        }
        XpLog.log(NativeSpicyLyricsHook.TAG + " covering " + method + " override in " + clazz.getName());
        try {
            if ("dispatchKeyEvent".equals(method)) {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#dispatchKeyEvent-override",
                        (XpHooks.Before) param -> handleActivityKeyForBack(
                                (Activity) param.thisObject, param));
            } else {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#onBackPressed-override",
                        (XpHooks.Before) param -> handleLegacyActivityBack(
                                (Activity) param.thisObject, param));
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleActivityKeyForBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
        KeyEvent event = (KeyEvent) param.args[0];
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            synchronized (BACK_PRESS_UNTIL_MS) {
                BACK_PRESS_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + BACK_PRESS_EXIT_WINDOW_MS);
            }
        }
    }

    private static boolean consumeRecentBackPress(Activity activity) {
        if (activity == null) return false;
        synchronized (BACK_PRESS_UNTIL_MS) {
            Long until = BACK_PRESS_UNTIL_MS.remove(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Owned-overlay back only: registered after takeover ownership is established    // (active session), never on native Spotify screens. No host-dispatch fallback:
    // unregistered screens keep normal host handling untouched.
    private void ensureSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        if (!isLyricsFullscreenActivity(activity)) return;
        if (isExplicitLyricsExit(activity)) return;
        if (!nativeLyricsSessionActive) return;
        synchronized (backCallbacks) {
            if (backCallbacks.containsKey(activity)) return;
            OnBackInvokedCallback callback = () -> {
                // Registration implies ownership; re-check for races without ever
                // delegating to deprecated host handling from inside the callback.
                if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) {
                    unregisterSystemBackCallback(activity);
                    return;
                }
                markExplicitLyricsExit(activity);
                activity.finish();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            backCallbacks.put(activity, callback);
        }
    }

    private void unregisterSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (backCallbacks) {
            OnBackInvokedCallback callback = backCallbacks.remove(activity);
            if (callback != null) activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(callback);
        }
    }

    void markLyricsActivityKeepWindow(Activity activity) {
        if (activity == null) return;
        synchronized (KEEP_LYRICS_ACTIVITY_UNTIL_MS) {
            KEEP_LYRICS_ACTIVITY_UNTIL_MS.put(
                    activity,
                    SystemClock.elapsedRealtime() + KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS
            );
        }
    }

    private void scheduleExtraLyricsButtonInjection(Activity activity) {
        if (activity == null) return;
        try {
            if (!isNativeSpicyEnabled(activity)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            cancelExtraLyricsButtonInjection(activity);
            ExtraInjectionRetry retry = new ExtraInjectionRetry(activity, decor);
            synchronized (extraInjectionRetries) {
                extraInjectionRetries.put(activity, retry);
            }
            retry.postNext();
            nowPlayingInjector.schedule(activity);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " schedule extra lyrics injection failed: " + t);
        }
    }

    private boolean injectExtraLyricsButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()
                    || isLyricsFullscreenActivity(activity)
                    || !isNativeSpicyEnabled(activity)) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            if (content.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return true;
            if (!isLikelyNowPlayingScreen(activity, content)) return false;

            // The Share/Queue cluster (accessory_row) is an R8-obfuscated ConstraintLayout. Add the
            // entry button to its parent and position it into the empty footer space after layout.
            View rowView = findViewByResourceEntryName(content, "accessory_row");
            if (rowView == null || !rowView.isShown() || rowView.getWidth() == 0) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " Extra lyrics: accessory_row not laid out yet in " + activity.getClass().getName());
                return false;
            }
            ViewGroup buttonHost = rowView.getParent() instanceof ViewGroup ? (ViewGroup) rowView.getParent() : null;
            if (buttonHost == null) return false;
            if (buttonHost.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return true;
            int side = rowView.getHeight() > 0 ? rowView.getHeight() : dp(48);
            View button = createExtraLyricsRowButton(activity);
            buttonHost.addView(button, new ViewGroup.LayoutParams(side, side));
            button.setTranslationX((buttonHost.getWidth() - side) / 2f);
            button.setTranslationY(rowView.getTop() + (rowView.getHeight() - side) / 2f);
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " inserted Extra lyrics ♪ centered in footer in " + activity.getClass().getName());
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " inject extra lyrics button failed: " + t);
            return false;
        }
    }

    private void cancelExtraLyricsButtonInjection(Activity activity) {
        ExtraInjectionRetry retry;
        synchronized (extraInjectionRetries) {
            retry = extraInjectionRetries.remove(activity);
        }
        if (retry != null) retry.cancel();
    }

    private final class ExtraInjectionRetry implements Runnable {
        private final Activity activity;
        private final View decor;
        private int attempt;
        private long steadyElapsedMs;
        private boolean cancelled;
        private boolean extraDoneOnce;

        ExtraInjectionRetry(Activity activity, View decor) {
            this.activity = activity;
            this.decor = decor;
        }

        void postNext() {
            if (cancelled) return;
            if (attempt < EXTRA_INJECTION_DELAYS_MS.length) {
                decor.postDelayed(this, EXTRA_INJECTION_DELAYS_MS[attempt++]);
                return;
            }
            // injectExtraLyricsButton's target (accessory_row) is only reached post-playback
            // (NowPlayingActivity), so it's always ready within this burst. The mini-player bar
            // lives on every screen but stays uninflated/GONE until the very first track starts
            // playing in this activity's lifetime, which can happen long after this burst - so
            // once the burst is spent, keep polling steadily (bounded, not forever) instead of
            // giving up permanently.
            if (activity.isFinishing() || steadyElapsedMs >= MINI_PLAYER_STEADY_RETRY_MAX_MS) return;
            steadyElapsedMs += MINI_PLAYER_STEADY_RETRY_MS;
            decor.postDelayed(this, MINI_PLAYER_STEADY_RETRY_MS);
        }

        void cancel() {
            cancelled = true;
            decor.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (cancelled) return;
            // Once the footer button lands it's done for good; no need to keep re-scanning for
            // it every steady-retry tick alongside the mini-player poll.
            boolean extraDone = extraDoneOnce || injectExtraLyricsButton(activity);
            extraDoneOnce = extraDone;
            boolean miniPlayerDone = injectMiniPlayerLyricsButton(activity);
            if (extraDone && miniPlayerDone) {
                synchronized (extraInjectionRetries) {
                    if (extraInjectionRetries.get(activity) == this) extraInjectionRetries.remove(activity);
                }
                return;
            }
            postNext();
        }
    }

    /**
     * Adds a button to the persistent mini player that jumps straight to Spicy's fullscreen
     * lyrics. Runs on every non-lyrics activity since the mini player bar is a global overlay,
     * not tied to one screen. Returns true once handled (or once determined not applicable) so
     * the retry loop can stop; false to keep retrying while the bar is not laid out yet.
     */
    // now_playing_bar.xml constants (decompiled from the host APK): play_pause_button is a
    // direct child of now_playing_bar_layout, 40dp square, end-aligned to the bar's right edge
    // with an 8dp margin. It renders through Spotify's own lazy Encore view-stub component,
    // which can report getWidth()==0 indefinitely even once visibly on screen — so position
    // relative to the BAR's own (always-reliable) measured width instead of the button's.
    private static final int PLAY_PAUSE_BUTTON_DP = 40;
    private static final int PLAY_PAUSE_MARGIN_END_DP = 8;

    private boolean injectMiniPlayerLyricsButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || isLyricsFullscreenActivity(activity)
                    || !isNativeSpicyEnabled(activity)) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            View existingButton = content.findViewWithTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
            if (!SpotifyPlusConfig.from(activity).get(Settings.MINI_PLAYER_LYRICS_ICON)) {
                if (existingButton != null) content.removeView(existingButton); // toggled off live
                return true;
            }
            View bar = findViewByResourceEntryName(content, "now_playing_bar_layout");
            if (existingButton != null) {
                // Safety-net re-sync at the steady poll's cadence (seconds, not frames): the bar's
                // own OnLayoutChangeListener below does not fire for every reason the button can
                // end up stale relative to the bar (e.g. screen off/on with unchanged bar bounds).
                if (bar != null && bar.isShown() && bar.getWidth() > 0) {
                    repositionMiniPlayerButton(existingButton, bar, content, dp(PLAY_PAUSE_BUTTON_DP),
                            findViewByResourceEntryName(bar, "connect_destination_button"),
                            findViewByResourceEntryName(bar, "tracks_carousel_view"));
                }
                return true;
            }
            if (bar == null || !bar.isShown() || bar.getWidth() == 0) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " mini player: now_playing_bar_layout not laid out yet in "
                        + activity.getClass().getName());
                return false;
            }
            // Presence (not shown/width) confirms the bar's content actually finished inflating,
            // without depending on this view-stub's own unreliable measured size.
            if (findViewByResourceEntryName(bar, "play_pause_button") == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " mini player: play_pause_button not present yet in "
                        + activity.getClass().getName());
                return false;
            }

            // now_playing_bar_layout is a MotionLayout: a child added directly to it that isn't
            // referenced in its MotionScene's ConstraintSets gets silently zero-sized/dropped by
            // MotionLayout's own layout pass on the next transition. Adding it to the activity's
            // plain content FrameLayout instead, positioned in screen coordinates derived from
            // the bar's live location, sidesteps MotionLayout's constraint system entirely.
            int side = dp(PLAY_PAUSE_BUTTON_DP);
            View button = createMiniPlayerLyricsButton(activity);
            content.addView(button, new FrameLayout.LayoutParams(side, side));
            button.bringToFront();
            // Resolved once (an O(view count) tree walk) and cached, not re-resolved on every
            // reposition. Re-resolved only when a real bar layout change is observed, since the
            // device icon can legitimately appear/disappear later.
            View[] connectButtonRef = {findViewByResourceEntryName(bar, "connect_destination_button")};
            View[] carouselRef = {findViewByResourceEntryName(bar, "tracks_carousel_view")};
            Runnable reposition = () -> repositionMiniPlayerButton(
                    button, bar, content, side, connectButtonRef[0], carouselRef[0]);
            reposition.run();
            // now_playing_bar_scene.xml's default_size <-> large_size transition is a continuous,
            // drag-driven MotionLayout animation, not a series of discrete layout passes - a plain
            // OnLayoutChangeListener only fires once a transition actually settles, so during the
            // transition itself this floating button would visibly lag behind the real bar content.
            // The frame-driven follow loop below only runs for a bounded burst right after such a
            // change instead of indefinitely (a permanent per-frame tree walk on Spotify's main
            // thread is exactly the kind of never-ending stutter this codebase already fixed once).
            bar.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or_, ob) -> {
                connectButtonRef[0] = findViewByResourceEntryName(bar, "connect_destination_button");
                carouselRef[0] = findViewByResourceEntryName(bar, "tracks_carousel_view");
                startMiniPlayerFollowBurst(activity, button, reposition);
            });

            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " inserted mini player lyrics button in " + activity.getClass().getName()
                    + " anchoredToConnect=" + (connectButtonRef[0] != null && connectButtonRef[0].isShown()));
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " inject mini player lyrics button failed: " + t);
            return true; // don't retry forever on a real failure
        }
    }

    private static final long MINI_PLAYER_FOLLOW_BURST_MS = 500L;

    /** Re-runs reposition on every display frame for a bounded burst instead of indefinitely, so
     *  a MotionLayout transition (drag-driven, not a single discrete layout pass) is tracked
     *  smoothly without turning into a permanent per-frame cost for the rest of the app session. */
    private void startMiniPlayerFollowBurst(Activity activity, View button, Runnable reposition) {
        long deadlineNanos = System.nanoTime() + MINI_PLAYER_FOLLOW_BURST_MS * 1_000_000L;
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (activity.isFinishing() || !button.isAttachedToWindow()) return;
                reposition.run();
                if (System.nanoTime() < deadlineNanos) {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            }
        });
    }

    /**
     * now_playing_bar.xml + its MotionScene (decompiled): left-to-right the bar reads carousel,
     * connect_destination_button (device icon), skippable_ad_view_stub (usually gone but its slot
     * in the chain still isn't free), add_to_button, play_pause_button - every one of those four
     * slots is a real, always-occupied control per the scene's ConstraintSet, so there is no empty
     * gap anywhere inside that cluster to drop a button into. The only actually free space is the
     * carousel's own flexible zone immediately left of the device icon - encroaching there just
     * trims the track-title marquee a bit, it doesn't sit on top of a real control.
     */
    private void repositionMiniPlayerButton(View button, View bar, View content, int side,
                                             View connectButton, View carousel) {
        try {
            int[] barLoc = new int[2];
            int[] contentLoc = new int[2];
            bar.getLocationOnScreen(barLoc);
            content.getLocationOnScreen(contentLoc);
            float barLeftInContent = barLoc[0] - contentLoc[0];
            float barTopInContent = barLoc[1] - contentLoc[1];
            float x;
            if (connectButton != null && connectButton.isShown() && connectButton.getWidth() > 0) {
                int[] connLoc = new int[2];
                connectButton.getLocationOnScreen(connLoc);
                x = (connLoc[0] - contentLoc[0]) - dp(4) - side;
            } else {
                // Device icon not laid out: still stay left of the whole 3-button cluster
                // (connect + add + play), not just play_pause_button alone.
                x = barLeftInContent + bar.getWidth() - dp(PLAY_PAUSE_MARGIN_END_DP) - side * 3 - dp(8);
            }
            button.setX(x);
            button.setY(barTopInContent + (bar.getHeight() - side) / 2f);
            // This button is a floating overlay, not a real MotionLayout participant, so the
            // marquee track title still scrolls straight into our button's space rather than
            // making room for it on its own. Push the carousel's own end margin out to actually
            // free that space, reapplied every time (MotionLayout's own transitions reassert the
            // ConstraintSet's original margin otherwise).
            if (carousel != null && carousel.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams carouselLp =
                        (ViewGroup.MarginLayoutParams) carousel.getLayoutParams();
                int wantedEndMargin = side + dp(8);
                if (carouselLp.getMarginEnd() < wantedEndMargin) {
                    carouselLp.setMarginEnd(wantedEndMargin);
                    carousel.setLayoutParams(carouselLp);
                }
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " reposition mini player button failed: " + t);
        }
    }

    private View createMiniPlayerLyricsButton(Activity activity) {
        ImageButton button = new ImageButton(activity);
        button.setTag(TAG_MINI_PLAYER_LYRICS_BUTTON);
        button.setContentDescription("Open Spicy lyrics");
        // House mark (mic + sparkles), same as the footer entry button — not a Lucide glyph.
        NativeIconButtons.setModuleIcon(button, activity, R.drawable.ic_spicy_lyrics_page);
        button.setColorFilter(Color.rgb(232, 232, 238));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> launchNativeLyricsFullscreen(activity));
        return button;
    }

    private View createExtraLyricsRowButton(Activity activity) {
        ImageButton button = new ImageButton(activity);
        button.setTag(TAG_EXTRA_LYRICS_BUTTON);
        button.setContentDescription("Open Spicy lyrics");
        NativeIconButtons.setModuleIcon(button, activity, R.drawable.ic_spicy_lyrics_page);
        button.setColorFilter(Color.rgb(232, 232, 238));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> launchNativeLyricsFullscreen(activity));
        return button;
    }

    private View findViewByResourceEntryName(View root, String entryName) {
        if (root == null || isBlank(entryName)) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    String name = view.getResources().getResourceEntryName(id);
                    if (entryName.equals(name)) return view;
                } catch (Throwable ignored) {
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }

    private boolean isLikelyNowPlayingScreen(Activity activity, View root) {
        String activityName = activity == null ? "" : activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (activityName.contains("settings") || activityName.contains("lyrics")) return false;
        if (activityName.contains("nowplaying") || activityName.contains("now_playing")) return true;
        if (hasVisibleClassNameContaining(root, "nowplaying")
                || hasVisibleClassNameContaining(root, "now_playing")
                || hasVisibleClassNameContaining(root, "com.spotify.nowplaying")) return true;
        SpotifyTrack track = host.getCurrentTrackSafely();
        return track != null
                && !isBlank(trackIdFromUri(track.uri))
                && containsVisibleText(root, track.title)
                && containsVisibleText(root, track.artist);
    }

    private boolean hasVisibleClassNameContaining(View root, String needleLower) {
        if (root == null || isBlank(needleLower)) return false;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            String name = view.getClass().getName().toLowerCase(Locale.ROOT);
            if (view.isShown() && name.contains(needleLower)) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    private boolean containsVisibleText(View root, String needle) {
        if (root == null || isBlank(needle)) return false;
        String normalizedNeedle = needle.trim().toLowerCase(Locale.ROOT);
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            if (view.isShown() && view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                if (text != null && text.toString().trim().toLowerCase(Locale.ROOT).contains(normalizedNeedle)) {
                    return true;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    void launchNativeLyricsFullscreen(Activity activity) {
        try {
            if (activity == null) return;
            takeoverArmed = true;
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), LYRICS_FULLSCREEN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " launched native lyrics fullscreen (takeover armed) from Extra lyrics button");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " launch native lyrics fullscreen failed: " + t);
        }
    }

    private boolean consumeTakeoverArmed() {
        if (takeoverArmed) {
            takeoverArmed = false;
            return true;
        }
        return false;
    }

    private boolean activateNativeTakeover(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Stale-root reclaim must not resurrect a session the user just exited.
        if (isExplicitLyricsExit(activity)) return false;
        if (nativeLyricsSessionActive || hasNativeSpicyRoot(activity)) return true;
        if (!consumeTakeoverArmed()) return false;
        // Promote the one-shot entry signal before waiting for a mount-ready window. This is the
        // durable lifecycle signal carried across rotation and Spotify activity relaunches.
        nativeLyricsSessionActive = true;
        return true;
    }

    private boolean isStayInLyricsEnabled(Activity activity) {
        try {
            return SpotifyPlusConfig.from(activity).get(Settings.STAY_IN_LYRICS);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // Pure back/finish decision matrix (unit-tested): user back must exit even while
    // track-change/rotation finishes stay suppressed; inactive native screens stay untouched.
    static boolean shouldInterceptLyricsBack(boolean sessionActive, boolean hasRoot) {
        return sessionActive || hasRoot;
    }

    static boolean shouldSuppressLyricsFinish(boolean stayInLyricsEnabled,
                                              boolean nativeSpicyEnabled,
                                              boolean sessionActive,
                                              boolean explicitExit) {
        if (!nativeSpicyEnabled) return false;
        if (!stayInLyricsEnabled) return false;
        if (!sessionActive) return false;
        return !explicitExit;
    }

    private boolean shouldKeepLyricsActivityOpen(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Spotify invalidates and finishes its fullscreen lyrics activity after some track
        // changes. During rotation that finish can happen before our recreated root mounts, so
        // root presence and short keep windows are not stable ownership signals. The takeover
        // session is stable; explicit back clears it before calling finish.
        boolean explicitExit;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            explicitExit = until != null && SystemClock.elapsedRealtime() <= until;
        }
        return shouldSuppressLyricsFinish(isStayInLyricsEnabled(activity),
                isNativeSpicyEnabled(activity), nativeLyricsSessionActive, explicitExit);
    }

    private boolean isLyricsFullscreenActivity(Activity activity) {
        return activity != null && LYRICS_FULLSCREEN_ACTIVITY.equals(activity.getClass().getName());
    }

    private void mountNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("mountNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            if (activity == null || activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            DeployCacheCleaner.ensureCleared(activity);
            if (!isLyricsFullscreenActivity(activity)) return;
            if (isExplicitLyricsExit(activity)) {
                unregisterSystemBackCallback(activity);
                return;
            }
            if (!isNativeSpicyEnabled(activity)) {
                removeNativeSpicyRoot(activity);
                return;
            }
            // Captured before the flag flips below: true here means a lyrics session was already
            // active going into this call, i.e. this mount is a reattach after an orientation-
            // driven activity recreate, not the screen's first open this session.
            boolean rotationContinuation = nativeLyricsSessionActive;
            nativeLyricsSessionActive = true; // our screen owns this lyrics session (survives rotation)
            ensureSystemBackCallback(activity);

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " content root missing");
                return;
            }

            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                ((NativeSpicyShellView) existing).start();
                if (existing.getAlpha() >= 1f) hideCoveredSiblings(content, existing);
                ensureSystemBackCallback(activity);
                return;
            }

            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_NATIVE_SPICY_ROOT);
            // Rotation reattach: panel was already visible a moment ago, show it instantly
            // without fading in. Fresh open: start invisible and fade up to announce arrival.
            if (rotationContinuation) {
                root.setAlpha(1f);
                root.setTranslationY(0f);
            } else {
                root.setAlpha(0f);
                root.setTranslationY(NativeLyricsUtils.dp(24));
            }
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            markLyricsActivityKeepWindow(activity);
            root.start();
            if (!rotationContinuation) {
                root.animate().alpha(1f).translationY(0f).setDuration(260)
                        .withEndAction(() -> hideCoveredSiblings(content, root)).start();
            } else {
                hideCoveredSiblings(content, root);
            }
            XpLog.log(NativeSpicyLyricsHook.TAG + " mounted native Spicy renderer shell");
            Diagnostics.event("renderer", "mount_state",
                    Diagnostics.context("surface", "fullscreen", "mounted", "true"));
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " mount failed: " + t);
            Diagnostics.event("renderer", "mount_state", t,
                    Diagnostics.context("surface", "fullscreen", "mounted", "false"));
        }
    }

    private void removeNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("removeNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                // Before any fade-out: the page underneath must be drawn again once the shell
                // stops covering it.
                restoreCoveredSiblings(shell);
                // Host activity may already be leaving for rotation or a track-driven recreate.
                // Do not rely on an exit animation callback from a detached window to stop the
                // shell; that callback can be skipped, leaving stale subscriptions alive.
                if (activity.isDestroyed() || activity.isFinishing() || activity.isChangingConfigurations()) {
                    shell.stop();
                    content.removeView(shell);
                    return;
                }
                shell.animate().alpha(0f).translationY(NativeLyricsUtils.dp(24)).setDuration(220).withEndAction(() -> {
                    try {
                        shell.stop();
                        content.removeView(shell);
                    } catch (Throwable t) {
                        XpLog.log(NativeSpicyLyricsHook.TAG + " remove animation cleanup failed: " + t);
                    }
                }).start();
                XpLog.log(NativeSpicyLyricsHook.TAG + " removed native Spicy shell");
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " remove failed: " + t);
        }
        // Rotation keeps the session (and its owned back) alive across remount; only release
        // owned back when ownership itself ended. Explicit exit and destroy unregister directly.
        if (!nativeLyricsSessionActive) unregisterSystemBackCallback(activity);
    }

    /**
     * Spotify's own lyrics page (a full-screen ComposeView) stays mounted under the opaque shell.
     * Left VISIBLE it is still recorded and rasterized every frame behind the lyrics - a whole
     * extra screen of drawing, plus Compose's own lyric animations - without a single pixel of it
     * ever reaching the display. Hiding it once the shell fully covers it changes nothing on
     * screen; it stays attached and laid out, so anything reading its views still works.
     */
    private static void hideCoveredSiblings(FrameLayout content, View root) {
        try {
            if (content == null || root == null || root.getParent() != content) return;
            if (root.getTag(TAG_COVERED_SIBLINGS) != null) return;
            java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
            int rootIndex = content.indexOfChild(root);
            for (int i = 0; i < rootIndex; i++) {
                View child = content.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                child.setVisibility(View.INVISIBLE);
                hidden.add(child);
            }
            root.setTag(TAG_COVERED_SIBLINGS, hidden);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " hide covered page failed: " + t);
        }
    }

    private static void restoreCoveredSiblings(View root) {
        try {
            Object tag = root == null ? null : root.getTag(TAG_COVERED_SIBLINGS);
            if (!(tag instanceof java.util.List)) return;
            root.setTag(TAG_COVERED_SIBLINGS, null);
            for (Object o : (java.util.List<?>) tag) {
                // Only undo our own change; Spotify may have hidden the view itself meanwhile.
                if (o instanceof View && ((View) o).getVisibility() == View.INVISIBLE) {
                    ((View) o).setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable ignored) {
        }
    }


    private boolean hasNativeSpicyRoot(Activity activity) {
        try {
            if (activity == null) return false;
            FrameLayout content = activity.findViewById(android.R.id.content);
            return content != null && content.findViewWithTag(TAG_NATIVE_SPICY_ROOT) instanceof NativeSpicyShellView;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
