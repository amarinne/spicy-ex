package com.eza.spicyex.auto;

/** Artwork enters once per compact takeover, independently of other surfaces. */
final class AutoCompactArtwork {
    private boolean owned;

    boolean update(boolean ready, boolean spotify, boolean weather) {
        boolean next = ready && spotify && !weather;
        boolean entering = next && !owned;
        owned = next;
        return entering;
    }

    boolean owned() { return owned; }
}
