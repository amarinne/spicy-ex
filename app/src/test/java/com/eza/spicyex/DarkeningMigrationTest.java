package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.testsupport.FakePrefs;

import org.junit.Test;

/**
 * The v2.0.41 force switch and its level become one darkening value; 0 is off.
 *
 * <p>The renderer darkens by the level when the switch was on, so a switched-off install keeps
 * an undarkened background and a switched-on install keeps its level.
 */
public class DarkeningMigrationTest {
    @Test public void aForcedBackgroundKeepsItsLevel() {        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsStore.LEGACY_FORCE_DARK_BACKGROUND, true);
        prefs.values.put(SettingsStore.LEGACY_EXTRA_DARK_BACKGROUND, 60);
        SettingsStore.migrateDarkening(prefs);
        assertEquals(60, prefs.values.get(Settings.LYRICS_DARKENING.key));
    }

    @Test public void anUnforcedBackgroundBecomesOff() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsStore.LEGACY_FORCE_DARK_BACKGROUND, false);
        prefs.values.put(SettingsStore.LEGACY_EXTRA_DARK_BACKGROUND, 60);
        SettingsStore.migrateDarkening(prefs);
        assertEquals(0, prefs.values.get(Settings.LYRICS_DARKENING.key));
    }

    /** The value the renderer reads: 0 leaves the background alone, otherwise it darkens. */
    @Test public void theMigratedValueDarkensExactlyAsThePairDid() {
        // The v2.0.41 editor always wrote the force switch from the level, so a forced
        // background always carried a level above zero; those are the pairs an install holds.
        for (int level : new int[]{5, 35, 60, 100}) {
            for (boolean forced : new boolean[]{true, false}) {
                FakePrefs prefs = new FakePrefs();
                prefs.values.put(SettingsStore.LEGACY_FORCE_DARK_BACKGROUND, forced);
                prefs.values.put(SettingsStore.LEGACY_EXTRA_DARK_BACKGROUND, level);
                SettingsStore.migrateDarkening(prefs);
                Object migrated = prefs.values.get(Settings.LYRICS_DARKENING.key);
                assertTrue(migrated instanceof Integer);
                int darkening = (Integer) migrated;

                boolean legacyDark = forced;
                int legacyLevel = forced ? level : 0;
                assertEquals("the same dark switch", legacyDark, darkening > 0);
                assertEquals("the same level", legacyLevel, darkening);
                assertEquals("the same brightness",
                        legacyDark ? Math.max(0f, Math.min(1f, 1f - legacyLevel / 100f)) : 1f,
                        darkening > 0 ? Math.max(0f, Math.min(1f, 1f - darkening / 100f)) : 1f,
                        0.0001f);
            }
        }
    }

    /** A forced background with no level keeps the default level, so it still darkens. */
    @Test public void aForcedBackgroundWithoutALevelKeepsTheDefaultLevel() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsStore.LEGACY_FORCE_DARK_BACKGROUND, true);
        SettingsStore.migrateDarkening(prefs);
        assertEquals(Settings.LYRICS_DARKENING.defaultValue,
                prefs.values.get(Settings.LYRICS_DARKENING.key));
    }

    /**
     * A forced background whose level was zero keeps the stored level, which is off. The v2.0.41
     * editor wrote the switch from the level, so it never stored that pair itself.
     */
    @Test public void aForcedBackgroundAtZeroBecomesOff() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsStore.LEGACY_FORCE_DARK_BACKGROUND, true);
        prefs.values.put(SettingsStore.LEGACY_EXTRA_DARK_BACKGROUND, 0);
        SettingsStore.migrateDarkening(prefs);
        assertEquals(0, prefs.values.get(Settings.LYRICS_DARKENING.key));
    }

    @Test public void anAbsentPairWritesNothing() {
        FakePrefs prefs = new FakePrefs();
        SettingsStore.migrateDarkening(prefs);
        assertFalse(prefs.values.containsKey(Settings.LYRICS_DARKENING.key));
    }

    @Test public void anAlreadyMigratedValueIsLeftAlone() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LYRICS_DARKENING.key, 20);
        SettingsStore.migrateDarkening(prefs);
        assertEquals(20, prefs.values.get(Settings.LYRICS_DARKENING.key));
    }
}
