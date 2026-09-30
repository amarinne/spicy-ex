package com.eza.spicyex.lyrics;

/** Per-letter animation state for the letter-pop path of long syllables. */
public class AnimatedLetterState {
    public float start;
    public float duration;
    public float glowDuration;
    public SpicyAnimatedTextView view;
    public Spring scaleSpring;
    public Spring ySpring;
    public Spring glowSpring;
    /** F6: this letter's motion constants, synced from its segment per frame. */
    public boolean appleMotion;
}
