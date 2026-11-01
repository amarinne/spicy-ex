package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * Older-epoch Meaning output stays on screen and never buys by itself.
 *
 * <p>The epoch boundary is a spending boundary. These cases pin the three outcomes that matter:
 * automatic reuse of the old answer with zero provider calls, an explicit request that buys the
 * current epoch while keeping the old record, and a failed explicit request that keeps the old
 * answer visible.
 */
public class AiLegacyDisplayTest {

    private static final String TARGET = "en";
    private static final AiCredentialStore.Cipher CIPHER = new AiCredentialStore.Cipher() {
        @Override public String encrypt(String plaintext) { return plaintext; }
        @Override public String decrypt(String ciphertext) { return ciphertext; }
        @Override public void clear() { }
    };

    private FakeAndroidContext context;
    private SettingsStore settingsStore;
    private AiSettings settings;
    private FakeAiRecordStore store;
    private LyricsDocument document;
    private CanonicalBase base;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private boolean failProvider;
    private OkHttpClient originalClient;

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        settingsStore = new SettingsStore(context);
        settingsStore.put(Settings.AI_ENABLED, true);
        settingsStore.put(Settings.TRANSLATION_ENABLED, true);
        settingsStore.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        settingsStore.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        settingsStore.put(Settings.AI_TRANSLATION_PIPELINE, AiSettings.TRANSLATION_PIPELINE_AI_ONLY);
        settingsStore.put(Settings.AI_TRANSLATION_MODE, "On demand");
        AiCredentialStore credentials = new AiCredentialStore(context, CIPHER);
        settings = new AiSettings(settingsStore, credentials);
        assertTrue(credentials.save(settings.credentialScope(), "sk-test-key"));

        store = new FakeAiRecordStore();
        AiRecordStores.installFactoryForTest(ignored -> store);

        document = new LyricsDocument();
        document.trackId = "spotify:track:epoch";
        document.language = "ko";
        document.translationPending = true;
        for (String text : Arrays.asList("안녕하세요", "반갑습니다")) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            document.lines.add(line);
        }
        base = CanonicalBase.fromDocument("spotify:track:epoch", document);

        originalClient = AiHttp.client();
        OkHttpClient intercepted = originalClient.newBuilder()
                .addInterceptor(chain -> answer(chain))
                .build();
        HTTP_CLIENT.set(null, intercepted);
    }

    @After public void tearDown() throws Exception {
        AiRecordStores.installFactoryForTest(null);
        HTTP_CLIENT.set(null, originalClient);
        AiRequestLiveState.clearForTest();
    }

    // --- automatic reuse ----------------------------------------------------

    @Test public void aLegacyOnlySongIsReusedWhenTheRunMayNotCallTheProvider() {
        seed(legacyConfig());
        String legacyPayload = AiPaidRecordCodec.encode(store.peek(legacyConfig()));

        AiMeaningRun.Result result = run(false, false);

        assertEquals(AiRunOutcome.Kind.REUSED, result.outcome.kind);
        assertTrue(result.hasArtifact());
        assertTrue("the artifact must say it is older-epoch output", result.legacy);
        assertEquals(AiHarnessEpoch.LEGACY, result.epoch);
        assertEquals("no provider call may answer a legacy-only song", 0, providerCalls.get());
        assertEquals("no current-epoch record may be created", 1, store.keys().size());
        assertEquals(legacyPayload, AiPaidRecordCodec.encode(store.peek(legacyConfig())));
    }

    @Test public void aRunThatMayNotBuyDoesNotResumeAPartialCurrentEpochRecord() {
        seed(legacyConfig());
        AiPaidRecord partial = AiPaidRecord.begin(currentConfig(), 3L);
        partial.status = AiPaidRecord.Status.PARTIAL;
        partial.replay = replay();
        partial.putItem(replay().rowIds.get(0), "AI 안녕하세요");
        assertTrue(store.commit(currentConfig(), partial));
        String currentBefore = AiPaidRecordCodec.encode(store.peek(currentConfig()));

        AiMeaningRun.Result result = run(false, false);

        assertEquals(AiRunOutcome.Kind.REUSED, result.outcome.kind);
        assertTrue(result.legacy);
        assertEquals("the partial current-epoch record must not be resumed", 0, providerCalls.get());
        assertEquals(currentBefore, AiPaidRecordCodec.encode(store.peek(currentConfig())));
    }

    @Test public void anExplicitRequestThatCannotBuyStillKeepsTheLegacyOutput() {
        seed(legacyConfig());
        AiCredentialStore empty = new AiCredentialStore(new FakeAndroidContext(), CIPHER);
        AiSettings blocked = new AiSettings(settingsStore, empty);
        assertFalse(blocked.canRequest());

        AiMeaningRun.Result result = AiMeaningRun.run(context, blocked, base, document, null,
                false, TARGET, true, new AiSignal(), null, true);

        assertEquals(AiRunOutcome.Kind.REUSED, result.outcome.kind);
        assertTrue(result.legacy);
        assertEquals(0, providerCalls.get());
    }

    // --- purchase -----------------------------------------------------------

    @Test public void anAutomaticRunThatMayCallTheProviderReplacesTheLegacyOutput() {
        seed(legacyConfig());
        String legacyPayload = AiPaidRecordCodec.encode(store.peek(legacyConfig()));

        AiMeaningRun.Result result = run(true, false);

        assertEquals(AiRunOutcome.Kind.COMPLETED, result.outcome.kind);
        assertFalse(result.legacy);
        assertEquals(AiHarnessEpoch.MEANING, result.epoch);
        assertTrue(providerCalls.get() >= 1);
        assertEquals("the older record stays stored", 2, store.keys().size());
        assertEquals(legacyPayload, AiPaidRecordCodec.encode(store.peek(legacyConfig())));
    }

    @Test public void anExplicitRequestBuysTheCurrentEpochAndKeepsTheLegacyRecord() {
        seed(legacyConfig());
        String legacyPayload = AiPaidRecordCodec.encode(store.peek(legacyConfig()));

        AiMeaningRun.Result result = run(true, true);

        assertEquals(AiRunOutcome.Kind.COMPLETED, result.outcome.kind);
        assertTrue(result.hasArtifact());
        assertFalse(result.legacy);
        assertEquals(AiHarnessEpoch.MEANING, result.epoch);
        assertTrue(providerCalls.get() >= 1);
        assertEquals("both epochs stay stored", 2, store.keys().size());
        AiPaidRecord bought = store.peek(currentConfig());
        assertNotNull(bought);
        assertEquals(AiHarnessEpoch.MEANING, bought.harnessEpoch);
        assertTrue(bought.isCompleteForRows(AiRows.forMeaning(base, document, null, false)));
        assertEquals("the old record is never overwritten",
                legacyPayload, AiPaidRecordCodec.encode(store.peek(legacyConfig())));
    }

    @Test public void aFailedExplicitPurchaseStillReturnsTheLegacyArtifact() {
        seed(legacyConfig());
        failProvider = true;
        String legacyPayload = AiPaidRecordCodec.encode(store.peek(legacyConfig()));

        AiMeaningRun.Result result = run(true, true);

        assertEquals(AiRunOutcome.Kind.FAILED, result.outcome.kind);
        assertTrue("old output stays displayable after a failed retry", result.hasArtifact());
        assertTrue(result.legacy);
        assertEquals(AiHarnessEpoch.LEGACY, result.epoch);
        assertTrue(providerCalls.get() >= 1);
        AiPaidRecord failed = store.peek(currentConfig());
        if (failed != null) assertEquals(AiHarnessEpoch.MEANING, failed.harnessEpoch);
        assertEquals(legacyPayload, AiPaidRecordCodec.encode(store.peek(legacyConfig())));
    }

    // --- ordering and unchanged behaviour ------------------------------------

    @Test public void aCompleteCurrentEpochRecordBeatsTheLegacyRecord() {
        seed(legacyConfig());
        seedCurrentEpoch("CURRENT ");

        AiMeaningRun.Result result = run(true, false);

        assertEquals(AiRunOutcome.Kind.REUSED, result.outcome.kind);
        assertFalse("current-epoch output is not legacy", result.legacy);
        assertEquals(AiHarnessEpoch.MEANING, result.epoch);
        assertEquals(0, providerCalls.get());
        String firstRow = replay().rowIds.get(0);
        assertTrue(result.artifact.meaning(firstRow).text.startsWith("CURRENT "));
    }

    @Test public void withoutLegacyOutputTheRunStillBuysTheCurrentEpoch() {
        AiMeaningRun.Result idle = run(false, false);
        assertEquals(AiRunOutcome.Kind.NOTHING_TO_DO, idle.outcome.kind);
        assertEquals(0, providerCalls.get());

        AiMeaningRun.Result result = run(true, false);

        assertEquals(AiRunOutcome.Kind.COMPLETED, result.outcome.kind);
        assertFalse(result.legacy);
        assertEquals(AiHarnessEpoch.MEANING, result.epoch);
        assertEquals(1, providerCalls.get());
        assertEquals(1, store.keys().size());
        assertEquals(AiHarnessEpoch.MEANING, store.peek(currentConfig()).harnessEpoch);
    }

    // --- stateless legacy read ----------------------------------------------

    @Test public void theLegacyReadDoesNotPinTheRunFence() {
        AiRunConfig legacy = legacyConfig();
        AiRunConfig current = currentConfig();
        MapStorage storage = new MapStorage();
        storage.payloads.put(legacy.recordIdentity().storageKey(),
                AiPaidRecordCodec.encode(legacyRecord(legacy)));
        AiPaidRecords records = new AiPaidRecords(storage);

        assertNotNull(records.readLegacy(legacy));
        assertNull("the current-epoch read still sees its own (absent) entry", records.read(current));

        AiRecordStore.Reservation reservation = records.reserve(current, 4_096L);
        assertTrue("a legacy read must not fail the current run's reservation",
                reservation.accepted());
    }

    // --- helpers ------------------------------------------------------------

    private AiMeaningRun.Result run(boolean allowProviderRequest, boolean explicitRetry) {
        return AiMeaningRun.run(context, settings, base, document, null, false, TARGET,
                allowProviderRequest, new AiSignal(), null, explicitRetry);
    }

    private List<AiLine> rows() {
        return AiRows.forMeaning(base, document, null, false);
    }

    private AiReplayIdentity replay() {
        return AiReplayIdentity.of(rows(), false);
    }

    private AiRunConfig configFor(int epoch) {
        AiProviderConfig provider = settings.providerConfig(LayerKind.MEANING, TARGET, false);
        assertNotNull(provider);
        String instructions = settings.instructions(LayerKind.MEANING);
        AiIdentity.Config input = new AiIdentity.Config();
        input.layer = LayerKind.MEANING;
        input.provider = settings.providerId();
        input.providerVersion = provider.providerVersion;
        input.modelName = provider.model.name;
        input.targetLang = TARGET;
        input.instructions = instructions;
        input.promptVersion = provider.promptVersion;
        input.harnessEpoch = epoch;
        return new AiRunConfig(LayerKind.MEANING, AiIdentity.buildDocDigest(rows(),
                AiLyricContext.EMPTY, false), AiIdentity.buildConfigId(input),
                settings.providerId(), AiLyricContext.EMPTY, provider, instructions, null, null,
                epoch);
    }

    private AiRunConfig legacyConfig() {
        return configFor(AiHarnessEpoch.LEGACY);
    }

    private AiRunConfig currentConfig() {
        return configFor(AiHarnessEpoch.MEANING);
    }

    private void seed(AiRunConfig config) {
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config;
        args.provider = new FakeAiProvider();
        args.store = store;
        args.rows = rows();
        args.replay = replay();
        args.binding = new AiSourceBinding(base, document);
        args.nowMs = 1L;
        assertEquals(AiRunOutcome.Kind.COMPLETED, AiLayerRunner.run(args).kind);
    }

    private void seedCurrentEpoch(String prefix) {
        AiReplayIdentity replay = replay();
        AiPaidRecord record = AiPaidRecord.begin(currentConfig(), 2L);
        record.status = AiPaidRecord.Status.COMPLETE;
        record.replay = replay;
        for (AiLine row : rows()) {
            if (row.isSent()) record.putItem(row.id, prefix + row.sourceText);
        }
        AiChunkRecord chunk = new AiChunkRecord(replay.rowIds, "");
        chunk.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", chunk);
        assertTrue(store.commit(currentConfig(), record));
    }

    private static AiPaidRecord legacyRecord(AiRunConfig config) {
        AiPaidRecord record = AiPaidRecord.begin(config, 1L);
        record.status = AiPaidRecord.Status.PARTIAL;
        return record;
    }

    private static final Field HTTP_CLIENT = httpClientField();

    private static Field httpClientField() {
        try {
            Field field = AiHttp.class.getDeclaredField("client");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("AiHttp client field moved", missing);
        }
    }

    private Response answer(okhttp3.Interceptor.Chain chain) throws IOException {
        providerCalls.incrementAndGet();
        Buffer buffer = new Buffer();
        if (chain.request().body() != null) chain.request().body().writeTo(buffer);
        if (failProvider) {
            return response(chain.request(), 401,
                    "{\"error\":{\"message\":\"test refusal\"}}");
        }
        return response(chain.request(), 200, successBody(buffer.readUtf8()));
    }

    private static Response response(Request request, int status, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status == 200 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    /** Answers the OpenAI-wire request with one accepted item per requested row. */
    private static String successBody(String wireBody) {
        JsonObject wire = JsonParser.parseString(wireBody).getAsJsonObject();
        JsonObject request = null;
        if (wire.has("messages") && wire.get("messages").isJsonArray()) {
            for (JsonElement element : wire.getAsJsonArray("messages")) {
                JsonObject message = element.getAsJsonObject();
                if (message.has("role") && "user".equals(message.get("role").getAsString())) {
                    request = JsonParser.parseString(
                            message.get("content").getAsString()).getAsJsonObject();
                    break;
                }
            }
        }
        if (request == null) throw new AssertionError("unexpected provider request");
        JsonArray answers = new JsonArray();
        for (JsonElement element : request.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject answer = new JsonObject();
            answer.addProperty("id", item.get("id").getAsString());
            answer.addProperty("t", "AI " + item.get("s").getAsString());
            answers.add(answer);
        }
        JsonObject payload = new JsonObject();
        payload.add("items", answers);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", payload.toString());
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 11);
        usage.addProperty("completion_tokens", 7);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        root.add("usage", usage);
        return root.toString();
    }

    /** A storage double that mirrors the durable entry lookup, including its deletion fence. */
    private static final class MapStorage implements AiPaidRecords.Storage {
        final Map<String, String> payloads = new LinkedHashMap<>();
        final List<PaidArtifactIdentity> reads = new ArrayList<>();

        @Override public AIPaidArtifactCache.Read read(PaidArtifactIdentity identity) {
            reads.add(identity);
            String payload = payloads.get(identity.storageKey());
            return new AIPaidArtifactCache.Read(payload,
                    AIPaidArtifactCache.entryKey(identity), identity.layerKind.name(), 0L, 0L, 0L);
        }

        @Override public AIPaidArtifactCache.Reservation reserve(PaidArtifactIdentity identity,
                long bytes, AIPaidArtifactCache.Reservation current,
                AIPaidArtifactCache.Read read) {
            return AIPaidArtifactCache.Reservation.admitted("token",
                    AIPaidArtifactCache.entryKey(identity));
        }

        @Override public boolean put(PaidArtifactIdentity identity, String payload,
                AIPaidArtifactCache.Reservation reservation, AIPaidArtifactCache.Read read) {
            payloads.put(identity.storageKey(), payload);
            return true;
        }

        @Override public void release(AIPaidArtifactCache.Reservation reservation) { }

        @Override public void remove(PaidArtifactIdentity identity) {
            payloads.remove(identity.storageKey());
        }
    }
}
