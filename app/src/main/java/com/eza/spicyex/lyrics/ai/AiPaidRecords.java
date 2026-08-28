package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;

/**
 * The durable store's read path, which until now had none.
 *
 * <p>{@link AIPaidArtifactCache} has been able to hold a paid artifact since v412, but nothing ever
 * read one back because nothing produced one. This is that side: it decodes what was stored,
 * refuses anything it cannot fully trust, and reports honestly whether a write actually landed.
 *
 * <p>A read deliberately does not write. {@code lastAccessedAt} is inventory, not correctness, and
 * touching the store on every read would turn displaying a cached answer into a disk write on a
 * path that runs on every track change.
 */
public final class AiPaidRecords implements AiRecordStore {

    private final Context context;
    private AIPaidArtifactCache.Reservation activeReservation;

    public AiPaidRecords(Context context) {
        this.context = context;
    }

    @Override
    public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
        if (context == null || config == null) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "storage-unavailable");
        }
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "incomplete-identity");
        }
        AIPaidArtifactCache.Reservation next = AIPaidArtifactCache.reserve(
                context, identity, maxRecordBytes, activeReservation);
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
        if (context == null || config == null) return null;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) return null;
        AiPaidRecord record = AiPaidRecordCodec.decode(AIPaidArtifactCache.get(context, identity));
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
        if (context == null || config == null || record == null) return false;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity == null || !identity.isComplete()) return false;
        AIPaidArtifactCache.Write write =
                AIPaidArtifactCache.put(context, identity, AiPaidRecordCodec.encode(record),
                        activeReservation);
        if (!write.durable) {
            // The store never evicts to make room, so a rejected write means it is full or the
            // payload is too large. Either way the caller must not claim the result was saved.
            Diagnostics.event("AiPaidRecords", "paid_write_rejected:" + write.reason);
        }
        return write.durable;
    }

    @Override
    public void release(AiRunConfig config) {
        AIPaidArtifactCache.release(context, activeReservation);
        activeReservation = null;
    }

    @Override
    public void forget(AiRunConfig config) {
        if (context == null || config == null) return;
        PaidArtifactIdentity identity = config.recordIdentity();
        if (identity != null && identity.isComplete()) {
            AIPaidArtifactCache.remove(context, identity);
        }
    }
}
