package com.eza.spicyex.lyrics;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.session.LayerConfigIds;
import com.eza.spicyex.lyrics.session.LyricsSourcePolicy;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TranslationBackend;

/**
 * Immutable translation and Meaning snapshot: the user gate, backend, target, source selection,
 * and display flow read together.
 *
 * <p>Owns no schema, default, persistence, cache version, or live observation. The reader resolves
 * orientation and stored-label validation, never this holder. The layer identity is composed from
 * {@link #identityInputs()} by {@link LayerConfigIds}. Consumers take a fresh snapshot where they
 * read the settings before.
 */
public final class TranslationConfig {
    public final boolean enabled;
    public final TranslationBackend backend;
    public final String target;
    public final String sourceMode;
    public final String sourceLanguage;
    public final AiSettings.MeaningFlow meaningFlow;

    private final IdentityInputs identity;

    private static final SettingsReader DEFAULTS = new SettingsReader() {
        @Override
        public <T> T get(Settings.Setting<T> setting) {
            return setting.defaultValue;
        }
    };

    private TranslationConfig(boolean enabled, TranslationBackend backend, String target,
            String sourceMode, String sourceLanguage, AiSettings.MeaningFlow meaningFlow) {
        this.enabled = enabled;
        this.backend = backend;
        this.target = target;
        this.sourceMode = sourceMode;
        this.sourceLanguage = sourceLanguage;
        this.meaningFlow = meaningFlow;
        this.identity = new IdentityInputs(enabled, backend.storedLabel(), target, sourceMode,
                sourceLanguage, meaningFlow.configToken());
    }

    /** A null reader reads every schema default, like an absent config elsewhere. */
    public static TranslationConfig read(SettingsReader reader) {
        SettingsReader source = reader == null ? DEFAULTS : reader;
        String backendValue = source.get(Settings.TRANSLATION_BACKEND);
        // The stored "disabled" label predates the typed backend and still means the gate is off.
        boolean enabled = Boolean.TRUE.equals(source.get(Settings.TRANSLATION_ENABLED))
                && !"disabled".equalsIgnoreCase(backendValue);
        String sourceMode = source.get(Settings.SOURCE_LANGUAGE_MODE);
        return new TranslationConfig(
                enabled,
                StoredValues.fromStored(TranslationBackend.class, backendValue),
                source.get(Settings.TRANSLATION_TARGET),
                sourceMode,
                LyricsSourcePolicy.isStrictSourceLanguage(sourceMode)
                        ? source.get(Settings.SOURCE_LANGUAGE) : "auto",
                AiSettings.MeaningFlow.ofStoredValue(
                        source.get(Settings.AI_TRANSLATION_PIPELINE)));
    }

    /** True when the Meaning lane is served by Google rather than the configured AI provider. */
    public boolean usesGoogleBackend() {
        return backend == TranslationBackend.GOOGLE_UNOFFICIAL;
    }

    /** True only for the flow that sends a Google draft to the provider as refinement input. */
    public boolean meaningUsesGoogleBaseline() {
        return meaningFlow == AiSettings.MeaningFlow.GOOGLE_DRAFT;
    }

    /** The exact values {@link LayerConfigIds#meaning} consumes, as one holder. */
    public IdentityInputs identityInputs() {
        return identity;
    }

    public static final class IdentityInputs {
        public final boolean enabled;
        public final String backend;
        public final String target;
        public final String sourceMode;
        public final String sourceLanguage;
        public final String meaningFlow;

        private IdentityInputs(boolean enabled, String backend, String target, String sourceMode,
                String sourceLanguage, String meaningFlow) {
            this.enabled = enabled;
            this.backend = backend;
            this.target = target;
            this.sourceMode = sourceMode;
            this.sourceLanguage = sourceLanguage;
            this.meaningFlow = meaningFlow;
        }

        public String configId() {
            return LayerConfigIds.meaning(enabled, backend, target, sourceMode, sourceLanguage,
                    meaningFlow);
        }
    }
}
