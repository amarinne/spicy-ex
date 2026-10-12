package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.Digests;
import com.google.gson.*;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import static org.junit.Assert.*;

/** Frozen plan-7 identities and stored synthetic text protect already purchased output. */
public class AiCandidateCompatibilityTest {
    private static AiLayerRunner.Args args(FakeAiRecordStore store) {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", 7, false);
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        args.rows = Collections.singletonList(AiLine.of("r0", "hola / mundo", null, false));
        args.store = store;
        args.provider = new FakeAiProvider();
        return args;
    }

    private static AiPaidRecord legacyComplete(String text) {
        // Same fields and absent planning/fallback fields as current main's stripped paid payload.
        String payload = "{\"v\":1,\"layer\":\"MEANING\",\"docDigest\":\"doc\","
                + "\"configId\":\"config\",\"providerId\":\"fake\",\"modelName\":\""
                + FakeAiProvider.DEFAULT_MODEL.name + "\",\"targetLang\":\"en\","
                + "\"schemaVersion\":1,\"chunkPlanVersion\":7,\"status\":\"COMPLETE\","
                + "\"inputTokens\":7,\"outputTokens\":3,\"items\":{\"r0\":\"" + text + "\"},"
                + "\"chunks\":{\"C0\":{\"ids\":[\"r0~0\",\"r0~1\"],\"status\":\"COMPLETE\","
                + "\"attempts\":1,\"repairs\":0,\"inputTokens\":7,\"outputTokens\":3}}}";
        AiPaidRecord record = AiPaidRecordCodec.decode(payload);
        assertNotNull(record);
        return record;
    }

    @Test public void originalContractVersionsAndPaidNamespaceRemainUnchanged() {
        assertEquals(7, AiContract.PROMPT_VERSION);
        assertEquals(8, AiContract.SOUND_PROMPT_VERSION);
        assertEquals(8, AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION);
        assertEquals(3, AiContract.ITERATION_PROMPT_VERSION);
        assertEquals(7, AiContract.CHUNK_PLAN_VERSION);
        assertEquals("ai|schema=1|plan=7|cfg=config",
                AiIdentity.promptContractId(LayerKind.MEANING, "config"));
    }

    @Test public void unchangedAtomicPlansKeepCurrentMainRequestBytesAndChunkIds() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(
                "/ai-contract-current-main-plans.json"), StandardCharsets.UTF_8)) {
            JsonObject golden = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals("37454a682fe76c9a91dfba56937a3a79320990c2", golden.get("revision").getAsString());
            for (JsonElement entry : golden.getAsJsonArray("fixtures")) {
                JsonObject fixture = entry.getAsJsonObject();
                List<AiLine> rows = new ArrayList<>();
                for (int i = 0; i < 129; i++) {
                    rows.add(AiLine.of("r" + i, String.join(" / ", Collections.nCopies(
                            fixture.get("segments").getAsInt(), "hola")), null, false));
                }
                AiChunkPlanner.Input input = new AiChunkPlanner.Input();
                input.rows = rows;
                input.target = "en";
                input.model = FakeAiProvider.DEFAULT_MODEL;
                AiChunkPlan plan = AiChunkPlanner.plan(input);
                JsonArray expected = fixture.getAsJsonArray("chunks");
                assertEquals(fixture.get("version").getAsInt(), plan.version);
                assertEquals(expected.size(), plan.chunks.size());
                for (int i = 0; i < expected.size(); i++) {
                    JsonObject chunk = expected.get(i).getAsJsonObject();
                    assertEquals(chunk.get("id").getAsString(), plan.chunks.get(i).id);
                    assertEquals(chunk.get("itemCount").getAsInt(), plan.chunks.get(i).items.size());
                    assertEquals(chunk.get("requestDigest").getAsString(), Digests.sha256(plan.chunks.get(i).requestJson));
                }
            }
        }
    }

    @Test public void originalCompletePurchaseReusesWithoutCallsOrWrites() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(store);
        store.commit(args.config, legacyComplete("hello / world"));
        String before = AiPaidRecordCodec.encode(store.peek(args.config));
        args.allowProviderRequest = false;
        AiRunOutcome outcome = AiLayerRunner.run(args);
        assertEquals(AiRunOutcome.Kind.REUSED, outcome.kind);
        assertEquals("hello / world", outcome.record.item("r0"));
        assertEquals(7, outcome.record.inputTokens);
        assertEquals(3, outcome.record.outputTokens);
        assertEquals(1, store.commits);
        assertEquals(0, store.reservations);
        assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
        assertEquals(before, AiPaidRecordCodec.encode(store.peek(args.config)));
    }

    @Test public void storedHalfRowCannotPublishOrTriggerReplacementPaidWork() {
        for (boolean allow : new boolean[]{false, true}) {
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args args = args(store);
            args.allowProviderRequest = allow;
            store.commit(args.config, legacyComplete("hello"));
            AiRunOutcome outcome = AiLayerRunner.run(args);
            assertFalse(outcome.hasOutput());
            assertEquals(1, store.commits);
            assertEquals(0, store.reservations);
            assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
        }
    }

    @Test public void productionVoicesCanStillFalseRejectGenuineTokenTranslations() {
        for (AiVoiceHint voice : new AiVoiceHint[]{AiVoiceHint.PRIMARY, AiVoiceHint.ALTERNATE}) {
            AiRequestItem input = new AiRequestItem("r0", AiLineClass.ORDINARY, voice,
                    voice == AiVoiceHint.PRIMARY ? "主要" : "交互", null);
            AiResponseValidator.Partition result = AiResponseValidator.partition(
                    AiResponseReader.readItems("{\"items\":[{\"id\":\"r0\",\"t\":\"" + voice.token + "\"}]}"),
                    Collections.singletonList(input), LayerKind.MEANING, "en");
            assertTrue(result.accepted.isEmpty());
            assertEquals("voice_hint_echo", result.failed.get(0).token);
        }
    }
}
