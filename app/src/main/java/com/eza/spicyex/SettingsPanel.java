package com.eza.spicyex;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.beautifullyrics.entities.LyricsTranslator;

/**
 * The Spicy EX settings UI as a single floating rounded card (centered modal), built from the
 * {@link Settings} schema. Rendered in-Spotify by the hook (the only config surface). Platform
 * widgets + a custom {@link GlossyToggle} so it needs no host theme; one card with flat labelled
 * sections (no nested cards), the whole thing sized/centered by the hosting dialog.
 */
public final class SettingsPanel {
    private static final int COL_CARD = 0xF21C1C22;
    private static final int COL_CARD_BORDER = 0x24FFFFFF;
    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_SUMMARY = 0x99FFFFFF;
    private static final int COL_SECTION = 0xFF8A8A90;
    private static final int COL_ACCENT = 0xFF1ED760;

    private final Context context;
    private final SettingsStore store;
    private final Runnable onClose;
    private LinearLayout sectionsContainer;

    public SettingsPanel(Context context, SettingsStore store, Runnable onClose) {
        this.context = context;
        this.store = store;
        this.onClose = onClose;
    }

    /** Builds the card view; the host sizes/centers it. */
    public View build() {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(COL_CARD);
        cardBg.setCornerRadius(dp(26));
        cardBg.setStroke(dp(1), COL_CARD_BORDER);
        scroll.setBackground(cardBg);
        scroll.setClipToOutline(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        renderHeader(content);
        sectionsContainer = new LinearLayout(context);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(sectionsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderSections(sectionsContainer);
        renderActions(content);
        renderStatus(content);
        return scroll;
    }

    private void renderHeader(LinearLayout content) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Spicy EX", 26, COL_TITLE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onClose != null) {
            TextView close = text("✕", 20, COL_SUMMARY, false);
            close.setPadding(dp(10), dp(6), dp(6), dp(6));
            close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
            close.setOnClickListener(v -> onClose.run());
            header.addView(close);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        content.addView(header, lp);
    }

    private void renderSections(LinearLayout content) {
        Settings.Section currentSection = null;
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section == Settings.INTERNAL) continue;
            if (!shouldRender(setting)) continue;
            if (setting.section != currentSection) {
                currentSection = setting.section;
                sectionLabel(content, currentSection.label);
            }
            if (setting instanceof Settings.BooleanSetting) {
                switchRow(content, (Settings.BooleanSetting) setting);
            } else if (setting instanceof Settings.IntegerSetting) {
                stepperRow(content, (Settings.IntegerSetting) setting);
            } else if (setting instanceof Settings.StringSetting) {
                Settings.StringSetting s = (Settings.StringSetting) setting;
                if (s.allowedValues == null || s.allowedValues.isEmpty()) textFieldRow(content, s);
                else selectorRow(content, s);
            }
        }
    }

    private void rebuildSections() {
        if (sectionsContainer == null) return;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
    }

    private boolean shouldRender(Settings.Setting<?> setting) {
        if (setting == Settings.TRANSLATION_TARGET || setting == Settings.TRANSLATION_BRIGHTNESS) {
            return store.get(Settings.TRANSLATION_ENABLED);
        }
        if (setting == Settings.ALIGNED_PER_WORD_ROMAJI
                || setting == Settings.JAPANESE_READING_MODE
                || setting == Settings.CHINESE_MODE
                || setting == Settings.KOREAN_ROMANIZATION
                || setting == Settings.CHINESE_TONES
                || setting == Settings.CYRILLIC_MODE
                || setting == Settings.CYRILLIC_KEEP_SIGNS
                || setting == Settings.LIVE_CARD_SHOW_TRANSLITERATION) {
            return store.get(Settings.TRANSLITERATION_ENABLED);
        }
        if (setting == Settings.FORCE_DARK_BACKGROUND) {
            return store.get(Settings.ENABLE_BACKGROUND);
        }
        if (setting == Settings.LINE_SYNC_FILL) {
            return "Gradient wash".equals(store.get(Settings.ANIMATION_STYLE));
        }
        return true;
    }

    private boolean shouldRebuildAfterChange(Settings.Setting<?> setting) {
        return setting == Settings.TRANSLATION_ENABLED
                || setting == Settings.TRANSLITERATION_ENABLED
                || setting == Settings.ENABLE_BACKGROUND
                || setting == Settings.ANIMATION_STYLE;
    }

    private void renderActions(LinearLayout content) {
        sectionLabel(content, "Actions");
        actionRow(content, "Clear translation cache", v -> LyricsTranslator.clearCache(context));
        actionRow(content, "Clear lyrics response cache", v -> LyricsResponseCache.clear(context));
    }

    private void renderStatus(LinearLayout content) {
        sectionLabel(content, "Status");
        CurrentLyricState s = CurrentLyricState.get();
        String summary = "Last state: " + s.status
                + "\nTrack: " + s.title
                + "\nLine: " + s.originalLine;
        TextView state = text(summary, 12, COL_SUMMARY, false);
        state.setPadding(0, dp(4), 0, dp(2));
        content.addView(state);
        TextView version = text(BuildStamp.FULL, 11, COL_SECTION, false);
        version.setPadding(0, dp(12), 0, 0);
        content.addView(version);
    }

    // --- Rows ---

    private void sectionLabel(LinearLayout content, String label) {
        TextView view = text(label.toUpperCase(java.util.Locale.ROOT), 12, COL_ACCENT, true);
        view.setLetterSpacing(0.08f);
        view.setPadding(0, dp(18), 0, dp(6));
        content.addView(view);
    }

    private LinearLayout newRow(LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView titleColumn(LinearLayout row, String title, String summary) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 16, COL_TITLE, false));
        TextView sub = text(summary == null ? "" : summary, 13, COL_SUMMARY, false);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        col.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(12);
        row.addView(col, lp);
        return sub;
    }

    private void switchRow(LinearLayout content, Settings.BooleanSetting setting) {
        LinearLayout row = newRow(content);
        titleColumn(row, setting.label, null);
        GlossyToggle toggle = new GlossyToggle(context);
        toggle.setAccent(COL_ACCENT);
        toggle.setChecked(store.get(setting), false);
        toggle.setOnChangeListener(() -> {
            store.put(setting, toggle.isChecked());
            if (shouldRebuildAfterChange(setting)) rebuildSections();
        });
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
    }

    private void selectorRow(LinearLayout content, Settings.StringSetting setting) {
        LinearLayout row = newRow(content);
        TextView value = titleColumn(row, setting.label, labelFor(setting, store.get(setting)));
        value.setTextColor(COL_ACCENT);
        row.addView(text("›", 22, COL_SECTION, false));
        row.setOnClickListener(v -> showSelectorDialog(setting, value));
    }

    private void stepperRow(LinearLayout content, Settings.IntegerSetting setting) {
        LinearLayout row = newRow(content);
        titleColumn(row, setting.label, null);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = stepButton("-");
        TextView value = text(formatOffset(store.get(setting)), 15, COL_ACCENT, true);
        value.setGravity(Gravity.CENTER);
        TextView plus = stepButton("+");

        controls.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(dp(74), dp(36));
        valueLp.leftMargin = dp(4);
        valueLp.rightMargin = dp(4);
        controls.addView(value, valueLp);
        controls.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(controls);

        attachStepperTouch(minus, setting, value, -setting.stepValue);
        attachStepperTouch(plus, setting, value, setting.stepValue);
    }

    private TextView stepButton(String label) {
        TextView button = text(label, 20, COL_TITLE, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), COL_CARD_BORDER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return button;
    }

    private void adjustStepper(Settings.IntegerSetting setting, TextView valueView, int delta) {
        int current = store.get(setting);
        int next = Math.max(setting.minValue, Math.min(setting.maxValue, current + delta));
        store.put(setting, next);
        valueView.setText(formatOffset(next));
    }

    private void attachStepperTouch(TextView button, Settings.IntegerSetting setting, TextView valueView, int delta) {
        final int[] repeatCount = new int[]{0};
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            adjustStepper(setting, valueView, delta);
            repeatCount[0]++;
            long delayMs = Math.max(45L, 130L - repeatCount[0] * 8L);
            button.postDelayed(repeat[0], delayMs);
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    repeatCount[0] = 0;
                    adjustStepper(setting, valueView, delta);
                    v.removeCallbacks(repeat[0]);
                    v.postDelayed(repeat[0], 360L);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    v.setPressed(false);
                    v.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void showSelectorDialog(Settings.StringSetting setting, TextView valueView) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_CARD);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), COL_CARD_BORDER);
        box.setBackground(bg);
        box.setPadding(0, dp(18), 0, dp(10));

        TextView title = text(setting.label, 19, COL_TITLE, true);
        title.setPadding(dp(22), 0, dp(22), dp(12));
        box.addView(title);

        String current = store.get(setting);
        for (final String val : setting.allowedValues) {
            boolean selected = val.equals(current);
            LinearLayout optRow = new LinearLayout(context);
            optRow.setOrientation(LinearLayout.HORIZONTAL);
            optRow.setGravity(Gravity.CENTER_VERTICAL);
            optRow.setMinimumHeight(dp(52));
            optRow.setPadding(dp(22), dp(8), dp(22), dp(8));
            optRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));

            TextView dot = text(selected ? "●" : "○", 15, selected ? COL_ACCENT : COL_SECTION, false);
            dot.setPadding(0, 0, dp(16), 0);
            optRow.addView(dot);
            TextView label = text(labelFor(setting, val), 16, selected ? COL_ACCENT : COL_TITLE, false);
            optRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String preview = optionPreview(setting, val);
            if (!preview.isEmpty()) {
                TextView pv = text(preview, 18, selected ? COL_ACCENT : COL_TITLE, false);
                pv.setPadding(dp(12), 0, 0, 0);
                optRow.addView(pv);
            }
            optRow.setOnClickListener(v -> {
                store.put(setting, val);
                valueView.setText(labelFor(setting, val));
                dialog.dismiss();
            });
            box.addView(optRow);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box);
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.55f);
            int w = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.82f);
            int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.7f);
            window.setLayout(w, setting.allowedValues.size() > 8 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void textFieldRow(LinearLayout content, Settings.StringSetting setting) {
        LinearLayout row = newRow(content);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.addView(text(setting.label, 14, COL_SUMMARY, false));
        EditText field = new EditText(context);
        field.setText(store.get(setting));
        field.setTextColor(COL_TITLE);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackgroundTintList(ColorStateList.valueOf(COL_SECTION));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { store.put(setting, s.toString()); }
        });
        row.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void actionRow(LinearLayout content, String label, View.OnClickListener listener) {
        LinearLayout row = newRow(content);
        row.addView(text(label, 16, COL_ACCENT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
    }

    private static String optionPreview(Settings.StringSetting setting, String value) {
        if ("lyric_interlude_icon".equals(setting.key)) {
            if ("dots".equals(value)) return "• • •";
            if ("note".equals(value)) return "♪";
        }
        return "";
    }

    /** Option label with the actual multiplier appended for the magnitude-based selectors. */
    private static String labelFor(Settings.StringSetting setting, String value) {
        String base = displayLabel(value);
        String mult = multiplierFor(setting.key, value);
        return mult == null ? base : base + "  (" + mult + ")";
    }

    // Mirror of LyricsShellSettings.lineSpacingMultiplier() / lyricsTextSizeMultiplier() — display only.
    private static String multiplierFor(String key, String value) {
        if ("line_spacing".equals(key)) {
            switch (value) {
                case "compact": return "0.8";
                case "default": return "1.1";
                case "spacious": return "1.45";
                case "more": return "1.9";
                case "max": return "2.5";
                default: return null;
            }
        }
        if ("lyrics_text_size".equals(key) || "lyrics_live_card_text_size".equals(key)) {
            switch (value) {
                case "small": return "0.88";
                case "normal": return "1.0";
                case "large": return "1.2";
                case "xlarge": return "1.45";
                default: return null;
            }
        }
        return null;
    }

    private static String displayLabel(String value) {
        if (value == null || value.isEmpty()) return "";
        if ("furigana_romaji".equals(value)) return "Furigana + romaji";
        String name = LANGUAGE_NAMES.get(value);
        if (name != null) return name + " (" + value + ")";
        String spaced = value.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isEmpty()) return value;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String formatOffset(int offsetMs) {
        if (offsetMs == 0) return "0.0s";
        return String.format(java.util.Locale.US, "%+.1fs", offsetMs / 1000f);
    }

    private static final java.util.Map<String, String> LANGUAGE_NAMES = buildLanguageNames();

    private static java.util.Map<String, String> buildLanguageNames() {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        m.put("auto", "Auto-detect"); m.put("other", "Other");
        m.put("en", "English"); m.put("es", "Spanish"); m.put("fr", "French");
        m.put("de", "German"); m.put("it", "Italian"); m.put("pt", "Portuguese");
        m.put("nl", "Dutch"); m.put("sv", "Swedish"); m.put("no", "Norwegian");
        m.put("da", "Danish"); m.put("fi", "Finnish"); m.put("pl", "Polish");
        m.put("cs", "Czech"); m.put("sk", "Slovak"); m.put("hu", "Hungarian");
        m.put("ro", "Romanian"); m.put("el", "Greek"); m.put("tr", "Turkish");
        m.put("uk", "Ukrainian"); m.put("ru", "Russian"); m.put("bg", "Bulgarian");
        m.put("sr", "Serbian"); m.put("mk", "Macedonian"); m.put("be", "Belarusian");
        m.put("ja", "Japanese"); m.put("ko", "Korean");
        m.put("zh", "Chinese (Simplified)"); m.put("zh-TW", "Chinese (Traditional)");
        m.put("th", "Thai"); m.put("vi", "Vietnamese"); m.put("id", "Indonesian");
        m.put("ms", "Malay"); m.put("hi", "Hindi"); m.put("bn", "Bengali");
        m.put("ta", "Tamil"); m.put("te", "Telugu"); m.put("ar", "Arabic");
        m.put("he", "Hebrew"); m.put("fa", "Persian"); m.put("ur", "Urdu");
        m.put("ka", "Georgian"); m.put("hy", "Armenian"); m.put("am", "Amharic");
        m.put("my", "Burmese"); m.put("km", "Khmer"); m.put("lo", "Lao");
        return m;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
