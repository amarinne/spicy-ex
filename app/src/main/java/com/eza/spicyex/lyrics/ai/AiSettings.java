package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.session.LayerKind;

/**
 * The AI family's configuration, read as one thing.
 *
 * <p>Everything a run depends on is gathered here so eligibility is a single question with a single
 * answer. Scattering these checks across the lanes is how a build ends up dispatching a paid
 * request with no model chosen, or showing a spinner on a control that was never going to call
 * anything.
 *
 * <p>The credential is deliberately absent from {@link #isConfigured()}. A key is needed for new
 * provider work and for nothing else — an answer already bought stays readable after the key is
 * deleted, because deleting a key must not also delete access to what it paid for.
 */
public final class AiSettings {

    public enum Readiness {
        DISABLED, NO_CREDENTIAL, NO_MODEL, NO_ENDPOINT, READY
    }

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CUSTOM = "custom";
    static final String CREDENTIAL_SCOPE_OPENAI_OFFICIAL = "openai_official";
    public static final String TRANSLATION_PIPELINE_AI_ONLY = "AI only";
    public static final String TRANSLATION_PIPELINE_GOOGLE_DRAFT = "Google draft";
    /** The one address that does not change, so nobody has to type it. */
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final SettingsStore store;
    private final AiCredentialStore credentials;

    public AiSettings(Context context) {
        this(new SettingsStore(context), AiCredentialStore.create(context));
    }

    public AiSettings(SettingsStore store, AiCredentialStore credentials) {
        this.store = store;
        this.credentials = credentials;
    }

    public boolean isEnabled() {
        return store != null && Boolean.TRUE.equals(store.get(Settings.AI_ENABLED));
    }

    public String modelName() {
        if (store == null) return "";
        String scoped = AiText.nz(store.get(modelSetting()));
        // One-time read migration: installs made before provider-scoped models retain the old
        // selection until the owner makes a choice for this provider.
        return scoped.isEmpty() ? AiText.nz(store.get(Settings.AI_MODEL)) : scoped;
    }

    public void setModelName(String model) {
        if (store == null) return;
        store.put(modelSetting(), AiText.nz(model));
    }

    /** {@code gemini}, {@code openai}, or {@code custom}. */
    public String providerChoice() {
        String value = store == null ? "" : AiText.nz(store.get(Settings.AI_PROVIDER));
        return value.isEmpty() ? PROVIDER_GEMINI : value;
    }

    public String providerId() {
        return usesOpenAiWire() ? AiOpenAiProvider.ID : AiGeminiProvider.ID;
    }

    /** Storage scope follows the user-visible provider choice, never the shared wire adapter. */
    public String credentialScope() {
        return credentialScopeFor(providerChoice());
    }

    static String credentialScopeFor(String providerChoice) {
        if (PROVIDER_OPENAI.equals(providerChoice)) return CREDENTIAL_SCOPE_OPENAI_OFFICIAL;
        if (PROVIDER_CUSTOM.equals(providerChoice)) return PROVIDER_CUSTOM;
        return PROVIDER_GEMINI;
    }

    /** True for both OpenAI itself and any compatible endpoint: same wire format, same adapter. */
    public boolean usesOpenAiWire() {
        String choice = providerChoice();
        return PROVIDER_OPENAI.equals(choice) || PROVIDER_CUSTOM.equals(choice);
    }

    /**
     * True only for a self-supplied endpoint.
     *
     * <p>Official OpenAI is its own choice rather than a custom endpoint the owner has to type,
     * because making someone paste a URL they could get wrong — and that we would then have to
     * validate and explain — is worse than shipping the one address that never changes.
     */
    public boolean isCustomEndpoint() {
        return PROVIDER_CUSTOM.equals(providerChoice());
    }

    /** Normalized base URL for whichever OpenAI-wire provider is selected, or empty. */
    public String endpoint() {
        if (PROVIDER_OPENAI.equals(providerChoice())) return OPENAI_BASE_URL;
        return store == null ? "" : AiText.nz(store.get(Settings.AI_ENDPOINT));
    }

    public AiCredentialStore credentials() {
        return credentials;
    }

    public boolean hasCredential() {
        return credentials != null && credentials.has(credentialScope());
    }

    /** Stable, privacy-safe gate state for settings and diagnostics. */
    public Readiness readiness() {
        if (!isEnabled()) return Readiness.DISABLED;
        if (!hasCredential()) return Readiness.NO_CREDENTIAL;
        if (modelName().isEmpty()) return Readiness.NO_MODEL;
        if (usesOpenAiWire() && endpoint().isEmpty()) return Readiness.NO_ENDPOINT;
        return Readiness.READY;
    }

    /** True when a new paid request could be made right now. */
    public boolean canRequest() {
        if (!isEnabled() || modelName().isEmpty() || !hasCredential()) return false;
        // An OpenAI-wire provider with no address would send the document nowhere, slowly.
        return !usesOpenAiWire() || !endpoint().isEmpty();
    }

    /** True when the feature is on and pointed at a model, credential aside. */
    public boolean isConfigured() {
        return isEnabled() && !modelName().isEmpty();
    }

    public boolean translationAutomatic() {
        return "Always use AI".equals(store == null ? "" : store.get(Settings.AI_TRANSLATION_MODE));
    }

    public boolean pronunciationAutomatic() {
        return "Always use AI".equals(store == null ? "" : store.get(Settings.AI_PRONUNCIATION_MODE));
    }

    public boolean soundUsesBaseline() {
        return !"AI only".equals(store == null ? "" : store.get(Settings.AI_PRONUNCIATION_SOURCE));
    }

    public boolean meaningUsesGoogleBaseline() {
        if (store == null) return true;
        if (store.contains(Settings.AI_TRANSLATION_PIPELINE)) {
            return TRANSLATION_PIPELINE_GOOGLE_DRAFT.equals(
                    store.get(Settings.AI_TRANSLATION_PIPELINE));
        }
        if (store.contains(Settings.AI_TRANSLATION_REFINE_GOOGLE)) {
            return Boolean.TRUE.equals(store.get(Settings.AI_TRANSLATION_REFINE_GOOGLE));
        }
        return true;
    }

    public void setMeaningUsesGoogleBaseline(boolean value) {
        if (store != null) {
            store.put(Settings.AI_TRANSLATION_PIPELINE, value
                    ? TRANSLATION_PIPELINE_GOOGLE_DRAFT : TRANSLATION_PIPELINE_AI_ONLY);
        }
    }

    public boolean generateThenToggle() {
        return !"Toggle display only".equals(store == null ? "" : store.get(Settings.AI_BUTTON_BEHAVIOR));
    }

    public boolean meaningLayerEnabled() {
        return store != null && Boolean.TRUE.equals(store.get(Settings.TRANSLATION_ENABLED));
    }

    public boolean soundLayerEnabled() {
        return store != null && Boolean.TRUE.equals(store.get(Settings.TRANSLITERATION_ENABLED));
    }

    public String instructions(LayerKind layer) {
        if (store == null) return "";
        return AiText.nz(store.get(layer == LayerKind.SOUND
                ? Settings.AI_INSTRUCTIONS_SOUND : Settings.AI_INSTRUCTIONS_MEANING));
    }

    public void setInstructions(LayerKind layer, String value) {
        if (store == null) return;
        store.put(layer == LayerKind.SOUND ? Settings.AI_INSTRUCTIONS_SOUND
                : Settings.AI_INSTRUCTIONS_MEANING, AiContract.normalizeSteering(value));
    }

    public String customInstructions(LayerKind layer) {
        if (store == null) return "";
        return AiText.nz(store.get(layer == LayerKind.SOUND
                ? Settings.AI_CUSTOM_INSTRUCTIONS_SOUND
                : Settings.AI_CUSTOM_INSTRUCTIONS_MEANING));
    }

    public void setCustomInstructions(LayerKind layer, String value) {
        if (store == null) return;
        store.put(layer == LayerKind.SOUND ? Settings.AI_CUSTOM_INSTRUCTIONS_SOUND
                : Settings.AI_CUSTOM_INSTRUCTIONS_MEANING, AiContract.normalizeSteering(value));
    }

    /**
     * The model as the planner needs it.
     *
     * <p>Limits are the conservative floor rather than the model's real ones until discovery has
     * been run and its metadata persisted. Under-estimating a limit splits a document into more
     * chunks than necessary; over-estimating it gets the request rejected after it was billed.
     */
    public AiModelDescriptor model() {
        String name = modelName();
        if (name.isEmpty()) return null;
        return new AiModelDescriptor(name, "", AiContract.MAX_REQUEST_BYTES,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS, java.util.Collections.singletonList("generateContent"));
    }

    /** A provider bound to the stored key, re-read on every call so a rotation takes effect. */
    public AiProvider provider() {
        final AiCredentialStore keys = credentials;
        final String id = credentialScope();
        AiGeminiProvider.CredentialSource source = new AiGeminiProvider.CredentialSource() {
            @Override public String secret() {
                return keys == null ? "" : keys.load(id);
            }
        };
        return usesOpenAiWire()
                ? new AiOpenAiProvider(endpoint(), source)
                : new AiGeminiProvider(source);
    }

    private Settings.Setting<String> modelSetting() {
        switch (providerChoice()) {
            case PROVIDER_OPENAI: return Settings.AI_MODEL_OPENAI;
            case PROVIDER_CUSTOM: return Settings.AI_MODEL_CUSTOM;
            default: return Settings.AI_MODEL_GEMINI;
        }
    }

    /** Per-call configuration for {@code layer}, or null when nothing could be requested. */
    public AiProviderConfig providerConfig(LayerKind layer, String target) {
        return providerConfig(layer, target, false);
    }

    public AiProviderConfig providerConfig(LayerKind layer, String target,
                                           boolean baselineRefinement) {
        AiModelDescriptor model = model();
        if (model == null) return null;
        return new AiProviderConfig(layer, usesOpenAiWire() ? endpoint() : null,
                usesOpenAiWire() ? "openai-v1" : "v1beta", model, target,
                layer == LayerKind.SOUND ? AiContract.SOUND_PROMPT_VERSION
                        : baselineRefinement ? AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION
                        : AiContract.PROMPT_VERSION,
                false, baselineRefinement);
    }
}
