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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns Spotify activity takeover, entry injection, keepalive, and native shell root mount. */
final class LyricsActivityTakeoverHook {
    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";
    private static final int TAG_NATIVE_SPICY_ROOT = 0x53504C53; // SPLS
    private static final int TAG_EXTRA_LYRICS_BUTTON = 0x53504C58; // SPLX
    private static final long KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS = 3500L;

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

    LyricsActivityTakeoverHook(NativeSpicyLyricsHook host, NowPlayingInjector nowPlayingInjector) {
        this.host = host;
        this.nowPlayingInjector = nowPlayingInjector;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookLyricsActivityLifecycle");
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (isLyricsFullscreenActivity(activity)) {
                    if (hasNativeSpicyRoot(activity) || consumeTakeoverArmed() || nativeLyricsSessionActive) {
                        XposedBridge.log(NativeSpicyLyricsHook.TAG
                                + " lyrics activity onCreate (takeover) " + activity.getClass().getName());
                        activity.getWindow().getDecorView().postDelayed(() -> mountNativeSpicyRoot(activity), 250);
                    }
                    // else: opened via Spotify's native lyric card - leave Spotify's screen untouched.
                } else {
                    scheduleExtraLyricsButtonInjection(activity);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                References.currentActivity = activity;
                if (isLyricsFullscreenActivity(activity)) {
                    if (hasNativeSpicyRoot(activity) || consumeTakeoverArmed() || nativeLyricsSessionActive) {
                        activity.getWindow().getDecorView().postDelayed(() -> mountNativeSpicyRoot(activity), 150);
                    }
                    // else: native lyric card opened Spotify's own screen - do not take over.
                } else {
                    scheduleExtraLyricsButtonInjection(activity);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                nowPlayingInjector.stop(activity);
                if (!isLyricsFullscreenActivity(activity)) return;
                // Rotation/config change recreates the activity - keep the session so onCreate
                // re-mounts our shell. Only a real destroy ends the session.
                if (!activity.isChangingConfigurations()) nativeLyricsSessionActive = false;
                removeNativeSpicyRoot(activity);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                nowPlayingInjector.stop((Activity) param.thisObject); // quiet the now-playing card ticker
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onBackPressed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!isLyricsFullscreenActivity(activity)) return;
                markExplicitLyricsExit(activity);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!shouldKeepLyricsActivityOpen(activity)) return;
                XposedBridge.log(NativeSpicyLyricsHook.TAG
                        + " suppressed non-explicit lyrics activity finish to keep native renderer open");
                param.setResult(null);
            }
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
            decor.postDelayed(() -> injectExtraLyricsButton(activity), 450);
            decor.postDelayed(() -> injectExtraLyricsButton(activity), 1400);
            decor.postDelayed(() -> injectExtraLyricsButton(activity), 2800);
            decor.postDelayed(() -> injectExtraLyricsButton(activity), 5200);
            nowPlayingInjector.schedule(activity);
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " schedule extra lyrics injection failed: " + t);
        }
    }

    private void injectExtraLyricsButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()
                    || isLyricsFullscreenActivity(activity)
                    || !isNativeSpicyEnabled(activity)) return;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return;
            if (!isLikelyNowPlayingScreen(activity, content)) return;

            // The Share/Queue cluster (accessory_row) is an R8-obfuscated ConstraintLayout. Add the
            // entry button to its parent and position it into the empty footer space after layout.
            View rowView = findViewByResourceEntryName(content, "accessory_row");
            if (rowView == null || !rowView.isShown() || rowView.getWidth() == 0) {
                XposedBridge.log(NativeSpicyLyricsHook.TAG
                        + " Extra lyrics: accessory_row not laid out yet in " + activity.getClass().getName());
                return;
            }
            ViewGroup buttonHost = rowView.getParent() instanceof ViewGroup ? (ViewGroup) rowView.getParent() : null;
            if (buttonHost == null || buttonHost.findViewWithTag(TAG_EXTRA_LYRICS_BUTTON) != null) return;
            int side = rowView.getHeight() > 0 ? rowView.getHeight() : dp(48);
            View button = createExtraLyricsRowButton(activity);
            buttonHost.addView(button, new ViewGroup.LayoutParams(side, side));
            button.setTranslationX((buttonHost.getWidth() - side) / 2f);
            button.setTranslationY(rowView.getTop() + (rowView.getHeight() - side) / 2f);
            XposedBridge.log(NativeSpicyLyricsHook.TAG
                    + " inserted Extra lyrics ♪ centered in footer in " + activity.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " inject extra lyrics button failed: " + t);
        }
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
            XposedBridge.log(NativeSpicyLyricsHook.TAG
                    + " launched native lyrics fullscreen (takeover armed) from Extra lyrics button");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " launch native lyrics fullscreen failed: " + t);
        }
    }

    private boolean consumeTakeoverArmed() {
        if (takeoverArmed) {
            takeoverArmed = false;
            return true;
        }
        return false;
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
        if (!hasNativeSpicyRoot(activity)) return false;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            if (until != null && SystemClock.elapsedRealtime() <= until) return false;
        }
        synchronized (KEEP_LYRICS_ACTIVITY_UNTIL_MS) {
            Long until = KEEP_LYRICS_ACTIVITY_UNTIL_MS.get(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    private boolean isLyricsFullscreenActivity(Activity activity) {
        return activity != null && LYRICS_FULLSCREEN_ACTIVITY.equals(activity.getClass().getName());
    }

    private void mountNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("mountNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            DeployCacheCleaner.ensureCleared(activity);
            AuthTokenCaptureHook.restorePersistedAccessToken(activity);
            if (!isLyricsFullscreenActivity(activity)) return;
            if (!isNativeSpicyEnabled(activity)) {
                removeNativeSpicyRoot(activity);
                return;
            }
            nativeLyricsSessionActive = true; // our screen owns this lyrics session (survives rotation)

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XposedBridge.log(NativeSpicyLyricsHook.TAG + " content root missing");
                return;
            }

            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                ((NativeSpicyShellView) existing).start();
                return;
            }

            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_NATIVE_SPICY_ROOT);
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            markLyricsActivityKeepWindow(activity);
            root.start();
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " mounted native Spicy renderer shell");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " mount failed: " + t);
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
                ((NativeSpicyShellView) existing).stop();
                content.removeView(existing);
                XposedBridge.log(NativeSpicyLyricsHook.TAG + " removed native Spicy shell");
            }
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " remove failed: " + t);
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
