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
        SourceOrderEditor.Host, com.eza.spicyex.settings.CacheManager.Host {
    /**
     * Two levels, as a phone's own settings: the section list, and one section's page. Null is
     * the list. (It used to be one long page of all-caps accordions, every open section's rows
     * stacked into the same scroll - hard to find anything in.)
     */
    private String openSection;

    private final Context context;
    private final PanelStyle style;
    private final SettingsStore store;
    private final SettingsWriter writer;
    private final SettingRowFactory rows;
    private final PanelDialogs dialogs;
    private final SourceOrderEditor sources;
    private final com.eza.spicyex.settings.CacheManager cache;
    private final java.util.function.BooleanSupplier isHalfSize;
    private final Runnable onToggleSize;
    private final Runnable onClose;
    /** Opens the layout editor: {@link #EDITOR_LYRICS} or {@link #EDITOR_CARD}. */
    private final java.util.function.IntConsumer onOpenLayoutEditor;
    public static final int EDITOR_LYRICS = 1;
    public static final int EDITOR_CARD = 2;
    private final java.util.function.Consumer<CacheClearKind> onClearCache;
    private final Runnable onResyncTiming;

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
                         java.util.function.IntConsumer onOpenLayoutEditor,
                         java.util.function.Consumer<CacheClearKind> onClearCache,
                         Runnable onResyncTiming) {
        this.context = context;
        this.style = new PanelStyle(context);
        this.store = store;
        this.writer = new SettingsWriter(store);
        this.rows = new SettingRowFactory(this);
        this.dialogs = new PanelDialogs(this);
        this.sources = new SourceOrderEditor(this);
        this.cache = new com.eza.spicyex.settings.CacheManager(this);
        this.isHalfSize = isHalfSize;
        this.onToggleSize = onToggleSize;
        this.onClose = onClose;
        this.onOpenLayoutEditor = onOpenLayoutEditor;
        this.onClearCache = onClearCache;
        this.onResyncTiming = onResyncTiming;
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
        buildSearch(content);
        sectionsContainer = new LinearLayout(context);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(sectionsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderSections(sectionsContainer);
        return scroll;
    }

    // --- Search ---

    private LinearLayout searchBar;
    private android.widget.EditText searchField;
    private LinearLayout searchResults;
    private volatile com.eza.spicyex.settings.SettingsSearch searchIndex;

    /** A result that opens a Layout Editor rather than a panel row. */
    private static final class EditorTarget {
        final int mode;
        final Settings.Setting<?> setting;

        EditorTarget(int mode, Settings.Setting<?> setting) {
            this.mode = mode;
            this.setting = setting;
        }
    }

    /**
     * The search field above the section list. Typing swaps the list for results (see
     * {@link com.eza.spicyex.settings.SettingsSearch} for how they are found: any language,
     * typos, other words for the same idea); tapping one opens its section, scrolls to the row
     * and marks it, or opens the editor for what only the Layout Editor edits.
     */
    private void buildSearch(LinearLayout content) {
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(style.dp(12), 0, style.dp(4), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(style.dp(14));
        bg.setColor(0x14FFFFFF);
        searchBar.setBackground(bg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(18), style.dp(18));
        iconLp.rightMargin = style.dp(8);
        searchBar.addView(style.kindView(Kind.SEARCH, PanelStyle.COL_SUMMARY, 18), iconLp);
        searchField = new android.widget.EditText(context);
        searchField.setSingleLine(true);
        searchField.setHint(uiStrings.get("settings_search_hint", "Search settings"));
        searchField.setTextColor(PanelStyle.COL_TITLE);
        searchField.setHintTextColor(PanelStyle.COL_SUMMARY);
        searchField.setTextSize(15);
        searchField.setBackground(null);
        searchField.setPadding(0, style.dp(10), 0, style.dp(10));
        searchField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        searchBar.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.ImageView clear = style.kindView(Kind.CLOSE, PanelStyle.COL_SUMMARY, 16);
        clear.setPadding(style.dp(8), style.dp(8), style.dp(8), style.dp(8));
        clear.setContentDescription(uiStrings.get("settings_ai_cancel", "Cancel"));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> searchField.setText(""));
        searchBar.addView(clear, new LinearLayout.LayoutParams(style.dp(34), style.dp(34)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = style.dp(8);
        content.addView(searchBar, barLp);

        searchResults = new LinearLayout(context);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setVisibility(View.GONE);
        content.addView(searchResults, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Reading every language's strings takes a moment: start as soon as the field is
        // focused, so the first letter typed does not wait for it.
        searchField.setOnFocusChangeListener((v, focused) -> {
            if (focused && searchIndex == null) {
                Thread warm = new Thread(this::searchIndex, "SettingsSearchIndex");
                warm.setDaemon(true);
                warm.start();
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String query = text.toString();
                boolean searching = !query.trim().isEmpty();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (sectionsContainer != null) sectionsContainer.setVisibility(searching ? View.GONE : View.VISIBLE);
                searchResults.setVisibility(searching ? View.VISIBLE : View.GONE);
                if (!searching) {
                    searchResults.removeAllViews();
                    return;
                }
                renderSearchResults(query);
            }
        });
    }

    private void hideKeyboard() {
        if (searchField == null) return;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
    }

    /** Everything searchable, built once per search session from what the panel shows now. */
    private synchronized com.eza.spicyex.settings.SettingsSearch searchIndex() {
        if (searchIndex != null) return searchIndex;
        List<com.eza.spicyex.settings.SettingsSearch.Entry> entries = new ArrayList<>();
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = groupVisibleSettings();
        List<Settings.Section> sections = new ArrayList<>(grouped.keySet());
        sections.add(Settings.DEBUG);
        for (Settings.Section section : sections) {
            String name = uiStrings.section(section);
            entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(section,
                    names(name, SettingsUiResourceNames.section(section), section.label), "", section.id,
                    sectionKeywords(section)));
            List<Settings.Setting<?>> items = grouped.get(section);
            if (items == null) continue;
            for (Settings.Setting<?> setting : items) {
                if (setting == Settings.LYRICS_SOURCE_OVERRIDE || setting == Settings.LYRICS_SOURCE_ORDER) continue;
                entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(setting,
                        names(searchTitle(setting), SettingsUiResourceNames.setting(setting), setting.label),
                        name, section.id, settingKeywords(setting, section)));
            }
        }
        // What only the Layout Editor edits: found here too, opening the editor.
        String lyricsEditor = uiStrings.get("settings_search_editor_lyrics", "Layout editor · Lyrics screen");
        String cardEditor = uiStrings.get("settings_search_editor_card", "Layout editor · Now playing");
        for (Settings.Setting<?> setting : com.eza.spicyex.hooks.LayoutEditorSettings.covered()) {
            boolean card = com.eza.spicyex.hooks.LayoutEditorSettings.isCardSetting(setting);
            List<String> extra = settingKeywords(setting, null);
            extra.add("layout editor");
            extra.add(uiStrings.section(card ? Settings.NOW_PLAYING : Settings.LYRICS_SCREEN));
            entries.add(new com.eza.spicyex.settings.SettingsSearch.Entry(
                    new EditorTarget(card ? EDITOR_CARD : EDITOR_LYRICS, setting),
                    names(searchTitle(setting), SettingsUiResourceNames.setting(setting), setting.label),
                    card ? cardEditor : lyricsEditor, card ? "editor_card" : "editor_lyrics", extra));
        }
        // Synonym groups: each language's own words, from its strings file.
        List<List<String>> concepts = new ArrayList<>();
        for (String id : com.eza.spicyex.settings.SettingsSearch.CONCEPT_IDS) {
            concepts.add(com.eza.spicyex.settings.SettingsSearch.mergeTerms(
                    uiStrings.inEveryLanguage("search_terms_" + id)));
        }
        searchIndex = new com.eza.spicyex.settings.SettingsSearch(entries, concepts);
        return searchIndex;
    }

    /** A name as shown, then as every shipped language has it, then the English built-in. */
    private List<String> names(String shown, String resource, String builtIn) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(shown);
        out.addAll(uiStrings.inEveryLanguage(resource));
        if (builtIn != null) out.add(builtIn);
        return new ArrayList<>(out);
    }

    private String searchTitle(Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            return uiStrings.get("settings_source_list_title", "Sources");
        }
        return uiStrings.setting(setting);
    }

    private List<String> sectionKeywords(Settings.Section section) {
        List<String> extra = new ArrayList<>();
        extra.add(section.id.replace('_', ' '));
        if (section == Settings.LYRICS_SOURCES) {
            extra.add("Apple Music Musixmatch LRCLIB NetEase QQ Music Spotify cache");
            extra.add(uiStrings.get("settings_cache_title", "Stored lyrics"));
        }
        return extra;
    }

    /** The words that describe a setting besides its name: key, section, option labels. */
    private List<String> settingKeywords(Settings.Setting<?> setting, Settings.Section section) {
        List<String> extra = new ArrayList<>();
        extra.add(setting.key.replace('_', ' '));
        if (section != null) extra.add(section.label);
        // Words written for search, in every language that has them.
        extra.addAll(uiStrings.inEveryLanguage("settings_search_" + setting.key));
        if (setting instanceof Settings.StringSetting && setting.allowedValues != null) {
            for (Object value : setting.allowedValues) {
                String raw = String.valueOf(value);
                extra.add(raw);
                extra.add(uiStrings.option((Settings.StringSetting) setting, raw));
            }
        }
        if (setting == Settings.LYRICS_SOURCE_MODE) {
            extra.add("Apple Music Musixmatch LRCLIB NetEase QQ Music Spotify smart order ranking");
            extra.add(uiStrings.section(Settings.LYRICS_SOURCES));
        }
        if (setting == Settings.CACHE_SIZE) {
            extra.add(uiStrings.get("settings_cache_title", "Stored lyrics"));
            extra.add(uiStrings.get("settings_cache_clear", "Clear"));
        }
        return extra;
    }

    private void renderSearchResults(String query) {
        searchResults.removeAllViews();
        List<com.eza.spicyex.settings.SettingsSearch.Result> results = searchIndex().search(query, 12, 4);
        if (results.isEmpty()) {
            TextView empty = style.text(uiStrings.get("settings_search_empty", "No matching settings"), 14,
                    PanelStyle.COL_SUMMARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, style.dp(32), 0, style.dp(32));
            searchResults.addView(empty);
            return;
        }
        boolean relatedShown = false;
        for (com.eza.spicyex.settings.SettingsSearch.Result result : results) {
            if (result.related && !relatedShown) {
                relatedShown = true;
                TextView caption = style.text(uiStrings.get("settings_search_related", "Related"), 13,
                        PanelStyle.COL_SECTION, true);
                caption.setPadding(style.dp(6), style.dp(14), 0, style.dp(4));
                searchResults.addView(caption);
            }
            searchResults.addView(searchResultRow(result.entry));
        }
    }

    private View searchResultRow(final com.eza.spicyex.settings.SettingsSearch.Entry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(56));
        row.setPadding(style.dp(8), style.dp(8), style.dp(6), style.dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(style.dp(14));
        shape.setColor(0x00000000);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), shape, shape));

        Kind icon = entry.target instanceof Settings.Section
                ? PanelStyle.sectionIcon((Settings.Section) entry.target)
                : entry.target instanceof EditorTarget ? Kind.EDIT
                : entry.target instanceof Settings.Setting
                ? PanelStyle.sectionIcon(((Settings.Setting<?>) entry.target).section) : null;
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(20), style.dp(20));
        iconLp.rightMargin = style.dp(14);
        row.addView(style.kindView(icon == null ? Kind.SETTINGS : icon, PanelStyle.COL_SECTION, 18), iconLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = style.text(entry.title, 15, PanelStyle.COL_TITLE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        String detail = entry.place;
        if (entry.target instanceof Settings.StringSetting) {
            Settings.StringSetting setting = (Settings.StringSetting) entry.target;
            String value = selectorSummary(setting, store.get(setting));
            if (value != null && !value.isEmpty()) detail = detail.isEmpty() ? value : detail + " \u00b7 " + value;
        }
        if (!detail.isEmpty()) {
            TextView sub = style.text(detail, 12, PanelStyle.COL_SUMMARY, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 16),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(24)));
        row.setOnClickListener(v -> openSearchResult(entry));
        return row;
    }

    private void openSearchResult(com.eza.spicyex.settings.SettingsSearch.Entry entry) {
        hideKeyboard();
        Object target = entry.target;
        searchField.setText("");
        if (target instanceof EditorTarget) {
            com.eza.spicyex.hooks.LayoutEditorSettings.openOn(((EditorTarget) target).setting);
            openEditor(((EditorTarget) target).mode);
        } else if (target instanceof Settings.Section) {
            Settings.Section section = (Settings.Section) target;
            if (isEditorSection(section)) openEditorFor(section); else navigate(section);
        } else if (target instanceof Settings.Setting) {
            final Settings.Setting<?> setting = (Settings.Setting<?>) target;
            navigate(setting.section);
            sectionsContainer.postDelayed(() -> revealRow(setting), 240);
        }
    }

    /** Scrolls a row of the open page into view and marks it with a brief highlight. */
    private void revealRow(Settings.Setting<?> setting) {
        if (sectionsContainer == null || scrollRoot == null) return;
        View cardView = findChildByTag(sectionsContainer, PanelTags.card(setting.section));
        if (!(cardView instanceof LinearLayout)) return;
        View row = findRowIn((LinearLayout) cardView, setting.key);
        if (row == null) return;
        int y = 0;
        for (View v = row; v != null && v != scrollRoot; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            y += v.getTop();
        }
        scrollRoot.smoothScrollTo(0, Math.max(0, y - style.dp(96)));
        final android.graphics.drawable.GradientDrawable mark = new android.graphics.drawable.GradientDrawable();
        mark.setCornerRadius(style.dp(12));
        mark.setColor(PanelStyle.COL_ACCENT);
        mark.setAlpha(0);
        row.setForeground(mark);
        android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(1600);
        pulse.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            // In quickly, hold, then out slowly.
            float level = p < 0.15f ? p / 0.15f : p < 0.45f ? 1f : 1f - (p - 0.45f) / 0.55f;
            mark.setAlpha(Math.round(56 * level));
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                row.setForeground(null);
            }
        });
        pulse.start();
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
        LinkedHashMap<Settings.Section, List<Settings.Setting<?>>> grouped = groupVisibleSettings();
        Settings.Section open = openSectionOf(grouped);
        if (open == null) {
            // The list: one tile per section, what is inside it written underneath.
            openSection = null;
            for (Settings.Section section : grouped.keySet()) {
                appendSectionHeader(content, section, false, -1);
            }
            appendSectionHeader(content, Settings.DEBUG, false, -1);
            return;
        }
        // One section's page: a back bar with its name, then its rows.
        appendSectionHeader(content, open, true, -1);
        if (open == Settings.DEBUG) {
            appendDebugCard(content, -1);
        } else {
            List<Settings.Setting<?>> items = grouped.get(open);
            appendSectionCard(content, open, items == null ? new ArrayList<>() : items, -1);
        }
    }

    private Settings.Section openSectionOf(Map<Settings.Section, List<Settings.Setting<?>>> grouped) {
        if (openSection == null) return null;
        if (Settings.DEBUG.id.equals(openSection)) return Settings.DEBUG;
        for (Settings.Section section : grouped.keySet()) {
            if (section.id.equals(openSection) && !isEditorSection(section)) return section;
        }
        return null;
    }

    private boolean isOpen(Settings.Section section) {
        return openSection != null && openSection.equals(section.id);
    }

    /** Into a section's page (or back to the list with null), sliding the way it goes. */
    private void navigate(Settings.Section section) {
        if (sectionsContainer == null) return;
        boolean forward = section != null;
        openSection = section == null ? null : section.id;
        aiBadgeView = null;
        sectionsContainer.animate().cancel();
        sectionsContainer.removeAllViews();
        renderSections(sectionsContainer);
        if (searchBar != null) searchBar.setVisibility(section == null ? View.VISIBLE : View.GONE);
        // What is visible may change on a page; the next search reindexes.
        if (section != null) searchIndex = null;
        if (scrollRoot != null) scrollRoot.scrollTo(0, 0);
        sectionsContainer.setTranslationX(style.dp(forward ? 32 : -32));
        sectionsContainer.setAlpha(0f);
        sectionsContainer.animate().translationX(0f).alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** Back from a section's page returns to the list; on the list the host closes the panel. */
    public boolean handleBack() {
        // A search in progress is the first thing back undoes.
        if (searchField != null && searchField.getText().length() > 0) {
            searchField.setText("");
            return true;
        }
        if (openSection == null) return false;
        navigate(null);
        return true;
    }

    /** Closes this dialog (its usual animated exit), then hands off to the shell: the layout
     *  editor is an overlay on the real lyrics screen, not a separate window. */
    private void openEditor(int mode) {
        if (onClose != null) onClose.run();
        if (onOpenLayoutEditor != null) onOpenLayoutEditor.accept(mode);
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
            // The editor sections have no rows of their own: they are buttons into the editors.
            if (isEditorSection(section) && !grouped.containsKey(section)) {
                grouped.put(section, new ArrayList<>());
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
        snapshot.put(Settings.DOUBLE_TAP_LIKE, store.get(Settings.DOUBLE_TAP_LIKE));
        snapshot.put(Settings.TAP_SEEK_MODE, store.get(Settings.TAP_SEEK_MODE));
        return snapshot.build();
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

    /**
     * The sections whose look is edited on the screen itself: in the panel they are single
     * buttons that open their editor straight away, not sections to expand.
     */
    private static boolean isEditorSection(Settings.Section section) {
        return section == Settings.LYRICS_SCREEN || section == Settings.NOW_PLAYING;
    }

    private void openEditorFor(Settings.Section section) {
        openEditor(section == Settings.NOW_PLAYING ? EDITOR_CARD : EDITOR_LYRICS);
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
            sources.inlineBlock(content);
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
        if (setting == Settings.CACHE_SIZE) {
            // The limit row with the stored-lyrics block under it, one keyed unit: patchRow
            // still finds the limit's summary inside, and the block refreshes itself.
            LinearLayout unit = new LinearLayout(context);
            unit.setOrientation(LinearLayout.VERTICAL);
            rows.selectorRow(unit, Settings.CACHE_SIZE);
            if (unit.getChildCount() > 0) unit.getChildAt(0).setTag(null);
            cache.block(unit);
            unit.setTag(PanelTags.row(Settings.CACHE_SIZE));
            content.addView(unit, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        if (setting == Settings.LYRICS_FONT_CUSTOM_PATH) {
            lyricsFontPathRow(content);
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
        // On a section's page only that section is on screen; a change elsewhere shows when its
        // page is opened.
        if (openSection != null && !isOpen(target)) return;
        captureAnchor();
        boolean expanded = isOpen(target);
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
        if (items == null) items = new ArrayList<>();
        if (items.isEmpty()) {
            rebuildSections(); // defensive: rendered section without visible settings
            return;
        }
        int cardIdx = indexOfChildByTag(PanelTags.card(target));
        if (cardIdx < 0 || !(sectionsContainer.getChildAt(cardIdx) instanceof LinearLayout)) {
            // A section just expanded: its card is built whole, by the same code as a full
            // render, rather than row by row here - which left out everything in a card that is
            // not a setting row until the panel was reopened.
            appendSectionCard(sectionsContainer, target, items, headerIdx + 1);
            return;
        }
        LinearLayout card = (LinearLayout) sectionsContainer.getChildAt(cardIdx);
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
        // Double tap is one gesture: turning either use on turns the other off.
        if (setting == Settings.DOUBLE_TAP_LIKE && Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))
                && "Double tap".equals(store.get(Settings.TAP_SEEK_MODE))) {
            writer.put(Settings.TAP_SEEK_MODE, "Off");
        }
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
        return expanded ? buildPageBar(section) : buildSectionTile(section);
    }

    /**
     * A section in the list: its icon on a tinted square, its name in plain case, and a line
     * saying what is inside - the first few of its settings, or where the button goes.
     */
    private LinearLayout buildSectionTile(Settings.Section section) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(64));
        row.setPadding(style.dp(12), style.dp(10), style.dp(10), style.dp(10));
        android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
        tile.setCornerRadius(style.dp(16));
        tile.setColor(0x10FFFFFF);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), tile, null));
        row.setTag(PanelTags.header(section));

        boolean aiLit = section == Settings.AI && aiReady();
        Kind sectionIcon = PanelStyle.sectionIcon(section);
        android.widget.FrameLayout badge = new android.widget.FrameLayout(context);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(style.dp(11));
        badgeBg.setColor(isEditorSection(section) || aiLit ? 0x331ED760 : 0x1AFFFFFF);
        badge.setBackground(badgeBg);
        if (sectionIcon != null) {
            ImageView icon = style.kindView(sectionIcon,
                    isEditorSection(section) || aiLit ? PanelStyle.COL_ACCENT : PanelStyle.COL_TITLE, 20);
            if (section == Settings.AI) aiBadgeView = icon;
            badge.addView(icon, new android.widget.FrameLayout.LayoutParams(
                    style.dp(24), style.dp(24), Gravity.CENTER));
        }
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(style.dp(40), style.dp(40));
        badgeLp.rightMargin = style.dp(14);
        row.addView(badge, badgeLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(style.text(uiStrings.section(section), 16, PanelStyle.COL_TITLE, true));
        String summary = tileSummary(section);
        if (section == Settings.LYRICS_SOURCES) {
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = style.dp(4);
            texts.addView(sources.tileLine(), subLp);
        } else if (!summary.isEmpty()) {
            TextView sub = style.text(summary, 13, PanelStyle.COL_SUMMARY, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = style.dp(2);
            texts.addView(sub, subLp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isEditorSection(section)) {
            // A button into the editor: one tap opens it, there is no page.
            row.addView(style.kindView(Kind.CHEVRONS_RIGHT, PanelStyle.COL_ACCENT, 16),
                    new LinearLayout.LayoutParams(style.dp(28), style.dp(28)));
            row.setOnClickListener(v -> openEditorFor(section));
        } else {
            row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 16),
                    new LinearLayout.LayoutParams(style.dp(28), style.dp(28)));
            row.setOnClickListener(v -> navigate(section));
        }
        return row;
    }

    /** What a tile says is inside: where an editor button goes, else its first few settings. */
    private String tileSummary(Settings.Section section) {
        if (section == Settings.LYRICS_SCREEN) return uiStrings.get("settings_layout_editor", "Layout editor…");
        if (section == Settings.NOW_PLAYING) return uiStrings.get("settings_card_editor", "Now playing card editor…");
        if (section == Settings.DEBUG) {
            return uiStrings.get("settings_tile_debug", "Version, cache and diagnostics");
        }
        List<Settings.Setting<?>> items = groupVisibleSettings().get(section);
        if (items == null || items.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Settings.Setting<?> setting : items) {
            if (setting == Settings.LYRICS_SOURCE_OVERRIDE || setting == Settings.LYRICS_SOURCE_ORDER) continue;
            if (shown == 3) {
                out.append(" \u00b7 \u2026");
                break;
            }
            if (shown > 0) out.append(" \u00b7 ");
            out.append(uiStrings.setting(setting));
            shown++;
        }
        return out.toString();
    }

    /** A section's page: back to the list, and its name large. */
    private LinearLayout buildPageBar(Settings.Section section) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(48));
        row.setPadding(0, style.dp(2), style.dp(4), style.dp(8));
        row.setTag(PanelTags.header(section));
        ImageView back = style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_TITLE, 20);
        back.setRotation(180f);
        back.setContentDescription(uiStrings.get("settings_back", "Back"));
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        android.graphics.drawable.GradientDrawable backBg = new android.graphics.drawable.GradientDrawable();
        backBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        backBg.setColor(0x1AFFFFFF);
        back.setBackground(backBg);
        back.setOnClickListener(v -> navigate(null));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(style.dp(36), style.dp(36));
        backLp.rightMargin = style.dp(12);
        row.addView(back, backLp);
        Kind sectionIcon = PanelStyle.sectionIcon(section);
        if (sectionIcon != null) {
            ImageView icon = style.kindView(sectionIcon,
                    section == Settings.AI && aiReady() ? PanelStyle.COL_ACCENT : PanelStyle.COL_SECTION, 18);
            if (section == Settings.AI) aiBadgeView = icon;
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(22), style.dp(22));
            iconLp.rightMargin = style.dp(8);
            row.addView(icon, iconLp);
        }
        row.addView(style.text(uiStrings.section(section), 20, PanelStyle.COL_TITLE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void appendSectionHeader(LinearLayout parent, Settings.Section section,
                                     boolean expanded, int at) {
        View row = buildSectionHeader(section, expanded);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = style.dp(expanded ? 0 : 8);
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

    private void lyricsFontPathRow(LinearLayout content) {
        String path = store.get(Settings.LYRICS_FONT_CUSTOM_PATH);
        String display = path == null || path.isEmpty()
                ? uiStrings.get("settings_lyrics_font_path_absent", "Not set") : path;
        List<AiSettingsRows.IconAction> actions = new ArrayList<>();
        actions.add(new AiSettingsRows.IconAction(Kind.EDIT,
                uiStrings.get("settings_lyrics_font_path_edit", "Edit font path"),
                v -> dialogs.promptLyricsFontPath()));
        rows.aiFieldRow(content, uiStrings.setting(Settings.LYRICS_FONT_CUSTOM_PATH),
                display, false, Settings.LYRICS_FONT_CUSTOM_PATH.key,
                v -> dialogs.promptLyricsFontPath(),
                actions.toArray(new AiSettingsRows.IconAction[0]));
        String coverage = fontCoverageSummaryForPanel(path);
        if (!coverage.isEmpty()) {
            TextView cov = style.text(coverage, 12, PanelStyle.COL_SUMMARY, false);
            cov.setPadding(style.dp(52), 0, style.dp(16), style.dp(12));
            content.addView(cov, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private String fontCoverageSummaryForPanel(String path) {
        if (path == null || path.isEmpty()) return "";
        android.graphics.Typeface typeface = null;
        java.io.File file = new java.io.File(path);
        if (file.isFile()) {
            try {
                typeface = android.graphics.Typeface.createFromFile(file);
            } catch (Throwable ignored) {
            }
        }
        if (typeface == null) typeface = android.graphics.Typeface.create(path, android.graphics.Typeface.NORMAL);
        java.util.List<String> missing = com.eza.spicyex.lyrics.LyricsFontValidator.missingScripts(typeface);
        return missing.isEmpty()
                ? uiStrings.get("settings_lyrics_font_check_all_covered", "Covers every supported language")
                : uiStrings.get("settings_lyrics_font_check_missing", "Falls back for") + ": "
                        + String.join(", ", missing);
    }

    // --- Diagnostics card ---

    private void renderActions(LinearLayout content) {
        rows.actionRow(content, Kind.BUG,
                DiagnosticReportingDialog.reportProblemLabel(context, store),
                v -> DiagnosticReportingDialog.show(context, store));
        rows.actionRow(content, null,
                uiStrings.get("settings_action_resync_timing", "Reset lyrics sync"),
                v -> {
                    writer.put(Settings.SYNC_OFFSET_MS, 0);
                    if (onResyncTiming != null) onResyncTiming.run();
                    android.widget.Toast.makeText(context,
                            uiStrings.get("settings_resync_timing_done", "Lyrics sync reset"),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
        rows.actionRow(content, null,
                uiStrings.get("settings_action_ad_music_test", "Play / stop ad replacement music"),
                v -> {
                    boolean playing = com.eza.spicyex.hooks.AdMusicPreview.toggle(
                            store.get(Settings.AD_MUSIC_THEME));
                    android.widget.Toast.makeText(context, uiStrings.get(playing
                                    ? "settings_ad_music_test_playing" : "settings_ad_music_test_stopped",
                            playing ? "Playing a new piece" : "Stopped"),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
        // Cache clears live with the cache itself, on the Lyrics sources page (CacheManager).
        rows.actionRow(content, Kind.EXTERNAL_LINK,
                uiStrings.get("settings_action_open_github", "Open GitHub"), v -> openGithub());
    }

    @Override public void clearCache(CacheClearKind kind) {
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

    @Override public String cacheLimitLabel() {
        return uiStrings.option(Settings.CACHE_SIZE, store.get(Settings.CACHE_SIZE));
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
        // Seeking on double tap takes the gesture back from double-tap to like.
        if (setting == Settings.TAP_SEEK_MODE && "Double tap".equals(value)
                && Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))) {
            writer.put(Settings.DOUBLE_TAP_LIKE, false);
        }
        if (setting == Settings.UI_LANGUAGE) {
            uiStrings = UiLanguage.strings(context, value);
            if (panelTitle != null) panelTitle.setText(uiStrings.appName());
        }
    }

    @Override public void afterSettingChosen(Settings.Setting<?> setting) {
        onSettingChanged(setting);
    }

    @Override public String rowSummaryFor(Settings.StringSetting setting, String value) {
        return setting == Settings.CACHE_SIZE ? cacheSizeSummary() : selectorSummary(setting, value);
    }

    /** The shared double tap: warns on the like switch what turning it on turns off. Karaoke
     *  lyrics are Labs: they are the original recording's, timed to it, not to the karaoke one. */
    @Override public String switchNote(Settings.BooleanSetting setting) {
        if (setting == Settings.KARAOKE_ORIGINAL_LYRICS) {
            return uiStrings.get("settings_karaoke_labs_note",
                    "Labs \u00b7 these are the original song's lyrics, so they may not stay in sync "
                            + "with the karaoke recording.");
        }
        if (setting == Settings.DOUBLE_TAP_LIKE && "Double tap".equals(store.get(Settings.TAP_SEEK_MODE))
                && !Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))) {
            return uiStrings.get("settings_double_tap_like_note",
                    "Double tap already seeks to a line. Turning this on turns that off.");
        }
        return null;
    }

    /** Tap-to-seek on double tap does nothing while double tap likes; the row says so. */
    @Override public String selectorSummary(Settings.StringSetting setting, String value) {
        if (setting == Settings.TAP_SEEK_MODE && "Double tap".equals(value)
                && Boolean.TRUE.equals(store.get(Settings.DOUBLE_TAP_LIKE))) {
            return uiStrings.get("settings_tap_seek_taken_by_like", "Off \u00b7 double tap likes");
        }
        return labelFor(setting, value);
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
