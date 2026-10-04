package com.eza.spicyex.lyrics.providers;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgRetentionPlanTest {
    private static final long FETCH = 100_000L;

    @Test public void refreshWindowDoesNotDeleteTheGracePeriod() {
        long deadline = FETCH + SpicyOrgPolicy.RETENTION_MS;
        assertEquals(deadline, SpicyOrgRetentionPlan.deadline(true, FETCH, FETCH,
                FETCH + SpicyOrgPolicy.REFRESH_AFTER_MS));
        assertEquals(deadline, SpicyOrgRetentionPlan.deadline(true, FETCH, FETCH, deadline - 1));
        assertEquals(deadline, SpicyOrgRetentionPlan.deadline(true, FETCH, FETCH, deadline));
    }

    @Test public void freshReplacementMovesTheAlarmAndOlderRowsKeepTheirDeadline() {
        long now = FETCH + SpicyOrgPolicy.REFRESH_AFTER_MS;
        assertEquals(now + SpicyOrgPolicy.RETENTION_MS,
                SpicyOrgRetentionPlan.deadline(true, now, now, now));
        assertEquals(FETCH + SpicyOrgPolicy.RETENTION_MS,
                SpicyOrgRetentionPlan.deadline(true, FETCH, now, now));
    }

    @Test public void invalidAndExpiredAcquisitionsNeedImmediateCleanup() {
        long now = FETCH + SpicyOrgPolicy.RETENTION_MS + 1;
        assertEquals(now, SpicyOrgRetentionPlan.deadline(true, 0, FETCH, now));
        assertEquals(now, SpicyOrgRetentionPlan.deadline(true, FETCH, now + 1, now));
        assertEquals(now, SpicyOrgRetentionPlan.deadline(true, FETCH, FETCH, now));
        assertEquals(0, SpicyOrgRetentionPlan.deadline(false, 0, 0, now));
    }

    @Test public void deadlineAdditionDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, SpicyOrgRetentionPlan.deadline(true,
                Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1));
    }
}
