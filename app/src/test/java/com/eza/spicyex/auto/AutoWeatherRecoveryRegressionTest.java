package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoWeatherRecoveryRegressionTest {
    @Test public void nextRowInvalidatesWeatherEvenWithoutAMountedLyricView() {
        AutoSurfaceReadiness readiness = new AutoSurfaceReadiness();
        assertTrue(readiness.changed("weather", "row-a", true));
        readiness.reject("weather", "row-a");
        assertTrue(readiness.changed("weather", "row-a", true));
        assertFalse(readiness.changed("weather", "row-a", true));
        // The native weather subtree is showing. No lyric view can signal visibility.
        assertTrue(readiness.changed("weather", "row-b", true));
        assertFalse(readiness.changed("weather", "row-b", true));
    }

    @Test public void nativeWeatherResizeRetriesTheSameRejectedRowOnlyOnce() {
        AutoSurfaceReadiness readiness = new AutoSurfaceReadiness();
        assertFalse(readiness.measured("weather", 408, 40));
        readiness.reject("weather", "row");
        assertFalse(readiness.measured("weather", 408, 40));
        assertFalse(readiness.allows("weather", "row"));
        assertTrue(readiness.measured("weather", 408, 195));
        assertTrue(readiness.allows("weather", "row"));
        readiness.reject("weather", "row");
        assertFalse(readiness.measured("weather", 408, 195));
        assertFalse(readiness.allows("weather", "row"));
    }

    @Test public void disposalAndInitialMeasurementCannotLoopRejection() {
        AutoSurfaceReadiness readiness = new AutoSurfaceReadiness();
        readiness.reject("weather", "row");
        assertFalse(readiness.measured("weather", 408, 195));
        assertFalse(readiness.measured("weather", 0, 0));
        assertFalse(readiness.measured("weather", 408, 195));
        assertFalse(readiness.allows("weather", "row"));
        assertTrue(readiness.allows("player", "row"));
    }

    @Test public void disabledWeatherWaitsForEligibilityAfterRowAndBoundsChange() {
        AutoSurfaceReadiness readiness = new AutoSurfaceReadiness();
        assertFalse(readiness.changed("weather", "row-a", false));
        readiness.measured("weather", 408, 40);
        readiness.reject("weather", "row-a");
        readiness.measured("weather", 408, 195);
        assertFalse(readiness.changed("weather", "row-b", false));
        assertTrue(readiness.changed("weather", "row-b", true));
        assertTrue(readiness.changed("weather", "row-b", false));
        assertFalse(readiness.changed("weather", "row-b", false));
    }
}
