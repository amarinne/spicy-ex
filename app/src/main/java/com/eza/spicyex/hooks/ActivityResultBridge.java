package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Delivers the result of an activity this module started from inside Spotify's process (a system
 * file picker, for example) back to the module.
 *
 * <p>The module has no Activity of its own there: {@code startActivityForResult} runs on Spotify's
 * activity, and the result would go to Spotify's {@code onActivityResult}, which knows nothing
 * about it. {@code Activity#dispatchActivityResult} is the framework method every result passes
 * through, whatever the host overrides, so results carrying one of our request codes are taken
 * there and never reach Spotify.
 */
final class ActivityResultBridge {
    private static final String TAG = "[SpicyActivityResult]";
    private static final Map<Integer, Consumer<Intent>> PENDING = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean installed;

    private ActivityResultBridge() {
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        for (Method method : Activity.class.getDeclaredMethods()) {
            if (!"dispatchActivityResult".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 4 || types[1] != int.class || types[2] != int.class
                    || types[3] != Intent.class) continue;
            try {
                XpHooks.hookBefore(method, "activityResult:dispatch", param -> {
                    int requestCode = (Integer) param.args[1];
                    Consumer<Intent> consumer;
                    synchronized (ActivityResultBridge.class) {
                        consumer = PENDING.remove(requestCode);
                    }
                    if (consumer == null) return;
                    int resultCode = (Integer) param.args[2];
                    Intent data = resultCode == Activity.RESULT_OK ? (Intent) param.args[3] : null;
                    param.setResult(null);
                    MAIN.post(() -> {
                        try {
                            consumer.accept(data);
                        } catch (Throwable t) {
                            XpLog.log(TAG + " consumer failed: " + t);
                        }
                    });
                });
            } catch (Throwable t) {
                XpLog.log(TAG + " hook failed: " + t);
            }
        }
    }

    /**
     * Starts {@code intent} for a result that goes to {@code onResult} instead of the host.
     * {@code onResult} gets null when the user backed out or the start failed.
     */
    static void start(Activity activity, Intent intent, int requestCode, Consumer<Intent> onResult) {
        synchronized (ActivityResultBridge.class) {
            PENDING.put(requestCode, onResult);
        }
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Throwable t) {
            synchronized (ActivityResultBridge.class) {
                PENDING.remove(requestCode);
            }
            XpLog.log(TAG + " start failed: " + t);
            onResult.accept(null);
        }
    }
}
