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
    private static final CatalogReadOrder READS = new CatalogReadOrder();

    private LyricsCatalog() {
    }

    /** One track's committed catalog view: what renders and what to ask next. */
    public static final class View {
        public final String trackId;
        public final CatalogState state;
        public final CatalogPolicy policy;
        public final Resolution resolution;
        /** Null when the snapshot was unreadable; never a durable-absence plan. */
        public final AcquisitionPlanner.Plan plan;
        /** Decoded seat document, or null when nothing renders or the payload is unreadable. */
        public final LyricsDocument document;
        /** Source revision recorded with the seat document, for the session's base revision. */
        public final int sourceRevision;
        /**
         * False when the transaction that produced this view failed or the snapshot could not be
         * read. A false value is not evidence that the track has no stored lyrics.
         */
        public final boolean durable;
        /** False when the catalog storage read failed; {@link #state} is an empty placeholder. */
        public final boolean readable;
        /** Read order; a view with a lower sequence than one already applied is stale. */
        public final long sequence;
        /** Outcome of the command that produced this view, e.g. {@code missing-candidate}. */
        public final String outcome;

        View(String trackId, CatalogState state, CatalogPolicy policy, Resolution resolution,
             AcquisitionPlanner.Plan plan, LyricsDocument document, int sourceRevision,
             boolean durable, boolean readable, long sequence, String outcome) {
            this.trackId = trackId;
            this.state = state;
            this.policy = policy;
            this.resolution = resolution;
            this.plan = plan;
            this.document = document;
            this.sourceRevision = sourceRevision;
            this.durable = durable;
            this.readable = readable;
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
        return readView(context, trackId, includeLocal, committed.committed, "", track.duration);
    }

    /** Read-only view after a provider outcome was committed elsewhere. */
    public static View current(Context context, SpotifyTrack track, boolean includeLocal) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (context == null || trackId.isEmpty()) return null;
        return readView(context, trackId, includeLocal, true, "", track.duration);
    }

    /** Runs one user command over the track and returns the committed view. */
    public static View command(Context context, SpotifyTrack track, Command command) {
        String trackId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (context == null || trackId.isEmpty() || command == null) return null;
        CatalogPolicy policy = CatalogPolicy.read(context);
        long now = System.currentTimeMillis();
        CatalogStore.Committed committed = CatalogStore.transact(context, trackId,
                state -> command.decide(state, policy, now));
        return readView(context, trackId, true, committed.committed,
                committed.change == null ? "storage-failed" : committed.change.outcome, track.duration);
    }

    /** A user command over one track's catalog state. */
    public interface Command {
        CatalogChange decide(CatalogState state, CatalogPolicy policy, long nowMs);
    }

    public static Command importLocal(CatalogCandidate candidate, SpotifyTrack track) {
        return (state, policy, now) -> CatalogDecisions.importLocal(state, policy, candidate,
                CatalogTrack.of(track, now));
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
        if (candidate == null || candidate.normalizedDocument.isEmpty()
                || !candidate.hasValidProviderTiming()) return null;
        try {
            CanonicalSourceCodec.Record record =
                    CanonicalSourceCodec.decode(candidate.normalizedDocument);
            if (record == null || record.document == null || record.document.lines.isEmpty()) {
                return null;
            }
            CatalogAdapters.applyProviderTransliteration(candidate, record.document);
            record.document.catalogCandidateId = candidate.candidateId;
            record.document.catalogDelivery = new CatalogDelivery(candidate.sourceId,
                    candidate.providerItemId, candidate.matchMethod, true);
            return record;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View readView(Context context, String trackId, boolean includeLocal,
                                 boolean durable, String outcome, long durationMs) {
        return READS.read(sequence -> {
            CatalogPolicy policy = CatalogPolicy.read(context);
            CatalogStore.Read read = CatalogStore.read(context, trackId);
            return view(trackId, read.state, policy, System.currentTimeMillis(), includeLocal,
                    durable, read.readable, sequence, outcome, durationMs);
        });
    }

    static View view(String trackId, CatalogState state, CatalogPolicy policy,
                             long now, boolean includeLocal, boolean durable, long sequence,
                             String outcome, long durationMs) {
        return view(trackId, state, policy, now, includeLocal, durable, true, sequence, outcome,
                durationMs);
    }

    static View view(String trackId, CatalogState state, CatalogPolicy policy,
                             long now, boolean includeLocal, boolean durable, boolean readable,
                             long sequence, String outcome, long durationMs) {
        if (!readable) {
            // No snapshot means no seat and no plan. Callers must never read this placeholder as
            // a durable empty catalog or plan acquisition from it.
            return new View(trackId, CatalogState.empty(trackId), policy, null, null, null, 1,
                    false, false, sequence, outcome);
        }
        state = readableState(state);
        Resolution resolution = CatalogDecisions.render(state, policy);
        CanonicalSourceCodec.Record record = decode(resolution.winner);
        AcquisitionPlanner.Plan plan = AcquisitionPlanner.plan(state, policy,
                record == null ? null : resolution, now, includeLocal);
        return new View(trackId, state, policy, resolution, plan,
                record == null ? null : record.document,
                record == null ? 1 : Math.max(1, record.sourceRevision), durable, true, sequence,
                outcome);
    }

    /** Corrupt payloads remain stored, but cannot mask a usable fallback or prevent refresh. */
    static CatalogState readableState(CatalogState state) {
        java.util.List<CatalogCandidate> readable = new java.util.ArrayList<>();
        for (CatalogCandidate candidate : state.candidates) {
            if (decode(candidate) != null) readable.add(candidate);
        }
        return state.withVisible(readable);
    }

    /**
     * The public release kept one winner per track plus an optional per-track source override.
     * Both become catalog records the first time the track loads; the old store is only read.
     * An unreadable catalog is never treated as an empty one, so a failed read cannot trigger a
     * legacy import over state that may already exist.
     */
    private static void importPublicRelease(Context context, SpotifyTrack track, String trackId,
                                            CatalogPolicy policy, CatalogTrack row, long now) {
        try {
            if (!importEligible(CatalogStore.read(context, trackId))) return;
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

    /** Import only when the catalog was read successfully and the track has no history yet. */
    static boolean importEligible(CatalogStore.Read read) {
        return read != null && read.readable && !read.state.hasHistory();
    }
}
