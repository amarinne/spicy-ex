package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CatalogReadOrderTest {
    @Test public void pausedOldSnapshotCannotAcquireANewerPublicationSequence() throws Exception {
        CatalogReadOrder order = new CatalogReadOrder();
        AtomicInteger committedValue = new AtomicInteger(1);
        CountDownLatch oldSnapshotRead = new CountDownLatch(1);
        CountDownLatch finishOldRead = new CountDownLatch(1);
        CountDownLatch newerReaderStarted = new CountDownLatch(1);
        CountDownLatch newerSnapshotRead = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<long[]> old = workers.submit(() -> order.read(sequence -> {
                int value = committedValue.get();
                oldSnapshotRead.countDown();
                try {
                    if (!finishOldRead.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader timed out");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return new long[]{sequence, value};
            }));
            assertTrue(oldSnapshotRead.await(5, TimeUnit.SECONDS));
            committedValue.set(2);
            Future<long[]> newer = workers.submit(() -> {
                newerReaderStarted.countDown();
                return order.read(sequence -> {
                    newerSnapshotRead.countDown();
                    return new long[]{sequence, committedValue.get()};
                });
            });
            assertTrue(newerReaderStarted.await(5, TimeUnit.SECONDS));
            assertFalse("Snapshot reads must share the sequence lock",
                    newerSnapshotRead.await(100, TimeUnit.MILLISECONDS));
            finishOldRead.countDown();
            long[] first = old.get(5, TimeUnit.SECONDS);
            long[] second = newer.get(5, TimeUnit.SECONDS);
            assertEquals(1, first[1]);
            assertEquals(2, second[1]);
            assertTrue(first[0] < second[0]);
        } finally {
            finishOldRead.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
