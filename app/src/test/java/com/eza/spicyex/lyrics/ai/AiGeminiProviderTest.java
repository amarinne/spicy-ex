package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The adapter's job is shape translation and honest failure classification.
 *
 * <p>The classifications carry real consequences downstream, so they are what is tested hardest: a
 * 5xx must become delivery-unknown and never be retried automatically, a 429 must surrender its
 * Retry-After, and an unrecognised finish reason must be treated as a refusal rather than parsed
 * hopefully into an empty document.
 */
public class AiGeminiProviderTest {

    /** A transport that answers from a script and records what it was asked. */
    private static final class StubTransport implements AiGeminiProvider.Transport {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<AiHttp.Result> answers = new ArrayList<>();

        StubTransport(AiHttp.Result... scripted) {
            answers.addAll(Arrays.asList(scripted));
        }

        @Override public AiHttp.Result get(String url, Map<String, String> requestHeaders,
                                           AiSignal signal, int maxBytes) {
            urls.add(url);
            headers.add(new LinkedHashMap<>(requestHeaders));
            return answers.remove(0);
        }

        @Override public AiHttp.Result postJson(String url, Map<String, String> requestHeaders,
                                                String json, AiSignal signal, int maxBytes) {
            urls.add(url);
            headers.add(new LinkedHashMap<>(requestHeaders));
            bodies.add(json);
            return answers.remove(0);
        }
    }

    private static AiHttp.Result ok(String body) {
        return result(200, body, null);
    }

    private static AiHttp.Result status(int code, String body) {
        return result(code, body, null);
    }

    /** Builds a Result the way the transport would, through its package-private factories. */
    private static AiHttp.Result result(int status, String body, Long retryAfterMs) {
        AiHttp.Result result = AiHttp.Result.of(status, body, body == null ? 0 : body.length());
        result.retryAfterMs = retryAfterMs;
        return result;
    }

    private static AiGeminiProvider provider(StubTransport transport) {
        return new AiGeminiProvider(new AiGeminiProvider.CredentialSource() {
            @Override public String secret() {
                return "test-key";
            }
        }, transport);
    }

    private static AiProviderConfig config() {
        return new AiProviderConfig(LayerKind.MEANING, null, "1",
                new AiModelDescriptor("gemini-3.1-flash-lite", "1", 32_768, 8_192,
                        Collections.singletonList("generateContent")),
                "en", AiContract.PROMPT_VERSION, false).forCall(false, 4_096);
    }

    private static AiProviderRequest request() {
        return new AiProviderRequest(AiLyricContext.EMPTY, "en", "",
                Collections.singletonList(
                        new AiRequestItem("r0", AiLineClass.ORDINARY, null, "hola", null)));
    }

    private static String candidate(String text, String finish) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(text)
                + "}]},\"finishReason\":\"" + finish + "\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":7}}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // --- generation ---------------------------------------------------------

    @Test
    public void theKeyTravelsInAHeaderAndNeverInTheUrl() {
        StubTransport transport = new StubTransport(
                ok(candidate("{\"items\":[{\"id\":\"r0\",\"t\":\"hello\"}]}", "STOP")));

        provider(transport).generateChunk(request(), config(), null);

        assertEquals("test-key", transport.headers.get(0).get("x-goog-api-key"));
        assertFalse("a key in a URL reaches logs and proxies",
                transport.urls.get(0).contains("test-key"));
        assertFalse(transport.urls.get(0).contains("key="));
    }

    @Test
    public void theRequestAsksForJsonAtTheApiLevelAndPinsTemperature() {
        StubTransport transport = new StubTransport(
                ok(candidate("{\"items\":[{\"id\":\"r0\",\"t\":\"hello\"}]}", "STOP")));

        provider(transport).generateChunk(request(), config(), null);

        String body = transport.bodies.get(0);
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""));
        assertTrue(body.contains("\"responseSchema\""));
        assertTrue(body.contains("\"required\":[\"items\"]"));
        assertTrue(body.contains("\"temperature\":0"));
        assertTrue(body.contains("\"maxOutputTokens\":4096"));
        assertTrue("the system contract travels separately from the payload",
                body.contains("\"systemInstruction\""));
    }

    @Test
    public void anAcceptedAnswerCarriesItsTextFinishAndUsage() {
        StubTransport transport = new StubTransport(
                ok(candidate("{\"items\":[{\"id\":\"r0\",\"t\":\"hello\"}]}", "STOP")));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertTrue(result.ok);
        assertEquals(AiFinishReason.STOP, result.finish);
        assertEquals("{\"items\":[{\"id\":\"r0\",\"t\":\"hello\"}]}", result.rawText);
        assertEquals(Integer.valueOf(11), result.usage.input);
        assertEquals(Integer.valueOf(7), result.usage.output);
    }

    @Test
    public void ananswerSplitAcrossPartsIsRejoined() {
        StubTransport transport = new StubTransport(ok(
                "{\"candidates\":[{\"content\":{\"parts\":["
                        + "{\"text\":\"{\\\"items\\\":\"},{\"text\":\"[]}\"}]},"
                        + "\"finishReason\":\"STOP\"}]}"));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertEquals("{\"items\":[]}", result.rawText);
    }

    @Test
    public void aTruncatedAnswerIsReportedAsLengthRatherThanParsed() {
        StubTransport transport = new StubTransport(ok(candidate("{\"items\":[", "MAX_TOKENS")));
        assertEquals(AiFinishReason.LENGTH,
                provider(transport).generateChunk(request(), config(), null).finish);
    }

    @Test
    public void everyBlockingFinishReasonIsARefusal() {
        for (String reason : new String[]{"SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT"}) {
            StubTransport transport = new StubTransport(ok(candidate("", reason)));
            assertEquals(reason, AiFinishReason.SAFETY,
                    provider(transport).generateChunk(request(), config(), null).finish);
        }
    }

    @Test
    public void anUnknownFinishReasonIsNotOptimisticallyAccepted() {
        StubTransport transport = new StubTransport(ok(candidate("{}", "SOMETHING_NEW")));
        assertEquals(AiFinishReason.OTHER,
                provider(transport).generateChunk(request(), config(), null).finish);
    }

    @Test
    public void aPromptLevelBlockWithNoCandidateIsAFailureNotAnEmptyDocument() {
        StubTransport transport = new StubTransport(ok(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.PROTOCOL, result.failure.kind);
    }

    @Test
    public void missingUsageIsReportedAsUnreportedRatherThanZero() {
        StubTransport transport = new StubTransport(ok(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]},"
                        + "\"finishReason\":\"STOP\"}]}"));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertFalse("zero would be recorded as a free call", result.usage.isComplete());
    }

    // --- failure classification ---------------------------------------------

    @Test
    public void authFailuresAreTerminal() {
        for (int status : new int[]{401, 403}) {
            StubTransport transport = new StubTransport(status(status, "{}"));
            AiProviderResult result = provider(transport).generateChunk(request(), config(), null);
            assertEquals(AiProviderFailure.Kind.AUTH, result.failure.kind);
        }
    }

    @Test
    public void aRateLimitSurrendersItsRetryAfter() {
        StubTransport transport = new StubTransport(result(429, "{}", 5_000L));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertEquals(AiProviderFailure.Kind.RATE_LIMITED, result.failure.kind);
        assertEquals(Long.valueOf(5_000L), result.failure.retryAfterMs);
    }

    @Test
    public void aServerErrorIsDeliveryUnknownBecauseItMayAlreadyHaveBilled() {
        StubTransport transport = new StubTransport(status(503, "unavailable"));

        AiProviderResult result = provider(transport).generateChunk(request(), config(), null);

        assertEquals(AiProviderFailure.Kind.DELIVERY_UNKNOWN, result.failure.kind);
        assertEquals(AiProviderFailure.Cause.SERVER, result.failure.cause);
    }

    @Test
    public void anExhaustedQuotaIsTerminalRatherThanRetried() {
        StubTransport transport = new StubTransport(status(400,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));

        assertEquals(AiProviderFailure.Kind.QUOTA,
                provider(transport).generateChunk(request(), config(), null).failure.kind);
    }

    @Test
    public void aMissingModelSaysSoRatherThanLookingLikeABadRequest() {
        StubTransport transport = new StubTransport(status(404, "{}"));
        assertEquals(AiProviderFailure.Kind.MODEL_UNAVAILABLE,
                provider(transport).generateChunk(request(), config(), null).failure.kind);
    }

    @Test
    public void aBodyThatIsNotJsonIsAProtocolFailure() {
        StubTransport transport = new StubTransport(ok("<html>gateway</html>"));
        assertEquals(AiProviderFailure.Kind.PROTOCOL,
                provider(transport).generateChunk(request(), config(), null).failure.kind);
    }

    @Test
    public void noKeyMeansNoCallAtAll() {
        StubTransport transport = new StubTransport();
        AiGeminiProvider noCredential = new AiGeminiProvider(
                new AiGeminiProvider.CredentialSource() {
                    @Override public String secret() {
                        return "";
                    }
                }, transport);

        AiProviderResult result = noCredential.generateChunk(request(), config(), null);

        assertEquals(AiProviderFailure.Kind.AUTH, result.failure.kind);
        assertTrue("nothing should have been dispatched", transport.urls.isEmpty());
    }

    // --- discovery ----------------------------------------------------------

    @Test
    public void discoveryKeepsOnlyModelsThatCanAnswerTheRequest() {
        StubTransport transport = new StubTransport(ok("{\"models\":["
                + model("models/gemini-3.1-flash-lite", 1_000_000, 8_192, "generateContent")
                + "," + model("models/text-embedding-004", 2_048, 0, "embedContent")
                + "," + model("models/gemini-tts-preview", 1_000, 1_000, "generateContent")
                + "," + model("models/gemini-legacy", 1_000, 0, "generateContent")
                + "," + model("models/gemini-3.0-pro", 1_000_000, 8_192, "generateContent")
                + "]}"));

        AiModelListResult result = provider(transport).listModels(null);

        assertTrue(result.ok);
        List<String> names = new ArrayList<>();
        for (AiModelDescriptor model : result.models) names.add(model.name);
        assertEquals(Arrays.asList("gemini-3.1-flash-lite", "gemini-3.0-pro"), names);
    }

    @Test
    public void discoveryFollowsPagesUntilThereAreNoMore() {
        StubTransport transport = new StubTransport(
                ok("{\"models\":["
                        + model("models/gemini-a", 1_000, 100, "generateContent")
                        + "],\"nextPageToken\":\"page-2\"}"),
                ok("{\"models\":["
                        + model("models/gemini-b", 1_000, 100, "generateContent") + "]}"));

        AiModelListResult result = provider(transport).listModels(null);

        assertEquals(2, result.models.size());
        assertTrue(transport.urls.get(1).contains("pageToken=page-2"));
    }

    @Test
    public void theSuggestedModelIsOfferedFirstButNothingIsChosenAutomatically() {
        List<AiModelDescriptor> models = Arrays.asList(
                new AiModelDescriptor("gemini-3.0-pro", "1", 1, 1, Collections.<String>emptyList()),
                new AiModelDescriptor(AiGeminiProvider.SUGGESTED_MODEL, "1", 1, 1,
                        Collections.<String>emptyList()));

        List<AiModelDescriptor> ordered = AiGeminiProvider.withSuggestedFirst(models);

        assertEquals(AiGeminiProvider.SUGGESTED_MODEL, ordered.get(0).name);
        assertEquals(2, ordered.size());
    }

    @Test
    public void aDiscoveryFailureIsReportedRatherThanReturningAnEmptyList() {
        StubTransport transport = new StubTransport(status(401, "{}"));

        AiModelListResult result = provider(transport).listModels(null);

        assertFalse(result.ok);
        assertNotNull(result.failure);
        assertEquals(AiProviderFailure.Kind.AUTH, result.failure.kind);
    }

    private static String model(String name, int inputLimit, int outputLimit, String method) {
        return "{\"name\":\"" + name + "\",\"version\":\"1\",\"inputTokenLimit\":" + inputLimit
                + ",\"outputTokenLimit\":" + outputLimit
                + ",\"supportedGenerationMethods\":[\"" + method + "\"]}";
    }
}
