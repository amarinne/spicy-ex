package com.eza.spicyex;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.diagnostics.DiagnosticReportingDialog;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.CacheStoragePolicy;
import com.eza.spicyex.lyrics.LanguageModelPack;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.SpicyManualTokenStore;
import com.eza.spicyex.settings.PanelDialogs;
import com.eza.spicyex.settings.PanelPolicy;
import com.eza.spicyex.settings.PanelSnapshot;
import com.eza.spicyex.settings.PanelStrings;
import com.eza.spicyex.settings.PanelStyle;
import com.eza.spicyex.settings.PanelTags;
import com.eza.spicyex.settings.RowSyncPlan;
import com.eza.spicyex.settings.SettingLabels;
import com.eza.spicyex.settings.SettingRowFactory;
import com.eza.spicyex.settings.SettingUiSpec;
import com.eza.spicyex.settings.SettingsUiSchema;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.settings.SourceOrderEditor;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.PanelDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Coordinator for the in-Spotify settings panel.
 *
 * <p>Owns only what is genuinely panel-wide: the scrolling card, the section list, one applied
 * state snapshot per render pass, anchor-preserving rebuilds, and the dispatch of each setting
 * to its renderer. Everything with a narrower responsibility lives in an owner:
 *
 * <ul>
 * <li>{@link PanelStyle} — colours, density, and every shared view construction.</li>
 * <li>{@link SettingRowFactory} — one renderer per row kind, plus in-place patching.</li>
 * <li>{@link SourceOrderEditor} — the merged source row, ranking, and drag-to-reorder.</li>
 * <li>{@link PanelDialogs} — option, confirming, cache, and credential dialogs.</li>
 * <li>{@link PanelPolicy} — pure visibility, availability, commit, and rebuild policy.</li>
 * </ul>
 *
 * <p>Does not own setting defaults ({@link Settings}) or persistence ({@link SettingsStore}).
 * Rendered as a single floating rounded card using platform widgets and {@link GlossyToggle};
 * layout stays visually stable unless device screenshots verify a change.
 */
public final class SettingsPanel implements SettingRowFactory.Host, PanelDialogs.Host,
        SourceOrderEditor.Host {
    // Static: survives panel re-opens within the process, so the panel never re-opens fully collapsed.
    private static final Set<String> expandedSections = new java.util.HashSet<>();

    private final Context context;
    private final PanelStyle style;
    private final SettingsStore store;
    private final SettingsWriter writer;
    private final SettingRowFactory rows;
    private final PanelDialogs dialogs;
    private final SourceOrderEditor sources;
    private final java.util.function.BooleanSupplier isHalfSize;
    private final Runnable onToggleSize;
    private final Runnable onClose;
    private final java.util.function.Consumer<CacheClearKind> onClearCache;

    private LinearLayout sectionsContainer;
    private TextView panelTitle;
    private SettingsUiStrings uiStrings;
    private AiSettingsRows aiSettingsRows;
    private ScrollView scrollRoot;
    private ImageView aiBadgeView;
    private String anchorTag;
    private int anchorDelta;
    /**
     * Whether the built view is currently attached to a window.
     *
     * <p>AI model probes and discovery run on background threads and post back afterwards. This
     * is the panel's own lifecycle signal, so a late result cannot rebuild a dismissed panel or
     * repaint a detached badge.
     */
    private volatile boolean panelAttached;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** One queued download-status tick at a time, so attach/detach cannot stack polling loops. */
    private boolean languageModelPollQueued;

    /** Locale lookup for the pure policy layer; reads the current uiStrings on every call. */
    private final PanelStrings panelStrings = new PanelStrings() {
        @Override public String get(String name, String fallback) {
            return uiStrings.get(name, fallback);
        }

        @Override public String format(String name, String fallback, Object... args) {
            return uiStrings.format(name, fallback, args);
        }
    };

    public SettingsPanel(Context context, SettingsStore store,
                         java.util.function.BooleanSupplier isHalfSize,
                         Runnable onToggleSize, Runnable onClose,
                         java.util.function.Consumer<CacheClearKind> onClearCache) {
        this.context = context;
        this.style = new PanelStyle(context);
        this.store = store;
        this.writer = new SettingsWriter(store);
        this.rows = new SettingRowFactory(this);
        this.dialogs = new PanelDialogs(this);
        this.sources = new SourceOrderEditor(this);
        this.isHalfSize = isHalfSize;
        this.onToggleSize = onToggleSize;
        this.onClose = onClose;
        this.onClearCache = onClearCache;
        writer.ensureBackgroundStyleMigrated(store.get(Settings.ENABLE_BACKGROUND));
        this.uiStrings = UiLanguage.strings(context, store.get(Settings.UI_LANGUAGE));
    }

    /** Builds the card view; the host sizes/centers it. */
    public View build() {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(PanelStyle.COL_CARD);
        cardBg.setCornerRadius(style.dp(26));
        cardBg.setStroke(style.dp(1), PanelStyle.COL_CARD_BORDER);
        scroll.setBackground(cardBg);
        scroll.setClipToOutline(true);
        scrollRoot = scroll;
        // The panel's lifecycle owner is its own view tree: the host shows and dismisses this
        // ScrollView, so attach/detach is the exact moment work must start or stop.
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                panelAttached = true;
                // The panel is rebuilt on every open, so an install started earlier has no loop
                // left to continue: resume it or the row keeps the progress it was built with.
                if (LanguageModelPack.status().phase == LanguageModelPack.Phase.DOWNLOADING) {
                    resumeLanguageModelDownloadPolling();
                }
            }

            @Override public void onViewDetachedFromWindow(View v) {
                panelAttached = false;
                languageModelPollQueued = false;
                uiHandler.removeCallbacksAndMessages(null);
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(style.dp(20), style.dp(18), style.dp(20), style.dp(20));
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
        panelTitle = style.text(uiStrings.appName(), 26, PanelStyle.COL_TITLE, true);
        header.addView(panelTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onToggleSize != null) {
            // Chevrons up/down = collapse toward the top-anchored half panel; down/up = grow.
            android.widget.ImageButton resize = style.headerIconButton(resizeKind(),
                    uiStrings.get("settings_panel_resize", "Resize settings panel"), v -> {
                onToggleSize.run();
                ((android.widget.ImageButton) v).setImageDrawable(
                        new ActionIconDrawable(resizeKind(), PanelStyle.COL_SUMMARY, style.density()));
            });
            header.addView(resize);
        }
        if (onClose != null) {
            header.addView(style.headerIconButton(Kind.CLOSE,
                    uiStrings.get("settings_panel_close", "Close settings panel"), v -> onClose.run()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = style.dp(6);
        content.addView(header, lp);
    }

    private Kind resizeKind() {
        return isHalfSize != null && isHalfSize.getAsBoolean()
                ? Kind.CHEVRONS_DOWN_UP
                : Kind.CHEVRONS_UP_DOWN;
    }

    // --- Section rendering ---

    private void renderSections(LinearLayout content) {
        if (aiAvailable()) aiRows().ensureInitialModelCheck();
        for (Map.Entry<Settings.Section, List<Settings.Setting<?>>> entry
                : groupVisibleSettings().entrySet()) {
            renderSectionGroup(content, entry.getKey(), entry.getValue());
        }
        appendSectionHeader(content, Settings.DEBUG, expandedSections.contains(Settings.DEBUG.id), -1);
        if (expandedSections.contains(Settings.DEBUG.id)) appendDebugCard(content, -1);
    }

    private LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> groupVisibleSettings() {
        PanelSnapshot snapshot = captureSnapshot();
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = new LinkedHashMap<>();
        // Section order and row order are explicit schema data (SettingsUiSchema), not an
        // accident of declaration order in Settings.ALL.
        for (Settings.Section section : SettingsUiSchema.orderedSections()) {
            for (Settings.Setting<?> setting : SettingsUiSchema.orderedSettings(section)) {
                if (!PanelPolicy.shouldRender(setting, snapshot)) continue;
                List<Settings.Setting<?>> items = grouped.get(section);
                if (items == null) {
                    items = new ArrayList<>();
                    grouped.put(section, items);
                }
                items.add(setting);
            }
        }
        return grouped;
    }

    /** One immutable applied-state snapshot per render pass; policy reads this, never the store. */
    @Override public PanelSnapshot snapshot() {
        PanelSnapshot.Builder snapshot = PanelSnapshot.builder()
                .translationAvailable(FeatureAvailability.translationAvailable())
                .transliterationAvailable(FeatureAvailability.transliterationAvailable())
                .languageModelReady(com.eza.spicyex.lyrics.LanguageModelPack.isReady())
                .appleFontAvailable(FeatureAvailability.appleFontAvailable())
                .animatedBackgroundAvailable(FeatureAvailability.animatedBackgroundAvailable())
                .spicySourceEnabled(com.eza.spicyex.lyrics.session.LyricsSourcePreferences.sourceEnabled(
                        context, com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source.SPICY))
                .aiOffered(aiAvailable());
        snapshot.put(Settings.AI_ENABLED, store.get(Settings.AI_ENABLED));
        snapshot.put(Settings.AI_PROVIDER, store.get(Settings.AI_PROVIDER));
        snapshot.put(Settings.TRANSLATION_ENABLED, store.get(Settings.TRANSLATION_ENABLED));
        snapshot.put(Settings.TRANSLITERATION_ENABLED, store.get(Settings.TRANSLITERATION_ENABLED));
        snapshot.put(Settings.BACKGROUND_STYLE, store.get(Settings.BACKGROUND_STYLE));
        snapshot.put(Settings.FORCE_DARK_BACKGROUND, store.get(Settings.FORCE_DARK_BACKGROUND));
        snapshot.put(Settings.ANIMATION_STYLE, store.get(Settings.ANIMATION_STYLE));
        snapshot.put(Settings.LIVE_CARD_ANIMATION, store.get(Settings.LIVE_CARD_ANIMATION));
        snapshot.put(Settings.LYRICS_TEXT_SIZE, store.get(Settings.LYRICS_TEXT_SIZE));
        snapshot.put(Settings.LINE_SPACING, store.get(Settings.LINE_SPACING));
        snapshot.put(Settings.LIVE_CARD_TEXT_SIZE, store.get(Settings.LIVE_CARD_TEXT_SIZE));
        snapshot.put(Settings.TRACK_INFO_TEXT_SIZE, store.get(Settings.TRACK_INFO_TEXT_SIZE));
        return snapshot.build();
    }

    private void renderSectionGroup(LinearLayout content, Settings.Section section,
                                    List<Settings.Setting<?>> items) {
        boolean expanded = expandedSections.contains(section.id);
        appendSectionHeader(content, section, expanded, -1);
        if (!expanded) return;
        // The AI section's remaining rows are not settings: a key that must not persist as it
        // is typed, and a model list that has to be fetched before it can be offered.
        appendSectionCard(content, section, items, -1);
    }

    /** Card for a settings section; AI gets its non-setting rows appended after the settings. */
    private void appendSectionCard(LinearLayout parent, Settings.Section section,
                                   List<Settings.Setting<?>> items, int at) {
        LinearLayout card = style.newCard();
        card.setTag(PanelTags.card(section));
        for (Settings.Setting<?> setting : items) renderSetting(card, setting);
        if (section == Settings.AI && aiAvailable()) {
            // Dynamic AI rows churn with setup state; they live in their own tagged block so
            // keyed rebinding refreshes them as a unit without touching ordinary rows.
            LinearLayout dynamic = new LinearLayout(context);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            dynamic.setTag(PanelTags.AI_DYNAMIC);
            aiRows().render(dynamic);
            card.addView(dynamic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        style.attachCard(parent, card, at);
    }

    private void appendDebugCard(LinearLayout parent, int at) {
        LinearLayout card = style.newCard();
        card.setTag(PanelTags.card(Settings.DEBUG));
        renderActions(card);
        renderStatus(card);
        renderDiagnostics(card);
        style.attachCard(parent, card, at);
    }

    // --- Anchor-preserving rebuilds ---
    // scrollY alone orphans the reader's anchor when a section above folds; anchor on the
    // first header/card boundary visible at viewport top instead.

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

    private void retargetAnchorToHeader(Settings.Section target) {
        int headerIdx = indexOfChildByTag(PanelTags.header(target));
        if (headerIdx < 0 || scrollRoot == null) return;
        View header = sectionsContainer.getChildAt(headerIdx);
        anchorTag = PanelTags.header(target);
        anchorDelta = sectionsContainer.getTop() + header.getTop() - scrollRoot.getScrollY();
    }

    // --- Setting dispatch ---

    private void renderSetting(LinearLayout content, Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            sources.mergedRow(content);
            return;
        }
        if (setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_ORDER) {
            return;
        }
        if (setting == Settings.SPICY_MANUAL_TOKEN) {
            spicyTokenRow(content);
            return;
        }
        if (setting == Settings.DOWNLOAD_LANGUAGE_MODELS) {
            downloadLanguageModelsRow(content);
            return;
        }
        // Renderer dispatch follows the UI schema; composite rows above stay hand-built.
        SettingUiSpec.RowKind kind = SettingsUiSchema.specOf(setting).kind;
        if (kind == SettingUiSpec.RowKind.TOGGLE && setting instanceof Settings.BooleanSetting) {
            rows.switchRow(content, (Settings.BooleanSetting) setting);
        } else if (kind == SettingUiSpec.RowKind.STEPPER && setting instanceof Settings.IntegerSetting) {
            rows.stepperRow(content, (Settings.IntegerSetting) setting);
        } else if (setting instanceof Settings.StringSetting) {
            Settings.StringSetting s = (Settings.StringSetting) setting;
            if (kind == SettingUiSpec.RowKind.TEXT_FIELD) rows.textFieldRow(content, s);
            else if (setting == Settings.UI_LANGUAGE) {
                rows.selectorRow(content, s, uiStrings.availableUiLanguages(), null);
            } else rows.selectorRow(content, s);
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
     *
     * <p>The header is one fixed-height row and swaps as a unit; card rows rebind by stable
     * ID (ordinary rows patch in place, composites swap in place). DEBUG carries live values
     * and re-renders its card as a unit.
     */
    private void rebuildSection(Settings.Section target) {
        if (sectionsContainer == null) return;
        int headerIdx = indexOfChildByTag(PanelTags.header(target));
        if (headerIdx < 0) {
            rebuildSections();
            return;
        }
        captureAnchor();
        boolean expanded = expandedSections.contains(target.id);
        if (!expanded && PanelTags.card(target).equals(anchorTag)) {
            retargetAnchorToHeader(target);
        }
        // The card sits directly after its header, so one removal shifts the other onto headerIdx.
        sectionsContainer.removeViewAt(headerIdx);
        if (target == Settings.AI) aiBadgeView = null;
        appendSectionHeader(sectionsContainer, target, expanded, headerIdx);
        if (!expanded) {
            int staleCard = indexOfChildByTag(PanelTags.card(target));
            if (staleCard >= 0) sectionsContainer.removeViewAt(staleCard);
        } else if (target == Settings.DEBUG) {
            int cardIdx = indexOfChildByTag(PanelTags.card(target));
            if (cardIdx >= 0) sectionsContainer.removeViewAt(cardIdx);
            appendDebugCard(sectionsContainer, headerIdx + 1);
        } else {
            rebindCard(target, headerIdx);
        }
        restoreAnchor();
    }

    /** Keyed card sync: stale rows out, the rest reused by ID and patched, missing rows built. */
    private void rebindCard(Settings.Section target, int headerIdx) {
        List<Settings.Setting<?>> items = groupVisibleSettings().get(target);
        if (items == null || items.isEmpty()) {
            rebuildSections(); // defensive: rendered section without visible settings
            return;
        }
        int cardIdx = indexOfChildByTag(PanelTags.card(target));
        LinearLayout card;
        if (cardIdx < 0 || !(sectionsContainer.getChildAt(cardIdx) instanceof LinearLayout)) {
            card = style.newCard();
            card.setTag(PanelTags.card(target));
            style.attachCard(sectionsContainer, card, headerIdx + 1);
        } else {
            card = (LinearLayout) sectionsContainer.getChildAt(cardIdx);
        }
        PanelSnapshot snapshot = captureSnapshot();
        Map<String, Settings.Setting<?>> byKey = new java.util.HashMap<>();
        List<String> visibleKeys = new ArrayList<>();
        for (Settings.Setting<?> setting : items) {
            // The merged source row owns MODE; OVERRIDE and ORDER render nothing on their own.
            if (setting == Settings.LYRICS_SOURCE_OVERRIDE
                    || setting == Settings.LYRICS_SOURCE_ORDER) {
                continue;
            }
            byKey.put(setting.key, setting);
            visibleKeys.add(setting.key);
        }
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < card.getChildCount(); i++) {
            String key = PanelTags.keyOf(card.getChildAt(i).getTag());
            if (key != null) currentKeys.add(key);
        }
        RowSyncPlan plan = RowSyncPlan.of(visibleKeys, currentKeys);
        // The AI dynamic block re-renders as a unit; detach it so positions count rows only.
        View dynamic = findChildByTag(card, PanelTags.AI_DYNAMIC);
        if (dynamic != null) card.removeView(dynamic);
        for (String dead : plan.removals) {
            View stale = findRowIn(card, dead);
            if (stale != null) card.removeView(stale);
        }
        for (int i = 0; i < plan.order.size(); i++) {
            String key = plan.order.get(i);
            Settings.Setting<?> setting = byKey.get(key);
            if (setting == null) continue;
            View row = findRowIn(card, key);
            if (row == null || SettingsUiSchema.isComposite(setting)) {
                if (row != null) card.removeView(row);
                renderSetting(card, setting); // appends fresh; repositioned below
                row = card.getChildAt(card.getChildCount() - 1);
            } else {
                rows.patchRow(row, setting, snapshot);
            }
            if (row != null) {
                card.removeView(row);
                card.addView(row, Math.min(i, card.getChildCount()));
            }
        }
        refreshAiDynamicBlock(card, target);
    }

    /** AI dynamic rows re-render as one tagged block; ordinary AI settings rows patch by key. */
    private void refreshAiDynamicBlock(LinearLayout card, Settings.Section target) {
        View dynamic = findChildByTag(card, PanelTags.AI_DYNAMIC);
        if (target != Settings.AI || !aiAvailable()) {
            if (dynamic != null) card.removeView(dynamic);
            return;
        }
        LinearLayout block;
        if (dynamic instanceof LinearLayout) {
            block = (LinearLayout) dynamic;
            block.removeAllViews();
        } else {
            block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag(PanelTags.AI_DYNAMIC);
            card.addView(block, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        aiRows().render(block);
    }

    private View findRowIn(LinearLayout card, String key) {
        return findChildByTag(card, PanelTags.row(key));
    }

    private static View findChildByTag(LinearLayout parent, String tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (tag.equals(parent.getChildAt(i).getTag())) return parent.getChildAt(i);
        }
        return null;
    }

    private int indexOfChildByTag(String tag) {
        for (int i = 0; i < sectionsContainer.getChildCount(); i++) {
            if (tag.equals(sectionsContainer.getChildAt(i).getTag())) return i;
        }
        return -1;
    }

    /** UI language rebuilds every label; dependency settings rebuild only their own section. */
    @Override public void onSettingChanged(Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            com.eza.spicyex.lyrics.session.LyricsSourcePreferences.setRankingMode(context,
                    com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode.parse(
                            String.valueOf(store.get(setting))));
        }
        if (setting == Settings.UI_LANGUAGE) {
            rebuildSections();
        } else if (setting == Settings.ANIMATION_STYLE) {
            // The Apple Music card appears/disappears with this pick (a cross-section change),
            // so the whole panel rebuilds anchor-preserved instead of one section in place.
            if ("Apple Music".equals(String.valueOf(store.get(setting)))) {
                expandedSections.add(Settings.APPLE.id);
            }
            rebuildSections();
        } else if (PanelPolicy.shouldRebuildSectionAfterChange(setting)) {
            rebuildSection(setting.section);
        }
    }

    /**
     * Full-only. AI needs no on-device language packages, so nothing stops it running in Lite —
     * which is exactly the problem: Lite would gain translation while its own capability flag says
     * it has none. Until that flag is untangled, the family is not offered there.
     */
    private static boolean aiAvailable() {
        return FeatureAvailability.translationAvailable();
    }

    /** Section headers --- */

    private LinearLayout buildSectionHeader(Settings.Section section, boolean expanded) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(44));
        row.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row.setTag(PanelTags.header(section));

        Kind sectionIcon = PanelStyle.sectionIcon(section);
        if (sectionIcon != null) {
            ImageView sectionIconView = style.kindView(sectionIcon,
                    section == Settings.AI && aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, 18);
            if (section == Settings.AI) aiBadgeView = sectionIconView;
            row.addView(sectionIconView, style.leadParams());
        }

        TextView title = style.text(uiStrings.section(section), 14, PanelStyle.COL_TITLE, true);
        title.setAllCaps(true);
        title.setLetterSpacing(0.05f);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(style.kindView(expanded ? Kind.CHEVRON_DOWN : Kind.CHEVRON_RIGHT,
                        PanelStyle.COL_SECTION, 16),
                new LinearLayout.LayoutParams(style.dp(28), style.dp(28)));
        row.setOnClickListener(v -> {
            boolean nowExpanded = !expandedSections.contains(section.id);
            if (nowExpanded) expandedSections.add(section.id);
            else expandedSections.remove(section.id);
            rebuildSection(section);
        });
        return row;
    }

    private void appendSectionHeader(LinearLayout parent, Settings.Section section,
                                     boolean expanded, int at) {
        View row = buildSectionHeader(section, expanded);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = style.dp(4);
        if (at < 0 || at >= parent.getChildCount()) parent.addView(row, lp);
        else parent.addView(row, at, lp);
    }

    // --- Credentials row (composite) ---

    private void spicyTokenRow(LinearLayout content) {
        String masked = SpicyManualTokenStore.masked(context);
        List<AiSettingsRows.IconAction> actions = new ArrayList<>();
        actions.add(new AiSettingsRows.IconAction(Kind.EDIT,
                uiStrings.get("settings_spicy_token_edit", "Edit token"), v -> dialogs.promptSpicyToken()));
        if (!masked.isEmpty()) {
            actions.add(new AiSettingsRows.IconAction(Kind.VISIBILITY,
                    uiStrings.get("settings_spicy_token_reveal", "Reveal token"),
                    v -> dialogs.revealSpicyToken()));
            actions.add(new AiSettingsRows.IconAction(Kind.DELETE,
                    uiStrings.get("settings_spicy_token_delete", "Delete token"), v -> {
                SpicyManualTokenStore.delete(context);
                rebuildSection(Settings.LYRICS_SOURCES);
            }));
        }
        rows.aiFieldRow(content, uiStrings.setting(Settings.SPICY_MANUAL_TOKEN),
                masked.isEmpty() ? uiStrings.get("settings_spicy_token_absent", "Not set") : masked,
                false, Settings.SPICY_MANUAL_TOKEN.key, v -> dialogs.promptSpicyToken(),
                actions.toArray(new AiSettingsRows.IconAction[0]));
    }

    private void refreshLanguageModelDownloadStatus() {
        languageModelPollQueued = false;
        if (!panelAttached) return;
        LanguageModelPack.DownloadStatus status = LanguageModelPack.status();
        // Rebuild once more after the worker switches to READY or ERROR; otherwise the polling
        // loop would stop before the terminal state became visible in the panel.
        rebuildSection(Settings.TRANSLITERATION);
        if (status.phase == LanguageModelPack.Phase.DOWNLOADING) {
            languageModelPollQueued = true;
            uiHandler.postDelayed(this::refreshLanguageModelDownloadStatus, 500);
        }
    }

    /**
     * Starts the status loop for a panel that was built while an install was already running.
     *
     * <p>Detach cancels the queued tick, and the host builds a fresh panel on every open, so
     * without this the reopened panel shows the progress it rendered once and never notices the
     * worker reach READY — leaving the transliteration toggle disabled after a successful install.
     */
    private void resumeLanguageModelDownloadPolling() {
        if (languageModelPollQueued) return;
        languageModelPollQueued = true;
        uiHandler.postDelayed(this::refreshLanguageModelDownloadStatus, 500);
    }

    private void downloadLanguageModelsRow(LinearLayout content) {
        LanguageModelPack.DownloadStatus status = LanguageModelPack.status();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(Settings.DOWNLOAD_LANGUAGE_MODELS.key));
        row.setOnClickListener(v -> {
            if (LanguageModelPack.isReady()) {
                String sizeMb = String.format(Locale.getDefault(), "%.1f",
                        LanguageModelPack.installedSizeBytes() / 1_000_000.0);
                new PanelDialog(context, uiStrings.get("settings_language_model_delete_title", "Delete language model"))
                        .paragraph(uiStrings.format("settings_language_model_delete_desc",
                                "Delete the downloaded language model pack? This frees about %1$s MB.", sizeMb))
                        .primary(uiStrings.get("settings_language_model_delete", "Delete"), () -> {
                            LanguageModelPack.deleteDownload();
                            rebuildSection(Settings.TRANSLITERATION);
                        })
                        .secondary(uiStrings.get("settings_ai_cancel", "Cancel"), null)
                        .show();
                return;
            }
            if (status.phase == LanguageModelPack.Phase.ERROR) {
                LanguageModelPack.clearTransientState();
            }
            LanguageModelPack.requestDownload();
            rebuildSection(Settings.TRANSLITERATION);
            refreshLanguageModelDownloadStatus();
        });

        TextView title = style.text(uiStrings.setting(Settings.DOWNLOAD_LANGUAGE_MODELS), 16, PanelStyle.COL_TITLE, false);
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(title);

        String summary;
        String small = "";
        if (status.phase == LanguageModelPack.Phase.READY) {
            summary = uiStrings.get("settings_language_model_installed", "Downloaded");
            String sizeMb = String.format(Locale.getDefault(), "%.1f",
                    LanguageModelPack.installedSizeBytes() / 1_000_000.0);
            small = uiStrings.format("settings_language_model_tap_to_delete_size",
                    "Tap to delete · %1$s MB", sizeMb);
        } else if (status.phase == LanguageModelPack.Phase.DOWNLOADING) {
            summary = uiStrings.get("settings_language_model_downloading", "Downloading…");
            small = uiStrings.get("settings_language_model_progress", status.progressPercent + "%");
        } else if (status.phase == LanguageModelPack.Phase.ERROR) {
            summary = uiStrings.get("settings_language_model_failed", "Download failed");
            small = status.errorCode.isEmpty()
                    ? uiStrings.get("settings_language_model_retry", "Tap to retry")
                    : status.errorCode;
        } else {
            summary = uiStrings.get("settings_language_model_idle", "Tap to download");
            small = uiStrings.get("settings_language_model_size", "Optional language pack");
        }

        TextView statusView = style.text(summary, 12, PanelStyle.COL_SUMMARY, false);
        statusView.setPadding(0, style.dp(2), 0, 0);
        info.addView(statusView);

        if (status.phase == LanguageModelPack.Phase.DOWNLOADING || status.phase == LanguageModelPack.Phase.ERROR) {
            ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(status.phase == LanguageModelPack.Phase.DOWNLOADING ? status.progressPercent : 0);
            progress.setIndeterminate(status.phase == LanguageModelPack.Phase.DOWNLOADING && status.progressPercent <= 0);
            progress.setPadding(0, style.dp(8), 0, style.dp(6));
            info.addView(progress, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!small.isEmpty()) {
            TextView extra = style.text(small, 11, status.phase == LanguageModelPack.Phase.ERROR ? 0xFFFFB4B4 : PanelStyle.COL_SECTION, false);
            extra.setPadding(0, style.dp(2), 0, 0);
            info.addView(extra);
        }

        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(style.kindView(Kind.LANGUAGES, PanelStyle.COL_ACCENT, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
    }

    // --- Diagnostics card ---

    private void renderActions(LinearLayout content) {
        rows.actionRow(content, Kind.BUG,
                DiagnosticReportingDialog.reportProblemLabel(context, store),
                v -> DiagnosticReportingDialog.show(context, store));
        clearAction(content, "settings_action_clear_translation_cache",
                "Clear translation cache", CacheClearKind.TRANSLATION);
        clearAction(content, "settings_action_clear_reading_cache",
                "Clear transliteration cache", CacheClearKind.TRANSLITERATION);
        clearAction(content, "settings_action_clear_ai_cache",
                "Clear AI results", CacheClearKind.AI);
        clearAction(content, "settings_action_clear_lyrics_cache",
                "Clear lyrics response cache", CacheClearKind.LYRICS_RESPONSE);
        rows.actionRow(content, Kind.EXTERNAL_LINK,
                uiStrings.get("settings_action_open_github", "Open GitHub"), v -> openGithub());
    }

    private void clearAction(LinearLayout content, String key, String fallback, CacheClearKind kind) {
        rows.actionRow(content, null, uiStrings.get(key, fallback), v -> clearCache(kind));
    }

    private void clearCache(CacheClearKind kind) {
        if (onClearCache == null) return;
        onClearCache.accept(kind);
        // Cache clears update preference memory (and the AI database) before returning. Rebuild
        // the owning row now so its usage summary reflects the clear without closing the panel.
        rebuildSection(Settings.LYRICS_SOURCES);
    }

    private void renderStatus(LinearLayout content) {
        CurrentLyricState s = CurrentLyricState.get();
        String summary = uiStrings.format("settings_status_summary",
                "Last state: %1$s\nTrack: %2$s\nLine: %3$s",
                s.status, s.title, s.originalLine);
        TextView state = style.text(summary, 12, PanelStyle.COL_SUMMARY, false);
        state.setPadding(0, style.dp(4), 0, style.dp(2));
        content.addView(state);
        TextView version = style.text(BuildStamp.FULL, 11, PanelStyle.COL_SECTION, false);
        version.setPadding(0, style.dp(12), 0, 0);
        content.addView(version);
    }

    private void renderDiagnostics(LinearLayout content) {
        LyricsFetchDiagnosticsState.Snapshot s = LyricsFetchDiagnosticsState.get();
        rows.infoRow(content, uiStrings.get("settings_diagnostic_source_chosen", "Source chosen"),
                s.displayedSourceChosen());
        rows.infoRow(content, uiStrings.get("settings_diagnostic_candidates_seen", "Candidates seen"),
                s.candidatesSeen);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_provider", "Provider"), s.provider);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_type_chosen", "Type chosen"),
                s.typeChosen);
        rows.infoRow(content, uiStrings.get("settings_diagnostic_cache_write", "Cache write"),
                yesNo(s.cacheWrite));
    }

    // --- AI rows adapter ---

    /** Adapter giving the AI rows the panel's own row vocabulary, so they look like every other row. */
    private AiSettingsRows aiRows() {
        if (aiSettingsRows != null) return aiSettingsRows;
        aiSettingsRows = new AiSettingsRows(context, new AiSettingsRows.Host() {
            @Override public void info(LinearLayout content, String label, String value) {
                rows.infoRow(content, label, value);
            }

            @Override public void field(LinearLayout content, String label, String value,
                                        View.OnClickListener listener,
                                        AiSettingsRows.IconAction... actions) {
                rows.aiFieldRow(content, label, value, false, null, listener, actions);
            }

            @Override public void selector(LinearLayout content, String label, String value,
                                           View.OnClickListener listener,
                                           AiSettingsRows.IconAction... actions) {
                rows.aiFieldRow(content, label, value, true, null, listener, actions);
            }

            @Override public void rebuild() {
                // A probe can finish after the panel was dismissed; nothing is mounted then.
                if (!panelAttached) return;
                rebuildSection(Settings.AI);
            }

            @Override public void updateAiBadge(boolean live) {
                if (!panelAttached) return;
                // The probe outcome is not what the star reports; setup completeness is. This is
                // only the signal that something about the AI configuration may have moved.
                if (aiBadgeView == null) {
                    rebuildSection(Settings.AI);
                    return;
                }
                aiBadgeView.setImageDrawable(new ActionIconDrawable(Kind.SPARKLES,
                        aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, style.density()));
            }

            @Override public String string(String name, String fallback) {
                return uiStrings.get(name, fallback);
            }
        }, store);
        return aiSettingsRows;
    }

    // --- Misc ---

    private String yesNo(boolean value) {
        return value
                ? uiStrings.get("settings_yes", "yes")
                : uiStrings.get("settings_no", "no");
    }

    private void openGithub() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/amarinne/spicy-ex"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** Option label; magnitude-based selectors show the plain multiplier as the label. */
    @Override public String labelFor(Settings.StringSetting setting, String value) {
        String mult = SettingLabels.multiplierFor(setting.key, value);
        if (mult != null) return "\u00d7" + mult;
        return uiStrings.option(setting, value);
    }

    @Override public String stepperSummary(Settings.IntegerSetting setting) {
        if (setting == Settings.SYNC_OFFSET_MS) {
            return uiStrings.get("settings_sync_offset_summary", "Positive shows lyrics earlier");
        }
        return null;
    }

    /** Explains why a row is unavailable. */
    @Override public String unavailableSummary(Settings.Setting<?> setting) {
        if (setting == Settings.TRANSLITERATION_ENABLED && !LanguageModelPack.isReady()) {
            return uiStrings.setting(Settings.DOWNLOAD_LANGUAGE_MODELS);
        }
        return uiStrings.get("settings_unavailable_full_build", "Full build required");
    }

    @Override public boolean unavailable(Settings.Setting<?> setting) {
        return PanelPolicy.unavailable(setting, captureSnapshot());
    }

    @Override public String cacheSizeSummary() {
        String label = uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
        return SettingLabels.cacheUsageSummary(panelStrings, label,
                CacheStoragePolicy.formatBytes(CacheStoragePolicy.storedTotal(context)));
    }

    private PanelSnapshot captureSnapshot() {
        return snapshot();
    }

    // --- SettingRowFactory.Host ---

    @Override public SettingsStore store() {
        return store;
    }

    @Override public SettingsWriter writer() {
        return writer;
    }

    @Override public SettingsUiStrings strings() {
        return uiStrings;
    }

    @Override public PanelStyle style() {
        return style;
    }

    @Override public void openSelector(Settings.StringSetting setting, List<String> values,
                                       TextView valueView) {
        dialogs.openSelector(setting, values, valueView);
    }

    // --- PanelDialogs.Host ---

    /** Writes the value and applies immediate side effects (the UI-language swap). */
    @Override public void onOptionChosen(Settings.StringSetting setting, String value) {
        writer.put(setting, value);
        if (setting == Settings.UI_LANGUAGE) {
            uiStrings = UiLanguage.strings(context, value);
            if (panelTitle != null) panelTitle.setText(uiStrings.appName());
        }
    }

    @Override public void afterSettingChosen(Settings.Setting<?> setting) {
        onSettingChanged(setting);
    }

    @Override public String rowSummaryFor(Settings.StringSetting setting, String value) {
        return setting == Settings.CACHE_SIZE ? cacheSizeSummary() : labelFor(setting, value);
    }

    @Override public PanelStrings panelStrings() {
        return panelStrings;
    }

    @Override public void onSpicyTokenChanged() {
        rebuildSection(Settings.LYRICS_SOURCES);
    }

    // --- SourceOrderEditor.Host ---

    @Override public void onSourcesCommitted() {
        onSettingChanged(Settings.LYRICS_SOURCE_MODE);
        rebuildSection(Settings.LYRICS_SOURCES);
    }
}
