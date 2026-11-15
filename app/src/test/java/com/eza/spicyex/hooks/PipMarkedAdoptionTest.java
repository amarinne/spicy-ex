package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Intent;
import android.view.Window;
import com.eza.spicyex.xposed.XpHooks;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipMarkedAdoptionTest {
    @Test public void hostUiStateCallbackCannotOpenSpotifysVideoPipPage() throws Throwable {
        assertHostCallback("onPictureInPictureUiStateChanged",
                new Class<?>[]{android.app.PictureInPictureUiState.class}, new Object[]{null}, false);
    }

    @Test public void hostModeOverloadStillPublishesTheActualMode() throws Throwable {
        assertHostCallback("onPictureInPictureModeChanged", new Class<?>[]{boolean.class},
                new Object[]{true}, true);
        assertHostCallback("onPictureInPictureModeChanged",
                new Class<?>[]{boolean.class, android.content.res.Configuration.class},
                new Object[]{true, null}, true);
    }

    private void assertHostCallback(String name, Class<?>[] types, Object[] args,
                                    boolean modeChanged) throws Throwable {
        LyricsPipController controller = controller();
        java.util.Map<Executable, io.github.libxposed.api.XposedInterface.Hooker> hooks = new java.util.HashMap<>();
        Class<io.github.libxposed.api.XposedInterface> api = io.github.libxposed.api.XposedInterface.class;
        XpHooks.attach(api.cast(proxy(api, (p, method, values) -> {
            Executable target = (Executable) values[0];
            return proxy(io.github.libxposed.api.XposedInterface.HookBuilder.class, (builder, operation, params) -> {
                if ("intercept".equals(operation.getName())) {
                    hooks.put(target, (io.github.libxposed.api.XposedInterface.Hooker) params[0]);
                    return proxy(io.github.libxposed.api.XposedInterface.HookHandle.class, (h, m, a) -> null);
                }
                return builder;
            });
        })));
        try {
            controller.hookHostPipCallbacks(CallbackActivity.class);
            Method callback = CallbackActivity.class.getDeclaredMethod(name, types);
            io.github.libxposed.api.XposedInterface.Hooker installed = hooks.get(callback);
            assertNotNull("Spotify callback must be intercepted: " + name, installed);
            for (boolean owned : new boolean[]{true, false}) {
                CallbackActivity activity = allocate(CallbackActivity.class);
                if (owned) map(controller, "hosts").put(activity, false);
                installed.intercept((io.github.libxposed.api.XposedInterface.Chain) proxy(
                        io.github.libxposed.api.XposedInterface.Chain.class, (p, operation, params) -> {
                            switch (operation.getName()) {
                                case "getExecutable": return callback;
                                case "getThisObject": return activity;
                                case "getArgs": return java.util.Arrays.asList(args);
                                case "proceed": return callback.invoke(activity, args);
                                default: throw new AssertionError(operation);
                            }
                        }));
                assertEquals("ordinary Spotify callbacks must continue", owned ? 0 : 1, activity.callbacks);
                if (owned) assertEquals("UI state must not mark the host as entered",
                        modeChanged, map(controller, "hosts").get(activity));
            }
        } finally {
            XpHooks.unhookAll();
            XpHooks.attach(null);
        }
    }

    private Object proxy(Class<?> type, java.lang.reflect.InvocationHandler handler) {
        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static class CallbackActivity extends RecordingActivity {
        int callbacks;
        @Override public void onPictureInPictureUiStateChanged(android.app.PictureInPictureUiState state) { callbacks++; }
        @Override public void onPictureInPictureModeChanged(boolean inPip) { callbacks++; }
        @Override public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration config) { callbacks++; }
    }

    @Test public void spotifyCannotFinishAnAdoptedOrEnteredLyricsHost() throws Exception {
        LyricsPipController controller = controller();
        RecordingActivity activity = allocate(RecordingActivity.class);
        activity.controller = controller;
        for (boolean entered : new boolean[]{false, true}) {
            map(controller, "hosts").put(activity, entered);
            activity.finish();
            assertFalse("Spotify must not tear down an owned lyrics host", activity.finished);
        }
        map(controller, "hosts").remove(activity);
        activity.finish();
        assertTrue("Releasing ownership must permit host teardown", activity.finished);
    }

    @Test public void ordinarySpotifyActivityCanFinishWhileLyricsPipExists() throws Exception {
        LyricsPipController controller = controller();
        map(controller, "hosts").put(allocate(RecordingActivity.class), true);
        RecordingActivity ordinary = allocate(RecordingActivity.class);
        ordinary.controller = controller;
        ordinary.finish();
        assertTrue(ordinary.finished);
    }

    @Test public void returningToFullscreenReleasesAndFinishesThePipHost() throws Exception {
        LyricsPipController controller = controller();
        RecordingActivity pip = allocate(RecordingActivity.class);
        pip.controller = controller;
        map(controller, "hosts").put(pip, true);
        Method close = LyricsPipController.class.getDeclaredMethod("closeForLyrics", Activity.class);
        close.setAccessible(true);
        close.invoke(controller, allocate(RecordingActivity.class));
        assertTrue(pip.finished);
        assertFalse(map(controller, "hosts").containsKey(pip));
    }

    @Test public void dismissingReleasesTheHostWithoutOpeningFullscreen() throws Exception {
        LyricsPipController controller = controller();
        RecordingActivity pip = allocate(RecordingActivity.class);
        pip.controller = controller;
        map(controller, "hosts").put(pip, true);
        map(controller, "stopped").put(pip, true);
        Method mode = LyricsPipController.class.getDeclaredMethod("onModeChanged", Activity.class, boolean.class);
        mode.setAccessible(true);
        mode.invoke(controller, pip, false);
        assertTrue(pip.finished);
        assertNull(((RecordingHost) field("host").get(controller)).expanded);
        assertFalse(map(controller, "hosts").containsKey(pip));
    }

    @Test public void markedAdoptionRetiresOnlyItsOldForeignLaunch() throws Exception {
        LyricsPipController controller = controller();
        RecordingActivity activity = allocate(RecordingActivity.class);
        RecordingActivity other = allocate(RecordingActivity.class);
        Intent old = allocate(Intent.class);
        WeakHashMap<Activity, Intent> foreign = map(controller, "foreignLaunches");
        foreign.put(activity, old); foreign.put(other, old);

        adoptWithoutUi(controller, activity);

        assertFalse("A marked adoption must retire an earlier mini-player launch", foreign.containsKey(activity));
        assertSame("Other activities keep their own launch", old, foreign.get(other));
    }

    @Test public void expandingAfterMarkedReadoptionReturnsToLyrics() throws Exception {
        LyricsPipController controller = controller();
        RecordingActivity activity = allocate(RecordingActivity.class);
        activity.controller = controller;
        map(controller, "foreignLaunches").put(activity, allocate(Intent.class));
        adoptWithoutUi(controller, activity);
        assertFalse(map(controller, "foreignLaunches").containsKey(activity));

        Method mode = LyricsPipController.class.getDeclaredMethod("onModeChanged", Activity.class, boolean.class);
        mode.setAccessible(true);
        mode.invoke(controller, activity, true);
        mode.invoke(controller, activity, false);

        RecordingHost host = (RecordingHost) field("host").get(controller);
        assertSame(activity, host.expanded);
        assertTrue(activity.finished);
        assertFalse(map(controller, "hosts").containsKey(activity));
    }

    private static LyricsPipController controller() throws Exception {
        LyricsPipController controller = allocate(LyricsPipController.class);
        field("host").set(controller, allocate(RecordingHost.class));
        for (String name : new String[]{"hosts", "foreignLaunches", "stopped"}) {
            field(name).set(controller, new WeakHashMap<>());
        }
        return controller;
    }

    private static void adoptWithoutUi(LyricsPipController controller, Activity activity) throws Exception {
        Method adopt = LyricsPipController.class.getDeclaredMethod("adopt", Activity.class);
        adopt.setAccessible(true);
        // The real adoption runs through launch retirement and host registration. Its deferred UI
        // timer is unavailable on the JVM; the fixture deliberately stops at that boundary.
        try {
            adopt.invoke(controller, activity);
            fail("The missing UI handler must stop the deferred timer");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof NullPointerException);
        }
        assertEquals(Boolean.FALSE, map(controller, "hosts").get(activity));
    }

    private static Field field(String name) throws Exception {
        Field field = LyricsPipController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static <T> WeakHashMap<Activity, T> map(LyricsPipController controller, String name) throws Exception {
        return (WeakHashMap<Activity, T>) field(name).get(controller);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        // Avoid Android constructors without changing the platform mock or production constructors.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    public static class RecordingActivity extends Activity {
        boolean finished;
        LyricsPipController controller;
        @Override public Window getWindow() { return null; }
        @Override public boolean isFinishing() { return false; }
        @Override public void finish() {
            if (controller != null) {
                try {
                    Constructor<XpHooks.XpParam> constructor = XpHooks.XpParam.class.getDeclaredConstructor(
                            Object.class, Object[].class, Executable.class);
                    constructor.setAccessible(true);
                    XpHooks.XpParam param = constructor.newInstance(this, new Object[0],
                            RecordingActivity.class.getMethod("finish"));
                    controller.beforeHostFinish(param);
                    if (param.hasResult()) return;
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
            finished = true;
        }
        @Override public void overridePendingTransition(int enter, int exit) { }
    }

    public static class RecordingHost extends NativeSpicyLyricsHook {
        Activity expanded;
        public RecordingHost() { super(null); }
        @Override public boolean launchNativeLyricsFullscreen(Activity activity) {
            expanded = activity;
            return true;
        }
    }
}
