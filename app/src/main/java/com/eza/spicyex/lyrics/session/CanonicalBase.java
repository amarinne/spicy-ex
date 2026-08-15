package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/**
 * Immutable canonical lyric base for one source revision.
 *
 * <p>Holds original text, timing, stable row/span IDs, and source provenance only. Generated
 * reading or translation text never becomes canonical state — derived layers keep it in their
 * own artifacts and the compatibility composer folds it in at the publication boundary.
 */
public final class CanonicalBase {
    public final String trackUri;
    public final String trackId;
    public final String language;
    public final String provider;
    public final String fetchSource;
    public final String timingType;
    public final long durationMs;
    public final List<CanonicalRow> rows;
    /** Content digest over row identity, text, timing, and spans. Changes when the source changes. */
    public final String digest;

    private final Map<String, Integer> rowIndexById;

    public CanonicalBase(String trackUri, String trackId, String language, String provider,
                         String fetchSource, String timingType, long durationMs, List<CanonicalRow> rows) {
        this.trackUri = Digests.nz(trackUri);
        this.trackId = Digests.nz(trackId);
        this.language = Digests.nz(language);
        this.provider = Digests.nz(provider);
        this.fetchSource = Digests.nz(fetchSource);
        this.timingType = Digests.nz(timingType);
        this.durationMs = durationMs;
        this.rows = Collections.unmodifiableList(
                new ArrayList<>(rows == null ? Collections.<CanonicalRow>emptyList() : rows));
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < this.rows.size(); i++) {
            CanonicalRow row = this.rows.get(i);
            if (row != null) index.put(row.rowId, i);
        }
        this.rowIndexById = Collections.unmodifiableMap(index);
        this.digest = computeDigest();
    }

    /**
     * Reads the canonical projection out of a parsed {@link LyricsDocument}. Only source text and
     * timing are read; derived fields on the document are ignored, and the document is not modified.
     */
    public static CanonicalBase fromDocument(String trackUri, LyricsDocument document) {
        if (document == null) {
            return new CanonicalBase(trackUri, "", "", "", "", "", 0L, Collections.<CanonicalRow>emptyList());
        }
        List<CanonicalRow> rows = new ArrayList<>();
        for (int i = 0; i < document.lines.size(); i++) {
            LyricsLine line = document.lines.get(i);
            if (line == null) continue;
            List<CanonicalSpan> spans = new ArrayList<>();
            for (int s = 0; s < line.syllables.size(); s++) {
                SyllableSegment segment = line.syllables.get(s);
                if (segment == null) continue;
                // Prefer the provider's own stable span owner; fall back to a derived ID only
                // when the source gave us none.
                String spanId = Digests.nz(segment.spanId).isEmpty()
                        ? CanonicalRow.spanId(i, line.text, s, segment.text)
                        : segment.spanId;
                spans.add(new CanonicalSpan(spanId, segment.text, segment.startMs, segment.endMs));
            }
            rows.add(new CanonicalRow(i, line.text, line.startMs, line.endMs, line.interlude, spans));
        }
        return new CanonicalBase(trackUri, document.trackId, document.language, document.provider,
                document.fetchSource, document.type, document.durationMs, rows);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public boolean hasRow(String rowId) {
        return rowIndexById.containsKey(rowId);
    }

    /** Document line index for {@code rowId}, or {@code -1} when the row is not part of this base. */
    public int indexOfRow(String rowId) {
        Integer index = rowIndexById.get(rowId);
        return index == null ? -1 : index;
    }

    /**
     * Row occupying {@code documentIndex} in the document this base was read from, or null.
     *
     * <p>Rows skip null lines, so a row's position in {@link #rows} is not its document index.
     */
    public CanonicalRow rowAt(int documentIndex) {
        for (CanonicalRow row : rows) {
            if (row.index == documentIndex) return row;
            if (row.index > documentIndex) break;
        }
        return null;
    }

    public CanonicalRow row(String rowId) {
        int index = indexOfRow(rowId);
        return index < 0 ? null : rows.get(index);
    }

    /** Newline-joined source text, for language detection and whole-document processors. */
    public String joinedText() {
        StringBuilder out = new StringBuilder();
        for (CanonicalRow row : rows) {
            if (row == null || row.text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(row.text);
        }
        return out.toString();
    }

    private String computeDigest() {
        StringBuilder payload = new StringBuilder(256);
        payload.append(trackId).append(Digests.SEP).append(language).append(Digests.SEP)
                .append(timingType).append(Digests.SEP).append(durationMs);
        for (CanonicalRow row : rows) payload.append(Digests.SEP).append(row.digestPayload());
        return Digests.sha256(payload.toString());
    }
}
