package com.eza.spicyex.lyrics.catalog;

import android.content.Context;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;

/**
 * The session's single entry point into the catalog. Loading a track imports the public
 * release's stored winner once, re-seats against the current policy, and returns what renders plus
 * the acquisition plan, all from one committed state. Every call does storage work: call it off
 * the main thread.
 */
public final class LyricsCatalog {
    /**
     * Read order across IO threads. Taken before a view's state is read, so a higher sequence
     * always saw at least every commit a lower one saw; the session drops older views.
     */
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private LyricsCatalog() {
    }

    /** One track's committed catalog view: what renders and what to ask next. */
    public static final class View {
        public final String trackId;
        public final CatalogState state;
        public final CatalogPolicy policy;
        public final Resolution resolution;
        public final AcquisitionPlanner.Plan plan;
        /** Decoded seat document, or null when nothing renders or the payload is unreadable. */
        public final LyricsDocument document;
        /** Source revision recorded with the seat document, for the session's base revision. */
        public final int sourceRevision;
        /** False when the transaction that produced this view failed. */
        public final boolean durable;
        /** Read order; a view with a lower sequence than one already applied is stale. */
        public final long sequence;
        /** Outcome of the command that produced this view, e.g. {@code missing-candidate}. */
        public final String outcome;

        View(String trackId, CatalogState state, CatalogPolicy policy, Resolution resolution,
             AcquisitionPlanner.Plan plan, LyricsDocument document, int sourceRevision,
             boolean durable, long sequence, String outcome) {
            this.trackId = trackId;
            this.state = state;
            this.policy = policy;
            this.resolution = resolution;
            this.plan = plan;
            this.document = document;
            this.sourceRevision = sourceRevision;
            this.durable = durable;
            this.sequence = sequence;
            this.outcome = outcome == null ? "" : outcome;
        }

        public CatalogCandidate seat() {
            return resolution == null ? null : resolution.winner;
        }

        public String seatCandidateId() {
            CatalogCandidate seat = seat();
            return seat == null ? "" : seat.candidateId;
        }
    }

    /**
     * Track entry and settings Save: import the public release's record when the track has no
     * catalog history, then re-seat against the current policy.
     */
    public static View load(Context context, SpotifyTrack track, boolean includeLocal) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (context == null || trackId.isEmpty()) return null;
        CatalogPolicy policy = CatalogPolicy.read(context);
        long now = System.currentTimeMillis();
        CatalogTrack row = CatalogTrack.of(track, now);
        importPublicRelease(context, track, trackId, policy, row, now);
        CatalogStore.Committed committed = CatalogStore.transact(context, trackId,
                state -> CatalogDecisions.reconcile(state, policy, row));
        long sequence = SEQUENCE.incrementAndGet();
        CatalogState after = CatalogStore.state(context, trackId);
        return view(trackId, after, policy, now, includeLocal, committed.committed, sequence, "");
    }

    /** Read-only view after a provider outcome was committed elsewhere. */
    public static View current(Context context, SpotifyTrack track, boolean includeLocal) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (context == null || trackId.isEmpty()) return null;
        CatalogPolicy policy = CatalogPolicy.read(context);
        long sequence = SEQUENCE.incrementAndGet();
        CatalogState state = CatalogStore.state(context, trackId);
        return view(trackId, state, policy, System.currentTimeMillis(), includeLocal, true,
                sequence, "");
    }

    /** Runs one user command over the track and returns the committed view. */
    public static View command(Context context, SpotifyTrack track, Command command) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (context == null || trackId.isEmpty() || command == null) return null;
        CatalogPolicy policy = CatalogPolicy.read(context);
        long now = System.currentTimeMillis();
        CatalogStore.Committed committed = CatalogStore.transact(context, trackId,
                state -> command.decide(state, policy, now));
        long sequence = SEQUENCE.incrementAndGet();
        CatalogState after = CatalogStore.state(context, trackId);
        return view(trackId, after, policy, now, false, committed.committed, sequence,
                committed.change == null ? "storage-failed" : committed.change.outcome);
    }

    /** A user command over one track's catalog state. */
    public interface Command {
        CatalogChange decide(CatalogState state, CatalogPolicy policy, long nowMs);
    }

    public static Command select(String candidateId) {
        return (state, policy, now) -> CatalogDecisions.selectManual(state, policy, candidateId);
    }

    public static Command resetAuto() {
        return (state, policy, now) -> CatalogDecisions.resetAuto(state, policy);
    }

    public static Command reject(String candidateId) {
        return (state, policy, now) -> CatalogDecisions.reject(state, policy, candidateId, now);
    }

    public static Command remove(String candidateId) {
        return (state, policy, now) -> CatalogDecisions.remove(state, policy, candidateId);
    }

    /** Decodes a stored candidate into a render document stamped with its catalog identity. */
    public static CanonicalSourceCodec.Record decode(CatalogCandidate candidate) {
        if (candidate == null || candidate.normalizedDocument.isEmpty()) return null;
        try {
            CanonicalSourceCodec.Record record =
                    CanonicalSourceCodec.decode(candidate.normalizedDocument);
            if (record == null || record.document == null || record.document.lines.isEmpty()) {
                return null;
            }
            CatalogAdapters.applyProviderTransliteration(candidate, record.document);
            record.document.catalogCandidateId = candidate.candidateId;
            return record;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View view(String trackId, CatalogState state, CatalogPolicy policy,
                             long now, boolean includeLocal, boolean durable, long sequence,
                             String outcome) {
        Resolution resolution = CatalogDecisions.render(state, policy);
        CanonicalSourceCodec.Record record = decode(resolution.winner);
        AcquisitionPlanner.Plan plan = AcquisitionPlanner.plan(state, policy,
                record == null ? null : resolution, now, includeLocal);
        return new View(trackId, state, policy, resolution, plan,
                record == null ? null : record.document,
                record == null ? 1 : Math.max(1, record.sourceRevision), durable, sequence,
                outcome);
    }

    /**
     * The public release kept one winner per track plus an optional per-track source override.
     * Both become catalog records the first time the track loads; the old store is only read.
     */
    private static void importPublicRelease(Context context, SpotifyTrack track, String trackId,
                                            CatalogPolicy policy, CatalogTrack row, long now) {
        try {
            if (CatalogStore.state(context, trackId).hasHistory()) return;
            CanonicalSourceCodec.Record legacy = CanonicalSourceCache.load(context, track.uri);
            LyricsSourcePreferences.Source override =
                    LyricsSourcePreferences.trackOverride(context, trackId);
            CatalogLegacyImport.LegacyPick pick = CatalogLegacyImport.pickForLegacyOverride(
                    override == null ? null : override.id);
            CatalogCandidate candidate = legacy == null || legacy.document == null
                    || legacy.document.lines.isEmpty() ? null
                    : CatalogLegacyImport.candidateFromRecord(legacy, track.uri, now);
            if (candidate == null && pick.mode != CatalogSource.SelectionMode.MANUAL) return;
            CatalogStore.transact(context, trackId, state -> CatalogDecisions.importLegacy(
                    state, policy, candidate, pick, row, now));
        } catch (Throwable ignored) {
            // A bad legacy record must never strand the track: the catalog path continues.
        }
    }
}
