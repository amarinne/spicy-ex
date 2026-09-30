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
    private final boolean languageModelReady;
    private final boolean animatedBackgroundAvailable;
    private final boolean spicySourceEnabled;

    private PanelSnapshot(Builder builder) {
        this.values = Collections.unmodifiableMap(new HashMap<>(builder.values));
        this.languageModelReady = builder.languageModelReady;
        this.animatedBackgroundAvailable = builder.animatedBackgroundAvailable;
        this.spicySourceEnabled = builder.spicySourceEnabled;
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

    public boolean languageModelReady() {
        return languageModelReady;
    }

    public boolean animatedBackgroundAvailable() {
        return animatedBackgroundAvailable;
    }

    public boolean spicySourceEnabled() {
        return spicySourceEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> values = new HashMap<>();
        private boolean languageModelReady;
        private boolean animatedBackgroundAvailable;
        private boolean spicySourceEnabled;

        public Builder put(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
            return this;
        }

        public Builder languageModelReady(boolean value) {
            languageModelReady = value;
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

        /** All capabilities on: the model-installed, modern-device baseline tests start from. */
        public Builder allCapabilities() {
            languageModelReady = true;
            animatedBackgroundAvailable = true;
            return this;
        }

        public PanelSnapshot build() {
            return new PanelSnapshot(this);
        }
    }
}
