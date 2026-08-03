package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Background-vocal companion line attached to a lead {@link LyricsLine}. */
public class BackgroundLine {
    public String text = "";
    public String romanizedText = "";
    public String translatedText = "";
    public String providerTranslatedText = "";
    public String providerTranslationLanguage = "";
    public List<SyllableSegment> syllables = new ArrayList<>();
    public long startMs;
    public long endMs;

    public static BackgroundLine copyOf(BackgroundLine source) {
        if (source == null) return null;
        BackgroundLine copy = new BackgroundLine();
        copy.text = LyricsDocument.safe(source.text);
        copy.romanizedText = LyricsDocument.safe(source.romanizedText);
        copy.translatedText = LyricsDocument.safe(source.translatedText);
        copy.providerTranslatedText = LyricsDocument.safe(source.providerTranslatedText);
        copy.providerTranslationLanguage = LyricsDocument.safe(source.providerTranslationLanguage);
        copy.startMs = source.startMs;
        copy.endMs = source.endMs;
        for (SyllableSegment seg : source.syllables) copy.syllables.add(SyllableSegment.copyOf(seg));
        return copy;
    }
}
