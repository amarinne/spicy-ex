package com.eza.spicyex.settings.registry;

import java.util.Objects;

/**
 * Layout flags and resolved values shared by registry predicates. No storage keys are derived here.
 *
 * <p>{@code languagePackReady} reports the optional language data pack. The panel's Japanese reading rows need the downloaded dictionaries and
 * tokenizer. Ambient effects separately require runtime shader support.
 */
public final class UiContext {
    public final boolean twoColumn;
    public final boolean cardMode;
    public final boolean landscape;
    public final SettingsSnapshot settings;
    /** The optional language data pack is installed. */
    public final boolean languagePackReady;

    public final boolean ambientEffectsAvailable;

    public UiContext(boolean twoColumn, boolean cardMode, boolean landscape, SettingsSnapshot settings,
            boolean languagePackReady) {
        this(twoColumn, cardMode, landscape, settings, languagePackReady, true);
    }

    public UiContext(boolean twoColumn, boolean cardMode, boolean landscape, SettingsSnapshot settings,
            boolean languagePackReady, boolean ambientEffectsAvailable) {
        this.ambientEffectsAvailable = ambientEffectsAvailable;
        this.twoColumn = twoColumn;
        this.cardMode = cardMode;
        this.landscape = landscape;
        this.settings = Objects.requireNonNull(settings);
        this.languagePackReady = languagePackReady;
    }
}
