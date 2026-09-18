package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.eza.spicyex.xposed.XpLog;

/** Android shell lifecycle glue that should not live in renderer state. */
public final class LyricsShellLifecycle {
    private static final String TAG = "[SpotifyPlusShellLifecycle]";
    // Fullscreen lyrics back is owned by the takeover hook (session-gated OVERLAY callback,
    // including the rotation window with no mounted root). Registering here as well would be
    // a needless duplicate priority registration on the same activity.
    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";

    private final Activity activity;
    private final Runnable backAction;
    private OnBackInvokedCallback backInvokedCallback;

    public LyricsShellLifecycle(Activity activity, Runnable backAction) {
        this.activity = activity;
        this.backAction = backAction;
    }

    public void start() {
        registerBackGestureCallback();
    }

    public void stop() {
        unregisterBackGestureCallback();
    }

    private void registerBackGestureCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backInvokedCallback != null) return;
        // Fullscreen owner is the takeover hook; this path stays for non-fullscreen owned
        // overlays (e.g. now-playing artwork). String match keeps the lyrics->hooks
        // dependency direction intact. Settings dialog back is separate (dialog window +
        // OnKeyListener) and unaffected.
        if (activity != null && LYRICS_FULLSCREEN_ACTIVITY.equals(activity.getClass().getName())) return;
        try {
            backInvokedCallback = () -> {
                if (backAction != null) backAction.run();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback);
        } catch (Throwable t) {
            backInvokedCallback = null;
            XpLog.log(TAG + " back gesture callback registration failed: " + t);
        }
    }

    private void unregisterBackGestureCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backInvokedCallback == null) return;
        try {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        } catch (Throwable t) {
            XpLog.log(TAG + " back gesture callback unregister failed: " + t);
        } finally {
            backInvokedCallback = null;
        }
    }
}
