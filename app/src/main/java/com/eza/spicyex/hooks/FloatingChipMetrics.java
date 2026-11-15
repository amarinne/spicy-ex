package com.eza.spicyex.hooks;

/** The size and timing the skip and jump-to-current pills share as one stacked pair. */
final class FloatingChipMetrics {
    static final int HEIGHT_DP = 44;
    static final int STACK_GAP_DP = 8;
    /** Vertical distance between the two stacked chips. */
    static final int STACK_OFFSET_DP = HEIGHT_DP + STACK_GAP_DP;
    /** How long a pill keeps its label before collapsing to the icon. */
    static final long COLLAPSE_DELAY_MS = 3200L;

    private FloatingChipMetrics() {
    }
}
