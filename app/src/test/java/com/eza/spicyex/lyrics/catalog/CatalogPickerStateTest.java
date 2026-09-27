package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.DataMark;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.Row;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.RowKind;
import com.eza.spicyex.lyrics.catalog.CatalogPickerState.Climb;
import com.eza.spicyex.lyrics.catalog.CatalogPickerState.Shown;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CatalogPickerStateTest {
    private static final String A = "spotify:track:a";
    private static final String B = "spotify:track:b";
    private static final String LRC = "SOURCE:lrclib";
    private static final String DELETE = "ACTION_DELETE_TRACK";
    private static final String CHECK_ALL = "ACTION_CHECK_ALL";

    private static List<Row> rows(String lrclibSubtitle) {
        return Arrays.asList(
                new Row(RowKind.AUTO, "Auto", "", true, false, false, DataMark.NONE, "", null),
                new Row(RowKind.SOURCE, "LRCLIB", lrclibSubtitle, false, false, true,
                        DataMark.NONE, "", SourceId.LRCLIB),
                new Row(RowKind.ACTION_CHECK_ALL, "Check all", "", false, false, false,
                        DataMark.NONE, "", null),
                new Row(RowKind.ACTION_DELETE_TRACK, "Clear saved lyrics", "", false, false,
                        false, DataMark.NONE, "", null));
    }

    private static CatalogPickerState loaded(String uri) {
        CatalogPickerState state = CatalogPickerState.initial(uri).requestLoad();
        return state.withRows(uri, state.loadSerial(), rows("Tap to check"));
    }

    private static Shown shown(CatalogPickerState state, String key) {
        for (Shown row : state.project("Checking", "Tap again to delete")) {
            if (row.key.equals(key)) return row;
        }
        throw new AssertionError("no row " + key);
    }

    @Test
    public void keysAreStableAcrossReloadedText() {
        assertEquals(LRC, CatalogPickerState.key(rows("a").get(1)));
        assertEquals(CatalogPickerState.key(rows("a").get(1)),
                CatalogPickerState.key(rows("Not found · tap to retry").get(1)));
    }

    @Test
    public void pendingRowShowsCheckingUntilALoadRequestedAfterFinishLands() {
        CatalogPickerState state = loaded(A);
        CatalogPickerState beforeFinish = state.requestLoad(); // a tick-driven load in flight
        int earlyLoad = beforeFinish.loadSerial();
        state = beforeFinish.started(LRC);
        assertTrue(shown(state, LRC).pending);
        assertEquals("Checking", shown(state, LRC).row.subtitle);
        assertFalse(state.canStart(LRC));

        state = state.finished(LRC, true);
        int settleLoad = state.loadSerial();
        // The early load read the store before the command committed: still pending.
        state = state.withRows(A, earlyLoad, rows("Tap to check"));
        assertTrue(shown(state, LRC).pending);

        state = state.withRows(A, settleLoad, rows("Available"));
        assertFalse(shown(state, LRC).pending);
        assertEquals("Available", shown(state, LRC).row.subtitle);
        assertTrue(state.canStart(LRC));
    }

    @Test
    public void staleTrackAndSupersededLoadsAreDropped() {
        CatalogPickerState state = loaded(A).requestLoad().requestLoad();
        int older = state.loadSerial() - 1;
        state = state.withRows(A, state.loadSerial(), rows("new"));
        assertSame(state, state.withRows(A, older, rows("old")));
        CatalogPickerState pending = state.requestLoad();
        // A fresh, requested serial still cannot carry another track's rows.
        assertSame(pending, pending.withRows(B, pending.loadSerial(), rows("other track")));
        assertEquals("new", shown(state, LRC).row.subtitle);
    }

    @Test
    public void unrequestedSerialIsDropped() {
        CatalogPickerState state = loaded(A);
        assertSame(state, state.withRows(A, state.loadSerial() + 1, rows("future")));
    }

    @Test
    public void armedDeleteConfirmsInPlaceAndDisarmsOnAnyOtherStart() {
        CatalogPickerState state = loaded(A).armed(DELETE);
        assertTrue(shown(state, DELETE).armed);
        assertEquals("Tap again to delete", shown(state, DELETE).row.title);
        assertEquals("Clear saved lyrics", shown(state, DELETE).source.title);

        state = state.started(LRC);
        assertEquals("", state.armedKey);
        assertEquals("Clear saved lyrics", shown(state, DELETE).row.title);
    }

    @Test
    public void armSurvivesReloadButNotTrackChange() {
        CatalogPickerState state = loaded(A).armed(DELETE).requestLoad();
        state = state.withRows(A, state.loadSerial(), rows("reloaded"));
        assertEquals(DELETE, state.armedKey);

        state = state.trackChanged(B);
        assertEquals("", state.armedKey);
        assertTrue(state.rows.isEmpty());
        assertFalse(state.canStart(DELETE));
    }

    @Test
    public void armExpiryOnlyClearsTheArmItWasScheduledFor() {
        CatalogPickerState first = loaded(A).armed(DELETE);
        int firstSerial = first.armSerial();
        CatalogPickerState rearmed = first.disarmed().armed(DELETE);
        assertEquals(DELETE, rearmed.armExpired(firstSerial).armedKey);
        assertEquals("", rearmed.armExpired(rearmed.armSerial()).armedKey);
    }

    @Test
    public void trackChangeDropsPendingAndIgnoresTheOldFinish() {
        CatalogPickerState state = loaded(A).started(LRC).trackChanged(B);
        assertSame(state, state.finished(LRC, true));
        state = state.requestLoad();
        state = state.withRows(B, state.loadSerial(), rows("Tap to check"));
        assertFalse(shown(state, LRC).pending);
        assertEquals(B, state.trackUri);
    }

    @Test
    public void fetchActivityWithoutARequestedClimbChangesNothing() {
        CatalogPickerState state = loaded(A);
        assertSame(state, state.fetchObserved(true));
        assertSame(state, state.fetchObserved(false));
        assertFalse(shown(state, CHECK_ALL).pending);
    }

    @Test
    public void climbStaysPendingUntilTheObservedFetchEnds() {
        CatalogPickerState state = loaded(A).started(CHECK_ALL).finished(CHECK_ALL, true);
        state = state.withRows(A, state.loadSerial(), rows("x"));
        assertEquals(Climb.REQUESTED, state.climb);
        assertTrue(shown(state, CHECK_ALL).pending);
        assertFalse(state.canStart(CHECK_ALL));

        assertSame(state, state.fetchObserved(false)); // not seen running yet
        state = state.fetchObserved(true);
        assertEquals(Climb.RUNNING, state.climb);
        int before = state.loadSerial();
        state = state.fetchObserved(false);
        assertEquals(Climb.NONE, state.climb);
        assertEquals(before + 1, state.loadSerial());
        assertFalse(shown(state, CHECK_ALL).pending);
    }

    @Test
    public void failedOrUnstartedClimbClears() {
        CatalogPickerState failed = loaded(A).started(CHECK_ALL).finished(CHECK_ALL, false);
        assertEquals(Climb.NONE, failed.climb);

        CatalogPickerState idle = loaded(A).started(CHECK_ALL).finished(CHECK_ALL, true)
                .climbNeverStarted();
        assertEquals(Climb.NONE, idle.climb);
        CatalogPickerState running = loaded(A).started(CHECK_ALL).fetchObserved(true);
        assertSame(running, running.climbNeverStarted());
    }

    @Test
    public void nothingStartsBeforeRowsLoadOrOnAMissingRow() {
        CatalogPickerState empty = CatalogPickerState.initial(A);
        assertFalse(empty.canStart(LRC));
        assertSame(empty, empty.started(LRC));
        assertSame(empty, empty.armed(DELETE));
        CatalogPickerState state = loaded(A);
        assertSame(state, state.started("SOURCE:qq"));
    }
}
