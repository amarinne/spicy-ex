package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoFullBadgeOwnershipRegressionTest {
    @Test public void fullPainterRetainsOwnershipOnIndependentChildRestart() {
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        Object painter = new Object();
        assertTrue(ownership.enter(painter, true, false));
        assertTrue(ownership.suppress(true, true));
        ownership.exit();
        assertTrue(ownership.enter(painter, false, false));
        assertTrue(ownership.suppress(true, true));
        ownership.exit();
        assertFalse(ownership.suppress(true, true));
    }
    @Test public void nativeCompactPainterBesideWeatherRemainsVisibleDuringFullTakeover() {
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        Object full = new Object(), compact = new Object();
        ownership.enter(full, true, false);
        assertTrue(ownership.suppress(true, true));
        ownership.enter(compact, false, false);
        assertFalse(ownership.suppress(true, true));
        ownership.exit();
        assertTrue(ownership.suppress(true, true));
        ownership.exit();
        ownership.enter(compact, false, false);
        assertFalse(ownership.suppress(true, true));
        ownership.exit();
    }
    @Test public void sharedPainterFailsClosedAfterCompactOwnershipIsObserved() {
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        Object painter = new Object(); ownership.enter(painter, true, false); ownership.exit();
        assertFalse(ownership.enter(painter, false, true));
        assertFalse(ownership.suppress(true, true)); ownership.exit();
        assertFalse(ownership.enter(painter, true, false));
        assertFalse(ownership.suppress(true, true)); ownership.exit();
        assertFalse(ownership.suppress(true, true));
    }
    @Test public void releaseAndOtherAppRestoreEvenForKnownFullPainter() {
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        Object painter = new Object(); ownership.enter(painter, true, false);
        assertFalse(ownership.suppress(false, true));
        assertFalse(ownership.suppress(true, false));
        ownership.exit();
        ownership.enter(painter, false, false);
        assertFalse(ownership.suppress(false, true));
        ownership.exit();
    }
}
