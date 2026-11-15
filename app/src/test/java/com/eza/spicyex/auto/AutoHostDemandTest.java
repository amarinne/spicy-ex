package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoHostDemandTest {
    @Test public void installedModuleHasNoDemandWithoutACar() {
        AutoHostDemand demand = new AutoHostDemand();
        assertFalse(demand.active(1000));
        assertFalse(demand.update(false, 1000));
        assertFalse(demand.update(false, 100000));
        assertFalse(demand.active(100000));
    }

    @Test public void activeCarNotifiesOncePerSecondInsteadOfEveryFrame() {
        AutoHostDemand demand = new AutoHostDemand();
        assertTrue(demand.update(true, 1000));
        int notifications = 0;
        for (long now = 1100; now <= 3000; now += 100)
            if (demand.update(true, now)) notifications++;
        assertEquals(2, notifications);
        assertTrue(demand.active(3000));
    }

    @Test public void disconnectReleasesDemandImmediatelyAndDoesNotRepeat() {
        AutoHostDemand demand = new AutoHostDemand();
        demand.update(true, 1000);
        assertTrue(demand.update(false, 1100));
        assertFalse(demand.active(1100));
        assertFalse(demand.update(false, 1200));
        assertTrue(demand.update(true, 1300));
        assertTrue(demand.active(1300));
    }

    @Test public void killedHostExpiresWithoutAnyFurtherHostCall() {
        AutoHostDemand demand = new AutoHostDemand();
        demand.update(true, 1000);
        assertTrue(demand.active(5999));
        assertFalse(demand.active(6000));
        assertFalse(demand.active(60000));
        assertTrue(demand.update(true, 60001));
    }

    @Test public void timeReversalCannotKeepAnOldHostAlive() {
        AutoHostDemand demand = new AutoHostDemand();
        demand.update(true, 1000);
        assertFalse(demand.active(999));
    }
}
