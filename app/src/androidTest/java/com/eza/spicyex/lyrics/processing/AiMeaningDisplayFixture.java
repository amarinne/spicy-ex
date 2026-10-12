package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.MeaningArtifact;

/** Gives the native fixture access to the same required-row selection used by the Meaning lane. */
public final class AiMeaningDisplayFixture {
    private AiMeaningDisplayFixture() { }

    public static MeaningArtifact select(CanonicalBase base, LyricsDocument document, MeaningArtifact ai) {
        return MeaningDisplaySelector.select(base,
                LyricsMeaningLane.requiredRowIds(base, document, "th", "en"), ai, null);
    }
}
