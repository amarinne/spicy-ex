package com.eza.spicyex.lyrics;

import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Renderer-owned mount and animation state for one applied syllable/word. */
public final class SyllableRenderState {
    public View view;
    public View motionView;
    /** Further views that move with {@link #motionView}: the other line-break units of a
     *  segment that flows across lines. */
    public List<View> followerMotionViews = new ArrayList<>();
    public View containerView;
    public boolean motionOwner;
    public SpicyAnimatedTextView textView;
    public SpicyAnimatedTextView romanizedTextView;
    public final List<AnimatedLetterState> letters = new ArrayList<>();
    public Spring scaleSpring;
    public Spring ySpring;
    public Spring glowSpring;
    public Spring localScaleSpring;
    public Spring localYSpring;

    public void clear() {
        view = null;
        motionView = null;
        followerMotionViews = new ArrayList<>();
        containerView = null;
        motionOwner = false;
        textView = null;
        romanizedTextView = null;
        scaleSpring = null;
        ySpring = null;
        glowSpring = null;
        localScaleSpring = null;
        localYSpring = null;
        for (AnimatedLetterState letter : letters) {
            if (letter == null) continue;
            letter.view = null;
            letter.scaleSpring = null;
            letter.ySpring = null;
            letter.glowSpring = null;
        }
        letters.clear();
    }
}
