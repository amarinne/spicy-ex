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
    private static final String MODE_AUTO = "Auto";
    private static final String MODE_SOURCE_ORDER = "Source order";

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

    // --- Collapsed row ---

    /** Single merged "Lyrics source" row: ranking mode plus the enabled order summary. */
    public void mergedRow(LinearLayout content) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        String ranking = host.store().get(Settings.LYRICS_SOURCE_MODE);
        StringBuilder order = new StringBuilder();
        for (Source source : LyricsSourcePreferences.enabledSourceOrder(style.context())) {
            if (order.length() > 0) order.append(" · ");
            order.append(sourceLabel(source));
        }
        String rankLabel = strings.option(modeSetting(), ranking);
        String summary = order.length() == 0
                ? rankLabel + " · " + strings.get("settings_source_none_enabled", "None enabled")
                : rankLabel + " · " + order;
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(Settings.LYRICS_SOURCE_MODE));
        TextView value = style.titleColumn(row, strings.setting(Settings.LYRICS_SOURCE_OVERRIDE), summary);
        value.setTextColor(PanelStyle.COL_ACCENT);
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
        row.setOnClickListener(v -> showDialog());
    }

    /** Provider names are brands and stay as authored; only the surrounding copy localizes. */
    public String sourceLabel(Source source) {
        if (source == Source.APPLE_MUSIC) return "Apple Music";
        if (source == Source.SPICY) return "Spicy";
        if (source == Source.SPOTIFY) return "Spotify";
        if (source == Source.AMLL) return "AMLL";
        return "LRCLIB";
    }

    // --- Dialog ---

    public void showDialog() {
        final PanelStyle style = host.style();
        final SettingsUiStrings strings = host.strings();
        final String[] ranking = new String[]{rankingValue()};
        final ArrayList<Source> order = new ArrayList<>(LyricsSourcePreferences.sourceOrder(style.context()));
        final EnumMap<Source, GlossyToggle> toggles = new EnumMap<>(Source.class);

        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.LYRICS_SOURCE_OVERRIDE));

        // The order list only takes effect in Source order mode. In Auto, arbitration is by
        // sync level and quality score, so the reorder UI is hidden to avoid implying priority.
        final LinearLayout orderSection = new LinearLayout(style.context());
        orderSection.setOrientation(LinearLayout.VERTICAL);
        final Runnable refreshOrderVisibility = () -> orderSection.setVisibility(
                MODE_SOURCE_ORDER.equals(ranking[0]) ? View.VISIBLE : View.GONE);

        dialog.paragraph(strings.get("settings_source_ranking_title", "Ranking"));
        final ArrayList<LinearLayout> rankingRows = new ArrayList<>();
        // The value drives selection; the option label is authored English, as before.
        final String[][] rankingOptions = new String[][]{
                {MODE_AUTO, MODE_AUTO},
                {MODE_SOURCE_ORDER, MODE_SOURCE_ORDER + " — "
                        + strings.get("settings_source_ranking_order_desc", "follow the order below")}
        };
        for (final String[] option : rankingOptions) {
            LinearLayout row = style.radioRow(option[1], option[0].equals(ranking[0]));
            rankingRows.add(row);
            final String value = option[0];
            row.setOnClickListener(v -> {
                ranking[0] = value;
                refreshRankingRows(rankingRows, ranking[0]);
                refreshOrderVisibility.run();
            });
            dialog.add(row);
        }

        TextView orderTitle = style.text(strings.get("settings_source_order_title", "Order"),
                15, PanelStyle.COL_SECTION, true);
        orderTitle.setPadding(style.dp(12), style.dp(12), style.dp(8), style.dp(2));
        orderSection.addView(orderTitle);
        LinearLayout list = new LinearLayout(style.context());
        list.setOrientation(LinearLayout.VERTICAL);
        orderSection.addView(list);
        dialog.add(orderSection);
        refreshOrderVisibility.run();
        for (Source source : order) {
            // Spicy's remote path is retired from the user-selectable set. Keep it in the
            // backing order for old persisted data, but never expose it.
            if (source == Source.SPICY) continue;
            LinearLayout row = new LinearLayout(style.context());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(style.dp(12), style.dp(8), style.dp(8), style.dp(8));
            row.setTag(source);
            GlossyToggle toggle = new GlossyToggle(style.context());
            toggle.setAccent(PanelStyle.COL_ACCENT);
            toggle.setChecked(LyricsSourcePreferences.sourceEnabled(style.context(), source), false);
            toggles.put(source, toggle);
            row.addView(toggle, new LinearLayout.LayoutParams(style.dp(44), style.dp(30)));
            TextView label = style.text(sourceLabel(source), 16, PanelStyle.COL_TITLE, false);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = style.dp(12);
            row.addView(label, labelParams);
            ImageView grip = style.kindView(Kind.CHEVRONS_UP_DOWN, PanelStyle.COL_SUMMARY, 20);
            grip.setContentDescription(strings.get("settings_source_drag", "Drag to reorder"));
            row.addView(grip, new LinearLayout.LayoutParams(style.dp(40), style.dp(40)));
            attachSourceDrag(grip, row, list, order);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            EnumMap<Source, Boolean> enabled = new EnumMap<>(Source.class);
            for (Source source : Source.values()) {
                GlossyToggle toggle = toggles.get(source);
                enabled.put(source, toggle != null && toggle.isChecked());
            }
            sourceAdapter().commit(host.writer(), new SourcePreferencesAdapter.Commit(
                    ranking[0], order, enabled));
            host.onSourcesCommitted();
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
        refreshRankingRows(rankingRows, ranking[0]);
    }

    /** Current ranking as a persisted token; anything unrecognized reads as Auto. */
    private String rankingValue() {
        String stored = host.store().get(Settings.LYRICS_SOURCE_MODE);
        return MODE_SOURCE_ORDER.equals(stored) ? MODE_SOURCE_ORDER : MODE_AUTO;
    }

    /** Repaints radio rows from the persisted value, never from rendered label text. */
    private void refreshRankingRows(List<LinearLayout> rows, String selected) {
        for (int i = 0; i < rows.size(); i++) {
            boolean isSelected = i == (MODE_SOURCE_ORDER.equals(selected) ? 1 : 0);
            host.style().paintRadio(rows.get(i), isSelected);
        }
    }

    // --- Drag to reorder ---

    /**
     * Grip drag with sliding neighbors: the dragged row follows the finger via translationY
     * while the rows it passes slide out of the way. Order commits on release.
     */
    private void attachSourceDrag(View handle, LinearLayout row, final LinearLayout list,
                                  final ArrayList<Source> order) {
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
