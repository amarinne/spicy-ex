package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MeaningDisplaySelectorTest {
    @Test
    public void fullAiWinsOverFullGoogle() {
        Fixture fixture = fixture();
        MeaningArtifact ai = fixture.artifact(LayerAuthority.AI, "ai", "AI one", "AI two", false);
        MeaningArtifact google = fixture.artifact(
                LayerAuthority.MACHINE, "google", "Google one", "Google two", false);

        assertSame(ai, MeaningDisplaySelector.select(
                fixture.base, fixture.requiredRows, ai, google));
    }

    @Test
    public void fullGoogleWinsOverSparseAi() {
        Fixture fixture = fixture();
        MeaningArtifact ai = fixture.artifact(
                LayerAuthority.AI, "ai", "AI one", null, false);
        MeaningArtifact google = fixture.artifact(
                LayerAuthority.MACHINE, "google", "Google one", "Google two", false);

        assertSame(google, MeaningDisplaySelector.select(
                fixture.base, fixture.requiredRows, ai, google));
    }

    @Test
    public void incompleteCandidatesNeverBecomeFinalDisplayAuthority() {
        Fixture fixture = fixture();
        MeaningArtifact ai = fixture.artifact(
                LayerAuthority.AI, "ai", "AI one", null, false);
        MeaningArtifact google = fixture.artifact(
                LayerAuthority.MACHINE, "google", "Google one", "Google two", true);

        assertNull(MeaningDisplaySelector.select(
                fixture.base, fixture.requiredRows, ai, google));
    }

    @Test
    public void aiOnlyRejectsSparseAiWithoutACompleteFallback() {
        Fixture fixture = fixture();
        MeaningArtifact ai = fixture.artifact(
                LayerAuthority.AI, "ai", "AI one", null, false);

        assertNull(MeaningDisplaySelector.select(
                fixture.base, fixture.requiredRows, ai, null));
    }

    @Test
    public void requiredRowsIncludeCachedGeneratedTextButExcludeCompatibleProviderText() {
        LyricsDocument document = new LyricsDocument();
        LyricsLine generated = new LyricsLine();
        generated.text = "один";
        generated.translatedText = "one";
        LyricsLine provider = new LyricsLine();
        provider.text = "два";
        provider.providerTranslatedText = "two";
        provider.providerTranslationLanguage = "en";
        provider.translatedText = "two";
        document.lines.add(generated);
        document.lines.add(provider);
        CanonicalBase base = CanonicalBase.fromDocument("track", document);

        Set<String> required = LyricsMeaningLane.requiredRowIds(base, document, "ru", "en");

        org.junit.Assert.assertTrue(required.contains(base.rows.get(0).rowId));
        org.junit.Assert.assertFalse(required.contains(base.rows.get(1).rowId));
    }

    private static Fixture fixture() {
        LyricsDocument document = new LyricsDocument();
        LyricsLine first = new LyricsLine();
        first.text = "один";
        LyricsLine second = new LyricsLine();
        second.text = "два";
        document.lines.add(first);
        document.lines.add(second);
        CanonicalBase base = CanonicalBase.fromDocument("track", document);
        return new Fixture(base, new LinkedHashSet<>(Arrays.asList(
                base.rows.get(0).rowId, base.rows.get(1).rowId)));
    }

    private static final class Fixture {
        final CanonicalBase base;
        final Set<String> requiredRows;

        Fixture(CanonicalBase base, Set<String> requiredRows) {
            this.base = base;
            this.requiredRows = requiredRows;
        }

        MeaningArtifact artifact(LayerAuthority authority, String provider,
                                 String first, String second, boolean partial) {
            java.util.List<MeaningEntry> entries = new java.util.ArrayList<>();
            if (first != null) entries.add(new MeaningEntry(base.rows.get(0).rowId, first, "en"));
            if (second != null) entries.add(new MeaningEntry(base.rows.get(1).rowId, second, "en"));
            return new MeaningArtifact(base.digest, provider + "-config",
                    new LayerProvenance(authority, provider, "contract", 0L), entries, partial);
        }
    }
}
