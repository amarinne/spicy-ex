package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure view-model for the fullscreen source picker. One row per source: a stored best candidate
 * renders with its timing and capabilities, an unchecked source shows its status with a tap to
 * check it. The dialog renders rows; every action routes back through the shared session so
 * now playing updates together. No lyric bodies leave this model: titles carry providers,
 * timing, status, and capability labels only.
 */
public final class CatalogPickerModel {
    private CatalogPickerModel() {
    }

    public enum RowKind {
        AUTO,
        SOURCE,
        ACTION_CHECK_ALL,
        ACTION_DELETE_TRACK
    }

    /** Leading status glyph: fetched data usable, fetched but empty, or nothing to report. */
    public enum DataMark {
        NONE,
        HAVE,
        EMPTY
    }

    /** Source display order matches the automatic tie-break. */
    static final SourceId[] SOURCE_ORDER = {
            SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB,
            SourceId.QQ, SourceId.NETEASE,
    };

    public static final class Row {
        public final RowKind kind;
        public final String title;
        public final String subtitle;
        public final boolean selected;
        public final boolean stored;
        /** Checking starts a request; false for pure actions. */
        public final boolean checkable;
        public final DataMark mark;
        public final String candidateId;
        public final SourceId sourceId;

        Row(RowKind kind, String title, String subtitle, boolean selected, boolean stored,
            boolean checkable, DataMark mark, String candidateId, SourceId sourceId) {
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.selected = selected;
            this.stored = stored;
            this.checkable = checkable;
            this.mark = mark == null ? DataMark.NONE : mark;
            this.candidateId = candidateId == null ? "" : candidateId;
            this.sourceId = sourceId;
        }

        /** Copy with a replaced title (delete-arm confirmation); null keeps the title. */
        public Row withTitle(String title) {
            return new Row(kind, title == null ? this.title : title, subtitle, selected, stored,
                    checkable, mark, candidateId, sourceId);
        }

        public Row withSubtitle(String subtitle) {
            return new Row(kind, title, subtitle == null ? this.subtitle : subtitle, selected,
                    stored, checkable, mark, candidateId, sourceId);
        }
    }

    /**
     * @param candidates stored candidates for the track (any order; ranked here)
     * @param states persisted per-source states; absent means not checked
     * @param selection current selection (null behaves as Auto)
     * @param auto the resolver's current automatic outcome (null when nothing stored)
     */
    public static List<Row> build(List<CatalogCandidate> candidates,
                                  Map<SourceId, ProviderStatus> states,
                                  CatalogSelection selection, Resolution auto) {
        List<Row> rows = new ArrayList<>();
        boolean manual = selection != null && selection.mode == SelectionMode.MANUAL;
        SourceId winnerSource = !manual && auto != null && auto.winner != null
                ? auto.winner.sourceId : null;
        String winnerCandidate = !manual && auto != null && auto.winner != null
                ? auto.winner.candidateId : null;
        if (auto != null && auto.winner != null) {
            // No subtitle, in any state. "Best match" claimed a comparison across every source
            // when the automatic visit only asks the sources that are due, and "Fetching" was
            // state text the owner asked not to park in this menu. The title plus the
            // selected-green state is the whole claim.
            rows.add(new Row(RowKind.AUTO, "Auto", "",
                    !manual, true, false, DataMark.NONE, auto.winner.candidateId,
                    auto.winner.sourceId));
        } else {
            rows.add(new Row(RowKind.AUTO, "Auto · nothing stored yet", "",
                    !manual, false, false, DataMark.NONE, "", null));
        }
        Map<SourceId, CatalogCandidate> bestBySource = bestBySource(candidates);
        if (manual && selection != null) {
            CatalogCandidate pinned = candidateById(candidates, selection.candidateId);
            if (pinned != null && pinned.sourceId != null) {
                // The selected revision remains visible even when a newer variant from the same
                // provider ranks higher. A manual pin identifies a candidate, not just a brand.
                bestBySource.put(pinned.sourceId, pinned);
            }
        }
        for (SourceId source : SOURCE_ORDER) {
            CatalogCandidate best = bestBySource.get(source);
            ProviderStatus status = stateFor(states, source);
            boolean selected = manual && selection != null && best != null
                    && selection.candidateId.equals(best.candidateId);
            if (!manual && winnerSource != null && best != null
                    && winnerCandidate != null && winnerCandidate.equals(best.candidateId)) {
                // Auto mode greens the winning candidate row alongside the Auto row itself.
                selected = true;
            }
            if (best != null) {
                rows.add(new Row(RowKind.SOURCE,
                        displaySource(source) + " · " + displayTiming(best),
                        statusLine(best, status),
                        selected, true, false, DataMark.HAVE, best.candidateId, source));
            } else if (source == SourceId.SPOTIFY_NATIVE) {
                // Spotify reuses the lyrics Spotify already fetched for this track: one local
                // read, no network request. Tapping adopts whatever is already there.
                rows.add(new Row(RowKind.SOURCE, displaySource(source),
                        "Use Spotify's lyrics",
                        false, false, true, DataMark.NONE, "", source));
            } else {
                rows.add(new Row(RowKind.SOURCE, displaySource(source),
                        checkHint(status),
                        false, false, true, emptyMark(status), "", source));
            }
        }
        rows.add(new Row(RowKind.ACTION_CHECK_ALL, "Check all sources in order",
                "Apple, Spotify, AMLL, LRCLIB until one returns lyrics",
                false, false, false, DataMark.NONE, "", null));
        rows.add(new Row(RowKind.ACTION_DELETE_TRACK, "Clear saved lyrics",
                "stored candidates and states", false, false, false, DataMark.NONE,
                "", null));
        return Collections.unmodifiableList(rows);
    }

    /** Fetched-but-empty outcomes earn an X glyph; anything else reports nothing. */
    static DataMark emptyMark(ProviderStatus status) {
        if (status == null) return DataMark.NONE;
        switch (status) {
            case NOT_FOUND:
            case TRANSIENT_ERROR:
            case REJECTED:
            case NEEDS_REFRESH:
                return DataMark.EMPTY;
            default:
                return DataMark.NONE;
        }
    }

    /** Short retry hint for an enabled source with no stored candidate. */
    static String checkHint(ProviderStatus status) {
        if (status == null || status == ProviderStatus.NOT_CHECKED) return "Tap to check";
        switch (status) {
            case AVAILABLE:
                return "Tap to check";
            case NOT_FOUND:
                return "Not found · tap to retry";
            case TRANSIENT_ERROR:
                return "Failed · tap to retry";
            case NEEDS_REFRESH:
                return "Needs refresh · tap to retry";
            case REJECTED:
                return "Rejected · tap to retry";
            default:
                return "Tap to check";
        }
    }

    static String displaySource(SourceId source) {
        if (source == null) return "Unknown";
        switch (source) {
            case APPLE: return "Apple Music";
            case SPOTIFY_NATIVE: return "Spotify";
            case AMLL: return "AMLL";
            case LRCLIB: return "LRCLIB";
            case QQ: return "QQ Music";
            case NETEASE: return "NetEase";
            default: return source.id;
        }
    }

    static String displayTiming(CatalogCandidate candidate) {
        if (candidate == null) return "Unsynced";
        return displayTimingLevel(candidate.timingLevel);
    }

    /** Footer timing label for a rendered document type. */
    public static String displayTypeTiming(String documentType) {
        return displayTimingLevel(CatalogSource.TimingLevel.fromDocumentType(documentType));
    }

    private static String displayTimingLevel(CatalogSource.TimingLevel level) {
        if (level == null) return "Unsynced";
        switch (level) {
            case SYLLABLE: return "Syllable";
            case WORD: return "Word";
            case LINE: return "Line";
            default: return "Unsynced";
        }
    }

    static String statusLine(CatalogCandidate candidate, ProviderStatus status) {
        StringBuilder out = new StringBuilder(displayStatus(status));
        List<String> caps = new ArrayList<>();
        if (candidate.hasProviderTranslation) caps.add("Translation");
        if (candidate.hasProviderTransliteration) caps.add("Romanization");
        if (!caps.isEmpty()) out.append(" · ").append(join(caps));
        return out.toString();
    }

    static String displayStatus(ProviderStatus status) {
        if (status == null) return "Not checked";
        switch (status) {
            case AVAILABLE: return "Available";
            case NOT_CHECKED: return "Not checked";
            case NOT_FOUND: return "Not found";
            case TRANSIENT_ERROR: return "Failed";
            case REJECTED: return "Rejected";
            case NEEDS_REFRESH: return "Needs refresh";
            case DISABLED: return "Disabled";
            default: return "Not checked";
        }
    }

    private static Map<SourceId, CatalogCandidate> bestBySource(
            List<CatalogCandidate> candidates) {
        Map<SourceId, CatalogCandidate> best = new EnumMap<>(SourceId.class);
        for (CatalogCandidate candidate : CatalogResolver.ranked(candidates)) {
            if (candidate.sourceId != null && !best.containsKey(candidate.sourceId)) {
                best.put(candidate.sourceId, candidate);
            }
        }
        return best;
    }

    private static CatalogCandidate candidateById(List<CatalogCandidate> candidates, String id) {
        if (candidates == null || id == null || id.isEmpty()) return null;
        for (CatalogCandidate candidate : candidates) {
            if (candidate != null && id.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    private static ProviderStatus stateFor(Map<SourceId, ProviderStatus> states, SourceId source) {
        if (states == null || source == null) return null;
        return states.get(source);
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        return out.toString();
    }
}
