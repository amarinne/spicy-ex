package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A lane restarted for the same song asks Google the same question while the first ask is still
 * on the wire. The second ask must wait for the first answer, not send its own.
 */
public class GoogleInFlightJoinTest {
    private static GoogleEnhancer.HttpResult answer(String body) {
        GoogleEnhancer.HttpResult result = new GoogleEnhancer.HttpResult();
        result.body = body;
        result.status = 200;
        result.attempts = 1;
        return result;
    }

    @Test
    public void anIdenticalRequestJoinsTheOneInFlight() throws Exception {
        String key = "join-" + System.nanoTime();
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch onWire = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<GoogleEnhancer.HttpResult> first = new AtomicReference<>();
        AtomicReference<GoogleEnhancer.HttpResult> second = new AtomicReference<>();
        Thread owner = new Thread(() -> first.set(GoogleEnhancer.joinInFlight(key, () -> {
            sent.incrementAndGet();
            onWire.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return answer("reading");
        })));
        owner.start();
        assertTrue(onWire.await(5, TimeUnit.SECONDS));
        Thread joiner = new Thread(() -> second.set(GoogleEnhancer.joinInFlight(key, () -> {
            sent.incrementAndGet();
            return answer("second");
        })));
        joiner.start();
        // Release the first call only once the second is parked waiting for it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (joiner.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield();
        }
        release.countDown();
        owner.join(5_000L);
        joiner.join(5_000L);

        assertEquals("reading", first.get().body);
        assertEquals("reading", second.get().body);
        assertEquals(200, second.get().status);
        assertEquals(0, second.get().attempts);
        assertEquals(1, sent.get());
    }

    /** Once the call is answered, the next identical ask is a new request (the cache owns reuse). */
    @Test
    public void aFinishedRequestIsNotJoined() {
        String key = "done-" + System.nanoTime();
        AtomicInteger sent = new AtomicInteger();
        GoogleEnhancer.joinInFlight(key, () -> { sent.incrementAndGet(); return answer("a"); });
        GoogleEnhancer.HttpResult again =
                GoogleEnhancer.joinInFlight(key, () -> { sent.incrementAndGet(); return answer("b"); });
        assertEquals("b", again.body);
        assertEquals(2, sent.get());
    }

    /** A send that throws still releases the key, so the next ask is not stuck behind it. */
    @Test
    public void aFailedSendReleasesTheKey() {
        String key = "fail-" + System.nanoTime();
        try {
            GoogleEnhancer.joinInFlight(key, () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
        }
        assertEquals("ok", GoogleEnhancer.joinInFlight(key, () -> answer("ok")).body);
    }
}
