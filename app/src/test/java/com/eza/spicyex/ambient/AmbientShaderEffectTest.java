package com.eza.spicyex.ambient;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AmbientShaderEffectTest {
    @Test
    public void productionStartsOnKawarp() {
        assertSame(AmbientShaderEffect.KAWARP, AmbientShaderEffect.DEFAULT);
        assertSame(AmbientShaderEffect.KAWARP, AmbientShaderEffect.fallback());
    }

    @Test
    public void theDebugArgumentsSelectTheirEffect() {
        assertSame(AmbientShaderEffect.KAWARP, AmbientShaderEffect.fromArgument("kawarp"));
        assertSame(AmbientShaderEffect.MESH_GRADIENT, AmbientShaderEffect.fromArgument("mesh"));
    }

    @Test
    public void theMeshGradientAliasSurvives() {
        assertSame(AmbientShaderEffect.MESH_GRADIENT,
                AmbientShaderEffect.fromArgument("mesh-gradient"));
        assertSame(AmbientShaderEffect.MESH_GRADIENT,
                AmbientShaderEffect.fromArgument("  Mesh-Gradient  "));
    }

    @Test
    public void argumentsAreTrimmedAndCaseInsensitive() {
        assertSame(AmbientShaderEffect.MESH_GRADIENT, AmbientShaderEffect.fromArgument("  MeSh  "));
        assertSame(AmbientShaderEffect.SIMPLEX_NOISE,
                AmbientShaderEffect.fromArgument("  SIMPLEX-NOISE  "));
    }

    @Test
    public void missingAndUnknownArgumentsAreRejected() {
        assertNull(AmbientShaderEffect.fromArgument(null));
        assertNull(AmbientShaderEffect.fromArgument(""));
        assertNull(AmbientShaderEffect.fromArgument("   "));
        assertNull(AmbientShaderEffect.fromArgument("compose"));
        assertNull(AmbientShaderEffect.fromArgument("mesh-gradient-extra"));
    }

    @Test
    public void everyCatalogShaderHasAnEffect() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            AmbientShaderEffect effect = AmbientShaderEffect.fromArgument(spec.id);
            assertNotNull(spec.id, effect);
            if ("mesh-gradient".equals(spec.id)) {
                assertSame(AmbientShaderEffect.MESH_GRADIENT, effect);
            } else {
                assertEquals(spec.id, spec.id, effect.argument());
            }
        }
        assertEquals(PaperShaderCatalog.all().size() + 1, AmbientShaderEffect.values().length);
    }

    @Test
    public void everyNonKawarpEffectResolvesOrUsesTheAdaptedMesh() {
        for (AmbientShaderEffect effect : AmbientShaderEffect.values()) {
            if (effect == AmbientShaderEffect.KAWARP) continue;
            if (effect == AmbientShaderEffect.MESH_GRADIENT) {
                assertFalse(effect.isPaper());
                assertNotNull(PaperShaderCatalog.get("mesh-gradient"));
                continue;
            }
            assertTrue(effect.isPaper());
            assertNotNull(effect.argument(), PaperShaderCatalog.get(effect.argument()));
        }
    }

    @Test
    public void effectNamesNeverCollide() {
        Set<String> names = new HashSet<>();
        for (AmbientShaderEffect effect : AmbientShaderEffect.values()) {
            assertTrue(names.add(effect.argument()));
            AmbientShaderEffect resolved = AmbientShaderEffect.fromArgument(effect.argument());
            assertSame(effect, resolved);
        }
        assertSame(AmbientShaderEffect.MESH_GRADIENT,
                AmbientShaderEffect.fromArgument("mesh-gradient"));
    }

    @Test
    public void onlyKawarpNeedsArtwork() {
        assertFalse(AmbientShaderEffect.MESH_GRADIENT.requiresArtworkTexture());
        assertTrue(AmbientShaderEffect.KAWARP.requiresArtworkTexture());
        assertFalse(AmbientShaderEffect.WAVES.requiresArtworkTexture());
        assertTrue(AmbientShaderEffect.IMAGE_DITHERING.requiresArtworkTexture());
        assertTrue(AmbientShaderEffect.LIQUID_METAL.requiresArtworkTexture());
    }

    @Test
    public void paperEffectsTakeTheCoverPaletteButKawarpOnlyWithoutATexture() {
        assertTrue(AmbientShaderEffect.KAWARP.usesArtworkPalette(false));
        assertFalse(AmbientShaderEffect.KAWARP.usesArtworkPalette(true));
        assertTrue(AmbientShaderEffect.MESH_GRADIENT.usesArtworkPalette(false));
        assertTrue(AmbientShaderEffect.MESH_GRADIENT.usesArtworkPalette(true));
        for (AmbientShaderEffect effect : AmbientShaderEffect.values()) {
            if (!effect.isPaper()) continue;
            assertTrue(effect.name(), effect.usesArtworkPalette(false));
            assertTrue(effect.name(), effect.usesArtworkPalette(true));
        }
    }

    @Test
    public void paperTextureFlagsComeFromTheCatalog() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            AmbientShaderEffect effect = AmbientShaderEffect.fromArgument(spec.id);
            assertNotNull(spec.id, effect);
            if (effect == AmbientShaderEffect.MESH_GRADIENT) continue;
            assertEquals(spec.id, spec.usesImageTexture, effect.requiresArtworkTexture());
        }
    }
}
