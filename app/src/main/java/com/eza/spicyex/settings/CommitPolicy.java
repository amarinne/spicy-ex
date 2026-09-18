package com.eza.spicyex.settings;

/**
 * How a setting's edit reaches the store.
 *
 * <ul>
 * <li>{@code IMMEDIATE} — toggle, tap, or single-select pick writes at once.</li>
 * <li>{@code DEBOUNCED} — steppers settle ~250ms after the last tick before committing, so
 * press-and-hold ramps do not rebuild lyrics per tick.</li>
 * <li>{@code CONFIRMING} — language-class selectors accumulate a pending highlight and write
 * only on an explicit Save; dismiss paths never write.</li>
 * </ul>
 */
public enum CommitPolicy {
    IMMEDIATE,
    DEBOUNCED,
    CONFIRMING
}
