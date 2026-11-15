package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Delivers the result of an activity this module started from inside Spotify's process (a system
 * file picker, for example) back to the module.
 *
 * <p>The module has no Activity of its own there: {@code startActivityForResult} runs on Spotify's
 * activity, and the result would go to Spotify's {@code onActivityResult}, which knows nothing
 * about it. {@code Activity#dispatchActivityResult} is the framework method every result passes
 * through, whatever the host overrides, so results carrying one of our request codes are taken
 * there and never reach Spotify.
 *
 * <p>Ownership and lifecycle live in {@link ActivityResultRequests}: a result is matched to the
 * Activity that started it, only one attempt per Activity and code is pending, and destruction
 * releases the callback before its posted delivery can run.
 */
final class ActivityResultBridge {
    private static final String TAG = "[SpicyActivityResult]";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ActivityResultRequests REQUESTS =
            new ActivityResultRequests(MAIN::post);
    private static final Installation INSTALLATION = new Installation(REQUESTS);

    private ActivityResultBridge() {
    }

    static void install() {
        if (!INSTALLATION.install(new XposedInstaller())) {
            XpLog.log(TAG + " hooks not installed; activity results cannot be delivered");
        }
    }

    /**
     * Starts {@code intent} for a result that goes to {@code onResult} instead of the host.
     * {@code onResult} gets null when the user backed out, the launch failed, or another launch is
     * already waiting for the same code on this Activity.
     */
    static void start(Activity activity, Intent intent, int requestCode, Consumer<Intent> onResult) {
        INSTALLATION.start(activity, requestCode, onResult,
                code -> activity.startActivityForResult(intent, code));
    }

    /** Matches the framework's Activity-level result dispatch overloads across API levels. */
    static boolean isResultDispatch(Method method) {
        if (!"dispatchActivityResult".equals(method.getName())) return false;
        Class<?>[] types = method.getParameterTypes();
        return types.length >= 4
                && types[0] == String.class
                && types[1] == int.class
                && types[2] == int.class
                && types[3] == Intent.class;
    }

    /** Installs the module's hooks; implements the package-local seam used by bridge tests. */
    interface HookInstaller {
        void install(ActivityResultRequests requests) throws Throwable;

        void uninstall();
    }

    /** Becomes ready only after every required hook installed, and retries safely after failure. */
    static final class Installation {
        private final ActivityResultRequests requests;
        private boolean ready;

        Installation(ActivityResultRequests requests) {
            this.requests = requests;
        }

        synchronized boolean install(HookInstaller installer) {
            if (ready) return true;
            try {
                installer.install(requests);
            } catch (Throwable t) {
                XpLog.log(TAG + " hook install failed: " + t);
                try {
                    installer.uninstall();
                } catch (Throwable ignored) {
                }
                return false;
            }
            ready = true;
            return true;
        }

        synchronized boolean isReady() {
            return ready;
        }

        void start(Object activity, int requestCode, Consumer<Intent> onResult,
                   ActivityResultRequests.Launch launch) {
            if (!isReady()) {
                XpLog.log(TAG + " start refused: result hooks are unavailable");
                if (onResult != null) onResult.accept(null);
                return;
            }
            requests.start(activity, requestCode, onResult, launch);
        }
    }

    private static final class XposedInstaller implements HookInstaller {
        private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

        @Override
        public void install(ActivityResultRequests requests) throws Throwable {
            boolean dispatchHooked = false;
            for (Method method : Activity.class.getDeclaredMethods()) {
                if (!isResultDispatch(method)) continue;
                add(XpHooks.hookBefore(method, XpHooks.id("activityResult:dispatch", method),
                        param -> onDispatch(requests, param)));
                dispatchHooked = true;
            }
            if (!dispatchHooked) {
                throw new IllegalStateException("Activity.dispatchActivityResult not found");
            }
            add(XpHooks.findBefore(Activity.class, "onDestroy", "activityResult:Activity#onDestroy",
                    param -> requests.destroy(param.thisObject)));
        }

        private void add(XposedInterface.HookHandle handle) throws Throwable {
            if (handle == null) {
                throw new IllegalStateException("hook framework returned no handle");
            }
            handles.add(handle);
        }

        @Override
        public void uninstall() {
            for (XposedInterface.HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            handles.clear();
        }
    }

    private static void onDispatch(ActivityResultRequests requests, XpHooks.XpParam param) {
        // A named who means a fragment or permission result; only the Activity's own result is ours.
        if (param.args.length < 4 || param.args[0] != null) return;
        if (!(param.thisObject instanceof Activity)) return;
        int requestCode = (Integer) param.args[1];
        int resultCode = (Integer) param.args[2];
        Intent data = (Intent) param.args[3];
        if (requests.dispatch(param.thisObject, requestCode, resultCode == Activity.RESULT_OK, data)) {
            param.setResult(null);
        }
    }
}
