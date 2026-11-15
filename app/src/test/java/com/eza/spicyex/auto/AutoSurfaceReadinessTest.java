package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoSurfaceReadinessTest {
    @Test public void unfitWeatherKeepsNativeWithoutDisablingPlayer() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        assertTrue(surfaces.reject("weather", "song:line1"));
        assertFalse(surfaces.allows("weather", "song:line1"));
        assertTrue(surfaces.allows("player", "song:line1"));
        assertTrue(surfaces.allows("player-full", "song:line1"));
    }
    @Test public void repeatedFailureDoesNotTriggerARecompositionLoop() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        assertTrue(surfaces.reject("player", "row"));
        assertFalse(surfaces.reject("player", "row"));
        assertFalse(surfaces.allows("player", "row"));
    }
    @Test public void changedRowOrSettingsRetriesWithoutAPlaybackRestart() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player-full", "line:both");
        assertTrue(surfaces.allows("player-full", "line:main"));
        assertTrue(surfaces.allows("player-full", "next:both"));
    }
    @Test public void changedBoundsRetriesOnlyTheAffectedSurface() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player", "row"); surfaces.reject("player-full", "row");
        surfaces.layoutChanged("player");
        assertTrue(surfaces.allows("player", "row"));
        assertFalse(surfaces.allows("player-full", "row"));
    }

    @Test public void aBoxThatFitsNoPageStaysNativeForEveryLaterRow() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player", "row-a", 365, 62);
        assertFalse(surfaces.allows("player", "row-a"));
        // A new line is a new key; the dashboard slot is the same too-small box.
        assertFalse(surfaces.allows("player", "row-b"));
        assertFalse(surfaces.allows("player", "row-c"));
    }

    @Test public void aResizedBoxRetriesAndOtherSurfacesKeepTheirOwnVerdict() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player", "row", 365, 62);
        assertTrue(surfaces.allows("player-full", "row"));
        assertTrue(surfaces.measured("player", 365, 180));
        assertTrue(surfaces.allows("player", "row-next"));
        assertTrue(surfaces.allows("player-full", "row"));
    }

    @Test public void aMeasuredBoxAloneDoesNotBlockASurfaceThatNeverFailed() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        assertFalse(surfaces.measured("weather", 408, 40));
        assertTrue(surfaces.allows("weather", "row"));
        assertFalse(surfaces.measured("weather", 408, 195));
        assertTrue(surfaces.allows("weather", "row"));
    }

    @Test public void paddedFitFailureDoesNotInventANativeWeatherResize() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.measured("weather", 408, 195);
        surfaces.reject("weather", "credit", 384, 171);
        assertFalse("The native card has not resized", surfaces.measured("weather", 408, 195));
        assertFalse(surfaces.allows("weather", "credit"));
        assertTrue(surfaces.measured("weather", 408, 300));
        assertTrue(surfaces.allows("weather", "credit"));
    }
}
