package com.eza.spicyex.beautifullyrics.entities;

import android.graphics.Bitmap;
import android.view.View;

/**
 * Common contract for the lyrics ambient background layers so the controller can swap
 * implementations (the kawarp domain-warp shader on capable devices, the CPU blob renderer
 * elsewhere) without caring which is attached.
 */
public interface AmbientBackgroundLayer {
    void updateImage(Bitmap art);

    void pauseRendering();

    void resumeRendering();

    /** The layer as a View for add/visibility plumbing. */
    View asView();
}
