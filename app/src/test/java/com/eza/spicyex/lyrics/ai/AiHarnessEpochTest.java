package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.Digests;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The harness epoch must add identity only where it is new.
 *
 * <p>An epoch-zero digest that moved would orphan every record already stored on a device and
 * every golden vector the desktop fork produces. A positive epoch that did not move would serve
 * old-harness output as if the new harness produced it.
 */
public class AiHarnessEpochTest {

    private static AiIdentity.Config meaningConfig() {
        AiIdentity.Config config = new AiIdentity.Config();
        config.provider = "openai";
        config.providerVersion = "1";
        config.modelName = "model";
        config.targetLang = "en";
        config.promptVersion = 1;
        return config;
    }

    @Test
    public void theMeaningLayerRunsAtEpochOneAndSoundStaysLegacy() {
        assertEquals(0, AiHarnessEpoch.LEGACY);
        assertEquals(1, AiHarnessEpoch.MEANING);
        assertEquals(AiHarnessEpoch.MEANING, AiHarnessEpoch.forLayer(LayerKind.MEANING));
        assertEquals(AiHarnessEpoch.LEGACY, AiHarnessEpoch.forLayer(LayerKind.SOUND));
        assertTrue(AiHarnessEpoch.isLegacy(0, LayerKind.MEANING));
        assertFalse(AiHarnessEpoch.isLegacy(1, LayerKind.MEANING));
        assertFalse("an unknown epoch is not legacy output", AiHarnessEpoch.isLegacy(-1, LayerKind.MEANING));
        assertFalse(AiHarnessEpoch.isLegacy(0, LayerKind.SOUND));
    }

    @Test
    public void epochZeroKeepsTheExactMeaningConfigPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layer", "meaning");
        payload.put("provider", "openai");
        payload.put("providerVersion", "1");
        payload.put("endpoint", null);
        payload.put("modelName", "model");
        payload.put("targetLang", "en");
        payload.put("targetOrthography", null);
        payload.put("sourceLanguage", null);
        payload.put("pronunciationSystem", null);
        payload.put("soundMode", null);
        payload.put("soundBaselineMode", null);
        payload.put("instructions", "");
        payload.put("promptVersion", 1);
        payload.put("temperature", AiContract.TEMPERATURE);
        payload.put("contextMode", AiContract.CONTEXT_MODE);
        payload.put("iterationPromptVersion", null);
        payload.put("parentRecordKey", null);
        payload.put("parentOutputDigest", null);
        payload.put("revisionInstructions", null);

        assertEquals("epoch zero must not add a digest field",
                Digests.canonicalSha256(payload), AiIdentity.buildConfigId(meaningConfig()));
    }

    @Test
    public void epochZeroKeepsTheStoredSoundIdentity() {
        AiIdentity.Config config = new AiIdentity.Config();
        config.layer = LayerKind.SOUND;
        config.provider = "openai";
        config.providerVersion = "1";
        config.endpoint = null;
        config.modelName = "m";
        config.targetLang = "Latin";
        config.targetOrthography = "Latin";
        config.sourceLanguage = "ja";
        config.pronunciationSystem = "japanese";
        config.soundMode = "whole_line_v1";
        config.instructions = "Keep\nnames";
        config.promptVersion = 5;

        String implicitLegacy = AiIdentity.buildConfigId(config);
        config.harnessEpoch = AiHarnessEpoch.LEGACY;
        assertEquals("the stored identity must not move when the epoch is named",
                implicitLegacy, AiIdentity.buildConfigId(config));
        config.harnessEpoch = AiHarnessEpoch.MEANING;
        assertNotEquals("a positive epoch is a different question",
                implicitLegacy, AiIdentity.buildConfigId(config));
    }

    @Test
    public void theSoundRunConfigStaysAtLegacyZero() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.SOUND, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "Latin", AiContract.SOUND_PROMPT_VERSION, false);
        AiRunConfig config = new AiRunConfig(LayerKind.SOUND, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", "ja", null);
        assertEquals(AiHarnessEpoch.LEGACY, config.harnessEpoch);
        AiPaidRecord record = AiPaidRecord.begin(config, 1L);
        assertEquals(AiHarnessEpoch.LEGACY, record.harnessEpoch);
        assertFalse(AiPaidRecordCodec.encode(record).contains("harnessEpoch"));
    }

    @Test
    public void aPositiveEpochChangesTheMeaningConfigId() {
        String epochZero = AiIdentity.buildConfigId(meaningConfig());
        AiIdentity.Config next = meaningConfig();
        next.harnessEpoch = AiHarnessEpoch.MEANING;
        assertNotEquals(epochZero, AiIdentity.buildConfigId(next));
    }

    @Test
    public void artifactProvenanceCarriesTheEpochOnlyWhenItIsPositive() {
        assertEquals(AiIdentity.promptContractId(LayerKind.MEANING, "cfg-a"),
                AiIdentity.artifactContractId(LayerKind.MEANING, "cfg-a", AiHarnessEpoch.LEGACY));
        assertEquals(AiIdentity.promptContractId(LayerKind.MEANING, "cfg-a") + "|epoch=1",
                AiIdentity.artifactContractId(LayerKind.MEANING, "cfg-a", AiHarnessEpoch.MEANING));
        assertEquals(AiHarnessEpoch.LEGACY, AiHarnessEpoch.ofContractId("ai|schema=1|cfg=x"));
        assertEquals(AiHarnessEpoch.MEANING,
                AiHarnessEpoch.ofContractId("ai|schema=1|cfg=x|epoch=1"));
        assertEquals(AiHarnessEpoch.LEGACY, AiHarnessEpoch.ofContractId(null));
    }

    @Test
    public void onlyAnAiArtifactReportsAnEpoch() {
        MeaningArtifact ai = new MeaningArtifact("base", "cfg-a",
                new LayerProvenance(LayerAuthority.AI, "openai",
                        AiIdentity.artifactContractId(LayerKind.MEANING, "cfg-a",
                                AiHarnessEpoch.MEANING), "model", 1L),
                Collections.singletonList(new MeaningEntry("r0", "text", "en")), false);
        assertEquals(AiHarnessEpoch.MEANING, AiHarnessEpoch.ofArtifact(ai));

        MeaningArtifact legacy = new MeaningArtifact("base", "cfg-a",
                new LayerProvenance(LayerAuthority.AI, "openai",
                        AiIdentity.promptContractId(LayerKind.MEANING, "cfg-a"), "model", 1L),
                Collections.singletonList(new MeaningEntry("r0", "text", "en")), false);
        assertEquals(AiHarnessEpoch.LEGACY, AiHarnessEpoch.ofArtifact(legacy));

        MeaningArtifact machine = new MeaningArtifact("base", "cfg-a",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "cfg-a", 1L),
                Collections.singletonList(new MeaningEntry("r0", "text", "en")), false);
        assertEquals(-1, AiHarnessEpoch.ofArtifact(machine));
        assertEquals(-1, AiHarnessEpoch.ofArtifact((DerivedLayerArtifact) null));
    }

    // --- payload ------------------------------------------------------------

    private static AiPaidRecord record(int epoch) {
        return new AiPaidRecord(LayerKind.MEANING, "digest-1", "config-1", "openai",
                "model", "en", null, null, AiContract.MEANING_SCHEMA,
                AiContract.CHUNK_PLAN_VERSION, epoch);
    }

    @Test
    public void anEpochZeroPayloadOmitsTheFieldAndDecodesToLegacy() {
        String payload = AiPaidRecordCodec.encode(record(AiHarnessEpoch.LEGACY));
        assertFalse("an existing payload must keep its exact bytes",
                payload.contains("harnessEpoch"));
        AiPaidRecord decoded = AiPaidRecordCodec.decode(payload);
        assertEquals(AiHarnessEpoch.LEGACY, decoded.harnessEpoch);
    }

    @Test
    public void aPositiveEpochSurvivesTheRoundTrip() {
        String payload = AiPaidRecordCodec.encode(record(AiHarnessEpoch.MEANING));
        assertTrue(payload.contains("\"harnessEpoch\":1"));
        AiPaidRecord decoded = AiPaidRecordCodec.decode(payload);
        assertEquals(AiHarnessEpoch.MEANING, decoded.harnessEpoch);
    }

    @Test
    public void beginCopiesTheRunEpoch() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        AiRunConfig config = new AiRunConfig(LayerKind.MEANING, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null, AiHarnessEpoch.MEANING);
        assertEquals(AiHarnessEpoch.MEANING, AiPaidRecord.begin(config, 1L).harnessEpoch);

        AiRunConfig legacy = new AiRunConfig(LayerKind.MEANING, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        assertEquals(AiHarnessEpoch.LEGACY, AiPaidRecord.begin(legacy, 1L).harnessEpoch);
        assertEquals(AiHarnessEpoch.LEGACY, legacy.harnessEpoch);
    }

    @Test
    public void aStoredEntryReportsItsEpochAndUnreadableTextIsUnknown() {
        String payload = AiPaidRecordCodec.encode(record(AiHarnessEpoch.MEANING));
        String wrapper = "{\"payload\":" + quote(payload) + "}";
        assertEquals(AiHarnessEpoch.MEANING, AiHarnessEpoch.ofStoredPayload(wrapper));
        assertEquals(-1, AiHarnessEpoch.ofStoredPayload("not json"));
        assertEquals(-1, AiHarnessEpoch.ofStoredPayload(null));
    }

    @Test
    public void projectionThroughTheCodecPreservesTheEpoch() {
        AiPaidRecord source = record(AiHarnessEpoch.MEANING);
        source.status = AiPaidRecord.Status.COMPLETE;
        AiReplayIdentity replay = AiReplayIdentity.of(
                Collections.singletonList(AiLine.of("r0#aaaa", "hello", null, false)), false);
        source.replay = replay;
        source.putItem("r0#aaaa", "bonjour");
        AiChunkRecord chunk = new AiChunkRecord(replay.rowIds, "");
        chunk.status = AiChunkRecord.Status.COMPLETE;
        source.putChunk("C0", chunk);

        AiPaidRecord projected = replay.project(source);
        assertEquals(AiHarnessEpoch.MEANING, projected.harnessEpoch);
        assertEquals("bonjour", projected.item("r0#aaaa"));
    }

    private static String quote(String value) {
        return new com.google.gson.JsonPrimitive(value).toString();
    }
}
