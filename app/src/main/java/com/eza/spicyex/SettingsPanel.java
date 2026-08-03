package com.eza.spicyex;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import com.eza.spicyex.diagnostics.DiagnosticReportingDialog;
import com.eza.spicyex.lyrics.LyricCaches;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;

/**
 * Owns settings-panel view construction only.
 * Does not own setting defaults (Settings), persistence (SettingsStore), or runtime normalization.
 * Rendered in-Spotify by the hook as a single floating rounded card using platform widgets and
 * {@link GlossyToggle}; layout should remain visually stable unless device screenshots verify parity.
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
    private final java.util.function.BooleanSupplier isHalfSize;
    private final Runnable onToggleSize;
    private final Runnable onClose;
    // Static: survives panel re-opens within the process, so the panel never re-opens fully collapsed.
    private static final java.util.Set<String> expandedSections = new java.util.HashSet<>();
    private LinearLayout sectionsContainer;
    private TextView panelTitle;
    private SettingsUiStrings uiStrings;

    public SettingsPanel(Context context, SettingsStore store, java.util.function.BooleanSupplier isHalfSize,
                         Runnable onToggleSize, Runnable onClose) {
        this.context = context;
        this.store = store;
        this.isHalfSize = isHalfSize;
        this.onToggleSize = onToggleSize;
        this.onClose = onClose;
        this.uiStrings = new SettingsUiStrings(context, store.get(Settings.UI_LANGUAGE));
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
        return scroll;
    }

    private void renderHeader(LinearLayout content) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = text(uiStrings.appName(), 26, COL_TITLE, true);
        header.addView(panelTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onToggleSize != null) {
            // ▴ = shrink to the top-anchored half panel, ▾ = grow back to full.
            TextView resize = headerButton(sizeGlyph(), null);
            resize.setOnClickListener(v -> {
                onToggleSize.run();
                resize.setText(sizeGlyph());
            });
            header.addView(resize);
        }
        if (onClose != null) {
            header.addView(headerButton("✕", v -> onClose.run()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        content.addView(header, lp);
    }

    private String sizeGlyph() {
        return isHalfSize != null && isHalfSize.getAsBoolean() ? "▾" : "▴";
    }

    /** Uniform 36dp centered icon button so header glyphs align regardless of their metrics. */
    private TextView headerButton(String glyph, View.OnClickListener listener) {
        TextView button = text(glyph, 18, COL_SUMMARY, false);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        if (listener != null) button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    private void renderSections(LinearLayout content) {
        java.util.LinkedHashMap<Settings.Section, java.util.List<Settings.Setting<?>>> grouped =
                new java.util.LinkedHashMap<>();
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section == Settings.INTERNAL) continue;
            if (!shouldRender(setting)) continue;
            java.util.List<Settings.Setting<?>> items = grouped.get(setting.section);
            if (items == null) {
                items = new java.util.ArrayList<>();
                grouped.put(setting.section, items);
            }
            items.add(setting);
        }
        for (java.util.Map.Entry<Settings.Section, java.util.List<Settings.Setting<?>>> entry : grouped.entrySet()) {
            Settings.Section section = entry.getKey();
            java.util.List<Settings.Setting<?>> items = entry.getValue();
            boolean expanded = expandedSections.contains(section.id);
            sectionHeader(content, section, expanded);
            if (!expanded) continue;
            LinearLayout card = sectionCard(content);
            for (Settings.Setting<?> setting : items) renderSetting(card, setting);
        }
        boolean debugExpanded = expandedSections.contains(Settings.DEBUG.id);
        sectionHeader(content, Settings.DEBUG, debugExpanded);
        if (debugExpanded) {
            LinearLayout card = sectionCard(content);
            renderActions(card);
            renderStatus(card);
            renderDiagnostics(card);
        }
    }

    /** Rounded container that visually groups an expanded section's rows. */
    private LinearLayout sectionCard(LinearLayout content) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0DFFFFFF);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(2), dp(10), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        content.addView(card, lp);
        return card;
    }

    private void renderSetting(LinearLayout content, Settings.Setting<?> setting) {
            if (setting instanceof Settings.BooleanSetting) {
                switchRow(content, (Settings.BooleanSetting) setting);
            } else if (setting instanceof Settings.IntegerSetting) {
                stepperRow(content, (Settings.IntegerSetting) setting);
            } else if (setting instanceof Settings.StringSetting) {
                Settings.StringSetting s = (Settings.StringSetting) setting;
                if (setting == Settings.UI_LANGUAGE) selectorRow(content, s, uiStrings.availableUiLanguages());
                else if (s.allowedValues == null || s.allowedValues.isEmpty()) textFieldRow(content, s);
                else selectorRow(content, s);
            }
    }

    private void rebuildSections() {
        if (sectionsContainer == null) return;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
    }

    private boolean shouldRender(Settings.Setting<?> setting) {
        if (setting == Settings.TRANSLATION_TARGET || setting == Settings.TRANSLATION_BRIGHTNESS) {
            return FeatureAvailability.translationAvailable()
                    && store.get(Settings.TRANSLATION_ENABLED);
        }
        if (setting == Settings.ALIGNED_PER_WORD_ROMAJI
                || setting == Settings.JAPANESE_READING_MODE
                || setting == Settings.CHINESE_MODE
                || setting == Settings.KOREAN_ROMANIZATION
                || setting == Settings.CHINESE_TONES
                || setting == Settings.CYRILLIC_MODE
                || setting == Settings.CYRILLIC_KEEP_SIGNS) {
            return FeatureAvailability.transliterationAvailable() && store.get(Settings.TRANSLITERATION_ENABLED);
        }
        if (setting == Settings.FORCE_DARK_BACKGROUND) {
            return store.get(Settings.ENABLE_BACKGROUND);
        }
        if (setting == Settings.LINE_SYNC_FILL) {
            return "Gradient wash".equals(store.get(Settings.ANIMATION_STYLE));
        }
        if (setting == Settings.LIVE_CARD_LINE_SYNC_FILL) {
            return "Karaoke fill".equals(store.get(Settings.LIVE_CARD_ANIMATION));
        }
        if (setting == Settings.LIVE_CARD_GLOW) {
            return !"Minimal".equals(store.get(Settings.LIVE_CARD_ANIMATION));
        }
        if (setting == Settings.LYRICS_TEXT_SIZE_CUSTOM) {
            return "custom".equals(store.get(Settings.LYRICS_TEXT_SIZE));
        }
        if (setting == Settings.LINE_SPACING_CUSTOM) {
            return "custom".equals(store.get(Settings.LINE_SPACING));
        }
        if (setting == Settings.LIVE_CARD_TEXT_SIZE_CUSTOM) {
            return "custom".equals(store.get(Settings.LIVE_CARD_TEXT_SIZE));
        }
        return true;
    }

    private boolean shouldRebuildAfterChange(Settings.Setting<?> setting) {
        return setting == Settings.UI_LANGUAGE
                || setting == Settings.TRANSLATION_ENABLED
                || setting == Settings.TRANSLITERATION_ENABLED
                || setting == Settings.ENABLE_BACKGROUND
                || setting == Settings.ANIMATION_STYLE
                || setting == Settings.LIVE_CARD_ANIMATION
                || setting == Settings.LYRICS_TEXT_SIZE
                || setting == Settings.LINE_SPACING
                || setting == Settings.LIVE_CARD_TEXT_SIZE;
    }

    private boolean unavailable(Settings.Setting<?> setting) {
        return (setting == Settings.TRANSLITERATION_ENABLED && !FeatureAvailability.transliterationAvailable())
                || (setting == Settings.TRANSLATION_ENABLED && !FeatureAvailability.translationAvailable())
                || (setting == Settings.LYRICS_FONT && !FeatureAvailability.appleFontAvailable());
    }

    private void renderActions(LinearLayout content) {
        actionRow(content, DiagnosticReportingDialog.reportProblemLabel(context, store),
                v -> DiagnosticReportingDialog.show(context, store));
        actionRow(content, uiStrings.get("settings_action_clear_translation_cache", "Clear translation cache"),
                v -> LyricCaches.clearGoogle(context));
        actionRow(content, uiStrings.get("settings_action_clear_lyrics_cache", "Clear lyrics response cache"),
                v -> LyricsResponseCache.clear(context));
        actionRow(content, uiStrings.get("settings_action_open_github", "Open GitHub"), v -> openGithub());
    }

    private void renderStatus(LinearLayout content) {
        CurrentLyricState s = CurrentLyricState.get();
        String summary = uiStrings.format("settings_status_summary", "Last state: %1$s\nTrack: %2$s\nLine: %3$s",
                s.status, s.title, s.originalLine);
        TextView state = text(summary, 12, COL_SUMMARY, false);
        state.setPadding(0, dp(4), 0, dp(2));
        content.addView(state);
        TextView version = text(BuildStamp.FULL, 11, COL_SECTION, false);
        version.setPadding(0, dp(12), 0, 0);
        content.addView(version);
    }

    private void renderDiagnostics(LinearLayout content) {
        LyricsFetchDiagnosticsState.Snapshot s = LyricsFetchDiagnosticsState.get();
        infoRow(content, uiStrings.get("settings_diagnostic_source_chosen", "Source chosen"), s.sourceChosen);
        infoRow(content, uiStrings.get("settings_diagnostic_candidates_seen", "Candidates seen"), s.candidatesSeen);
        infoRow(content, uiStrings.get("settings_diagnostic_type_chosen", "Type chosen"), s.typeChosen);
        infoRow(content, uiStrings.get("settings_diagnostic_spicy_version_sent", "Spicy version sent"), emptyDash(s.spicyVersionSent));
        infoRow(content, uiStrings.get("settings_diagnostic_spicy_latest_version", "Spicy latest version"), emptyDash(s.spicyLatestVersion));
        infoRow(content, uiStrings.get("settings_diagnostic_token_present", "Token present"), yesNo(s.tokenPresent));
        infoRow(content, uiStrings.get("settings_diagnostic_spicy_query_status", "Spicy query status"), s.spicyQueryStatus);
        infoRow(content, uiStrings.get("settings_diagnostic_packed_payload", "Packed payload"), yesNo(s.packedPayload));
        infoRow(content, uiStrings.get("settings_diagnostic_poison_result", "Poison result"), s.poisonResult);
        infoRow(content, uiStrings.get("settings_diagnostic_cache_write", "Cache write"), yesNo(s.cacheWrite));
    }

    // --- Rows ---

    private void sectionHeader(LinearLayout content, Settings.Section section, boolean expanded) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));

        TextView title = text(uiStrings.section(section), 14, COL_TITLE, true);
        title.setAllCaps(true);
        title.setLetterSpacing(0.05f);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = text(expanded ? "▾" : "▸", 16, COL_SECTION, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.setOnClickListener(v -> {
            if (expandedSections.contains(section.id)) expandedSections.remove(section.id);
            else expandedSections.add(section.id);
            rebuildSections();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        content.addView(row, lp);
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
        boolean unavailable = unavailable(setting);
        titleColumn(row, uiStrings.setting(setting), unavailable ? unavailableSummary() : null);
        GlossyToggle toggle = new GlossyToggle(context);
        toggle.setAccent(COL_ACCENT);
        toggle.setChecked(!unavailable && store.get(setting), false);
        toggle.setEnabled(!unavailable);
        row.setEnabled(!unavailable);
        row.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            toggle.setOnChangeListener(() -> {
                store.put(setting, toggle.isChecked());
                if (shouldRebuildAfterChange(setting)) rebuildSections();
            });
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
        }
        row.addView(toggle);
    }

    private void selectorRow(LinearLayout content, Settings.StringSetting setting) {
        selectorRow(content, setting, setting.allowedValues);
    }

    private void selectorRow(LinearLayout content, Settings.StringSetting setting, java.util.List<String> values) {
        LinearLayout row = newRow(content);
        boolean unavailable = unavailable(setting);
        TextView value = titleColumn(row, uiStrings.setting(setting),
                unavailable ? unavailableSummary() : labelFor(setting, store.get(setting)));
        if (!unavailable) value.setTextColor(COL_ACCENT);
        row.addView(text("›", 22, COL_SECTION, false));
        row.setEnabled(!unavailable);
        if (!unavailable) row.setOnClickListener(v -> showSelectorDialog(setting, values, value));
    }

    private void stepperRow(LinearLayout content, Settings.IntegerSetting setting) {
        LinearLayout row = newRow(content);
        titleColumn(row, uiStrings.setting(setting), stepperSummary(setting));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = stepButton("-");
        TextView value = text(formatStepper(setting, store.get(setting)), 15, COL_ACCENT, true);
        value.setGravity(Gravity.CENTER);
        TextView plus = stepButton("+");

        controls.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(dp(74), dp(36));
        valueLp.leftMargin = dp(4);
        valueLp.rightMargin = dp(4);
        controls.addView(value, valueLp);
        controls.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(controls);

        final int[] pending = new int[]{store.get(setting)};
        final Runnable commit = () -> store.put(setting, pending[0]);
        attachStepperTouch(minus, setting, value, -setting.stepValue, pending, commit);
        attachStepperTouch(plus, setting, value, setting.stepValue, pending, commit);
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

    /**
     * Steppers track the pending value locally and only commit to the store ~250ms after the last
     * tick: expensive consumers (text size / spacing trigger a full lyric rebuild) would otherwise
     * lag the press-and-hold ramp. The label updates instantly, the rerender waits for settle.
     */
    private void adjustStepper(Settings.IntegerSetting setting, TextView valueView, int delta,
                               int[] pending, Runnable commit) {
        int next = Math.max(setting.minValue, Math.min(setting.maxValue, pending[0] + delta));
        pending[0] = next;
        valueView.setText(formatStepper(setting, next));
        valueView.removeCallbacks(commit);
        valueView.postDelayed(commit, 250L);
    }

    private void attachStepperTouch(TextView button, Settings.IntegerSetting setting, TextView valueView, int delta,
                                    int[] pending, Runnable commit) {
        final int[] repeatCount = new int[]{0};
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            adjustStepper(setting, valueView, delta, pending, commit);
            repeatCount[0]++;
            long delayMs = Math.max(45L, 130L - repeatCount[0] * 8L);
            button.postDelayed(repeat[0], delayMs);
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    // Without this the surrounding ScrollView intercepts on the slightest finger
                    // drift and cancels the hold, so press-and-hold repeat never ramps.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    repeatCount[0] = 0;
                    adjustStepper(setting, valueView, delta, pending, commit);
                    v.removeCallbacks(repeat[0]);
                    v.postDelayed(repeat[0], 360L);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    v.setPressed(false);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void showSelectorDialog(Settings.StringSetting setting, java.util.List<String> values, TextView valueView) {
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

        TextView title = text(uiStrings.setting(setting), 19, COL_TITLE, true);
        title.setPadding(dp(22), 0, dp(22), dp(12));
        box.addView(title);

        String current = store.get(setting);
        for (final String val : values) {
            box.addView(selectorOptionRow(setting, val, val.equals(current), valueView, dialog));
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
            window.setLayout(w, values.size() > 8 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private LinearLayout selectorOptionRow(Settings.StringSetting setting, String value, boolean selected,
                                           TextView valueView, Dialog dialog) {
        String unavailableReason = optionUnavailableReason(setting, value);
        boolean unavailable = !unavailableReason.isEmpty();
        LinearLayout optRow = new LinearLayout(context);
        optRow.setOrientation(LinearLayout.HORIZONTAL);
        optRow.setGravity(Gravity.CENTER_VERTICAL);
        optRow.setMinimumHeight(dp(52));
        optRow.setPadding(dp(22), dp(8), dp(22), dp(8));
        optRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));

        TextView dot = text(selected ? "●" : "○", 15, selected ? COL_ACCENT : COL_SECTION, false);
        dot.setPadding(0, 0, dp(16), 0);
        optRow.addView(dot);
        String optionLabel = labelFor(setting, value);
        if (unavailable) optionLabel = optionLabel + "  · " + unavailableReason;
        TextView label = text(optionLabel, 16, selected ? COL_ACCENT : COL_TITLE, false);
        optRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String preview = optionPreview(setting, value);
        if (!preview.isEmpty()) {
            TextView pv = text(preview, 18, selected ? COL_ACCENT : COL_TITLE, false);
            pv.setPadding(dp(12), 0, 0, 0);
            optRow.addView(pv);
        }
        optRow.setEnabled(!unavailable);
        optRow.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            optRow.setOnClickListener(v -> {
                store.put(setting, value);
                if (setting == Settings.UI_LANGUAGE) {
                    uiStrings = new SettingsUiStrings(context, value);
                    if (panelTitle != null) panelTitle.setText(uiStrings.appName());
                }
                valueView.setText(labelFor(setting, value));
                dialog.dismiss();
                if (shouldRebuildAfterChange(setting)) rebuildSections();
            });
        }
        return optRow;
    }

    private String optionUnavailableReason(Settings.StringSetting setting, String value) {
        if (setting != Settings.LIVE_CARD_SECONDARY_MODE) return "";
        boolean needsTransliteration = "Transliteration".equals(value) || "Both".equals(value);
        boolean needsTranslation = "Translation".equals(value) || "Both".equals(value);
        if (needsTransliteration && !FeatureAvailability.transliterationAvailable()) {
            return unavailableSummary();
        }
        if (needsTranslation && !FeatureAvailability.translationAvailable()) {
            return unavailableSummary();
        }
        if (needsTransliteration && !store.get(Settings.TRANSLITERATION_ENABLED)) {
            return uiStrings.get("settings_enable_transliteration", "Enable transliteration");
        }
        if (needsTranslation && !store.get(Settings.TRANSLATION_ENABLED)) {
            return uiStrings.get("settings_enable_translation", "Enable translation");
        }
        return "";
    }

    private void textFieldRow(LinearLayout content, Settings.StringSetting setting) {
        LinearLayout row = newRow(content);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.addView(text(uiStrings.setting(setting), 14, COL_SUMMARY, false));
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

    private void infoRow(LinearLayout content, String label, String value) {
        LinearLayout row = newRow(content);
        row.addView(text(label, 14, COL_SUMMARY, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = text(value == null ? "" : value, 14, COL_TITLE, false);
        val.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private String yesNo(boolean value) {
        return value
                ? uiStrings.get("settings_yes", "yes")
                : uiStrings.get("settings_no", "no");
    }

    private static String emptyDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private void openGithub() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amarinne/spicy-ex"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static String optionPreview(Settings.StringSetting setting, String value) {
        if ("lyric_interlude_icon".equals(setting.key)) {
            if ("dots".equals(value)) return "• • •";
            if ("note".equals(value)) return "♪";
        }
        return "";
    }

    /** Option label; magnitude-based selectors show the plain multiplier ("\u00d71.5") as the label. */
    private String labelFor(Settings.StringSetting setting, String value) {
        String mult = multiplierFor(setting.key, value);
        if (mult != null) return "\u00d7" + mult;
        return uiStrings.option(setting, value);
    }

    // Mirror of LyricsShellSettings.lineSpacingMultiplier() / lyricsTextSizeMultiplier() — display only.
    private static String multiplierFor(String key, String value) {
        if ("line_spacing".equals(key)) {
            switch (value) {
                case "compact": return "0.8";
                case "default": return "1.1";
                case "spacious": return "1.5";
                case "more": return "2.0";
                case "max": return "2.5";
                default: return null;
            }
        }
        if ("lyrics_text_size".equals(key) || "lyrics_live_card_text_size".equals(key)) {
            switch (value) {
                case "small":
                case "normal":
                case "large":
                case "xlarge":
                    return SettingsValueNormalizer.textSizeMultiplierLabel(value);
                default: return null;
            }
        }
        return null;
    }

    private String stepperSummary(Settings.IntegerSetting setting) {
        if (setting == Settings.SYNC_OFFSET_MS) {
            return uiStrings.get("settings_sync_offset_summary", "Positive shows lyrics earlier");
        }
        return null;
    }

    private static String formatStepper(Settings.IntegerSetting setting, int value) {
        if (setting == Settings.LYRICS_TEXT_SIZE_CUSTOM || setting == Settings.LINE_SPACING_CUSTOM
                || setting == Settings.LIVE_CARD_TEXT_SIZE_CUSTOM) {
            return String.format(java.util.Locale.US, "×%.2f", value / 100f);
        }
        return formatOffset(value);
    }

    private static String formatOffset(int offsetMs) {
        if (offsetMs == 0) return "0.0s";
        return String.format(java.util.Locale.US, "%+.1fs", offsetMs / 1000f);
    }

    private String unavailableSummary() {
        return uiStrings.get("settings_unavailable_full_build", "Full build required");
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
