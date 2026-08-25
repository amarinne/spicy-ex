package com.eza.spicyex.lyrics.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini, over its REST API.
 *
 * <p>The adapter owns transport and shape translation, nothing else. It does not retry, count
 * attempts, strip fences, or validate items: those are the runtime's and the shared reader's, so
 * every provider gets identical treatment and no adapter can quietly double a bill.
 *
 * <p>The key travels in the {@code x-goog-api-key} header and never in the URL. Query strings reach
 * logs, crash reports, and proxy access logs; a header does not, and this is the one difference
 * that is actually free.
 */
public final class AiGeminiProvider implements AiProvider {

    public static final String ID = "gemini";
    /** Suggested when discovery returns it. Never auto-selected — the owner chooses. */
    public static final String SUGGESTED_MODEL = "gemini-3.1-flash-lite";

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final int MAX_DISCOVERY_PAGES = 10;
    private static final int MAX_DISCOVERY_MODELS = 500;

    /** Model families that cannot answer a structured-JSON lyric request, whatever their limits. */
    private static final String[] EXCLUDED_FRAGMENTS = {
            "embedding", "embed", "aqa", "imagen", "image-generation", "vision", "tts",
            "text-to-speech", "robotics", "computer-use", "veo",
    };

    private final Transport transport;
    private final CredentialSource credential;

    /** The network seam, so every branch below is testable without a network or a key. */
    public interface Transport {
        AiHttp.Result get(String url, Map<String, String> headers, AiSignal signal, int maxBytes);

        AiHttp.Result postJson(String url, Map<String, String> headers, String json,
                               AiSignal signal, int maxBytes);
    }

    /** Reads the key at call time, so a rotation takes effect without rebuilding the provider. */
    public interface CredentialSource {
        String secret();
    }

    public AiGeminiProvider(CredentialSource credential) {
        this(credential, new Transport() {
            @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                               AiSignal signal, int maxBytes) {
                return AiHttp.get(url, headers, signal, maxBytes);
            }

            @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                    String json, AiSignal signal, int maxBytes) {
                return AiHttp.postJson(url, headers, json, signal, maxBytes);
            }
        });
    }

    public AiGeminiProvider(CredentialSource credential, Transport transport) {
        this.credential = credential;
        this.transport = transport;
    }

    @Override public String id() {
        return ID;
    }

    // --- discovery ----------------------------------------------------------

    @Override
    public AiModelListResult listModels(AiSignal signal) {
        String key = secret();
        if (key.isEmpty()) return AiModelListResult.failed(AiProviderFailure.auth());

        List<AiModelDescriptor> usable = new ArrayList<>();
        String pageToken = "";
        int raw = 0;
        for (int page = 0; page < MAX_DISCOVERY_PAGES; page++) {
            if (signal != null) signal.throwIfAborted();
            String url = BASE_URL + "/models?pageSize=100"
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + pageToken);
            AiHttp.Result result = transport.get(url, headers(key), signal,
                    AiContract.MAX_MODEL_LIST_BYTES);
            if (!result.ok()) return AiModelListResult.failed(failureOf(result));

            JsonObject body = objectOf(result.body);
            if (body == null) {
                return AiModelListResult.failed(AiProviderFailure.protocol("discovery_not_json"));
            }
            JsonArray models = body.has("models") && body.get("models").isJsonArray()
                    ? body.getAsJsonArray("models") : new JsonArray();
            for (JsonElement element : models) {
                if (++raw > MAX_DISCOVERY_MODELS) {
                    // A silently truncated list would leave the owner picking from models that are
                    // there while a model they use is missing, with nothing to explain it.
                    return AiModelListResult.failed(AiProviderFailure.protocol("discovery_too_many"));
                }
                AiModelDescriptor descriptor = descriptorOf(element);
                if (descriptor != null) usable.add(descriptor);
            }
            pageToken = stringOf(body, "nextPageToken");
            if (pageToken.isEmpty()) return AiModelListResult.ok(usable);
        }
        return AiModelListResult.failed(AiProviderFailure.protocol("discovery_too_many_pages"));
    }

    /** @return the model, or null when it cannot serve a structured-JSON lyric request */
    private static AiModelDescriptor descriptorOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject model = element.getAsJsonObject();

        String name = stringOf(model, "name");
        if (name.startsWith("models/")) name = name.substring("models/".length());
        if (name.isEmpty()) return null;

        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : EXCLUDED_FRAGMENTS) if (lower.contains(fragment)) return null;

        List<String> methods = new ArrayList<>();
        if (model.has("supportedGenerationMethods")
                && model.get("supportedGenerationMethods").isJsonArray()) {
            for (JsonElement method : model.getAsJsonArray("supportedGenerationMethods")) {
                if (method.isJsonPrimitive()) methods.add(method.getAsString());
            }
        }
        if (!methods.contains("generateContent")) return null;

        int outputLimit = intOf(model, "outputTokenLimit");
        int inputLimit = intOf(model, "inputTokenLimit");
        // A zero limit is not a small model, it is a model this API cannot tell us how to use.
        if (outputLimit <= 0 || inputLimit <= 0) return null;

        return new AiModelDescriptor(name, stringOf(model, "version"), inputLimit, outputLimit,
                methods);
    }

    // --- generation ---------------------------------------------------------

    @Override
    public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                          AiSignal signal) {
        if (signal != null) signal.throwIfAborted();
        String key = secret();
        if (key.isEmpty()) return AiProviderResult.failed(AiProviderFailure.auth());
        if (config == null || config.model == null || config.model.name.isEmpty()) {
            return AiProviderResult.failed(AiProviderFailure.modelUnavailable());
        }

        String url = BASE_URL + "/models/" + config.model.name + ":generateContent";
        AiHttp.Result result = transport.postJson(url, headers(key),
                bodyOf(request, config), signal, AiContract.MAX_RESPONSE_BYTES);
        if (!result.ok()) return AiProviderResult.failed(failureOf(result));

        JsonObject body = objectOf(result.body);
        if (body == null) return AiProviderResult.failed(AiProviderFailure.protocol("not_json"));

        JsonObject candidate = firstCandidate(body);
        if (candidate == null) {
            // No candidate at all is a refusal: a prompt-level block returns promptFeedback and
            // nothing else. Reading it as an empty answer would show the owner a blank document.
            return AiProviderResult.failed(AiProviderFailure.protocol("no_candidate"));
        }

        AiFinishReason finish = finishOf(stringOf(candidate, "finishReason"));
        return AiProviderResult.ok(partsOf(candidate, false), partsOf(candidate, true),
                usageOf(body), finish, result.bytes);
    }

    @Override
    public String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
        return bodyOf(request, config);
    }

    /**
     * The request body.
     *
     * <p>{@code responseMimeType} asks for JSON at the API level rather than trusting the prompt
     * alone, and temperature is pinned at zero because output configuration is part of cache
     * identity: the same question must not quietly become a different one.
     */
    private static String bodyOf(AiProviderRequest request, AiProviderConfig config) {
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", AiContract.buildSystemPrompt(config.layer,
                config.targetLang, config.repair, config.iteration,
                config.baselineRefinement));
        JsonArray systemParts = new JsonArray();
        systemParts.add(systemPart);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemParts);

        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", request.toJson());
        JsonArray userParts = new JsonArray();
        userParts.add(userPart);
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        content.add("parts", userParts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", config.temperature);
        generationConfig.addProperty("maxOutputTokens", config.maxOutputTokens);
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema());

        JsonObject body = new JsonObject();
        body.add("systemInstruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return body.toString();
    }

    /** Keep the wire contract machine-enforced, matching the desktop Gemini adapter. */
    private static JsonObject responseSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "STRING");
        JsonObject text = new JsonObject();
        text.addProperty("type", "STRING");
        properties.add("id", id);
        properties.add("t", text);
        item.add("properties", properties);
        JsonArray requiredItem = new JsonArray();
        requiredItem.add("id");
        requiredItem.add("t");
        item.add("required", requiredItem);

        JsonObject array = new JsonObject();
        array.addProperty("type", "ARRAY");
        array.add("items", item);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject rootProperties = new JsonObject();
        rootProperties.add("items", array);
        schema.add("properties", rootProperties);
        JsonArray requiredRoot = new JsonArray();
        requiredRoot.add("items");
        schema.add("required", requiredRoot);
        return schema;
    }

    private static JsonObject firstCandidate(JsonObject body) {
        if (!body.has("candidates") || !body.get("candidates").isJsonArray()) return null;
        JsonArray candidates = body.getAsJsonArray("candidates");
        if (candidates.size() == 0) return null;
        JsonElement first = candidates.get(0);
        return first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    /**
     * Concatenates the candidate's parts on one side of the {@code thought} flag; the API may split
     * one answer across several.
     *
     * <p>The two sides are read separately rather than joined because they are different kinds of
     * text. A thought summary is prose, the answer is the JSON document the reader parses strictly,
     * and appending the first to the second produced {@code invalid_json} for an answer the model
     * got right. Splitting on the flag the API already sets costs nothing when no thoughts are
     * requested — every part is then an answer part — and keeps the trace available when they are.
     */
    private static String partsOf(JsonObject candidate, boolean thoughts) {
        if (!candidate.has("content") || !candidate.get("content").isJsonObject()) return "";
        JsonObject content = candidate.getAsJsonObject("content");
        if (!content.has("parts") || !content.get("parts").isJsonArray()) return "";
        StringBuilder out = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray("parts")) {
            if (!part.isJsonObject()) continue;
            JsonObject object = part.getAsJsonObject();
            if (isThought(object) != thoughts) continue;
            out.append(stringOf(object, "text"));
        }
        return out.toString();
    }

    private static boolean isThought(JsonObject part) {
        JsonElement thought = part.get("thought");
        try {
            return thought != null && thought.isJsonPrimitive() && thought.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return false;
        }
    }

    /**
     * Finish mapping, closed by design.
     *
     * <p>Only {@code STOP} proceeds to validation. Everything else is a distinct outcome the owner
     * may need told apart — a truncated answer is worth retrying with a shorter document, a refusal
     * is not — and an unrecognised value is treated as a refusal rather than optimistically parsed.
     */
    private static AiFinishReason finishOf(String reason) {
        String value = AiText.nz(reason).toUpperCase(Locale.ROOT);
        if (value.isEmpty() || "STOP".equals(value)) return AiFinishReason.STOP;
        if ("MAX_TOKENS".equals(value)) return AiFinishReason.LENGTH;
        if ("SAFETY".equals(value) || "RECITATION".equals(value) || "BLOCKLIST".equals(value)
                || "PROHIBITED_CONTENT".equals(value) || "SPII".equals(value)) {
            return AiFinishReason.SAFETY;
        }
        return AiFinishReason.OTHER;
    }

    private static AiUsage usageOf(JsonObject body) {
        if (!body.has("usageMetadata") || !body.get("usageMetadata").isJsonObject()) {
            return AiUsage.UNREPORTED;
        }
        JsonObject usage = body.getAsJsonObject("usageMetadata");
        int input = intOf(usage, "promptTokenCount");
        int output = intOf(usage, "candidatesTokenCount");
        if (input <= 0 && output <= 0) return AiUsage.UNREPORTED;
        return AiUsage.of(input, output);
    }

    // --- shared -------------------------------------------------------------

    private String secret() {
        return credential == null ? "" : AiText.nz(credential.secret());
    }

    private static Map<String, String> headers(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", key);
        headers.put("Accept", "application/json");
        return headers;
    }

    /** Maps a transport outcome onto the typed failures the runtime knows how to treat. */
    private static AiProviderFailure failureOf(AiHttp.Result result) {
        if (result.failure != null) return result.failure;
        int status = result.status;
        if (status == 401 || status == 403) return AiProviderFailure.auth();
        if (status == 429) return AiProviderFailure.rateLimited(result.retryAfterMs);
        if (status == 404) return AiProviderFailure.modelUnavailable();
        // Quota exhaustion arrives as a 400 with a specific reason, and it is terminal: retrying it
        // burns attempts against a wall.
        if (status == 400 && result.body.contains("RESOURCE_EXHAUSTED")) {
            return AiProviderFailure.quota();
        }
        if (status >= 500) {
            return AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.SERVER, status);
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

    /** Discovery order with the suggested model first, when the account has it. */
    public static List<AiModelDescriptor> withSuggestedFirst(List<AiModelDescriptor> models) {
        if (models == null || models.isEmpty()) return Collections.emptyList();
        List<AiModelDescriptor> ordered = new ArrayList<>(models.size());
        for (AiModelDescriptor model : models) {
            if (SUGGESTED_MODEL.equals(model.name)) ordered.add(model);
        }
        for (AiModelDescriptor model : models) {
            if (!SUGGESTED_MODEL.equals(model.name)) ordered.add(model);
        }
        return ordered;
    }
}
