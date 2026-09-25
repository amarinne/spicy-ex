package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

/** The translation row's two host rules, off-device. */
public final class TranslationVisibilityHostDecisionTest {

    @Test public void aRefusedRequestLeavesTheRowAlone() {
        assertEquals(TranslationVisibilityPolicy.TapAction.ABORT,
                TranslationVisibilityPolicy.onTap(true, false, false));
    }

    @Test public void outputAlreadyShowingIsNotToggledAway() {
        assertEquals(TranslationVisibilityPolicy.TapAction.KEEP_VISIBLE,
                TranslationVisibilityPolicy.onTap(true, true, true));
    }

    @Test public void aPlainTapTogglesTheRow() {
        assertEquals(TranslationVisibilityPolicy.TapAction.TOGGLE,
                TranslationVisibilityPolicy.onTap(false, true, false));
        assertEquals(TranslationVisibilityPolicy.TapAction.TOGGLE,
                TranslationVisibilityPolicy.onTap(true, true, false));
    }

    @Test public void aRequestRevealsItsOwnRow() {
        assertEquals(TranslationVisibilityPolicy.RevealAction.REVEAL_ROMANIZATION,
                TranslationVisibilityPolicy.revealFor(LayerKind.SOUND, false, true));
        assertEquals(TranslationVisibilityPolicy.RevealAction.REVEAL_TRANSLATION,
                TranslationVisibilityPolicy.revealFor(LayerKind.MEANING, true, false));
    }

    @Test public void aRequestLeavesAVisibleRowAlone() {
        assertEquals(TranslationVisibilityPolicy.RevealAction.NONE,
                TranslationVisibilityPolicy.revealFor(LayerKind.SOUND, true, false));
        assertEquals(TranslationVisibilityPolicy.RevealAction.NONE,
                TranslationVisibilityPolicy.revealFor(LayerKind.MEANING, false, true));
    }
}
