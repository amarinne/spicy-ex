package com.eza.spicyex.settings.registry;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Registry consumers pass original setting identities through the existing writer and store. */
public class RegistryOrientationTest {
    private static final class OrientedContext extends ContextWrapper {
        final FakeAndroidContext fake = new FakeAndroidContext();
        final Configuration configuration = new Configuration();
        final Resources resources = new Resources(null, null, null) {
            @Override public Configuration getConfiguration() { return configuration; }
        };
        OrientedContext() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return fake.getSharedPreferences(name, mode);
        }
        @Override public java.io.File getFilesDir() { return fake.getFilesDir(); }
        @Override public Resources getResources() { return resources; }
        @Override public Context getApplicationContext() { return this; }
    }

    @Test public void everyEditorSettingRoundTripsThroughTheWriterAcrossOrientations() {
        OrientedContext context = new OrientedContext();
        SettingsStore store = new SettingsStore(context);
        SettingsWriter writer = new SettingsWriter(store);
        Set<Settings.Setting<?>> owned = new LinkedHashSet<>();
        for (ControlSpec row : ControlRegistry.editorControls()) {
            if (row.setting != null) owned.add(row.setting);
            if (row.customValue != null) owned.add(row.customValue);
        }
        for (Settings.Setting<?> setting : owned) {
            assertSame("Registry keeps the persistence identity", setting,
                    Settings.ALL.stream().filter(candidate -> candidate.key.equals(setting.key)).findFirst().get());
            context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
            write(writer, setting, setting.defaultValue);
            SettingsSnapshot portrait = SettingsSnapshot.capture(store);
            assertEquals(setting.defaultValue, portrait.get(setting));
            context.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
            assertEquals("Landscape initially inherits portrait: " + setting.key, setting.defaultValue, store.get(setting));
            Object other = alternate(setting);
            write(writer, setting, other);
            SettingsSnapshot landscape = SettingsSnapshot.capture(store);
            assertEquals(setting.key, other, landscape.get(setting));
            String landscapeKey = Settings.landscapeKey(context, setting);
            assertEquals(setting.key, setting.isOrientationSpecific(), landscapeKey != null);
            context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
            assertEquals(setting.key, setting.isOrientationSpecific() ? setting.defaultValue : other, store.get(setting));
            assertEquals("Snapshots retain their captured orientation", setting.defaultValue, portrait.get(setting));
            context.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
            assertEquals(setting.key, other, store.get(setting));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void write(SettingsWriter writer, Settings.Setting setting, Object value) {
        writer.put(setting, value);
    }

    private static Object alternate(Settings.Setting<?> setting) {
        if (setting instanceof Settings.BooleanSetting) return !((Boolean) setting.defaultValue);
        if (setting instanceof Settings.IntegerSetting) {
            Settings.IntegerSetting integer = (Settings.IntegerSetting) setting;
            return integer.defaultValue == integer.minValue ? integer.maxValue : integer.minValue;
        }
        if (setting.allowedValues != null) {
            for (Object value : setting.allowedValues) if (!value.equals(setting.defaultValue)) return value;
        }
        return setting == Settings.FULLSCREEN_CONTROLS ? "17 seconds" : "serif";
    }
}
