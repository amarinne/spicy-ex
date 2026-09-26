package com.eza.spicyex.hooks;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;
import com.eza.spicyex.xposed.XpLog;

import java.util.Collections;
import java.util.List;

/**
 * Source picker in the panel's visual language. The dialog only renders
 * {@link CatalogPickerModel} rows and routes taps back through {@link LyricsHost}: every
 * selection change flows through the shared session, so now playing updates together with
 * fullscreen. Selecting stored data makes zero requests; tapping an unchecked source checks it.
 *
 * <p>Public so Settings can open the same picker scoped to the current song.
 */
public final class LyricsSourcePickerDialog {
    private static final String TAG = "[SpotifyPlusSourcePicker]";

    /** Where short confirmations land (the fullscreen status line). */
    public interface StatusSink {
        void show(String message);
    }

    private LyricsSourcePickerDialog() {
    }

    public static void show(Activity activity, LyricsHost host, SettingsUiStrings strings,
                            StatusSink status) {
        if (activity == null || host == null) return;
        try {
            host.loadCatalogPickerRows(rows -> showRows(activity, host, strings, status, rows,
                    false));
        } catch (Throwable t) {
            XpLog.log(TAG + " picker load failed: " + t);
        }
    }

    private static void showRows(Activity activity, LyricsHost host, SettingsUiStrings strings,
                                 StatusSink status,
                                 List<CatalogPickerModel.Row> rows, boolean deleteArmed) {
        try {
            List<CatalogPickerModel.Row> safe =
                    rows == null ? Collections.emptyList() : rows;
            PanelDialog dialog = new PanelDialog(activity,
                    text(strings, "source_picker_title", "Choose lyrics source"));
            final boolean[] armed = {deleteArmed};
            for (CatalogPickerModel.Row row : safe) {
                CatalogPickerModel.Row shown = row;
                if (deleteArmed && row.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
                    shown = row.withTitle(text(strings, "source_picker_confirm_delete",
                            "Tap again to delete"));
                }
                LinearLayout view = rowView(activity, shown);
                bindRow(activity, host, strings, status, dialog, safe, shown, armed, view);
                dialog.add(view);
            }
            dialog.show();
            XpLog.log(TAG + " picker opened rows=" + safe.size());
        } catch (Throwable t) {
            XpLog.log(TAG + " picker show failed: " + t);
        }
    }

    private static LinearLayout rowView(Activity activity, CatalogPickerModel.Row row) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, Math.round(10 * density), pad, Math.round(10 * density));
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.rightMargin = Math.round(8 * density);

        TextView title = new TextView(activity);
        title.setText((row.selected ? "✓ " : "") + row.title);
        title.setTextColor(row.selected ? PanelDialog.COL_ACCENT : PanelDialog.COL_TITLE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText(row.subtitle);
        subtitle.setTextColor(PanelDialog.COL_SUMMARY);
        subtitle.setTextSize(13);
        text.addView(subtitle);

        root.addView(text, textParams);
        ImageView trailing = trailingIcon(activity, row, density);
        if (trailing != null) root.addView(trailing);
        root.setContentDescription(row.title + ", " + row.subtitle
                + (row.selected ? ", selected" : ""));
        root.setTag(row);
        return root;
    }

    /**
     * Right-aligned status glyph. Stored sources show a green circle-tick, fetched-but-empty
     * ones an X, and the delete action a trash icon; everything else has no glyph so every
     * row's text starts at the same inset.
     */
    private static ImageView trailingIcon(Activity activity, CatalogPickerModel.Row row,
                                          float density) {
        ActionIconDrawable.Kind kind;
        int color;
        if (row.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
            kind = ActionIconDrawable.Kind.DELETE;
            color = PanelDialog.COL_SUMMARY;
        } else if (row.kind == CatalogPickerModel.RowKind.SOURCE
                && row.mark != CatalogPickerModel.DataMark.NONE) {
            boolean have = row.mark == CatalogPickerModel.DataMark.HAVE;
            kind = have ? ActionIconDrawable.Kind.CIRCLE_CHECK
                    : ActionIconDrawable.Kind.CLOSE;
            color = have ? PanelDialog.COL_ACCENT : PanelDialog.COL_SUMMARY;
        } else {
            return null;
        }
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ActionIconDrawable(kind, color, density));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int size = Math.round(24 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = Math.round(8 * density);
        icon.setLayoutParams(params);
        return icon;
    }

    private static void bindRow(Activity activity, LyricsHost host, SettingsUiStrings strings,
                                StatusSink status, PanelDialog dialog,
                                List<CatalogPickerModel.Row> rows,
                                CatalogPickerModel.Row row, boolean[] armed,
                                LinearLayout view) {
        if (view == null) return;
        view.setOnClickListener(v ->
                onRowTap(activity, host, strings, status, dialog, rows, row, armed, view));
        if (row.kind == CatalogPickerModel.RowKind.SOURCE && row.stored) {
            view.setOnLongClickListener(v -> {
                onRowLongPress(activity, host, strings, status, dialog, row);
                return true;
            });
        }
    }

    private static void onRowTap(Activity activity, LyricsHost host, SettingsUiStrings strings,
                                 StatusSink status, PanelDialog dialog,
                                 List<CatalogPickerModel.Row> rows,
                                 CatalogPickerModel.Row row, boolean[] armed, View tapped) {
        try {
            switch (row.kind) {
                case AUTO:
                    begin(tapped, dialog, activity, host, strings, status, true,
                            text(strings, "source_picker_selected", "Source selected"),
                            callback -> host.resetCatalogToAuto(callback));
                    break;
                case SOURCE:
                    if (row.stored && !row.candidateId.isEmpty()) {
                        begin(tapped, dialog, activity, host, strings, status, true,
                                text(strings, "source_picker_selected", "Source selected"),
                                callback -> host.selectCatalogCandidate(row.candidateId, callback));
                    } else if (row.checkable && row.sourceId != null) {
                        show(status, text(strings, "source_picker_checking", "Checking")
                                + " " + row.title);
                        begin(tapped, dialog, activity, host, strings, status, true,
                                text(strings, "source_picker_checked", "Source checked"),
                                callback -> host.refreshCatalogSource(row.sourceId, callback));
                    }
                    break;
                case ACTION_CHECK_ALL:
                    show(status, text(strings, "source_picker_checking", "Checking")
                            + " every source in order");
                    begin(tapped, dialog, activity, host, strings, status, true,
                            text(strings, "source_picker_checked", "Source checked"),
                            callback -> host.refreshAllCatalogSourcesInOrder(callback));
                    break;
                case ACTION_DELETE_TRACK:
                    if (!armed[0]) {
                        armed[0] = true;
                        dialog.dismissThen(() -> showRows(
                                activity, host, strings, status, rows, true));
                        return;
                    }
                    begin(tapped, dialog, activity, host, strings, status, true,
                            text(strings, "source_picker_deleted", "Deleted saved lyrics"),
                            host::deleteCatalogTrack);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " picker action failed: " + t);
        }
    }

    private interface ActionStart {
        void run(LyricsHost.CatalogActionCallback callback);
    }

    /** Keeps the dialog present until the asynchronous session command reports an outcome. */
    private static void begin(View tapped, PanelDialog dialog, Activity activity, LyricsHost host,
                              SettingsUiStrings strings, StatusSink status, boolean reopen,
                              String successMessage, ActionStart action) {
        if (tapped != null && !tapped.isEnabled()) return;
        if (tapped != null) {
            tapped.setEnabled(false);
            tapped.setAlpha(0.5f);
        }
        try {
            action.run((success, detail) -> {
                if (!success) {
                    restore(tapped);
                    show(status, detail == null || detail.isEmpty()
                            ? text(strings, "source_picker_failed", "Could not update source")
                            : detail);
                    // Provider state is persisted off the callback thread. Rebuild rows for actions
                    // whose result is visible in the picker so a failed check cannot leave a stale
                    // "Not checked" label in the dialog that initiated it.
                    if (reopen) {
                        dialog.dismissThen(() -> show(activity, host, strings, status));
                    }
                    return;
                }
                show(status, successMessage);
                dialog.dismissThen(reopen ? () -> show(activity, host, strings, status) : null);
            });
        } catch (Throwable error) {
            restore(tapped);
            show(status, error.getMessage());
            XpLog.log(TAG + " picker action failed: " + error);
        }
    }

    private static void restore(View tapped) {
        if (tapped == null) return;
        tapped.setEnabled(true);
        tapped.setAlpha(1f);
    }

    private static void onRowLongPress(Activity activity, LyricsHost host,
                                       SettingsUiStrings strings, StatusSink status,
                                       PanelDialog dialog, CatalogPickerModel.Row row) {
        try {
            PanelDialog menu = new PanelDialog(activity, row.title);
            LinearLayout reject = menuRow(activity, "Reject wrong match");
            reject.setOnClickListener(v -> {
                try {
                    host.rejectCatalogCandidate(row.candidateId, (success, detail) -> {
                        show(status, success
                                ? text(strings, "source_picker_rejected", "Rejected match")
                                : detail);
                        if (success) menu.dismissThen(() -> dialog.dismissThen(
                                () -> show(activity, host, strings, status)));
                    });
                } catch (Throwable t) {
                    XpLog.log(TAG + " picker hold action failed: " + t);
                }
            });
            LinearLayout remove = menuRow(activity, "Remove saved candidate");
            remove.setOnClickListener(v -> {
                try {
                    host.removeCatalogCandidate(row.candidateId, (success, detail) -> {
                        show(status, success ? text(strings, "source_picker_removed",
                                "Removed saved candidate") : detail);
                        if (success) menu.dismissThen(() -> dialog.dismissThen(
                                () -> show(activity, host, strings, status)));
                    });
                } catch (Throwable t) {
                    XpLog.log(TAG + " picker hold action failed: " + t);
                }
            });
            menu.add(reject);
            menu.add(remove);
            menu.show();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker hold menu failed: " + t);
        }
    }

    private static LinearLayout menuRow(Activity activity, String label) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, Math.round(10 * density), pad, Math.round(10 * density));
        row.setClickable(true);
        row.setFocusable(true);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(PanelDialog.COL_TITLE);
        title.setTextSize(16);
        row.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(label);
        return row;
    }

    private static String text(SettingsUiStrings strings, String name, String fallback) {
        try {
            return strings == null ? fallback : strings.get(name, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void show(StatusSink status, String message) {
        if (status == null) return;
        try {
            status.show(message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }
}
