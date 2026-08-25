package com.eza.spicyex.beautifullyrics.entities;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Common contract for the lyrics ambient background layer, kept so the controller does not depend
 * on the concrete renderer. Only {@code KawarpBackgroundView} implements it today: the animated
 * background is AGSL-only, so devices below API 33 get no layer rather than a lesser stand-in.
 */
public interface AmbientBackgroundLayer {
    void updateImage(Bitmap art);

    void pauseRendering();

    void resumeRendering();

    /** The layer as a View for add/visibility plumbing. */
    View asView();
}
