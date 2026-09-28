package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Lyrics in picture-in-picture.
 *
 * <p>Android only lets an activity whose manifest entry says {@code supportsPictureInPicture}
 * enter PiP, and the system checks its own copy of that flag - it can't be granted from inside
 * Spotify's process. Spotify's lyrics page doesn't have it; its NowPlayingActivity (the video
 * player's PiP host) does. So PiP opens that activity, marked with {@link #EXTRA_PIP}, mounts the
 * lyrics shell over whatever it shows, and puts it into PiP.
 *
 * <p>The shell is laid out for a full-width screen of the window's shape (Settings.PIP_SHAPE) and scaled down to the window
 * (same shape), so PiP shows the lyrics screen in miniature instead of a layout squeezed into
 * ~200dp. Its controls are left
 * out: nothing in a PiP window can be touched. Spotify's own video PiP is kept out of this host (see hook()).
 * Leaving PiP: a window dismissed while the host is stopped just closes; an expanded one opens
 * the full lyrics screen again in its place.
 */
final class LyricsPipController {
    private static final String PIP_HOST_ACTIVITY =
            "com.spotify.nowplaying.musicinstallation.NowPlayingActivity";
    private static final String EXTRA_PIP = "com.eza.spicyex.LYRICS_PIP";
    private static final String ACTION_CONTROL = "com.eza.spicyex.LYRICS_PIP_CONTROL";
    private static final String EXTRA_CONTROL = "control";
    private static final int CONTROL_PREVIOUS = 1;
    private static final int CONTROL_TOGGLE = 2;
    private static final int CONTROL_NEXT = 3;
    private static final int TAG_PIP_ROOT = 0x53504C50; // SPLP
    private static final long ENTER_TIMEOUT_MS = 4000L;
    /** Width : height of the PiP window, and of the screen the shell is laid out for
     *  (Settings.PIP_SHAPE); read when a host mounts. */
    private Rational aspect = new Rational(3, 4);

    private static Rational aspectSetting(Context context) {
        int[] ratio = com.eza.spicyex.Settings.pipShapeRatio(
                com.eza.spicyex.SpotifyPlusConfig.from(context).get(com.eza.spicyex.Settings.PIP_SHAPE));
        return new Rational(ratio[0], ratio[1]);
    }

    private final NativeSpicyLyricsHook host;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** PiP hosts, and whether each has entered PiP yet. */
    private final WeakHashMap<Activity, Boolean> hosts = new WeakHashMap<>();
    /** The lyrics screen PiP was opened from; closed once PiP is really up. */
    private WeakReference<Activity> opener;
    /** Hosts that are stopped: leaving PiP while stopped means the window was dismissed. */
    private final WeakHashMap<Activity, Boolean> stopped = new WeakHashMap<>();
    private boolean applyingOwnParams;
    private final WeakHashMap<android.widget.TextView, android.graphics.Typeface> keptTypefaces = new WeakHashMap<>();
    private boolean receiverRegistered;
    private boolean lastPlaying;

    LyricsPipController(NativeSpicyLyricsHook host) {
        this.host = host;
    }

    static boolean isSupported(Context context) {
        if (Build.VERSION.SDK_INT < 26 || context == null) return false;
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } catch (Throwable t) {
            return false;
        }
    }

    void hook(ClassLoader loader) {
        if (Build.VERSION.SDK_INT < 26) return;
        XpHooks.findAfter(Activity.class, "onCreate", "pip:Activity#onCreate", param -> {
            Activity activity = (Activity) param.thisObject;
            if (isPipIntent(activity, activity.getIntent())) adopt(activity);
        }, android.os.Bundle.class);
        // launchMode singleTask: an instance already running gets the intent here instead.
        XpHooks.findAfter(Activity.class, "onNewIntent", "pip:Activity#onNewIntent", param -> {
            Activity activity = (Activity) param.thisObject;
            Intent intent = (Intent) param.args[0];
            if (isPipIntent(activity, intent)) {
                activity.setIntent(intent);
                adopt(activity);
            }
        }, Intent.class);
        XpHooks.findAfter(Activity.class, "onResume", "pip:Activity#onResume", param -> {
            Activity activity = (Activity) param.thisObject;
            if (!hosts.containsKey(activity)) return;
            mount(activity); // back on top if anything was added over it
            if (Boolean.FALSE.equals(hosts.get(activity))) main.post(() -> enter(activity));
        });
        XpHooks.findAfter(Activity.class, "onStart", "pip:Activity#onStart", param -> {
            if (hosts.containsKey(param.thisObject)) stopped.remove(param.thisObject);
        });
        XpHooks.findAfter(Activity.class, "onStop", "pip:Activity#onStop", param -> {
            if (hosts.containsKey(param.thisObject)) stopped.put((Activity) param.thisObject, Boolean.TRUE);
        });
        // Keep Spotify's video PiP out of our host. On entering PiP (and on every start while in
        // it) NowPlayingActivity swaps in its video PiP page, which finishes the activity as soon
        // as the current track isn't a video - lyrics PiP closed within half a second - and
        // pushes its own window params. For our host: its PiP callback doesn't run at all, it
        // is told it isn't in PiP, and only our params reach the window.
        try {
            XpHooks.findBefore(PIP_HOST_ACTIVITY, loader, "onPictureInPictureModeChanged",
                    "pip:NowPlayingActivity#onPictureInPictureModeChanged", param -> {
                        Activity activity = (Activity) param.thisObject;
                        if (!hosts.containsKey(activity)) return;
                        param.setResult(null);
                        onModeChanged(activity, (boolean) param.args[0]);
                    }, boolean.class, Configuration.class);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: host hook unavailable " + t);
        }
        // Entering PiP is a configuration change the host handles itself, so every text view in
        // the shell sees onConfigurationChanged - and the platform's TextView (Samsung's, at least)
        // puts the system typeface back on views that never named a font family. The lyrics lost
        // their chosen face (Bold showed as regular, rows built afterwards had it again). Views in
        // a PiP host keep the typeface they had.
        XpHooks.hook(XpReflect.findMethodExact(android.widget.TextView.class, "onConfigurationChanged",
                Configuration.class), "pip:TextView#onConfigurationChanged", param -> {
                    android.widget.TextView view = (android.widget.TextView) param.thisObject;
                    if (hosts.containsKey(view.getContext())) keptTypefaces.put(view, view.getTypeface());
                }, param -> {
                    android.widget.TextView view = (android.widget.TextView) param.thisObject;
                    android.graphics.Typeface kept = keptTypefaces.remove(view);
                    if (kept != null && view.getTypeface() != kept) view.setTypeface(kept);
                });
        XpHooks.findBefore(Activity.class, "isInPictureInPictureMode", "pip:Activity#isInPictureInPictureMode",
                param -> {
                    if (hosts.containsKey(param.thisObject)) param.setResult(false);
                });
        XpHooks.findBefore(Activity.class, "setPictureInPictureParams", "pip:Activity#setPictureInPictureParams",
                param -> {
                    if (hosts.containsKey(param.thisObject) && !applyingOwnParams) param.setResult(null);
                }, PictureInPictureParams.class);
        XpHooks.findBefore(Activity.class, "onDestroy", "pip:Activity#onDestroy", param -> {
            Activity activity = (Activity) param.thisObject;
            if (hosts.remove(activity) != null) unmount(activity);
        });
    }

    /** Closing the lyrics screen with Settings.PIP_ON_CLOSE on: continue it in PiP instead.
     *  False (close as usual) when off, unsupported, or PiP is already up or on its way. */
    boolean openOnClose(Activity from) {
        if (from == null || !isSupported(from) || !hosts.isEmpty() || opener != null) return false;
        if (!Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(from)
                .get(com.eza.spicyex.Settings.PIP_ON_CLOSE))) return false;
        open(from);
        return opener != null;
    }

    /** Starts the PiP host; the lyrics screen closes once the window is up (see enter()). */
    void open(Activity from) {
        if (from == null || !isSupported(from)) return;
        if (hosts.containsValue(Boolean.TRUE)) {
            // Already in PiP. Starting the host again would pull that window back to full
            // screen (it is a single-task activity), so only leave the lyrics screen.
            leave(from);
            return;
        }
        try {
            opener = new WeakReference<>(from);
            Intent intent = new Intent();
            intent.setClassName(from.getPackageName(), PIP_HOST_ACTIVITY);
            intent.putExtra(EXTRA_PIP, true);
            // No open animation: the host stays invisible until it is in PiP with lyrics up.
            from.startActivity(intent,
                    android.app.ActivityOptions.makeCustomAnimation(from, 0, 0).toBundle());
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: opening host");
            // Back was already consumed for PiP: if the window never comes up (the host was not
            // created, or it could not enter PiP), still close the lyrics screen as asked.
            main.postDelayed(() -> {
                Activity pending = opener == null ? null : opener.get();
                if (pending != from) return;
                opener = null;
                XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: not entered, closing lyrics");
                if (!from.isFinishing()) {
                    host.markExplicitLyricsExit(from);
                    from.finish();
                }
            }, ENTER_TIMEOUT_MS + 300L);
        } catch (Throwable t) {
            opener = null;
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: host launch failed " + t);
        }
    }

    private static boolean isPipIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null
                || !PIP_HOST_ACTIVITY.equals(activity.getClass().getName())) return false;
        try {
            return intent.getBooleanExtra(EXTRA_PIP, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void adopt(Activity activity) {
        hosts.put(activity, Boolean.FALSE);
        mount(activity);
        // Nothing of the start is shown - not the host at full screen for the moment before
        // PiP, not the lyrics loading in; the window fades in once both are done (reveal()).
        ViewGroup decor = decor(activity);
        if (decor != null) decor.setAlpha(0f);
        // Nothing is certain about a foreign activity's resume: if PiP never comes up, don't
        // leave a black full-screen page behind.
        main.postDelayed(() -> {
            if (Boolean.FALSE.equals(hosts.get(activity)) && !activity.isFinishing()) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: never entered, closing host");
                hosts.remove(activity);
                unmount(activity);
                activity.finish();
            }
        }, ENTER_TIMEOUT_MS);
    }

    private void enter(Activity activity) {
        if (!Boolean.FALSE.equals(hosts.get(activity)) || activity.isFinishing()) return;
        try {
            boolean entered = activity.enterPictureInPictureMode(params(activity));
            if (!entered) return;
            hosts.put(activity, Boolean.TRUE);
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: entered");
            reveal(activity, SystemClock.uptimeMillis());
            // The full-screen lyrics would only render the same lines a second time.
            Activity from = opener == null ? null : opener.get();
            opener = null;
            if (from != null && from != activity) leave(from);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: enter failed " + t);
        }
    }

    private static final long REVEAL_POLL_MS = 100L;
    private static final long REVEAL_MAX_WAIT_MS = 3000L;
    /** Settle time after the lyrics arrive: the header's artwork and the scroll to the line. */
    private static final long REVEAL_SETTLE_MS = 250L;

    private void reveal(Activity activity, long since) {
        ViewGroup decor = decor(activity);
        if (decor == null || decor.getAlpha() >= 1f || activity.isFinishing()) return;
        NativeSpicyShellView shell = shellOf(decor);
        boolean ready = shell != null && shell.hasLyricsDocument();
        if (!ready && SystemClock.uptimeMillis() - since < REVEAL_MAX_WAIT_MS) {
            main.postDelayed(() -> reveal(activity, since), REVEAL_POLL_MS);
            return;
        }
        main.postDelayed(() -> decor.animate().alpha(1f).setDuration(220L).start(),
                ready ? REVEAL_SETTLE_MS : 0L);
    }

    private static NativeSpicyShellView shellOf(ViewGroup decor) {
        View frame = decor.findViewWithTag(TAG_PIP_ROOT);
        if (!(frame instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) frame;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof NativeSpicyShellView) return (NativeSpicyShellView) group.getChildAt(i);
        }
        return null;
    }

    /** Closes the full-screen lyrics PiP continues, and sends Spotify behind whatever was open
     *  before it: left in front, it shows its now-playing page next to the PiP window. */
    private void leave(Activity from) {
        final int task = from.getTaskId();
        final boolean toBack = !Boolean.FALSE.equals(com.eza.spicyex.SpotifyPlusConfig.from(from)
                .get(com.eza.spicyex.Settings.PIP_LEAVE_SPOTIFY));
        if (!from.isFinishing()) {
            if (toBack) {
                try {
                    from.moveTaskToBack(true);
                } catch (Throwable ignored) {
                }
            }
            host.markExplicitLyricsExit(from);
            from.finish();
            return;
        }
        if (!toBack) return;
        // Spotify sometimes closes the lyrics page itself while the host opens; then whatever
        // of its task is in front (its main screen) goes back instead, once it has resumed.
        main.postDelayed(() -> {
            Activity current = com.eza.spicyex.References.currentActivity();
            if (current == null || hosts.containsKey(current) || current.isFinishing()
                    || current.getTaskId() != task) return;
            try {
                current.moveTaskToBack(true);
            } catch (Throwable ignored) {
            }
        }, 400L);
    }

    private void onModeChanged(Activity activity, boolean inPip) {
        if (inPip) {
            hosts.put(activity, Boolean.TRUE);
            mount(activity);
            return;
        }
        if (!Boolean.TRUE.equals(hosts.get(activity))) return;
        hosts.remove(activity);
        boolean dismissed = stopped.remove(activity) != null;
        unmount(activity);
        if (activity.isFinishing()) return;
        // Expanded rather than dismissed: continue in the real lyrics screen.
        if (!dismissed) host.launchNativeLyricsFullscreen(activity);
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    // --- Shell ---------------------------------------------------------------------------------

    private void mount(Activity activity) {
        try {
            ViewGroup decor = decor(activity);
            if (decor == null) return;
            View existing = decor.findViewWithTag(TAG_PIP_ROOT);
            if (existing != null) {
                existing.bringToFront();
                return;
            }
            int[] screen = screenSize(activity);
            aspect = aspectSetting(activity);
            // The window's short side stands for the screen's short side, so the lyrics keep the
            // size they have on the phone whatever the shape: a portrait shape is the portrait
            // screen cut to that height, a landscape one the landscape screen cut to that width
            // (laid out as landscape - two columns - rather than a stretched portrait page,
            // whose lines came out a fraction of the size).
            final boolean landscape = aspect.getNumerator() > aspect.getDenominator();
            final int virtualW = landscape
                    ? Math.round(screen[0] * (float) aspect.getNumerator() / aspect.getDenominator())
                    : screen[0];
            final int visibleH = landscape
                    ? screen[0]
                    : Math.round(virtualW * (float) aspect.getDenominator() / aspect.getNumerator());
            // The layout keeps room for a status bar that a PiP window doesn't have: lay out
            // that much taller and crop it off the top.
            final int cropTop = NativeLyricsUtils.statusBarClearance(activity);
            final int virtualH = visibleH + cropTop;

            FrameLayout frame = new FrameLayout(activity);
            frame.setTag(TAG_PIP_ROOT);
            frame.setBackgroundColor(Color.BLACK);
            frame.setClickable(true); // nothing reaches Spotify's page underneath
            NativeSpicyShellView shell = new NativeSpicyShellView(host, activity, landscape
                    ? NativeSpicyShellViewImpl.PIP_LAYOUT_LANDSCAPE
                    : NativeSpicyShellViewImpl.PIP_LAYOUT_PORTRAIT);
            shell.setPipPresentation(virtualH, cropTop);
            shell.setPivotX(0f);
            shell.setPivotY(0f);
            frame.addView(shell, new FrameLayout.LayoutParams(virtualW, virtualH));
            frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int w = r - l, h = b - t;
                if (w <= 0 || h <= 0) return;
                float scale = Math.min(w / (float) virtualW, h / (float) visibleH);
                shell.setScaleX(scale);
                shell.setScaleY(scale);
                shell.setTranslationX((w - virtualW * scale) / 2f);
                shell.setTranslationY((h - visibleH * scale) / 2f - cropTop * scale);
            });
            // On the window's decor, not in android:id/content: NowPlayingActivity rebuilds its
            // content after onCreate, which took a shell mounted there with it (PiP then showed
            // Spotify's own now-playing page).
            decor.addView(frame, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shell.start();
            shell.setPipPresentation(virtualH, cropTop);
            ensureReceiver(activity);
            main.removeCallbacks(playStateWatch);
            main.postDelayed(playStateWatch, 1000L);
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: shell mounted " + virtualW + "x" + virtualH);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: mount failed " + t);
        }
    }

    private void unmount(Activity activity) {
        try {
            ViewGroup decor = decor(activity);
            View frame = decor == null ? null : decor.findViewWithTag(TAG_PIP_ROOT);
            if (!(frame instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) frame;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof NativeSpicyShellView) ((NativeSpicyShellView) child).stop();
            }
            decor.removeView(frame);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: unmount failed " + t);
        }
    }

    private static ViewGroup decor(Activity activity) {
        View d = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        return d instanceof ViewGroup ? (ViewGroup) d : null;
    }

    /** The portrait full-screen size in pixels - what the lyrics screen is laid out for. Read
     *  from the display, not the activity: a host already in PiP reports the small window. */
    private static int[] screenSize(Activity activity) {
        int w, h;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return new int[]{Math.min(w, h), Math.max(w, h)};
    }

    // --- Window controls -----------------------------------------------------------------------

    private PictureInPictureParams params(Activity activity) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setActions(actions(activity));
        if (Build.VERSION.SDK_INT >= 31) builder.setSeamlessResizeEnabled(false);
        return builder.build();
    }

    private List<RemoteAction> actions(Activity activity) {
        lastPlaying = host.isPlayerActuallyPlaying();
        List<RemoteAction> list = new ArrayList<>(3);
        if (!Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(activity)
                .get(com.eza.spicyex.Settings.PIP_CONTROLS))) return list;
        list.add(action(activity, CONTROL_PREVIOUS, android.R.drawable.ic_media_previous, "Previous"));
        list.add(lastPlaying
                ? action(activity, CONTROL_TOGGLE, android.R.drawable.ic_media_pause, "Pause")
                : action(activity, CONTROL_TOGGLE, android.R.drawable.ic_media_play, "Play"));
        list.add(action(activity, CONTROL_NEXT, android.R.drawable.ic_media_next, "Next"));
        return list;
    }

    private static RemoteAction action(Activity activity, int control, int icon, String title) {
        Intent intent = new Intent(ACTION_CONTROL)
                .setPackage(activity.getPackageName())
                .putExtra(EXTRA_CONTROL, control);
        PendingIntent pending = PendingIntent.getBroadcast(activity, control, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource("android", icon), title, title, pending);
    }

    private void ensureReceiver(Activity activity) {
        if (receiverRegistered) return;
        Context app = activity.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int control = intent.getIntExtra(EXTRA_CONTROL, 0);
                if (control == CONTROL_PREVIOUS) host.skipToPreviousTrack();
                else if (control == CONTROL_TOGGLE) host.togglePlayPause();
                else if (control == CONTROL_NEXT) host.skipToNextTrack();
                // The play/pause icon follows once the player has actually changed state.
                main.postDelayed(LyricsPipController.this::refreshActions, 400L);
                main.postDelayed(LyricsPipController.this::refreshActions, 1200L);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ACTION_CONTROL);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " lyrics PiP: control receiver failed " + t);
        }
    }

    /** Playback also changes from elsewhere (headset, notification): keep the button honest. */
    private final Runnable playStateWatch = new Runnable() {
        @Override
        public void run() {
            if (hosts.isEmpty()) return;
            main.postDelayed(this, 1000L);
            if (host.isPlayerActuallyPlaying() != lastPlaying) refreshActions();
        }
    };

    private void refreshActions() {
        for (java.util.Map.Entry<Activity, Boolean> e : new ArrayList<>(hosts.entrySet())) {
            Activity activity = e.getKey();
            if (activity == null || !Boolean.TRUE.equals(e.getValue()) || activity.isFinishing()) continue;
            applyingOwnParams = true;
            try {
                activity.setPictureInPictureParams(params(activity));
            } catch (Throwable ignored) {
            } finally {
                applyingOwnParams = false;
            }
        }
    }
}
