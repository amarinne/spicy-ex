package com.eza.spicyex.settings.editor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.settings.TypedStore;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import com.eza.spicyex.ui.SettingsUiStrings;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * One editor options panel's row widgets and group cards: toggle, chip, slider, mode-slider and the
 * softly tinted card that groups related rows. Moved out of LyricsLayoutEditController so the
 * registry renderer and every remaining hand builder share one implementation.
 *
 * <p>Both writers are deliberate: the settings writer is used directly for controls that already
 * write one value per movement, while {@link Write} is the editor session's deferred write (store,
 * then force the shell to re-apply, then run the row's effect after the next layout).
 */
public final class EditorRows {
    public static final int TEXT_COLOR = 0xFFE8E8EE;
    public static final int ACCENT_COLOR = 0xFF1ED760;
    public static final int GROUP_TITLE_COLOR = 0x8AFFFFFF;

    private static final int GROUP_TITLE_SP = 12;
    private static final int CONTROL_TEXT_SP = 15;
    private static final int CONTROL_MIN_HEIGHT_DP = 44;
    private static final int GROUP_RADIUS_DP = 14;
    private static final int GROUP_FILL_COLOR = 0x0FFFFFFF;
    private static final int HANDLE_SIZE_DP = 22;
    private static final int GROUP_GAP_DP = 10;
    private static final int GROUP_TITLE_GAP_DP = 10;

    /** The session's deferred write: persists, re-applies the shell, then runs the effect. */
    public interface Write {
        <T> void put(Settings.Setting<T> setting, T value, Runnable afterApply);
    }

    /** The live Random dialog's own controls, reached from the agent route. */
    public interface RandomPoolAgent {
        /** @return null when the action ran, or the reason it was refused. */
        String agent(String action, String argument);
    }

    /** Tag on the layout editor's Random list opener chip. */
    public static final String RANDOM_POOL_OPEN_TAG = "action.background_random_effects";

    private final Activity activity;
    private final LinearLayout optionsCard;
    private final SettingsUiStrings strings;
    private final TypedStore store;
    private final SettingsWriter writer;
    private final Write write;
    /** Card the option builders are currently writing into; null means "straight to the card". */
    private LinearLayout optionsGroup;
    private boolean draggingSlider;
    private RandomPoolAgent randomPoolAgent;

    public EditorRows(Activity activity, LinearLayout optionsCard, SettingsUiStrings strings,
            TypedStore store, SettingsWriter writer, Write write) {
        this.activity = activity;
        this.optionsCard = optionsCard;
        this.strings = strings;
        this.store = store;
        this.writer = writer;
        this.write = write;
    }

    public Activity activity() {
        return activity;
    }

    // -- group cards --------------------------------------------------------

    // The panel used to be one flat column: a 13sp label, a control, a hairline rule, a 13sp
    // label, a control, and so on for every setting an element has. Label and control text
    // were the same size, so nothing established what belonged to what, and with a rule
    // between every single row the whole card read as dense ruled paper rather than a set of
    // choices. Each setting now gets its own softly-tinted rounded card with a smaller, muted
    // title above larger, clearly tappable controls: the same options, one obvious visual unit
    // each, and no rules at all.

    public void beginGroup(String title) {
        endGroup();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(GROUP_RADIUS_DP));
        bg.setColor(GROUP_FILL_COLOR);
        box.setBackground(bg);
        box.setPadding(dp(14), dp(12), dp(14), dp(13));
        if (title != null && !title.isEmpty()) {
            TextView label = text(title, GROUP_TITLE_SP, GROUP_TITLE_COLOR, true);
            label.setLetterSpacing(0.02f);
            box.addView(label, matchWrap(GROUP_TITLE_GAP_DP));
        }
        optionsCard.addView(box, matchWrap(GROUP_GAP_DP));
        optionsGroup = box;
    }

    public void endGroup() {
        optionsGroup = null;
    }

    /** Option-builder entry point: lands in the open group card, or the panel itself when a
     *  builder adds something before opening one. */
    public void addOption(View view, LinearLayout.LayoutParams lp) {
        (optionsGroup != null ? optionsGroup : optionsCard).addView(view, lp);
    }

    /** List mode's element header: a title above that element's group cards, on the panel itself
     *  so its top is the section's scroll position. */
    public TextView addElementHeader(String title, boolean first) {
        endGroup();
        TextView header = text(title, 17, TEXT_COLOR, true);
        header.setSingleLine(true);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setPadding(0, dp(first ? 4 : 16), 0, dp(8));
        optionsCard.addView(header, matchWrap(0));
        return header;
    }

    public void addSectionLabel(Settings.Setting<?> setting, int bottomDp) {
        beginGroup(strings.setting(setting));
    }

    /** Ends the current card, so the next row starts another one. */
    public void addDivider() {
        endGroup();
    }

    public boolean isDraggingSlider() {
        return draggingSlider;
    }

    // -- text and chip widgets ---------------------------------------------

    /** Editor-only freeform text (hints, element names, action labels) that has no
     *  corresponding {@link Settings.Setting} of its own to borrow a label from. */
    public String s(String key, String fallback) {
        return strings.get("settings_layout_editor_" + key, fallback);
    }

    public String settingLabel(Settings.Setting<?> setting) {
        return strings.setting(setting);
    }

    /** A whole string resource name, for registry keys that are not derived from a setting. */
    public String resource(String key) {
        return key == null ? "" : strings.get(key, "");
    }

    /** Editor-only formatted text; the key carries the {@code settings_layout_editor_} prefix. */
    public String format(String key, String fallback, Object... args) {
        return strings.format("settings_layout_editor_" + key, fallback, args);
    }

    public String onOffLabel(boolean on) {
        return strings.get(on ? "settings_option_on" : "settings_option_off", on ? "On" : "Off");
    }

    public TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    public TextView chip(String label) {
        TextView chip = text(label, CONTROL_TEXT_SP, TEXT_COLOR, false);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setPadding(dp(6), dp(11), dp(6), dp(11));
        chip.setMinHeight(dp(CONTROL_MIN_HEIGHT_DP));
        // A standalone chip keeps its label on one line. A chip that shares a line with
        // others opts out of that: see chipRow(), where the line is planned from the labels.
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return chip;
    }

    public void paintChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(selected ? 0x331ED760 : 0x14FFFFFF);
        chip.setBackground(bg);
        chip.setTextColor(selected ? ACCENT_COLOR : TEXT_COLOR);
        // Unselected chips also dim slightly - color alone (white vs. accent) read as too
        // close in weight; a touch of transparency makes the selected one pop more clearly.
        chip.setAlpha(selected ? 1f : 0.72f);
    }

    public LinearLayout.LayoutParams matchWrap(int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        return lp;
    }

    /** A bare horizontal container for composites that own their chip behavior. */
    LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    /** The translated label of one stored value, the same lookup the Settings panel uses. */
    String optionLabel(Settings.StringSetting setting, String value) {
        return strings.option(setting, value);
    }

    // -- store reads and unit text -----------------------------------------

    public int safeGet(Settings.IntegerSetting setting) {
        Integer value = store.get(setting);
        return value == null ? setting.defaultValue : value;
    }

    /** Stored value of a string setting, with its declared default as the fallback - for a
     *  control derived from another setting rather than backed by one. */
    public String safeGetString(Settings.Setting<String> setting) {
        String value = store.get(setting);
        return value == null ? setting.defaultValue : value;
    }

    /** Applies one registry row's deferred write. */
    public <T> void apply(Settings.Setting<T> setting, T value, Runnable afterApply) {
        write.put(setting, value, afterApply);
    }

    /** Direct write, without the shell re-apply: for controls the editor drives itself, whose
     *  own refresh is the only thing the value changes. */
    public <T> void put(Settings.Setting<T> setting, T value) {
        writer.put(setting, value);
    }

    /** Unit suffix for a registry unit key. The registry keys have no shipped resources yet. */
    public String unit(String unitKey) {
        if (unitKey == null) return "";
        String value = strings.get(unitKey, "");
        if (!value.isEmpty()) return value;
        if ("settings_unit_dp".equals(unitKey)) return "dp";
        if ("settings_unit_percent".equals(unitKey)) return "%";
        return "";
    }

    // -- choice chips -------------------------------------------------------

    /**
     * The tag a choice chip carries: the setting it belongs to and the value it stores.
     *
     * <p>The tag is the chip's identity in the panel's own view tree, so the agent route can
     * find the very chip a tap would hit and reach the row's real owner. The value is part of
     * the tag, so a request names the choice it means and nothing else.
     */
    public static String choiceTag(String settingKey, String value) {
        return "option." + settingKey + "." + value;
    }

    /** The prefix every choice chip of one setting carries. */
    public static String choiceTagPrefix(String settingKey) {
        return "option." + settingKey + ".";
    }

    /**
     * The choice chips the panel really rendered for one setting, keyed by the value each one
     * stores.
     *
     * <p>A setting the current layout does not offer has no chips here, and a value that is not
     * one of the setting's choices has no entry: the panel is the only source of truth, so an
     * option that is hidden, filtered out or simply not drawn cannot be picked.
     */
    public Map<String, View> renderedChoices(String settingKey) {
        Map<String, View> out = new LinkedHashMap<>();
        if (settingKey == null || settingKey.isEmpty()) return out;
        String prefix = choiceTagPrefix(settingKey);
        collectChoices(optionsCard, prefix, out);
        return out;
    }

    private static void collectChoices(View view, String prefix, Map<String, View> out) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith(prefix)) {
            out.put(((String) tag).substring(prefix.length()), view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectChoices(group.getChildAt(i), prefix, out);
            }
        }
    }

    // -- Random pool dialog route ------------------------------------------

    public void randomPoolAgent(RandomPoolAgent agent) {
        randomPoolAgent = agent;
    }

    public void clearRandomPoolAgent(RandomPoolAgent agent) {
        if (randomPoolAgent == agent) randomPoolAgent = null;
    }

    /**
     * Drives the Random dialog through the views it really rendered. Opening uses the panel's own
     * Choose chip, and every later action runs on that dialog's live controls rather than writing
     * a second implementation of the pool edit.
     */
    public String agentRandomPool(String action, String argument) {
        if (action == null || action.isEmpty()) {
            return "expected open, search, toggle, apply, cancel, or status";
        }
        if ("open".equals(action)) {
            if (randomPoolAgent != null) return "the Random list is already open";
            View opener = optionsCard.findViewWithTag(RANDOM_POOL_OPEN_TAG);
            if (opener == null || !opener.isShown()) return "the Random list is not on screen";
            return opener.performClick() && randomPoolAgent != null
                    ? null : "the Random list did not open";
        }
        if (randomPoolAgent == null) {
            return "status".equals(action)
                    ? agentRandomPoolStatus() : "the Random list is not open";
        }
        return randomPoolAgent.agent(action, argument);
    }

    /** Reports the dialog's pending choices separately from the store's persisted pool. */
    public String agentRandomPoolStatus() {
        String persisted = BackgroundEffectPool.parse(
                safeGetString(Settings.BACKGROUND_RANDOM_EFFECTS)).encode();
        if (randomPoolAgent == null) {
            return randomPoolStatus(false, "", "none", persisted, 0);
        }
        return randomPoolAgent.agent("status", "");
    }

    static String randomPoolStatus(boolean open, String query, String pending,
            String persisted, int shown) {
        return "open=" + open
                + " query=" + (query == null || query.isEmpty() ? "-" : query)
                + " pending=" + pending
                + " persisted=" + persisted
                + " shown=" + shown;
    }

    // -- shared row builders ------------------------------------------------

    /** Chip labels always come from {@link SettingsUiStrings#option}, the same lookup the
     *  main Settings panel uses for this exact setting/value pair - so a value already
     *  labeled and translated there (most of them: Off/Top/Bottom/Regular/Small/...) reads
     *  identically here with no separate translation to maintain.
     *
     *  <p>The line a chip sits on is planned from the labels themselves
     *  ({@link ChipRowPlan}), so a long label gets room instead of an ellipsis. */
    public LinearLayout chipRow(Settings.Setting<String> setting, String[] values,
            Runnable onChanged) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag("setting." + setting.key);
        TextView[] chips = new TextView[values.length];
        String current = store.get(setting);
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = optionLabel((Settings.StringSetting) setting, values[i]);
        }
        List<List<Integer>> lines = ChipRowPlan.plan(Arrays.asList(labels));
        for (int line = 0; line < lines.size(); line++) {
            LinearLayout chipsLine = new LinearLayout(activity);
            chipsLine.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lineLp = matchWrap(0);
            if (line > 0) lineLp.topMargin = dp(6);
            row.addView(chipsLine, lineLp);
            for (int index : lines.get(line)) {
                TextView chip = chip(labels[index]);
                // A label that does not fit its share of the line wraps inside the chip
                // instead of being cut: the words that tell two options apart have to stay
                // readable, and the accessibility node keeps the whole label either way.
                chip.setSingleLine(false);
                chip.setEllipsize(null);
                chip.setTag(choiceTag(setting.key, values[index]));
                chips[index] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                chipsLine.addView(chip, lp);
            }
        }
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            chips[i].setOnClickListener(v -> {
                for (int j = 0; j < values.length; j++) paintChip(chips[j], values[j].equals(value));
                // Deferred: TRACK_INFO_POSITION can reparent a whole view subtree
                // (entering/leaving Header mode) - see the session writer's documentation for
                // why that can't happen synchronously from inside this click callback.
                write.put(setting, value, onChanged);
            });
            paintChip(chips[i], value.equals(current));
        }
        return row;
    }

    /** Label + a two-state On/Off chip, for boolean settings - the same {@code chip()}/
     *  {@code paintChip()} widgets {@code chipRow()} uses, just one toggling chip instead of
     *  a row of mutually-exclusive ones. */
    public LinearLayout toggleRow(Settings.Setting<Boolean> setting, String label, Runnable onChanged) {
        boolean current = Boolean.TRUE.equals(store.get(setting));
        return toggleRow(label, current, (toggle, next) -> write.put(setting, next, onChanged));
    }

    /** Label + a two-state On/Off chip, backed by a plain boolean rather than a
     *  {@code Setting<Boolean>} directly - for a toggle derived from some other value (e.g.
     *  Extra darken's on/off state is just "is the 0-100 intensity setting non-zero") rather
     *  than its own persisted key. {@code onToggle} is responsible for persisting the new
     *  state; it's given the chip so it can still opt into the same optimistic repaint the
     *  plain-setting overload above does, or rebuild the whole panel (e.g. via
     *  {@code selectElement}) when the toggle changes what else is shown. */
    public LinearLayout toggleRow(String label, boolean current,
            BiConsumer<TextView, Boolean> onToggle) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, CONTROL_TEXT_SP, 0xE0FFFFFF, false);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggle = chip(onOffLabel(current));
        paintChip(toggle, current);
        boolean[] state = {current};
        toggle.setOnClickListener(v -> {
            boolean next = !state[0];
            state[0] = next;
            toggle.setText(onOffLabel(next));
            paintChip(toggle, next);
            onToggle.accept(toggle, next);
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                dp(68), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(toggle, toggleLp);
        return row;
    }

    /** @param defaultMarker optional visual marker for the one declared default. It never
     *  changes, rounds, or magnetically snaps the user's value. */
    public LinearLayout dragRow(int min, int max, int initial, String unit, Integer defaultMarker,
            IntConsumer onChange) {
        return dragRow(min, max, initial, unit, defaultMarker, value -> value + unit, onChange);
    }

    public LinearLayout dragRow(int min, int max, int initial, String unit, Integer defaultMarker,
            IntFunction<String> labelForValue, IntConsumer onChange) {
        int[] value = {initial};
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView valueLabel = text(labelForValue.apply(initial), CONTROL_TEXT_SP, Color.WHITE, false);
        valueLabel.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(valueLabel, labelLp);

        FrameLayout track = new FrameLayout(activity);
        track.setFocusable(true);
        track.setContentDescription(valueLabel.getText());
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x24FFFFFF);
        trackBg.setCornerRadius(dp(3));
        track.setBackground(trackBg);

        View tick = null;
        if (defaultMarker != null) {
            tick = new View(activity);
            tick.setBackgroundColor(0x80FFFFFF);
            track.addView(tick, new FrameLayout.LayoutParams(dp(2), dp(10), Gravity.CENTER_VERTICAL));
        }
        final View tickView = tick;

        View thumb = new View(activity);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(ACCENT_COLOR);
        thumb.setBackground(thumbBg);
        int thumbSize = dp(HANDLE_SIZE_DP);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.START);
        track.addView(thumb, thumbLp);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
        trackLp.leftMargin = dp(10);
        row.addView(track, trackLp);

        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            int trackW = track.getWidth() - thumbSize;
            if (trackW <= 0) {
                track.post(() -> paint[0].run());
                return;
            }
            float fraction = (value[0] - min) / (float) Math.max(1, max - min);
            thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
            thumb.setLayoutParams(thumbLp);
            if (tickView != null) {
                float detentFraction = (defaultMarker - min) / (float) Math.max(1, max - min);
                FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) tickView.getLayoutParams();
                tickLp.leftMargin = thumbSize / 2 - dp(1) + Math.round(trackW * clamp01(detentFraction));
                tickView.setLayoutParams(tickLp);
            }
        };
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
        track.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            // The options card scrolls (see MaxHeightScrollView) - without this, a drag
            // that drifts even slightly vertically hands the gesture to that scroll instead
            // of finishing the slide here.
            if (action == MotionEvent.ACTION_DOWN) {
                draggingSlider = true;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                draggingSlider = false;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
            draggingSlider = true;
            int trackW = track.getWidth() - thumbSize;
            if (trackW <= 0) return false;
            float x = event.getX() - thumbSize / 2f;
            float fraction = clamp01(x / trackW);
            int newValue = Math.round(min + fraction * (max - min));
            if (newValue != value[0]) {
                value[0] = newValue;
                valueLabel.setText(labelForValue.apply(newValue));
                track.setContentDescription(valueLabel.getText());
                onChange.accept(newValue);
            }
            paint[0].run();
            return true;
        });
        track.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.SeekBar.class.getName());
                info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        min, max, value[0]));
                if (value[0] < max) info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                if (value[0] > min) info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                int direction = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1
                        : action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 0;
                if (direction == 0) return super.performAccessibilityAction(host, action, args);
                int step = Math.max(1, Math.round((max - min) / 20f));
                int next = clamp(value[0] + direction * step, min, max);
                if (next == value[0]) return true;
                value[0] = next;
                valueLabel.setText(labelForValue.apply(next));
                track.setContentDescription(valueLabel.getText());
                onChange.accept(next);
                paint[0].run();
                host.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
                return true;
            }
        });
        return row;
    }

    /** Smooth custom-value slider for settings that retain legacy named modes in storage.
     * Entering the editor converts the currently rendered named mode to its equivalent value;
     * the first user movement then writes Custom directly with no preset snap points. */
    public LinearLayout customSliderRow(Settings.Setting<String> modeSetting,
            Settings.IntegerSetting customSetting, String customValue, Runnable onChanged) {
        int initial = resolvedCustomPercent(modeSetting, customSetting);
        return dragRow(customSetting.minValue, customSetting.maxValue, initial, "%",
                customSetting.defaultValue, value -> {
                    writer.put(modeSetting, customValue);
                    writer.put(customSetting, value);
                    if (onChanged != null) onChanged.run();
                });
    }

    public int resolvedCustomPercent(Settings.Setting<String> modeSetting,
            Settings.IntegerSetting customSetting) {
        String mode = store.get(modeSetting);
        if (modeSetting == Settings.LINE_SPACING) {
            if ("compact".equals(mode)) return 80;
            if ("default".equals(mode)) return 110;
            if ("spacious".equals(mode)) return 150;
            if ("more".equals(mode)) return 200;
            if ("max".equals(mode)) return 250;
        } else if (modeSetting == Settings.TRACK_INFO_TEXT_SIZE) {
            if ("Small".equals(mode)) return 87;
            if ("Normal".equals(mode)) return 100;
            if ("Large".equals(mode)) return 120;
            if ("XLarge".equals(mode)) return 147;
        } else {
            if ("small".equalsIgnoreCase(mode)) return 90;
            if ("normal".equalsIgnoreCase(mode)) return 100;
            if ("large".equalsIgnoreCase(mode)) return 120;
            if ("xlarge".equalsIgnoreCase(mode)) return 150;
        }
        return safeGet(customSetting);
    }

    /** Registry slider: bounds and marker from the spec, one direct write per movement, then the
     *  row's effect. Direct writes match the hand-built sliders this replaces, which did not
     *  force a shell re-apply before their own refresh. */
    public LinearLayout sliderRow(com.eza.spicyex.settings.registry.SliderSpec slider,
            Settings.IntegerSetting setting, Runnable onChange) {
        return dragRow(slider.min, slider.max, safeGet(setting), unit(slider.unitKey),
                slider.defaultMarker, value -> {
                    writer.put(setting, value);
                    if (onChange != null) onChange.run();
                });
    }

    /**
     * Registry slider over a setting that stores a name instead of a number: the codec names
     * every stop, and the setting stores exactly that name. The final stop is the codec's own
     * name for it ("Always"), so the row reads the same as the setting's stored values.
     */
    public LinearLayout codecSliderRow(com.eza.spicyex.settings.registry.SliderSpec slider,
            Settings.Setting<String> setting, Runnable onChange) {
        com.eza.spicyex.settings.registry.ValueCodec codec = slider.codec;
        int top = slider.endValue != null ? slider.endValue : slider.max;
        int initial = codec.decode(store.get(setting));
        if (initial < slider.min) initial = slider.min;
        if (initial > top) initial = top;
        return dragRow(slider.min, top, initial, "", slider.defaultMarker,
                value -> stopLabel(slider, codec, value), value -> {
                    writer.put(setting, codec.encode(value));
                    if (onChange != null) onChange.run();
                });
    }

    /**
     * The name of one stop. The codec owns the wording - it is the text the setting stores -
     * except for a stop that has a string resource of its own.
     */
    private String stopLabel(com.eza.spicyex.settings.registry.SliderSpec slider,
            com.eza.spicyex.settings.registry.ValueCodec codec, int value) {
        if (slider.endValue != null && value == slider.endValue && slider.endLabelKey != null) {
            String named = resource(slider.endLabelKey);
            if (!named.isEmpty()) return named;
        }
        return codec.encode(value);
    }

    // -- small view helpers -------------------------------------------------

    int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
