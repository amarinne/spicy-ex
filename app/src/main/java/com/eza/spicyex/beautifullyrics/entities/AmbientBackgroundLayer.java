package com.eza.spicyex.beautifullyrics.entities;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Common contract for the lyrics ambient background layer, kept so the controller does not depend
 * on the concrete renderer. Only {@code AmbientArtworkBackgroundView} implements it today: the animated
 * background is AGSL-only, so devices below API 33 get no layer rather than a lesser stand-in.
 */
public interface AmbientBackgroundLayer {
    /** Installs a prepared module-owned texture on the UI thread. */
    void updateImage(Bitmap art);

    /**
     * Installs a prepared texture plus its compact variance profile (a few floats).
     * Default keeps backward compatibility for non-adaptive layers.
     */
    default void updateImage(Bitmap art, AmbientArtworkProfile profile) {
        updateImage(art);
    }

    /** Stops frames and releases texture references. The layer can be reused. */
    void release();

    void pauseRendering();

    void resumeRendering();

    /** The layer as a View for add/visibility plumbing. */
    View asView();
}
