package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.*;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.session.*;
import com.google.gson.*;
import java.util.*;
import org.junit.Test;

/** Independent row occurrence, billing, and storage-integrity assertions. */
public class AiReplayIdentityTest {
    private static AiRunConfig config(String digest) {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "en", AiContract.PROMPT_VERSION, false);
        return new AiRunConfig(LayerKind.MEANING, digest, "config", "fake", AiLyricContext.EMPTY,
                provider, "", null, null);
    }
    private static List<AiLine> rows(String prefix) {
        return Arrays.asList(AiLine.of(prefix + "0", "First line", null, false),
                AiLine.of(prefix + "1", "Repeated line", null, false),
                AiLine.of(prefix + "2", "Repeated line", null, false));
    }
    private static AiPaidRecord paid(List<AiLine> rows, boolean baseline) {
        AiPaidRecord record = AiPaidRecord.begin(config("original"), 1);
        record.status = AiPaidRecord.Status.COMPLETE;
        record.replay = AiReplayIdentity.of(rows, baseline);
        for (int i = 0; i < rows.size(); i++) record.putItem(rows.get(i).id, "answer " + i);
        AiChunkRecord chunk = new AiChunkRecord(record.replay.rowIds, "");
        chunk.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", chunk);
        record.inputTokens = 17; record.outputTokens = 23;
        return record;
    }
    @Test public void retimedRoutedSourcesReuseEachOccurrenceWithoutCallsOrPurchaseCopies() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiPaidRecord record = paid(rows("old"), false);
        assertTrue(store.commit(config("original"), record));
        int writes = store.commits;
        FakeAiProvider provider = new FakeAiProvider();
        Set<String> canonical = new HashSet<>();
        for (String route : new String[]{"apple_music", "apple_music", "spicy_lyrics", "spotify", "apple_music"}) {
            LyricsDocument document = new LyricsDocument();
            document.trackId = "track"; document.provider = "Apple Music";
            document.fetchSource = canonical.isEmpty() || canonical.size() == 4 ? "apple_music" : "spicy_org";
            document.spicyOrgSource = route;
            for (int i = 0; i < 3; i++) {
                LyricsLine line = new LyricsLine(); line.text = rows("x").get(i).sourceText;
                line.startMs = 1000L * i + canonical.size() * 10; line.endMs = line.startMs + 900;
                document.lines.add(line);
            }
            CanonicalBase base = CanonicalBase.fromDocument("spotify:track:track", document);
            canonical.add(base.digest);
            List<AiLine> current = rows("new" + canonical.size());
            AiLayerRunner.Args args = new AiLayerRunner.Args();
            args.config = config("changed-" + base.digest); args.store = store; args.provider = provider;
            args.rows = current; args.replay = AiReplayIdentity.of(current, false);
            args.binding = new AiSourceBinding(base, document);
            AiRunOutcome replayed = AiLayerRunner.run(args);
            assertEquals(AiRunOutcome.Kind.REUSED, replayed.kind);
            for (int i = 0; i < 3; i++) assertEquals("answer " + i, replayed.record.item(current.get(i).id));
            assertEquals(17, replayed.record.inputTokens); assertEquals(23, replayed.record.outputTokens);
            if ("spicy_org".equals(document.fetchSource)) {
                assertEquals("spicy_org", args.binding.route); assertEquals(route, args.binding.upstream);
            }
        }
        assertEquals(5, canonical.size()); assertEquals(1, store.keys().size());
        assertEquals(writes, store.commits); assertEquals(0, store.reservations); assertTrue(provider.calls.isEmpty());
    }
    @Test public void changedTextVoiceOrderCoverageAndFlowCannotReplay() {
        AiPaidRecord saved = paid(rows("old"), false);
        List<AiLine> changed = new ArrayList<>(rows("new"));
        changed.set(0, AiLine.of("new0", "Different line", null, false));
        assertNull(AiReplayIdentity.of(changed, false).project(saved));
        changed.set(0, AiLine.of("new0", "First line", AiVoiceHint.BACKGROUND, false));
        assertNull(AiReplayIdentity.of(changed, false).project(saved));
        changed = new ArrayList<>(rows("new")); Collections.swap(changed, 0, 1);
        assertNull(AiReplayIdentity.of(changed, false).project(saved));
        assertNull(AiReplayIdentity.of(rows("new").subList(0, 2), false).project(saved));
        assertNull(AiReplayIdentity.of(rows("new"), true).project(saved));
        changed = new ArrayList<>(rows("new")); changed.set(0, AiLine.of("new0", "First\u200b line", null, false));
        assertNull(AiReplayIdentity.of(changed, false).project(saved));
    }
    @Test public void anInvalidLiveRecordDeclinesProjectionWithoutThrowing() {
        AiPaidRecord saved = paid(rows("old"), false);
        saved.putChunk("C1", new AiChunkRecord(Collections.singletonList("old0"), ""));
        assertNull(AiReplayIdentity.of(rows("new"), false).project(saved));
    }
    @Test public void changedGoogleDraftKeepsSavedOutputWithoutRetainingInput() {
        AiPaidRecord saved = paid(rows("old"), true);
        List<AiLine> current = Arrays.asList(
                AiLine.withBaseline("new0", "First line", null, false, "different draft", "google"),
                AiLine.of("new1", "Repeated line", null, false), AiLine.of("new2", "Repeated line", null, false));
        AiPaidRecord decoded = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(saved, false));
        assertNotNull(AiReplayIdentity.of(current, true).project(decoded));
        assertFalse(AiPaidRecordCodec.encode(saved, false).contains("First line"));
    }
    @Test public void legacyRequestsRecoverOnlyOrderedCompleteContextFreeInput() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = new AiLayerRunner.Args(); args.config = config("original");
        args.rows = rows("old"); args.store = store; args.provider = new FakeAiProvider();
        AiPaidRecord legacy = AiLayerRunner.run(args).record;
        assertNotNull(AiReplayIdentity.fromRecord(legacy));
        assertNotNull(AiReplayIdentity.of(rows("new"), false).project(legacy));
        legacy.putItem("foreign", "uncovered"); assertNull(AiReplayIdentity.fromRecord(legacy));
        legacy = store.peek(config("original"));
        AiChunkRecord chunk = legacy.chunks().values().iterator().next();
        JsonObject request = JsonParser.parseString(chunk.requestJson).getAsJsonObject();
        request.getAsJsonObject("context").addProperty("title", "unverified context");
        AiChunkRecord altered = new AiChunkRecord(chunk.ids, request.toString());
        altered.status = chunk.status;
        legacy.putChunk(legacy.chunks().keySet().iterator().next(), altered);
        assertNull(AiReplayIdentity.fromRecord(legacy));
    }
    @Test public void wrapperAndStoredKeyMustAgreeWithThePaidPayload() {
        AiPaidRecord record = paid(rows("old"), false);
        PaidArtifactIdentity identity = config("original").recordIdentity();
        String key = AIPaidArtifactCache.entryKey(identity);
        JsonObject wrapper = new JsonObject(); wrapper.addProperty("schema", 1);
        wrapper.addProperty("layer", "MEANING"); wrapper.addProperty("canonicalDigest", record.docDigest);
        wrapper.addProperty("providerId", record.providerId); wrapper.addProperty("modelId", record.modelName);
        wrapper.addProperty("promptContractId", identity.promptContractId);
        wrapper.addProperty("payload", AiPaidRecordCodec.encode(record));
        assertNotNull(AiReplayIdentity.index(key, wrapper.toString()));
        for (String field : new String[]{"schema", "layer", "canonicalDigest", "providerId", "modelId", "promptContractId"}) {
            JsonObject corrupt = wrapper.deepCopy(); corrupt.addProperty(field, "foreign");
            assertNull(field, AiReplayIdentity.index(key, corrupt.toString()));
        }
        assertNull(AiReplayIdentity.index("wrong-key", wrapper.toString()));
        assertNull(AiReplayIdentity.index(key, "malformed"));
        record.status = AiPaidRecord.Status.PARTIAL;
        wrapper.addProperty("payload", AiPaidRecordCodec.encode(record));
        assertNull(AiReplayIdentity.index(key, wrapper.toString()));
    }
    @Test public void automaticDeliveryUnknownDoesNotDispatchOrChangeStoredAccounting() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = new AiLayerRunner.Args(); args.config = config("original");
        args.rows = rows("old"); args.store = store;
        args.provider = new FakeAiProvider(FakeAiProvider.failure(
                AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.NETWORK, 0)));
        assertEquals(AiRunOutcome.Kind.FAILED, AiLayerRunner.run(args).kind);
        String saved = AiPaidRecordCodec.encode(store.peek(args.config));
        int writes = store.commits, reservations = store.reservations;
        FakeAiProvider forbidden = new FakeAiProvider(); args.provider = forbidden;
        AiRunOutcome automatic = AiLayerRunner.run(args);
        assertEquals("delivery_unknown", automatic.failureToken); assertTrue(forbidden.calls.isEmpty());
        assertEquals(writes, store.commits); assertEquals(reservations, store.reservations);
        assertEquals(saved, AiPaidRecordCodec.encode(store.peek(args.config)));
    }
}
