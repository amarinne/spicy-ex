package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.testsupport.FakePrefs;

import org.junit.Test;

/**
 * The retired "Subtle line" glow choice renders like "Word only", so a stored one migrates.
 */
public class LiveCardGlowMigrationTest {
    @Test public void theRetiredChoiceBecomesTheOneThatRendersTheSame() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LIVE_CARD_GLOW.key, "Subtle line");
        SettingsStore.migrateLiveCardGlow(prefs);
        assertEquals("Word only", prefs.values.get(Settings.LIVE_CARD_GLOW.key));
    }

    @Test public void theRemainingChoicesStayAsTheyAre() {
        for (String choice : new String[]{"Off", "Word only"}) {
            FakePrefs prefs = new FakePrefs();
            prefs.values.put(Settings.LIVE_CARD_GLOW.key, choice);
            SettingsStore.migrateLiveCardGlow(prefs);
            assertEquals(choice, prefs.values.get(Settings.LIVE_CARD_GLOW.key));
        }
    }

    @Test public void anAbsentValueWritesNothing() {
        FakePrefs prefs = new FakePrefs();
        SettingsStore.migrateLiveCardGlow(prefs);
        assertFalse(prefs.values.containsKey(Settings.LIVE_CARD_GLOW.key));
    }

    /** The choice list the card editor offers no longer has the retired value. */
    @Test public void theSchemaOffersOnlyTheTwoRemainingChoices() {
        assertEquals(2, Settings.LIVE_CARD_GLOW.allowedValues.size());
        assertTrue(Settings.LIVE_CARD_GLOW.allowedValues.contains("Off"));
        assertTrue(Settings.LIVE_CARD_GLOW.allowedValues.contains("Word only"));
    }
}
