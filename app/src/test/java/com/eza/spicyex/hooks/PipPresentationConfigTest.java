package com.eza.spicyex.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.TypedStore;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The PiP presentation snapshot: all six reads, schema defaults, and ratio parity. */
public class PipPresentationConfigTest {
    @Test
    public void readsAllSixPipSettingsOnce() {
        FakeStore store = new FakeStore();
        store.set(Settings.PIP_ENABLED, true);
        store.set(Settings.PIP_SHAPE, "Landscape");
        store.set(Settings.PIP_FOCUS, "Center");
        store.set(Settings.PIP_CONTROLS, false);
        store.set(Settings.PIP_LEAVE_SPOTIFY, false);
        store.set(Settings.PIP_ALBUM_ART, true);

        PipPresentationConfig pip = PipPresentationConfig.read(store);

        assertTrue(pip.enabled);
        assertEquals("Landscape", pip.shape);
        assertEquals("Center", pip.focus);
        assertFalse(pip.controls);
        assertFalse(pip.leaveSpotify);
        assertTrue(pip.albumArt);
        assertEquals(6, store.reads);
    }

    @Test
    public void unsetValuesKeepTheirSchemaDefaults() {
        PipPresentationConfig pip = PipPresentationConfig.read(new FakeStore());

        assertEquals(Settings.PIP_ENABLED.defaultValue, Boolean.valueOf(pip.enabled));
        assertEquals(Settings.PIP_SHAPE.defaultValue, pip.shape);
        assertEquals(Settings.PIP_FOCUS.defaultValue, pip.focus);
        assertEquals(Settings.PIP_CONTROLS.defaultValue, Boolean.valueOf(pip.controls));
        assertEquals(Settings.PIP_LEAVE_SPOTIFY.defaultValue, Boolean.valueOf(pip.leaveSpotify));
        assertEquals(Settings.PIP_ALBUM_ART.defaultValue, Boolean.valueOf(pip.albumArt));
    }

    @Test
    public void snapshotDoesNotFollowLaterWrites() {
        FakeStore store = new FakeStore();
        store.set(Settings.PIP_ENABLED, true);
        store.set(Settings.PIP_FOCUS, "Center");
        store.set(Settings.PIP_ALBUM_ART, true);

        PipPresentationConfig pip = PipPresentationConfig.read(store);

        store.set(Settings.PIP_ENABLED, false);
        store.set(Settings.PIP_FOCUS, "Same as lyrics screen");
        store.set(Settings.PIP_ALBUM_ART, false);
        assertTrue(pip.enabled);
        assertEquals("Center", pip.focus);
        assertTrue(pip.albumArt);
    }

    @Test
    public void shapeRatioMatchesTheExistingHelper() {
        for (String shape : Settings.PIP_SHAPE.allowedValues) {
            FakeStore store = new FakeStore();
            store.set(Settings.PIP_SHAPE, shape);
            assertArrayEquals(Settings.pipShapeRatio(shape),
                    PipPresentationConfig.read(store).shapeRatio());
        }
        FakeStore unknown = new FakeStore();
        unknown.set(Settings.PIP_SHAPE, "Not a shape");
        assertArrayEquals(Settings.pipShapeRatio(Settings.PIP_SHAPE.defaultValue),
                PipPresentationConfig.read(unknown).shapeRatio());
    }

    private static final class FakeStore implements TypedStore {
        final Map<String, Object> values = new HashMap<>();
        int reads;

        void set(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            reads++;
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }

        @Override
        public boolean contains(Settings.Setting<?> setting) {
            return values.containsKey(setting.key);
        }

        @Override
        public void putBoolean(Settings.BooleanSetting setting, boolean value) {
            values.put(setting.key, value);
        }

        @Override
        public void putString(Settings.StringSetting setting, String value) {
            values.put(setting.key, value);
        }

        @Override
        public void putInt(Settings.IntegerSetting setting, int value) {
            values.put(setting.key, value);
        }
    }
}
