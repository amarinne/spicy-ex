package com.eza.spicyex.settings;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.ContextWrapper;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsShellSettings;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Schema-wide persistence contracts, with the production runtime readers composed. */
public class SettingsCompositionBehaviorTest {
    @Test public void schemaMatchesBehaviorInventory() throws Exception {
        com.google.gson.JsonObject actual = new com.google.gson.JsonObject();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        for (Settings.Setting<?> setting : Settings.ALL) {
            com.google.gson.JsonObject metadata = new com.google.gson.JsonObject();
            metadata.addProperty("type", setting.defaultValue.getClass().getSimpleName());
            metadata.addProperty("section", setting.section.id);
            metadata.add("default", gson.toJsonTree(setting.defaultValue));
            metadata.add("allowed", gson.toJsonTree(setting.allowedValues));
            metadata.addProperty("orientation_specific", setting.isOrientationSpecific());
            if (setting instanceof Settings.IntegerSetting) {
                Settings.IntegerSetting integer = (Settings.IntegerSetting) setting;
                metadata.addProperty("min", integer.minValue); metadata.addProperty("max", integer.maxValue);
                metadata.addProperty("step", integer.stepValue);
            }
            actual.add(setting.key, metadata);
        }
        java.nio.file.Path snapshot = java.nio.file.Paths.get("build", "settings-schema.json");
        java.nio.file.Files.write(snapshot, new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(actual)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Path inventory = java.nio.file.Paths.get("..", "docs", "SETTINGS_BEHAVIOR_INVENTORY.json");
        {
            com.google.gson.JsonObject saved = com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(inventory),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonObject expected = new com.google.gson.JsonObject();
            for (com.google.gson.JsonElement entry : saved.getAsJsonArray("settings")) {
                com.google.gson.JsonObject item = entry.getAsJsonObject();
                expected.add(item.get("key").getAsString(), item.get("schema"));
            }
            assertEquals("Update the setting inventory and owning behavior scenarios", expected, actual);
        }
    }
    private static final class OrientedContext extends ContextWrapper {
        final FakeAndroidContext fake = new FakeAndroidContext();
        final Configuration configuration = new Configuration();
        final Resources resources = new Resources(null, null, null) {
            @Override public Configuration getConfiguration() { return configuration; }
        };
        OrientedContext() { super(null); }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return fake.getSharedPreferences(name, mode);
        }
        @Override public java.io.File getFilesDir() { return fake.getFilesDir(); }
        @Override public java.io.File getCacheDir() { return fake.getCacheDir(); }
        @Override public Resources getResources() { return resources; }
        @Override public android.content.Context getApplicationContext() { return this; }
    }

    @Test public void allSchemaValuesPersistThroughTheRuntimeBoundary() {
        OrientedContext context = new OrientedContext();
        context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        SettingsStore store = new SettingsStore(context);
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        HashSet<String> keys = new HashSet<>();
        for (Settings.Setting<?> setting : Settings.ALL) {
            assertTrue("duplicate key " + setting.key, keys.add(setting.key));
            assertNotNull(setting.section); assertNotNull(setting.label); assertNotNull(setting.defaultValue);
            assertEquals(setting.key, setting.defaultValue, setting.coerce(setting.defaultValue));
            if (setting.allowedValues != null) assertTrue(setting.key, setting.allowedValues.contains(setting.defaultValue));
            exercise(store, config, setting);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void exercise(SettingsStore store, SpotifyPlusConfig config, Settings.Setting setting) {
        List<Object> values = new ArrayList<>(); values.add(setting.defaultValue);
        if (setting.allowedValues != null) values.addAll(setting.allowedValues);
        if (setting instanceof Settings.BooleanSetting) { values.add(true); values.add(false); }
        if (setting instanceof Settings.IntegerSetting) {
            Settings.IntegerSetting integer = (Settings.IntegerSetting) setting;
            assertTrue(setting.key, integer.minValue <= (Integer) setting.defaultValue && (Integer) setting.defaultValue <= integer.maxValue);
            values.add(integer.minValue); values.add(integer.maxValue);
            values.add(integer.minValue - 1); values.add(integer.maxValue + 1);
        }
        for (Object value : values) {
            store.put(setting, value);
            assertEquals(setting.key, setting.coerce(value), config.get(setting));
        }
        store.put(setting, setting.defaultValue);
        assertEquals(setting.key, setting.defaultValue, config.get(setting));
    }

    @Test public void orientationRoundTripPreservesAllScopedValues() {
        OrientedContext context = new OrientedContext();
        SettingsStore store = new SettingsStore(context); SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        for (Settings.Setting<?> setting : Settings.ALL) if (setting.isOrientationSpecific()) {
            context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
            put(store, setting, setting.defaultValue);
            context.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
            assertEquals(setting.key, setting.defaultValue, config.get(setting));
            Object other = alternate(setting); put(store, setting, other);
            assertEquals(setting.key, other, config.get(setting));
            context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
            assertEquals(setting.key, setting.defaultValue, config.get(setting));
            context.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
            assertEquals(setting.key, other, config.get(setting));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void put(SettingsStore store, Settings.Setting setting, Object value) { store.put(setting, value); }
    private static Object alternate(Settings.Setting<?> setting) {
        if (setting instanceof Settings.BooleanSetting) return !((Boolean) setting.defaultValue);
        if (setting instanceof Settings.IntegerSetting) {
            Settings.IntegerSetting integer = (Settings.IntegerSetting) setting;
            return integer.defaultValue == integer.minValue ? integer.maxValue : integer.minValue;
        }
        for (Object value : setting.allowedValues) if (!value.equals(setting.defaultValue)) return value;
        throw new AssertionError("no alternate value for " + setting.key);
    }

    @Test public void shellReadersHonorLandscapeAndLiveWrites() {
        OrientedContext context = new OrientedContext(); SettingsStore store = new SettingsStore(context);
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context); LyricsShellSettings shell = new LyricsShellSettings(context, config);
        context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        store.put(Settings.ANIMATION_STYLE, "Spotlight"); store.put(Settings.LINE_SYNC_FILL, "Top to bottom");
        context.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        store.put(Settings.ANIMATION_STYLE, "Apple Music"); store.put(Settings.LINE_SYNC_FILL, "Left to right (sentence)");
        assertTrue(shell.appleAnimation()); assertFalse(shell.spotlightAnimation()); assertTrue(shell.lineSyncFillSentence());
        store.put(Settings.ANIMATION_STYLE, "Gradient wash"); assertFalse(shell.appleAnimation());
        context.configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        assertTrue(shell.spotlightAnimation()); assertFalse(shell.appleAnimation()); assertTrue(shell.lineSyncFillTopDown());
    }
}
