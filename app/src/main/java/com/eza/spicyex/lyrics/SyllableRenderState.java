package com.eza.spicyex.lyrics;

import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Renderer-owned mount and animation state for one applied syllable/word. */
public final class SyllableRenderState {
    public View view;
    public SpicyAnimatedTextView textView;
    public SpicyAnimatedTextView romanizedTextView;
    public final List<AnimatedLetterState> letters = new ArrayList<>();
    public Spring scaleSpring;
    public Spring ySpring;
    public Spring glowSpring;

    public void clear() {
        view = null;
        textView = null;
        romanizedTextView = null;
        scaleSpring = null;
        ySpring = null;
        glowSpring = null;
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
