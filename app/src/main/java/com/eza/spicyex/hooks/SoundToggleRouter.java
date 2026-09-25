package com.eza.spicyex.hooks;

/**
 * What the Sound toggle does for each gesture.
 *
 * <p>A tap on that toggle belongs to the local reading pipeline: in cycle mode it advances the
 * local language modes. Starting a paid AI reading is a deliberate, billable act, so only a long
 * press — or the automatic gap fill — may ask for one. Keeping the mapping here means the two
 * listeners say what they do and the rule is testable off-device.
 */
public final class SoundToggleRouter {

    public enum Action {
        CYCLE_LOCAL_MODE,
        OPEN_AI_PANEL
    }

    private SoundToggleRouter() {
    }

    public static Action forGesture(boolean longPress) {
        return longPress ? Action.OPEN_AI_PANEL : Action.CYCLE_LOCAL_MODE;
    }
}
