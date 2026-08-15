package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One immutable canonical lyric row: original text, timing, and stable identity.
 *
 * <p>Row IDs combine the row's position with a digest of its source text, so an identical
 * re-parse of the same source reproduces the same ID while a text change produces a new one.
 * Derived layers address rows only by {@link #rowId}; positional application is never valid.
 */
public final class CanonicalRow {
    public final String rowId;
    public final int index;
    public final String text;
    public final long startMs;
    public final long endMs;
    public final boolean interlude;
    public final List<CanonicalSpan> spans;

    public CanonicalRow(int index, String text, long startMs, long endMs, boolean interlude,
                        List<CanonicalSpan> spans) {
        this.index = index;
        this.text = Digests.nz(text);
        this.startMs = startMs;
        this.endMs = endMs;
        this.interlude = interlude;
        this.spans = Collections.unmodifiableList(
                new ArrayList<>(spans == null ? Collections.<CanonicalSpan>emptyList() : spans));
        this.rowId = rowId(index, this.text);
    }

    /** Stable row identifier for {@code index} holding {@code text}. */
    public static String rowId(int index, String text) {
        return "r" + index + "#" + Digests.shortHash(Digests.nz(text));
    }

    /** Stable span identifier for span {@code spanIndex} of the row at {@code index}. */
    public static String spanId(int index, String rowText, int spanIndex, String spanText) {
        return rowId(index, rowText) + "/s" + spanIndex + "#" + Digests.shortHash(Digests.nz(spanText));
    }

    String digestPayload() {
        StringBuilder out = new StringBuilder(64);
        out.append(rowId).append(Digests.SEP).append(text).append(Digests.SEP)
                .append(startMs).append(Digests.SEP).append(endMs).append(Digests.SEP)
                .append(interlude ? '1' : '0');
        for (CanonicalSpan span : spans) out.append(Digests.SEP).append(span.digestPayload());
        return out.toString();
    }
}
