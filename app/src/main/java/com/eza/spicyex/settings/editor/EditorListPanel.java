package com.eza.spicyex.settings.editor;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.eza.spicyex.settings.registry.ControlSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The views of the editor's list mode inside the options panel: one header per element in
 * {@link EditorListPlan#SECTIONS} order, each element's rows under it, and tail space so a scroll
 * can bring any section, the last included, to the top of the list.
 *
 * <p>Section tops are only final after a layout pass, so a scroll request waits for the panel's
 * next layout. The scroll position itself is kept in the session's {@link EditorListPlan.State}.
 */
public final class EditorListPanel {
    /** Renders one section's rows under its header. */
    public interface Body {
        void render(EditorListPlan.Section section);
    }

    private final EditorRows rows;
    private final LinearLayout card;
    private final ScrollView scroll;
    /** The list's visible height when it is full: the tail space is sized against it. */
    private final IntSupplier viewportPx;
    private final EditorListPlan.State state;
    private final List<View> headers = new ArrayList<>();
    private View tail;
    /** The section to scroll to once the list is laid out, or -1. */
    private int pendingSection = -1;
    /** Restore the remembered scroll once the rebuilt list is laid out. */
    private boolean pendingRestore;
    /** How many times the list has been filled. The probe reads it to tell a rebuilt list from
     *  one that was left alone. */
    private int buildCount;

    public EditorListPanel(EditorRows rows, LinearLayout card, ScrollView scroll,
            IntSupplier viewportPx, EditorListPlan.State state) {
        this.rows = rows;
        this.card = card;
        this.scroll = scroll;
        this.viewportPx = viewportPx;
        this.state = state;
        card.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> onLaidOut());
    }

    /** Whether the panel holds the list now. */
    public boolean isBuilt() {
        return !headers.isEmpty();
    }

    /** Forgets the list; the panel's views now belong to a per-element panel. */
    public void clear() {
        headers.clear();
        tail = null;
        pendingSection = -1;
        pendingRestore = false;
    }

    /** Fills the panel with every section and scrolls to an element's section once laid out. */
    public void buildAt(ControlSpec.Element element, Body body) {
        build(body);
        pendingSection = EditorListPlan.indexOf(element);
    }

    /** Rebuilds the list after an edit and keeps its scroll position. */
    public void rebuild(Body body) {
        state.rememberScroll(scroll.getScrollY());
        build(body);
        pendingRestore = true;
        scroll.scrollTo(0, state.scrollY());
    }

    /** Scrolls an element's section to the top of the list. */
    public void scrollTo(ControlSpec.Element element) {
        pendingSection = EditorListPlan.indexOf(element);
        applyPendingScroll();
    }

    /** The section ids the panel has headers for, in panel order. */
    public List<String> sectionIds() {
        return headers.isEmpty() ? Collections.emptyList()
                : EditorListPlan.sectionIds().subList(0, headers.size());
    }

    /** The section id at the top of the list viewport, or "" when there is no list. */
    public String firstVisible() {
        int index = EditorListPlan.firstVisible(sectionTops(), scroll.getScrollY());
        return index < 0 ? "" : EditorListPlan.SECTIONS.get(index).id;
    }

    /**
     * How many times the list has been filled.
     *
     * <p>An edit in list mode rebuilds the list in place. The scroll and the selection can stay
     * the same whether the list was rebuilt or left alone, so this counter is what tells the
     * two apart.
     */
    public int buildCount() {
        return buildCount;
    }

    private void build(Body body) {
        buildCount++;
        card.removeAllViews();
        rows.endGroup();
        clear();
        for (int i = 0; i < EditorListPlan.SECTIONS.size(); i++) {
            EditorListPlan.Section section = EditorListPlan.SECTIONS.get(i);
            headers.add(rows.addElementHeader(rows.s(section.labelKey, section.label), i == 0));
            body.render(section);
        }
        rows.endGroup();
        tail = new View(card.getContext());
        card.addView(tail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
    }

    /** Runs after every layout of the list: sizes the tail space, then applies a pending scroll
     *  once the section tops are final. */
    private void onLaidOut() {
        if (headers.isEmpty() || tail == null || tail.getParent() != card) return;
        int lastSection = tail.getTop() - headers.get(headers.size() - 1).getTop();
        int wanted = Math.max(0, viewportPx.getAsInt() - lastSection);
        ViewGroup.LayoutParams lp = tail.getLayoutParams();
        if (lp.height != wanted) {
            lp.height = wanted;
            // Never request a layout from inside one; the next pass calls back here.
            View view = tail;
            view.post(view::requestLayout);
            return;
        }
        applyPendingScroll();
    }

    private void applyPendingScroll() {
        if (pendingSection < 0 && !pendingRestore) return;
        if (card.isLayoutRequested()) return; // onLaidOut() runs again
        int y = pendingSection >= 0
                ? state.scrollTo(EditorListPlan.SECTIONS.get(pendingSection).element, sectionTops())
                : state.scrollY();
        pendingSection = -1;
        pendingRestore = false;
        scroll.scrollTo(0, y);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private int[] sectionTops() {
        int[] tops = new int[headers.size()];
        for (int i = 0; i < tops.length; i++) tops[i] = headers.get(i).getTop();
        return tops;
    }
}
