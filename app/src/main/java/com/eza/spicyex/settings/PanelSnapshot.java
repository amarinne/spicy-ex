package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable applied-state snapshot for the settings panel.
 *
 * <p>Owns the answer to "what is the panel showing": persisted setting values plus the
 * capability flags that gate them. Pure Java: no Android types, no store reads. The Android
 * layer builds one per render pass ({@code SettingsPanel.captureSnapshot}) and hands it to
 * {@link PanelPolicy}; JVM tests build one directly from literals.
 */
public final class PanelSnapshot {
    private final Map<String, Object> values;
    private final boolean translationAvailable;
    private final boolean transliterationAvailable;
    private final boolean languageModelReady;
    private final boolean appleFontAvailable;
    private final boolean animatedBackgroundAvailable;
    private final boolean spicySourceEnabled;
    private final boolean aiOffered;

    private PanelSnapshot(Builder builder) {
        this.values = Collections.unmodifiableMap(new HashMap<>(builder.values));
        this.translationAvailable = builder.translationAvailable;
        this.transliterationAvailable = builder.transliterationAvailable;
        this.languageModelReady = builder.languageModelReady;
        this.appleFontAvailable = builder.appleFontAvailable;
        this.animatedBackgroundAvailable = builder.animatedBackgroundAvailable;
        this.spicySourceEnabled = builder.spicySourceEnabled;
        this.aiOffered = builder.aiOffered;
    }

    /** Coerced value for a setting; missing entries read as the declared default. */
    public <T> T get(Settings.Setting<T> setting) {
        try {
            return setting.coerce(values.get(setting.key));
        } catch (RuntimeException invalidStoredValue) {
            return setting.defaultValue;
        }
    }

    public boolean isAiEnabled() {
        return Boolean.TRUE.equals(get(Settings.AI_ENABLED));
    }

    public boolean translationAvailable() {
        return translationAvailable;
    }

    public boolean transliterationAvailable() {
        return transliterationAvailable;
    }

    public boolean languageModelReady() {
        return languageModelReady;
    }

    public boolean appleFontAvailable() {
        return appleFontAvailable;
    }

    public boolean animatedBackgroundAvailable() {
        return animatedBackgroundAvailable;
    }

    public boolean spicySourceEnabled() {
        return spicySourceEnabled;
    }

    /** Whether the AI family is offered at all (Full-only gate, independent of the master switch). */
    public boolean aiOffered() {
        return aiOffered;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new HashMap<>();
        private boolean translationAvailable;
        private boolean transliterationAvailable;
        private boolean languageModelReady;
        private boolean appleFontAvailable;
        private boolean animatedBackgroundAvailable;
        private boolean spicySourceEnabled;
        private boolean aiOffered;

        public Builder put(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
            return this;
        }

        public Builder translationAvailable(boolean value) {
            translationAvailable = value;
            return this;
        }

        public Builder transliterationAvailable(boolean value) {
            transliterationAvailable = value;
            return this;
        }

        public Builder languageModelReady(boolean value) {
            languageModelReady = value;
            return this;
        }

        public Builder appleFontAvailable(boolean value) {
            appleFontAvailable = value;
            return this;
        }

        public Builder animatedBackgroundAvailable(boolean value) {
            animatedBackgroundAvailable = value;
            return this;
        }

        public Builder spicySourceEnabled(boolean value) {
            spicySourceEnabled = value;
            return this;
        }

        public Builder aiOffered(boolean value) {
            aiOffered = value;
            return this;
        }

        /** All capabilities on: the Full-build, modern-device baseline tests start from. */
        public Builder allCapabilities() {
            translationAvailable = true;
            transliterationAvailable = true;
            languageModelReady = true;
            appleFontAvailable = true;
            animatedBackgroundAvailable = true;
            aiOffered = true;
            return this;
        }

        public PanelSnapshot build() {
            return new PanelSnapshot(this);
        }
    }
}
