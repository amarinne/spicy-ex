package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;

import java.util.Locale;

/**
 * Pure mappings from legacy stores into catalog records. All functions are side-effect free so
 * the migration matrix stays JVM-testable; the cutover slice applies them to real storage.
 */
public final class CatalogLegacyImport {
    private CatalogLegacyImport() {
    }

    /** Manual pick described by a legacy override value: mode plus mapped source. */
    public static final class LegacyPick {
        public final CatalogSource.SelectionMode mode;
        public final SourceId sourceId;

        LegacyPick(CatalogSource.SelectionMode mode, SourceId sourceId) {
            this.mode = mode;
            this.sourceId = sourceId;
        }
    }

    /**
     * Maps one legacy per-track override value ({@code null} and {@code "auto"} clear it) to a
     * catalog selection. The retired {@code spicy} route becomes Apple; an experimental
     * direct-Musixmatch value becomes Auto because that route no longer exists.
     */
    public static LegacyPick pickForLegacyOverride(String rawValue) {
        if (rawValue == null) return new LegacyPick(CatalogSource.SelectionMode.AUTO, null);
        String v = rawValue.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v) || "musixmatch".equals(v)) {
            return new LegacyPick(CatalogSource.SelectionMode.AUTO, null);
        }
        if ("spicy".equals(v)) {
            return new LegacyPick(CatalogSource.SelectionMode.MANUAL, SourceId.APPLE);
        }
        SourceId parsed = SourceId.parse(v);
        if (parsed == null) return new LegacyPick(CatalogSource.SelectionMode.AUTO, null);
        if ("apple_music".equals(v) || "aml".equals(v) || "lenerd".equals(v)) {
            return new LegacyPick(CatalogSource.SelectionMode.MANUAL, SourceId.APPLE);
        }
        return new LegacyPick(CatalogSource.SelectionMode.MANUAL, parsed);
    }

    /**
     * Builds the one candidate a legacy {@code CanonicalSourceCache} winner becomes. Provenance
     * the old record never kept (match method, confidence, provider item) is marked unknown
     * rather than invented: unknown provenance refreshes, it never outranks a validated result.
     */
    public static CatalogCandidate candidateFromRecord(CanonicalSourceCodec.Record record,
                                                       String trackUri, long importedAtMs) {
        String bareId = CatalogSource.bareTrackId(trackUri);
        if (record == null || record.document == null || bareId.isEmpty()) return null;
        LyricsDocument doc = record.document;
        SourceId source = CatalogSource.inferSourceId(doc.fetchSource, doc.provider);
        if (source == null) source = SourceId.APPLE;
        TimingLevel timing = TimingLevel.fromDocumentType(doc.type);
        boolean complete = !doc.lines.isEmpty();
        String normalized = CanonicalSourceCodec.encode(doc, record.sourceRevision,
                record.canonicalDigest, importedAtMs, record.selectionIdentity);
        String digest = record.canonicalDigest == null || record.canonicalDigest.isEmpty()
                ? "legacy" : record.canonicalDigest;
        String candidateId = CatalogCandidate.stableId(bareId, source, "legacy-winner", digest);
        boolean timingHealthy = timing == TimingLevel.UNSYNCED || hasTiming(doc);
        return new CatalogCandidate(candidateId, bareId, source, "legacy-winner",
                MatchMethod.STRONG_SEARCH, 0.5, 0L, timing, complete, timingHealthy,
                hasProviderTranslation(doc), false, hasBackgroundVocals(doc), false,
                !isBlank(doc.songWriters), digest, normalized,
                CatalogCandidate.emptyTransliteration(doc.lines.size()), new byte[0],
                record.sourceRevision, 0, importedAtMs);
    }

    private static boolean hasTiming(LyricsDocument doc) {
        if (doc == null) return false;
        for (com.eza.spicyex.lyrics.LyricsLine line : doc.lines) {
            if (line != null && (line.startMs > 0 || line.endMs > 0)) return true;
        }
        return false;
    }

    private static boolean hasProviderTranslation(LyricsDocument doc) {
        if (doc == null) return false;
        for (com.eza.spicyex.lyrics.LyricsLine line : doc.lines) {
            if (line != null && !isBlank(line.providerTranslatedText)) return true;
        }
        return false;
    }

    private static boolean hasBackgroundVocals(LyricsDocument doc) {
        if (doc == null) return false;
        for (com.eza.spicyex.lyrics.LyricsLine line : doc.lines) {
            if (line != null && !line.backgroundLines.isEmpty()) return true;
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
