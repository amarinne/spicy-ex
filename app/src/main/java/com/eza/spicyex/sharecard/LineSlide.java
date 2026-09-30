package com.eza.spicyex.sharecard;

import android.view.View;

import java.util.List;

/** A lyric line's wrapped lines sliding to a new alignment, and the hand-over after. */
final class LineSlide {
    final View host;
    final List<View> strips;
    Runnable reveal;

    LineSlide(View host, List<View> strips) {
        this.host = host;
        this.strips = strips;
    }
}
