package com.eza.spicyex.hooks;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel;
import com.eza.spicyex.lyrics.catalog.CatalogPickerState;
import com.eza.spicyex.settings.RowSyncPlan;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;
import com.eza.spicyex.xposed.XpLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source picker in the panel's visual language. One {@link CatalogPickerState} owns what the picker
 * shows; every tap, load, and session tick becomes a transition on it, and the rows are patched in
 * place from its projection. The dialog is therefore never closed to show an outcome: a delete
 * confirmation, a pending check, and a loaded result all land on the rows that are already there.
 *
 * <p>Rows are still {@link CatalogPickerModel} output and every action still routes through
 * {@link LyricsHost}, so a selection change flows through the shared session and now playing
 * updates together with fullscreen. Selecting stored data makes zero requests; tapping an unchecked
 * source checks it.
 *
 * <p>Public so Settings can open the same picker scoped to the current song.
 */
public final class LyricsSourcePickerDialog implements LyricsSessionManager.Listener {
    private static final String TAG = "[SpotifyPlusSourcePicker]";
    /** How long a delete stays armed before it reverts on its own. */
    private static final long ARM_EXPIRY_MS = 4000L;
    /** A climb that shows no fetch within this window is treated as never started. */
    private static final long CLIMB_SETTLE_MS = 3000L;
    private static final String KEY_CHECK_ALL = CatalogPickerModel.RowKind.ACTION_CHECK_ALL.name();

    /** Where short confirmations land (the fullscreen status line). */
    public interface StatusSink {
        void show(String message);
    }

    /** One session command, run with the callback that reports its outcome. */
    private interface ActionStart {
        void run(LyricsHost.CatalogActionCallback callback);
    }

    private final Activity activity;
    private final LyricsHost host;
    private final SettingsUiStrings strings;
    private final StatusSink status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Mounted row views by state key, in mount order; the sync plan reads it as current keys. */
    private final Map<String, RowView> mounted = new LinkedHashMap<>();

    private PanelDialog dialog;
    private LinearLayout rows;
    private CatalogPickerState state;
    private LyricsSessionManager.SessionSubscription subscription;
    private LyricsSessionManager.PollingDemandLease lease;
    private boolean closed;
    private LyricsHost.CatalogActionCallback opening;
    /** The last session tick seen; ticks arrive every 200 ms, so only a change reloads. */
    private boolean tickPrimed;
    private String tickTrackUri = "";
    private int tickGeneration;
    private String tickStatus = "";
    private boolean tickFetchInFlight;

    private LyricsSourcePickerDialog(Activity activity, LyricsHost host, SettingsUiStrings strings,
                                     StatusSink status) {
        this.activity = activity;
        this.host = host;
        this.strings = strings;
        this.status = status;
    }

    public static void show(Activity activity, LyricsHost host, SettingsUiStrings strings,
                            StatusSink status) {
        if (activity == null || host == null) return;
        new LyricsSourcePickerDialog(activity, host, strings, status).open();
    }

    static LyricsSourcePickerDialog showForAgent(Activity activity, LyricsHost host,
            SettingsUiStrings strings, LyricsHost.CatalogActionCallback opening) {
        LyricsSourcePickerDialog picker = new LyricsSourcePickerDialog(activity, host, strings,
                message -> { });
        picker.opening = opening;
        picker.open();
        return picker;
    }

    boolean isOpen() {
        return !closed && dialog != null;
    }

    boolean isShowing() {
        return isOpen() && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        close();
    }

    private void completeOpening(boolean success, String detail) {
        LyricsHost.CatalogActionCallback callback = opening;
        opening = null;
        if (callback != null) callback.onComplete(success, detail);
    }

    // --- Lifecycle ---

    /** Opens the picker for the session's current track; no track means nothing to choose. */
    private void open() {
        try {
            String uri = host.catalogTrackUri();
            if (uri == null || uri.isEmpty()) {
                completeOpening(false, "No current track");
                close();
                return;
            }
            state = CatalogPickerState.initial(uri);
            dialog = new PanelDialog(activity,
                    text(strings, "source_picker_title", "Choose lyrics source"));
            rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            dialog.add(rows);
            // Each row carries the panel's 8 dp gap; the container must not add a second one.
            ((LinearLayout.LayoutParams) rows.getLayoutParams()).bottomMargin = 0;
            dialog.onDismiss(this::close);
            reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker show failed: " + t);
            close();
        }
    }

    /** The picker is showing; ticks are what keep its rows honest, and the lease makes them come. */
    private void present() {
        dialog.show();
        completeOpening(true, "opened track=" + state.trackUri);
        XpLog.log(TAG + " picker opened rows=" + rows.getChildCount());
        try {
            subscription = host.subscribeLyricsSession(this);
            lease = host.acquireLyricsPollingDemand();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker watch failed: " + t);
        }
    }

    /** The user closed the picker, so nothing keeps ticking or loading on its behalf. */
    private void close() {
        completeOpening(false, "picker closed before opening");
        closed = true;
        handler.removeCallbacksAndMessages(null);
        release(subscription);
        release(lease);
        subscription = null;
        lease = null;
    }

    private static void release(AutoCloseable held) {
        if (held == null) return;
        try {
            held.close();
        } catch (Throwable ignored) {
        }
    }

    // --- Rows ---

    /** Asks for a fresh set of rows: a new serial, then the load that carries it. */
    private void reload() {
        if (closed) return;
        dispatch(state.requestLoad());
        load();
    }

    /** Fetches the rows the current serial asked for. The reducer drops stale and superseded ones. */
    private void load() {
        if (closed) return;
        final int serial = state.loadSerial();
        try {
            host.loadCatalogPickerRows((uri, loaded) -> {
                if (closed) return;
                try {
                    dispatch(state.withRows(uri, serial, loaded));
                } catch (Throwable t) {
                    XpLog.log(TAG + " picker render failed: " + t);
                    completeOpening(false, "picker render failed");
                    dismiss();
                }
            });
        } catch (Throwable t) {
            XpLog.log(TAG + " picker load failed: " + t);
            completeOpening(false, "picker load failed");
            dismiss();
        }
    }

    /**
     * Fetches rows for a serial a transition already requested. A tick that only observed fetch
     * activity raises no serial, so nothing is asked for there.
     */
    private void loadIfSerialRose(int before) {
        if (!closed && state.loadSerial() > before) load();
    }

    private void dispatch(CatalogPickerState next) {
        if (closed || next == state) return;
        state = next;
        render();
    }

    // --- Session ---

    @Override
    public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
        try {
            if (closed || snapshot == null) return;
            String uri = snapshot.trackUri == null ? "" : snapshot.trackUri;
            boolean inFlight = host.catalogFetchInFlight();
            // The first tick only records the baseline: the opening load already read this state.
            boolean tickChanged = tickPrimed
                    && (!uri.equals(tickTrackUri) || snapshot.generation != tickGeneration
                    || !same(snapshot.status, tickStatus) || inFlight != tickFetchInFlight);
            tickPrimed = true;
            tickTrackUri = uri;
            tickGeneration = snapshot.generation;
            tickStatus = snapshot.status;
            tickFetchInFlight = inFlight;
            if (!uri.equals(state.trackUri)) {
                dispatch(state.trackChanged(uri));
                reload();
                return;
            }
            int before = state.loadSerial();
            dispatch(state.fetchObserved(inFlight));
            loadIfSerialRose(before);
            if (tickChanged) reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker session tick failed: " + t);
        }
    }

    @Override
    public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                  LyricsDocument document) {
        try {
            reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker document change failed: " + t);
        }
    }

    // --- Rendering ---

    /** Keyed sync: stale views out, mounted views patched, missing ones built, order projected. */
    private void render() {
        if (closed || dialog == null || rows == null) return;
        List<CatalogPickerState.Shown> shown = state.project(checkingLabel(), confirmLabel());
        Map<String, CatalogPickerState.Shown> byKey = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(shown.size());
        for (CatalogPickerState.Shown row : shown) {
            byKey.put(row.key, row);
            keys.add(row.key);
        }
        RowSyncPlan plan = RowSyncPlan.of(keys, new ArrayList<>(mounted.keySet()));
        for (String dead : plan.removals) {
            RowView stale = mounted.remove(dead);
            if (stale != null) rows.removeView(stale.root);
        }
        for (int index = 0; index < plan.order.size(); index++) {
            String key = plan.order.get(index);
            CatalogPickerState.Shown row = byKey.get(key);
            if (row == null) continue;
            RowView view = mounted.get(key);
            if (view == null) {
                view = new RowView(key);
                mounted.put(key, view);
            }
            patch(view, row);
            // Move only a misplaced row: detaching drops accessibility focus and touch state.
            if (rows.indexOfChild(view.root) != index) {
                rows.removeView(view.root);
                rows.addView(view.root, Math.min(index, rows.getChildCount()), view.params);
            }
        }
        if (!dialog.isShowing() && !shown.isEmpty()) present();
        // Subtitles change height as rows move between states, so the card refits every render.
        if (dialog.isShowing()) dialog.refit();
    }

    private void patch(RowView view, CatalogPickerState.Shown shown) {
        CatalogPickerModel.Row row = shown.row;
        view.title.setText((row.selected ? "✓ " : "") + row.title);
        view.title.setTextColor(row.selected ? PanelDialog.COL_ACCENT : PanelDialog.COL_TITLE);
        view.subtitle.setText(row.subtitle);
        patchTrailing(view, row);
        view.root.setContentDescription(row.title + ", " + row.subtitle
                + (row.selected ? ", selected" : ""));
        view.root.setEnabled(!shown.pending);
        view.root.setAlpha(shown.pending ? 0.5f : 1f);
        // Holding a row is only meaningful for a stored candidate; every other row clears the menu.
        boolean holdable = shown.source.kind == CatalogPickerModel.RowKind.SOURCE
                && shown.source.stored;
        view.root.setOnLongClickListener(holdable ? v -> openMenu(shown.source) : null);
        view.root.setLongClickable(holdable);
    }

    /**
     * Right-aligned status glyph, created only for a row that needs one. Stored sources show a
     * green circle-tick, fetched-but-empty ones an X, and the delete action a trash icon; rows
     * with no outcome have none, so every row's text starts at the same inset.
     */
    private void patchTrailing(RowView view, CatalogPickerModel.Row row) {
        ActionIconDrawable.Kind kind;
        int color;
        if (row.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
            kind = ActionIconDrawable.Kind.DELETE;
            color = PanelDialog.COL_SUMMARY;
        } else if (row.kind == CatalogPickerModel.RowKind.SOURCE
                && row.mark != CatalogPickerModel.DataMark.NONE) {
            boolean have = row.mark == CatalogPickerModel.DataMark.HAVE;
            kind = have ? ActionIconDrawable.Kind.CIRCLE_CHECK : ActionIconDrawable.Kind.CLOSE;
            color = have ? PanelDialog.COL_ACCENT : PanelDialog.COL_SUMMARY;
        } else {
            if (view.trailing != null) {
                view.root.removeView(view.trailing);
                view.trailing = null;
            }
            return;
        }
        if (view.trailing == null) {
            ImageView icon = new ImageView(activity);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconParams.leftMargin = dp(8);
            icon.setLayoutParams(iconParams);
            view.root.addView(icon);
            view.trailing = icon;
        }
        view.trailing.setImageDrawable(new ActionIconDrawable(kind, color, density()));
    }

    private String checkingLabel() {
        return text(strings, "source_picker_checking", "Checking") + "…";
    }

    private String confirmLabel() {
        return text(strings, "source_picker_confirm_delete", "Tap again to delete");
    }

    // --- Taps ---

    private void onTap(String key) {
        try {
            CatalogPickerState.Shown row = shown(key);
            if (row == null) return;
            if (row.source.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
                if (key.equals(state.armedKey)) {
                    run(key, host::deleteCatalogTrack,
                            text(strings, "source_picker_deleted", "Deleted saved lyrics"));
                    return;
                }
                CatalogPickerState armedState = state.armed(key);
                if (armedState == state) return;
                dispatch(armedState);
                final int armSerial = state.armSerial();
                handler.postDelayed(() -> dispatch(state.armExpired(armSerial)), ARM_EXPIRY_MS);
                return;
            }
            dispatch(state.disarmed());
            CatalogPickerModel.Row source = row.source;
            switch (source.kind) {
                case AUTO:
                    run(key, host::resetCatalogToAuto,
                            text(strings, "source_picker_selected", "Source selected"));
                    break;
                case SOURCE:
                    if (source.stored && !source.candidateId.isEmpty()) {
                        run(key, callback -> host.selectCatalogCandidate(source.candidateId,
                                callback), text(strings, "source_picker_selected",
                                "Source selected"));
                    } else if (source.checkable && source.sourceId != null) {
                        run(key, callback -> host.refreshCatalogSource(source.sourceId, callback),
                                text(strings, "source_picker_checked", "Source checked"));
                    }
                    break;
                case ACTION_CHECK_ALL:
                    run(key, host::refreshAllCatalogSourcesInOrder,
                            text(strings, "source_picker_checked", "Source checked"));
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " picker action failed: " + t);
        }
    }

    /**
     * Starts one session command for a row. The track a command acts on is the session's, so the
     * guard read and the call happen in one main-thread frame: a command can never be issued for
     * the track the rows belonged to after the user has already moved on.
     */
    private void run(String key, ActionStart action, String successMessage) {
        if (closed || !state.canStart(key)) return;
        try {
            String uri = host.catalogTrackUri();
            if (uri == null || !uri.equals(state.trackUri)) {
                dispatch(state.trackChanged(uri == null ? "" : uri));
                reload();
                status(text(strings, "source_picker_track_changed", "Track changed"));
                return;
            }
            dispatch(state.started(key));
            action.run(new LyricsHost.CatalogActionCallback() {
                @Override public void onProgress(String detail) {
                    // The ordered climb reports each source as it lands; reread rows so the
                    // picker stays live instead of waiting for the final outcome.
                    handler.post(() -> {
                        if (!closed) load();
                    });
                }

                @Override public void onComplete(boolean success, String detail) {
                    complete(key, success, detail, successMessage);
                }
            });
        } catch (Throwable error) {
            XpLog.log(TAG + " picker action failed: " + error);
            complete(key, false, error.getMessage(), successMessage);
        }
    }

    /** One command's outcome: report it, release the row, then read the store it wrote. */
    private void complete(String key, boolean success, String detail, String successMessage) {
        try {
            String failure = detail == null || detail.isEmpty()
                    ? text(strings, "source_picker_failed", "Could not update source") : detail;
            // The outcome is reported even after the picker closed; only the rows stop updating.
            status(success ? successMessage : failure);
            if (closed) return;
            dispatch(state.finished(key, success));
            load();
            if (success && isCheckAll(key)) {
                handler.postDelayed(this::settleClimb, CLIMB_SETTLE_MS);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " picker action callback failed: " + t);
        }
    }

    /** An ordered climb that never showed a fetch settled without one; clear it and reread rows. */
    private void settleClimb() {
        if (closed) return;
        int before = state.loadSerial();
        dispatch(state.climbNeverStarted());
        loadIfSerialRose(before);
    }

    private static boolean isCheckAll(String key) {
        return KEY_CHECK_ALL.equals(key);
    }

    private CatalogPickerState.Shown shown(String key) {
        if (key == null || key.isEmpty()) return null;
        for (CatalogPickerState.Shown row : state.project(checkingLabel(), confirmLabel())) {
            if (key.equals(row.key)) return row;
        }
        return null;
    }

    // --- Long press ---

    /** Per-candidate actions over one stored row; the picker itself stays open behind the menu. */
    private boolean openMenu(CatalogPickerModel.Row row) {
        try {
            final String key = CatalogPickerState.key(row);
            final String candidateId = row.candidateId;
            PanelDialog menu = new PanelDialog(activity, row.title);
            menu.add(menuRow(text(strings, "source_picker_reject_wrong_match",
                    "Reject wrong match"), () -> {
                menu.dismiss();
                run(key, callback -> host.rejectCatalogCandidate(candidateId, callback),
                        text(strings, "source_picker_rejected", "Rejected match"));
            }));
            menu.add(menuRow(text(strings, "source_picker_remove_saved_candidate",
                    "Remove saved candidate"), () -> {
                menu.dismiss();
                run(key, callback -> host.removeCatalogCandidate(candidateId, callback),
                        text(strings, "source_picker_removed", "Removed saved candidate"));
            }));
            menu.show();
            return true;
        } catch (Throwable t) {
            XpLog.log(TAG + " picker hold menu failed: " + t);
            return false;
        }
    }

    private LinearLayout menuRow(String label, Runnable action) {
        int pad = dp(12);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, dp(10), pad, dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(PanelDialog.COL_TITLE);
        title.setTextSize(16);
        row.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(label);
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        return row;
    }

    // --- Rows as views ---

    /** One mounted row; the views are kept so a rerender patches them instead of rebuilding. */
    private final class RowView {
        final LinearLayout root;
        final TextView title;
        final TextView subtitle;
        final LinearLayout.LayoutParams params;
        ImageView trailing;

        RowView(String key) {
            int pad = dp(12);
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(pad, dp(10), pad, dp(10));
            root.setClickable(true);
            root.setFocusable(true);
            // The listener captures the stable key only; the row it means is read on tap.
            root.setOnClickListener(v -> onTap(key));

            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.rightMargin = dp(8);

            title = new TextView(activity);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            labels.addView(title);

            subtitle = new TextView(activity);
            subtitle.setTextColor(PanelDialog.COL_SUMMARY);
            subtitle.setTextSize(13);
            labels.addView(subtitle);

            root.addView(labels, labelParams);
            params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
        }
    }

    // --- Shared helpers ---

    private int dp(int value) {
        return Math.round(value * density());
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String text(SettingsUiStrings strings, String name, String fallback) {
        try {
            return strings == null ? fallback : strings.get(name, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void status(String message) {
        if (status == null) return;
        try {
            status.show(message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }
}
