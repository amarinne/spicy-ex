package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.BufferedSource;

/** Explicit CLI evaluation tool. It never runs as a JUnit test and prints metadata only. */
public final class AiHarnessModelComparisonTool {
    private static final class Fixture {
        final String name, target;
        final LayerKind layer;
        final boolean refinement;
        final List<AiLine> rows = new ArrayList<>();
        final Map<String, String[]> words = new LinkedHashMap<>();
        Fixture(String name, LayerKind layer, String target, boolean refinement) {
            this.name = name; this.layer = layer; this.target = target; this.refinement = refinement;
        }
        Fixture add(String source, String baseline, String... requiredWords) {
            String id = CanonicalRow.rowId(rows.size(), source);
            rows.add(new AiLine(id, AiLineClass.ORDINARY, AiSendDisposition.SENT, source,
                    rows.isEmpty() ? AiVoiceHint.BACKGROUND : AiVoiceHint.PRIMARY,
                    false, baseline, baseline == null ? null : "google"));
            if (requiredWords.length > 0) words.put(id, requiredWords);
            return this;
        }
    }

    private static List<Fixture> fixtures() {
        return Arrays.asList(
            new Fixture("meaning_mixed", LayerKind.MEANING, "en", false)
                .add("背景", null, "background")
                .add("La puerta roja está abierta.", null, "red", "door", "open")
                .add("雨が降っている / OK", null, "rain", "ok")
                .add("ฉันคิดถึงคุณ", null, "miss")
                .add("Я жду тебя у окна.", null, "wait", "window")
                .add("我们一起回家。", null, "home", "together"),
            new Fixture("meaning_refinement", LayerKind.MEANING, "en", true)
                .add("赤い花", "blue flower", "red", "flower")
                .add("La puerta está cerrada.", "The door is open.", "door", "closed")
                .add("我们一起回家。", "We go to work alone.", "home", "together")
                .add("雨が降っている / OK", "Snow is falling / OK", "rain", "ok"),
            new Fixture("sound_latin", LayerKind.SOUND, "Latin", false)
                .add("ฉันคิดถึงคุณ", null)
                .add("고마워 / OK", null)
                .add("Я жду тебя", null)
                .add("ありがとう", null),
            new Fixture("sound_kana", LayerKind.SOUND, "Kana", false)
                .add("あ / OK", null)
                .add("コーヒー", null)
                .add("고마워", null));
    }

    private static JsonObject evaluate(String raw, AiProviderRequest request, Fixture fixture) {
        JsonObject result = new JsonObject();
        try {
            AiResponseValidator.Partition partition = AiResponseValidator.partition(
                    AiResponseReader.readItems(raw), request.items, fixture.layer, fixture.target);
            result.addProperty("acceptedRows", partition.accepted.size());
            result.addProperty("failedRows", partition.failed.size());
            result.addProperty("structural", partition.structural.split(":")[0]);
            result.addProperty("clean", partition.clean());
            Map<String,Integer> failureCategories = new LinkedHashMap<>();
            for (AiResponseValidator.RowFailure failure : partition.failed)
                failureCategories.merge(failure.token.split(":")[0], 1, Integer::sum);
            result.add("failureCategories",new Gson().toJsonTree(failureCategories));
            int checked = 0, matched = 0;
            for (AiResponseItem item : partition.accepted) {
                String[] required = fixture.words.get(item.id);
                if (required == null) continue;
                for (String word : required) {
                    checked++;
                    if (item.text.toLowerCase(Locale.ROOT).contains(word)) matched++;
                }
            }
            result.addProperty("keywordChecks", checked);
            result.addProperty("keywordMatches", matched);
        } catch (RuntimeException rejected) {
            result.addProperty("acceptedRows", 0);
            result.addProperty("clean", false);
            result.addProperty("structural", "invalid_json");
        }
        return result;
    }

    private static AiProviderRequest requestOf(JsonObject value, boolean productionVoices) {
        List<AiRequestItem> items = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            AiVoiceHint voice = null;
            if (item.has("v") && !item.get("v").isJsonNull()) {
                for (AiVoiceHint candidate : AiVoiceHint.values())
                    if (candidate.token.equals(item.get("v").getAsString())) voice = candidate;
            }
            if (productionVoices && voice == AiVoiceHint.BACKGROUND) voice = AiVoiceHint.PRIMARY;
            items.add(new AiRequestItem(item.get("id").getAsString(), AiLineClass.ORDINARY,
                    voice, item.get("s").getAsString(), item.has("p") ? item.get("p").getAsString() : null));
        }
        return new AiProviderRequest(AiLyricContext.EMPTY, value.get("target").getAsString(), "", items);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("language-preview")) {
            // Offline only. This branch returns before credentials, transport, or a provider exist.
            JsonObject rules = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[3])),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject preview = languagePreview(args[1], args[2], rules, args.length > 4 ? args[4] : "");
            System.out.println(preview);
            return;
        }
        if (args[0].equals("plans")) {
            for (int segments : new int[]{1, 2, 4}) {
                AiChunkPlanner.Input input = new AiChunkPlanner.Input();
                List<AiLine> rows = new ArrayList<>();
                for (int i = 0; i < 129; i++) {
                    rows.add(AiLine.of("r" + i, String.join(" / ",
                            Collections.nCopies(segments, "hola")), null, false));
                }
                input.rows = rows;
                input.target = "en";
                input.model = FakeAiProvider.DEFAULT_MODEL;
                AiChunkPlan plan = AiChunkPlanner.plan(input);
                JsonObject result = new JsonObject();
                result.addProperty("segments", segments);
                result.addProperty("version", plan.version);
                JsonArray chunks = new JsonArray();
                for (AiPlannedChunk chunk : plan.chunks) {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", chunk.id);
                    value.addProperty("requestDigest", com.eza.spicyex.lyrics.session.Digests.sha256(chunk.requestJson));
                    value.add("ids", new Gson().toJsonTree(chunk.itemIds()));
                    chunks.add(value);
                }
                result.add("chunks", chunks);
                System.out.println(result);
            }
            return;
        }
        if (args[0].equals("score") || args[0].equals("score-production-voices")) {
            JsonArray captures = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[1])),
                    StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : captures) {
                JsonObject capture = element.getAsJsonObject();
                Fixture fixture = fixtures().stream().filter(f -> f.name.equals(capture.get("fixture").getAsString())).findFirst().get();
                JsonObject score = evaluate(capture.get("raw").getAsString(), requestOf(capture.getAsJsonObject("request"),
                        args[0].equals("score-production-voices")), fixture);
                score.addProperty("fixture", fixture.name);
                System.out.println(score);
            }
            return;
        }
        JsonObject access = JsonParser.parseString(new BufferedReader(new InputStreamReader(System.in,
                StandardCharsets.UTF_8)).readLine()).getAsJsonObject();
        String endpoint = access.get("endpoint").getAsString();
        String key = access.get("key").getAsString();
        String model = args[1];
        boolean offline = args[0].equals("offline");
        JsonArray captures = new JsonArray();
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        for (Fixture fixture : fixtures()) {
            if (args.length > 3 && !fixture.name.equals(args[3])) continue;
            int[] wireCalls = {0}, providerCalls = {0};
            List<Integer> statuses = new ArrayList<>();
            Set<String> servedModels = new LinkedHashSet<>();
            Set<String> rejectedFields = new LinkedHashSet<>();
            AiGeminiProvider.Transport transport = new AiGeminiProvider.Transport() {
                public AiHttp.Result get(String url, Map<String,String> headers, AiSignal signal, int max) {
                    throw new AssertionError("No discovery from evaluation provider");
                }
                public AiHttp.Result postJson(String url, Map<String,String> headers, String json,
                                             AiSignal signal, int max) {
                    wireCalls[0]++;
                    if (!url.equals(endpoint + "/chat/completions")) throw new AssertionError("Unexpected endpoint");
                    try {
                        Request.Builder builder = new Request.Builder().url(url);
                        headers.forEach(builder::header);
                        try (Response response = client.newCall(builder.post(RequestBody.create(
                                json.getBytes(StandardCharsets.UTF_8),
                                MediaType.get("application/json; charset=utf-8"))).build()).execute()) {
                            byte[] bytes;
                            if (response.body() == null) bytes = new byte[0];
                            else {
                                BufferedSource source = response.body().source();
                                source.request((long) max + 1);
                                bytes = source.readByteArray(Math.min(source.getBuffer().size(), (long) max + 1));
                            }
                            statuses.add(response.code());
                            if (bytes.length > max) return AiHttp.Result.failed(AiProviderFailure.oversized(bytes.length));
                            String body = new String(bytes, StandardCharsets.UTF_8);
                            if (response.code() >= 400) {
                                String lower = body.toLowerCase(Locale.ROOT);
                                for (String token : Arrays.asList("json_schema", "response_format", "temperature",
                                        "max_tokens", "max_completion_tokens", "prompt", "context", "unsupported",
                                        "not supported", "thinking", "budget", "safety", "quota", "grammar", "strict",
                                        "content", "empty", "function", "system", "auth", "sensitive", "role", "invalid"))
                                    if (lower.contains(token)) rejectedFields.add(token);
                            }
                            try {
                                JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
                                if (parsed.has("model")) {
                                    String reported = parsed.get("model").getAsString();
                                    if (reported.matches("[A-Za-z0-9./_-]{1,128}")) servedModels.add(reported);
                                }
                            } catch (RuntimeException ignored) { }
                            return AiHttp.Result.of(response.code(), body, bytes.length);
                        }
                    } catch (IOException failure) {
                        return AiHttp.Result.failed(AiProviderFailure.deliveryUnknown(AiProviderFailure.Cause.NETWORK, 0));
                    }
                }
            };
            AiProvider delegate = offline ? new FakeAiProvider((request, config, signal) -> {
                List<String> texts = new ArrayList<>();
                for (AiRequestItem item : request.items) {
                    if (item.source.equals("背景")) texts.add("background");
                    else if (item.source.equals("OK")) texts.add("OK");
                    else if (fixture.layer == LayerKind.SOUND) texts.add(fixture.target.equals("Kana") ? "アー" : "reading");
                    else texts.add("red door open closed rain ok miss wait window home together flower");
                }
                return AiProviderResult.ok(FakeAiProvider.itemsJson(request,texts), AiUsage.of(10,10), AiFinishReason.STOP,1);
            }) : new AiOpenAiProvider(endpoint, () -> key, transport);
            JsonObject[] firstScore = {null};
            AiProvider provider = new AiProvider() {
                public String id() { return delegate.id(); }
                public AiModelListResult listModels(AiSignal signal) { throw new AssertionError("No discovery"); }
                public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config, AiSignal signal) {
                    providerCalls[0]++;
                    AiProviderResult result = delegate.generateChunk(request,config,signal);
                    if (providerCalls[0] == 1 && result.ok && result.finish == AiFinishReason.STOP) {
                        firstScore[0] = evaluate(result.rawText,request,fixture);
                        JsonObject capture = new JsonObject();
                        capture.addProperty("fixture",fixture.name);
                        capture.addProperty("raw",result.rawText);
                        capture.add("request",JsonParser.parseString(request.toJson()));
                        captures.add(capture);
                    }
                    return result;
                }
            };
            AiProviderConfig config = new AiProviderConfig(fixture.layer, endpoint, "comparison-v1",
                new AiModelDescriptor(model,"",32768,8192,Collections.singletonList("chat.completions")),
                fixture.target, fixture.layer == LayerKind.SOUND ? AiContract.SOUND_PROMPT_VERSION
                    : fixture.refinement ? AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION : AiContract.PROMPT_VERSION,
                false,fixture.refinement);
            AiLayerRunner.Args run = new AiLayerRunner.Args();
            run.config = new AiRunConfig(fixture.layer,"synthetic-doc-"+fixture.name,"synthetic-config",
                provider.id(),AiLyricContext.EMPTY,config,"",null,null);
            run.rows = fixture.rows; run.provider = provider; run.store = new FakeAiRecordStore();
            run.useBaseline = fixture.refinement; run.signal = new AiSignal();
            long start = System.nanoTime();
            AiRunOutcome outcome = AiLayerRunner.run(run);
            boolean soundValid = true;
            if (fixture.layer == LayerKind.SOUND && outcome.hasOutput()) {
                Method validate = AiSoundRun.class.getDeclaredMethod("validateStoredOutput",AiPaidRecord.class,List.class,String.class);
                validate.setAccessible(true);
                soundValid = validate.invoke(null,outcome.record,fixture.rows,fixture.target) == null;
            }
            JsonObject row = new JsonObject();
            row.addProperty("fixture",fixture.name); row.addProperty("model",model);
            row.addProperty("kind",outcome.kind.name()); row.addProperty("reason",outcome.failureToken);
            row.addProperty("category",outcome.failureDetail.split(":")[0]);
            row.addProperty("expectedRows",fixture.rows.size());
            row.addProperty("acceptedRows",outcome.record == null ? 0 : outcome.record.items().size());
            row.addProperty("complete",outcome.hasOutput() && outcome.record.isComplete() && soundValid);
            row.addProperty("publishable",outcome.hasOutput() && soundValid && !outcome.record.items().isEmpty());
            row.addProperty("partial",outcome.hasOutput() && !outcome.record.isComplete());
            row.addProperty("storedSoundValid",soundValid);
            row.addProperty("providerCalls",providerCalls[0]); row.addProperty("wireCalls",wireCalls[0]);
            row.add("httpStatuses",new Gson().toJsonTree(statuses));
            row.add("servedModels",new Gson().toJsonTree(servedModels));
            row.add("rejectedFields",new Gson().toJsonTree(rejectedFields));
            row.addProperty("inputTokens",outcome.record == null ? 0 : outcome.record.inputTokens);
            row.addProperty("outputTokens",outcome.record == null ? 0 : outcome.record.outputTokens);
            row.addProperty("estimatedUsage",outcome.record != null && outcome.record.usageEstimated);
            row.addProperty("elapsedMs",(System.nanoTime()-start)/1000000L);
            row.addProperty("promptBytes",AiText.utf8Bytes(config.systemPrompt()));
            if (firstScore[0] != null) row.add("initial",firstScore[0]);
            System.out.println(row); System.out.flush();
        }
        Files.write(Paths.get(args[2]),captures.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Isolated Meaning prompt assembly through the actual instruction and identity boundaries. */
    static JsonObject languagePreview(String source, String target, JsonObject rules, String ownerInstructions) {
        JsonArray selected = new JsonArray();
        List<String> guidance = new ArrayList<>();
        for (JsonElement entry : rules.getAsJsonArray("rules")) {
            JsonObject rule = entry.getAsJsonObject();
            if (languageMatches(rule.getAsJsonArray("sourceLanguages"), source)
                    && languageMatches(rule.getAsJsonArray("targetLanguages"), target)) {
                selected.add(rule.get("id").getAsString());
                guidance.add(rule.get("instructions").getAsString());
            }
        }
        String owner = AiContract.normalizeSteering(ownerInstructions);
        if (!owner.isEmpty()) guidance.add(owner); // Later instructions retain the owner's precedence.
        String combined = AiContract.normalizeSteering(String.join("\n", guidance));
        AiIdentity.Config identity = new AiIdentity.Config();
        identity.provider = "offline-prototype";
        identity.providerVersion = "fixture-v1";
        identity.modelName = "fixture-model";
        identity.targetLang = target;
        identity.instructions = combined;
        JsonObject result = new JsonObject();
        result.addProperty("providerCalls", 0);
        result.addProperty("sourceLanguage", source);
        result.addProperty("targetLanguage", target);
        result.add("selectedRules", selected);
        result.addProperty("instructions", combined);
        result.addProperty("configId", AiIdentity.buildConfigId(identity));
        result.addProperty("systemPrompt", AiContract.buildSystemPrompt(LayerKind.MEANING, target, false, false));
        return result;
    }

    private static boolean languageMatches(JsonArray supported, String language) {
        String primary = language == null ? "und" : language.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        for (JsonElement item : supported) {
            if (item.getAsString().equals("*") || item.getAsString().equals(primary)) return true;
        }
        return false;
    }
}
