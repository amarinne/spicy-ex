package com.eza.spicyex.settings;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.AiSettingsRows;
import com.eza.spicyex.GlossyToggle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;

import java.util.List;

/**
 * Builds every ordinary settings row and patches one in place on rebind.
 *
 * <p>One renderer per {@link SettingUiSpec.RowKind}, so adding a setting of an existing kind
 * needs no new branch anywhere. Composite rows (source ordering, credentials, diagnostics) are
 * built by their own owners and never arrive here.
 *
 * <p>The factory owns no panel state. Values come from the store, labels from the host's
 * locale, and every user edit is handed back through {@link Host} so the panel keeps the single
 * decision about what to rebuild.
 */
public final class SettingRowFactory {
    /** What rows need from the panel: values, locale, and the callbacks that own rebuilds. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        PanelSnapshot snapshot();

        /** A value for this setting changed; the panel decides whether to rebuild a section. */
        void onSettingChanged(Settings.Setting<?> setting);

        /** Opens the option dialog for a selector row. */
        void openSelector(Settings.StringSetting setting, List<String> values, TextView valueView);

        String labelFor(Settings.StringSetting setting, String value);

        /** A small note under a switch (what turning it on would change), or null. */
        default String switchNote(Settings.BooleanSetting setting) {
            return null;
        }

        /** A selector row's summary for its stored value; the option's label unless the host
         *  knows better (a value another setting has overridden, for instance). */
        default String selectorSummary(Settings.StringSetting setting, String value) {
            return labelFor(setting, value);
        }

        boolean unavailable(Settings.Setting<?> setting);

        String unavailableSummary(Settings.Setting<?> setting);

        String cacheSizeSummary();

        String stepperSummary(Settings.IntegerSetting setting);
    }

    private final Host host;

    public SettingRowFactory(Host host) {
        this.host = host;
    }

    // --- Row construction ---

    public void switchRow(LinearLayout content, final Settings.BooleanSetting setting) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        boolean unavailable = host.unavailable(setting);
        style.titleColumn(row, host.strings().setting(setting),
                unavailable ? host.unavailableSummary(setting) : host.switchNote(setting));
        style.applyRowLead(row, setting.key);
        GlossyToggle toggle = new GlossyToggle(style.context());
        toggle.setAccent(PanelStyle.COL_ACCENT);
        toggle.setChecked(!unavailable && host.store().get(setting), false);
        toggle.setEnabled(!unavailable);
        row.setEnabled(!unavailable);
        row.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            toggle.setOnChangeListener(() -> {
                host.writer().put(setting, toggle.isChecked());
                host.onSettingChanged(setting);
            });
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
        }
        row.addView(toggle);
    }

    public void selectorRow(LinearLayout content, final Settings.StringSetting setting) {
        if (setting == Settings.CACHE_SIZE) {
            selectorRow(content, setting, Settings.CACHE_SIZE.allowedValues, host.cacheSizeSummary());
            return;
        }
        selectorRow(content, setting, setting.allowedValues, null);
    }

    public void selectorRow(LinearLayout content, final Settings.StringSetting setting,
                            final List<String> values, String summaryOverride) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        boolean unavailable = host.unavailable(setting);
        String summary = summaryOverride != null ? summaryOverride
                : host.selectorSummary(setting, host.store().get(setting));
        TextView value = style.titleColumn(row, host.strings().setting(setting),
                unavailable ? host.unavailableSummary(setting) : summary);
        style.applyRowLead(row, setting.key);
        if (!unavailable) value.setTextColor(PanelStyle.COL_ACCENT);
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
        row.setEnabled(!unavailable);
        if (!unavailable) row.setOnClickListener(v -> host.openSelector(setting, values, value));
    }

    public void stepperRow(LinearLayout content, final Settings.IntegerSetting setting) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        style.titleColumn(row, host.strings().setting(setting), host.stepperSummary(setting));

        LinearLayout controls = new LinearLayout(style.context());
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);

        View minus = stepButton(false, setting);
        TextView value = style.text(
                SettingLabels.formatStepper(setting, host.store().get(setting)),
                15, PanelStyle.COL_ACCENT, true);
        value.setTag(PanelTags.ROW_VALUE);
        value.setGravity(android.view.Gravity.CENTER);
        View plus = stepButton(true, setting);

        controls.addView(minus, new LinearLayout.LayoutParams(style.dp(36), style.dp(36)));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(style.dp(74), style.dp(36));
        valueLp.leftMargin = style.dp(4);
        valueLp.rightMargin = style.dp(4);
        controls.addView(value, valueLp);
        controls.addView(plus, new LinearLayout.LayoutParams(style.dp(36), style.dp(36)));
        row.addView(controls);

        final int[] pending = new int[]{host.store().get(setting)};
        final Runnable commit = () -> host.writer().put(setting, pending[0]);
        attachStepperTouch(minus, setting, value, -setting.stepValue, pending, commit);
        attachStepperTouch(plus, setting, value, setting.stepValue, pending, commit);
    }

    public void textFieldRow(LinearLayout content, final Settings.StringSetting setting) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.START);
        row.addView(style.text(host.strings().setting(setting), 14, PanelStyle.COL_SUMMARY, false));
        EditText field = new EditText(style.context());
        field.setText(host.store().get(setting));
        if (setting == Settings.SPICY_MANUAL_TOKEN) {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        field.setTextColor(PanelStyle.COL_TITLE);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PanelStyle.COL_SECTION));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                host.writer().put(setting, s.toString());
            }
        });
        row.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void actionRow(LinearLayout content, Kind lead, String label, View.OnClickListener listener) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        if (lead != null) row.addView(style.kindView(lead, PanelStyle.COL_ACCENT, 19), style.leadParams());
        row.addView(style.text(label, 16, PanelStyle.COL_ACCENT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
    }

    public void infoRow(LinearLayout content, String label, String value) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.addView(style.text(label, 14, PanelStyle.COL_SUMMARY, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = style.text(value == null ? "" : value, 14, PanelStyle.COL_TITLE, false);
        val.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        row.addView(val, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** AI-provided row: label + accent value, optional icon actions and a chevron. */
    public void aiFieldRow(LinearLayout content, String label, String value, boolean selector,
                           String rowKey, View.OnClickListener listener,
                           AiSettingsRows.IconAction... actions) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        if (rowKey != null) row.setTag(PanelTags.row(rowKey));
        TextView summary = style.titleColumn(row, label, value);
        summary.setTextColor(PanelStyle.COL_ACCENT);
        if (actions != null) {
            for (AiSettingsRows.IconAction action : actions) {
                if (action == null) continue;
                row.addView(style.iconButton(action));
            }
        }
        if (selector) {
            row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                    new LinearLayout.LayoutParams(style.dp(28), style.dp(36)));
        }
        row.setOnClickListener(listener);
    }

    // --- In-place patching ---

    /** Patches a reused ordinary row: value, availability copy, and interactive state. */
    public void patchRow(View row, Settings.Setting<?> setting, PanelSnapshot snapshot) {
        boolean unavailable = PanelPolicy.unavailable(setting, snapshot);
        SettingUiSpec.RowKind kind = SettingsUiSchema.specOf(setting).kind;
        if (kind == SettingUiSpec.RowKind.TEXT_FIELD && setting instanceof Settings.StringSetting) {
            EditText field = findEditText(row);
            if (field != null && !field.isFocused()) {
                String current = host.store().get((Settings.StringSetting) setting);
                if (!current.equals(field.getText().toString())) field.setText(current);
            }
        } else if (setting instanceof Settings.BooleanSetting
                && kind == SettingUiSpec.RowKind.TOGGLE) {
            TextView sub = row.findViewWithTag(PanelTags.ROW_SUMMARY);
            if (sub instanceof TextView) {
                String text = unavailable ? host.unavailableSummary(setting)
                        : host.switchNote((Settings.BooleanSetting) setting);
                sub.setText(text == null ? "" : text);
                sub.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
            }
            GlossyToggle toggle = findToggle(row);
            if (toggle != null) {
                toggle.setChecked(!unavailable && host.store().get((Settings.BooleanSetting) setting), false);
                toggle.setEnabled(!unavailable);
            }
            row.setEnabled(!unavailable);
            row.setAlpha(unavailable ? 0.48f : 1f);
        } else if (setting instanceof Settings.StringSetting) {
            TextView sub = row.findViewWithTag(PanelTags.ROW_SUMMARY);
            if (sub instanceof TextView) {
                Settings.StringSetting string = (Settings.StringSetting) setting;
                String summary = setting == Settings.CACHE_SIZE
                        ? host.cacheSizeSummary() : host.selectorSummary(string, host.store().get(string));
                sub.setText(unavailable ? host.unavailableSummary(setting) : summary);
                sub.setTextColor(unavailable ? PanelStyle.COL_SUMMARY : PanelStyle.COL_ACCENT);
            }
            row.setEnabled(!unavailable);
        } else if (setting instanceof Settings.IntegerSetting) {
            TextView value = row.findViewWithTag(PanelTags.ROW_VALUE);
            if (value instanceof TextView) {
                value.setText(SettingLabels.formatStepper(
                        (Settings.IntegerSetting) setting,
                        host.store().get((Settings.IntegerSetting) setting)));
            }
        }
    }

    // --- Lookup helpers ---

    public static GlossyToggle findToggle(View row) {
        if (!(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof GlossyToggle) return (GlossyToggle) group.getChildAt(i);
        }
        return null;
    }

    public static EditText findEditText(View row) {
        if (!(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof EditText) return (EditText) group.getChildAt(i);
        }
        return null;
    }

    // --- Stepper mechanics ---

    /** Pill button holding a lucide minus/plus; touch logic is view-agnostic. */
    private View stepButton(boolean plus, Settings.IntegerSetting setting) {
        PanelStyle style = host.style();
        android.widget.ImageButton glyph = new android.widget.ImageButton(style.context());
        glyph.setImageDrawable(new ActionIconDrawable(plus ? Kind.PLUS : Kind.MINUS,
                PanelStyle.COL_TITLE, style.density()));
        int pad = style.dp(8);
        glyph.setPadding(pad, pad, pad, pad);
        String description = host.strings().format(
                plus ? "settings_stepper_increase" : "settings_stepper_decrease",
                plus ? "Increase %1$s" : "Decrease %1$s", host.strings().setting(setting));
        glyph.setContentDescription(description);
        glyph.setTooltipText(description);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(style.dp(18));
        bg.setStroke(style.dp(1), PanelStyle.COL_CARD_BORDER);
        glyph.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return glyph;
    }

    /**
     * Steppers track the pending value locally and only commit ~250ms after the last tick:
     * expensive consumers would otherwise lag the press-and-hold ramp.
     */
    private void adjustStepper(Settings.IntegerSetting setting, TextView valueView, int delta,
                               int[] pending, Runnable commit) {
        int next = Math.max(setting.minValue, Math.min(setting.maxValue, pending[0] + delta));
        pending[0] = next;
        valueView.setText(SettingLabels.formatStepper(setting, next));
        valueView.removeCallbacks(commit);
        valueView.postDelayed(commit, 250L);
    }

    private void attachStepperTouch(View button, final Settings.IntegerSetting setting,
                                    final TextView valueView, final int delta,
                                    final int[] pending, final Runnable commit) {
        final int[] repeatCount = new int[]{0};
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            adjustStepper(setting, valueView, delta, pending, commit);
            repeatCount[0]++;
            long delayMs = Math.max(45L, 130L - repeatCount[0] * 8L);
            button.postDelayed(repeat[0], delayMs);
        };
        button.setOnClickListener(v -> adjustStepper(setting, valueView, delta, pending, commit));
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    // Without this the surrounding ScrollView intercepts on the slightest finger
                    // drift and cancels the hold, so press-and-hold repeat never ramps.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    repeatCount[0] = 0;
                    adjustStepper(setting, valueView, delta, pending, commit);
                    v.removeCallbacks(repeat[0]);
                    v.postDelayed(repeat[0], 360L);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.sendAccessibilityEvent(
                            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                    // fall through
                case android.view.MotionEvent.ACTION_CANCEL:
                case android.view.MotionEvent.ACTION_OUTSIDE:
                    v.setPressed(false);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
    }
}
