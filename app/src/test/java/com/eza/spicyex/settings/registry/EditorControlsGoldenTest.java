package com.eza.spicyex.settings.registry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Compares registry metadata directly with the recorded control contract from a6f5a3c8.
 * This gate does not load builder source or run builders, so it survives their removal in S2.
 * It pins row order, kinds, choices, exact mode writes, visibility variants and tab membership.
 * A deliberately reshaped row, such as the compact background-effect picker, is recorded in its
 * current shape. Hint text, dividers and grip-only controls are outside this options-row fixture.
 */
public class EditorControlsGoldenTest {
    @Test public void registryMatchesEveryFixedContextVariantAndTab() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        assertEquals(Arrays.asList("portrait", "landscape", "two_column", "card_mode"),
                new ArrayList<>(fixture.contexts.keySet()));
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            assertEquals(layout.getKey(), Arrays.asList("default", "custom", "apple", "spotlight"),
                    new ArrayList<>(layout.getValue().variants.keySet()));
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                assertEquals(layout.getKey() + "/" + variant.getKey(), variant.getValue().controls,
                        registryRows(layout.getValue().context(variant.getValue())));
            }
        }
    }

    /** The Lyrics Style tab's renderer source is its own registry projection, in every context. */
    @Test public void lyricsStyleTabRendersTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                List<String> recorded = variant.getValue().controls.get("LYRICS/0");
                assertEquals(layout.getKey() + "/" + variant.getKey(),
                        recorded.subList(1, recorded.size()),
                        project(ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 0, context), context));
            }
        }
    }

    /**
     * The Animation and Effects tabs render the same keys, kinds, choices and order the frozen
     * builders produced, in every context and every variant - the Apple rows and the
     * word-bounce rows included.
     */
    @Test public void lyricsAnimationAndEffectsTabsRenderTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                for (int tab : new int[]{1, 2}) {
                    List<String> recorded = variant.getValue().controls.get("LYRICS/" + tab);
                    assertEquals(layout.getKey() + "/" + variant.getKey() + "/tab" + tab,
                            recorded.subList(1, recorded.size()),
                            project(ControlRegistry.tabControls(ControlSpec.Element.LYRICS, tab, context), context));
                }
            }
        }
    }

    /**
     * The Background element renders the same keys, kinds, choices and order the recorded
     * contract names, in every context and every variant: the style chips, the compact effect
     * picker, and the one darkening control.
     */
    @Test public void backgroundRendersTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                List<String> recorded = variant.getValue().controls.get("BACKGROUND");
                assertEquals(layout.getKey() + "/" + variant.getKey(),
                        recorded,
                        project(ControlRegistry.optionControls(ControlSpec.Element.BACKGROUND, context), context));
            }
        }
    }

    /**
     * The Top bar renders the same keys, kinds, choices and order the frozen builder produced,
     * in every context and every variant: the visibility toggle, the three chrome choices and
     * the fullscreen-controls slider.
     */
    @Test public void topBarRendersTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                List<String> recorded = variant.getValue().controls.get("TOP_BAR");
                assertEquals(layout.getKey() + "/" + variant.getKey(),
                        recorded,
                        project(ControlRegistry.optionControls(ControlSpec.Element.TOP_BAR, context), context));
            }
        }
    }

    /**
     * The Skip and Follow chips render the same keys, kinds, choices and order the frozen
     * builder produced, in every context and every variant: both outlines open this one
     * combined panel.
     */
    @Test public void chipsRenderTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                List<String> recorded = variant.getValue().controls.get("CHIPS");
                assertEquals(layout.getKey() + "/" + variant.getKey(),
                        recorded,
                        project(ControlRegistry.optionControls(ControlSpec.Element.CHIPS, context), context));
            }
        }
    }

    /**
     * The card panel renders the same keys, kinds, choices and order the frozen builder
     * produced, in every context and every variant: the Text tab's size, weight, secondary
     * line and overflow with the scroll scope that only the scrolling overflow shows, and the
     * Animation tab's four rows.
     */
    @Test public void cardTabsRenderTheFrozenRowsInEveryContext() throws Exception {
        EditorControlsFixture fixture = EditorControlsFixture.read();
        for (Map.Entry<String, EditorControlsFixture.Layout> layout : fixture.contexts.entrySet()) {
            for (Map.Entry<String, EditorControlsFixture.Variant> variant : layout.getValue().variants.entrySet()) {
                UiContext context = layout.getValue().context(variant.getValue());
                for (int tab : new int[]{0, 1}) {
                    List<String> recorded = variant.getValue().controls.get("CARD/" + tab);
                    assertEquals(layout.getKey() + "/" + variant.getKey() + "/tab" + tab,
                            recorded.subList(1, recorded.size()),
                            project(ControlRegistry.tabControls(ControlSpec.Element.CARD, tab, context), context));
                }
            }
        }
    }

    private static Map<String, List<String>> registryRows(UiContext context) {
        Map<String, List<String>> elements = new LinkedHashMap<>();
        for (ControlSpec.Element element : ControlSpec.Element.values()) {
            List<ControlSpec> controls = ControlRegistry.optionControls(element, context);
            elements.put(element.name(), project(controls, context, null));
            for (int tab = 0; tab < ControlSpec.tabCount(element); tab++) {
                elements.put(element.name() + "/" + tab, project(controls, context, tab));
            }
        }
        return elements;
    }

    private static List<String> project(List<ControlSpec> controls, UiContext context) {
        return project(controls, context, null);
    }

    private static List<String> project(List<ControlSpec> controls, UiContext context, Integer tab) {
        List<String> rows = new ArrayList<>();
        for (ControlSpec row : controls) {
            // Null membership is the navigation row shared by every tab.
            if (tab != null && row.tab != null && !tab.equals(row.tab)) continue;
            String key = row.setting == null ? "@" + row.compositeId : row.setting.key;
            String kind = row.kind.name();
            List<String> choices = row.choices;
            // Composite adapters retain the current widget kind and context-specific choices.
            if ("track_info_position".equals(row.compositeId)) {
                kind = context.twoColumn ? "TOGGLE" : "CHOICE";
                if (context.twoColumn) choices = Collections.emptyList();
            } else if ("font_chips".equals(row.compositeId)) {
                kind = "CHOICE";
            }
            String mode = row.modeValue == null ? "" : "|mode=" + row.modeValue;
            rows.add(key + "|" + kind + "|" + String.join(";", choices) + mode);
        }
        return rows;
    }
}
