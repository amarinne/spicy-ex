package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI-wire adapter is exercised through a scripted transport, never the network. What these
 * pin: the canonical body's shape, the closed failure mapping, and — because reasoning endpoints
 * reject basic parameters — that a 400 naming {@code max_tokens} or {@code temperature} is
 * renegotiated from what the endpoint actually said, once, without spending an attempt above this
 * layer.
 */
public class AiOpenAiProviderTest {

    private static final AiModelDescriptor MODEL = new AiModelDescriptor("test-model", "1",
            AiContract.MAX_REQUEST_BYTES, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
            Collections.singletonList("chat.completions"));

    private static final String BASE = "https://api.example.com/v1";
    private static final String KEY = "secret-key-value";

    /** A transport with scripted responses; records every POST body it was handed. */
    private static final class FakeTransport implements AiGeminiProvider.Transport {
        final List<String> postedBodies = new ArrayList<>();
        private final List<AiHttp.Result> postResults = new ArrayList<>();

        FakeTransport add(AiHttp.Result result) {
            postResults.add(result);
            return this;
        }

        @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                           AiSignal signal, int maxBytes) {
            return AiHttp.Result.of(200, "{\"data\":[{\"id\":\"m1\",\"owned_by\":\" opencode go \"},{\"id\":\"m2\"}]}", 40L);
        }

        @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                String json, AiSignal signal, int maxBytes) {
            assertEquals(BASE + "/chat/completions", url);
            assertEquals("Bearer " + KEY, headers.get("Authorization"));
            postedBodies.add(json);
            return postResults.remove(0);
        }
    }

    /** The Sound layer keeps the structured JSON contract that schema negotiation serves. */
    private static AiProviderConfig soundConfig() {
        return new AiProviderConfig(LayerKind.SOUND, BASE, "openai-v1",
                MODEL, "Latin", AiContract.SOUND_PROMPT_VERSION, false).forCall(false,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    @Test
    public void meaningSendsNumberedTextWithoutAResponseFormat() {
        FakeTransport transport = new FakeTransport().add(ok(completion("1. hello")));
        assertTrue(provider(transport).generateChunk(request(), config(), new AiSignal()).ok);
        JsonObject body = parse(transport.postedBodies.get(0));
        assertFalse(body.has("response_format"));
        String user = body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString();
        assertTrue(user.contains("Lines:\n1. hola"));
    }

    @Test
    public void anEndpointThatNamesALowerOutputCapGetsItOnTheNextRound() {
        List<String> bodies = new ArrayList<>();
        AiOpenAiProvider capped = new AiOpenAiProvider("https://capped.example.org/v1", () -> KEY,
                scripted(bodies,
                        status(400, "{\"error\":{\"message\":\"max_tokens is too large: 131072. "
                                + "This model supports at most 16384 completion tokens.\"}}"),
                        ok(completion("1. hello")), ok(completion("1. again"))));
        assertTrue(capped.generateChunk(request(), config(), new AiSignal()).ok);
        assertEquals(16384, parse(bodies.get(1)).get("max_tokens").getAsInt());
        assertTrue(capped.generateChunk(request(), config(), new AiSignal()).ok);
        assertEquals("the lower cap is remembered", 3, bodies.size());
        assertEquals(16384, parse(bodies.get(2)).get("max_tokens").getAsInt());
    }

    @Test
    public void lowerCapsAreReadFromEachProvidersWording() {
        assertEquals(65536, AiOpenAiProvider.lowerOutputCap("{\"error\":{\"message\":\"The "
                + "requested maxOutputTokens (131072) exceeds the limit of 65536.\"}}", 131072));
        assertEquals(8192, AiOpenAiProvider.lowerOutputCap("Invalid max_tokens value, the valid "
                + "range of max_tokens is [1, 8192]", 131072));
        assertEquals(0, AiOpenAiProvider.lowerOutputCap("model not found 404", 131072));
    }

    @Test
    public void aCustomGatewaySendsItsReasoningEffort() {
        List<String> bodies = new ArrayList<>();
        AiProviderConfig low = new AiProviderConfig(LayerKind.MEANING, BASE,
                "openai-v1+reasoning=low", MODEL, "en", AiContract.PROMPT_VERSION, false);
        new AiOpenAiProvider("https://effort.example.org/v1", () -> KEY,
                scripted(bodies, ok(completion("1. hello")))).generateChunk(request(), low,
                new AiSignal());
        assertEquals("low", parse(bodies.get(0)).get("reasoning_effort").getAsString());
    }

    /** The per-attempt configuration exactly as the runtime builds it, cap included. */
    private static AiProviderConfig config() {
        return new AiProviderConfig(LayerKind.MEANING, BASE, "openai-v1",
                MODEL, "en", AiContract.PROMPT_VERSION, false).forCall(false,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    private static AiOpenAiProvider provider(AiGeminiProvider.Transport transport) {
        return new AiOpenAiProvider(BASE, () -> KEY, transport);
    }

    private static AiProviderRequest request() {
        List<AiRequestItem> items = Collections.singletonList(
                new AiRequestItem("S0", AiLineClass.ORDINARY, null, "hola", null));
        return new AiProviderRequest(AiLyricContext.EMPTY, "en", "", items);
    }

    private static AiHttp.Result ok(String body) {
        return AiHttp.Result.of(200, body, body.length());
    }

    private static AiHttp.Result status(int code, String body) {
        return AiHttp.Result.of(code, body, body.length());
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String completion(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
    }

    @Test
    public void deepSeekReasoningContentIsCapturedWithoutEnteringTheAnswer() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"reasoning_content\":\"the second line is an ad-lib\","
                + "\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}";
        FakeTransport transport = new FakeTransport().add(ok(body));

        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertEquals("{}", result.rawText);
        assertEquals("the second line is an ad-lib", result.reasoning);
        assertTrue(result.hasReasoning());
    }

    /** Routed gateways answer on {@code reasoning}, and some of them in blocks. */
    @Test
    public void routedReasoningIsReadFromEitherAStringOrBlocks() {
        FakeTransport strings = new FakeTransport().add(ok(
                "{\"choices\":[{\"message\":{\"reasoning\":\"thinking\",\"content\":\"{}\"},"
                        + "\"finish_reason\":\"stop\"}]}"));
        assertEquals("thinking", provider(strings)
                .generateChunk(request(), config(), new AiSignal()).reasoning);

        FakeTransport blocks = new FakeTransport().add(ok(
                "{\"choices\":[{\"message\":{\"reasoning\":[{\"text\":\"first\"},"
                        + "{\"text\":\"second\"}],\"content\":\"{}\"},"
                        + "\"finish_reason\":\"stop\"}]}"));
        assertEquals("first\nsecond", provider(blocks)
                .generateChunk(request(), config(), new AiSignal()).reasoning);
    }

    /** A model that returned no trace must not manufacture an empty section in the panel. */
    @Test
    public void anAnswerWithoutAnyTraceReportsNoReasoning() {
        FakeTransport transport = new FakeTransport().add(ok(completion("hello")));

        assertFalse(provider(transport).generateChunk(request(), config(), new AiSignal())
                .hasReasoning());
    }

    // --- canonical body ------------------------------------------------------

    @org.junit.Before
    public void resetSchemaMemo() {
        AiOpenAiProvider.resetSchemaSupportForTest();
    }

    @Test
    public void theFirstBodyOffersStrictSchemaEnforcement() {
        FakeTransport transport = new FakeTransport().add(ok(completion("hello")));
        assertTrue(provider(transport).generateChunk(request(), soundConfig(), new AiSignal()).ok);

        JsonObject body = parse(transport.postedBodies.get(0));
        assertEquals("test-model", body.get("model").getAsString());
        assertEquals(0, body.get("temperature").getAsInt());
        assertEquals(AiContract.MAX_CONFIGURED_OUTPUT_TOKENS, body.get("max_tokens").getAsInt());
        assertFalse(body.has("max_completion_tokens"));

        JsonObject format = body.getAsJsonObject("response_format");
        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertTrue(schema.getAsJsonObject("properties").has("items"));
        assertTrue(schema.get("additionalProperties").getAsBoolean() == false);
        JsonObject item = schema.getAsJsonObject("properties").getAsJsonObject("items")
                .getAsJsonObject("items");
        assertTrue(item.getAsJsonObject("properties").has("id"));
        assertTrue(item.getAsJsonObject("properties").has("t"));

        JsonObject system = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        JsonObject user = body.getAsJsonArray("messages").get(1).getAsJsonObject();
        assertEquals("system", system.get("role").getAsString());
        assertTrue(system.get("content").getAsString()
                .contains(AiContract.SOUND_SYSTEM_PROMPT.substring(0, 64)));
        assertEquals("user", user.get("role").getAsString());
        assertTrue(user.get("content").getAsString().contains("\"id\":\"S0\""));
    }

    @Test
    public void theMonitorPayloadMatchesTheDispatchedBodyAndCarriesNoCredential() {
        FakeTransport transport = new FakeTransport().add(ok(completion("hello")));
        AiOpenAiProvider openAiProvider = provider(transport);
        String payload = openAiProvider.monitorPayload(request(), config());
        assertFalse(payload.contains(KEY));
        openAiProvider.generateChunk(request(), config(), new AiSignal());
        assertEquals(payload, transport.postedBodies.get(0));
    }

    // --- response parsing ----------------------------------------------------

    @Test
    public void contentBlocksAndFinishReasonsAreReadFromTheChoiceOnly() {
        String blocks = "{\"choices\":[{\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"he\"},{\"type\":\"text\",\"text\":\"llo\"}]},"
                + "\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":5,"
                + "\"completion_tokens\":9}}";
        FakeTransport transport = new FakeTransport().add(ok(blocks));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertTrue(result.ok);
        assertEquals("hello", result.rawText);
        assertEquals(AiFinishReason.LENGTH, result.finish);
        assertEquals(5, result.usage.input.intValue());
        assertEquals(9, result.usage.output.intValue());
    }

    @Test
    public void reasoningContentNeverBecomesDisplayedOutput() {
        String response = "{\"choices\":[{\"message\":{\"reasoning_content\":"
                + "\"private chain of thought\",\"content\":\"final answer\"},"
                + "\"finish_reason\":\"stop\"}]}";
        AiProviderResult result = provider(new FakeTransport().add(ok(response)))
                .generateChunk(request(), config(), new AiSignal());

        assertTrue(result.ok);
        assertEquals("final answer", result.rawText);
        assertFalse(result.rawText.contains("chain of thought"));
    }

    @Test
    public void anUnknownFinishReasonIsARefusalNotASuccess() {
        FakeTransport transport = new FakeTransport().add(ok(
                "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"weird\"}]}"));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());
        assertTrue(result.ok);
        assertEquals(AiFinishReason.OTHER, result.finish);
    }

    @Test
    public void aBodyWithoutChoicesIsAProtocolFailure() {
        FakeTransport transport = new FakeTransport().add(ok("{\"object\":\"chat.completion\"}"));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());
        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.PROTOCOL, result.failure.kind);
    }

    // --- reasoning trace -----------------------------------------------------

    /** Scripted transport for an arbitrary endpoint; records the POST bodies. */
    private static AiGeminiProvider.Transport scripted(List<String> bodies,
                                                       AiHttp.Result... results) {
        final List<AiHttp.Result> queue = new ArrayList<>(java.util.Arrays.asList(results));
        return new AiGeminiProvider.Transport() {
            @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                               AiSignal signal, int maxBytes) {
                throw new UnsupportedOperationException();
            }

            @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                    String json, AiSignal signal, int maxBytes) {
                bodies.add(json);
                return queue.remove(0);
            }
        };
    }

    @Test
    public void aCustomGatewayIsAskedForTheReasoningTrace() {
        List<String> bodies = new ArrayList<>();
        new AiOpenAiProvider("https://gateway.example.net/v1", () -> KEY,
                scripted(bodies, ok(completion("hello")))).generateChunk(request(), config(),
                new AiSignal());
        assertTrue(parse(bodies.get(0)).get("include_reasoning").getAsBoolean());
    }

    @Test
    public void officialOpenAiIsNeverSentIncludeReasoning() {
        List<String> bodies = new ArrayList<>();
        new AiOpenAiProvider("https://api.openai.com/v1", () -> KEY,
                scripted(bodies, ok(completion("hello")))).generateChunk(request(), config(),
                new AiSignal());
        assertFalse(parse(bodies.get(0)).has("include_reasoning"));
    }

    @Test
    public void aGatewayThatRejectsIncludeReasoningIsAskedWithoutItAndRemembered() {
        List<String> bodies = new ArrayList<>();
        AiOpenAiProvider strict = new AiOpenAiProvider("https://strict.example.org/v1", () -> KEY,
                scripted(bodies,
                        status(400, "{\"error\":{\"message\":\"Unrecognized request argument "
                                + "supplied: include_reasoning\"}}"),
                        ok(completion("hello")), ok(completion("again"))));
        assertTrue(strict.generateChunk(request(), config(), new AiSignal()).ok);
        assertFalse(parse(bodies.get(1)).has("include_reasoning"));
        assertTrue(strict.generateChunk(request(), config(), new AiSignal()).ok);
        assertEquals("the refusal is remembered per endpoint", 3, bodies.size());
        assertFalse(parse(bodies.get(2)).has("include_reasoning"));
    }

    // --- streaming -----------------------------------------------------------

    /** A transport that answers a streamed post with these server-sent event payloads. */
    private static AiGeminiProvider.Transport streaming(final List<String> bodies,
                                                        final String... events) {
        return new AiGeminiProvider.Transport() {
            @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                               AiSignal signal, int maxBytes) {
                throw new UnsupportedOperationException();
            }

            @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                    String json, AiSignal signal, int maxBytes) {
                throw new AssertionError("a streaming endpoint must be asked to stream");
            }

            @Override public AiHttp.Result postJsonStream(String url, Map<String, String> headers,
                                                          String json, AiSignal signal,
                                                          int maxBytes, AiHttp.StreamSink sink) {
                bodies.add(json);
                for (String event : events) sink.onData(event);
                return AiHttp.Result.streamed(200, 100L);
            }
        };
    }

    private static String delta(String field, String text) {
        return "{\"choices\":[{\"delta\":{\"" + field + "\":\"" + text + "\"}}]}";
    }

    @Test
    public void aStreamIsFoldedIntoTheSameResultAndFinishedLinesReachTheListener() {
        List<String> bodies = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        AiProviderResult result = new AiOpenAiProvider("https://stream.example.net/v1", () -> KEY,
                streaming(bodies, delta("reasoning_content", "thinking"),
                        delta("content", "1. hel"), delta("content", "lo\\n2. wor"),
                        delta("content", "ld"),
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,"
                                + "\"completion_tokens\":9}}"))
                .generateChunk(request(), config(), new AiSignal(), new AiProvider.StreamListener() {
                    @Override public void onText(String textSoFar) {
                        texts.add(textSoFar);
                    }
                });
        assertTrue(result.ok);
        assertEquals("1. hello\n2. world", result.rawText);
        assertEquals("thinking", result.reasoning);
        assertEquals(AiFinishReason.STOP, result.finish);
        assertEquals(Integer.valueOf(9), result.usage.output);
        assertEquals("only a finished line is reported", 1, texts.size());
        assertEquals("1. hello\n2. wor", texts.get(0));
        JsonObject body = parse(bodies.get(0));
        assertTrue(body.get("stream").getAsBoolean());
        assertTrue(body.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
    }

    @Test
    public void aStreamedJsonSoundResponseNotifiesWhenEachItemCloses() {
        final List<String> texts = new ArrayList<>();
        AiProviderResult result = new AiOpenAiProvider("https://sound-stream.example.net/v1",
                () -> KEY,
                streaming(new ArrayList<>(),
                        delta("content", "{\\\"items\\\":[{\\\"id\\\":\\\"r0\\\",\\\"t\\\":\\\"ha\\\"}"),
                        delta("content", ",{\\\"id\\\":\\\"r1\\\",\\\"t\\\":\\\"lo\\\"}]}"),
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"))
                .generateChunk(request(), soundConfig(), new AiSignal(),
                        new AiProvider.StreamListener() {
                            @Override public void onText(String textSoFar) {
                                texts.add(textSoFar);
                            }
                        });
        assertTrue(result.ok);
        assertEquals(2, texts.size());
    }

    @Test
    public void customGatewayReasoningOffSendsThinkingDisabledWithoutIncludeReasoning() {
        List<String> bodies = new ArrayList<>();
        AiProviderConfig customOff = new AiProviderConfig(LayerKind.MEANING,
                "https://custom-off.example.net/v1",
                AiSettings.customProviderVersion(
                        com.eza.spicyex.settings.values.AiDeepSeekReasoning.OFF),
                MODEL, "en", AiContract.PROMPT_VERSION, false)
                .forCall(false, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);

        AiOpenAiProvider gateway = new AiOpenAiProvider("https://custom-off.example.net/v1",
                () -> KEY, scripted(bodies, ok(completion("1. hello"))));
        String monitored = gateway.monitorPayload(request(), customOff);
        assertTrue(gateway.generateChunk(request(), customOff, new AiSignal()).ok);
        assertEquals(monitored, bodies.get(0));

        JsonObject body = parse(bodies.get(0));
        assertEquals("none", body.get("reasoning_effort").getAsString());
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(body.has("include_reasoning"));
    }

    @Test
    public void customGatewayThatRejectsThinkingDropsItAndRemembers() {
        List<String> bodies = new ArrayList<>();
        AiProviderConfig customOff = new AiProviderConfig(LayerKind.MEANING,
                "https://strict-thinking.example.net/v1",
                AiSettings.customProviderVersion(
                        com.eza.spicyex.settings.values.AiDeepSeekReasoning.OFF),
                MODEL, "en", AiContract.PROMPT_VERSION, false)
                .forCall(false, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);

        AiOpenAiProvider gateway = new AiOpenAiProvider("https://strict-thinking.example.net/v1",
                () -> KEY, scripted(bodies,
                        status(400, "{\"error\":{\"message\":\"Unknown parameter: thinking\"}}"),
                        ok(completion("1. hello")),
                        ok(completion("1. again"))));
        assertTrue(gateway.generateChunk(request(), customOff, new AiSignal()).ok);
        assertTrue(parse(bodies.get(0)).has("thinking"));
        assertFalse(parse(bodies.get(1)).has("thinking"));
        assertEquals("none", parse(bodies.get(1)).get("reasoning_effort").getAsString());

        assertTrue(gateway.generateChunk(request(), customOff, new AiSignal()).ok);
        assertEquals(3, bodies.size());
        assertFalse(parse(bodies.get(2)).has("thinking"));
    }

    @Test
    public void aStreamThatEndsWithoutAFinishReasonIsAnUnknownDelivery() {
        AiProviderResult result = new AiOpenAiProvider("https://cut.example.net/v1", () -> KEY,
                streaming(new ArrayList<>(), delta("content", "1. hel")))
                .generateChunk(request(), config(), new AiSignal(), null);
        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.DELIVERY_UNKNOWN, result.failure.kind);
        assertEquals("stream_ended", result.failure.detail);
    }

    @Test
    public void aStreamOptionsRejectionKeepsStreamingWithoutUsage() {
        {
            String body = "{\"error\":{\"message\":\"Unknown parameter: stream_options\"}}";
            String upstream = "{\"error\":{\"message\":\"upstream timed out\"}}";
            List<String> bodies = new ArrayList<>();
            AiOpenAiProvider provider = new AiOpenAiProvider("https://opts.example.net/v1",
                    () -> KEY, new AiGeminiProvider.Transport() {
                        int calls;
                        @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                                           AiSignal signal, int maxBytes) {
                            throw new UnsupportedOperationException();
                        }
                        @Override public AiHttp.Result postJson(String url,
                                Map<String, String> headers, String json, AiSignal signal,
                                int maxBytes) {
                            throw new AssertionError("streaming stays on");
                        }
                        @Override public AiHttp.Result postJsonStream(String url,
                                Map<String, String> headers, String json, AiSignal signal,
                                int maxBytes, AiHttp.StreamSink sink) {
                            bodies.add(json);
                            if (calls++ == 0) return status(400, body);
                            if (calls == 2) return status(400, upstream);
                            return AiHttp.Result.streamed(200, 1L);
                        }
                    });
            provider.generateChunk(request(), config(), new AiSignal(), null);
            assertFalse(parse(bodies.get(1)).has("stream_options"));
            assertTrue(parse(bodies.get(1)).get("stream").getAsBoolean());
            assertEquals("an unrelated 'upstream' error ends negotiation", 2, bodies.size());
        }
    }

    // --- parameter negotiation ----------------------------------------------

    private static final String OPENAI_MAX_TOKENS_REJECTION = "{\"error\":{\"message\":"
            + "\"Unsupported parameter: 'max_tokens' is not supported with this model. "
            + "Use 'max_completion_tokens' instead.\",\"type\":\"invalid_request_error\","
            + "\"param\":\"max_tokens\",\"code\":\"unsupported_parameter\"}}";

    @Test
    public void aMaxTokensRejectionIsRenegotiatedToMaxCompletionTokensFromTheBodyAlone() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, OPENAI_MAX_TOKENS_REJECTION))
                .add(ok(completion("hello")));
        AiProviderResult result = provider(transport).generateChunk(request(), soundConfig(),
                new AiSignal());

        assertTrue(result.ok);
        assertEquals(2, transport.postedBodies.size());
        JsonObject renegotiated = parse(transport.postedBodies.get(1));
        assertFalse(renegotiated.has("max_tokens"));
        assertEquals(AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                renegotiated.get("max_completion_tokens").getAsInt());
        assertEquals("json_schema",
                renegotiated.getAsJsonObject("response_format").get("type").getAsString());
        assertEquals(0, renegotiated.get("temperature").getAsInt());
    }

    @Test
    public void aTemperatureRejectionIsRenegotiatedByDroppingTheParameter() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, "{\"error\":{\"message\":\"deepseek-reasoner does not support "
                        + "the parameter `temperature`\"}}"))
                .add(ok(completion("hello")));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertTrue(result.ok);
        assertEquals(2, transport.postedBodies.size());
        JsonObject renegotiated = parse(transport.postedBodies.get(1));
        assertFalse(renegotiated.has("temperature"));
        assertTrue(renegotiated.has("max_tokens"));
    }

    @Test
    public void chainedRejectionsPreserveEarlierWireShapeNegotiation() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, OPENAI_MAX_TOKENS_REJECTION))
                .add(status(400, "{\"error\":{\"message\":\"this model does not support "
                        + "the parameter `temperature`\"}}"))
                .add(ok(completion("hello")));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertTrue(result.ok);
        assertEquals(3, transport.postedBodies.size());
        JsonObject renegotiated = parse(transport.postedBodies.get(2));
        assertFalse(renegotiated.has("max_tokens"));
        assertTrue(renegotiated.has("max_completion_tokens"));
        assertFalse(renegotiated.has("temperature"));
    }

    @Test
    public void aSchemaRejectionDowngradesToJsonObjectAndIsRememberedForTheEndpoint() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, "{\"error\":{\"message\":\"response_format type "
                        + "'json_schema' is not supported\"}}"))
                .add(ok(completion("hello")))
                .add(ok(completion("again")));
        AiOpenAiProvider openAiProvider = provider(transport);

        assertTrue(openAiProvider.generateChunk(request(), soundConfig(), new AiSignal()).ok);
        JsonObject fallback = parse(transport.postedBodies.get(1));
        assertEquals("json_object",
                fallback.getAsJsonObject("response_format").get("type").getAsString());

        // The downgrade is remembered per endpoint: the next call goes straight to json_object.
        assertTrue(openAiProvider.generateChunk(request(), soundConfig(), new AiSignal()).ok);
        assertEquals(3, transport.postedBodies.size());
        assertEquals("json_object", parse(transport.postedBodies.get(2))
                .getAsJsonObject("response_format").get("type").getAsString());
    }

    @Test
    public void aSupportedEndpointKeepsSchemaEnforcementAcrossCalls() {
        FakeTransport transport = new FakeTransport()
                .add(ok(completion("hello")))
                .add(ok(completion("again")));
        AiOpenAiProvider openAiProvider = provider(transport);

        assertTrue(openAiProvider.generateChunk(request(), soundConfig(), new AiSignal()).ok);
        assertTrue(openAiProvider.generateChunk(request(), soundConfig(), new AiSignal()).ok);
        for (String posted : transport.postedBodies) {
            assertEquals("json_schema",
                    parse(posted).getAsJsonObject("response_format").get("type").getAsString());
        }
    }

    @Test
    public void anUnrelatedRejectionIsNeverRetriedHere() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, "{\"error\":{\"message\":\"context length exceeded\"}}"));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.REQUEST_REJECTED, result.failure.kind);
        assertEquals(1, transport.postedBodies.size());
    }

    @Test
    public void aRenegotiatedRequestThatStillFailsReportsTheSecondOutcome() {
        FakeTransport transport = new FakeTransport()
                .add(status(400, OPENAI_MAX_TOKENS_REJECTION))
                .add(status(401, "{\"error\":{\"message\":\"bad key\"}}"));
        AiProviderResult result = provider(transport).generateChunk(request(), config(),
                new AiSignal());

        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.AUTH, result.failure.kind);
        assertEquals(2, transport.postedBodies.size());
    }

    // --- failure mapping and discovery ---------------------------------------

    @Test
    public void statusesMapOntoClosedFailureKinds() {
        assertEquals(AiProviderFailure.Kind.AUTH, generateWith(status(403, "{}")).kind);
        assertEquals(AiProviderFailure.Kind.QUOTA, generateWith(status(402, "{}")).kind);
        assertEquals(AiProviderFailure.Kind.RATE_LIMITED,
                generateWith(status(429, "{}")).kind);
        assertEquals(AiProviderFailure.Kind.MODEL_UNAVAILABLE,
                generateWith(status(404, "{}")).kind);
        assertEquals(AiProviderFailure.Kind.DELIVERY_UNKNOWN,
                generateWith(status(502, "{}")).kind);
        assertEquals(AiProviderFailure.Kind.QUOTA, generateWith(status(400,
                "{\"error\":{\"code\":\"insufficient_quota\"}}")).kind);
    }

    private static AiProviderFailure generateWith(AiHttp.Result scripted) {
        return provider(new FakeTransport().add(scripted))
                .generateChunk(request(), config(), new AiSignal()).failure;
    }

    @Test
    public void discoveryReadsTheBareModelList() {
        FakeTransport transport = new FakeTransport();
        AiModelListResult result = provider(transport).listModels(new AiSignal());
        assertTrue(result.ok);
        assertEquals(2, result.models.size());
        assertEquals("m1", result.models.get(0).name);
        assertEquals("opencode go", result.models.get(0).owner);
        assertEquals("", result.models.get(1).owner);
    }

    @Test
    public void providerErrorCodesAreKeptAsTokensNotMessages() {
        assertEquals("TOS_VIOLATION", AiProviderFailure.errorToken("{\"error\":{\"code\":403,"
                + "\"message\":\"disabled\",\"status\":\"PERMISSION_DENIED\",\"details\":"
                + "[{\"reason\":\"TOS_VIOLATION\"}]}}"));
        assertEquals("api_key_expired", AiProviderFailure.errorToken("{\"error\":{\"type\":"
                + "\"authentication_error\",\"code\":\"api_key_expired\"}}"));
        assertEquals("invalid_request_error", AiProviderFailure.errorToken(
                "{\"error\":{\"message\":\"You have insufficient credits\","
                        + "\"type\":\"invalid_request_error\"}}"));
        assertEquals("", AiProviderFailure.errorToken("{\"error\":{\"code\":\"two words\"}}"));
        assertEquals("", AiProviderFailure.errorToken("upstream timeout"));
    }

    @Test
    public void aMissingCredentialShortCircuitsBeforeAnyCall() {
        AiOpenAiProvider unkeyed = new AiOpenAiProvider(BASE, () -> "",
                new FakeTransport().add(ok(completion("hello"))));
        AiProviderResult result = unkeyed.generateChunk(request(), config(), new AiSignal());
        assertFalse(result.ok);
        assertEquals(AiProviderFailure.Kind.AUTH, result.failure.kind);
    }

    // --- routed endpoints (OpenRouter) ---------------------------------------

    private static AiProviderConfig routedConfig() {
        return new AiProviderConfig(LayerKind.MEANING, BASE,
                AiSettings.OPENROUTER_PROVIDER_VERSION, MODEL, "en", AiContract.PROMPT_VERSION,
                false).forCall(false, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    private static AiProviderConfig deepSeekConfig(String reasoning) {
        return new AiProviderConfig(LayerKind.MEANING, AiSettings.DEEPSEEK_BASE_URL,
                AiSettings.deepSeekProviderVersion(reasoning), MODEL, "en",
                AiContract.PROMPT_VERSION, false)
                .forCall(false, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    /**
     * The knob every upstream names differently — {@code reasoning_effort},
     * {@code enable_thinking}, {@code thinking_budget}, {@code thinkingLevel} — normalized to one.
     * Asking for little of it is the point: reasoning tokens are billed as output.
     */
    @Test
    public void aRoutedEndpointAsksForLittleReasoningAndCompatibleRoutingOnly() {
        FakeTransport transport = new FakeTransport().add(ok(completion("hello")));
        assertTrue(provider(transport).generateChunk(request(), routedConfig(), new AiSignal()).ok);

        JsonObject body = parse(transport.postedBodies.get(0));
        assertEquals(AiSettings.OPENROUTER_REASONING_EFFORT,
                body.getAsJsonObject("reasoning").get("effort").getAsString());
        assertTrue(body.getAsJsonObject("provider").get("require_parameters").getAsBoolean());
    }

    /** A plain OpenAI-compatible endpoint must never see a gateway-only field. */
    @Test
    public void aPlainEndpointIsSentNoRoutingFields() {
        FakeTransport transport = new FakeTransport().add(ok(completion("hello")));
        assertTrue(provider(transport).generateChunk(request(), config(), new AiSignal()).ok);

        JsonObject body = parse(transport.postedBodies.get(0));
        assertFalse(body.has("reasoning"));
        assertFalse(body.has("provider"));
    }

    /** DeepSeek documents JSON-object output and defaults thinking to high effort. */
    @Test
    public void deepSeekUsesJsonObjectAndExplicitLowThinking() {
        final List<String> bodies = new ArrayList<>();
        AiOpenAiProvider deepSeek = new AiOpenAiProvider(AiSettings.DEEPSEEK_BASE_URL, () -> KEY,
                new AiGeminiProvider.Transport() {
                    @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                                       AiSignal signal, int maxBytes) {
                        throw new UnsupportedOperationException();
                    }

                    @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                            String json, AiSignal signal,
                                                            int maxBytes) {
                        assertEquals(AiSettings.DEEPSEEK_BASE_URL + "/chat/completions", url);
                        bodies.add(json);
                        return ok(completion("hello"));
                    }
                });

        String monitored = deepSeek.monitorPayload(request(), deepSeekConfig("Low"));
        assertTrue(deepSeek.generateChunk(request(), deepSeekConfig("Low"), new AiSignal()).ok);
        assertEquals(monitored, bodies.get(0));
        JsonObject body = parse(bodies.get(0));
        assertFalse("Meaning uses the text wire", body.has("response_format"));
        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("low", body.get("reasoning_effort").getAsString());
        assertFalse(body.has("reasoning"));
        assertFalse(body.has("provider"));
    }

    @Test
    public void deepSeekReasoningMayBeDisabled() {
        final List<String> bodies = new ArrayList<>();
        AiOpenAiProvider deepSeek = new AiOpenAiProvider(AiSettings.DEEPSEEK_BASE_URL, () -> KEY,
                new AiGeminiProvider.Transport() {
                    @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                                       AiSignal signal, int maxBytes) {
                        throw new UnsupportedOperationException();
                    }

                    @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                            String json, AiSignal signal,
                                                            int maxBytes) {
                        bodies.add(json);
                        return ok(completion("hello"));
                    }
                });

        assertTrue(deepSeek.generateChunk(request(), deepSeekConfig("Off"), new AiSignal()).ok);
        JsonObject body = parse(bodies.get(0));
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(body.has("reasoning_effort"));
    }

    @Test
    public void deepSeekReasoningIdentityKeepsEveryDocumentedModeDistinct() {
        assertTrue(AiSettings.deepSeekProviderVersion("Low").endsWith("reasoning=low"));
        assertTrue(AiSettings.deepSeekProviderVersion("High").endsWith("reasoning=high"));
        assertTrue(AiSettings.deepSeekProviderVersion("Max").endsWith("reasoning=max"));
        assertTrue(AiSettings.deepSeekProviderVersion("Off").endsWith("thinking=disabled"));
    }

    /**
     * The trait is read from the owner's explicit provider choice, never from a model name — a
     * gateway serves hundreds of names it does not own.
     */
    @Test
    public void routingTraitsComeFromTheProviderVersionAndNotTheModelName() {
        AiModelDescriptor namedLikeAGatewayModel = new AiModelDescriptor(
                "deepseek/deepseek-v4-flash", "1", AiContract.MAX_REQUEST_BYTES,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                Collections.singletonList("chat.completions"));
        AiProviderConfig plainWireWithGatewayStyleName = new AiProviderConfig(LayerKind.MEANING,
                BASE, "openai-v1", namedLikeAGatewayModel, "en", AiContract.PROMPT_VERSION, false)
                .forCall(false, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);

        assertEquals("", AiOpenAiProvider.routedReasoningEffort(plainWireWithGatewayStyleName));
        assertEquals(AiSettings.OPENROUTER_REASONING_EFFORT,
                AiOpenAiProvider.routedReasoningEffort(routedConfig()));
    }

    /**
     * A routing gateway publishes a model index of several hundred kilobytes. Bounding it by the
     * generation ceiling made every model on the endpoint unreachable and reported it as
     * "could not reach provider" — the endpoint was reached, and answered.
     */
    @Test
    public void aLargeModelIndexIsNotMistakenForAnUnreachableEndpoint() {
        StringBuilder data = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 400; i++) {
            if (i > 0) data.append(',');
            data.append("{\"id\":\"vendor/model-").append(i)
                    .append("\",\"name\":\"Model ").append(i)
                    .append("\",\"description\":\"")
                    .append(new String(new char[600]).replace('\0', 'x'))
                    .append("\"}");
        }
        final String body = data.append("]}").toString();
        // A real gateway index is ~700 KiB. Discovery must be bounded by the index ceiling, which
        // is the only one sized for a document the endpoint publishes rather than generates.
        assertTrue("an index ceiling below the generation ceiling would be pointless",
                AiContract.MAX_MODEL_LIST_BYTES > AiContract.MAX_RESPONSE_BYTES);
        assertTrue("fixture must stay inside the index ceiling",
                body.length() < AiContract.MAX_MODEL_LIST_BYTES);

        AiModelListResult result = new AiOpenAiProvider(BASE, () -> KEY,
                new AiGeminiProvider.Transport() {
                    @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                                       AiSignal signal, int maxBytes) {
                        assertEquals(AiContract.MAX_MODEL_LIST_BYTES, maxBytes);
                        return body.length() > maxBytes
                                ? AiHttp.Result.failed(AiProviderFailure.oversized(body.length()))
                                : AiHttp.Result.of(200, body, body.length());
                    }

                    @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                            String json, AiSignal signal,
                                                            int maxBytes) {
                        throw new UnsupportedOperationException();
                    }
                }).listModels(null);

        assertTrue(result.ok);
        assertEquals(400, result.models.size());
    }
}
