package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.Digests;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Identity is the part of this contract that costs money to get wrong: a digest that differs by a
 * key order or a normalization form pays twice for one answer, and one that collides serves the
 * wrong answer for free.
 */
public class AiCanonicalIdentityTest {

    // --- canonical serialization -------------------------------------------

    @Test
    public void canonicalFormSortsKeysNormalizesNfcAndPreservesArrayOrder() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", "e\u0301");
        value.put("a", Arrays.asList(2, null));
        assertEquals("{\"a\":[2,null],\"b\":\"\u00e9\"}", Digests.canonicalJson(value));
    }

    @Test
    public void insertionOrderAndNormalizationFormDoNotChangeTheDigest() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("b", "e\u0301");
        left.put("a", Arrays.asList(2, null));
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("a", Arrays.asList(2, null));
        right.put("b", "\u00e9");
        assertEquals(Digests.canonicalSha256(left), Digests.canonicalSha256(right));
        assertTrue(Digests.canonicalSha256(left).matches("^[0-9a-f]{64}$"));
    }

    @Test
    public void aValueThatCannotBeCanonicalizedIsRejectedRatherThanCoerced() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("when", new java.util.Date(0L));
        try {
            Digests.canonicalJson(value);
            fail("expected an unsupported canonical value to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("$.when"));
        }
    }

    @Test
    public void escapingMatchesTheWireForm() {
        StringBuilder out = new StringBuilder();
        Digests.appendJsonString(out, "a\"b\\c\nd\u0001e f\u00e9");
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001e f\u00e9\"", out.toString());
    }

    // --- config identity ----------------------------------------------------

    private static AiIdentity.Config baseConfig() {
        AiIdentity.Config config = new AiIdentity.Config();
        config.provider = "openai";
        config.providerVersion = "1";
        config.modelName = "model";
        config.targetLang = "en";
        config.promptVersion = 1;
        return config;
    }

    @Test
    public void providerEndpointAndNormalizedSteeringAreAllPartOfConfigIdentity() {
        AiIdentity.Config direct = baseConfig();
        direct.endpoint = "https://api.openai.com/v1";
        String directId = AiIdentity.buildConfigId(direct);

        AiIdentity.Config proxy = baseConfig();
        proxy.endpoint = "https://proxy.example.test/v1";
        assertNotEquals(directId, AiIdentity.buildConfigId(proxy));

        AiIdentity.Config gemini = baseConfig();
        gemini.provider = "gemini";
        gemini.endpoint = null;
        assertNotEquals(directId, AiIdentity.buildConfigId(gemini));

        AiIdentity.Config steered = baseConfig();
        steered.endpoint = "https://api.openai.com/v1";
        steered.instructions = "Preserve honorifics.";
        String steeredId = AiIdentity.buildConfigId(steered);
        assertNotEquals(directId, steeredId);

        AiIdentity.Config padded = baseConfig();
        padded.endpoint = "https://api.openai.com/v1";
        padded.instructions = "  Preserve honorifics.  ";
        assertEquals("padding is not a different request", steeredId,
                AiIdentity.buildConfigId(padded));

        AiIdentity.Config rewrapped = baseConfig();
        rewrapped.endpoint = "https://api.openai.com/v1";
        rewrapped.instructions = "Preserve\nhonorifics.";
        assertNotEquals(steeredId, AiIdentity.buildConfigId(rewrapped));
    }

    @Test
    public void steeringIdentityIsNormalizationFormIndependent() {
        AiIdentity.Config decomposed = baseConfig();
        decomposed.instructions = "e\u0301";
        AiIdentity.Config composed = baseConfig();
        composed.instructions = "\u00e9";
        assertEquals(AiIdentity.buildConfigId(decomposed), AiIdentity.buildConfigId(composed));
    }

    @Test
    public void theLayerIsPartOfConfigIdentity() {
        AiIdentity.Config meaning = baseConfig();
        meaning.endpoint = "https://api.openai.com/v1";
        AiIdentity.Config sound = baseConfig();
        sound.endpoint = "https://api.openai.com/v1";
        sound.layer = LayerKind.SOUND;
        sound.targetLang = "Latin";
        assertNotEquals(AiIdentity.buildConfigId(meaning), AiIdentity.buildConfigId(sound));
    }

    @Test
    public void googleRefinementPromptVersionHasItsOwnConfigIdentity() {
        AiIdentity.Config scratch = baseConfig();
        scratch.promptVersion = AiContract.PROMPT_VERSION;
        AiIdentity.Config refined = baseConfig();
        refined.promptVersion = AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION;

        assertNotEquals(AiIdentity.buildConfigId(scratch), AiIdentity.buildConfigId(refined));
    }

    @Test
    public void soundIdentityCoversSourceLanguageOrthographyModeAndSystem() {
        AiIdentity.Config base = baseConfig();
        base.layer = LayerKind.SOUND;
        base.endpoint = "https://proxy.example/v1";
        base.targetLang = "Latin";
        base.targetOrthography = "Latin";
        base.sourceLanguage = "ja";
        base.pronunciationSystem = "japanese";
        base.soundMode = "whole_line_v1";
        base.instructions = "Keep names";
        base.promptVersion = 2;
        String first = AiIdentity.buildConfigId(base);

        base.sourceLanguage = "ko";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
        base.sourceLanguage = "ja";

        base.targetOrthography = "Hangul";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
        base.targetOrthography = "Latin";

        base.soundMode = null;
        assertNotEquals(first, AiIdentity.buildConfigId(base));
        base.soundMode = "whole_line_v1";

        base.soundBaselineMode = "raw_source_v1";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
        base.soundBaselineMode = null;

        // Two systems can share one orthography, so the system has to be in the key on its own.
        base.pronunciationSystem = "mandarin-pinyin";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
    }

    @Test
    public void aRevisionIsIdentifiedByItsParentAndItsNote() {
        AiIdentity.Config base = baseConfig();
        base.promptVersion = 3;
        base.iterationPromptVersion = 1;
        base.parentRecordKey = "parent-a";
        base.parentOutputDigest = "digest-a";
        base.revisionInstructions = "Make it warmer.";
        String first = AiIdentity.buildConfigId(base);

        base.modelName = "model-2";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
        base.modelName = "model";

        base.parentRecordKey = "parent-b";
        base.parentOutputDigest = "digest-b";
        assertNotEquals(first, AiIdentity.buildConfigId(base));
    }

    // --- document identity --------------------------------------------------

    private static List<AiLine> rows() {
        List<AiLine> rows = new ArrayList<>();
        rows.add(AiLine.of("r0#aaaa", "歌", null, false));
        rows.add(AiLine.of("r1#bbbb", "♪", null, false));
        return rows;
    }

    @Test
    public void documentIdentityCoversClassDispositionVoiceAndUnchangedPolicy() {
        List<AiLine> base = rows();
        String digest = AiIdentity.buildDocDigest(base, null, false);

        List<AiLine> reDisposed = rows();
        AiLine structural = reDisposed.get(1);
        reDisposed.set(1, new AiLine(structural.id, structural.lineClass,
                AiSendDisposition.SKIPPED, structural.sourceText, null, false, null, null));
        assertNotEquals(digest, AiIdentity.buildDocDigest(reDisposed, null, false));

        List<AiLine> reVoiced = rows();
        AiLine sung = reVoiced.get(0);
        reVoiced.set(0, new AiLine(sung.id, sung.lineClass, sung.sendDisposition, sung.sourceText,
                AiVoiceHint.ALTERNATE, false, null, null));
        assertNotEquals(digest, AiIdentity.buildDocDigest(reVoiced, null, false));

        List<AiLine> reUnchanged = rows();
        reUnchanged.set(0, new AiLine(sung.id, sung.lineClass, sung.sendDisposition,
                sung.sourceText, null, true, null, null));
        assertNotEquals(digest, AiIdentity.buildDocDigest(reUnchanged, null, false));
    }

    @Test
    public void trackMetadataIsPartOfDocumentIdentityAndIsNormalizedFirst() {
        List<AiLine> base = rows();
        String bare = AiIdentity.buildDocDigest(base, null, false);
        AiLyricContext context =
                new AiLyricContext("Song", Collections.singletonList("Artist"), "Album");
        assertNotEquals(bare, AiIdentity.buildDocDigest(base, context, false));

        AiLyricContext untidy =
                new AiLyricContext(" e\u0301 ", Collections.singletonList(" Artist  Name "), null);
        AiLyricContext tidy =
                new AiLyricContext("\u00e9", Collections.singletonList("Artist Name"), null);
        assertEquals(AiIdentity.buildDocDigest(base, untidy, false),
                AiIdentity.buildDocDigest(base, tidy, false));
    }

    @Test
    public void soundIdentityIncludesTheBaselineItWasGivenAndMeaningDoesNot() {
        List<AiLine> first = Collections.singletonList(
                AiLine.withBaseline("r0#aaaa", "ฉัน", null, true, "chan", "google"));
        List<AiLine> second = Collections.singletonList(
                AiLine.withBaseline("r0#aaaa", "ฉัน", null, true, "chun", "google"));
        assertNotEquals(AiIdentity.buildDocDigest(first, null, true),
                AiIdentity.buildDocDigest(second, null, true));
        assertEquals(AiIdentity.buildDocDigest(first, null, false),
                AiIdentity.buildDocDigest(second, null, false));
    }

    // --- record key ---------------------------------------------------------

    @Test
    public void theRecordKeyIsTheExistingPaidIdentityCarryingTheLayerExplicitly() {
        PaidArtifactIdentity meaning = AiIdentity.recordIdentity(LayerKind.MEANING, "doc-a",
                "openai", "model", "cfg-a");
        PaidArtifactIdentity sound = AiIdentity.recordIdentity(LayerKind.SOUND, "doc-a",
                "openai", "model", "cfg-a");

        assertTrue(meaning.isComplete());
        assertTrue(meaning.storageKey().contains("|meaning|"));
        assertTrue(sound.storageKey().contains("|sound|"));
        assertNotEquals("the layer must discriminate, never the schema number by implication",
                meaning.storageKey(), sound.storageKey());
        assertTrue(meaning.promptContractId.contains("schema=" + AiContract.MEANING_SCHEMA));
        assertTrue(sound.promptContractId.contains("schema=" + AiContract.SOUND_SCHEMA));
        assertTrue(meaning.promptContractId.contains("plan=" + AiContract.CHUNK_PLAN_VERSION));
    }

    @Test
    public void adifferentConfigurationIsADifferentRecord() {
        PaidArtifactIdentity first = AiIdentity.recordIdentity(LayerKind.MEANING, "doc-a",
                "openai", "model", "cfg-a");
        PaidArtifactIdentity second = AiIdentity.recordIdentity(LayerKind.MEANING, "doc-a",
                "openai", "model", "cfg-b");
        assertNotEquals(first.storageKey(), second.storageKey());
    }

    @Test
    public void theRecordKeyCarriesNothingFromTheBuildAxis() {
        String contractId = AiIdentity.promptContractId(LayerKind.MEANING, "cfg-a");
        assertEquals("ai|schema=1|plan=" + AiContract.CHUNK_PLAN_VERSION + "|cfg=cfg-a", contractId);
    }
}
