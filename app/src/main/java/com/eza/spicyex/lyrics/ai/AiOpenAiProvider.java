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
 */
public final class AiOpenAiProvider implements AiProvider {

    public static final String ID = "openai";

    private final AiGeminiProvider.Transport transport;
    private final AiGeminiProvider.CredentialSource credential;
    private final String baseUrl;

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
                AiContract.MAX_RESPONSE_BYTES);
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

        AiHttp.Result result = transport.postJson(baseUrl + "/chat/completions", headers(key),
                bodyOf(request, config), signal, AiContract.MAX_RESPONSE_BYTES);
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
        return bodyOf(request, config);
    }

    private static String bodyOf(AiProviderRequest request, AiProviderConfig config) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", AiContract.buildSystemPrompt(config.layer, config.targetLang,
                config.repair, config.iteration, config.baselineRefinement)));
        messages.add(message("user", request.toJson()));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model.name);
        body.add("messages", messages);
        body.addProperty("temperature", config.temperature);
        body.addProperty("max_tokens", config.maxOutputTokens);
        body.add("response_format", responseFormat);
        return body.toString();
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
