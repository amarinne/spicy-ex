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
}
