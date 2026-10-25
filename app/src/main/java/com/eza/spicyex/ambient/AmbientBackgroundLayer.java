package com.eza.spicyex.ambient;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Common contract for the lyrics ambient background layer, kept so the controller does not depend
 * on the concrete renderer. Only {@code AmbientArtworkBackgroundView} implements it today: the animated
 * background is AGSL-only, so devices below API 33 get no layer rather than a lesser stand-in.
 */
public interface AmbientBackgroundLayer {
    /** Returns false when the candidate is rejected and the previous effect remains installed. */
    boolean setEffect(AmbientShaderEffect effect);

    /** Installs a prepared module-owned texture on the UI thread. */
    void updateImage(Bitmap art);

    /**
     * Installs a prepared texture plus its compact variance profile (a few floats).
     * Default keeps backward compatibility for non-adaptive layers.
     */
    default void updateImage(Bitmap art, AmbientArtworkProfile profile) {
        updateImage(art);
    }

    /**
     * Installs an owned, aspect-preserving original cover. Paper image shaders sample this instead
     * of Kawarp's blurred normal/dark atlas.
     */
    default void updateOriginalImage(Bitmap art) {
        updateImage(art);
    }

    /** False for a palette-only effect that must not wait for or depend on artwork. */
    default boolean usesArtworkTexture() {
        return true;
    }

    /** True only for Paper effects whose source samples the original cover. */
    default boolean usesOriginalArtwork() {
        return false;
    }

    /** Stops frames and releases texture references. The layer can be reused. */
    void release();

    void pauseRendering();

    void resumeRendering();

    /** The layer as a View for add/visibility plumbing. */
    View asView();
}
