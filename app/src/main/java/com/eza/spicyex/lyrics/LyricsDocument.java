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
    /** Source-selection metadata shared by fullscreen and now-playing surfaces. */
    public String selectedSource = "";
    public String selectionMode = "smart";
    public String selectionOverride = "auto";
    public boolean spicyPackedPayload;
    public boolean spicyEnvelopeNoticePresent;
    public Integer spicyQueryStatus;
    public String spicyFormat = "";
    public boolean spicyPoisoned;
    public String spicyQualityReason;
    public long durationMs;
    public long startTimeMs;
    public int generation;
    public int processingVersion;
    public boolean processingPending;
    public boolean romanizationPending;
    public boolean translationPending;
    public boolean includesRomanization;
    public boolean includesTranslation;
    /**
     * Whether the reading and translation currently shown came from a model.
     *
     * <p>Provenance lives on the artifact, but the controls that need it read the document, so the
     * composer stamps it here on the way through. Display state only: nothing decides what to run
     * from these.
     */
    public boolean readingFromAi;
    public boolean translationFromAi;
    /** True only while the corresponding layer is actively running under AI authority. */
    public boolean readingAiPending;
    public boolean translationAiPending;
    /** True when the displayed AI translation used Google Translate output as request input. */
    public boolean translationAiRefinedFromGoogle;
    /** Model that produced the displayed AI output, from its provenance. Empty when not AI. */
    public String readingAiModel = "";
    public String translationAiModel = "";
    /** Exact privacy-safe AI failure tokens for the current layer settlement, or empty. */
    public String readingAiFailureToken = "";
    public String translationAiFailureToken = "";
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
        copy.selectedSource = safe(source.selectedSource);
        copy.selectionMode = safe(source.selectionMode);
        copy.selectionOverride = safe(source.selectionOverride);
        copy.spicyPackedPayload = source.spicyPackedPayload;
        copy.spicyEnvelopeNoticePresent = source.spicyEnvelopeNoticePresent;
        copy.spicyQueryStatus = source.spicyQueryStatus;
        copy.spicyFormat = safe(source.spicyFormat);
        copy.spicyPoisoned = source.spicyPoisoned;
        copy.spicyQualityReason = source.spicyQualityReason;
        copy.durationMs = source.durationMs;
        copy.startTimeMs = source.startTimeMs;
        copy.generation = source.generation;
        copy.processingVersion = source.processingVersion;
        copy.processingPending = source.processingPending;
        copy.romanizationPending = source.romanizationPending;
        copy.translationPending = source.translationPending;
        copy.includesRomanization = source.includesRomanization;
        copy.includesTranslation = source.includesTranslation;
        copy.readingFromAi = source.readingFromAi;
        copy.translationFromAi = source.translationFromAi;
        copy.readingAiPending = source.readingAiPending;
        copy.translationAiPending = source.translationAiPending;
        copy.translationAiRefinedFromGoogle = source.translationAiRefinedFromGoogle;
        copy.readingAiModel = source.readingAiModel;
        copy.translationAiModel = source.translationAiModel;
        copy.readingAiFailureToken = safe(source.readingAiFailureToken);
        copy.translationAiFailureToken = safe(source.translationAiFailureToken);
        copy.detectedChinese = source.detectedChinese;
        copy.detectedScripts.addAll(source.detectedScripts);
        for (LyricsLine line : source.lines) copy.lines.add(LyricsLine.copyOf(line));
        return copy;
    }

    static String safe(String value) {
        return LyricUtils.safe(value);
    }
}
