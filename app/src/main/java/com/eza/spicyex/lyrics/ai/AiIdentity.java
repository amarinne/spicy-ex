package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identity of a paid result: what was asked, and what it was asked about.
 *
 * <p>Two digests, deliberately separate. {@code configId} covers how the question was put — the
 * provider, model, endpoint, target, steering, and prompt contract. {@code docDigest} covers the
 * document it was put about — the rows, their classes and roles, and the track metadata that
 * travelled with them. A record answers a request only when both match, which is what makes a
 * repeat visit free and stops a record produced under one configuration being served for another.
 *
 * <p>Nothing here is keyed on a build stamp, deploy epoch, or processing version. Those exist to
 * make development iterate, and iterating is exactly when the owner is the one paying: a key that
 * included them would throw away purchased work on every build.
 */
public final class AiIdentity {

    /** Everything {@code configId} covers. Absent values are explicit null, never omitted. */
    public static final class Config {
        public LayerKind layer = LayerKind.MEANING;
        public String provider;
        public String providerVersion;
        /** Normalized base URL for an OpenAI-compatible endpoint, or null. */
        public String endpoint;
        public String modelName;
        /** Target language for Meaning, target orthography for Sound. */
        public String targetLang;
        /**
         * The Sound target orthography, named by the contract's field list.
         *
         * <p>Kept alongside {@link #pronunciationSystem} rather than instead of it: two systems can
         * write the same orthography — Mandarin pinyin and Cantonese jyutping are both Latin — so
         * collapsing them would let one system's paid reading be served for the other's request.
         */
        public String targetOrthography;
        public String sourceLanguage;
        /** Which pronunciation system produced the reading, where one applies. */
        public String pronunciationSystem;
        /** {@code whole_line_v1}, or null. */
        public String soundMode;
        /** {@code existing_output_v1} or {@code raw_source_v1}, or null. */
        public String soundBaselineMode;
        /** Raw steering; normalized into the digest. */
        public String instructions;
        public int promptVersion = AiContract.PROMPT_VERSION;

        /** An explicit quality revision additionally carries its lineage. */
        public Integer iterationPromptVersion;
        public String parentRecordKey;
        public String parentOutputDigest;
        public String revisionInstructions;
    }

    private AiIdentity() {
    }

    /** Digest of the configuration a result was produced under. */
    public static String buildConfigId(Config config) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("layer", AiContract.layerToken(config.layer));
        value.put("provider", AiText.nz(config.provider));
        value.put("providerVersion", AiText.nz(config.providerVersion));
        value.put("endpoint", config.endpoint);
        value.put("modelName", AiText.nz(config.modelName));
        value.put("targetLang", AiText.nz(config.targetLang));
        value.put("targetOrthography", config.targetOrthography);
        value.put("sourceLanguage", config.sourceLanguage);
        value.put("pronunciationSystem", config.pronunciationSystem);
        value.put("soundMode", config.soundMode);
        value.put("soundBaselineMode", config.soundBaselineMode);
        value.put("instructions", AiContract.normalizeSteering(config.instructions));
        value.put("promptVersion", config.promptVersion);
        value.put("temperature", AiContract.TEMPERATURE);
        value.put("contextMode", AiContract.CONTEXT_MODE);
        value.put("iterationPromptVersion", config.iterationPromptVersion);
        value.put("parentRecordKey", config.parentRecordKey);
        value.put("parentOutputDigest", config.parentOutputDigest);
        value.put("revisionInstructions", config.revisionInstructions == null
                ? null : AiContract.normalizeSteering(config.revisionInstructions));
        return Digests.canonicalSha256(value);
    }

    /**
     * Digest of the document the request was about.
     *
     * @param includeBaseline true for Sound, where the existing local reading is sent as {@code p}
     *                        and is therefore part of what the model was given
     */
    public static String buildDocDigest(List<AiLine> rows, AiLyricContext context,
                                        boolean includeBaseline) {
        List<Object> rowValues = new ArrayList<>();
        if (rows != null) {
            for (AiLine row : rows) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", row.id);
                value.put("class", row.lineClass.token);
                value.put("sendDisposition", row.sendDisposition.token);
                value.put("sourceText", row.sourceText);
                value.put("voice", AiVoiceHint.tokenOf(row.voice));
                value.put("allowUnchanged", row.allowUnchanged);
                if (includeBaseline) {
                    value.put("baselineTranslatedText", row.baselineText);
                    value.put("baselineProvenance", row.baselineProvenance);
                }
                rowValues.add(value);
            }
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("context", AiLyricContext.normalize(context).toDigestMap());
        document.put("rows", rowValues);
        return Digests.canonicalSha256(document);
    }

    /**
     * The paid store's key for this result.
     *
     * <p>Deliberately the existing {@link PaidArtifactIdentity} rather than a second key scheme.
     * The contract's logical key is
     * {@code layer | schema | configId | docDigest | chunkPlanVersion}, and all three
     * contract-version components fold into the prompt-contract slot: they name which contract
     * produced the artifact, which is exactly what that slot is for. The layer is carried
     * explicitly, never inferred from the schema number.
     */
    public static PaidArtifactIdentity recordIdentity(LayerKind layer, String docDigest,
                                                      String providerId, String modelName,
                                                      String configId) {
        return new PaidArtifactIdentity(layer, docDigest, providerId, modelName,
                promptContractId(layer, configId));
    }

    /** {@code ai|schema=…|plan=…|cfg=…} — the contract axis, and nothing from the build axis. */
    public static String promptContractId(LayerKind layer, String configId) {
        return "ai|schema=" + AiContract.schemaFor(layer)
                + "|plan=" + AiContract.CHUNK_PLAN_VERSION
                + "|cfg=" + AiText.nz(configId);
    }
}
