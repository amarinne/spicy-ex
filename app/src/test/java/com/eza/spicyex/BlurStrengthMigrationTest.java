package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsBlurCurve;
import com.eza.spicyex.settings.BlurStrength;
import com.eza.spicyex.testsupport.FakePrefs;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The v2.0.41 blur pair (a selector and an intensity percentage) becomes one blur strength.
 *
 * <p>The proof is about what the user sees: every pair a v2.0.41 install could hold must render
 * the same blur as before. The renderer reads only the enabled flag, the distance curve and the
 * quality multiplier, so the test compares those and the blur they produce at every distance.
 */
public class BlurStrengthMigrationTest {
    /** The selectors the v2.0.41 schema allowed. */
    private static final List<String> LEVELS = Arrays.asList("Off", "Slight", "Heavy");
    /** The intensity percentages the v2.0.41 schema allowed. */
    private static final List<Integer> INTENSITIES = Arrays.asList(
            25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115,
            120, 125, 130, 135, 140, 145, 150, 155, 160, 165, 170, 175, 180, 185, 190, 195,
            200, 205, 210, 215, 220, 225, 230, 235, 240, 245, 250);

    @Test public void everyV2041PairRendersIdentically() {
        for (String level : LEVELS) {
            for (int intensity : INTENSITIES) {
                FakePrefs prefs = new FakePrefs();
                prefs.values.put(SettingsStore.LEGACY_ENABLE_LINE_BLUR, level);
                prefs.values.put(SettingsStore.LEGACY_LYRICS_BLUR_INTENSITY, intensity);
                SettingsStore.migrateBlurStrength(prefs);

                Object migrated = prefs.values.get(Settings.LYRICS_BLUR_STRENGTH.key);
                assertTrue(level + "/" + intensity + " must migrate", migrated instanceof Integer);
                int strength = (Integer) migrated;

                boolean legacyEnabled = !"Off".equals(level);
                boolean legacyHeavy = "Heavy".equals(level);
                float legacyQuality = intensity / 100f;
                boolean enabled = strength > 0;
                boolean heavy = BlurStrength.standardCurve(strength);
                float quality = BlurStrength.percent(strength) / 100f;

                assertEquals(level + "/" + intensity + ": the blur stays on or off",
                        legacyEnabled, enabled);
                assertEquals(level + "/" + intensity + ": the same distance curve",
                        legacyHeavy, heavy);
                if (legacyEnabled) {
                    assertEquals(level + "/" + intensity + ": the same quality",
                            legacyQuality, quality, 0.0001f);
                }
                for (int distance = 1; distance <= 8; distance++) {
                    for (boolean emphasized : new boolean[]{false, true}) {
                        assertEquals(level + "/" + intensity + ": blur at " + distance,
                                rendered(legacyEnabled, distance, emphasized, legacyHeavy, false,
                                        legacyQuality),
                                rendered(enabled, distance, emphasized, heavy, false, quality),
                                0.0001f);
                        assertEquals(level + "/" + intensity + ": apple blur at " + distance,
                                rendered(legacyEnabled, distance, emphasized, legacyHeavy, true,
                                        legacyQuality),
                                rendered(enabled, distance, emphasized, heavy, true, quality),
                                0.0001f);
                    }
                }
            }
        }
    }

    /** What the renderer draws: a disabled blur draws nothing, whatever the curve would say. */
    private static float rendered(boolean enabled, int distance, boolean emphasized,
            boolean standard, boolean apple, float quality) {
        return enabled ? LyricsBlurCurve.blurPx(distance, emphasized, standard, apple, quality) : 0f;
    }

    @Test public void anAbsentPairWritesNothing() {        FakePrefs prefs = new FakePrefs();
        SettingsStore.migrateBlurStrength(prefs);
        assertFalse(prefs.values.containsKey(Settings.LYRICS_BLUR_STRENGTH.key));
    }

    @Test public void anAlreadyMigratedValueIsLeftAlone() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LYRICS_BLUR_STRENGTH.key, 175);
        SettingsStore.migrateBlurStrength(prefs);
        assertEquals(175, prefs.values.get(Settings.LYRICS_BLUR_STRENGTH.key));
    }

    /** The landscape pair keeps its own value, so the two orientations stay independent. */
    @Test public void theLandscapePairMigratesToItsOwnValue() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsStore.LEGACY_ENABLE_LINE_BLUR, "Slight");
        prefs.values.put(SettingsStore.LEGACY_LYRICS_BLUR_INTENSITY, 100);
        prefs.values.put(SettingsStore.LEGACY_ENABLE_LINE_BLUR + "_ls", "Heavy");
        prefs.values.put(SettingsStore.LEGACY_LYRICS_BLUR_INTENSITY + "_ls", 40);
        SettingsStore.migrateBlurStrength(prefs);

        assertEquals(BlurStrength.legacySlight(100),
                prefs.values.get(Settings.LYRICS_BLUR_STRENGTH.key));
        assertEquals(40, prefs.values.get(Settings.LYRICS_BLUR_STRENGTH.key + "_ls"));
    }
}
