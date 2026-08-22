package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.Arrays;

/**
 * This payload is what a paid answer becomes between one launch and the next.
 *
 * <p>Two failure modes matter and they pull in opposite directions. Losing a field silently makes a
 * resume resend a chunk that was already bought. Accepting a payload we do not fully understand
 * risks treating a foreign or truncated record as a finished answer, which would show the owner
 * half a document and never buy the rest. So the round trip has to be exact, and anything else has
 * to decode to null.
 */
public class AiPaidRecordCodecTest {

    private static AiPaidRecord populated() {
        AiPaidRecord record = new AiPaidRecord(LayerKind.SOUND, "digest-1", "config-1", "gemini",
                "some-model", "Latin", "ja", "mandarin-pinyin",
                AiContract.SOUND_SCHEMA, AiContract.CHUNK_PLAN_VERSION);
        record.status = AiPaidRecord.Status.PARTIAL;
        record.inputTokens = 120;
        record.outputTokens = 340;
        record.usageEstimated = true;
        record.createdAtMs = 1_000L;
        record.lastAccessedAtMs = 2_000L;
        record.putItem("r0#aa", "kimi");
        record.putItem("r1#bb", "うた / uta");

        AiChunkRecord done = new AiChunkRecord(Arrays.asList("r0#aa", "r1#bb"), "{\"items\":[]}");
        done.status = AiChunkRecord.Status.COMPLETE;
        done.attempts = 2;
        done.repairs = 1;
        done.inputTokens = 120;
        done.outputTokens = 340;
        done.usageEstimated = true;
        record.putChunk("C0", done);

        AiChunkRecord failed = new AiChunkRecord(Arrays.asList("r2#cc"), "{\"items\":[2]}");
        failed.status = AiChunkRecord.Status.FAILED;
        failed.attempts = 2;
        failed.failure = new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                "id_set_mismatch:missing:r2#cc");
        record.putChunk("C1", failed);
        return record;
    }

    @Test
    public void aRecordSurvivesTheRoundTripFieldForField() {
        AiPaidRecord source = populated();
        AiPaidRecord back = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(source));

        assertNotNull(back);
        assertEquals(LayerKind.SOUND, back.layer);
        assertEquals("digest-1", back.docDigest);
        assertEquals("config-1", back.configId);
        assertEquals("gemini", back.providerId);
        assertEquals("some-model", back.modelName);
        assertEquals("Latin", back.targetLang);
        assertEquals("ja", back.sourceLanguage);
        assertEquals("mandarin-pinyin", back.pronunciationSystem);
        assertEquals(AiContract.SOUND_SCHEMA, back.schemaVersion);
        assertEquals(AiContract.CHUNK_PLAN_VERSION, back.chunkPlanVersion);
        assertEquals(AiPaidRecord.Status.PARTIAL, back.status);
        assertEquals(120, back.inputTokens);
        assertEquals(340, back.outputTokens);
        assertTrue(back.usageEstimated);
        assertEquals(1_000L, back.createdAtMs);
        assertEquals(2_000L, back.lastAccessedAtMs);
    }

    @Test
    public void acceptedTextSurvivesExactlyIncludingItsSegments() {
        AiPaidRecord back = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(populated()));
        assertEquals(2, back.items().size());
        assertEquals("kimi", back.item("r0#aa"));
        assertEquals("うた / uta", back.item("r1#bb"));
    }

    @Test
    public void chunkStateSurvivesBecauseAResumeIsBuiltOnIt() {
        AiPaidRecord back = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(populated()));

        AiChunkRecord done = back.chunk("C0");
        assertTrue(done.isComplete());
        assertEquals(Arrays.asList("r0#aa", "r1#bb"), done.ids);
        assertEquals("the resend must replay the same bytes", "{\"items\":[]}", done.requestJson);
        assertEquals(2, done.attempts);
        assertEquals(1, done.repairs);
        assertEquals(120, done.inputTokens);
        assertEquals(340, done.outputTokens);
        assertTrue(done.usageEstimated);

        AiChunkRecord failed = back.chunk("C1");
        assertEquals(AiChunkRecord.Status.FAILED, failed.status);
        assertEquals(AiFailureReason.PROTOCOL_INVALID, failed.failure.reason);
        assertEquals("id_set_mismatch:missing:r2#cc", failed.failure.detail);
    }

    @Test
    public void reopeningAfterAReloadTouchesOnlyTheFailedChunk() {
        AiPaidRecord back = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(populated()));
        back.reopenFailedChunks();

        assertTrue("a completed chunk is paid for and stays complete", back.chunk("C0").isComplete());
        assertEquals(AiChunkRecord.Status.PENDING, back.chunk("C1").status);
        assertEquals(0, back.chunk("C1").attempts);
        assertNull(back.chunk("C1").failure);
    }

    @Test
    public void aPayloadFromAnotherContractVersionIsNotTrusted() {
        String encoded = AiPaidRecordCodec.encode(populated());
        assertNull(AiPaidRecordCodec.decode(encoded.replace("\"v\":1", "\"v\":2")));
    }

    @Test
    public void anUnknownEnumIsARecordThisBuildCannotRead() {
        String encoded = AiPaidRecordCodec.encode(populated());
        assertNull(AiPaidRecordCodec.decode(encoded.replace("\"status\":\"PARTIAL\"",
                "\"status\":\"HALF_DONE\"")));
        assertNull(AiPaidRecordCodec.decode(encoded.replace("\"reason\":\"PROTOCOL_INVALID\"",
                "\"reason\":\"BUDGET_EXCEEDED\"")));
    }

    @Test
    public void garbageDecodesToNothingRatherThanThrowing() {
        assertNull(AiPaidRecordCodec.decode(null));
        assertNull(AiPaidRecordCodec.decode(""));
        assertNull(AiPaidRecordCodec.decode("{"));
        assertNull(AiPaidRecordCodec.decode("[]"));
        assertNull(AiPaidRecordCodec.decode("\"just a string\""));
        assertNull(AiPaidRecordCodec.decode("{\"v\":1,\"layer\":\"NONSENSE\"}"));
    }

    @Test
    public void aRecordKeyedForOneQuestionIsRefusedForAnother() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        AiRunConfig meaning = new AiRunConfig(LayerKind.MEANING, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);

        // Same document, but the stored answer is a Sound reading under a different configuration.
        assertFalse(meaning.matches(populated()));
    }
}
