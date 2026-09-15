package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;
import static com.eza.spicyex.hooks.NativeLyricsUtils.trackIdFromUri;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
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
import java.util.Locale;
import java.util.WeakHashMap;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

/** Owns Spotify activity takeover, entry injection, keepalive, and native shell root mount. */
final class LyricsActivityTakeoverHook {
    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";
    private static final int TAG_NATIVE_SPICY_ROOT = 0x53504C53; // SPLS
    private static final int TAG_EXTRA_LYRICS_BUTTON = 0x53504C58; // SPLX
    private static final int TAG_MINI_PLAYER_LYRICS_BUTTON = 0x53504C4D; // SPLM
    private static final long KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS = 3500L;
    private static final long[] EXTRA_INJECTION_DELAYS_MS = {450L, 950L, 1400L, 2400L};
    private static final long MINI_PLAYER_STEADY_RETRY_MS = 3000L;
    private static final long MINI_PLAYER_STEADY_RETRY_MAX_MS = 10 * 60 * 1000L;

    private static final WeakHashMap<Activity, Long> EXPLICIT_LYRICS_EXIT_UNTIL_MS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> KEEP_LYRICS_ACTIVITY_UNTIL_MS = new WeakHashMap<>();
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
                registerSystemBackCallback(activity);
                if (activateNativeTakeover(activity)) {
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
                activateNativeTakeover(activity);
                // else: native lyric card opened Spotify's own screen - do not take over.
            } else {
                scheduleExtraLyricsButtonInjection(activity);
            }
        });

        XpHooks.findAfter(Activity.class, "onWindowFocusChanged", "takeover:Activity#onWindowFocusChanged",
                param -> {
                    if (!((boolean) param.args[0])) return;
                    Activity activity = (Activity) param.thisObject;
                    if (isLyricsFullscreenActivity(activity) && activateNativeTakeover(activity)) {
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
            Activity activity = (Activity) param.thisObject;
            if (!isLyricsFullscreenActivity(activity)) {
                if (nowPlayingInjector.consumeArtworkBack(activity)) param.setResult(null);
                return;
            }
            markExplicitLyricsExit(activity);
        });

        XpHooks.findBefore(Activity.class, "finish", "takeover:Activity#finish", param -> {
            Activity activity = (Activity) param.thisObject;
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
    }

    private void registerSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (backCallbacks) {
            if (backCallbacks.containsKey(activity)) return;
            OnBackInvokedCallback callback = () -> {
                markExplicitLyricsExit(activity);
                activity.finish();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
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
                // Safety-net re-sync, not just the initial placement: the only other trigger is
                // bar's own OnLayoutChangeListener below, which does not fire for every reason the
                // button can end up stale relative to the bar - confirmed live, the button freezes
                // in place after a screen off/on cycle, since the bar's own bounds genuinely don't
                // change even though whatever the button was anchored against (e.g. the device
                // icon appearing/disappearing) can. This runs at the steady poll's cadence (a few
                // seconds), not every frame, so the cost is negligible - nothing like the
                // per-frame walk that caused the stutter this codebase already fixed once.
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
            // MotionLayout's own layout pass on the next transition - confirmed live (the button
            // was gone from the accessibility tree immediately after its own successful-insertion
            // log line). Adding it to the activity's plain content FrameLayout instead, positioned
            // in screen coordinates derived from the bar's live location, sidesteps MotionLayout's
            // constraint system entirely.
            int side = dp(PLAY_PAUSE_BUTTON_DP);
            View button = createMiniPlayerLyricsButton(activity);
            content.addView(button, new FrameLayout.LayoutParams(side, side));
            button.bringToFront();
            // Resolved once (an O(view count) tree walk) and cached, not re-resolved on every
            // reposition - see the perf note above startMiniPlayerFollowBurst. Re-resolved only
            // when a real bar layout change is observed, since the device icon can legitimately
            // appear/disappear later (Connect destinations coming and going).
            View[] connectButtonRef = {findViewByResourceEntryName(bar, "connect_destination_button")};
            View[] carouselRef = {findViewByResourceEntryName(bar, "tracks_carousel_view")};
            Runnable reposition = () -> repositionMiniPlayerButton(
                    button, bar, content, side, connectButtonRef[0], carouselRef[0]);
            reposition.run();
            // now_playing_bar_scene.xml's default_size <-> large_size transition is a continuous,
            // drag-driven MotionLayout animation, not a series of discrete layout passes - a plain
            // OnLayoutChangeListener only fires once a transition actually settles, so during the
            // transition itself this floating button visibly lagged behind/detached from the real
            // bar content sliding smoothly underneath it.
            //
            // A prior version chased this by riding the Choreographer frame clock *forever*
            // (posting a new frame callback from inside itself with no stop condition) so it
            // would always be glued to the bar regardless of transition state - but each tick
            // re-ran two full child-tree walks (findViewByResourceEntryName) on every single
            // display frame, on Spotify's own main thread, for as long as Spotify was running
            // with this button attached (effectively always, on every non-lyrics screen). That is
            // exactly the kind of persistent per-frame overhead that reads as "stutter that never
            // goes away" - it was live the whole time the app was open, not just during a
            // transition. Fixed two ways: the tree walks only happen here and when the bar's
            // layout actually changes (below), and the frame-driven loop now only runs for a
            // bounded burst right after such a change instead of indefinitely.
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
     * gap anywhere inside that cluster to drop a button into (confirmed against live bounds: a
     * flat 2-button offset from the bar's edge landed almost exactly on top of add_to_button). The
     * only actually free space is the carousel's own flexible zone immediately left of the device
     * icon - encroaching there just trims the track-title marquee a bit, it doesn't sit on top of
     * a real control.
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
            // This button is a floating overlay, not a real MotionLayout participant (see the
            // class-level note on injectMiniPlayerLyricsButton for why), so the marquee track
            // title still scrolls the way it always did - straight into our button's space -
            // rather than making room for it on its own. Push the carousel's own end margin out
            // to actually free that space, reapplied every time (MotionLayout's own transitions
            // reassert the ConstraintSet's original margin otherwise, same reason the button
            // itself needs re-positioning on every bar layout change instead of just once).
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

    private boolean shouldKeepLyricsActivityOpen(Activity activity) {
        if (!isLyricsFullscreenActivity(activity) || !isNativeSpicyEnabled(activity)) return false;
        if (!isStayInLyricsEnabled(activity)) return false;
        // Spotify invalidates and finishes its fullscreen lyrics activity after some track
        // changes. During rotation that finish can happen before our recreated root mounts, so
        // root presence and short keep windows are not stable ownership signals. The takeover
        // session is stable; explicit back clears it before calling finish.
        if (!nativeLyricsSessionActive) return false;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            if (until != null && SystemClock.elapsedRealtime() <= until) return false;
        }
        return true;
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
            if (!isNativeSpicyEnabled(activity)) {
                removeNativeSpicyRoot(activity);
                return;
            }
            nativeLyricsSessionActive = true; // our screen owns this lyrics session (survives rotation)

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " content root missing");
                return;
            }

            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                ((NativeSpicyShellView) existing).start();
                return;
            }

            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_NATIVE_SPICY_ROOT);
            root.setAlpha(0f);
            root.setTranslationY(NativeLyricsUtils.dp(24));
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            markLyricsActivityKeepWindow(activity);
            root.start();
            root.animate().alpha(1f).translationY(0f).setDuration(260).start();
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
