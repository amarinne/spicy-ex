package com.eza.spicyex;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Schema coercion behavior, independent of which values the schema currently declares. */
public class SettingsDefaultsTest {
    @Test
    public void allowedValuesRoundTripThroughCoerce() {
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (!(setting instanceof Settings.StringSetting)) continue;
            if (setting.allowedValues == null) continue;
            for (Object value : setting.allowedValues) {
                assertEquals(setting.key, value, setting.coerce(value));
            }
        }
    }

    @Test
    public void unknownChoiceFallsBackToTheDeclaredDefault() {
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (!(setting instanceof Settings.StringSetting)) continue;
            if (setting.allowedValues == null || setting.allowedValues.isEmpty()) continue;
            assertEquals(setting.key, setting.defaultValue, setting.coerce("not a stored value"));
        }
    }

    @Test
    public void coerceIsIdempotent() {
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (!(setting instanceof Settings.StringSetting)) continue;
            Object once = setting.coerce("not a stored value");
            assertEquals(setting.key, once, setting.coerce(once));
        }
    }
}
