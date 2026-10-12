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
    private boolean storageReadFailed;
    private final Context context;

    public AiPaidRecords(Context context) {
        this(context, new Storage() {
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
        this(null, storage);
    }

    private AiPaidRecords(Context context, Storage storage) {
        this.context = context;
        this.storage = storage;
    }

    @Override public AiPaidRecord readReplay(AiRunConfig config, AiReplayIdentity replay) {
        try {
            return AiPaidRecordCodec.decode(AIPaidArtifactCache.replay(context, config.layer.name(),
                    config.configId, replay.digest, replay.baseline));
        } catch (Throwable failure) {
            storageReadFailed = true;
            Diagnostics.warn("AiPaidRecords", "replay_read", failure);
            return null;
        }
    }

    @Override public void bind(AiPaidRecord record, AiSourceBinding source, AiReplayIdentity replay) {
        if (record == null || !record.isComplete()) return;
        try {
            AIPaidArtifactCache.bind(context, AiIdentity.recordIdentity(record.layer, record.docDigest,
                    record.providerId, record.modelName, record.configId), source, record.configId, replay.digest);
        } catch (Throwable failure) {
            Diagnostics.warn("AiPaidRecords", "bind", failure);
        }
    }

    @Override
    public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
        if (storageReadFailed || !readStarted || runRead == null || !runRead.admissionSafe) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "storage-read-unavailable");
        }
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
            try {
                runRead = storage.read(identity);
            } catch (Throwable failure) {
                storageReadFailed = true;
                Diagnostics.warn("AiPaidRecords", "read", failure);
            }
        }
        AiPaidRecord record = AiPaidRecordCodec.decode(runRead == null ? null : runRead.payload);
        if (record == null) {
            if (runRead != null && runRead.payload != null) Diagnostics.event("AiPaidRecords", "paid_payload_unreadable");
            storageReadFailed |= runRead == null || !runRead.admissionSafe || runRead.payload != null;
            return null;
        }
        if (!config.matches(record)) {
            // Filed under this key but describing a different question. Treat it as absent rather
            // than serving a paid answer to something it does not answer.
            Diagnostics.event("AiPaidRecords", "record_key_mismatch");
            storageReadFailed = true;
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
