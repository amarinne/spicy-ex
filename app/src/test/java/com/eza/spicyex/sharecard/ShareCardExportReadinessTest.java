package com.eza.spicyex.sharecard;

import android.widget.FrameLayout;
import com.eza.spicyex.SpotifyTrack;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShareCardExportReadinessTest {
    // Android view construction is unavailable on the JVM. Exercise the controller's real gates
    // without constructing its UI; render stops at the first activity access after invalidation.
    private static final Object UNSAFE;
    private static final Method ALLOCATE;
    static {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field field = unsafe.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = field.get(null);
            ALLOCATE = unsafe.getMethod("allocateInstance", Class.class);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private LyricsShareCardController controller() throws Exception {
        LyricsShareCardController controller = (LyricsShareCardController)
                ALLOCATE.invoke(UNSAFE, LyricsShareCardController.class);
        field("track").set(controller,
                new SpotifyTrack("Song", "Artist", "Album", "spotify:track:id", 0, "", 0, null, 1000, false));
        field("overlay").set(controller, ALLOCATE.invoke(UNSAFE, FrameLayout.class));
        return controller;
    }

    private static Field field(String name) throws Exception {
        Field field = LyricsShareCardController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test public void startingRenderRetiresPreviousImageEvenIfRenderFails() throws Exception {
        LyricsShareCardController controller = controller();
        CardRecipe previous = new CardRecipe(rounded -> { throw new AssertionError("Old image exported"); });
        field("currentRecipe").set(controller, previous);
        Class<?> transition = Class.forName(LyricsShareCardController.class.getName() + "$Transition");
        Method render = LyricsShareCardController.class.getDeclaredMethod("render", transition);
        render.setAccessible(true);
        try {
            render.invoke(controller, transition.getEnumConstants()[0]);
            fail("The incomplete UI fixture must stop rendering");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof NullPointerException);
        }
        assertEquals(1, field("generation").getInt(controller));
        assertNull("Pending or failed render must not retain the previous image", field("currentRecipe").get(controller));
        assertExportsWait(controller);
    }

    @Test public void allExportPathsWaitForACompletedRecipe() throws Exception {
        assertExportsWait(controller());
    }

    private static void assertExportsWait(LyricsShareCardController controller) throws Exception {
        Method save = LyricsShareCardController.class.getDeclaredMethod("saveOnly");
        save.setAccessible(true);
        save.invoke(controller);
        Method share = LyricsShareCardController.class.getDeclaredMethod("shareCard", android.content.ComponentName.class);
        share.setAccessible(true);
        // The selection fixture is deliberately absent: pending renders must return before reading
        // a new selection or queueing an export with an old image.
        share.invoke(controller, new Object[] {null});
        assertFalse(controller.agentCapture());
    }
}
