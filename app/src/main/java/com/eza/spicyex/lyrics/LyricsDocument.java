package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed lyrics document, normalized across all sources (Spicy API, Spotify native DB/model,
 * LRCLIB). {@code lines} is the source-faithful parse result; {@code appliedLines} is the
 * renderer row plan produced by {@link LyricTimeline#applySyncedRows(LyricsDocument)}.
 */
public class LyricsDocument {
    public String trackId = "";
    public String provider = "Spicy Lyrics";
    public String songWriters = ""; // "Written by" credits from the lyrics response, if any
    public String type = "Unknown";
    public String language = "";
    public String fetchSource = "unknown";
    public long durationMs;
    public long startTimeMs;
    public int generation;
    public int processingVersion;
    public boolean processingPending;
    public boolean romanizationPending;
    public boolean translationPending;
    public boolean includesRomanization;
    public boolean includesTranslation;
    public boolean detectedChinese;
    public final List<SpicyTextDetection.Script> detectedScripts = new ArrayList<>();
    public final List<LyricsLine> lines = new ArrayList<>();
    public final List<AppliedLine> appliedLines = new ArrayList<>();

    /** Deep copy of the parse model (applied rows / view state are intentionally not copied). */
    public static LyricsDocument copyOf(LyricsDocument source) {
        if (source == null) return null;
        LyricsDocument copy = new LyricsDocument();
        copy.trackId = safe(source.trackId);
        copy.provider = safe(source.provider);
        copy.songWriters = safe(source.songWriters);
        copy.type = safe(source.type);
        copy.language = safe(source.language);
        copy.fetchSource = safe(source.fetchSource);
        copy.durationMs = source.durationMs;
        copy.startTimeMs = source.startTimeMs;
        copy.generation = source.generation;
        copy.processingVersion = source.processingVersion;
        copy.processingPending = source.processingPending;
        copy.romanizationPending = source.romanizationPending;
        copy.translationPending = source.translationPending;
        copy.includesRomanization = source.includesRomanization;
        copy.includesTranslation = source.includesTranslation;
        copy.detectedChinese = source.detectedChinese;
        copy.detectedScripts.addAll(source.detectedScripts);
        for (LyricsLine line : source.lines) copy.lines.add(LyricsLine.copyOf(line));
        return copy;
    }

    static String safe(String value) {
        return LyricUtils.safe(value);
    }
}
