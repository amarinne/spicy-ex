package com.eza.spicyex.lyrics;

/** One timed word/syllable. Parse data lives on this object; renderer state is external. */
public class SyllableSegment {
    /** Stable provider-local owner. Never derive semantic ownership from display text or row index. */
    public String spanId = "";
    public String text = "";
    public String sourceText = "";
    public String romanizedText = "";
    public long startMs;
    public long endMs;
    public long totalMs;
    /** Raw provider flag. Adapter evidence only; consumers use boundaryAfter. */
    public Boolean providerPartOfWord;
    /** Canonical trailing-edge relation. True means visible separation after this span. */
    public boolean boundaryAfter;
    public String boundaryProvenance = "";
    public int canonicalStartCp = -1;
    public int canonicalEndCp = -1;
    /** Compatibility projection for old host contracts. Do not use for lyric semantics. */
    public boolean partOfWord;
    public boolean dot;
    public boolean bgWord;

    public static SyllableSegment copyOf(SyllableSegment source) {
        if (source == null) return null;
        SyllableSegment copy = new SyllableSegment();
        copy.spanId = LyricsDocument.safe(source.spanId);
        copy.text = LyricsDocument.safe(source.text);
        copy.sourceText = LyricsDocument.safe(source.sourceText);
        copy.romanizedText = LyricsDocument.safe(source.romanizedText);
        copy.startMs = source.startMs;
        copy.endMs = source.endMs;
        copy.totalMs = source.totalMs;
        copy.providerPartOfWord = source.providerPartOfWord;
        copy.boundaryAfter = source.boundaryAfter;
        copy.boundaryProvenance = LyricsDocument.safe(source.boundaryProvenance);
        copy.canonicalStartCp = source.canonicalStartCp;
        copy.canonicalEndCp = source.canonicalEndCp;
        copy.partOfWord = source.partOfWord;
        copy.dot = source.dot;
        copy.bgWord = source.bgWord;
        return copy;
    }
}
