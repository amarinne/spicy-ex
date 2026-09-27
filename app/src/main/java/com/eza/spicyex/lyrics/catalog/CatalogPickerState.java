package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.Row;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel.RowKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The source picker's single owner of state. The dialog routes every event through one of these
 * transitions and renders {@link #project}; no row view decides its own text, and no action
 * closes and reopens the dialog to show its outcome.
 *
 * <p>Rows are {@link CatalogPickerModel} output, unchanged. This class only overlays what the
 * model cannot know: which rows have an action in flight, which row is armed for a destructive
 * second tap, and whether an ordered climb is still running. Immutable and pure so every
 * transition is unit tested; the Android dialog lives in {@code hooks}.
 *
 * <p>Row loads are serial-stamped. A load requested before an action finished can land after it,
 * so a row stays pending until a load requested at or after its finish arrives. Loads for another
 * track or older than the last applied load are dropped.
 */
public final class CatalogPickerState {
    /** Ordered climb progress: requested by the picker, observed running, then settled. */
    public enum Climb {
        NONE,
        REQUESTED,
        RUNNING
    }

    /** One rendered row: display text already overlaid, plus the model row actions bind to. */
    public static final class Shown {
        public final String key;
        public final Row row;
        public final Row source;
        public final boolean pending;
        public final boolean armed;

        Shown(String key, Row row, Row source, boolean pending, boolean armed) {
            this.key = key;
            this.row = row;
            this.source = source;
            this.pending = pending;
            this.armed = armed;
        }
    }

    public final String trackUri;
    public final List<Row> rows;
    public final String armedKey;
    public final Climb climb;
    private final Set<String> pending;
    /** Finished actions awaiting a load at or after the mapped serial. */
    private final Map<String, Integer> settling;
    private final int armSerial;
    private final int requestedLoad;
    private final int appliedLoad;

    private CatalogPickerState(String trackUri, List<Row> rows, String armedKey, Climb climb,
                               Set<String> pending, Map<String, Integer> settling, int armSerial,
                               int requestedLoad, int appliedLoad) {
        this.trackUri = trackUri;
        this.rows = rows;
        this.armedKey = armedKey;
        this.climb = climb;
        this.pending = pending;
        this.settling = settling;
        this.armSerial = armSerial;
        this.requestedLoad = requestedLoad;
        this.appliedLoad = appliedLoad;
    }

    /** Empty picker for the session's current track; rows arrive through {@link #withRows}. */
    public static CatalogPickerState initial(String trackUri) {
        return new CatalogPickerState(safe(trackUri), Collections.<Row>emptyList(), "",
                Climb.NONE, Collections.<String>emptySet(),
                Collections.<String, Integer>emptyMap(), 0, 0, 0);
    }

    /** Stable identity across reloads: kind, plus the provider for source rows. */
    public static String key(Row row) {
        if (row == null) return "";
        if (row.kind == RowKind.SOURCE) {
            return row.kind.name() + ":" + (row.sourceId == null ? "" : row.sourceId.id);
        }
        return row.kind.name();
    }

    /** Serial to stamp the next row load with; changes only through {@link #requestLoad}. */
    public int loadSerial() {
        return requestedLoad;
    }

    /** Serial of the current arm; a delayed expiry passes it back to {@link #armExpired}. */
    public int armSerial() {
        return armSerial;
    }

    public CatalogPickerState requestLoad() {
        return copy(trackUri, rows, armedKey, climb, pending, settling, armSerial,
                requestedLoad + 1, appliedLoad);
    }

    /** Applies a row load for this track; stale tracks and superseded serials are dropped. */
    public CatalogPickerState withRows(String uri, int serial, List<Row> loaded) {
        if (!trackUri.equals(safe(uri)) || serial <= appliedLoad || serial > requestedLoad) {
            return this;
        }
        List<Row> next = loaded == null ? Collections.<Row>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(loaded));
        Map<String, Integer> stillSettling = new HashMap<>();
        for (Map.Entry<String, Integer> entry : settling.entrySet()) {
            if (entry.getValue() > serial) stillSettling.put(entry.getKey(), entry.getValue());
        }
        String armed = hasRow(next, armedKey) ? armedKey : "";
        return copy(trackUri, next, armed, climb, pending, stillSettling, armSerial,
                requestedLoad, serial);
    }

    /**
     * The session moved to another track. Everything shown belonged to the old one, so rows,
     * pending work, an armed delete, and the climb all drop; the caller loads fresh rows.
     */
    public CatalogPickerState trackChanged(String uri) {
        String next = safe(uri);
        if (next.equals(trackUri)) return this;
        return new CatalogPickerState(next, Collections.<Row>emptyList(), "", Climb.NONE,
                Collections.<String>emptySet(), Collections.<String, Integer>emptyMap(),
                armSerial + 1, requestedLoad, appliedLoad);
    }

    /** True when a tap on {@code key} may start a session command now. */
    public boolean canStart(String key) {
        if (!hasRow(rows, key) || isBusy(key)) return false;
        return !isCheckAll(key) || climb == Climb.NONE;
    }

    /** A command started for {@code key}. Starting anything disarms a pending delete. */
    public CatalogPickerState started(String key) {
        if (!canStart(key)) return this;
        Set<String> nextPending = new HashSet<>(pending);
        nextPending.add(key);
        Map<String, Integer> nextSettling = new HashMap<>(settling);
        nextSettling.remove(key);
        return copy(trackUri, rows, "", isCheckAll(key) ? Climb.REQUESTED : climb,
                nextPending, nextSettling, armSerial, requestedLoad, appliedLoad);
    }

    /**
     * The command for {@code key} reported back. The row stays pending until a load requested now
     * lands, so its new persisted state replaces the pending text without an intermediate flash
     * of the old one. A failed check-all never started a climb, so the climb clears.
     */
    public CatalogPickerState finished(String key, boolean success) {
        if (!pending.contains(key)) return this;
        Set<String> nextPending = new HashSet<>(pending);
        nextPending.remove(key);
        int load = requestedLoad + 1;
        Map<String, Integer> nextSettling = new HashMap<>(settling);
        nextSettling.put(key, load);
        Climb nextClimb = isCheckAll(key) && !success ? Climb.NONE : climb;
        return copy(trackUri, rows, armedKey, nextClimb, nextPending, nextSettling, armSerial,
                load, appliedLoad);
    }

    /**
     * Session fetch activity, sampled on every session tick. A requested climb is running once a
     * fetch is seen; it has settled when that fetch ends, which requests a reload.
     */
    public CatalogPickerState fetchObserved(boolean inFlight) {
        if (climb == Climb.REQUESTED && inFlight) {
            return copy(trackUri, rows, armedKey, Climb.RUNNING, pending, settling, armSerial,
                    requestedLoad, appliedLoad);
        }
        if (climb == Climb.RUNNING && !inFlight) {
            return copy(trackUri, rows, armedKey, Climb.NONE, pending, settling, armSerial,
                    requestedLoad + 1, appliedLoad);
        }
        return this;
    }

    /** The session declined to fetch after a check-all request (nothing was due). */
    public CatalogPickerState climbNeverStarted() {
        if (climb != Climb.REQUESTED) return this;
        return copy(trackUri, rows, armedKey, Climb.NONE, pending, settling, armSerial,
                requestedLoad + 1, appliedLoad);
    }

    /** First tap of a destructive action. Arms only a present, idle row. */
    public CatalogPickerState armed(String key) {
        if (!hasRow(rows, key) || isBusy(key)) return this;
        return copy(trackUri, rows, key, climb, pending, settling, armSerial + 1, requestedLoad,
                appliedLoad);
    }

    public CatalogPickerState disarmed() {
        if (armedKey.isEmpty()) return this;
        return copy(trackUri, rows, "", climb, pending, settling, armSerial, requestedLoad,
                appliedLoad);
    }

    /** Timed expiry of one specific arm; a later re-arm is not affected. */
    public CatalogPickerState armExpired(int serial) {
        return serial == armSerial ? disarmed() : this;
    }

    /** Display rows: pending rows read {@code checkingLabel}, the armed row {@code confirmLabel}. */
    public List<Shown> project(String checkingLabel, String confirmLabel) {
        List<Shown> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            String key = key(row);
            boolean busy = isBusy(key) || (isCheckAll(key) && climb != Climb.NONE);
            boolean armed = key.equals(armedKey);
            Row shown = row;
            if (armed) shown = shown.withTitle(confirmLabel);
            if (busy) shown = shown.withSubtitle(checkingLabel);
            out.add(new Shown(key, shown, row, busy, armed));
        }
        return Collections.unmodifiableList(out);
    }

    private boolean isBusy(String key) {
        return pending.contains(key) || settling.containsKey(key);
    }

    private static boolean isCheckAll(String key) {
        return RowKind.ACTION_CHECK_ALL.name().equals(key);
    }

    private static boolean hasRow(List<Row> rows, String key) {
        if (key == null || key.isEmpty()) return false;
        for (Row row : rows) {
            if (key.equals(key(row))) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static CatalogPickerState copy(String trackUri, List<Row> rows, String armedKey,
                                           Climb climb, Set<String> pending,
                                           Map<String, Integer> settling, int armSerial,
                                           int requestedLoad, int appliedLoad) {
        return new CatalogPickerState(trackUri, rows, armedKey, climb,
                Collections.unmodifiableSet(new HashSet<>(pending)),
                Collections.unmodifiableMap(new HashMap<>(settling)), armSerial, requestedLoad,
                appliedLoad);
    }
}
