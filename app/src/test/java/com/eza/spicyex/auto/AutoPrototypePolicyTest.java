package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoPrototypePolicyTest {
    @Test public void shortFullPlayerRemovesProgressOnlyDuringSpotifyTakeover() {
        assertTrue(AutoPrototypePolicy.suppressFullProgress(true, true, 340));
        assertTrue(AutoPrototypePolicy.suppressFullProgress(true, true, 400));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(true, true, 401));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(true, true, 640));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(false, true, 400));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(true, false, 400));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(true, true, 0));
        assertFalse(AutoPrototypePolicy.suppressFullProgress(true, true, Float.NaN));
    }
    @Test public void fullPlayerRequiresSyncedLyricsButStressStillExercisesTheSurface() {
        assertFalse(AutoPrototypePolicy.fullPlayerTakeover(true, false, false, true));
        assertTrue(AutoPrototypePolicy.fullPlayerTakeover(true, true, false, true));
        assertTrue(AutoPrototypePolicy.fullPlayerTakeover(true, false, true, true));
        assertFalse(AutoPrototypePolicy.fullPlayerTakeover(false, true, true, true));
        assertTrue(AutoPrototypePolicy.fullPlayerTakeover(true, false, false, false));
        assertFalse(AutoPrototypePolicy.fullPlayerTakeover(false, false, false, false));
    }
    @Test public void noFitAndPauseReleaseRestoreFullBadge() {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player-full", "row");
        boolean surfaceReadyFull = surfaces.allows("player-full", "row");
        assertFalse(surfaceReadyFull);
        boolean noFitTakeover = AutoPrototypePolicy.fullPlayerTakeover(surfaceReadyFull, true, false, true);
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        ownership.enter(new Object(), true, false);
        assertFalse(ownership.suppress(noFitTakeover, true));
        AutoPrototypePolicy.PlaybackHold hold = new AutoPrototypePolicy.PlaybackHold();
        assertTrue(hold.update(true, true, 100));
        assertTrue(hold.update(true, false, 200));
        boolean pausedTakeover = AutoPrototypePolicy.fullPlayerTakeover(
                hold.update(true, false, 3200), true, false, true);
        assertFalse(ownership.suppress(pausedTakeover, true));
        ownership.exit();
    }
    @Test public void compactBadgeSuppressesOnlyWithoutWeatherToPreserveAdjacentNative() {
        boolean playerReady = true;
        assertTrue(AutoPrototypePolicy.suppressCompactBadge(playerReady, true, false));
        assertFalse(AutoPrototypePolicy.suppressCompactBadge(playerReady, true, true));
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("player", "row");
        boolean noFitPlayer = surfaces.allows("player", "row");
        assertFalse(AutoPrototypePolicy.suppressCompactBadge(noFitPlayer, true, false));
        assertFalse(AutoPrototypePolicy.suppressCompactBadge(false, true, false));
        assertFalse(AutoPrototypePolicy.suppressCompactBadge(true, false, false));
    }
    @Test public void configuredHoldCanReleaseImmediatelyOrKeepAPauseLonger() {
        AutoPrototypePolicy.PlaybackHold hold = new AutoPrototypePolicy.PlaybackHold();
        assertTrue(hold.update(true, true, 0, 0));
        assertFalse(hold.update(true, false, 100, 0));
        assertTrue(hold.update(true, true, 200, 5000));
        assertTrue(hold.update(true, false, 300, 5000));
        assertTrue(hold.update(true, false, 5299, 5000));
        assertFalse(hold.update(true, false, 5300, 5000));
    }
    @Test public void playbackClockAdvancesBetweenSamplesButPausedAndFutureSamplesDoNot() {
        assertEquals(1200, AutoPrototypePolicy.position(1000, 5000, true, 5200));
        assertEquals(1000, AutoPrototypePolicy.position(1000, 5000, false, 5200));
        assertEquals(1000, AutoPrototypePolicy.position(1000, 5000, true, 4999));
    }
    @Test public void pausedSamplesDoNotExtendThreeSecondHold() {
        AutoPrototypePolicy.PlaybackHold hold = new AutoPrototypePolicy.PlaybackHold();
        assertFalse(hold.update(true, false, 0));
        assertTrue(hold.update(true, true, 100));
        assertTrue(hold.update(true, false, 200));
        assertTrue(hold.update(true, false, 3199));
        assertFalse(hold.update(true, false, 3200));
        assertFalse(hold.update(true, false, 3300));
    }
    @Test public void resumeCancelsPauseDeadlineAndInvalidSessionReleasesImmediately() {
        AutoPrototypePolicy.PlaybackHold hold = new AutoPrototypePolicy.PlaybackHold();
        assertTrue(hold.update(true, true, 0));
        assertTrue(hold.update(true, false, 100));
        assertTrue(hold.update(true, true, 3099));
        assertTrue(hold.update(true, false, 3100));
        assertTrue(hold.update(true, false, 6099));
        assertFalse(hold.update(false, false, 6100));
        assertFalse(hold.update(true, false, 6101));
        assertTrue(hold.update(true, true, 6102));
        assertFalse(hold.update(false, true, 6103));
    }
    @Test public void repeatedDeliveryDoesNotExtendSampleLifetime() {
        long sample = 1000;
        assertTrue(AutoPrototypePolicy.fresh(sample, 8999));
        assertFalse(AutoPrototypePolicy.fresh(sample, 9000));
        assertFalse(AutoPrototypePolicy.fresh(sample, 100000));
    }
    @Test public void missingAndFutureSamplesFailClosed() {
        assertFalse(AutoPrototypePolicy.fresh(0, 100));
        assertFalse(AutoPrototypePolicy.fresh(-1, 100));
        assertFalse(AutoPrototypePolicy.fresh(101, 100));
    }
    @Test public void documentMustMatchBothGenerationAndTrack() {
        assertTrue(AutoPrototypePolicy.sameSession(4, "spotify:a", 4, "spotify:a"));
        assertFalse(AutoPrototypePolicy.sameSession(4, "spotify:a", 3, "spotify:a"));
        assertFalse(AutoPrototypePolicy.sameSession(4, "spotify:a", 4, "spotify:b"));
        assertFalse(AutoPrototypePolicy.sameSession(4, "", 4, ""));
        assertFalse(AutoPrototypePolicy.sameSession(4, null, 4, null));
    }
}
