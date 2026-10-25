package com.eza.spicyex.settings.values;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The lyrics background: the plain gradient and the two texture modes. */
public class BackgroundStyleTest {
    @Test
    public void unknownValuesFallBackToGradient() {
        BackgroundStyle unknown = StoredValues.fromStored(BackgroundStyle.class, "old");
        assertSame(BackgroundStyle.GRADIENT, unknown);
        assertFalse(unknown.usesTexture());
    }

    @Test
    public void textureModesStayDistinct() {
        assertTrue(BackgroundStyle.STATIC_TEXTURE.usesTexture());
        assertFalse(BackgroundStyle.STATIC_TEXTURE.isAnimated());
        assertTrue(BackgroundStyle.ANIMATED_TEXTURE.usesTexture());
        assertTrue(BackgroundStyle.ANIMATED_TEXTURE.isAnimated());
    }

    @Test
    public void theGradientDrawsNoTexture() {
        assertFalse(BackgroundStyle.GRADIENT.usesTexture());
        assertFalse(BackgroundStyle.GRADIENT.isAnimated());
    }
}
