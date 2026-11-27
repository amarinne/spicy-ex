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
    /**
     * Output caps an endpoint said a model accepts, keyed by endpoint and model. A compatible
     * {@code /models} carries no limits, so the contract ceiling is offered first and lowered
     * once to the figure the endpoint names.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> OUTPUT_CAP =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("\\d{3,7}");

    /**
     * The lower output cap a 400 body names for {@code max_tokens}, or 0 when it names none.
     * Reads the largest number below the cap that was sent, which covers "supports at most
     * 16384" and "valid range of max_tokens is [1, 8192]" alike.
     */
    static int lowerOutputCap(String errorBody, int sentCap) {
        String text = AiText.nz(errorBody).toLowerCase(Locale.ROOT);
        if (!text.contains("max_tokens") && !text.contains("max_completion_tokens")
                && !text.contains("output tokens") && !text.contains("completion tokens")
                && !text.contains("maxoutputtokens") && !text.contains("max_output_tokens")) {
            return 0;
        }
        int best = 0;
        java.util.regex.Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            int value;
            try {
                value = Integer.parseInt(numbers.group());
            } catch (NumberFormatException tooLarge) {
                continue;
            }
            if (value >= 256 && value < sentCap && value > best) best = value;
        }
        return best;
    }

    /**
     * Streaming level an endpoint accepted: 2 streams with usage, 1 streams without
     * {@code stream_options}, 0 does not stream. Optimistic until an endpoint's 400 says otherwise.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> STREAM_SUPPORT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Memo of endpoints that refused {@code include_reasoning}; asked until told otherwise. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> REASONING_SUPPORT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Memo of endpoints that refused {@code thinking}; sent for custom Reasoning: Off until told otherwise. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> THINKING_SUPPORT =
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
            JsonObject entry = element.getAsJsonObject();
            String id = stringOf(entry, "id");
            if (id.isEmpty()) continue;
            models.add(new AiModelDescriptor(id, "", AiContract.MAX_REQUEST_BYTES,
                    AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                    java.util.Collections.singletonList("chat.completions"), -1,
                    stringOf(entry, "owned_by")));
            if (models.size() >= 500) break;
        }
        return AiModelListResult.ok(models);
    }

    @Override
    public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                          AiSignal signal) {
        return generateChunk(request, config, signal, null);
    }

    @Override
    public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                          AiSignal signal, StreamListener listener) {
        if (signal != null) signal.throwIfAborted();
        if (baseUrl.isEmpty()) return AiProviderResult.failed(AiProviderFailure.protocol("no_endpoint"));
        String key = secret();
        if (key.isEmpty()) return AiProviderResult.failed(AiProviderFailure.auth());
        if (config == null || config.model == null || config.model.name.isEmpty()) {
            return AiProviderResult.failed(AiProviderFailure.modelUnavailable());
        }

        boolean structured = !AiTextWire.usesText(config.layer);
        boolean offeredSchema = structured && !isDeepSeek(config)
                && SCHEMA_SUPPORT.computeIfAbsent(baseUrl, endpoint -> true);
        boolean offeredReasoning = asksForReasoningTrace(config)
                && REASONING_SUPPORT.computeIfAbsent(baseUrl, endpoint -> true);
        boolean offeredThinkingDisabled = customDisablesThinking(config)
                && THINKING_SUPPORT.computeIfAbsent(baseUrl, endpoint -> true);
        WireShape shape = WireShape.forSchema(offeredSchema).withReasoning(offeredReasoning)
                .withThinkingDisabled(offeredThinkingDisabled)
                .withStream(STREAM_SUPPORT.getOrDefault(baseUrl, 2));
        String capKey = baseUrl + "|" + config.model.name;
        Integer knownCap = OUTPUT_CAP.get(capKey);
        if (knownCap != null && knownCap < config.maxOutputTokens) {
            config = config.forCall(config.repair, knownCap);
        }
        StreamAccumulator stream = new StreamAccumulator(listener);
        AiHttp.Result result = post(key, request, config, shape, signal, stream);

        // Bounded renegotiation rounds, each driven by what the endpoint's 400 body names. Every
        // rejection is pre-dispatch, so none can bill; a body that names nothing actionable ends
        // the loop rather than buying another look. One round per negotiable trait at most.
        int rounds = 0;
        while (!result.ok() && result.status == 400 && !result.body.isEmpty() && rounds < 6) {
            WireShape adjusted = WireShape.negotiated(result.body, shape);
            int lowered = lowerOutputCap(result.body, config.maxOutputTokens);
            if (adjusted.equals(shape) && lowered <= 0) break;
            shape = adjusted;
            if (lowered > 0) {
                config = config.forCall(config.repair, lowered);
                OUTPUT_CAP.put(capKey, lowered);
            }
            rounds++;
            stream = new StreamAccumulator(listener);
            result = post(key, request, config, shape, signal, stream);
        }
        if (shape.streamLevel() < STREAM_SUPPORT.getOrDefault(baseUrl, 2)) {
            STREAM_SUPPORT.put(baseUrl, shape.streamLevel());
        }
        if (!structured) {
            // The text wire asks for no response format, so this call says nothing about schema.
        } else if (offeredSchema && !shape.useJsonSchema) {
            // The endpoint refused schema enforcement outright; remember the downgrade so later
            // calls skip straight to json_object instead of paying the round trip again.
            SCHEMA_SUPPORT.put(baseUrl, false);
        } else if (result.ok()) {
            SCHEMA_SUPPORT.put(baseUrl, shape.useJsonSchema);
        }
        // Any other failure is inconclusive about schema support: leave the memo alone.
        if (offeredReasoning && !shape.includeReasoning) REASONING_SUPPORT.put(baseUrl, false);
        if (offeredThinkingDisabled && !shape.includeThinkingDisabled) THINKING_SUPPORT.put(baseUrl, false);
        if (!result.ok()) return AiProviderResult.failed(failureOf(result));
        com.eza.spicyex.xposed.XpLog.log("[SpotifyPlusAiStream] streamed=" + result.streamed
                + " " + stream.summary());
        if (result.streamed) return stream.result(result.bytes);

        JsonObject body = objectOf(result.body);
        if (body == null) return AiProviderResult.failed(AiProviderFailure.protocol("not_json"));
        JsonObject choice = firstChoice(body);
        if (choice == null) return AiProviderResult.failed(AiProviderFailure.protocol("no_choice"));

        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        String text = message == null ? "" : contentOf(message);
        return AiProviderResult.ok(text, reasoningOf(message), usageOf(body),
                finishOf(stringOf(choice, "finish_reason")), result.bytes);
    }

    private AiHttp.Result post(String key, AiProviderRequest request, AiProviderConfig config,
                               WireShape shape, AiSignal signal, StreamAccumulator stream) {
        String url = baseUrl + "/chat/completions";
        String body = bodyOf(request, config, shape);
        return shape.stream
                ? transport.postJsonStream(url, headers(key), body, signal,
                        AiContract.MAX_RESPONSE_BODY_BYTES, stream)
                : transport.postJson(url, headers(key), body, signal,
                        AiContract.MAX_RESPONSE_BODY_BYTES);
    }

    /**
     * Folds a chat-completions event stream into the result an ordinary body would have given.
     *
     * <p>Content is bounded by {@link AiContract#MAX_RESPONSE_BYTES}, as a buffered body is; a
     * trace past its share of {@link AiContract#MAX_RESPONSE_BODY_BYTES} is cut for display rather
     * than failing a good answer. A stream that ends without a finish reason was cut off, which
     * is a delivery failure, not an answer.
     */
    private static final class StreamAccumulator implements AiHttp.StreamSink {
        private static final int REASONING_LIMIT =
                AiContract.MAX_RESPONSE_BODY_BYTES - AiContract.MAX_RESPONSE_BYTES;
        private final StreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private String finish = "";
        private JsonObject usage;
        private boolean oversized;
        private long textBytes;
        private int notifiedReasoning;

        private final long startedAtMs = System.currentTimeMillis();
        private long firstEventMs = -1L;
        private long firstContentMs = -1L;
        private long lastContentMs = -1L;
        private int events;
        private int lineNotices;

        StreamAccumulator(StreamListener listener) {
            this.listener = listener;
        }

        /** Content-free timing for the module log: is the endpoint really streaming? */
        String summary() {
            return "events=" + events + " firstEventMs=" + firstEventMs
                    + " firstContentMs=" + firstContentMs + " lastContentMs=" + lastContentMs
                    + " lineNotices=" + lineNotices + " textChars=" + text.length()
                    + " reasoningChars=" + reasoning.length();
        }

        @Override public void onData(String data) {
            long now = System.currentTimeMillis() - startedAtMs;
            events++;
            if (firstEventMs < 0) firstEventMs = now;
            JsonObject event = objectOf(data);
            if (event == null) return;
            if (event.has("usage") && event.get("usage").isJsonObject()) usage = event;
            JsonObject choice = firstChoice(event);
            if (choice == null) return;
            String reason = stringOf(choice, "finish_reason");
            if (!reason.isEmpty()) finish = reason;
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                    ? choice.getAsJsonObject("delta") : null;
            if (delta == null) return;
            String piece = contentOf(delta);
            if (!piece.isEmpty() && !oversized) {
                if (firstContentMs < 0) firstContentMs = now;
                lastContentMs = now;
                text.append(piece);
                textBytes += AiText.utf8Bytes(piece);
                if (textBytes > AiContract.MAX_RESPONSE_BYTES) {
                    oversized = true;
                } else if (listener != null && (piece.indexOf('\n') >= 0 || piece.indexOf('}') >= 0)) {
                    // Only a finished line (Meaning text) or JSON item (Sound) is worth parsing.
                    lineNotices++;
                    listener.onText(text.toString());
                }
            }
            String thought = reasoningOf(delta);
            if (!thought.isEmpty() && reasoning.length() < REASONING_LIMIT) {
                reasoning.append(thought);
                if (listener != null && reasoning.length() - notifiedReasoning >= 512) {
                    notifiedReasoning = reasoning.length();
                    listener.onReasoning(reasoning.toString());
                }
            }
        }

        AiProviderResult result(long bytes) {
            if (oversized) return AiProviderResult.failed(AiProviderFailure.oversized(bytes));
            if (finish.isEmpty()) {
                return AiProviderResult.failed(AiProviderFailure.deliveryUnknown(
                        AiProviderFailure.Cause.NETWORK, 0).withHttp(0, "stream_ended"));
            }
            return AiProviderResult.ok(text.toString(), reasoning.toString(),
                    usage == null ? usageOf(new JsonObject()) : usageOf(usage),
                    finishOf(finish), bytes);
        }
    }

    @Override
    public String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
        boolean schema = !isDeepSeek(config)
                && SCHEMA_SUPPORT.computeIfAbsent(baseUrl, key -> true);
        boolean reasoning = asksForReasoningTrace(config)
                && REASONING_SUPPORT.computeIfAbsent(baseUrl, key -> true);
        boolean thinkingDisabled = customDisablesThinking(config)
                && THINKING_SUPPORT.computeIfAbsent(baseUrl, key -> true);
        return bodyOf(request, config, WireShape.forSchema(schema).withReasoning(reasoning)
                .withThinkingDisabled(thinkingDisabled)
                .withStream(STREAM_SUPPORT.getOrDefault(baseUrl, 2)));
    }

    /**
     * Whether to ask a custom OpenAI-compatible gateway for the reasoning trace.
     *
     * <p>Gateways such as CLIProxy return a thinking model's trace only when the request asks for
     * it; {@code include_reasoning: true} asks without changing how much the model thinks. Without
     * it a Gemini alias reasoned for ~3k tokens on 2026-11-19 and returned no trace. Official
     * OpenAI rejects the field, and DeepSeek and OpenRouter have their own reasoning controls, so
     * only the plain compatible profile on a non-OpenAI host asks — and never when the owner
     * explicitly selected Reasoning: Off. Display only: not identity.
     */
    private boolean asksForReasoningTrace(AiProviderConfig config) {
        if (config == null || !AiText.nz(config.providerVersion).startsWith("openai-v1")) {
            return false;
        }
        if (customDisablesThinking(config)) return false;
        return !baseUrl.toLowerCase(Locale.ROOT).contains("://api.openai.com");
    }

    private static boolean customDisablesThinking(AiProviderConfig config) {
        return "none".equals(gatewayReasoningEffort(config));
    }

    private static String bodyOf(AiProviderRequest request, AiProviderConfig config) {
        return bodyOf(request, config, WireShape.forSchema(false));
    }

    private static String bodyOf(AiProviderRequest request, AiProviderConfig config,
                                 WireShape shape) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", config.systemPrompt()));
        messages.add(message("user", AiTextWire.userTurn(request, config.layer)));

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model.name);
        body.add("messages", messages);
        if (shape.includeTemperature) body.addProperty("temperature", config.temperature);
        if (shape.useMaxCompletionTokens) {
            body.addProperty("max_completion_tokens", config.maxOutputTokens);
        } else {
            body.addProperty("max_tokens", config.maxOutputTokens);
        }
        if (!AiTextWire.usesText(config.layer)) body.add("response_format", responseFormat(shape));
        if (shape.includeReasoning) body.addProperty("include_reasoning", true);
        if (shape.stream) {
            body.addProperty("stream", true);
            if (shape.streamUsage) {
                JsonObject options = new JsonObject();
                options.addProperty("include_usage", true);
                body.add("stream_options", options);
            }
        }
        String effort = shape.includeEffort ? gatewayReasoningEffort(config) : "";
        if (!effort.isEmpty()) body.addProperty("reasoning_effort", effort);
        if (shape.includeThinkingDisabled && customDisablesThinking(config)) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        applyRoutedTraits(body, config);
        applyDeepSeekTraits(body, config);
        return body.toString();
    }

    /**
     * DeepSeek enables high-effort thinking by default. Lyrics benefit from bounded reasoning, but
     * not enough to justify buying the provider default; request low explicitly. Only the final
     * {@code content} becomes the artifact — {@code reasoning_content} is read separately, for the
     * monitor, and never reaches the reader or a lyric row.
     */
    /**
     * The OpenAI-standard {@code reasoning_effort} for a custom gateway, or empty. Off maps to
     * {@code none}; Max maps to {@code high}, the highest value the standard defines.
     */
    static String gatewayReasoningEffort(AiProviderConfig config) {
        String version = config == null ? "" : AiText.nz(config.providerVersion);
        String prefix = AiSettings.CUSTOM_LEGACY_PROVIDER_VERSION + "+reasoning=";
        if (!version.startsWith(prefix)) return "";
        switch (version.substring(prefix.length())) {
            case "off": return "none";
            case "low": return "low";
            case "high":
            case "max": return "high";
            default: return "";
        }
    }

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
            return new WireShape(false, true, useJsonSchema, false, true, false, true, true);
        }

        WireShape withReasoning(boolean include) {
            return new WireShape(useMaxCompletionTokens, includeTemperature, useJsonSchema,
                    include, includeEffort, includeThinkingDisabled, stream, streamUsage);
        }

        WireShape withThinkingDisabled(boolean include) {
            return new WireShape(useMaxCompletionTokens, includeTemperature, useJsonSchema,
                    includeReasoning, includeEffort, include, stream, streamUsage);
        }

        /** Applies a remembered streaming level: 2 full, 1 without usage, 0 none. */
        WireShape withStream(int level) {
            return new WireShape(useMaxCompletionTokens, includeTemperature, useJsonSchema,
                    includeReasoning, includeEffort, includeThinkingDisabled, level >= 1, level >= 2);
        }

        int streamLevel() {
            return !stream ? 0 : streamUsage ? 2 : 1;
        }

        /** Send the output cap as {@code max_completion_tokens} instead of {@code max_tokens}. */
        final boolean useMaxCompletionTokens;
        /** Whether {@code temperature} may be sent at all. */
        final boolean includeTemperature;
        /** Whether {@code response_format} asks for {@code json_schema} or plain {@code json_object}. */
        final boolean useJsonSchema;
        /** Whether {@code include_reasoning: true} asks a gateway for the reasoning trace. */
        final boolean includeReasoning;
        /** Whether a configured gateway {@code reasoning_effort} may be sent. */
        final boolean includeEffort;
        /** Whether a custom gateway with Reasoning: Off may send {@code thinking: {type: disabled}}. */
        final boolean includeThinkingDisabled;
        /** Whether to ask for a server-sent event stream. */
        final boolean stream;
        /** Whether to ask for usage in the stream's last event ({@code stream_options}). */
        final boolean streamUsage;

        private WireShape(boolean useMaxCompletionTokens, boolean includeTemperature,
                          boolean useJsonSchema, boolean includeReasoning,
                          boolean includeEffort, boolean includeThinkingDisabled,
                          boolean stream, boolean streamUsage) {
            this.useMaxCompletionTokens = useMaxCompletionTokens;
            this.includeTemperature = includeTemperature;
            this.useJsonSchema = useJsonSchema;
            this.includeReasoning = includeReasoning;
            this.includeEffort = includeEffort;
            this.includeThinkingDisabled = includeThinkingDisabled;
            this.stream = stream;
            this.streamUsage = stream && streamUsage;
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
        /** "stream" as its own word: "upstream" in a gateway's error must not disable it. */
        private static final java.util.regex.Pattern STREAM_WORD =
                java.util.regex.Pattern.compile("(?<![a-z_])stream(?![a-z_])");
        private static final java.util.regex.Pattern THINKING_WORD =
                java.util.regex.Pattern.compile("(?<![a-z_])thinking(?![a-z_])");

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
                    previous.useJsonSchema && !schemaRejected,
                    previous.includeReasoning && !text.contains("include_reasoning"),
                    previous.includeEffort && !text.contains("reasoning_effort"),
                    previous.includeThinkingDisabled && !THINKING_WORD.matcher(text).find(),
                    previous.stream && !STREAM_WORD.matcher(text).find(),
                    previous.streamUsage && !text.contains("stream_options")
                            && !STREAM_WORD.matcher(text).find());
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof WireShape)) return false;
            WireShape that = (WireShape) other;
            return useMaxCompletionTokens == that.useMaxCompletionTokens
                    && includeTemperature == that.includeTemperature
                    && useJsonSchema == that.useJsonSchema
                    && includeReasoning == that.includeReasoning
                    && includeEffort == that.includeEffort
                    && includeThinkingDisabled == that.includeThinkingDisabled
                    && stream == that.stream && streamUsage == that.streamUsage;
        }

        @Override public int hashCode() {
            return (useMaxCompletionTokens ? 1 : 0) | (includeTemperature ? 2 : 0)
                    | (useJsonSchema ? 4 : 0) | (includeReasoning ? 8 : 0)
                    | (includeEffort ? 16 : 0) | (includeThinkingDisabled ? 32 : 0)
                    | (stream ? 64 : 0) | (streamUsage ? 128 : 0);
        }
    }

    /**
     * The reasoning trace a thinking model returned alongside its answer, if any.
     *
     * <p>Two field names, because the wire never settled on one: DeepSeek answers on
     * {@code reasoning_content}, routed gateways on {@code reasoning}. Both are already being paid
     * for — this adapter asks for bounded reasoning on both profiles — so the only question was
     * whether the owner ever gets to see it. Read defensively and never parsed: a gateway that
     * returns blocks, an object, or nothing at all must not turn a good answer into a failure.
     */
    private static String reasoningOf(JsonObject message) {
        if (message == null) return "";
        String explicit = blockTextOf(message.get("reasoning_content"));
        return explicit.isEmpty() ? blockTextOf(message.get("reasoning")) : explicit;
    }

    /** A field that may be one string, an array of blocks, or a structured object. */
    private static String blockTextOf(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonObject()) {
            String text = stringOf(value.getAsJsonObject(), "text");
            return text.isEmpty() ? value.toString() : text;
        }
        if (!value.isJsonArray()) return "";
        StringBuilder out = new StringBuilder();
        for (JsonElement part : value.getAsJsonArray()) {
            String text = blockTextOf(part);
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
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
        String token = AiProviderFailure.errorToken(result.body);
        return classify(status, token, result).withHttp(status, token);
    }

    private static AiProviderFailure classify(int status, String token, AiHttp.Result result) {
        if (status == 401 || status == 403) return AiProviderFailure.auth();
        if (status == 402) return AiProviderFailure.quota();
        if (status == 429) return AiProviderFailure.rateLimited(result.retryAfterMs);
        // 503 and 529 say the server is not taking work now: nothing was generated, so they are
        // a busy signal to wait out, not an unknown delivery. Other 5xx may follow generation.
        if (status == 503 || status == 529) {
            return AiProviderFailure.rateLimited(result.retryAfterMs);
        }
        if (status == 404 || "model_not_found".equals(token)) {
            return AiProviderFailure.modelUnavailable();
        }
        if (status >= 500) {
            return AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.SERVER, status);
        }
        String lower = result.body.toLowerCase(java.util.Locale.ROOT);
        if (status == 400 && (lower.contains("insufficient_quota")
                || lower.contains("insufficient credits"))) {
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
