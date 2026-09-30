package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;

/** One run's durable records, with deletion ownership retained from its first read. */
public final class AiPaidRecords implements AiRecordStore {

    interface Storage {
        AIPaidArtifactCache.Read read(PaidArtifactIdentity identity);
        AIPaidArtifactCache.Reservation reserve(PaidArtifactIdentity identity, long bytes,
                AIPaidArtifactCache.Reservation current, AIPaidArtifactCache.Read read);
        boolean put(PaidArtifactIdentity identity, String payload,
                AIPaidArtifactCache.Reservation reservation, AIPaidArtifactCache.Read read);
        void release(AIPaidArtifactCache.Reservation reservation);
        void remove(PaidArtifactIdentity identity);
    }

    private final Storage storage;
    private AIPaidArtifactCache.Reservation activeReservation;
    private AIPaidArtifactCache.Read runRead;
    private boolean readStarted;

    public AiPaidRecords(Context context) {
        this(new Storage() {
            public AIPaidArtifactCache.Read read(PaidArtifactIdentity identity) {
                return AIPaidArtifactCache.read(context, identity);
            }
            public AIPaidArtifactCache.Reservation reserve(PaidArtifactIdentity identity,
                    long bytes, AIPaidArtifactCache.Reservation current,
                    AIPaidArtifactCache.Read read) {
                return AIPaidArtifactCache.reserve(context, identity, bytes, current, read);
            }
            public boolean put(PaidArtifactIdentity identity, String payload,
                    AIPaidArtifactCache.Reservation reservation, AIPaidArtifactCache.Read read) {
                AIPaidArtifactCache.Write write =
                        AIPaidArtifactCache.put(context, identity, payload, reservation, read);
                if (!write.durable) Diagnostics.event("AiPaidRecords",
                        "paid_write_rejected:" + write.reason);
                return write.durable;
            }
            public void release(AIPaidArtifactCache.Reservation reservation) {
                AIPaidArtifactCache.release(context, reservation);
            }
            public void remove(PaidArtifactIdentity identity) {
                AIPaidArtifactCache.remove(context, identity);
            }
        });
    }

    AiPaidRecords(Storage storage) {
        this.storage = storage;
    }

    @Override
    public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
        if (config == null) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "storage-unavailable");
        }
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "incomplete-identity");
        }
        AIPaidArtifactCache.Reservation next = storage.reserve(
                identity, maxRecordBytes, activeReservation, runRead);
        if (next.accepted()) activeReservation = next;
        switch (next.status) {
            case ADMITTED:
                return Reservation.admitted();
            case FULL:
                return Reservation.rejected(Reservation.Status.FULL, next.reason);
            case BUSY:
                return Reservation.rejected(Reservation.Status.BUSY, next.reason);
            default:
                return Reservation.rejected(Reservation.Status.UNAVAILABLE, next.reason);
        }
    }

    @Override
    public AiPaidRecord read(AiRunConfig config) {
        if (config == null) return null;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) return null;
        if (!readStarted) {
            readStarted = true;
            runRead = storage.read(identity);
        }
        AiPaidRecord record = AiPaidRecordCodec.decode(runRead == null ? null : runRead.payload);
        if (record == null) return null;
        if (!config.matches(record)) {
            // Filed under this key but describing a different question. Treat it as absent rather
            // than serving a paid answer to something it does not answer.
            Diagnostics.event("AiPaidRecords", "record_key_mismatch");
            return null;
        }
        record.lastAccessedAtMs = System.currentTimeMillis();
        return record;
    }

    @Override
    public boolean commit(AiRunConfig config, AiPaidRecord record) {
        if (config == null || record == null) return false;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) return false;
        return storage.put(identity, AiPaidRecordCodec.encode(record), activeReservation, runRead);
    }

    @Override
    public void release(AiRunConfig config) {
        storage.release(activeReservation);
        activeReservation = null;
    }

    @Override
    public void forget(AiRunConfig config) {
        if (config == null) return;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity != null && identity.isComplete()) {
            storage.remove(identity);
        }
    }
}
