package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoDisplaySettingsTest {
    @Test public void extraLineTracksEveryModeExceptMainOnly() {
        assertFalse(AutoDisplaySettings.extraLine(null));
        assertFalse(AutoDisplaySettings.extraLine(state("Main only")));
        assertTrue(AutoDisplaySettings.extraLine(state("Transliteration")));
        assertTrue(AutoDisplaySettings.extraLine(state("Romanization")));
        assertTrue(AutoDisplaySettings.extraLine(state("Translation")));
        assertTrue(AutoDisplaySettings.extraLine(state("Both")));
        assertFalse(AutoDisplaySettings.extraLine(state("not a mode")));
    }

    @Test public void duetToggleUsesTheSchemaDefaultAndTravelsInBundles() {
        assertFalse(AutoDisplaySettings.duet(null));
        assertFalse(AutoDisplaySettings.duet(state("Main only")));
        assertFalse(AutoDisplaySettings.duet(state("Main only", false)));
        assertTrue(AutoDisplaySettings.duet(state("Main only", true)));
        Bundle source = new Bundle();
        source.putBoolean(Settings.AUTO_DUET.key, true);
        Bundle destination = new Bundle();
        AutoDisplaySettings.sanitize(source, destination);
        assertTrue(destination.getBoolean(Settings.AUTO_DUET.key));
    }

    @Test public void duetToggleChangesTheFitSignature() {
        assertNotEquals(AutoDisplaySettings.signature(state("Main only", false)),
                AutoDisplaySettings.signature(state("Main only", true)));
    }

    @Test public void rowPacketWithoutSelectionKeepsThePreviousMode() {
        Bundle clean = new Bundle();
        AutoDisplaySettings.sanitize(new Bundle(), state("Both", true), clean);
        assertEquals("Both", clean.getString(Settings.AUTO_SECONDARY_TEXT.key));
        assertTrue(AutoDisplaySettings.extraLine(clean));
        assertTrue(AutoDisplaySettings.duet(clean));
    }

    @Test public void packetSelectionWinsOverThePreviousMode() {
        Bundle packet = state("Main only", false);
        Bundle clean = new Bundle();
        AutoDisplaySettings.sanitize(packet, state("Both", true), clean);
        assertEquals("Main only", clean.getString(Settings.AUTO_SECONDARY_TEXT.key));
        assertFalse(AutoDisplaySettings.extraLine(clean));
        assertFalse(AutoDisplaySettings.duet(clean));
    }

    @Test public void absentSelectionWithoutHistoryFallsBackToTheDefault() {
        Bundle clean = new Bundle();
        AutoDisplaySettings.sanitize(new Bundle(), null, clean);
        assertEquals("Main only", clean.getString(Settings.AUTO_SECONDARY_TEXT.key));
        assertFalse(AutoDisplaySettings.duet(clean));
    }

    private static Bundle state(String mode) {
        return state(mode, false);
    }

    private static Bundle state(String mode, boolean duet) {
        Bundle value = new Bundle();
        value.putString(Settings.AUTO_SECONDARY_TEXT.key, mode);
        value.putBoolean(Settings.AUTO_DUET.key, duet);
        return value;
    }
}
