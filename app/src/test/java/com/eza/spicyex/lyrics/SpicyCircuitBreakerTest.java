package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyCircuitBreakerTest {
    private long now = 10_000_000;
    private final MemoryStore store = new MemoryStore();
    private SpicyCircuitBreaker breaker = create();
    private SpicyCircuitBreaker create() { return new SpicyCircuitBreaker(store, () -> now, () -> 0.5); }
    private static final class MemoryStore implements SpicyCircuitBreaker.Store {
        long[] state = new long[4];
        public long[] load() { return state.clone(); }
        public void save(long until, int rung, long trip, long probe) { state = new long[]{until,rung,trip,probe}; }
    }
    private void trip() throws Exception {
        breaker.failure(breaker.acquire(false), null);
        breaker.failure(breaker.acquire(false), null);
    }
    @Test public void twoConsecutiveFailuresAndSuccessReset() throws Exception {
        breaker.failure(breaker.acquire(false), null);
        breaker.success(breaker.acquire(false));
        breaker.failure(breaker.acquire(false), null);
        assertEquals(0, store.state[0]);
        breaker.failure(breaker.acquire(false), null);
        assertEquals(30000, store.state[0] - now);
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> breaker.acquire(false));
    }
    @Test public void ladderAndHalfOpenProbeRemainBounded() throws Exception {
        trip();
        long[] delays = {30000,30000,60000,120000,120000,120000,120000,120000,120000,120000,120000,300000,300000};
        for (long delay : delays) {
            now = store.state[0];
            SpicyCircuitBreaker.Lease probe = breaker.acquire(false);
            assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> breaker.acquire(false));
            breaker.failure(probe, null);
            assertEquals(delay, store.state[0] - now);
        }
    }
    @Test public void userProbeDoesNotEscalateAndCooldownSurvivesRestart() throws Exception {
        trip();
        long until = store.state[0];
        breaker.failure(breaker.acquire(true), 500000L);
        assertEquals(until, store.state[0]);
        breaker = create();
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> breaker.acquire(true));
        now += 30000;
        breaker.success(breaker.acquire(true));
        assertEquals(0, store.state[0]);
        assertEquals(0, store.state[1]);
    }
    @Test public void staleNormalResponseCannotCloseNewTrip() throws Exception {
        SpicyCircuitBreaker.Lease slow = breaker.acquire(false);
        trip();
        breaker.success(slow);
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> breaker.acquire(false));
    }
    @Test public void abandonedProbeCannotReleaseReplacementSlot() throws Exception {
        trip();
        SpicyCircuitBreaker.Lease old = breaker.acquire(true);
        now += 60001;
        SpicyCircuitBreaker.Lease current = breaker.acquire(false);
        breaker.success(old);
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> breaker.acquire(false));
        breaker.success(current);
        assertNotNull(breaker.acquire(false));
    }
    @Test public void retryAfterOverridesLadderAndClamps() throws Exception {
        breaker.failure(breaker.acquire(false), null);
        breaker.failure(breaker.acquire(false), Long.MAX_VALUE);
        assertEquals(SpicyCircuitBreaker.MAX_PAUSE, store.state[0] - now);
        assertEquals(Long.valueOf(120000), SpicyCircuitBreaker.retryAfter("120", now));
        assertEquals(Long.valueOf(60000), SpicyCircuitBreaker.retryAfter(
                "Thu, 1 Jan 1970 02:47:40 GMT", now));
        assertNull(SpicyCircuitBreaker.retryAfter("invalid", now));
        assertNull(SpicyCircuitBreaker.retryAfter("0", now));
    }
    @Test public void jitterRangeAndPersistedClockRepair() throws Exception {
        for (double random : new double[]{0, 1}) {
            MemoryStore local = new MemoryStore();
            SpicyCircuitBreaker b = new SpicyCircuitBreaker(local, () -> now, () -> random);
            b.failure(b.acquire(false), null); b.failure(b.acquire(false), null);
            assertEquals((long)(30000 * (0.5 + random)), local.state[0] - now);
        }
        store.state = new long[]{now + SpicyCircuitBreaker.MAX_PAUSE + 1, 12, now + 1, now + 1};
        breaker = create();
        assertEquals(0, store.state[0]);
        assertEquals(0, store.state[1]);
        assertNotNull(breaker.acquire(false));
    }
    @Test public void transport401NeverTripsButAll5xxDo() {
        assertFalse(SpicyCircuitBreaker.tripStatus(401));
        assertFalse(SpicyCircuitBreaker.tripStatus(404));
        for (int status : new int[]{403,408,425,429,500,501,502,503,504,599})
            assertTrue(SpicyCircuitBreaker.tripStatus(status));
    }
}
