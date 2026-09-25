package com.eza.spicyex.settings;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.GlossyToggle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;
import com.eza.spicyex.ui.ActionIconDrawable.Kind;
import com.eza.spicyex.ui.PanelDialog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Owner of the merged "Lyrics source" row and its ranking/order/toggle dialog.
 *
 * <p>This is the panel's one genuinely stateful editor: a working copy of ranking, order, and
 * enabled flags is edited locally and committed only on Save, through
 * {@link SourcePreferencesAdapter}, because the values span two preference namespaces.
 *
 * <p>Selection is tracked by <em>persisted value</em>, never by rendered label text. The old
 * implementation decided which radio was lit by comparing displayed strings, so a localized
 * label silently broke selection. Display labels resolve through the locale; the value that is
 * read, compared, and saved stays the stable persisted token.
 *
 * <p>Retired sources (Spicy's remote path) stay in the backing order for compatibility with
 * old persisted data but are never shown. Because the visible list is therefore a projection,
 * a visible drop position maps back to a full-order index by identity.
 */
public final class SourceOrderEditor {
    /** The two persisted ranking tokens. They are values, not labels; do not localize them. */
    private static final String MODE_SMART = "Auto";
    private static final String MODE_USER_ORDER = "Source order";

    /** What the editor needs from the panel. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        /** Ranking/order/enabled were saved; refresh the owning section. */
        void onSourcesCommitted();
    }

    private final Host host;

    public SourceOrderEditor(Host host) {
        this.host = host;
    }

    /**
     * {@code enumSetting} is declared as {@code Setting<String>} but is constructed as a
     * {@link Settings.StringSetting}; the option-label lookup needs the concrete type.
     */
    private static Settings.StringSetting modeSetting() {
        return (Settings.StringSetting) Settings.LYRICS_SOURCE_MODE;
    }

    // --- Inline block ---

    /**
     * The sources, right on the Lyrics sources page: how the source is chosen (Smart or My
     * order) as a two-way switch with a line saying what it does, then every source with its
     * switch, a line about it, and - in My order - its rank and a drag handle. Changes apply at
     * once. (This used to be one summary row opening a dialog with a Save button.)
     */
    public void inlineBlock(LinearLayout content) {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final android.content.Context context = style.context();
        final String ranking = rankingValue();
        final boolean byOrder = MODE_USER_ORDER.equals(ranking);
        final ArrayList<Source> order = new ArrayList<>(LyricsSourcePreferences.sourceOrder(context));

        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setTag(PanelTags.row(Settings.LYRICS_SOURCE_MODE));
        block.setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(6));

        // How the source is chosen.
        block.addView(caption(strings.get("settings_source_ranking_title", "Ranking")));
        LinearLayout segments = new LinearLayout(context);
        segments.setPadding(style.dp(3), style.dp(3), style.dp(3), style.dp(3));
        android.graphics.drawable.GradientDrawable segBg = new android.graphics.drawable.GradientDrawable();
        segBg.setCornerRadius(style.dp(20));
        segBg.setColor(0x1AFFFFFF);
        segments.setBackground(segBg);
        String[][] modes = {
                {MODE_SMART, strings.get("settings_source_rank_smart", "Smart")},
                {MODE_USER_ORDER, strings.get("settings_source_rank_order", "My order")}};
        for (String[] mode : modes) {
            boolean on = mode[0].equals(ranking);
            TextView segment = style.text(mode[1], 14, on ? 0xFF000000 : 0xCCFFFFFF, on);
            segment.setGravity(android.view.Gravity.CENTER);
            segment.setPadding(style.dp(8), style.dp(8), style.dp(8), style.dp(8));
            if (on) {
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setCornerRadius(style.dp(17));
                pill.setColor(0xFFFFFFFF);
                segment.setBackground(pill);
            }
            final String value = mode[0];
            segment.setOnClickListener(v -> {
                if (!value.equals(rankingValue())) commit(value, order, currentEnabled());
            });
            segments.addView(segment, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        block.addView(segments, matchWrap(style, 6));
        TextView modeLine = style.text(byOrder
                        ? capitalized(strings.get("settings_source_ranking_order_desc", "follow the order below"))
                        : capitalized(strings.get("settings_source_ranking_smart_desc",
                                "compares source quality, selects best match")),
                13, PanelStyle.COL_SUMMARY, false);
        block.addView(modeLine, matchWrap(style, 16));

        // The sources.
        int total = 0;
        int on = 0;
        for (Source source : order) {
            if (source == Source.SPICY) continue;
            total++;
            if (LyricsSourcePreferences.sourceEnabled(context, source)) on++;
        }
        LinearLayout head = new LinearLayout(context);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(caption(strings.get("settings_source_list_title", "Sources")),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(style.text(String.format(java.util.Locale.ROOT,
                strings.get("settings_source_count", "%1$d of %2$d on"), on, total),
                12, PanelStyle.COL_SUMMARY, false));
        block.addView(head);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        block.addView(list, matchWrap(style, 0));
        int rank = 0;
        for (final Source source : order) {
            // Spicy's remote path is retired from the user-selectable set
            if (source == Source.SPICY) continue;
            boolean enabled = LyricsSourcePreferences.sourceEnabled(context, source);
            if (enabled) rank++;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(style.dp(10), style.dp(10), style.dp(6), style.dp(10));
            row.setTag(source);
            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
            rowBg.setCornerRadius(style.dp(14));
            rowBg.setColor(enabled ? 0x14FFFFFF : 0x08FFFFFF);
            row.setBackground(rowBg);

            if (byOrder) {
                TextView badge = style.text(enabled ? String.valueOf(rank) : "–", 13,
                        enabled ? 0xFF000000 : PanelStyle.COL_SUMMARY, true);
                badge.setGravity(android.view.Gravity.CENTER);
                android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
                badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                badgeBg.setColor(enabled ? PanelStyle.COL_ACCENT : 0x1AFFFFFF);
                badge.setBackground(badgeBg);
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
                badgeLp.rightMargin = style.dp(12);
                row.addView(badge, badgeLp);
            }

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(style.text(sourceLabel(source), 16,
                    enabled ? PanelStyle.COL_TITLE : PanelStyle.COL_SUMMARY, true));
            TextView about = style.text(sourceDescription(source), 12, PanelStyle.COL_SUMMARY, false);
            LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aboutLp.topMargin = style.dp(1);
            texts.addView(about, aboutLp);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            GlossyToggle toggle = new GlossyToggle(context);
            toggle.setAccent(PanelStyle.COL_ACCENT);
            toggle.setChecked(enabled, false);
            toggle.setOnChangeListener(() -> {
                EnumMap<Source, Boolean> next = currentEnabled();
                next.put(source, toggle.isChecked());
                // After the switch's own slide: the block re-renders on commit.
                toggle.postDelayed(() -> commit(rankingValue(), order, next), 180);
            });
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
            row.addView(toggle, new LinearLayout.LayoutParams(style.dp(44), style.dp(30)));

            if (byOrder) {
                ImageView grip = style.kindView(Kind.CHEVRONS_UP_DOWN, PanelStyle.COL_SUMMARY, 20);
                grip.setContentDescription(strings.get("settings_source_drag", "Drag to reorder"));
                grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(style.dp(36), style.dp(40));
                gripLp.leftMargin = style.dp(4);
                row.addView(grip, gripLp);
                attachSourceDrag(grip, row, list, order,
                        () -> commit(rankingValue(), order, currentEnabled()));
            }
            list.addView(row, matchWrap(style, 6));
        }
        content.addView(block, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * The Lyrics sources tile's second line: how the source is picked as a small pill, then the
     * providers that are on, in their order - "[Smart]  Apple Music › Musixmatch › LRCLIB +2".
     */
    public View tileLine() {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final android.content.Context context = style.context();
        boolean byOrder = MODE_USER_ORDER.equals(rankingValue());

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView pill = style.text(byOrder
                        ? strings.get("settings_source_rank_order", "My order")
                        : strings.get("settings_source_rank_smart", "Smart"),
                11, PanelStyle.COL_ACCENT, true);
        pill.setSingleLine(true);
        pill.setPadding(style.dp(7), style.dp(1), style.dp(7), style.dp(2));
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setCornerRadius(style.dp(8));
        pillBg.setColor(0x261ED760);
        pill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.rightMargin = style.dp(8);
        line.addView(pill, pillLp);

        ArrayList<String> names = new ArrayList<>();
        for (Source source : LyricsSourcePreferences.sourceOrder(context)) {
            if (source == Source.SPICY) continue;
            if (LyricsSourcePreferences.sourceEnabled(context, source)) names.add(sourceLabel(source));
        }
        StringBuilder text = new StringBuilder();
        if (names.isEmpty()) {
            text.append(strings.get("settings_source_none_on", "No sources on"));
        } else {
            // Smart has no fixed order, so its providers read as a set rather than a chain.
            String join = byOrder ? "  \u203a  " : ", ";
            int shown = Math.min(3, names.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) text.append(join);
                text.append(names.get(i));
            }
            if (names.size() > shown) text.append("  +").append(names.size() - shown);
        }
        TextView list = style.text(text.toString(), 13, PanelStyle.COL_SUMMARY, false);
        list.setSingleLine(true);
        list.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    private TextView caption(String label) {
        PanelStyle style = host.style();
        TextView view = style.text(label, 13, PanelStyle.COL_SECTION, true);
        view.setPadding(style.dp(2), 0, 0, style.dp(8));
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap(PanelStyle style, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = style.dp(bottomDp);
        return lp;
    }

    private static String capitalized(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private EnumMap<Source, Boolean> currentEnabled() {
        EnumMap<Source, Boolean> enabled = new EnumMap<>(Source.class);
        for (Source source : Source.values()) {
            enabled.put(source, LyricsSourcePreferences.sourceEnabled(host.style().context(), source));
        }
        return enabled;
    }

    /** Applies at once and re-renders the block (through the owning section). */
    private void commit(String ranking, List<Source> order, EnumMap<Source, Boolean> enabled) {
        sourceAdapter().commit(host.writer(), new SourcePreferencesAdapter.Commit(
                ranking, new ArrayList<>(order), enabled));
        host.onSourcesCommitted();
    }

    /** One line about what each source is good for. */
    public String sourceDescription(Source source) {
        SettingsUiStrings strings = host.strings();
        switch (source) {
            case APPLE_MUSIC:
                return strings.get("settings_source_desc_apple", "Apple Music's lyrics, often word by word");
            case SPOTIFY:
                return strings.get("settings_source_desc_spotify", "Spotify's own lyrics");
            case MUSIXMATCH:
                return strings.get("settings_source_desc_musixmatch", "Musixmatch's large catalog");
            case NETEASE:
                return strings.get("settings_source_desc_netease", "NetEase Cloud Music - strong for Chinese songs");
            case QQ_MUSIC:
                return strings.get("settings_source_desc_qq", "QQ Music - strong for Chinese songs");
            case LRCLIB:
                return strings.get("settings_source_desc_lrclib", "Community-made synced lyrics");
            default:
                return "";
        }
    }

    /** Provider names are brands and stay as authored; only the surrounding copy localizes. */
    public static String sourceLabel(Source source) {
        if (source == Source.APPLE_MUSIC) return "Apple Music";
        if (source == Source.SPICY) return "Spicy";
        if (source == Source.SPOTIFY) return "Spotify";
        if (source == Source.AMLL) return "AMLL";
        if (source == Source.NETEASE) return "NetEase";
        if (source == Source.QQ_MUSIC) return "QQ Music";
        if (source == Source.MUSIXMATCH) return "Musixmatch";
        return "LRCLIB";
    }

    /** Current ranking as a persisted token; anything unrecognized reads as Smart. */
    private String rankingValue() {
        String stored = host.store().get(Settings.LYRICS_SOURCE_MODE);
        return MODE_USER_ORDER.equals(stored) ? MODE_USER_ORDER : MODE_SMART;
    }

    // --- Drag to reorder ---

    /**
     * Grip drag with sliding neighbors: the dragged row follows the finger via translationY
     * while the rows it passes slide out of the way. Order commits on release.
     */
    private void attachSourceDrag(View handle, LinearLayout row, final LinearLayout list,
                                  final ArrayList<Source> order, final Runnable onDropped) {
        final PanelStyle style = host.style();
        final float[] startRawY = new float[1];
        final int[] fromIndex = new int[1];
        final int[] rowHeight = new int[1];
        final int[] targetIndex = new int[1];
        final boolean[] dragging = new boolean[1];
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (row.getHeight() <= 0) return false;
                    startRawY[0] = event.getRawY();
                    fromIndex[0] = list.indexOfChild(row);
                    targetIndex[0] = fromIndex[0];
                    if (fromIndex[0] < 0) return false;
                    rowHeight[0] = row.getHeight();
                    dragging[0] = true;
                    disallowIntercept(list, true);
                    if (android.os.Build.VERSION.SDK_INT >= 21) row.setElevation(style.dp(6));
                    row.setAlpha(0.92f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging[0]) return false;
                    float dy = event.getRawY() - startRawY[0];
                    row.setTranslationY(dy);
                    float center = row.getTop() + dy + rowHeight[0] / 2f;
                    int target = insertionIndex(list, row, center);
                    targetIndex[0] = target;
                    slideNeighbors(list, row, fromIndex[0], target, rowHeight[0]);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging[0]) return false;
                    dragging[0] = false;
                    boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP;
                    int from = fromIndex[0];
                    int target = commit ? targetIndex[0] : from;
                    // Stop neighbor animations before moving the child. Pending animator
                    // writes were racing the reparent and caused overlap/jumps on release.
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View child = list.getChildAt(i);
                        child.animate().cancel();
                        if (child != row) child.setTranslationY(0f);
                    }
                    if (commit && target != from && target >= 0) {
                        // The visible list hides retired sources, so a visible position is not
                        // an index into the full backing order. Resolve by identity.
                        Source dragged = (Source) row.getTag();
                        int orderFrom = order.indexOf(dragged);
                        if (dragged != null && orderFrom >= 0) {
                            order.remove(orderFrom);
                            order.add(visibleInsertionToOrderIndex(order, target), dragged);
                            list.removeView(row);
                            list.addView(row, Math.min(target, list.getChildCount()));
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 21) row.setElevation(0);
                    row.setAlpha(1f);
                    settleTranslations(list);
                    disallowIntercept(list, false);
                    if (commit && target != from && onDropped != null) {
                        // After the settle, so the re-rendered list does not cut it short.
                        list.postDelayed(onDropped, 140);
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /**
     * Maps a visible-list insertion position to an index in the full source order, which can
     * contain retired entries hidden from the reorder UI. Hidden entries keep their slots: the
     * dragged source lands before the visible item at the target position, or at the end when
     * the target is past the last visible item.
     */
    static int visibleInsertionToOrderIndex(List<Source> order, int visibleTarget) {
        int seen = 0;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i) == Source.SPICY) continue;
            if (seen == visibleTarget) return i;
            seen++;
        }
        return order.size();
    }

    /** Insertion index after the dragged row is removed: non-row children above the finger point. */
    private int insertionIndex(LinearLayout list, LinearLayout row, float centerY) {
        int position = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child == row) continue;
            float mid = child.getTop() + child.getHeight() / 2f;
            if (centerY > mid) position++;
        }
        return Math.max(0, Math.min(list.getChildCount() - 1, position));
    }

    /** Slides the rows between the drag origin and the insertion point out of the way. */
    private void slideNeighbors(LinearLayout list, LinearLayout row, int from, int target, int height) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child == row) continue;
            float shift = 0f;
            if (target > from && i > from && i <= target) shift = -height;
            else if (target < from && i >= target && i < from) shift = height;
            if (child.getTranslationY() != shift) {
                child.animate().translationY(shift).setDuration(120).start();
            }
        }
    }

    private void settleTranslations(LinearLayout list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getTranslationY() != 0f) {
                child.animate().translationY(0f).setDuration(120).start();
            }
        }
    }

    private void disallowIntercept(View view, boolean disallow) {
        View current = view;
        while (current != null) {
            android.view.ViewParent parent = current.getParent();
            if (parent == null) return;
            parent.requestDisallowInterceptTouchEvent(disallow);
            if (!(parent instanceof View)) return;
            current = (View) parent;
        }
    }

    /** Source-namespace sink for the merged Save; ordinary values go via the writer. */
    private SourcePreferencesAdapter sourceAdapter() {
        PanelStyle style = host.style();
        return new SourcePreferencesAdapter(new SourcePreferencesAdapter.Sink() {
            @Override public void setRankingMode(RankingMode mode) {
                LyricsSourcePreferences.setRankingMode(style.context(), mode);
            }

            @Override public void setSourceOrder(List<Source> order) {
                LyricsSourcePreferences.setSourceOrder(style.context(), order);
            }

            @Override public void setSourceEnabled(Source source, boolean enabled) {
                LyricsSourcePreferences.setSourceEnabled(style.context(), source, enabled);
            }
        });
    }
}
