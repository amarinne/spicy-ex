package com.eza.spicyex.hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One no-touch reading of the lyrics screen: the real on-screen geometry of the shell and of the
 * layout editor overlay, plus the rule violations that geometry implies.
 *
 * <p>Pure Java on purpose. The callers read {@code View}s and translate them into plain ints here;
 * nothing below this class touches Android, so {@link #violations()} is unit-testable on the JVM.
 * That is the whole point of the probe: an agent driving the editor by text has to be able to tell
 * a real overlap from a clean layout without reading pixels, and the rule that says so has to be
 * verifiable without a phone.
 *
 * <p>Every rect is {@code {left, top, right, bottom}} in <em>screen</em> coordinates, matching
 * {@code View#getLocationOnScreen}. A rect is simply absent when the thing it describes is not on
 * screen to measure - not laid out, not visible, or empty - and every rule that needs one skips
 * instead of inventing a value. Presence is therefore itself a fact: the editor only records its
 * own rects while it is open, and only records {@code sheet} while the sheet is actually up.
 */
final class LayoutProbeReport {

    /** How far a capture outline may sit from the real view before it counts as drift. */
    static final int CAPTURE_TOLERANCE_PX = 2;
    /** Same slack for the editor's focus line against the anchor the shell resolved. */
    static final int FOCUS_TOLERANCE_PX = 2;
    /** Sheet coverage strictly above this share of the target counts as covering it. */
    static final float SHEET_COVER_LIMIT = 0.5f;
    /** Chrome dimmer than this is not "visible chrome" for the lyrics-under-chrome rule. */
    static final float CHROME_VISIBLE_ALPHA = 0.5f;
    /** Slack for the top controls' edge gap against the Follow chip's, and for the row's corner. */
    static final int EDGE_TOLERANCE_PX = 2;
    /** A mounted lyrics window is small; the cap keeps the reply one readable line. */
    static final int MAX_LYRIC_ROWS = 40;

    private static final String[] CHIPS = {"skip", "follow"};
    /** What an editor toolbar button must never sit on top of. The real back button is not one:
     *  the editor places Done over it on purpose, so the two always share a rect. */
    private static final String[] TOOLBAR_TARGETS = {"artwork", "track_text", "dock",
            "card_caption"};
    /** Editor capture outline, the real rect it traces, and the label both are reported under. */
    private static final String[][] CAPTURE_PAIRS = {
            {"artwork", "artwork", "capture.artwork"},
            {"track_text", "track_text", "capture.track_text"},
            {"dock", "dock", "capture.dock"},
            {"skip", "chip.skip", "capture.skip"},
            {"follow", "chip.follow", "capture.follow"},
    };
    /** Selected editor element -> the real rect it is pointing at on the live screen. */
    private static final Map<String, String> ELEMENT_SOURCE_RECT = elementSourceRects();

    private final Map<String, int[]> rects = new LinkedHashMap<>();
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private final List<int[]> lyricRows = new ArrayList<>();

    /**
     * Records a screen rect. An absent, short, or empty rect is dropped rather than stored, so
     * callers can hand over a view that is not on screen without null-checking the result.
     */
    LayoutProbeReport rect(String name, int[] value) {
        if (name == null || name.isEmpty() || value == null || value.length < 4) return this;
        if (value[2] <= value[0] || value[3] <= value[1]) return this;
        rects.put(name, new int[]{value[0], value[1], value[2], value[3]});
        return this;
    }

    LayoutProbeReport rect(String name, int left, int top, int right, int bottom) {
        return rect(name, new int[]{left, top, right, bottom});
    }

    LayoutProbeReport number(String name, int value) {
        return fact(name, Integer.valueOf(value));
    }

    LayoutProbeReport number(String name, float value) {
        return fact(name, Float.valueOf(value));
    }

    LayoutProbeReport flag(String name, boolean value) {
        return fact(name, Boolean.valueOf(value));
    }

    LayoutProbeReport text(String name, String value) {
        return fact(name, value == null ? "" : value);
    }

    private LayoutProbeReport fact(String name, Object value) {
        if (name == null || name.isEmpty()) return this;
        facts.put(name, value);
        return this;
    }

    /** One mounted lyric row's text content, capped at {@link #MAX_LYRIC_ROWS} in total. */
    LayoutProbeReport lyricRow(int[] value) {
        if (value == null || value.length < 4) return this;
        if (value[2] <= value[0] || value[3] <= value[1]) return this;
        if (lyricRows.size() >= MAX_LYRIC_ROWS) return this;
        lyricRows.add(new int[]{value[0], value[1], value[2], value[3]});
        return this;
    }

    Map<String, int[]> rects() {
        return Collections.unmodifiableMap(rects);
    }

    Map<String, Object> facts() {
        return Collections.unmodifiableMap(facts);
    }

    List<int[]> lyricRows() {
        return Collections.unmodifiableList(lyricRows);
    }

    int[] rect(String name) {
        return rects.get(name);
    }

    Object fact(String name) {
        return facts.get(name);
    }

    /**
     * Every rule this geometry breaks, as one short line each, in a fixed order. Empty means the
     * reading found nothing wrong - which is the answer an agent is actually looking for.
     */
    List<String> violations() {
        List<String> out = new ArrayList<>();
        toolbarOverlap(out);
        captureDrift(out);
        focusDrift(out);
        chipAboveEditor(out);
        chipOverSheet(out);
        sheetCoversTarget(out);
        lyricsUnderChrome(out);
        sheetOverToolbar(out);
        chromeEdge(out);
        chromeCorner(out);
        chromeOverChip(out);
        truncatedOptions(out);
        if (isTrue("credit_present") && !isTrue("credit_in_footer")) {
            out.add("R13 credit_outside_footer");
        }
        return out;
    }

    /** R1: an editor toolbar button sitting on top of something the user reads. */
    private void toolbarOverlap(List<String> out) {
        for (Map.Entry<String, int[]> entry : rects.entrySet()) {
            if (!entry.getKey().startsWith("toolbar.")) continue;
            for (String target : TOOLBAR_TARGETS) {
                // The card stage is opaque: the lyrics-screen elements under it are not visible.
                if (isTrue("card_mode") && !"card_caption".equals(target)) continue;
                int[] other = rects.get(target);
                if (other == null) continue;
                if (intersects(entry.getValue(), other)) {
                    out.add("R1 toolbar_overlap " + entry.getKey() + " " + target);
                }
            }
        }
    }

    /** R2: a capture outline that no longer sits on the real view it is outlining. */
    private void captureDrift(List<String> out) {
        for (String[] pair : CAPTURE_PAIRS) {
            int[] source = rects.get(pair[1]);
            int[] capture = rects.get(pair[2]);
            if (source == null || capture == null) continue;
            int dx = Math.max(Math.abs(capture[0] - source[0]), Math.abs(capture[2] - source[2]));
            int dy = Math.max(Math.abs(capture[1] - source[1]), Math.abs(capture[3] - source[3]));
            if (Math.max(dx, dy) > CAPTURE_TOLERANCE_PX) {
                out.add("R2 capture_drift " + pair[0] + " " + dx + " " + dy);
            }
        }
    }

    /** R3: the drawn focus line off the anchor the shell actually scrolled to. */
    private void focusDrift(List<String> out) {
        if (!isTrue("editor_open")) return;
        int[] line = rects.get("focus_line");
        Double anchor = number("focus_anchor_y");
        if (line == null || anchor == null) return;
        int dy = (int) Math.round((line[1] + line[3]) / 2f - anchor.doubleValue());
        if (Math.abs(dy) > FOCUS_TOLERANCE_PX) out.add("R3 focus_drift " + dy);
    }

    /** R4: a floating chip that draws over the editor instead of under it. */
    private void chipAboveEditor(List<String> out) {
        for (String chip : CHIPS) {
            if (chipRidesAboveEditor(chip)) out.add("R4 chip_above_editor " + chip);
        }
    }

    /** R5: a chip that both rides above the editor and lands on the options sheet. */
    private void chipOverSheet(List<String> out) {
        int[] sheet = rects.get("sheet");
        if (sheet == null) return;
        for (String chip : CHIPS) {
            int[] rect = rects.get("chip." + chip);
            if (rect == null || !chipRidesAboveEditor(chip)) continue;
            if (intersects(rect, sheet)) out.add("R5 chip_over_sheet " + chip);
        }
    }

    /**
     * True while the chip would paint over the editor overlay: it is only a real risk when the two
     * are siblings under the same parent (then elevation decides) and the chip's z is higher.
     */
    private boolean chipRidesAboveEditor(String chip) {
        if (!isTrue("editor_open")) return false;
        if (!isTrue("chip." + chip + ".sibling")) return false;
        Double z = number("z.chip." + chip);
        Double overlayZ = number("z.overlay");
        if (z == null || overlayZ == null) return false;
        return z > overlayZ;
    }

    /** R6: the options sheet parked on top of whatever the user is editing. */
    private void sheetCoversTarget(List<String> out) {
        int[] sheet = rects.get("sheet");
        Object selected = facts.get("selected");
        if (sheet == null || !(selected instanceof String) || ((String) selected).isEmpty()) return;
        String source = ELEMENT_SOURCE_RECT.get(selected);
        if (source == null) return;
        int[] target = rects.get(source);
        if (target == null) return;
        int area = area(target);
        if (area <= 0) return;
        if (overlapArea(sheet, target) > area * SHEET_COVER_LIMIT) {
            out.add("R6 sheet_covers_target " + selected);
        }
    }

    /** R8: the options sheet on top of the editor's own Done/Reset/Demo buttons. */
    private void sheetOverToolbar(List<String> out) {
        int[] sheet = rects.get("sheet");
        if (sheet == null) return;
        for (Map.Entry<String, int[]> entry : rects.entrySet()) {
            if (entry.getKey().startsWith("toolbar.") && intersects(entry.getValue(), sheet)) {
                out.add("R8 sheet_over_toolbar " + entry.getKey());
            }
        }
    }

    /** R7: real lyric text sitting underneath chrome that is still opaque enough to hide it. */
    private void lyricsUnderChrome(List<String> out) {
        Double alpha = number("chrome_alpha");
        int[] dock = rects.get("dock");
        if (alpha == null || alpha <= CHROME_VISIBLE_ALPHA || dock == null) return;
        // The opaque card stage hides the lyrics and the chrome alike.
        if (isTrue("card_mode")) return;
        int covered = 0;
        for (int[] row : lyricRows) {
            if (intersects(row, dock)) covered++;
        }
        if (covered > 0) out.add("R7 lyrics_under_chrome " + covered);
    }

    /**
     * R9: the top controls and the Follow chip hug the same screen edge at different distances.
     * Both line up on one edge gap; a chip on the other half of the screen is not compared.
     */
    private void chromeEdge(List<String> out) {
        int[] screen = rects.get("screen");
        int[] dock = rects.get("dock");
        int[] follow = rects.get("chip.follow");
        if (screen == null || dock == null || follow == null) return;
        boolean dockRight = centerX(dock) > centerX(screen);
        if (dockRight != (centerX(follow) > centerX(screen))) return;
        int dockGap = dockRight ? screen[2] - dock[2] : dock[0] - screen[0];
        int followGap = dockRight ? screen[2] - follow[2] : follow[0] - screen[0];
        if (Math.abs(dockGap - followGap) > EDGE_TOLERANCE_PX) {
            out.add("R9 chrome_edge dock=" + dockGap + " follow=" + followGap);
        }
    }

    /** R10: top controls - rail or row - that do not start one edge gap below the top floor. */
    private void chromeCorner(List<String> out) {
        int[] dock = rects.get("dock");
        Double floor = number("chrome_top_floor");
        Double margin = number("edge_margin");
        if (dock == null || floor == null || margin == null) return;
        int topGap = (int) Math.round(dock[1] - floor.doubleValue());
        if (Math.abs(topGap - margin.doubleValue()) > EDGE_TOLERANCE_PX) {
            out.add("R10 chrome_corner top=" + topGap + " margin=" + Math.round(margin.doubleValue()));
        }
    }

    /** R11: the top controls reaching down onto the Follow or skip chip. */
    private void chromeOverChip(List<String> out) {
        int[] dock = rects.get("dock");
        if (dock == null) return;
        for (String chip : CHIPS) {
            int[] rect = rects.get("chip." + chip);
            if (rect != null && intersects(dock, rect)) out.add("R11 chrome_over_chip " + chip);
        }
    }

    private static int centerX(int[] rect) {
        return (rect[0] + rect[2]) / 2;
    }

    /** R12: an option hides the words needed to distinguish its stored value. */
    private void truncatedOptions(List<String> out) {
        for (Map.Entry<String, Object> fact : facts.entrySet()) {
            if (fact.getKey().startsWith("truncated.option.") && Boolean.TRUE.equals(fact.getValue())) {
                out.add("R12 option_text_truncated " + fact.getKey().substring("truncated.option.".length()));
            }
        }
    }

    private boolean isTrue(String name) {
        return Boolean.TRUE.equals(facts.get(name));
    }

    private Double number(String name) {
        Object value = facts.get(name);
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    /** Strict: shared edges are touching, not overlapping, so they do not count. */
    private static boolean intersects(int[] a, int[] b) {
        return a != null && b != null && a[0] < b[2] && a[2] > b[0]
                && a[1] < b[3] && a[3] > b[1];
    }

    private static int area(int[] rect) {
        return (rect[2] - rect[0]) * (rect[3] - rect[1]);
    }

    private static int overlapArea(int[] a, int[] b) {
        int width = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        int height = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        return width > 0 && height > 0 ? width * height : 0;
    }

    /**
     * Background has no on-screen shape of its own, so it has no source rect and no coverage
     * verdict; every other element points at the live view its outline is on.
     */
    private static Map<String, String> elementSourceRects() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("artwork", "artwork");
        map.put("track_text", "track_text");
        map.put("focus", "focus_line");
        map.put("skip", "chip.skip");
        map.put("follow", "chip.follow");
        map.put("top_bar", "dock");
        map.put("card", "card");
        return Collections.unmodifiableMap(map);
    }
}
