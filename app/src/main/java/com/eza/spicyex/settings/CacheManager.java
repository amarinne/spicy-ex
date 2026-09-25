package com.eza.spicyex.settings;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.CacheStoragePolicy;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.PanelDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cache, managed where the lyrics come from: a block on the Lyrics sources page with what is
 * stored (size against the limit, songs per provider), and a "See all" browser listing every
 * cached song by song or by provider. Deleting lives in the browser only: a song, a provider's
 * songs, or from "Delete all" every song's lyrics or one derived layer (translations, readings,
 * AI results).
 *
 * <p>Every store read runs on one background thread; the views only ever get finished results.
 */
public final class CacheManager {

    public interface Host {
        PanelStyle style();

        SettingsUiStrings strings();

        /** Runs a live-session clear (it also refreshes what is on screen). */
        void clearCache(CacheClearKind kind);

        /** The "Cache size limit" option's label, e.g. "128 MB" or "No limit". */
        String cacheLimitLabel();
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SpicyCacheManager");
        thread.setDaemon(true);
        return thread;
    });
    private static final int COL_DANGER = 0xFFFF7A7A;

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    // The block on the page, refreshed in place after a clear.
    private View usageFill;
    private LinearLayout usageTrack;
    private TextView usageText;
    private TextView breakdownText;

    public CacheManager(Host host) {
        this.host = host;
    }

    // --- The block on the Lyrics sources page ---

    public void block(LinearLayout content) {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final Context context = style.context();

        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(style.dp(16));
        bg.setColor(0x0DFFFFFF);
        block.setBackground(bg);

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = style.kindView(Kind.DISC_3, PanelStyle.COL_SECTION, 16);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(style.dp(18), style.dp(18));
        iconLp.rightMargin = style.dp(8);
        head.addView(icon, iconLp);
        head.addView(style.text(strings.get("settings_cache_title", "Stored lyrics"), 15,
                PanelStyle.COL_TITLE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView more = link(strings.get("settings_cache_more", "See all"), PanelStyle.COL_ACCENT);
        more.setOnClickListener(v -> openBrowser());
        head.addView(more);
        block.addView(head);

        usageTrack = new LinearLayout(context);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(style.dp(3));
        track.setColor(0x1AFFFFFF);
        usageTrack.setBackground(track);
        usageFill = new View(context);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(style.dp(3));
        fill.setColor(PanelStyle.COL_ACCENT);
        usageFill.setBackground(fill);
        usageTrack.addView(usageFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        usageTrack.setWeightSum(1f);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(6));
        trackLp.topMargin = style.dp(12);
        block.addView(usageTrack, trackLp);

        usageText = style.text("…", 13, PanelStyle.COL_TITLE, false);
        LinearLayout.LayoutParams usageLp = wrap();
        usageLp.topMargin = style.dp(8);
        block.addView(usageText, usageLp);
        breakdownText = style.text("", 12, PanelStyle.COL_SUMMARY, false);
        breakdownText.setMaxLines(2);
        breakdownText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams breakdownLp = wrap();
        breakdownLp.topMargin = style.dp(2);
        block.addView(breakdownText, breakdownLp);

        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.topMargin = style.dp(4);
        blockLp.bottomMargin = style.dp(6);
        content.addView(block, blockLp);
        refresh();
    }

    /** Re-reads the store and repaints the block. */
    public void refresh() {
        final Context context = host.style().context();
        IO.execute(() -> {
            final long used = CacheStoragePolicy.storedTotal(context);
            final long budget = CacheStoragePolicy.totalBudget(context);
            final long ai = AIPaidArtifactCache.usageBytes(context);
            final List<CanonicalSourceCache.Entry> entries = CanonicalSourceCache.entries(context);
            main.post(() -> paintBlock(used, budget, ai, entries));
        });
    }

    private void paintBlock(long used, long budget, long ai, List<CanonicalSourceCache.Entry> entries) {
        if (usageText == null) return;
        SettingsUiStrings strings = host.strings();
        boolean limited = budget != CacheStoragePolicy.UNLIMITED && budget > 0;
        float fraction = limited ? Math.min(1f, used / (float) budget) : 0f;
        // A sliver stays visible for a cache that is not empty, however small.
        if (used > 0 && fraction < 0.02f && limited) fraction = 0.02f;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) usageFill.getLayoutParams();
        lp.weight = fraction;
        usageFill.setLayoutParams(lp);
        usageTrack.setVisibility(limited ? View.VISIBLE : View.GONE);
        String usage = limited
                ? strings.format("settings_cache_usage", "%1$s of %2$s", CacheStoragePolicy.formatBytes(used),
                        host.cacheLimitLabel())
                : strings.format("settings_cache_usage_unlimited", "%1$s used",
                        CacheStoragePolicy.formatBytes(used));
        if (ai > 0) {
            usage += " · " + strings.format("settings_cache_usage_ai", "AI %1$s",
                    CacheStoragePolicy.formatBytes(ai));
        }
        usageText.setText(usage);

        if (entries.isEmpty()) {
            breakdownText.setText(strings.get("settings_cache_empty", "No lyrics stored yet"));
            return;
        }
        StringBuilder out = new StringBuilder(songs(entries.size()));
        List<Group> groups = groupBySource(entries);
        int shown = 0;
        for (Group group : groups) {
            if (shown == 3) {
                out.append(" · +").append(groups.size() - shown);
                break;
            }
            out.append(" · ").append(group.label).append(' ').append(group.entries.size());
            shown++;
        }
        breakdownText.setText(out.toString());
    }

    private void clearKind(CacheClearKind kind) {
        host.clearCache(kind);
        toast(host.strings().get("settings_cache_cleared", "Cleared"));
        refresh();
    }

    private void confirmClearAllLyrics(Runnable after) {
        SettingsUiStrings strings = host.strings();
        confirm(strings.get("settings_cache_clear_lyrics_confirm",
                "Delete every stored song's lyrics? They download again the next time each song plays."),
                () -> {
                    final Context context = host.style().context();
                    IO.execute(() -> {
                        CanonicalSourceCache.clear(context);
                        LyricsResponseCache.clear(context);
                        main.post(() -> {
                            // Reloads the song on screen from its providers.
                            host.clearCache(CacheClearKind.LYRICS_RESPONSE);
                            toast(host.strings().get("settings_cache_cleared", "Cleared"));
                            if (after != null) after.run();
                        });
                    });
                });
    }

    // --- The browser ---

    private static final int VIEW_SONGS = 0;
    private static final int VIEW_PROVIDERS = 1;
    private static final int SORT_RECENT = 0;
    private static final int SORT_TITLE = 1;
    private static final int SORT_SIZE = 2;

    /** State of one open browser. */
    private final class Browser {
        final PanelDialog dialog;
        final LinearLayout list;
        final LinearLayout sortRow;
        final TextView total;
        final TextView clearAll;
        final List<TextView> viewTabs = new ArrayList<>();
        final List<TextView> sortChips = new ArrayList<>();
        final java.util.Set<String> expanded = new java.util.HashSet<>();
        List<CanonicalSourceCache.Entry> entries;
        int view = VIEW_SONGS;
        int sort = SORT_RECENT;

        Browser(PanelDialog dialog, LinearLayout list, LinearLayout sortRow, TextView total, TextView clearAll) {
            this.dialog = dialog;
            this.list = list;
            this.sortRow = sortRow;
            this.total = total;
            this.clearAll = clearAll;
        }
    }

    public void openBrowser() {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final Context context = style.context();
        PanelDialog dialog = new PanelDialog(context,
                strings.get("settings_cache_browser_title", "Stored lyrics")).tall();

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);

        LinearLayout segments = new LinearLayout(context);
        segments.setPadding(style.dp(3), style.dp(3), style.dp(3), style.dp(3));
        GradientDrawable segBg = new GradientDrawable();
        segBg.setCornerRadius(style.dp(20));
        segBg.setColor(0x1AFFFFFF);
        segments.setBackground(segBg);
        top.addView(segments, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sortRow = new LinearLayout(context);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sortLp.topMargin = style.dp(10);
        top.addView(sortRow, sortLp);

        LinearLayout summary = new LinearLayout(context);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        TextView total = style.text("…", 13, PanelStyle.COL_SUMMARY, false);
        summary.addView(total, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clearAll = link(strings.get("settings_cache_delete_all", "Delete all"), COL_DANGER);
        summary.addView(clearAll);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = style.dp(10);
        top.addView(summary, summaryLp);
        dialog.pinned(top);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        dialog.add(list);
        dialog.secondary(strings.get("settings_cache_details_close", "Close"), null);

        final Browser browser = new Browser(dialog, list, sortRow, total, clearAll);
        String[] views = {strings.get("settings_cache_by_song", "By song"),
                strings.get("settings_cache_by_provider", "By provider")};
        for (int i = 0; i < views.length; i++) {
            final int index = i;
            TextView tab = style.text(views[i], 14, 0xCCFFFFFF, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(style.dp(8), style.dp(8), style.dp(8), style.dp(8));
            tab.setOnClickListener(v -> {
                if (browser.view == index) return;
                browser.view = index;
                render(browser);
            });
            browser.viewTabs.add(tab);
            segments.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        sortRow.addView(style.text(strings.get("settings_cache_sort", "Sort"), 12,
                PanelStyle.COL_SECTION, true), marginRight(style, 8));
        String[] sorts = {strings.get("settings_cache_sort_recent", "Recent"),
                strings.get("settings_cache_sort_title", "Title"),
                strings.get("settings_cache_sort_size", "Size")};
        for (int i = 0; i < sorts.length; i++) {
            final int index = i;
            TextView chip = style.text(sorts[i], 13, PanelStyle.COL_TITLE, false);
            chip.setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6));
            chip.setOnClickListener(v -> {
                if (browser.sort == index) return;
                browser.sort = index;
                render(browser);
            });
            browser.sortChips.add(chip);
            sortRow.addView(chip, marginRight(style, 6));
        }
        clearAll.setOnClickListener(v -> openClearMenu(browser));

        TextView loading = style.text(strings.get("settings_cache_loading", "Loading…"), 14,
                PanelStyle.COL_SUMMARY, false);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, style.dp(40), 0, style.dp(40));
        list.addView(loading);
        paintTabs(browser);
        dialog.show();

        IO.execute(() -> {
            final List<CanonicalSourceCache.Entry> entries = CanonicalSourceCache.entries(context);
            main.post(() -> {
                browser.entries = entries;
                render(browser);
            });
        });
    }

    /** Everything deletable at once: every song's lyrics, or one derived layer for all songs. */
    private void openClearMenu(final Browser browser) {
        SettingsUiStrings strings = host.strings();
        PanelDialog menu = new PanelDialog(host.style().context(),
                strings.get("settings_cache_delete_all", "Delete all"));
        menu.option(strings.get("settings_cache_clear_lyrics", "All lyrics"), false,
                () -> confirmClearAllLyrics(() -> {
                    browser.entries = new ArrayList<>();
                    render(browser);
                    refresh();
                }));
        menu.option(strings.get("settings_cache_clear_translation", "Translations"), false,
                () -> clearKind(CacheClearKind.TRANSLATION));
        menu.option(strings.get("settings_cache_clear_reading", "Readings"), false,
                () -> clearKind(CacheClearKind.TRANSLITERATION));
        menu.option(strings.get("settings_cache_clear_ai", "AI results"), false,
                () -> confirm(strings.get("settings_cache_clear_ai_confirm",
                                "Delete saved AI translations and readings? Making them again uses your AI quota."),
                        () -> clearKind(CacheClearKind.AI)));
        menu.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        menu.show();
    }

    private void paintTabs(Browser browser) {
        PanelStyle style = host.style();
        for (int i = 0; i < browser.viewTabs.size(); i++) {
            TextView tab = browser.viewTabs.get(i);
            boolean on = i == browser.view;
            tab.setTextColor(on ? 0xFF000000 : 0xCCFFFFFF);
            tab.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            if (on) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(style.dp(17));
                pill.setColor(0xFFFFFFFF);
                tab.setBackground(pill);
            } else {
                tab.setBackground(null);
            }
        }
        // Providers are listed biggest first; sorting applies to the songs view only.
        browser.sortRow.setVisibility(browser.view == VIEW_SONGS ? View.VISIBLE : View.GONE);
        for (int i = 0; i < browser.sortChips.size(); i++) {
            TextView chip = browser.sortChips.get(i);
            boolean on = i == browser.sort;
            chip.setTextColor(on ? PanelStyle.COL_ACCENT : PanelStyle.COL_TITLE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(style.dp(14));
            bg.setColor(on ? 0x261ED760 : 0x12FFFFFF);
            chip.setBackground(bg);
        }
    }

    private void render(Browser browser) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        paintTabs(browser);
        browser.list.removeAllViews();
        List<CanonicalSourceCache.Entry> entries = browser.entries == null
                ? new ArrayList<>() : browser.entries;
        long bytes = 0;
        for (CanonicalSourceCache.Entry entry : entries) bytes += entry.bytes;
        browser.total.setText(songs(entries.size()) + " · " + CacheStoragePolicy.formatBytes(bytes));
        if (entries.isEmpty()) {
            TextView empty = style.text(strings.get("settings_cache_empty", "No lyrics stored yet"), 14,
                    PanelStyle.COL_SUMMARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, style.dp(40), 0, style.dp(40));
            browser.list.addView(empty);
            return;
        }
        if (browser.view == VIEW_SONGS) {
            List<CanonicalSourceCache.Entry> sorted = new ArrayList<>(entries);
            sortEntries(sorted, browser.sort);
            for (CanonicalSourceCache.Entry entry : sorted) {
                browser.list.addView(songRow(browser, entry, true));
            }
        } else {
            for (Group group : groupBySource(entries)) {
                browser.list.addView(providerHeader(browser, group));
                if (browser.expanded.contains(group.label)) {
                    for (CanonicalSourceCache.Entry entry : group.entries) {
                        View row = songRow(browser, entry, false);
                        row.setPadding(row.getPaddingLeft() + style.dp(14), row.getPaddingTop(),
                                row.getPaddingRight(), row.getPaddingBottom());
                        browser.list.addView(row);
                    }
                }
            }
        }
        browser.dialog.scroller().scrollTo(0, 0);
    }

    private View songRow(final Browser browser, final CanonicalSourceCache.Entry entry, boolean withSource) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        Context context = style.context();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(style.dp(6), style.dp(8), 0, style.dp(8));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = style.text(titleOf(entry), 15, PanelStyle.COL_TITLE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        List<String> parts = new ArrayList<>();
        if (!entry.artist.isEmpty()) parts.add(entry.artist);
        if (withSource) parts.add(sourceLabel(entry.source));
        parts.add(CacheStoragePolicy.formatBytes(entry.bytes));
        if (entry.savedAtMs > 0) {
            parts.add(DateUtils.getRelativeTimeSpanString(entry.savedAtMs, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString());
        }
        TextView detail = style.text(TextUtils.join(" · ", parts), 12, PanelStyle.COL_SUMMARY, false);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView delete = style.kindView(Kind.DELETE, PanelStyle.COL_SUMMARY, 18);
        delete.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        delete.setPadding(style.dp(10), style.dp(10), style.dp(10), style.dp(10));
        delete.setContentDescription(strings.get("settings_cache_delete", "Delete"));
        delete.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, oval()));
        delete.setOnClickListener(v -> {
            v.setEnabled(false);
            final Context ctx = host.style().context();
            IO.execute(() -> {
                CanonicalSourceCache.remove(ctx, entry);
                main.post(() -> row.animate().alpha(0f).translationX(row.getWidth() * 0.15f)
                        .setDuration(180).withEndAction(() -> {
                            browser.entries.remove(entry);
                            render(browser);
                            refresh();
                        }).start());
            });
        });
        row.addView(delete, new LinearLayout.LayoutParams(style.dp(40), style.dp(40)));
        return row;
    }

    private View providerHeader(final Browser browser, final Group group) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        Context context = style.context();
        final boolean open = browser.expanded.contains(group.label);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(style.dp(14));
        bg.setColor(0x12FFFFFF);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null));

        ImageView chevron = style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SUMMARY, 16);
        chevron.setRotation(open ? 90f : 0f);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(style.dp(20), style.dp(20));
        chevronLp.rightMargin = style.dp(8);
        row.addView(chevron, chevronLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(style.text(group.label, 15, PanelStyle.COL_TITLE, true));
        texts.addView(style.text(songs(group.entries.size()) + " · "
                + CacheStoragePolicy.formatBytes(group.bytes), 12, PanelStyle.COL_SUMMARY, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView delete = link(strings.get("settings_cache_delete", "Delete"), COL_DANGER);
        delete.setOnClickListener(v -> confirm(strings.format("settings_cache_delete_provider_confirm",
                "Delete the %1$d songs stored from %2$s?", group.entries.size(), group.label), () -> {
            final Context ctx = host.style().context();
            final List<CanonicalSourceCache.Entry> doomed = new ArrayList<>(group.entries);
            IO.execute(() -> {
                for (CanonicalSourceCache.Entry entry : doomed) CanonicalSourceCache.remove(ctx, entry);
                main.post(() -> {
                    browser.entries.removeAll(doomed);
                    render(browser);
                    refresh();
                    toast(host.strings().get("settings_cache_cleared", "Cleared"));
                });
            });
        }));
        row.addView(delete);
        row.setOnClickListener(v -> {
            if (open) browser.expanded.remove(group.label);
            else browser.expanded.add(group.label);
            render(browser);
        });
        LinearLayout wrapper = new LinearLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = style.dp(4);
        lp.bottomMargin = style.dp(4);
        wrapper.addView(row, lp);
        return wrapper;
    }

    // --- Grouping and sorting ---

    static final class Group {
        final String label;
        final List<CanonicalSourceCache.Entry> entries = new ArrayList<>();
        long bytes;

        Group(String label) {
            this.label = label;
        }
    }

    /** Songs per provider, the provider with the most songs first. */
    static List<Group> groupBySource(List<CanonicalSourceCache.Entry> entries) {
        Map<String, Group> byLabel = new LinkedHashMap<>();
        for (CanonicalSourceCache.Entry entry : entries) {
            String label = sourceLabel(entry.source);
            Group group = byLabel.get(label);
            if (group == null) {
                group = new Group(label);
                byLabel.put(label, group);
            }
            group.entries.add(entry);
            group.bytes += entry.bytes;
        }
        List<Group> groups = new ArrayList<>(byLabel.values());
        Collections.sort(groups, (a, b) -> b.entries.size() != a.entries.size()
                ? b.entries.size() - a.entries.size() : a.label.compareToIgnoreCase(b.label));
        return groups;
    }

    static void sortEntries(List<CanonicalSourceCache.Entry> entries, int sort) {
        if (sort == SORT_TITLE) {
            final java.text.Collator collator = java.text.Collator.getInstance();
            Collections.sort(entries, (a, b) -> collator.compare(titleOf(a), titleOf(b)));
        } else if (sort == SORT_SIZE) {
            Collections.sort(entries, (a, b) -> Long.compare(b.bytes, a.bytes));
        } else {
            Collections.sort(entries, (a, b) -> Long.compare(b.savedAtMs, a.savedAtMs));
        }
    }

    /** Title, or for songs stored before titles were recorded, their first line in quotes. */
    static String titleOf(CanonicalSourceCache.Entry entry) {
        if (!entry.title.isEmpty()) return entry.title;
        if (!entry.firstLine.isEmpty()) return "“" + entry.firstLine + "”";
        return "—";
    }

    /** "apple_music", "Apple Music", "LRCLIB"... → the provider's display name. */
    static String sourceLabel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "?";
        LyricsSourcePreferences.Source source = LyricsSourcePreferences.Source.parse(
                value.replace(' ', '_').toLowerCase(Locale.ROOT));
        return source == null ? value : SourceOrderEditor.sourceLabel(source);
    }

    // --- Small views ---

    private String songs(int count) {
        return host.strings().format("settings_cache_songs", "%1$d songs", count);
    }

    private TextView link(String label, int color) {
        PanelStyle style = host.style();
        TextView view = style.text(label, 13, color, true);
        view.setPaintFlags(view.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        view.setPadding(style.dp(8), style.dp(6), style.dp(2), style.dp(6));
        return view;
    }

    private TextView chip(String label, boolean danger, Runnable action) {
        PanelStyle style = host.style();
        TextView chip = style.text(label, 13, danger ? COL_DANGER : PanelStyle.COL_TITLE, false);
        chip.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(style.dp(16));
        bg.setColor(danger ? 0x1FFF7A7A : 0x14FFFFFF);
        chip.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null));
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = style.dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void confirm(String message, Runnable onYes) {
        SettingsUiStrings strings = host.strings();
        new PanelDialog(host.style().context(), strings.get("settings_cache_delete", "Delete"))
                .paragraph(message)
                .primary(strings.get("settings_cache_delete", "Delete"), onYes)
                .secondary(strings.get("settings_ai_cancel", "Cancel"), null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(host.style().context(), message, Toast.LENGTH_SHORT).show();
    }

    private static GradientDrawable oval() {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return mask;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams marginRight(PanelStyle style, int dp) {
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = style.dp(dp);
        return lp;
    }
}
