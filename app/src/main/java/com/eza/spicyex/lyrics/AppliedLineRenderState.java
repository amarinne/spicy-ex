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
    public Spring blurSpring;
    public Spring lineShadowSpring;
    public View rowView;
    public SpicyAnimatedTextView mainView;
    public SpicyAnimatedTextView romanView;
    public SpicyAnimatedTextView translationView;
    public List<SpicyAnimatedTextView> dotViews;
    public Spring dotMainScaleSpring;
    public Spring dotMainOpacitySpring;
    public int lastTargetClass = Integer.MIN_VALUE;
    public boolean needsRender = true;
    public float lastTopMeltT0 = Float.NaN;
    public float lastTopMeltT1 = Float.NaN;
    public float lastTopMeltBlurPx = Float.NaN;
    public int lastTopMeltHeight = Integer.MIN_VALUE;

    public void clearMounts() {
        rowView = null;
        mainView = null;
        romanView = null;
        translationView = null;
        dotViews = null;
        opacitySpring = null;
        lineScaleSpring = null;
        lineGlowSpring = null;
        blurSpring = null;
        lineShadowSpring = null;
        dotMainScaleSpring = null;
        dotMainOpacitySpring = null;
        measuredHeightPx = 0;
        lastTargetClass = Integer.MIN_VALUE;
        needsRender = true;
        lastTopMeltT0 = Float.NaN;
        lastTopMeltT1 = Float.NaN;
        lastTopMeltBlurPx = Float.NaN;
        lastTopMeltHeight = Integer.MIN_VALUE;
    }
}
