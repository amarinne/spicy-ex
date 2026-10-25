package com.eza.spicyex.lyrics;

import android.graphics.Bitmap;
import android.view.View;
import com.eza.spicyex.ambient.AmbientBackgroundLayer;
import com.eza.spicyex.ambient.AmbientShaderEffect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real controller ordering without constructing Android's unavailable JVM views. */
public class LyricsAmbientEffectLifecycleTest {
    @Test public void noArtworkReleaseThenMeshResumesThroughController() throws Exception {
        Layer layer = new Layer();
        LyricsAmbientController controller = controller(layer);
        missingArtwork(controller);
        assertFalse(layer.enabled);

        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);

        assertTrue(layer.enabled);
        assertEquals(Arrays.asList("release", "mesh", "resume"), layer.events);
    }

    @Test public void pausedNoArtworkLayerStaysPausedUntilOwnerResumes() throws Exception {
        Layer layer = new Layer();
        LyricsAmbientController controller = controller(layer);
        missingArtwork(controller);
        controller.pauseAnimation();
        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
        assertFalse(layer.enabled);
        assertFalse(layer.events.contains("resume"));
        controller.resumeAnimation();
        assertTrue(layer.enabled);
    }

    @Test public void disabledOrStoppedLayerDoesNotResumeOnSelection() throws Exception {
        for (String gate : new String[]{"textureEnabled", "active"}) {
            Layer layer = new Layer();
            LyricsAmbientController controller = controller(layer);
            missingArtwork(controller);
            set(controller, gate, false);
            controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
            assertFalse(gate, layer.enabled);
            assertFalse(gate, layer.events.contains("resume"));
        }
    }

    @Test public void rejectedSelectionRetainsControllerStateAndAllowsExplicitRetry() throws Exception {
        Layer layer = new Layer();
        LyricsAmbientController controller = controller(layer);
        missingArtwork(controller);
        layer.reject = true;
        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
        assertEquals(AmbientShaderEffect.KAWARP, get(controller, "effect"));
        assertFalse(layer.enabled);
        assertEquals(Arrays.asList("release", "mesh"), layer.events);

        layer.reject = false;
        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
        assertEquals(AmbientShaderEffect.MESH_GRADIENT, get(controller, "effect"));
        assertTrue(layer.enabled);
        assertEquals(Arrays.asList("release", "mesh", "mesh", "resume"), layer.events);
    }

    @Test public void returningToKawarpWithoutArtReleasesAgain() throws Exception {
        Layer layer = new Layer();
        LyricsAmbientController controller = controller(layer);
        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
        controller.setEffect(AmbientShaderEffect.KAWARP);
        assertFalse(layer.enabled);
        controller.setEffect(AmbientShaderEffect.MESH_GRADIENT);
        assertTrue(layer.enabled);
        assertEquals(Arrays.asList("mesh", "resume", "kawarp", "release", "mesh", "resume"),
                layer.events);
    }

    private static LyricsAmbientController controller(Layer layer) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        LyricsAmbientController controller = (LyricsAmbientController) unsafe
                .getMethod("allocateInstance", Class.class).invoke(field.get(null), LyricsAmbientController.class);
        set(controller, "animatedBackground", layer);
        set(controller, "effect", AmbientShaderEffect.DEFAULT);
        set(controller, "active", true);
        set(controller, "textureEnabled", true);
        set(controller, "desiredArtImageId", "");
        return controller;
    }

    private static void missingArtwork(LyricsAmbientController controller) throws Exception {
        Method method = LyricsAmbientController.class.getDeclaredMethod("updateAnimatedBackgroundArt",
                String.class, LyricsAmbientController.RunningState.class);
        method.setAccessible(true);
        method.invoke(controller, "", null);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = LyricsAmbientController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = LyricsAmbientController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Layer implements AmbientBackgroundLayer {
        final List<String> events = new ArrayList<>();
        AmbientShaderEffect effect = AmbientShaderEffect.DEFAULT;
        boolean enabled = true;
        boolean reject;
        public boolean setEffect(AmbientShaderEffect next) {
            events.add(next.argument());
            if (reject) return false;
            effect = next;
            return true;
        }
        public boolean usesArtworkTexture() { return effect.requiresArtworkTexture(); }
        public void release() { enabled = false; events.add("release"); }
        public void pauseRendering() { enabled = false; }
        public void resumeRendering() { enabled = true; events.add("resume"); }
        public void updateImage(Bitmap art) { fail("No artwork should be loaded"); }
        public View asView() { throw new AssertionError("No view should be touched"); }
    }
}
