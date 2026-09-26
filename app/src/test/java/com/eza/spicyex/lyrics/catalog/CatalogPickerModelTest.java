package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.Row;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.RowKind;
import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CatalogPickerModelTest {
    private static int nextId;

    private static CatalogCandidate cand(SourceId source, TimingLevel timing) {
        String id = source.id + "|item|d" + (nextId++);
        return new CatalogCandidate(id, "track", source, "item", MatchMethod.STRONG_SEARCH, 0.9,
                0L, timing, true, true, true, false, false, false, true, "d", "{}", "[]",
                new byte[0], 1, 1, 0L);
    }

    private static Resolution auto(CatalogCandidate winner) {
        return CatalogResolver.resolve(Collections.singletonList(winner), null);
    }

    private static Row sourceRow(List<Row> rows, SourceId source) {
        for (Row row : rows) {
            if (row.kind == RowKind.SOURCE && source.equals(row.sourceId)) return row;
        }
        return null;
    }

    @Test
    public void autoRowShowsWinnerWithoutDuplicatingIt() {
        CatalogCandidate apple = cand(SourceId.APPLE, TimingLevel.SYLLABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(apple),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, auto(apple));

        assertEquals(RowKind.AUTO, rows.get(0).kind);
        assertEquals("Auto", rows.get(0).title);
        // No subtitle: the automatic visit only asks the sources that are due, so the row must not
        // claim it compared against every source.
        assertEquals("", rows.get(0).subtitle);
        assertTrue(rows.get(0).selected);
        Row winner = sourceRow(rows, SourceId.APPLE);
        assertTrue(winner != null && winner.selected);
    }

    @Test
    public void storedSourceRowShowsMatchStatusAndCapabilities() {
        CatalogCandidate apple = cand(SourceId.APPLE, TimingLevel.LINE);
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.APPLE, ProviderStatus.AVAILABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(apple), states,
                null, auto(apple));

        Row row = sourceRow(rows, SourceId.APPLE);
        assertTrue(row != null);
        assertEquals("Apple Music · Line", row.title);
        assertEquals("Available · Translation", row.subtitle);
        assertTrue(row.selected);
        assertTrue(row.stored);
        assertEquals(CatalogPickerModel.DataMark.HAVE, row.mark);
        assertTrue(!row.checkable);
    }

    @Test
    public void uncheckedEnabledSourceOffersATapToCheck() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        Row qq = sourceRow(rows, SourceId.QQ);
        Row netease = sourceRow(rows, SourceId.NETEASE);
        assertTrue(qq != null && netease != null);
        assertEquals("QQ Music", qq.title);
        assertEquals("Tap to check", qq.subtitle);
        assertTrue(!qq.stored);
        assertTrue(qq.checkable);
    }

    @Test
    public void disabledSourceStillOffersTrackScopedCheck() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>singletonMap(SourceId.QQ,
                        ProviderStatus.DISABLED),
                null, null);

        Row qq = sourceRow(rows, SourceId.QQ);
        assertTrue(qq != null);
        assertEquals("Tap to check", qq.subtitle);
        assertTrue(!qq.stored);
        assertTrue(qq.checkable);
    }

    @Test
    public void failedSourceSurfacesItsStatus() {
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.LRCLIB, ProviderStatus.TRANSIENT_ERROR);
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                states, null, null);

        Row lrclib = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(lrclib != null);
        assertEquals("Failed · tap to retry", lrclib.subtitle);
        assertTrue(lrclib.checkable);
        assertEquals(CatalogPickerModel.DataMark.EMPTY, lrclib.mark);
    }

    @Test
    public void manualSelectionMarksThePinnedSourceRow() {
        CatalogCandidate apple = cand(SourceId.APPLE, TimingLevel.LINE);
        CatalogCandidate lrclib = cand(SourceId.LRCLIB, TimingLevel.LINE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                lrclib.candidateId, SourceId.LRCLIB, "item", "");
        List<Row> rows = CatalogPickerModel.build(Arrays.asList(apple, lrclib),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, auto(apple));

        assertTrue(!rows.get(0).selected);
        assertEquals("", rows.get(0).subtitle);
        Row pinned = sourceRow(rows, SourceId.LRCLIB);
        assertTrue(pinned != null && pinned.selected);
    }

    @Test
    public void manualSelectionKeepsPinnedProviderVariantVisible() {
        CatalogCandidate pinnedLine = cand(SourceId.APPLE, TimingLevel.LINE);
        CatalogCandidate newerSyllable = cand(SourceId.APPLE, TimingLevel.SYLLABLE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                pinnedLine.candidateId, SourceId.APPLE, "item", pinnedLine.canonicalDigest);

        List<Row> rows = CatalogPickerModel.build(Arrays.asList(newerSyllable, pinnedLine),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual,
                auto(newerSyllable));

        Row apple = sourceRow(rows, SourceId.APPLE);
        assertTrue(apple.selected);
        assertEquals(pinnedLine.candidateId, apple.candidateId);
        assertEquals("Apple Music · Line", apple.title);
    }

    @Test
    public void theAutoRowNeverCarriesSubtitleText() {
        CatalogCandidate apple = cand(SourceId.APPLE, TimingLevel.LINE);
        CatalogCandidate syllable = cand(SourceId.AMLL, TimingLevel.SYLLABLE);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                apple.candidateId, SourceId.APPLE, "item", "");

        // Every reachable combination: a winner, a manual pin over a winner, and no winner at all.
        List<Row> withWinner = CatalogPickerModel.build(Collections.singletonList(apple),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, auto(apple));
        List<Row> withManual = CatalogPickerModel.build(Arrays.asList(apple, syllable),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, auto(syllable));
        List<Row> withNothing = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);
        List<Row> manualWithNothing = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), manual, null);

        for (List<Row> rows : Arrays.asList(withWinner, withManual, withNothing, manualWithNothing)) {
            assertEquals(RowKind.AUTO, rows.get(0).kind);
            assertTrue(rows.get(0).title.startsWith("Auto"));
            assertEquals("", rows.get(0).subtitle);
        }
    }

    @Test
    public void emptyCatalogStillOffersActions() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        assertEquals(1 + 6 + 2, rows.size());
        assertEquals("Auto · nothing stored yet", rows.get(0).title);
        // The Auto row carries its state in the title and the selected-green colour only. No
        // subtitle in any state, so no state text can be parked in this menu again.
        assertEquals("", rows.get(0).subtitle);
        assertEquals(RowKind.ACTION_CHECK_ALL, rows.get(7).kind);
        assertEquals("Check all sources in order", rows.get(7).title);
        assertEquals(RowKind.ACTION_DELETE_TRACK, rows.get(8).kind);
        assertEquals("Clear saved lyrics", rows.get(8).title);
    }

    @Test
    public void spotifyUncheckedUsesLocalLyrics() {
        List<Row> rows = CatalogPickerModel.build(Collections.<CatalogCandidate>emptyList(),
                Collections.<SourceId, ProviderStatus>emptyMap(), null, null);

        Row spotify = sourceRow(rows, SourceId.SPOTIFY_NATIVE);
        assertTrue(spotify != null);
        assertEquals("Spotify", spotify.title);
        assertEquals("Use Spotify's lyrics", spotify.subtitle);
        assertTrue(!spotify.stored);
        assertTrue(spotify.checkable);
        assertEquals(CatalogPickerModel.DataMark.NONE, spotify.mark);
    }

    @Test
    public void storedSourceAlwaysCarriesHaveMark() {
        CatalogCandidate apple = cand(SourceId.APPLE, TimingLevel.LINE);
        Map<SourceId, ProviderStatus> states = new HashMap<>();
        states.put(SourceId.APPLE, ProviderStatus.AVAILABLE);
        List<Row> rows = CatalogPickerModel.build(Collections.singletonList(apple), states,
                null, auto(apple));

        Row row = sourceRow(rows, SourceId.APPLE);
        assertTrue(row != null && row.stored);
        assertEquals("Available · Translation", row.subtitle);
    }

    @Test
    public void brandsStayUntranslated() {
        assertEquals("Apple Music", CatalogPickerModel.displaySource(SourceId.APPLE));
        assertEquals("Spotify", CatalogPickerModel.displaySource(SourceId.SPOTIFY_NATIVE));
        assertEquals("LRCLIB", CatalogPickerModel.displaySource(SourceId.LRCLIB));
        assertEquals("QQ Music", CatalogPickerModel.displaySource(SourceId.QQ));
    }

    @Test
    public void footerTimingFollowsDocumentType() {
        assertEquals("Syllable", CatalogPickerModel.displayTypeTiming("Syllable"));
        assertEquals("Word", CatalogPickerModel.displayTypeTiming("Word"));
        assertEquals("Line", CatalogPickerModel.displayTypeTiming("Line"));
        assertEquals("Unsynced", CatalogPickerModel.displayTypeTiming("Static"));
        assertEquals("Unsynced", CatalogPickerModel.displayTypeTiming(null));
    }
}
