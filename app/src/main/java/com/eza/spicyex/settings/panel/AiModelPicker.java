package com.eza.spicyex.settings.panel;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.eza.spicyex.lyrics.ai.AiModelDescriptor;
import com.eza.spicyex.ui.PanelDialog;
import com.eza.spicyex.ui.PickerSearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The model chooser: a pinned search field over the current model and the discovered list.
 *
 * <p>It opens before discovery returns, so the current model and the typed-id path are usable at
 * once, and the list fills in place. A compatible proxy can list a hundred ids from several
 * upstreams and can omit one it still serves, so search covers the id and its upstream owner, and
 * any typed id can be used as is.
 */
final class AiModelPicker {
    private final PanelDialog dialog;
    private final EditText search;
    private final TextView status;
    private final View typedRow;
    private final String current;
    private final Consumer<String> onPick;
    private final String typedFormat;
    /** Model id to its row and searchable owner; insertion order is display order. */
    private final Map<String, View> rows = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();
    private String typed = "";

    AiModelPicker(Context context, String title, String searchHint, String typedFormat,
                  String closeLabel, String current, Consumer<String> onPick) {
        this.current = current == null ? "" : current;
        this.onPick = onPick;
        this.typedFormat = typedFormat;
        dialog = new PanelDialog(context, title).closeIcon(closeLabel).fillHeight();
        search = dialog.pinnedSearch(searchHint);
        status = new TextView(context);
        status.setTextSize(13f);
        status.setTextColor(PanelDialog.COL_SUMMARY);
        status.setVisibility(View.GONE);
        dialog.add(status);
        for (String id : AiModelChoices.ordered(null, this.current)) addRow(id, "");
        typedRow = dialog.choiceRow("", "", false, () -> pick(typed));
        typedRow.setVisibility(View.GONE);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        // Enter takes the only remaining choice; with several left it only closes the keyboard.
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            View only = onlyVisibleChoice();
            if (only == null) return false;
            only.performClick();
            return true;
        });
    }

    void show(Runnable onDismiss) {
        dialog.onDismiss(onDismiss);
        dialog.show();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** Status line above the list; empty hides it. */
    void status(String text) {
        status.setText(text == null ? "" : text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void models(List<AiModelDescriptor> models) {
        List<String> ids = new ArrayList<>();
        Map<String, String> owner = new LinkedHashMap<>();
        for (AiModelDescriptor model : models) {
            ids.add(model.name);
            owner.put(model.name, model.owner);
        }
        for (String id : AiModelChoices.ordered(ids, current)) {
            String detail = owner.containsKey(id) ? owner.get(id) : "";
            if (rows.containsKey(id)) {
                owners.put(id, detail);
                PanelDialog.setChoiceLines(rows.get(id), null, detail);
            } else {
                addRow(id, detail);
            }
        }
        // The typed-id row stays last: a search word should list real matches before it.
        android.view.ViewGroup list = (android.view.ViewGroup) typedRow.getParent();
        list.removeView(typedRow);
        list.addView(typedRow);
        filter();
    }

    private void addRow(String id, String owner) {
        rows.put(id, dialog.choiceRow(id, owner, id.equals(current), () -> pick(id)));
        owners.put(id, owner);
    }

    private void filter() {
        String query = search.getText().toString();
        typed = AiModelChoices.typedChoice(query, rows.keySet());
        typedRow.setVisibility(typed.isEmpty() ? View.GONE : View.VISIBLE);
        if (!typed.isEmpty()) {
            PanelDialog.setChoiceLines(typedRow, String.format(typedFormat, typed), "");
        }
        for (Map.Entry<String, View> row : rows.entrySet()) {
            boolean shown = PickerSearch.matches(query, row.getKey(), owners.get(row.getKey()));
            row.getValue().setVisibility(shown ? View.VISIBLE : View.GONE);
        }
    }

    private View onlyVisibleChoice() {
        View only = typedRow.getVisibility() == View.VISIBLE ? typedRow : null;
        for (View row : rows.values()) {
            if (row.getVisibility() != View.VISIBLE) continue;
            if (only != null) return null;
            only = row;
        }
        return only;
    }

    private void pick(String id) {
        if (id == null || id.isEmpty() || onPick == null) return;
        onPick.accept(id);
    }
}
