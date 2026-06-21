package com.eza.spicyex.lyrics;

import android.view.View;

import java.util.List;

/** Renderer-owned mount and animation state for one applied lyric row. */
public final class AppliedLineRenderState {
    public int baseTextSp;
    public int measuredHeightPx;
    public Spring opacitySpring;
    public Spring lineScaleSpring;
    public Spring lineGlowSpring;
    public View rowView;
    public SpicyAnimatedTextView mainView;
    public SpicyAnimatedTextView romanView;
    public SpicyAnimatedTextView translationView;
    public List<SpicyAnimatedTextView> dotViews;
    public Spring dotMainScaleSpring;
    public Spring dotMainOpacitySpring;

    public void clearMounts() {
        rowView = null;
        mainView = null;
        romanView = null;
        translationView = null;
        dotViews = null;
        opacitySpring = null;
        lineScaleSpring = null;
        lineGlowSpring = null;
        dotMainScaleSpring = null;
        dotMainOpacitySpring = null;
        measuredHeightPx = 0;
    }
}
