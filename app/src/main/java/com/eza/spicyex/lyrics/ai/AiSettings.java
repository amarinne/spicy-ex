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
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_CUSTOM = "custom";
    static final String CREDENTIAL_SCOPE_OPENAI_OFFICIAL = "openai_official";
    public static final String TRANSLATION_PIPELINE_AI_ONLY = "AI only";
    public static final String TRANSLATION_PIPELINE_GOOGLE_PREVIEW = "Google preview";
    public static final String TRANSLATION_PIPELINE_GOOGLE_DRAFT = "Google draft";

    /**
     * The three Meaning flows, closed on purpose: each one is a distinct request contract and a
     * distinct run/config identity, so an unrecognized stored value can only fall back to the
     * shipped default, never to a guessed fourth behavior.
     */
    public enum MeaningFlow {
        /** Google displays first from its own job; the AI request carries raw lyrics only. */
        GOOGLE_PREVIEW,
        /** Google draft is request input; the paid answer stays refinement-keyed. */
        GOOGLE_DRAFT,
        /** Raw lyrics only, no Google acquisition or fallback. */
        AI_ONLY;

        /**
         * Stable token for layer/run configuration identity. Deliberately never a paid-request
         * input: preview and AI-only share one paid identity, so the token must not leak into
         * {@code configId} of a billed call.
         */
        public String configToken() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static MeaningFlow ofStoredValue(String value) {
            if (TRANSLATION_PIPELINE_GOOGLE_PREVIEW.equals(value)) return GOOGLE_PREVIEW;
            if (TRANSLATION_PIPELINE_AI_ONLY.equals(value)) return AI_ONLY;
            // The default and every unrecognized value read as Google draft. Legacy installs are
            // migrated by meaningFlow() before this sees them.
            return GOOGLE_DRAFT;
        }
    }
    /** The one address that does not change, so nobody has to type it. */
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    /** Same reasoning as OpenAI's: one fixed address nobody should have to paste. */
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    /** Official OpenAI-format base URL from {@code api-docs.deepseek.com}. */
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_PROVIDER_VERSION_PREFIX =
            "deepseek-v1+json-object+thinking=";
    /**
     * How much thinking OpenRouter is asked to buy.
     *
     * <p>Lyric translation into a fixed item shape is not a reasoning problem, and several models
     * behind this endpoint think at high effort unless told otherwise — DeepSeek's default is
     * exactly that. Reasoning tokens are billed as output, so the request that does not ask for
     * them is both cheaper and less likely to run out of budget mid-document.
     *
     * <p>It travels in {@code providerVersion} rather than in a field of its own because that is
     * already what {@code configId} covers: change the effort and the answer may change, so the
     * cached result of the old effort must not be served for the new one.
     */
    public static final String OPENROUTER_REASONING_EFFORT = "low";
    public static final String OPENROUTER_PROVIDER_VERSION =
            "openrouter-v1+reasoning=" + OPENROUTER_REASONING_EFFORT;

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

    /**
     * The model chosen for the selected provider, or empty.
     *
     * <p>Never another provider's choice. The unscoped {@link Settings#AI_MODEL} is read only for
     * the default provider, because that is the only one an install predating provider-scoped
     * models could have been pointed at when it wrote that value. Letting any provider fall back to
     * it made a freshly added provider inherit a name from whatever was selected before — a Gemini
     * model offered as an OpenRouter selection, reported ready, and sent to an endpoint that has
     * never heard of it.
     */
    public String modelName() {
        if (store == null) return "";
        String scoped = AiText.nz(store.get(modelSetting()));
        if (!scoped.isEmpty()) return scoped;
        return PROVIDER_GEMINI.equals(providerChoice())
                ? AiText.nz(store.get(Settings.AI_MODEL)) : "";
    }

    public void setModelName(String model) {
        if (store == null) return;
        store.put(modelSetting(), AiText.nz(model));
    }

    /** Persisted user-visible provider choice. */
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
        if (PROVIDER_OPENROUTER.equals(providerChoice)) return PROVIDER_OPENROUTER;
        if (PROVIDER_DEEPSEEK.equals(providerChoice)) return PROVIDER_DEEPSEEK;
        if (PROVIDER_CUSTOM.equals(providerChoice)) return PROVIDER_CUSTOM;
        return PROVIDER_GEMINI;
    }

    /** True for both OpenAI itself and any compatible endpoint: same wire format, same adapter. */
    public boolean usesOpenAiWire() {
        String choice = providerChoice();
        return PROVIDER_OPENAI.equals(choice) || PROVIDER_OPENROUTER.equals(choice)
                || PROVIDER_DEEPSEEK.equals(choice) || PROVIDER_CUSTOM.equals(choice);
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
        if (PROVIDER_OPENROUTER.equals(providerChoice())) return OPENROUTER_BASE_URL;
        if (PROVIDER_DEEPSEEK.equals(providerChoice())) return DEEPSEEK_BASE_URL;
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

    /**
     * The selected Meaning flow, with the legacy boolean migrated exactly once.
     *
     * <p>{@code true} mapped to Google draft and {@code false} to AI only when the enum setting
     * did not exist; the preview flow is new and is never inferred for an old install. An
     * unrecognized stored value reads as the shipped default rather than as a fourth behavior.
     */
    public MeaningFlow meaningFlow() {
        if (store == null) return MeaningFlow.GOOGLE_DRAFT;
        if (store.contains(Settings.AI_TRANSLATION_PIPELINE)) {
            return MeaningFlow.ofStoredValue(store.get(Settings.AI_TRANSLATION_PIPELINE));
        }
        if (store.contains(Settings.AI_TRANSLATION_REFINE_GOOGLE)) {
            return Boolean.TRUE.equals(store.get(Settings.AI_TRANSLATION_REFINE_GOOGLE))
                    ? MeaningFlow.GOOGLE_DRAFT : MeaningFlow.AI_ONLY;
        }
        return MeaningFlow.GOOGLE_DRAFT;
    }

    /**
     * Whether the AI request carries a Google baseline, kept for callers outside the lane that
     * describe or gate refinement. Only Google draft refines; preview and AI-only run raw lyrics.
     */
    public boolean meaningUsesGoogleBaseline() {
        return meaningFlow() == MeaningFlow.GOOGLE_DRAFT;
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
     *
     * <p>When the structured-output probe has measured this exact endpoint/model pair, the
     * descriptor carries that measurement as its reasoning allowance instead of the contract
     * default — see {@link AiProbeMeasurement}.
     */
    public AiModelDescriptor model() {
        String name = modelName();
        if (name.isEmpty()) return null;
        AiProbeMeasurement.Parsed probe = lastProbe();
        int allowance = probe != null && probe.completionTokens != null
                ? AiProbeMeasurement.allowanceFrom(probe.completionTokens) : -1;
        return new AiModelDescriptor(name, "", AiContract.MAX_REQUEST_BYTES,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                java.util.Collections.singletonList("generateContent"), allowance);
    }

    /**
     * Persists one probe outcome for the selected provider scope.
     *
     * <p>The record is keyed to the endpoint host and model it measured, so a later selection of a
     * different model reads as unmeasured rather than inheriting another model's budget. Called by
     * both probe paths: the explicit test and the one-time readiness check.
     */
    public void recordProbeOutcome(AiModelProbe.Result result) {
        if (store == null || result == null) return;
        Integer tokens = result.trace == null || result.trace.usage.output == null
                ? null : result.trace.usage.output;
        store.put(probeSetting(), AiProbeMeasurement.encode(AiEndpoint.hostOf(endpoint()),
                modelName(), tokens, result.ok ? "" : result.failure));
    }

    /** The stored probe record for the current identity, or null when none matches. */
    private AiProbeMeasurement.Parsed lastProbe() {
        if (store == null) return null;
        return AiProbeMeasurement.decode(store.get(probeSetting()),
                AiEndpoint.hostOf(endpoint()), modelName());
    }

    /** The failure token of the last probe for the current identity, or empty. */
    public String lastProbeFailureToken() {
        AiProbeMeasurement.Parsed probe = lastProbe();
        return probe == null ? "" : AiText.nz(probe.failureToken);
    }

    private Settings.Setting<String> probeSetting() {
        switch (providerChoice()) {
            case PROVIDER_OPENAI: return Settings.AI_PROBE_OPENAI;
            case PROVIDER_OPENROUTER: return Settings.AI_PROBE_OPENROUTER;
            case PROVIDER_DEEPSEEK: return Settings.AI_PROBE_DEEPSEEK;
            case PROVIDER_CUSTOM: return Settings.AI_PROBE_CUSTOM;
            default: return Settings.AI_PROBE_GEMINI;
        }
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
            case PROVIDER_OPENROUTER: return Settings.AI_MODEL_OPENROUTER;
            case PROVIDER_DEEPSEEK: return Settings.AI_MODEL_DEEPSEEK;
            case PROVIDER_CUSTOM: return Settings.AI_MODEL_CUSTOM;
            default: return Settings.AI_MODEL_GEMINI;
        }
    }

    /**
     * How this endpoint is being spoken to, as it enters {@code configId}.
     *
     * <p>OpenRouter is its own token rather than a shared {@code openai-v1} because the request
     * carries traits the plain wire does not — a reasoning budget and provider routing — and a
     * result bought under those is not the same result.
     */
    String providerVersionToken() {
        if (PROVIDER_OPENROUTER.equals(providerChoice())) return OPENROUTER_PROVIDER_VERSION;
        if (PROVIDER_DEEPSEEK.equals(providerChoice())) {
            String mode = store == null ? "Low" : store.get(Settings.AI_DEEPSEEK_REASONING);
            return deepSeekProviderVersion(mode);
        }
        return usesOpenAiWire() ? "openai-v1" : "v1beta";
    }

    static String deepSeekProviderVersion(String mode) {
        if ("Off".equals(mode)) return DEEPSEEK_PROVIDER_VERSION_PREFIX + "disabled";
        String effort = "Max".equals(mode) ? "max" : "High".equals(mode) ? "high" : "low";
        return DEEPSEEK_PROVIDER_VERSION_PREFIX + "enabled+reasoning=" + effort;
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
                providerVersionToken(), model, target,
                layer == LayerKind.SOUND ? AiContract.SOUND_PROMPT_VERSION
                        : baselineRefinement ? AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION
                        : AiContract.PROMPT_VERSION,
                false, baselineRefinement);
    }
}
