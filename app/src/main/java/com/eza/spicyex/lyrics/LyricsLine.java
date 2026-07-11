package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;

/** One parsed lyric line (vocal or interlude marker), before row planning. */
public class LyricsLine {
    public String text = "";
    public String romanizedText = "";
    public String translatedText = "";
    public SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading;
    public RenderPlan readingRenderPlan;
    public List<SyllableSegment> syllables = new ArrayList<>();
    public List<BackgroundLine> backgroundLines = new ArrayList<>();
    public String chineseMode = "";
    public long startMs;
    public long endMs;
    public boolean interlude;
    public boolean oppositeAligned;

    public static LyricsLine copyOf(LyricsLine source) {
        if (source == null) return null;
        LyricsLine copy = new LyricsLine();
        copy.text = LyricsDocument.safe(source.text);
        copy.romanizedText = LyricsDocument.safe(source.romanizedText);
        copy.translatedText = LyricsDocument.safe(source.translatedText);
        copy.japaneseReading = source.japaneseReading;
        copy.readingRenderPlan = source.readingRenderPlan;
        copy.chineseMode = LyricsDocument.safe(source.chineseMode);
        copy.startMs = source.startMs;
        copy.endMs = source.endMs;
        copy.interlude = source.interlude;
        copy.oppositeAligned = source.oppositeAligned;
        for (SyllableSegment seg : source.syllables) copy.syllables.add(SyllableSegment.copyOf(seg));
        for (BackgroundLine bg : source.backgroundLines) copy.backgroundLines.add(BackgroundLine.copyOf(bg));
        return copy;
    }
}
