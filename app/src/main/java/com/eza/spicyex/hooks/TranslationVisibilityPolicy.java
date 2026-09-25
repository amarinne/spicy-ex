package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.session.LayerKind;

/**
 * What the translation row does when a request is asked for, and when one is already showing.
 *
 * <p>Both rules were previously readable only inside the host view, and together they are the
 * generate-then-toggle contract: asking for a Meaning output reveals the translation row and keeps
 * it revealed, even when the request is refused, because the answer the user is waiting for will
 * land there.
 */
public final class TranslationVisibilityPolicy {

    /** What a tap on the translation toggle does after any requested output was asked for. */
    public enum TapAction {
        TOGGLE,
        KEEP_VISIBLE,
        ABORT
    }

    /** Which row a request for this layer must reveal before the request is made. */
    public enum RevealAction {
        NONE,
        REVEAL_ROMANIZATION,
        REVEAL_TRANSLATION
    }

    private TranslationVisibilityPolicy() {
    }

    /**
     * @param requestedOutput   the surface is configured to generate this layer on tap
     * @param requestStarted    that requested output was accepted; false means nothing will arrive
     * @param keepVisible       the session says the requested output is already on screen
     */
    public static TapAction onTap(boolean requestedOutput, boolean requestStarted,
                                  boolean keepVisible) {
        if (requestedOutput && !requestStarted) return TapAction.ABORT;
        return keepVisible ? TapAction.KEEP_VISIBLE : TapAction.TOGGLE;
    }

    /**
     * A layer's own row has to be visible for its output to be readable. Sound reveals the reading
     * row and Meaning the translation row; a request for the other layer leaves this one alone.
     */
    public static RevealAction revealFor(LayerKind layer, boolean romanizationVisible,
                                         boolean translationVisible) {
        if (layer == LayerKind.SOUND) {
            return romanizationVisible ? RevealAction.NONE : RevealAction.REVEAL_ROMANIZATION;
        }
        if (layer == LayerKind.MEANING) {
            return translationVisible ? RevealAction.NONE : RevealAction.REVEAL_TRANSLATION;
        }
        return RevealAction.NONE;
    }
}
