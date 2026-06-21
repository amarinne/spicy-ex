package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * One renderer row produced by {@link LyricTimeline#applySyncedRows(LyricsDocument)}: a lead
 * vocal, a background vocal, or a synthesized interlude dot row.
 *
 * {@code startMs}/{@code endMs} define the row's ACTIVE window (endMs may be extended into a
 * short gap so the highlight carries to the next line); the karaoke fill should use the source
 * line's own end time — see {@link LyricTimeline#fillEndMs(AppliedLine)}.
 */
public class AppliedLine {
    public String text = "";
    public String romanizedText = "";
    public String translatedText = "";
    public SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading;
    public final List<SyllableSegment> words = new ArrayList<>();
    // True when `words` were synthesised from the line text (sentence-synced line) purely to attach
    // per-word transliteration — not real word-level timing. Lets us drop them if the setting is off.
    public boolean syntheticWords;
    public LyricsLine sourceLine;
    public long startMs;
    public long endMs;
    public long totalMs;
    public boolean dotLine;
    public boolean bgLine;
    public boolean oppositeAligned;
}
