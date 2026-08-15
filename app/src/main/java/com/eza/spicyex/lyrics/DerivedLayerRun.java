package com.eza.spicyex.lyrics;

import android.content.Context;

import java.util.concurrent.atomic.AtomicLong;

import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;

/**
 * One derived-layer run's identity, and the gate that decides whether its result may still be
 * published.
 *
 * <p>Object identity of the document is not enough. A result is accepted only when the track and
 * generation still match, no newer run of the same lane has started, the layer's configuration is
 * unchanged since the run began, and the canonical base still has the same digest. Anything else is
 * a late result: it stays cacheable under its own identity but never lands on screen.
 */
final class DerivedLayerRun {
    /** Canonical base the run started from; how a lane addresses rows by stable ID. */
    final CanonicalBase base;
    /**
     * Process-global, so two lane instances (fullscreen and the session each hold one) can never
     * mint the same run ID and release each other's coalescer key.
     */
    private static final AtomicLong RUN_IDS = new AtomicLong();

    /** OkHttp call tag, so a retired run's in-flight requests can be cancelled. */
    final String tag;
    private final Context context;
    private final LayerKind kind;
    private final String configId;
    private final String canonicalDigest;
    private final long sequence;
    private final AtomicLong laneSequence;

    private DerivedLayerRun(Context context, LayerKind kind, String configId, CanonicalBase base,
                            long sequence, AtomicLong laneSequence) {
        this.base = base;
        this.context = context;
        this.kind = kind;
        this.configId = configId;
        this.canonicalDigest = base.digest;
        this.sequence = sequence;
        this.laneSequence = laneSequence;
        this.tag = kind.name() + "#" + RUN_IDS.incrementAndGet();
    }

    /** Claims the next sequence for {@code laneSequence}, retiring every earlier run of that lane. */
    static DerivedLayerRun begin(Context context, LayerKind kind, LyricsDocument workerSnapshot,
                                 AtomicLong laneSequence) {
        long next = laneSequence.incrementAndGet();
        return new DerivedLayerRun(context, kind, configIdFor(context, kind, workerSnapshot),
                CanonicalBase.fromDocument("", workerSnapshot), next, laneSequence);
    }

    String configId() {
        return configId;
    }

    String canonicalDigest() {
        return canonicalDigest;
    }

    /** True while this run is the lane's newest; false once a later run has begun. */
    boolean isNewest() {
        return laneSequence.get() == sequence;
    }

    /**
     * Full acceptance check, run immediately before anything is applied or published.
     * Records a rejection metric so stale-result pressure is visible in diagnostics.
     */
    boolean accepts(LyricsSecondaryProcessor.CurrentGuard guard, String id, int generation,
                    LyricsDocument snapshot) {
        if (!isNewest()) return reject();
        if (guard != null && !guard.isCurrent(id, generation, snapshot)) return reject();
        if (!configId.equals(configIdFor(context, kind, snapshot))) return reject();
        if (!canonicalDigest.equals(digestOf(snapshot))) return reject();
        return true;
    }

    private static boolean reject() {
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
        return false;
    }

    private static String configIdFor(Context context, LayerKind kind, LyricsDocument doc) {
        return kind == LayerKind.MEANING
                ? LyricsDocumentProcessor.meaningConfigId(context)
                : LyricsDocumentProcessor.currentSoundConfigId(context, doc);
    }

    /**
     * Canonical digest only — the derived text the lanes write is not part of it, so a document
     * mutated by the sibling lane still compares equal.
     */
    private static String digestOf(LyricsDocument doc) {
        return CanonicalBase.fromDocument("", doc).digest;
    }
}
