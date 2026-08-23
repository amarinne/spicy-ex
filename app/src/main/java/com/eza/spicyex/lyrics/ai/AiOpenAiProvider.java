package com.eza.spicyex.lyrics.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Any OpenAI-compatible endpoint: a local proxy, a self-hosted gateway, or a compatible service.
 *
 * <p>The shape is the same as Gemini's adapter and the differences are all in the wire format:
 * a bearer token instead of a vendor header, {@code /chat/completions} instead of
 * {@code :generateContent}, and a system message in the same array as the user message rather than
 * a separate field.
 *
 * <p>Discovery is thinner here by necessity. {@code /models} on a compatible server is often a bare
 * list of ids with no token limits and no capability flags, so there is nothing to filter on and
 * nothing to trust about limits. Every discovered model is offered and the planner falls back to
 * the contract's conservative bounds — under-estimating splits a document into more calls than it
 * needed, which costs a little; over-estimating gets a request rejected after it was billed.
 *
 * <p><b>Wire capabilities are negotiated, never assumed.</b> Reasoning models on this wire
 * disagree about basic parameters — some reject {@code max_tokens} in favor of
 * {@code max_completion_tokens}, others reject any {@code temperature} other than 1 — and gateways
 * differ on whether they accept {@code response_format} types beyond plain {@code json_object}.
 * Every one of those rejections arrives as a 400 before the model ran, so none of them costs
 * anything: the adapter offers the strictest shape first ({@code json_schema} enforcement),
 * watches what each 400 body names, and retries with that piece renegotiated. What an endpoint
 * accepts is remembered per endpoint, never guessed from a model name, which is why this ages on
 * custom gateways whose model lists cannot be trusted.
 */
public final class AiOpenAiProvider implements AiProvider {

    public static final String ID = "openai";

    /** {@code providerVersion} prefix marking an endpoint that routes to upstream providers. */
    private static final String ROUTED_PREFIX = "openrouter-v1";
    /** Official DeepSeek profile: JSON-object output and explicit low-effort thinking. */
    private static final String DEEPSEEK_PREFIX = "deepseek-v1";
    private static final String REASONING_MARKER = "+reasoning=";

    private final AiGeminiProvider.Transport transport;
    private final AiGeminiProvider.CredentialSource credential;
    private final String baseUrl;
    /** Memo of structured-output support for this exact endpoint; optimistic until told otherwise. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SCHEMA_SUPPORT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Clears the per-endpoint schema memo. Test seam only. */
    public static void resetSchemaSupportForTest() {
        SCHEMA_SUPPORT.clear();
    }

    public AiOpenAiProvider(String baseUrl, AiGeminiProvider.CredentialSource credential) {
        this(baseUrl, credential, AiTransports.live());
    }

    public AiOpenAiProvider(String baseUrl, AiGeminiProvider.CredentialSource credential,
                            AiGeminiProvider.Transport transport) {
        this.baseUrl = AiEndpoint.validate(baseUrl).normalized;
        this.credential = credential;
        this.transport = transport;
    }

    @Override public String id() {
        return ID;
    }

    @Override
    public AiModelListResult listModels(AiSignal signal) {
        if (baseUrl.isEmpty()) return AiModelListResult.failed(AiProviderFailure.protocol("no_endpoint"));
        String key = secret();
        if (key.isEmpty()) return AiModelListResult.failed(AiProviderFailure.auth());
        if (signal != null) signal.throwIfAborted();

        AiHttp.Result result = transport.get(baseUrl + "/models", headers(key), signal,
                AiContract.MAX_MODEL_LIST_BYTES);
        if (!result.ok()) return AiModelListResult.failed(failureOf(result));

        JsonObject body = objectOf(result.body);
        if (body == null) {
            return AiModelListResult.failed(AiProviderFailure.protocol("discovery_not_json"));
        }
        JsonArray data = body.has("data") && body.get("data").isJsonArray()
                ? body.getAsJsonArray("data") : new JsonArray();
        List<AiModelDescriptor> models = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            String id = stringOf(element.getAsJsonObject(), "id");
            if (id.isEmpty()) continue;
            models.add(new AiModelDescriptor(id, "", AiContract.MAX_REQUEST_BYTES,
                    AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                    java.util.Collections.singletonList("chat.completions")));
            if (models.size() >= 500) break;
        }
        return AiModelListResult.ok(models);
    }

    @Override
    public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                          AiSignal signal) {
        if (signal != null) signal.throwIfAborted();
        if (baseUrl.isEmpty()) return AiProviderResult.failed(AiProviderFailure.protocol("no_endpoint"));
        String key = secret();
        if (key.isEmpty()) return AiProviderResult.failed(AiProviderFailure.auth());
        if (config == null || config.model == null || config.model.name.isEmpty()) {
            return AiProviderResult.failed(AiProviderFailure.modelUnavailable());
        }

        boolean offeredSchema = !isDeepSeek(config)
                && SCHEMA_SUPPORT.computeIfAbsent(baseUrl, endpoint -> true);
        WireShape shape = WireShape.forSchema(offeredSchema);
        AiHttp.Result result = transport.postJson(baseUrl + "/chat/completions", headers(key),
                bodyOf(request, config, shape), signal, AiContract.MAX_RESPONSE_BYTES);

        // Two bounded renegotiation rounds, each driven by what the endpoint's 400 body names.
        // Both rejections are pre-dispatch, so neither can bill; a body that names nothing
        // actionable ends the loop rather than buying a third look.
        int rounds = 0;
        while (!result.ok() && result.status == 400 && !result.body.isEmpty() && rounds < 2) {
            WireShape adjusted = WireShape.negotiated(result.body, shape);
            if (adjusted.equals(shape)) break;
            shape = adjusted;
            rounds++;
            result = transport.postJson(baseUrl + "/chat/completions", headers(key),
                    bodyOf(request, config, shape), signal, AiContract.MAX_RESPONSE_BYTES);
        }
        if (offeredSchema && !shape.useJsonSchema) {
            // The endpoint refused schema enforcement outright; remember the downgrade so later
            // calls skip straight to json_object instead of paying the round trip again.
            SCHEMA_SUPPORT.put(baseUrl, false);
        } else if (result.ok()) {
            SCHEMA_SUPPORT.put(baseUrl, shape.useJsonSchema);
        }
        // Any other failure is inconclusive about schema support: leave the memo alone.
        if (!result.ok()) return AiProviderResult.failed(failureOf(result));

        JsonObject body = objectOf(result.body);
        if (body == null) return AiProviderResult.failed(AiProviderFailure.protocol("not_json"));
        JsonObject choice = firstChoice(body);
        if (choice == null) return AiProviderResult.failed(AiProviderFailure.protocol("no_choice"));

        String text = "";
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            text = contentOf(choice.getAsJsonObject("message"));
        }
        return AiProviderResult.ok(text, usageOf(body), finishOf(stringOf(choice, "finish_reason")),
                result.bytes);
    }

    @Override
    public String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
        boolean schema = !isDeepSeek(config)
                && SCHEMA_SUPPORT.computeIfAbsent(baseUrl, key -> true);
        return bodyOf(request, config, WireShape.forSchema(schema));
    }

    private static String bodyOf(AiProviderRequest request, AiProviderConfig config) {
        return bodyOf(request, config, WireShape.forSchema(false));
    }

    private static String bodyOf(AiProviderRequest request, AiProviderConfig config,
                                 WireShape shape) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", AiContract.buildSystemPrompt(config.layer, config.targetLang,
                config.repair, config.iteration, config.baselineRefinement)));
        messages.add(message("user", request.toJson()));

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model.name);
        body.add("messages", messages);
        if (shape.includeTemperature) body.addProperty("temperature", config.temperature);
        if (shape.useMaxCompletionTokens) {
            body.addProperty("max_completion_tokens", config.maxOutputTokens);
        } else {
            body.addProperty("max_tokens", config.maxOutputTokens);
        }
        body.add("response_format", responseFormat(shape));
        applyRoutedTraits(body, config);
        applyDeepSeekTraits(body, config);
        return body.toString();
    }

    /**
     * DeepSeek enables high-effort thinking by default. Lyrics benefit from bounded reasoning, but
     * not enough to justify buying the provider default; request low explicitly and read only the
     * final {@code content}, leaving {@code reasoning_content} out of the artifact.
     */
    private static void applyDeepSeekTraits(JsonObject body, AiProviderConfig config) {
        if (!isDeepSeek(config)) return;
        boolean disabled = AiText.nz(config.providerVersion).contains("+thinking=disabled");
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", disabled ? "disabled" : "enabled");
        body.add("thinking", thinking);
        if (!disabled) {
            body.addProperty("reasoning_effort", deepSeekReasoningEffort(config));
        }
    }

    private static String deepSeekReasoningEffort(AiProviderConfig config) {
        String version = config == null ? "" : AiText.nz(config.providerVersion);
        int marker = version.indexOf("+reasoning=");
        if (marker < 0) return "low";
        String effort = version.substring(marker + "+reasoning=".length());
        return "max".equals(effort) || "high".equals(effort) ? effort : "low";
    }

    private static boolean isDeepSeek(AiProviderConfig config) {
        return config != null
                && AiText.nz(config.providerVersion).startsWith(DEEPSEEK_PREFIX);
    }

    /**
     * Traits only a routing gateway understands, added when the configuration says we are talking
     * to one.
     *
     * <p>Read from {@code providerVersion} — the owner's explicit provider choice — and never from
     * the model name. A gateway serves hundreds of model names it does not own, so a name is
     * evidence about the model and none at all about the wire.
     *
     * <ul>
     *   <li>{@code reasoning.effort} normalizes what every upstream calls something different:
     *       {@code reasoning_effort}, {@code enable_thinking}, {@code thinking_budget},
     *       {@code thinkingLevel}. Asking for little of it is the point — see
     *       {@link AiSettings#OPENROUTER_REASONING_EFFORT}.</li>
     *   <li>{@code provider.require_parameters} routes only to upstreams that accept what this
     *       body carries, so a model that cannot honour the response format is excluded by routing
     *       rather than by failing a request that was already billed.</li>
     * </ul>
     */
    private static void applyRoutedTraits(JsonObject body, AiProviderConfig config) {
        String effort = routedReasoningEffort(config);
        if (effort.isEmpty()) return;

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        body.add("reasoning", reasoning);

        JsonObject routing = new JsonObject();
        routing.addProperty("require_parameters", true);
        body.add("provider", routing);
    }

    /** The requested reasoning effort for a routed endpoint, or empty when this is not one. */
    static String routedReasoningEffort(AiProviderConfig config) {
        String version = config == null ? "" : AiText.nz(config.providerVersion);
        int marker = version.indexOf(REASONING_MARKER);
        if (!version.startsWith(ROUTED_PREFIX) || marker < 0) return "";
        return version.substring(marker + REASONING_MARKER.length());
    }

    /**
     * {@code json_schema} where the endpoint takes it — the server then enforces the item shape
     * and no prompt has to ask for it — falling back to the permissive {@code json_object}.
     * Which one applies is learned from the endpoint's own rejections and remembered per
     * endpoint, never guessed from a model name.
     */
    private static JsonObject responseFormat(WireShape shape) {
        JsonObject responseFormat = new JsonObject();
        if (!shape.useJsonSchema) {
            responseFormat.addProperty("type", "json_object");
            return responseFormat;
        }
        responseFormat.addProperty("type", "json_schema");

        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject itemProperties = new JsonObject();
        itemProperties.add("id", simpleType("string"));
        itemProperties.add("t", simpleType("string"));
        item.add("properties", itemProperties);
        JsonArray requiredItem = new JsonArray();
        requiredItem.add("id");
        requiredItem.add("t");
        item.add("required", requiredItem);
        item.addProperty("additionalProperties", false);

        JsonArray items = new JsonArray();
        items.add(item);
        JsonObject schemaProperties = new JsonObject();
        JsonObject itemsProperty = new JsonObject();
        itemsProperty.addProperty("type", "array");
        itemsProperty.add("items", item);
        schemaProperties.add("items", itemsProperty);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", schemaProperties);
        JsonArray requiredRoot = new JsonArray();
        requiredRoot.add("items");
        schema.add("required", requiredRoot);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "lyric_items");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    private static JsonObject simpleType(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    /**
     * The wire shape one request used, and the next shape after an endpoint's rejection names a
     * parameter it will not take.
     */
    private static final class WireShape {
        /** What every endpoint is first offered: schema enforcement where it is supported. */
        static WireShape forSchema(boolean useJsonSchema) {
            return new WireShape(false, true, useJsonSchema);
        }

        /** Send the output cap as {@code max_completion_tokens} instead of {@code max_tokens}. */
        final boolean useMaxCompletionTokens;
        /** Whether {@code temperature} may be sent at all. */
        final boolean includeTemperature;
        /** Whether {@code response_format} asks for {@code json_schema} or plain {@code json_object}. */
        final boolean useJsonSchema;

        private WireShape(boolean useMaxCompletionTokens, boolean includeTemperature,
                          boolean useJsonSchema) {
            this.useMaxCompletionTokens = useMaxCompletionTokens;
            this.includeTemperature = includeTemperature;
            this.useJsonSchema = useJsonSchema;
        }

        /**
         * Reads the next negotiation step out of a 400 body, relative to the shape that was just
         * refused. Matched on what the endpoint said, not on the model name: OpenAI's o-series and
         * GPT-5 family answer with an "unsupported parameter ... use 'max_completion_tokens'"
         * message, reasoning endpoints elsewhere reject {@code temperature} in their own words,
         * and strict gateways reject unknown {@code response_format} types in theirs. A body that
         * names nothing actionable yields an equal shape — an unrelated rejection must not be
         * retried here, because above this adapter every retry is a paid attempt.
         */
        static WireShape negotiated(String errorBody, WireShape previous) {
            String text = AiText.nz(errorBody).toLowerCase(Locale.ROOT);
            boolean unsupported = text.contains("not supported") || text.contains("unsupported")
                    || text.contains("does not support");
            boolean temperatureRejected = text.contains("temperature") && unsupported;
            boolean schemaRejected = text.contains("json_schema")
                    || (text.contains("response_format") && unsupported);
            return new WireShape(
                    previous.useMaxCompletionTokens || text.contains("max_completion_tokens"),
                    previous.includeTemperature && !temperatureRejected,
                    previous.useJsonSchema && !schemaRejected);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof WireShape)) return false;
            WireShape that = (WireShape) other;
            return useMaxCompletionTokens == that.useMaxCompletionTokens
                    && includeTemperature == that.includeTemperature
                    && useJsonSchema == that.useJsonSchema;
        }

        @Override public int hashCode() {
            return (useMaxCompletionTokens ? 1 : 0) | (includeTemperature ? 2 : 0)
                    | (useJsonSchema ? 4 : 0);
        }
    }

    /** OpenAI-compatible servers increasingly return content blocks, not only one string. */
    private static String contentOf(JsonObject message) {
        if (message == null || !message.has("content")) return "";
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return content.isJsonObject() ? content.toString() : "";
        StringBuilder out = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonPrimitive()) {
                out.append(part.getAsString());
            } else if (part.isJsonObject()) {
                out.append(stringOf(part.getAsJsonObject(), "text"));
            }
        }
        return out.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject firstChoice(JsonObject body) {
        if (!body.has("choices") || !body.get("choices").isJsonArray()) return null;
        JsonArray choices = body.getAsJsonArray("choices");
        if (choices.size() == 0) return null;
        JsonElement first = choices.get(0);
        return first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    /** Closed mapping, as on the other adapter: an unknown reason is a refusal, not a success. */
    private static AiFinishReason finishOf(String reason) {
        String value = AiText.nz(reason).toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "stop".equals(value)) return AiFinishReason.STOP;
        if ("length".equals(value)) return AiFinishReason.LENGTH;
        if ("content_filter".equals(value)) return AiFinishReason.SAFETY;
        return AiFinishReason.OTHER;
    }

    private static AiUsage usageOf(JsonObject body) {
        if (!body.has("usage") || !body.get("usage").isJsonObject()) return AiUsage.UNREPORTED;
        JsonObject usage = body.getAsJsonObject("usage");
        int input = intOf(usage, "prompt_tokens");
        int output = intOf(usage, "completion_tokens");
        if (input <= 0 && output <= 0) return AiUsage.UNREPORTED;
        return AiUsage.of(input, output);
    }

    private String secret() {
        return credential == null ? "" : AiText.nz(credential.secret());
    }

    private static Map<String, String> headers(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("Accept", "application/json");
        return headers;
    }

    private static AiProviderFailure failureOf(AiHttp.Result result) {
        if (result.failure != null) return result.failure;
        int status = result.status;
        if (status == 401 || status == 403) return AiProviderFailure.auth();
        if (status == 402) return AiProviderFailure.quota();
        if (status == 429) return AiProviderFailure.rateLimited(result.retryAfterMs);
        if (status == 404) return AiProviderFailure.modelUnavailable();
        if (status >= 500) {
            return AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.SERVER, status);
        }
        if (status == 400 && result.body.contains("insufficient_quota")) {
            return AiProviderFailure.quota();
        }
        return AiProviderFailure.requestRejected(status);
    }

    private static JsonObject objectOf(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(AiText.nz(body));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String stringOf(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int intOf(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }
}
