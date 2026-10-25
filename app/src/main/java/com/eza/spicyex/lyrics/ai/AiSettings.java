package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.TranslationConfig;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.settings.values.AiButtonBehavior;
import com.eza.spicyex.settings.values.AiDeepSeekReasoning;
import com.eza.spicyex.settings.values.AiPronunciationMode;
import com.eza.spicyex.settings.values.AiPronunciationSource;
import com.eza.spicyex.settings.values.AiProviderChoice;
import com.eza.spicyex.settings.values.AiTranslationMode;
import com.eza.spicyex.settings.values.StoredValues;

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

        /** The stored label that selects this flow, the inverse of {@link #ofStoredValue}. */
        public String storedLabel() {
            if (this == GOOGLE_PREVIEW) return TRANSLATION_PIPELINE_GOOGLE_PREVIEW;
            if (this == AI_ONLY) return TRANSLATION_PIPELINE_AI_ONLY;
            return TRANSLATION_PIPELINE_GOOGLE_DRAFT;
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
        return providerChoiceValue() == AiProviderChoice.GEMINI
                ? AiText.nz(store.get(Settings.AI_MODEL)) : "";
    }

    public void setModelName(String model) {
        if (store == null) return;
        store.put(modelSetting(), AiText.nz(model));
    }

    /** Persisted user-visible provider choice. */
    public String providerChoice() {
        return providerChoiceValue().storedLabel();
    }

    private AiProviderChoice providerChoiceValue() {
        return store == null ? AiProviderChoice.DEFAULT
                : store.get(Settings.AI_PROVIDER, AiProviderChoice.class);
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
        AiProviderChoice choice = providerChoiceValue();
        return choice == AiProviderChoice.OPENAI || choice == AiProviderChoice.OPENROUTER
                || choice == AiProviderChoice.DEEPSEEK || choice == AiProviderChoice.CUSTOM;
    }

    /**
     * True only for a self-supplied endpoint.
     *
     * <p>Official OpenAI is its own choice rather than a custom endpoint the owner has to type,
     * because making someone paste a URL they could get wrong — and that we would then have to
     * validate and explain — is worse than shipping the one address that never changes.
     */
    public boolean isCustomEndpoint() {
        return providerChoiceValue() == AiProviderChoice.CUSTOM;
    }

    /** Normalized base URL for whichever OpenAI-wire provider is selected, or empty. */
    public String endpoint() {
        AiProviderChoice choice = providerChoiceValue();
        if (choice == AiProviderChoice.OPENAI) return OPENAI_BASE_URL;
        if (choice == AiProviderChoice.OPENROUTER) return OPENROUTER_BASE_URL;
        if (choice == AiProviderChoice.DEEPSEEK) return DEEPSEEK_BASE_URL;
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

    /** Publication fence only; it does not change historical paid identities. */
    public String replayFingerprint() {
        com.google.gson.JsonObject identity = new com.google.gson.JsonObject();
        identity.addProperty("enabled", isEnabled());
        identity.addProperty("provider", providerChoice());
        identity.addProperty("endpoint", endpoint());
        identity.addProperty("model", modelName());
        identity.addProperty("meaningFlow", meaningFlow().name());
        identity.addProperty("soundBaseline", soundUsesBaseline());
        for (LayerKind layer : new LayerKind[]{LayerKind.MEANING, LayerKind.SOUND}) {
            identity.addProperty(layer.name() + "Enabled", layer == LayerKind.MEANING
                    ? meaningLayerEnabled() : soundLayerEnabled());
            identity.addProperty(layer.name() + "Instructions", instructions(layer));
            AiProviderConfig config = providerConfig(layer,
                    layer == LayerKind.MEANING ? "en" : AiContract.ORTHOGRAPHY_LATIN,
                    meaningFlow() == MeaningFlow.GOOGLE_DRAFT);
            identity.addProperty(layer.name() + "Version", config == null ? "" : config.providerVersion);
        }
        return com.eza.spicyex.lyrics.session.Digests.sha256(identity.toString());
    }

    public boolean translationAutomatic() {
        return store != null && store.get(Settings.AI_TRANSLATION_MODE, AiTranslationMode.class)
                == AiTranslationMode.ALWAYS_USE_AI;
    }

    public boolean pronunciationAutomatic() {
        return store != null && store.get(Settings.AI_PRONUNCIATION_MODE, AiPronunciationMode.class)
                == AiPronunciationMode.ALWAYS_USE_AI;
    }

    public boolean soundUsesBaseline() {
        return store == null || store.get(Settings.AI_PRONUNCIATION_SOURCE,
                AiPronunciationSource.class) != AiPronunciationSource.AI_ONLY;
    }

    /**
     * The selected Meaning flow, with the legacy boolean migrated exactly once.
     *
     * <p>{@code true} mapped to Google draft and {@code false} to AI only when the enum setting
     * did not exist; the preview flow is new and is never inferred for an old install. An
     * unrecognized stored value reads as the shipped default rather than as a fourth behavior.
     */
    public MeaningFlow meaningFlow() {
        if (store == null) return TranslationConfig.read(null).meaningFlow;
        // Only a store can say whether the flow key was ever written. Without that probe an
        // install that predates the key would read the default instead of its migrated choice.
        if (!store.contains(Settings.AI_TRANSLATION_PIPELINE)
                && store.contains(Settings.AI_TRANSLATION_REFINE_GOOGLE)) {
            return Boolean.TRUE.equals(store.get(Settings.AI_TRANSLATION_REFINE_GOOGLE))
                    ? MeaningFlow.GOOGLE_DRAFT : MeaningFlow.AI_ONLY;
        }
        return translation().meaningFlow;
    }

    /** The translation and Meaning settings this object composes, read as one holder. */
    public TranslationConfig translation() {
        return TranslationConfig.read(store);
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
        return store == null || store.get(Settings.AI_BUTTON_BEHAVIOR, AiButtonBehavior.class)
                != AiButtonBehavior.TOGGLE_DISPLAY_ONLY;
    }

    public boolean meaningLayerEnabled() {
        return translation().enabled;
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

    /** One immutable settings probe, including the key selected before its worker starts. */
    public static final class ProbeSnapshot {
        public final AiProvider provider;
        public final AiProviderConfig config;
        final String identity;
        final String endpoint;
        final String model;
        final Settings.Setting<String> probeSetting;

        private ProbeSnapshot(AiProvider provider, AiProviderConfig config, String identity,
                              String endpoint, Settings.Setting<String> probeSetting) {
            this.provider = provider;
            this.config = config;
            this.identity = identity;
            this.endpoint = endpoint;
            this.model = config.model.name;
            this.probeSetting = probeSetting;
        }
    }

    /** Capture on the settings thread, before another edit can change the request identity. */
    public ProbeSnapshot snapshotProbe() {
        return snapshotProbe(AiTransports.live());
    }

    ProbeSnapshot snapshotProbe(AiGeminiProvider.Transport transport) {
        if (!isEnabled()) return null;
        String choice = providerChoice();
        String endpoint = endpoint();
        boolean openAiWire = usesOpenAiWire();
        AiProviderConfig config = providerConfig(LayerKind.MEANING, "en");
        if (config == null || (openAiWire && endpoint.isEmpty())) return null;
        final String secret = credentials == null ? ""
                : credentials.load(credentialScopeFor(choice));
        if (secret.isEmpty()) return null;
        AiGeminiProvider.CredentialSource source = () -> secret;
        AiProvider provider = openAiWire
                ? new AiOpenAiProvider(endpoint, source, transport)
                : new AiGeminiProvider(source, transport);
        return new ProbeSnapshot(provider, config,
                choice + '\n' + endpoint + '\n' + config.model.name, endpoint, probeSetting());
    }

    /** Persist only the accepted attempt's endpoint, model, and provider scope. */
    void recordProbeOutcome(ProbeSnapshot snapshot, AiModelProbe.Result result) {
        if (store == null || snapshot == null || result == null) return;
        Integer tokens = result.trace == null || result.trace.usage.output == null
                ? null : result.trace.usage.output;
        store.put(snapshot.probeSetting, AiProbeMeasurement.encode(
                AiEndpoint.hostOf(snapshot.endpoint), snapshot.model, tokens,
                result.ok ? "" : result.failure));
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
        switch (providerChoiceValue()) {
            case OPENAI: return Settings.AI_PROBE_OPENAI;
            case OPENROUTER: return Settings.AI_PROBE_OPENROUTER;
            case DEEPSEEK: return Settings.AI_PROBE_DEEPSEEK;
            case CUSTOM: return Settings.AI_PROBE_CUSTOM;
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

    /** Model discovery needs a key and endpoint, but does not need a model selected yet. */
    public static final class DiscoverySnapshot {
        public final AiProvider provider;
        public final String providerChoice;
        private final String endpoint;
        private final String credentialDigest;

        private DiscoverySnapshot(AiProvider provider, String choice, String endpoint, String secret) {
            this.provider = provider;
            this.providerChoice = choice;
            this.endpoint = endpoint;
            this.credentialDigest = com.eza.spicyex.lyrics.session.Digests.sha256(secret);
        }

        public boolean matches(AiSettings settings) {
            return settings != null && providerChoice.equals(settings.providerChoice())
                    && endpoint.equals(settings.endpoint()) && settings.credentials != null
                    && credentialDigest.equals(com.eza.spicyex.lyrics.session.Digests.sha256(
                            settings.credentials.load(credentialScopeFor(providerChoice))));
        }
    }

    public DiscoverySnapshot snapshotDiscovery() {
        return snapshotDiscovery(AiTransports.live());
    }

    DiscoverySnapshot snapshotDiscovery(AiGeminiProvider.Transport transport) {
        String choice = providerChoice();
        String endpoint = endpoint();
        boolean openAiWire = usesOpenAiWire();
        String secret = credentials == null ? "" : credentials.load(credentialScopeFor(choice));
        if (secret.isEmpty() || openAiWire && endpoint.isEmpty()) return null;
        AiGeminiProvider.CredentialSource key = () -> secret;
        AiProvider captured = openAiWire ? new AiOpenAiProvider(endpoint, key, transport)
                : new AiGeminiProvider(key, transport);
        return new DiscoverySnapshot(captured, choice, endpoint, secret);
    }

    private Settings.Setting<String> modelSetting() {
        switch (providerChoiceValue()) {
            case OPENAI: return Settings.AI_MODEL_OPENAI;
            case OPENROUTER: return Settings.AI_MODEL_OPENROUTER;
            case DEEPSEEK: return Settings.AI_MODEL_DEEPSEEK;
            case CUSTOM: return Settings.AI_MODEL_CUSTOM;
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
        AiProviderChoice choice = providerChoiceValue();
        if (choice == AiProviderChoice.OPENROUTER) return OPENROUTER_PROVIDER_VERSION;
        if (choice == AiProviderChoice.DEEPSEEK) {
            AiDeepSeekReasoning mode = store == null ? AiDeepSeekReasoning.DEFAULT
                    : store.get(Settings.AI_DEEPSEEK_REASONING, AiDeepSeekReasoning.class);
            return deepSeekProviderVersion(mode);
        }
        return usesOpenAiWire() ? "openai-v1" : "v1beta";
    }

    static String deepSeekProviderVersion(AiDeepSeekReasoning mode) {
        if (mode == AiDeepSeekReasoning.OFF) return DEEPSEEK_PROVIDER_VERSION_PREFIX + "disabled";
        String effort = mode == AiDeepSeekReasoning.MAX ? "max"
                : mode == AiDeepSeekReasoning.HIGH ? "high" : "low";
        return DEEPSEEK_PROVIDER_VERSION_PREFIX + "enabled+reasoning=" + effort;
    }

    static String deepSeekProviderVersion(String mode) {
        return deepSeekProviderVersion(StoredValues.fromStored(AiDeepSeekReasoning.class, mode));
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
