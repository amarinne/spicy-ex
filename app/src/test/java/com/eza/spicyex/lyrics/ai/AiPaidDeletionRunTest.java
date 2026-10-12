package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.*;

import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real paid-record adapter and runner against a deterministic storage transaction seam. */
public class AiPaidDeletionRunTest {
    private static AiRunConfig config(LayerKind layer) {
        AiProviderConfig provider = new AiProviderConfig(layer, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        return new AiRunConfig(layer, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
    }

    private static AiLayerRunner.Args args(AiRunConfig config, AiPaidRecords store,
                                           FakeAiProvider provider, AiSignal signal) {
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config;
        args.store = store;
        args.provider = provider;
        args.signal = signal;
        args.rows = Collections.singletonList(AiLine.of("r0", "hello", null, false));
        args.wait = (millis, pending) -> { if (pending != null) pending.throwIfAborted(); };
        return args;
    }

    @Test public void clearAfterPartialReadRejectsCancellationCommitWithoutReservation() {
        Storage storage = new Storage();
        AiRunConfig config = config(LayerKind.MEANING);
        storage.seed(config, AiPaidRecord.begin(config, 1));
        AiPaidRecords store = new AiPaidRecords(storage);
        assertNotNull(store.read(config)); // Same pre-read used by AiMeaningRun.
        storage.clear(LayerKind.MEANING);
        AiSignal signal = new AiSignal();
        signal.abort("user");
        FakeAiProvider provider = new FakeAiProvider();

        AiRunOutcome result = AiLayerRunner.run(args(config, store, provider, signal));

        assertEquals(AiRunOutcome.Kind.CANCELLED, result.kind);
        assertFalse(result.durable);
        assertEquals(1, storage.reads); // The runner's second read cannot renew deletion ownership.
        assertEquals(0, storage.reservations);
        assertEquals(1, storage.puts);
        assertNull(storage.lastReservation);
        assertNull(storage.peek(config));
        assertTrue(provider.calls.isEmpty());
    }

    @Test public void workerReleasedAfterClearCannotCommitThePartialItJustRead() throws Exception {
        Storage storage = new Storage();
        AiRunConfig config = config(LayerKind.MEANING);
        storage.seed(config, AiPaidRecord.begin(config, 1));
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        storage.afterRead = () -> {
            read.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker not released");
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        };
        AiSignal signal = new AiSignal();
        FakeAiProvider provider = new FakeAiProvider();
        AtomicReference<AiRunOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                outcome.set(AiLayerRunner.run(args(config, new AiPaidRecords(storage), provider, signal)));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        try {
            assertTrue("worker reached first read", read.await(5, TimeUnit.SECONDS));
            signal.abort("user");
            storage.clear(LayerKind.MEANING);
        } finally {
            resume.countDown();
            worker.join(5000);
        }
        assertFalse("worker finished", worker.isAlive());
        assertNull(failure.get());
        assertNotNull(outcome.get());
        assertEquals(AiRunOutcome.Kind.CANCELLED, outcome.get().kind);
        assertFalse(outcome.get().durable);
        assertEquals(0, storage.reservations);
        assertNull(storage.peek(config));
        assertTrue(provider.calls.isEmpty());
    }

    @Test public void clearAfterReadAlsoRefusesNewReservationBeforeProviderDispatch() {
        Storage storage = new Storage();
        AiRunConfig config = config(LayerKind.SOUND);
        AiPaidRecords store = new AiPaidRecords(storage);
        store.read(config);
        storage.clear(null);
        FakeAiProvider provider = new FakeAiProvider();

        AiRunOutcome result = AiLayerRunner.run(args(config, store, provider, null));

        assertEquals(AiRunOutcome.Kind.FAILED, result.kind);
        assertEquals("storage_unavailable", result.failureToken);
        assertTrue(provider.calls.isEmpty());
        assertNull(storage.peek(config));
    }

    @Test public void terminalChunksCannotRecreateClearedRecordWithoutReservation() {
        Storage storage = new Storage();
        AiRunConfig config = config(LayerKind.MEANING);
        FakeAiProvider first = new FakeAiProvider();
        AiRunOutcome bought = AiLayerRunner.run(args(config, new AiPaidRecords(storage), first, null));
        assertEquals(AiRunOutcome.Kind.COMPLETED, bought.kind);
        // A crash can leave the complete chunk ledger before the final COMPLETE status write.
        bought.record.status = AiPaidRecord.Status.PARTIAL;
        storage.seed(config, bought.record);
        AiPaidRecords resumed = new AiPaidRecords(storage);
        assertNotNull(resumed.read(config));
        storage.clear(LayerKind.MEANING);
        int reservations = storage.reservations;
        FakeAiProvider provider = new FakeAiProvider();

        AiRunOutcome result = AiLayerRunner.run(args(config, resumed, provider, null));

        assertEquals(AiRunOutcome.Kind.FAILED, result.kind);
        assertFalse(result.durable);
        assertEquals(reservations, storage.reservations);
        assertNull(storage.lastReservation);
        assertNull(storage.peek(config));
        assertTrue(provider.calls.isEmpty());
    }

    @Test public void clearingMeaningPreservesSoundReservationAndPartialOutputForResume() {
        Storage storage = new Storage();
        AiRunConfig sound = config(LayerKind.SOUND);
        AiRunConfig meaning = config(LayerKind.MEANING);
        AiLayerRunner.Args retained = args(sound, null, new FakeAiProvider(), null);
        retained.rows = new java.util.ArrayList<>();
        for (int i = 0; i < 129; i++) retained.rows.add(AiLine.of("r" + i, "hello", null, false));
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = retained.rows;
        input.layer = sound.layer;
        input.target = sound.targetLang;
        input.model = sound.provider.model;
        AiPlannedChunk paid = AiChunkPlanner.plan(input).chunks.get(0);
        AiPaidRecord partial = AiPaidRecord.begin(sound, 1);
        AiChunkRecord paidChunk = AiChunkRecord.forChunk(paid);
        paidChunk.status = AiChunkRecord.Status.COMPLETE;
        partial.putChunk(paid.id, paidChunk);
        for (String id : paid.itemIds()) partial.putItem(id, "paid reading");
        storage.seed(sound, partial);
        AiPaidRecords soundRun = new AiPaidRecords(storage);
        assertNotNull(soundRun.read(sound));
        assertTrue(soundRun.reserve(sound, 4096).accepted());
        storage.seed(meaning, AiPaidRecord.begin(meaning, 1));
        storage.clear(LayerKind.MEANING);
        AiSignal signal = new AiSignal();
        signal.abort("user");

        retained.store = soundRun;
        retained.signal = signal;
        AiRunOutcome stopped = AiLayerRunner.run(retained);
        assertTrue(stopped.durable);
        assertNotNull(storage.lastReservation);
        AiPaidRecord reloaded = new AiPaidRecords(storage).read(sound);
        assertNotNull(reloaded);
        assertEquals("paid reading", reloaded.item("r0"));
        assertNull(storage.peek(meaning));

        storage.clear(null);
        assertFalse(soundRun.commit(sound, partial));
        assertNull(storage.peek(sound));
    }

    @Test public void commitWithoutFirstReadFailsClosed() {
        Storage storage = new Storage();
        AiRunConfig config = config(LayerKind.SOUND);
        assertFalse(new AiPaidRecords(storage).commit(config, AiPaidRecord.begin(config, 1)));
        assertNull(storage.peek(config));
    }

    @Test public void corruptOrForeignPaidPayloadBlocksRepurchaseAndKeepsTheOriginal() {
        AiPaidRecord incompleteComplete = new AiPaidRecord(LayerKind.SOUND, "digest-1",
                "config-1", "fake", FakeAiProvider.DEFAULT_MODEL.name, "en", null, null,
                AiContract.SOUND_SCHEMA, AiContract.CHUNK_PLAN_VERSION);
        incompleteComplete.status = AiPaidRecord.Status.COMPLETE;
        incompleteComplete.putItem("r0", "paid output");
        for (String payload : new String[]{"malformed", "{\"v\":99}",
                AiPaidRecordCodec.encode(incompleteComplete),
                AiPaidRecordCodec.encode(new AiPaidRecord(LayerKind.MEANING, "digest", "config",
                        "foreign-provider", "foreign-model", "en", null, null, 1, 1))}) {
            Storage storage = new Storage();
            AiRunConfig config = config(LayerKind.SOUND);
            storage.payloads.put(config.recordIdentity().storageKey(), payload);
            FakeAiProvider forbidden = new FakeAiProvider();
            AiRunOutcome outcome = AiLayerRunner.run(args(config, new AiPaidRecords(storage), forbidden, null));
            assertEquals("storage_unavailable", outcome.failureToken);
            assertTrue(forbidden.calls.isEmpty());
            assertEquals(0, storage.reservations);
            assertEquals(payload, storage.payloads.get(config.recordIdentity().storageKey()));
        }
    }

    private static final class Storage implements AiPaidRecords.Storage {
        final Map<String, String> payloads = new HashMap<>();
        final Map<LayerKind, Long> generations = new HashMap<>();
        long allGeneration;
        int reads, reservations, puts;
        AIPaidArtifactCache.Reservation lastReservation;
        Runnable afterRead;

        long generation(LayerKind layer) { return generations.getOrDefault(layer, 0L); }
        boolean accepts(PaidArtifactIdentity identity, AIPaidArtifactCache.Read read) {
            return read != null && read.matches(identity.storageKey(), identity.layerKind.name(),
                    allGeneration, generation(identity.layerKind), 0L);
        }
        public AIPaidArtifactCache.Read read(PaidArtifactIdentity identity) {
            reads++;
            AIPaidArtifactCache.Read read = new AIPaidArtifactCache.Read(payloads.get(identity.storageKey()),
                    identity.storageKey(), identity.layerKind.name(), allGeneration,
                    generation(identity.layerKind), 0L);
            if (afterRead != null) afterRead.run();
            return read;
        }
        public AIPaidArtifactCache.Reservation reserve(PaidArtifactIdentity identity, long bytes,
                AIPaidArtifactCache.Reservation current, AIPaidArtifactCache.Read read) {
            reservations++;
            return accepts(identity, read)
                    ? AIPaidArtifactCache.Reservation.admitted("token", identity.storageKey())
                    : AIPaidArtifactCache.Reservation.rejected(
                            AIPaidArtifactCache.Reservation.Status.UNAVAILABLE, "run-revoked");
        }
        public boolean put(PaidArtifactIdentity identity, String payload,
                AIPaidArtifactCache.Reservation reservation, AIPaidArtifactCache.Read read) {
            puts++;
            lastReservation = reservation;
            if (!accepts(identity, read)) return false;
            payloads.put(identity.storageKey(), payload);
            return true;
        }
        public void release(AIPaidArtifactCache.Reservation reservation) {}
        public void remove(PaidArtifactIdentity identity) { payloads.remove(identity.storageKey()); }
        void clear(LayerKind layer) {
            if (layer == null) {
                allGeneration++;
                payloads.clear();
            } else {
                generations.put(layer, generation(layer) + 1);
                payloads.entrySet().removeIf(entry -> AiPaidRecordCodec.decode(entry.getValue()).layer == layer);
            }
        }
        void seed(AiRunConfig config, AiPaidRecord record) {
            payloads.put(config.recordIdentity().storageKey(), AiPaidRecordCodec.encode(record));
        }
        AiPaidRecord peek(AiRunConfig config) {
            return AiPaidRecordCodec.decode(payloads.get(config.recordIdentity().storageKey()));
        }
    }
}
