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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.diagnostics.DiagnosticReportingDialog;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.LyricsBackgroundStyle;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.Motion;

import java.util.HashMap;
import java.util.Map;

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

    private static final String TAG_HEADER_PREFIX = "hdr:";
    private static final String TAG_CARD_PREFIX = "card:";

    /** Leading icon per collapsible section (artifacts/settings-icon-plan.md §4). */
    private static final Map<String, Kind> SECTION_ICONS = new HashMap<>();

    static {
        SECTION_ICONS.put("lyrics", Kind.AUDIO_LINES);
        SECTION_ICONS.put("transliteration", Kind.BOOK_OPEN_TEXT);
        SECTION_ICONS.put("translation", Kind.LANGUAGES);
        SECTION_ICONS.put("now_playing", Kind.DISC_3);
        SECTION_ICONS.put("lyrics_screen", Kind.FULLSCREEN);
        SECTION_ICONS.put("ai", Kind.SPARKLES);
        SECTION_ICONS.put("debug", Kind.ACTIVITY);
    }

    /** Recommended-tier leading icons per setting row; absent keys stay text-only. */
    private static final Map<String, Object> ROW_LEADS = new HashMap<>();

    static {
        ROW_LEADS.put("settings_ui_language", Kind.GLOBE);
        ROW_LEADS.put("lyric_sync_offset_ms", Kind.TIMER);
        ROW_LEADS.put("lyrics_live_card_weight", Kind.BOLD);
        ROW_LEADS.put("lyrics_weight", Kind.BOLD);
        ROW_LEADS.put("lyrics_live_card_text_size", Kind.A_LARGE_SMALL);
        ROW_LEADS.put("lyrics_text_size", Kind.A_LARGE_SMALL);
        ROW_LEADS.put("line_spacing", Kind.ALIGN_VERTICAL_DISTRIBUTE_CENTER);
        ROW_LEADS.put("lyrics_font", Kind.TYPE);
        ROW_LEADS.put("lyric_interlude_icon", Kind.ELLIPSIS);
        ROW_LEADS.put("lyrics_translation_enabled", Kind.LANGUAGES);
        ROW_LEADS.put("lyrics_translation_target", Kind.ARROW_RIGHT_LEFT);
        // Reading rows reuse the translit chip's script glyphs (GlyphIconDrawable).
        ROW_LEADS.put("lyrics_japanese_reading_mode", "あ");
        ROW_LEADS.put("lyrics_chinese_mode", "拼");
        ROW_LEADS.put("lyrics_korean_romanization", "한");
        ROW_LEADS.put("lyrics_cyrillic_mode", "Я");
    }

    private final Context context;
    private final SettingsStore store;
    private final java.util.function.BooleanSupplier isHalfSize;
    private final Runnable onToggleSize;
    private final Runnable onClose;
    private final java.util.function.Consumer<CacheClearKind> onClearCache;
    // Static: survives panel re-opens within the process, so the panel never re-opens fully collapsed.
    private static final java.util.Set<String> expandedSections = new java.util.HashSet<>();
    private LinearLayout sectionsContainer;
    private TextView panelTitle;
    private SettingsUiStrings uiStrings;
    private AiSettingsRows aiSettingsRows;
    private android.widget.ScrollView scrollRoot;
    private ImageView aiBadgeView;

    public SettingsPanel(Context context, SettingsStore store, java.util.function.BooleanSupplier isHalfSize,
                         Runnable onToggleSize, Runnable onClose,
                         java.util.function.Consumer<CacheClearKind> onClearCache) {
        this.context = context;
        this.store = store;
        this.isHalfSize = isHalfSize;
        this.onToggleSize = onToggleSize;
        this.onClose = onClose;
        this.onClearCache = onClearCache;
        if (!store.contains(Settings.BACKGROUND_STYLE)) {
            store.put(Settings.BACKGROUND_STYLE, store.get(Settings.ENABLE_BACKGROUND)
                    ? LyricsBackgroundStyle.ANIMATED_TEXTURE
                    : LyricsBackgroundStyle.GRADIENT);
        }
        String storedLanguage = store.get(Settings.UI_LANGUAGE);
        this.uiStrings = new SettingsUiStrings(context, storedLanguage);
        if (!uiStrings.selectedLanguage().equals(storedLanguage)) {
            store.put(Settings.UI_LANGUAGE, uiStrings.selectedLanguage());
        }
    }

    /** Builds the card view; the host sizes/centers it. */
    public View build() {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(COL_CARD);
        cardBg.setCornerRadius(dp(26));
        cardBg.setStroke(dp(1), COL_CARD_BORDER);
        scroll.setBackground(cardBg);
        scroll.setClipToOutline(true);
        scrollRoot = scroll;

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
            // Chevrons up/down = collapse toward the top-anchored half panel; down/up = grow.
            ImageButton resize = headerIconButton(resizeKind(),
                    uiStrings.get("settings_panel_resize", "Resize settings panel"), v -> {
                onToggleSize.run();
                ((ImageButton) v).setImageDrawable(
                        new ActionIconDrawable(resizeKind(), COL_SUMMARY, density()));
            });
            header.addView(resize);
        }
        if (onClose != null) {
            header.addView(headerIconButton(ActionIconDrawable.Kind.CLOSE,
                    uiStrings.get("settings_panel_close", "Close settings panel"),
                    v -> onClose.run()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        content.addView(header, lp);
    }

    private ActionIconDrawable.Kind resizeKind() {
        return isHalfSize != null && isHalfSize.getAsBoolean()
                ? ActionIconDrawable.Kind.CHEVRONS_DOWN_UP
                : ActionIconDrawable.Kind.CHEVRONS_UP_DOWN;
    }

    private float density() {
        return context.getResources().getDisplayMetrics().density;
    }

    private ImageButton headerIconButton(ActionIconDrawable.Kind icon, String contentDescription,
                                         View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setImageDrawable(new ActionIconDrawable(icon, COL_SUMMARY,
                context.getResources().getDisplayMetrics().density));
        button.setContentDescription(contentDescription);
        button.setTooltipText(contentDescription);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x0DFFFFFF);
        circle.setStroke(Math.max(1, dp(1)), 0x30FFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x24FFFFFF), circle, mask));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    private void renderSections(LinearLayout content) {
        if (aiAvailable()) aiRows().ensureInitialModelCheck();
        for (java.util.Map.Entry<Settings.Section, java.util.List<Settings.Setting<?>>> entry
                : groupVisibleSettings().entrySet()) {
            renderSectionGroup(content, entry.getKey(), entry.getValue());
        }
        renderDebugGroup(content);
    }

    private java.util.LinkedHashMap<Settings.Section, java.util.List<Settings.Setting<?>>>
            groupVisibleSettings() {
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
        return grouped;
    }

    private void renderSectionGroup(LinearLayout content, Settings.Section section,
                                    java.util.List<Settings.Setting<?>> items) {
        appendSectionHeader(content, section, expandedSections.contains(section.id), -1);
        if (!expandedSections.contains(section.id)) return;
        // The AI section's remaining rows are not settings: a key that must not persist as it
        // is typed, and a model list that has to be fetched before it can be offered.
        appendSectionCard(content, section, items, -1);
    }

    private void renderDebugGroup(LinearLayout content) {
        appendSectionHeader(content, Settings.DEBUG, expandedSections.contains(Settings.DEBUG.id), -1);
        if (!expandedSections.contains(Settings.DEBUG.id)) return;
        appendDebugCard(content, -1);
    }

    /** Card for a settings section; AI gets its non-setting rows appended after the settings. */
    private void appendSectionCard(LinearLayout parent, Settings.Section section,
                                   java.util.List<Settings.Setting<?>> items, int at) {
        LinearLayout card = newCard();
        card.setTag(TAG_CARD_PREFIX + section.id);
        for (Settings.Setting<?> setting : items) renderSetting(card, setting);
        if (section == Settings.AI && aiAvailable()) aiRows().render(card);
        attachCard(parent, card, at);
    }

    private void appendDebugCard(LinearLayout parent, int at) {
        LinearLayout card = newCard();
        card.setTag(TAG_CARD_PREFIX + Settings.DEBUG.id);
        renderActions(card);
        renderStatus(card);
        renderDiagnostics(card);
        attachCard(parent, card, at);
    }

    /** Rounded container that visually groups an expanded section's rows. */
    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0DFFFFFF);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(2), dp(10), dp(2));
        return card;
    }

    private void attachCard(LinearLayout parent, LinearLayout card, int at) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        if (at < 0 || at >= parent.getChildCount()) parent.addView(card, lp);
        else parent.addView(card, at, lp);
    }

    private int indexOfChildByTag(String tag) {
        for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
            Object t = sectionsContainer.getChildAt(i).getTag();
            if (tag.equals(t)) return i;
        }
        return -1;
    }

    // --- Anchor-preserving rebuilds ---
    // scrollY alone orphans the reader's anchor when a section above folds; anchor on the
    // first header/card boundary visible at viewport top instead.

    private String anchorTag;
    private int anchorDelta;

    private void captureAnchor() {
        anchorTag = null;
        anchorDelta = 0;
        if (scrollRoot == null || sectionsContainer == null) return;
        int scrollY = scrollRoot.getScrollY();
        int bottom = scrollY + Math.max(1, scrollRoot.getHeight());
        int base = sectionsContainer.getTop();
        for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
            View child = sectionsContainer.getChildAt(i);
            if (!(child.getTag() instanceof String)) continue;
            int absTop = base + child.getTop();
            if (absTop >= scrollY && absTop < bottom) {
                anchorTag = (String) child.getTag();
                anchorDelta = absTop - scrollY;
                return;
            }
        }
        // Nothing starts inside the viewport: anchor on the last boundary above it.
        for (int i = sectionsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = sectionsContainer.getChildAt(i);
            if (!(child.getTag() instanceof String)) continue;
            int absTop = base + child.getTop();
            if (absTop <= scrollY) {
                anchorTag = (String) child.getTag();
                anchorDelta = absTop - scrollY; // ≤ 0
                return;
            }
        }
    }

    private void restoreAnchor() {
        restoreAnchorAndFallback();
    }

    private void restoreAnchorAndFallback() {
        if (scrollRoot == null || sectionsContainer == null || anchorTag == null) return;
        final String tag = anchorTag;
        final int delta = anchorDelta;
        scrollRoot.post(() -> {
            for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
                View child = sectionsContainer.getChildAt(i);
                if (!tag.equals(child.getTag())) continue;
                scrollRoot.scrollTo(0,
                        Math.max(0, sectionsContainer.getTop() + child.getTop() - delta));
                return;
            }
        });
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

    /**
     * True when the AI star should be lit: the whole family is configured and could run now.
     *
     * <p>Falls back to the enable flag only before the AI rows exist, which is the one moment
     * nothing can be asked about credentials.
     */
    private boolean aiReady() {
        return aiSettingsRows != null ? aiSettingsRows.isReady()
                : Boolean.TRUE.equals(store.get(Settings.AI_ENABLED));
    }

    private void rebuildSections() {
        if (sectionsContainer == null) return;
        captureAnchor();
        aiBadgeView = null;
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
        restoreAnchor();
    }

    /**
     * Re-renders one section (header + card) in place instead of tearing down the whole panel.
     * Gating toggles, selector picks and section folds land here; UI_LANGUAGE still takes the
     * full path because every label changes. Anchor-preserving either way.
     */
    private void rebuildSection(Settings.Section target) {
        if (sectionsContainer == null) return;
        int headerIdx = indexOfChildByTag(TAG_HEADER_PREFIX + target.id);
        if (headerIdx < 0) {
            rebuildSections();
            return;
        }
        captureAnchor();
        int cardIdx = indexOfChildByTag(TAG_CARD_PREFIX + target.id);
        boolean expanded = expandedSections.contains(target.id);
        if (!expanded && (TAG_CARD_PREFIX + target.id).equals(anchorTag)) {
            retargetAnchorToHeader(target);
        }
        // The card sits directly after its header, so one removal shifts the other onto headerIdx.
        sectionsContainer.removeViewAt(headerIdx);
        if (cardIdx >= 0) sectionsContainer.removeViewAt(headerIdx);
        if (target == Settings.AI) aiBadgeView = null;
        appendSectionHeader(sectionsContainer, target, expanded, headerIdx);
        if (expanded) {
            if (target == Settings.DEBUG) {
                appendDebugCard(sectionsContainer, headerIdx + 1);
            } else {
                java.util.List<Settings.Setting<?>> items = groupVisibleSettings().get(target);
                if (items == null || items.isEmpty()) {
                    rebuildSections(); // defensive: rendered section without visible settings
                    return;
                }
                appendSectionCard(sectionsContainer, target, items, headerIdx + 1);
            }
        }
        restoreAnchor();
    }

    private void retargetAnchorToHeader(Settings.Section target) {
        int headerIdx = indexOfChildByTag(TAG_HEADER_PREFIX + target.id);
        if (headerIdx < 0 || scrollRoot == null) return;
        View header = sectionsContainer.getChildAt(headerIdx);
        anchorTag = TAG_HEADER_PREFIX + target.id;
        anchorDelta = sectionsContainer.getTop() + header.getTop() - scrollRoot.getScrollY();
    }

    private boolean shouldRender(Settings.Setting<?> setting) {
        if (setting == Settings.AI_ENABLED) return aiAvailable();
        if (setting == Settings.AI_DEEPSEEK_REASONING) {
            return AiSettings.PROVIDER_DEEPSEEK.equals(store.get(Settings.AI_PROVIDER))
                    && aiSettingVisible(setting, Boolean.TRUE.equals(store.get(Settings.AI_ENABLED)));
        }
        if (setting.section == Settings.AI) {
            return aiSettingVisible(setting, Boolean.TRUE.equals(store.get(Settings.AI_ENABLED)));
        }
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
            return FeatureAvailability.animatedBackgroundAvailable()
                    && LyricsBackgroundStyle.usesTexture(store.get(Settings.BACKGROUND_STYLE));
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

    /** Keep AI master switch visible; nest every other AI setting under that switch. */
    static boolean aiSettingVisible(Settings.Setting<?> setting, boolean enabled) {
        return setting == Settings.AI_ENABLED || enabled;
    }

    /** UI language rebuilds every label; dependency settings rebuild only their own section. */
    private void onSettingChanged(Settings.Setting<?> setting) {
        if (setting == Settings.UI_LANGUAGE) {
            rebuildSections();
        } else if (shouldRebuildSectionAfterChange(setting)) {
            rebuildSection(setting.section);
        }
    }

    private static boolean shouldRebuildSectionAfterChange(Settings.Setting<?> setting) {
        return setting == Settings.AI_ENABLED
                || setting == Settings.AI_PROVIDER
                || setting == Settings.TRANSLATION_ENABLED
                || setting == Settings.TRANSLITERATION_ENABLED
                || setting == Settings.BACKGROUND_STYLE
                || setting == Settings.ANIMATION_STYLE
                || setting == Settings.LIVE_CARD_ANIMATION
                || setting == Settings.LYRICS_TEXT_SIZE
                || setting == Settings.LINE_SPACING
                || setting == Settings.LIVE_CARD_TEXT_SIZE;
    }

    /**
     * Full-only. AI needs no on-device language packages, so nothing stops it running in Lite —
     * which is exactly the problem: Lite would gain translation while its own capability flag says
     * it has none. Until that flag is untangled, the family is not offered there.
     */
    private static boolean aiAvailable() {
        return FeatureAvailability.translationAvailable();
    }

    private boolean unavailable(Settings.Setting<?> setting) {
        return (setting == Settings.TRANSLITERATION_ENABLED && !FeatureAvailability.transliterationAvailable())
                || (setting == Settings.TRANSLATION_ENABLED && !FeatureAvailability.translationAvailable())
                || (setting == Settings.LYRICS_FONT && !FeatureAvailability.appleFontAvailable());
    }

    // --- Icon helpers ---

    /** Tinted lucide icon view; decorative by default (row text carries the meaning). */
    private ImageView kindView(Kind kind, int color, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new ActionIconDrawable(kind, color, density()));
        int pad = dp(Math.max(2, 22 - sizeDp) / 4);
        view.setPadding(pad, pad, pad, pad);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private LinearLayout.LayoutParams leadParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.rightMargin = dp(10);
        return lp;
    }

    /** Inserts the row's leading icon at index 0 when one is mapped for this setting. */
    private void applyRowLead(LinearLayout row, String key) {
        Object lead = ROW_LEADS.get(key);
        if (lead == null) return;
        View iconView;
        if (lead instanceof Kind) {
            iconView = kindView((Kind) lead, COL_SECTION, 19);
        } else {
            ImageView glyph = new ImageView(context);
            glyph.setImageDrawable(new GlyphIconDrawable(
                    (String) lead, android.graphics.Typeface.DEFAULT_BOLD));
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconView = glyph;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(38));
        lp.rightMargin = dp(8);
        row.addView(iconView, 0, lp);
    }

    private void renderActions(LinearLayout content) {
        actionRow(content, Kind.BUG,
                DiagnosticReportingDialog.reportProblemLabel(context, store),
                v -> DiagnosticReportingDialog.show(context, store));
        actionRow(content, null, uiStrings.get("settings_action_clear_translation_cache", "Clear translation cache"),
                v -> clearCache(CacheClearKind.TRANSLATION));
        actionRow(content, null, uiStrings.get("settings_action_clear_reading_cache", "Clear transliteration cache"),
                v -> clearCache(CacheClearKind.TRANSLITERATION));
        actionRow(content, null, uiStrings.get("settings_action_clear_ai_cache", "Clear AI results"),
                v -> clearCache(CacheClearKind.AI));
        actionRow(content, null, uiStrings.get("settings_action_clear_lyrics_cache", "Clear lyrics response cache"),
                v -> clearCache(CacheClearKind.LYRICS_RESPONSE));
        actionRow(content, Kind.EXTERNAL_LINK, uiStrings.get("settings_action_open_github", "Open GitHub"), v -> openGithub());
    }

    private void clearCache(CacheClearKind kind) {
        if (onClearCache != null) onClearCache.accept(kind);
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

    private LinearLayout buildSectionHeader(Settings.Section section, boolean expanded) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        row.setTag(TAG_HEADER_PREFIX + section.id);

        Kind sectionIcon = SECTION_ICONS.get(section.id);
        if (sectionIcon != null) {
            ImageView sectionIconView = kindView(sectionIcon,
                    section == Settings.AI && aiReady() ? COL_ACCENT : COL_SECTION, 18);
            if (section == Settings.AI) aiBadgeView = sectionIconView;
            row.addView(sectionIconView, leadParams());
        }

        TextView title = text(uiStrings.section(section), 14, COL_TITLE, true);
        title.setAllCaps(true);
        title.setLetterSpacing(0.05f);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(kindView(expanded ? Kind.CHEVRON_DOWN : Kind.CHEVRON_RIGHT, COL_SECTION, 16),
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.setOnClickListener(v -> {
            boolean nowExpanded = !expandedSections.contains(section.id);
            if (nowExpanded) expandedSections.add(section.id);
            else expandedSections.remove(section.id);
            rebuildSection(section);
        });
        return row;
    }

    private void sectionHeader(LinearLayout content, Settings.Section section, boolean expanded) {
        appendSectionHeader(content, section, expanded, -1);
    }

    private void appendSectionHeader(LinearLayout parent, Settings.Section section,
                                     boolean expanded, int at) {
        View row = buildSectionHeader(section, expanded);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        if (at < 0 || at >= parent.getChildCount()) parent.addView(row, lp);
        else parent.addView(row, at, lp);
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
        titleColumn(row, uiStrings.setting(setting), unavailable ? unavailableSummary(setting) : null);
        applyRowLead(row, setting.key);
        GlossyToggle toggle = new GlossyToggle(context);
        toggle.setAccent(COL_ACCENT);
        toggle.setChecked(!unavailable && store.get(setting), false);
        toggle.setEnabled(!unavailable);
        row.setEnabled(!unavailable);
        row.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            toggle.setOnChangeListener(() -> {
                store.put(setting, toggle.isChecked());
                onSettingChanged(setting);
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
                unavailable ? unavailableSummary(setting) : labelFor(setting, store.get(setting)));
        applyRowLead(row, setting.key);
        if (!unavailable) value.setTextColor(COL_ACCENT);
        row.addView(kindView(Kind.CHEVRON_RIGHT, COL_SECTION, 18),
                new LinearLayout.LayoutParams(dp(24), dp(30)));
        row.setEnabled(!unavailable);
        if (!unavailable) row.setOnClickListener(v -> showSelectorDialog(setting, values, value));
    }

    private void stepperRow(LinearLayout content, Settings.IntegerSetting setting) {
        LinearLayout row = newRow(content);
        titleColumn(row, uiStrings.setting(setting), stepperSummary(setting));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        View minus = stepButton(false, setting);
        TextView value = text(formatStepper(setting, store.get(setting)), 15, COL_ACCENT, true);
        value.setGravity(Gravity.CENTER);
        View plus = stepButton(true, setting);

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

    /** Pill button holding a lucide minus/plus; returns View — touch logic is view-agnostic. */
    private View stepButton(boolean plus, Settings.IntegerSetting setting) {
        ImageButton glyph = new ImageButton(context);
        glyph.setImageDrawable(new ActionIconDrawable(plus ? Kind.PLUS : Kind.MINUS,
                COL_TITLE, density()));
        int pad = dp(8);
        glyph.setPadding(pad, pad, pad, pad);
        String description = uiStrings.format(
                plus ? "settings_stepper_increase" : "settings_stepper_decrease",
                plus ? "Increase %1$s" : "Decrease %1$s", uiStrings.setting(setting));
        glyph.setContentDescription(description);
        glyph.setTooltipText(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), COL_CARD_BORDER);
        glyph.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return glyph;
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

    private void attachStepperTouch(View button, Settings.IntegerSetting setting, TextView valueView, int delta,
                                    int[] pending, Runnable commit) {
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
                    v.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
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
        final boolean deferCommit = setting == Settings.TRANSLATION_TARGET;
        final String initialValue = store.get(setting);
        final String[] pendingValue = new String[]{initialValue};

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

        for (final String val : values) {
            box.addView(selectorOptionRow(setting, val, val.equals(initialValue), valueView,
                    dialog, box, deferCommit, pendingValue));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box);
        dialog.setContentView(scroll);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                Motion.exitCardThen(box, dialog::isShowing, dialog::dismiss);
                return true;
            }
            return false;
        });
        if (deferCommit) {
            dialog.setOnDismissListener(d -> {
                String selected = pendingValue[0];
                if (selected == null || selected.equals(initialValue)) return;
                store.put(setting, selected);
                valueView.setText(labelFor(setting, selected));
                onSettingChanged(setting);
            });
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.55f);
            int w = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.82f);
            int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.7f);
            window.setLayout(w, values.size() > 8 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        Motion.enterCard(box);
    }

    private LinearLayout selectorOptionRow(Settings.StringSetting setting, String value, boolean selected,
                                           TextView valueView, Dialog dialog, LinearLayout card,
                                           boolean deferCommit, String[] pendingValue) {
        String unavailableReason = optionUnavailableReason(setting, value);
        boolean unavailable = !unavailableReason.isEmpty();
        LinearLayout optRow = new LinearLayout(context);
        optRow.setOrientation(LinearLayout.HORIZONTAL);
        optRow.setGravity(Gravity.CENTER_VERTICAL);
        optRow.setMinimumHeight(dp(52));
        optRow.setPadding(dp(22), dp(8), dp(22), dp(8));
        optRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));

        ImageView dot = new ImageView(context);
        dot.setImageDrawable(new ActionIconDrawable(Kind.CIRCLE,
                selected ? COL_ACCENT : COL_SECTION, density(), selected));
        dot.setPadding(dp(4), dp(4), dp(4), dp(4));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        dotLp.rightMargin = dp(12);
        optRow.addView(dot, dotLp);
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
        optRow.setSelected(selected);
        optRow.setFocusable(true);
        optRow.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            optRow.setOnClickListener(v -> {
                if (deferCommit) {
                    pendingValue[0] = value;
                    Motion.exitCardThen(card, dialog::isShowing, dialog::dismiss);
                    return;
                }
                store.put(setting, value);
                if (setting == Settings.UI_LANGUAGE) {
                    uiStrings = new SettingsUiStrings(context, value);
                    if (panelTitle != null) panelTitle.setText(uiStrings.appName());
                }
                valueView.setText(labelFor(setting, value));
                Motion.exitCardThen(card, dialog::isShowing, () -> {
                    dialog.dismiss();
                    onSettingChanged(setting);
                });
            });
        }
        return optRow;
    }

    private String optionUnavailableReason(Settings.StringSetting setting, String value) {
        if (setting == Settings.BACKGROUND_STYLE
                && LyricsBackgroundStyle.usesTexture(value)
                && !FeatureAvailability.animatedBackgroundAvailable()) {
            return uiStrings.get("settings_unavailable_android_13", "Android 13+ required");
        }
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

    private void actionRow(LinearLayout content, Kind lead, String label, View.OnClickListener listener) {
        LinearLayout row = newRow(content);
        if (lead != null) row.addView(kindView(lead, COL_ACCENT, 19), leadParams());
        row.addView(text(label, 16, COL_ACCENT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
    }

    /** Adapter giving the AI rows the panel's own row vocabulary, so they look like every other row. */
    private AiSettingsRows aiRows() {
        if (aiSettingsRows != null) return aiSettingsRows;
        aiSettingsRows = new AiSettingsRows(context, new AiSettingsRows.Host() {
            @Override public void info(LinearLayout content, String label, String value) {
                infoRow(content, label, value);
            }

            @Override public void field(LinearLayout content, String label, String value,
                                        View.OnClickListener listener,
                                        AiSettingsRows.IconAction... actions) {
                aiFieldRow(content, label, value, false, listener, actions);
            }

            @Override public void selector(LinearLayout content, String label, String value,
                                           View.OnClickListener listener,
                                           AiSettingsRows.IconAction... actions) {
                aiFieldRow(content, label, value, true, listener, actions);
            }

            @Override public void rebuild() {
                rebuildSection(Settings.AI);
            }

            @Override public void updateAiBadge(boolean live) {
                // The probe outcome is not what the star reports; setup completeness is. This is
                // only the signal that something about the AI configuration may have moved.
                if (aiBadgeView == null) {
                    rebuildSection(Settings.AI);
                    return;
                }
                aiBadgeView.setImageDrawable(new ActionIconDrawable(Kind.SPARKLES,
                        aiReady() ? COL_ACCENT : COL_SECTION, density()));
            }

            @Override public String string(String name, String fallback) {
                return uiStrings.get(name, fallback);
            }
        }, store);
        return aiSettingsRows;
    }

    private void aiFieldRow(LinearLayout content, String label, String value, boolean selector,
                            View.OnClickListener listener, AiSettingsRows.IconAction... actions) {
        LinearLayout row = newRow(content);
        TextView summary = titleColumn(row, label, value);
        summary.setTextColor(COL_ACCENT);
        if (actions != null) {
            for (AiSettingsRows.IconAction action : actions) {
                if (action == null) continue;
                row.addView(aiIconButton(action));
            }
        }
        if (selector) {
            ImageView arrow = new ImageView(context);
            arrow.setPadding(dp(6), dp(6), dp(6), dp(6));
            arrow.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.CHEVRON_RIGHT,
                    COL_SECTION, context.getResources().getDisplayMetrics().density));
            row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(36)));
        }
        row.setOnClickListener(listener);
    }

    private ImageButton aiIconButton(AiSettingsRows.IconAction action) {
        ImageButton button = new ImageButton(context);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setImageDrawable(new ActionIconDrawable(action.icon, COL_TITLE,
                context.getResources().getDisplayMetrics().density));
        button.setContentDescription(action.contentDescription);
        button.setTooltipText(action.contentDescription);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(0xFFFFFFFF)));
        button.setOnClickListener(action.listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(2);
        button.setLayoutParams(lp);
        return button;
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

    /**
     * Why this row is greyed out. Most unavailability is a Lite-build gap, but the animated
     * background is blocked by the device's API level instead — telling that user to install the
     * full build would send them after a download that cannot fix it.
     */
    private String unavailableSummary(Settings.Setting<?> setting) {
        return unavailableSummary();
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
