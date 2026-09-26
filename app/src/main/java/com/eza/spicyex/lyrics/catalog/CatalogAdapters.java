package com.eza.spicyex.lyrics.catalog;

import android.content.Context;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider outcome adapters: every source persists its own results and outcomes under its own
 * identity. Each adapter declares its query provenance (direct Spotify-ID vs title search) so
 * stored candidates carry real match metadata instead of an invented confidence.
 *
 * <p>Every call commits through {@link CatalogStore#transact}: the candidate, the source outcome,
 * and the resolved seat land together. Malformed documents (no lines, blank encoding) are refused,
 * never stored.
 */
public final class CatalogAdapters {
    /** Adapter revision per source: stored on candidates, retires parser/protocol failures. */
    public static final int APPLE_ADAPTER_REVISION = 1;
    public static final int SPOTIFY_NATIVE_ADAPTER_REVISION = 1;
    public static final int AMLL_ADAPTER_REVISION = 1;
    public static final int LRCLIB_ADAPTER_REVISION = 1;

    private CatalogAdapters() {
    }

    /** Retires only an old partial capture with identical source content and corrected metadata. */
    static boolean isPartialNativeDuplicate(CatalogCandidate old, CatalogCandidate fresh) {
        if (old.sourceId != SourceId.SPOTIFY_NATIVE || fresh.sourceId != old.sourceId
                || !old.providerItemId.equals(fresh.providerItemId)) return false;
        CanonicalSourceCodec.Record before = CanonicalSourceCodec.decode(old.normalizedDocument);
        CanonicalSourceCodec.Record after = CanonicalSourceCodec.decode(fresh.normalizedDocument);
        if (before == null || after == null) return false;
        LyricsDocument previous = before.document;
        LyricsDocument current = after.document;
        if (!previous.fetchSource.startsWith("spotify_native_model:")
                || !"spotify_native_model".equals(current.fetchSource)
                || isBlank(current.language) || "unknown".equals(current.language)) return false;
        if (isBlank(previous.language) || "unknown".equals(previous.language)) {
            previous.language = current.language;
        }
        previous.fetchSource = current.fetchSource;
        previous.selectedSource = current.selectedSource;
        previous.selectionMode = current.selectionMode;
        previous.selectionOverride = current.selectionOverride;
        // Compare the complete source projection, including provider translations and spans.
        // Different lyrics, timings, known languages, or provider metadata remain separate.
        return CanonicalSourceCodec.encode(previous, 1, "", 0, "").equals(
                CanonicalSourceCodec.encode(current, 1, "", 0, ""));
    }

    /** Fallback order among the adapters owned here: AMLL word timing before LRCLIB lines. */
    public static List<SourceId> fallbackOrder() {
        List<SourceId> order = new ArrayList<>();
        order.add(SourceId.AMLL);
        order.add(SourceId.LRCLIB);
        return Collections.unmodifiableList(order);
    }

    /** Direct Spotify-ID queries are exact mappings; title searches are strong search matches. */
    public static MatchMethod matchMethodForQuery(SourceId source, boolean exactIdQuery) {
        if (exactIdQuery && (source == SourceId.AMLL || source == SourceId.QQ
                || source == SourceId.NETEASE)) {
            return MatchMethod.EXACT_SPOTIFY_ID;
        }
        return MatchMethod.STRONG_SEARCH;
    }

    /**
     * Builds the storable candidate for a delivered document, or null when the document is
     * malformed and must not be stored. Pure and JVM-tested; storage itself is the thin
     * {@link #recordSuccess} below.
     */
    public static CatalogCandidate buildCandidate(SourceId source, SpotifyTrack track,
                                                  LyricsDocument doc, MatchMethod method,
                                                  String providerItemId, String rawPayload,
                                                  int adapterRevision, long fetchedAtMs) {
        if (source == null || doc == null || doc.lines.isEmpty()) return null;
        String bareId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        if (bareId.isEmpty()) return null;
        CanonicalBase base;
        try {
            base = LyricsDocumentProcessor.canonicalBaseOf(doc);
        } catch (Throwable ignored) {
            return null;
        }
        if (base == null || base.isEmpty()) return null;
        String normalized;
        try {
            normalized = CanonicalSourceCodec.encode(doc, 1, base.digest, fetchedAtMs, "");
        } catch (Throwable ignored) {
            return null;
        }
        if (normalized == null || normalized.isEmpty()) return null;
        TimingLevel timing = TimingLevel.fromDocumentType(doc.type);
        String item = providerItemId == null ? "" : providerItemId;
        String candidateId = CatalogCandidate.stableId(bareId, source, item, base.digest);
        List<String> transliteration = providerTransliteration(doc);
        boolean hasTransliteration = false;
        for (String value : transliteration) {
            if (!isBlank(value)) {
                hasTransliteration = true;
                break;
            }
        }
        return new CatalogCandidate(candidateId, bareId, source, item,
                method == null ? MatchMethod.STRONG_SEARCH : method, 0.8, 0L, timing, true,
                timing == TimingLevel.UNSYNCED || hasTiming(doc), hasProviderTranslation(doc),
                hasTransliteration, hasBackgroundVocals(doc), hasDuet(doc),
                !isBlank(doc.songWriters),
                base.digest, normalized,
                CatalogCodec.encodeProviderTransliteration(transliteration),
                CatalogCodec.deflate(rawPayload), CanonicalSourceCodec.SCHEMA_VERSION,
                adapterRevision, fetchedAtMs);
    }

    /**
     * Commits a provider success: the candidate, the source outcome, and the resulting seat in one
     * catalog transaction. On commit the document is stamped with its candidate ID so the session
     * knows it is stored. Returns false when nothing was stored (malformed, rejected item, or a
     * storage failure); the caller must not treat the delivery as durable then.
     */
    public static boolean recordSuccess(Context context, SourceId source, SpotifyTrack track,
                                        LyricsDocument doc, MatchMethod method,
                                        String providerItemId, String rawPayload,
                                        int adapterRevision) {
        try {
            long now = System.currentTimeMillis();
            CatalogCandidate candidate = buildCandidate(source, track, doc, method, providerItemId,
                    rawPayload, adapterRevision, now);
            if (candidate == null) return false;
            CatalogPolicy policy = CatalogPolicy.read(context);
            CatalogTrack row = CatalogTrack.of(track, now);
            CatalogStore.Committed committed = CatalogStore.transact(context, candidate.trackId,
                    state -> CatalogDecisions.providerSuccess(state, policy, candidate, row, now));
            boolean stored = committed.committed && committed.change != null
                    && !committed.change.putCandidates.isEmpty();
            if (stored) doc.catalogCandidateId = candidate.candidateId;
            return stored;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Commits a delivery no adapter stored (a response-cache replay, for instance) with provenance
     * inferred from the document. Idempotent: an already-stamped document is left alone.
     */
    public static boolean commitDelivered(Context context, SpotifyTrack track, LyricsDocument doc) {
        if (doc == null) return false;
        if (!doc.catalogCandidateId.isEmpty()) return true;
        SourceId source = CatalogSource.inferSourceId(doc.fetchSource, doc.provider);
        if (source == null) return false;
        boolean exact = source == SourceId.APPLE || source == SourceId.SPOTIFY_NATIVE;
        String bare = CatalogSource.bareTrackId(track == null ? "" : track.uri);
        return recordSuccess(context, source, track, doc,
                exact ? MatchMethod.EXACT_SPOTIFY_ID : MatchMethod.STRONG_SEARCH,
                exact ? bare : "", "", adapterRevision(source));
    }

    /** Commits a terminal provider failure, classified into a durable or transient outcome. */
    public static boolean recordError(Context context, SourceId source, SpotifyTrack track,
                                      String error) {
        try {
            String bareId = CatalogSource.bareTrackId(track == null ? "" : track.uri);
            if (bareId.isEmpty() || source == null) return false;
            ProviderStatus status = ProviderFailureClassifier.classify(source, error);
            long now = System.currentTimeMillis();
            CatalogPolicy policy = CatalogPolicy.read(context);
            return CatalogStore.transact(context, bareId,
                    state -> CatalogDecisions.providerFailure(state, policy, source, status, now))
                    .committed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int adapterRevision(SourceId source) {
        if (source == null) return 0;
        switch (source) {
            case APPLE:
                return APPLE_ADAPTER_REVISION;
            case SPOTIFY_NATIVE:
                return SPOTIFY_NATIVE_ADAPTER_REVISION;
            case AMLL:
                return AMLL_ADAPTER_REVISION;
            case LRCLIB:
                return LRCLIB_ADAPTER_REVISION;
            default:
                return 1;
        }
    }

    /** Restores the provider-owned reading baseline omitted by the canonical document codec. */
    public static void applyProviderTransliteration(CatalogCandidate candidate,
                                                    LyricsDocument document) {
        if (candidate == null || document == null) return;
        List<String> values = candidate.providerTransliterationLines();
        int count = Math.min(values.size(), document.lines.size());
        for (int i = 0; i < count; i++) {
            LyricsLine line = document.lines.get(i);
            String value = values.get(i);
            if (line != null && !isBlank(value)) line.romanizedText = value;
        }
    }

    private static List<String> providerTransliteration(LyricsDocument doc) {
        List<String> out = new ArrayList<>();
        for (LyricsLine line : doc.lines) out.add(line == null ? "" : line.romanizedText);
        return Collections.unmodifiableList(out);
    }

    private static boolean hasTiming(LyricsDocument doc) {
        for (LyricsLine line : doc.lines) {
            if (line != null && (line.startMs > 0 || line.endMs > 0)) return true;
        }
        return false;
    }

    private static boolean hasProviderTranslation(LyricsDocument doc) {
        for (LyricsLine line : doc.lines) {
            if (line != null && !isBlank(line.providerTranslatedText)) return true;
        }
        return false;
    }

    private static boolean hasBackgroundVocals(LyricsDocument doc) {
        for (LyricsLine line : doc.lines) {
            if (line != null && !line.backgroundLines.isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasDuet(LyricsDocument doc) {
        for (LyricsLine line : doc.lines) {
            if (line != null && line.oppositeAligned) return true;
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
