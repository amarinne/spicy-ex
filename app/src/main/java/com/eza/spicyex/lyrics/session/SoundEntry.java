package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;

/**
 * Reading output for one canonical row.
 *
 * <p>{@link #renderPlan} is the authoritative span-aligned form when present; {@link #displayText}
 * is the whole-line fallback for documents without span timing. A Sound entry never carries
 * translation text — {@code RenderPlan.translation} stays unpopulated by construction here.
 */
public final class SoundEntry implements LayerEntry {
    public final String rowId;
    public final String displayText;
    /** Output orthography / mode the reading was produced in, e.g. {@code "pinyin"} or {@code "rr"}. */
    public final String mode;
    /** Span-aligned reading plan owned by the reading pipeline; null when line-level only. */
    public final RenderPlan renderPlan;
    /** Japanese furigana/romaji reading, when the Japanese processor produced one. */
    public final SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading;
    public final List<SpanReading> spanReadings;

    public SoundEntry(String rowId, String displayText, String mode, RenderPlan renderPlan,
                      List<SpanReading> spanReadings) {
        this(rowId, displayText, mode, renderPlan, null, spanReadings);
    }

    public SoundEntry(String rowId, String displayText, String mode, RenderPlan renderPlan,
                      SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading,
                      List<SpanReading> spanReadings) {
        this.japaneseReading = japaneseReading;
        this.rowId = Digests.nz(rowId);
        this.displayText = Digests.nz(displayText);
        this.mode = Digests.nz(mode);
        this.renderPlan = renderPlan;
        this.spanReadings = Collections.unmodifiableList(
                new ArrayList<>(spanReadings == null ? Collections.<SpanReading>emptyList() : spanReadings));
    }

    public static SoundEntry line(String rowId, String displayText, String mode) {
        return new SoundEntry(rowId, displayText, mode, null, null);
    }

    public static SoundEntry plan(String rowId, String mode, RenderPlan renderPlan) {
        return new SoundEntry(rowId, renderPlan == null ? "" : Digests.nz(renderPlan.joinedDisplayText),
                mode, renderPlan, null);
    }

    /**
     * Captures the whole reading projection a processor left on one line: plan or legacy string,
     * Japanese reading, output mode, and per-span readings addressed by canonical span IDs.
     *
     * <p>This is the bridge from a lane that still writes onto a {@code LyricsLine} to an artifact
     * the session can own. It must stay lossless, or the composer cannot reproduce what the lane
     * rendered.
     */
    public static SoundEntry fromLine(CanonicalRow row, com.eza.spicyex.lyrics.LyricsLine line) {
        if (row == null || line == null) return null;
        List<SpanReading> spans = new ArrayList<>();
        int count = Math.min(row.spans.size(), line.syllables.size());
        for (int i = 0; i < count; i++) {
            com.eza.spicyex.lyrics.SyllableSegment segment = line.syllables.get(i);
            if (segment == null || Digests.nz(segment.romanizedText).isEmpty()) continue;
            spans.add(new SpanReading(row.spans.get(i).spanId, i, segment.romanizedText));
        }
        return new SoundEntry(row.rowId,
                line.readingRenderPlan == null ? Digests.nz(line.romanizedText) : "",
                Digests.nz(line.chineseMode), line.readingRenderPlan, line.japaneseReading, spans);
    }

    @Override public String rowId() {
        return rowId;
    }

    @Override public String digestPayload() {
        StringBuilder out = new StringBuilder(48);
        out.append(rowId).append(Digests.SEP).append(displayText).append(Digests.SEP).append(mode);
        if (japaneseReading != null) {
            out.append(Digests.SEP).append(Digests.nz(japaneseReading.romaji));
        }
        if (renderPlan != null) {
            out.append(Digests.SEP).append(Digests.nz(renderPlan.lineId))
                    .append('>').append(Digests.nz(renderPlan.joinedDisplayText));
            for (TimedReadingUnit unit : renderPlan.timedReadingUnits) {
                if (unit == null) continue;
                out.append(',').append(Digests.nz(unit.spanId)).append('=').append(Digests.nz(unit.text));
            }
        }
        for (SpanReading reading : spanReadings) {
            out.append(Digests.SEP).append(reading.spanId).append('=').append(reading.text);
        }
        return out.toString();
    }

    /**
     * Reading text bound to one canonical span.
     *
     * <p>Carries the span's position as well as its ID: a provider that supplies no span IDs gets
     * derived ones, which the document's own segments do not carry, so the ID alone cannot always
     * find the segment back.
     */
    public static final class SpanReading {
        public final String spanId;
        public final int spanIndex;
        public final String text;

        public SpanReading(String spanId, int spanIndex, String text) {
            this.spanId = Digests.nz(spanId);
            this.spanIndex = spanIndex;
            this.text = Digests.nz(text);
        }

        public SpanReading(String spanId, String text) {
            this(spanId, -1, text);
        }
    }
}
