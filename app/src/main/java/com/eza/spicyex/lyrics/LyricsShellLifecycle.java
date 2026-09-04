package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.eza.spicyex.xposed.XpLog;

/** Android shell lifecycle glue that should not live in renderer state. */
public final class LyricsShellLifecycle {
    private static final String TAG = "[SpotifyPlusShellLifecycle]";

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
