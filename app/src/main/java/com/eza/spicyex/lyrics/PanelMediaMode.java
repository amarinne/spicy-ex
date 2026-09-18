package com.eza.spicyex.lyrics;

/**
 * Pure policy for the Panel media controls selector, shared by the two-column panel
 * artwork and the track-info readout artwork so both surfaces behave identically.
 *
 * <ul>
 *   <li>{@code Off} — no tap gestures and no swipe; the art is a static cover.</li>
 *   <li>{@code Single tap} — a single tap opens the persistent play/pause overlay.</li>
 *   <li>{@code Double tap} — a double-tap toggles play/pause directly with a brief
 *       icon pulse, skipping the overlay; single taps do nothing.</li>
 * </ul>
 */
public final class PanelMediaMode {
    public static final String OFF = "Off";
    public static final String SINGLE_TAP = "Single tap";
    public static final String DOUBLE_TAP = "Double tap";

    private PanelMediaMode() {
    }

    /** Swipe plus tap gestures are enabled in every mode except Off. */
    public static boolean gesturesEnabled(String mode) {
        return !OFF.equals(mode);
    }

    /** A single tap opens the persistent play/pause overlay (Single-tap mode only). */
    public static boolean revealOnSingleTap(String mode) {
        return SINGLE_TAP.equals(mode);
    }
}
