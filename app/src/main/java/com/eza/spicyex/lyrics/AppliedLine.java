package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;

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
    public RenderPlan readingRenderPlan;
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

    /** Memo for {@link #isShortText(int)}: the exact {@code text} instance it was measured from,
     *  so a line whose text is replaced during secondary processing re-measures itself. */
    private String shortTextKey;
    private int shortTextLimit = -1;
    private boolean shortText;

    /**
     * Whether this row's text is at most {@code limitCodePoints} code points long.
     *
     * <p>Memoised because the blur curve asks this of every mounted row on every frame, and
     * {@code codePointCount} walks the whole string each time - a few thousand needless character
     * scans a second on the exact path that has to stay inside the frame budget.
     */
    public boolean isShortText(int limitCodePoints) {
        String current = text == null ? "" : text;
        if (shortTextKey == current && shortTextLimit == limitCodePoints) return shortText;
        shortTextKey = current;
        shortTextLimit = limitCodePoints;
        shortText = current.codePointCount(0, current.length()) <= limitCodePoints;
        return shortText;
    }
}
