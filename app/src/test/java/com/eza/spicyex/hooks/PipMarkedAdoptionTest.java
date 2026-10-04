package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Intent;
import android.view.Window;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipMarkedAdoptionTest {
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
        @Override public Window getWindow() { return null; }
        @Override public boolean isFinishing() { return false; }
        @Override public void finish() { finished = true; }
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
