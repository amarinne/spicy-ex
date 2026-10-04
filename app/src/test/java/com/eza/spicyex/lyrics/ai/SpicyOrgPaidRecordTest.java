package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.session.LayerKind;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgPaidRecordTest {
    private static final String SOURCE = "Original org lyric input";

    private static AiRunConfig config() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        return new AiRunConfig(LayerKind.MEANING, "digest-1", "config-1", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
    }

    private static AiLayerRunner.Args args(AiRecordStore store, FakeAiProvider provider) {
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config();
        args.store = store;
        args.provider = provider;
        args.rows = Collections.singletonList(AiLine.of("r0", SOURCE, null, false));
        args.wait = (millis, signal) -> { };
        return args;
    }

    private static LyricsDocument document(String origin, String provider) {
        LyricsDocument doc = new LyricsDocument();
        doc.fetchSource = origin;
        doc.provider = provider;
        return doc;
    }

    @After public void restoreStore() { AiRecordStores.installFactoryForTest(null); }

    @Test public void orgPaidOutputRemainsDurableAndReusedWithoutRetainingRequestText() {
        for (String providerName : new String[]{"Spicy Lyrics", "Apple Music", "Spotify (Musixmatch)"}) {
            FakeAiRecordStore disk = new FakeAiRecordStore();
            AiRecordStores.installFactoryForTest(ignored -> disk);
            AiRecordStore org = AiRecordStores.forRun(null, document("spicy_org", providerName));
            FakeAiProvider provider = new FakeAiProvider((request, config, signal) ->
                    AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                            Collections.singletonList("Paid independent output")),
                            AiUsage.of(10, 20), AiFinishReason.STOP, 1));
            AiRunOutcome first = AiLayerRunner.run(args(org, provider));
            assertEquals(AiRunOutcome.Kind.COMPLETED, first.kind);
            assertTrue(first.durable);
            assertEquals("Paid independent output", disk.peek(config()).item("r0"));
            String stored = AiPaidRecordCodec.encode(disk.peek(config()));
            assertFalse(stored.contains(SOURCE));
            assertFalse(stored.contains("requestJson"));
            assertEquals(10, disk.peek(config()).inputTokens);
            assertEquals(20, disk.peek(config()).outputTokens);
            AiRunOutcome reused = AiLayerRunner.run(args(org, provider));
            assertEquals(AiRunOutcome.Kind.REUSED, reused.kind);
            assertTrue(reused.durable);
            assertEquals(1, provider.calls.size());
        }
    }

    @Test public void failedOrgChunkResumesFromFreshPlanWithoutStoredInput() {
        FakeAiRecordStore disk = new FakeAiRecordStore();
        AiRecordStores.installFactoryForTest(ignored -> disk);
        AiRecordStore org = AiRecordStores.forRun(null, document("spicy_org", "Apple Music"));
        FakeAiProvider failing = new FakeAiProvider(FakeAiProvider.failure(
                AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.NETWORK, 0)));
        AiRunOutcome failed = AiLayerRunner.run(args(org, failing));
        assertEquals(AiRunOutcome.Kind.FAILED, failed.kind);
        assertTrue(failed.durable);
        assertFalse(AiPaidRecordCodec.encode(disk.peek(config())).contains(SOURCE));
        int oldTokens = disk.peek(config()).inputTokens;
        FakeAiProvider recovered = new FakeAiProvider();
        AiRunOutcome resumed = AiLayerRunner.run(args(org, recovered));
        assertEquals(AiRunOutcome.Kind.COMPLETED, resumed.kind);
        assertTrue(resumed.durable);
        assertEquals(1, recovered.calls.size());
        assertTrue(recovered.calls.get(0).requestJson.contains(SOURCE));
        assertTrue(disk.peek(config()).inputTokens > oldTokens);
        assertFalse(AiPaidRecordCodec.encode(disk.peek(config())).contains("requestJson"));
    }

    @Test public void directProvidersKeepExistingDurableRequestAccounting() {
        FakeAiRecordStore disk = new FakeAiRecordStore();
        AiRecordStores.installFactoryForTest(ignored -> disk);
        AiRecordStore apple = AiRecordStores.forRun(null, document("apple_music", "Apple Music"));
        assertSame(disk, apple);
        AiRunOutcome result = AiLayerRunner.run(args(apple, new FakeAiProvider()));
        assertTrue(result.durable);
        assertTrue(AiPaidRecordCodec.encode(disk.peek(config())).contains(SOURCE));
        assertTrue(AiPaidRecordCodec.encode(disk.peek(config())).contains("requestJson"));
    }
}
