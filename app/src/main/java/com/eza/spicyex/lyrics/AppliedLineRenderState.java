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
    public Spring lineBlurSpring;
    /** 0..1: how lit a line-synced row is (see LyricsLineViewState#stepLineLit). */
    public Spring lineLitSpring;
    public View rowView;
    public SpicyAnimatedTextView mainView;
    public SpicyAnimatedTextView romanView;
    public SpicyAnimatedTextView translationView;
    /** Per-word reading row under timed lyrics (the ViewGroup counterpart of romanView). */
    public View timedRomanRow;
    public List<SpicyAnimatedTextView> dotViews;
    public Spring dotMainScaleSpring;
    public Spring dotMainOpacitySpring;
    public Spring lineShadowSpring;
    public int lastTargetClass = Integer.MIN_VALUE;
    public boolean needsRender = true;
    /** Every spring except opacity/blur was at rest when last fully checked, and only the
     *  opacity/blur fast path has run since - see LyricsLineViewState#needsFrame. */
    public boolean settledExceptFade;
    /** Load-reveal fade factor, 0..1. The renderer multiplies the row's natural opacity by this
     *  instead of the reveal animating the row's alpha itself - two writers on one View property
     *  fight each other, and a reveal that ended at a flat alpha 1 would then visibly snap back
     *  down to the row's real (dimmed) opacity on the next frame. */
    public float entranceProgress = 1f;
    /** Extra blur the load reveal adds on top of the renderer's own, in px. */
    public float entranceBlurPx;

    public void clearMounts() {
        rowView = null;
        mainView = null;
        romanView = null;
        translationView = null;
        timedRomanRow = null;
        dotViews = null;
        opacitySpring = null;
        lineScaleSpring = null;
        lineGlowSpring = null;
        lineBlurSpring = null;
        lineLitSpring = null;
        dotMainScaleSpring = null;
        dotMainOpacitySpring = null;
        lineShadowSpring = null;
        measuredHeightPx = 0;
        lastTargetClass = Integer.MIN_VALUE;
        needsRender = true;
        settledExceptFade = false;
        entranceProgress = 1f;
        entranceBlurPx = 0f;
    }
}
