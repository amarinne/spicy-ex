package com.eza.spicyex.settings.values;

import com.eza.spicyex.Settings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundEffectPoolTest {
    private static final List<String> PAPER_IDS = Arrays.asList(
            "mesh-gradient", "simplex-noise", "dot-orbit", "god-rays", "voronoi",
            "swirl", "neuro-noise", "perlin-noise", "color-panels",
            "water", "gem-smoke", "smoke-ring", "warp",
            "metaballs", "pulsing-border", "grain-gradient");

    @Test public void pickerHasOnlySelectablePaperShaders() {
        assertEquals(16, PAPER_IDS.size());
        assertEquals(17, BackgroundEffect.fixedValues().size());
        assertEquals(16, BackgroundEffect.paperValues().size());
        assertEquals("kawarp", BackgroundEffect.KAWARP.stableId());
        assertEquals("mesh-gradient", BackgroundEffect.MESH_GRADIENT.stableId());
        List<String> paperIds = new java.util.ArrayList<>();
        for (BackgroundEffect effect : BackgroundEffect.paperValues()) {
            paperIds.add(effect.stableId());
        }
        assertEquals(new HashSet<>(PAPER_IDS), new HashSet<>(paperIds));
        assertEquals(18, new HashSet<>(BackgroundEffect.stableIds()).size());
        assertEquals(BackgroundEffect.stableIds(),
                StoredValues.labels(BackgroundEffect.class));
        assertEquals("kawarp", StoredValues.defaultLabel(BackgroundEffect.class));
        assertTrue(BackgroundEffect.pickerValues().contains(BackgroundEffect.RANDOM));
        assertSame(BackgroundEffect.RANDOM, BackgroundEffect.pickerValues().get(0));
    }

    @Test public void legacyLabelsReadAsTheirStableEffect() {
        assertSame(BackgroundEffect.KAWARP,
                StoredValues.fromStored(BackgroundEffect.class, "Kawarp"));
        assertSame(BackgroundEffect.MESH_GRADIENT,
                StoredValues.fromStored(BackgroundEffect.class, "Mesh Gradient"));
        assertSame(BackgroundEffect.RANDOM,
                StoredValues.fromStored(BackgroundEffect.class, "Random"));
        assertSame(BackgroundEffect.GOD_RAYS, BackgroundEffect.fromText("God Rays"));
        assertNull(BackgroundEffect.fromText("compose"));
        assertNull(BackgroundEffect.fixedFromText("random"));
    }

    @Test public void staticCatalogEntriesAreNotSelectable() {
        assertNull(BackgroundEffect.fromText("waves"));
        assertNull(BackgroundEffect.fromText("static-radial-gradient"));
        // Removed from the picker before any public release; a stored id falls back.
        assertNull(BackgroundEffect.fromText("image-dithering"));
        assertNull(BackgroundEffect.fromText("heatmap"));
        assertNull(BackgroundEffect.fromText("liquid-metal"));
        assertEquals(BackgroundEffectPool.DEFAULT, BackgroundEffectPool.parse("waves"));
    }

    @Test public void oldPoolLabelsAndStableIdsParseToTheSameCanonicalPool() {
        assertEquals("kawarp,mesh-gradient", BackgroundEffectPool.DEFAULT.encode());
        assertEquals("kawarp,mesh-gradient", BackgroundEffectPool.parse("Both").encode());
        assertEquals("kawarp", BackgroundEffectPool.parse("Kawarp").encode());
        assertEquals("mesh-gradient", BackgroundEffectPool.parse("Mesh Gradient").encode());
        assertEquals("kawarp,mesh-gradient", BackgroundEffectPool.parse("").encode());
        assertEquals("kawarp,mesh-gradient", BackgroundEffectPool.parse("unknown").encode());
        assertEquals("mesh-gradient,god-rays",
                BackgroundEffectPool.parse("god-rays, mesh-gradient, god-rays").encode());
        assertEquals("kawarp,god-rays",
                BackgroundEffectPool.parse("kawarp,random,god-rays").encode());
    }

    @Test public void explicitPoolsRejectEmptyUnknownAndRandom() {
        assertNull(BackgroundEffectPool.parseExplicit(""));
        assertNull(BackgroundEffectPool.parseExplicit("compose"));
        assertNull(BackgroundEffectPool.parseExplicit("random"));
        assertNull(BackgroundEffectPool.parseExplicit("kawarp,"));
        assertEquals("kawarp,god-rays",
                BackgroundEffectPool.parseExplicit("god-rays,kawarp").encode());
    }

    @Test public void pickingIsUniformWithReplacement() {
        BackgroundEffectPool pool = BackgroundEffectPool.of(
                BackgroundEffect.KAWARP, BackgroundEffect.GOD_RAYS);
        Random first = new Random() {
            @Override public int nextInt(int bound) { return 0; }
        };
        Random repeat = new Random() {
            @Override public int nextInt(int bound) { return 1; }
        };
        assertSame(BackgroundEffect.KAWARP, pool.pick(first));
        assertSame(BackgroundEffect.GOD_RAYS, pool.pick(repeat));
        assertSame(BackgroundEffect.GOD_RAYS, pool.pick(repeat));
    }

    @Test public void schemaAllowsStableIdsAndLegacyLabels() {
        assertTrue(Settings.BACKGROUND_EFFECT.allowedValues.contains("kawarp"));
        assertTrue(Settings.BACKGROUND_EFFECT.allowedValues.contains("god-rays"));
        assertTrue(Settings.BACKGROUND_EFFECT.allowedValues.contains("Kawarp"));
        assertTrue(Settings.BACKGROUND_EFFECT.allowedValues.contains("Mesh Gradient"));
        assertEquals("kawarp", Settings.BACKGROUND_EFFECT.defaultValue);
        assertNull(Settings.BACKGROUND_RANDOM_EFFECTS.allowedValues);
        assertEquals("kawarp,mesh-gradient", Settings.BACKGROUND_RANDOM_EFFECTS.defaultValue);
    }
}
