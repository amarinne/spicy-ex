package com.eza.spicyex.lyrics;

/** One timed word/syllable. Parse data lives on this object; renderer state is external. */
public class SyllableSegment {
    public String text = "";
    public String romanizedText = "";
    public long startMs;
    public long endMs;
    public long totalMs;
    public boolean partOfWord;
    public boolean dot;
    public boolean bgWord;

    public static SyllableSegment copyOf(SyllableSegment source) {
        if (source == null) return null;
        SyllableSegment copy = new SyllableSegment();
        copy.text = LyricsDocument.safe(source.text);
        copy.romanizedText = LyricsDocument.safe(source.romanizedText);
        copy.startMs = source.startMs;
        copy.endMs = source.endMs;
        copy.totalMs = source.totalMs;
        copy.partOfWord = source.partOfWord;
        copy.dot = source.dot;
        copy.bgWord = source.bgWord;
        return copy;
    }
}
