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

/**
 * Explicit CLI evaluation tool. It never runs as a JUnit test and prints metadata only.
 *
 * <p>Lab mode ({@code lab offline|live}) replays a private fixture file through the real planner,
 * runtime and validator, with a per-arm instruction string. It is offline with the fake provider
 * unless the caller says live. Row text is written only when {@code --emit-text} names a path
 * outside the repository worktree; the default report holds counts, tokens, finish reasons and
 * configuration identity, never source or output text. See
 * {@code scripts/ai-harness-model-comparison.py} for the usage note.
 */
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
        if (args[0].equals("lab")) {
            labMain(args);
            return;
        }
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

    // --- Lab mode -----------------------------------------------------------
    //
    // Additive CLI used by the person-reference lab. It replays a fixture file through the real
    // planner, runtime and validator, with either the offline fake provider or a live
    // OpenAI-compatible endpoint. The default report is metadata only; row text leaves the JVM
    // only through --emit-text, and that path must be outside the repository worktree.

    static final String LAB_SCHEMA = "ai-harness-lab-v1";

    static final class LabOptions {
        boolean live;
        String model = "";
        final List<String> fixtures = new ArrayList<>();
        String instructions = "";
        String arm = "unlabelled";
        String providerVersion = "comparison-v1";
        int maxCalls;
        int maxOutputTokens;
        String emitText;
        String report;
        String endpoint = "";
        String key = "";
    }

    static final class LabRow {
        final String id, source, role, song, baseline;
        final AiLineClass lineClass;
        final AiVoiceHint voice;

        LabRow(String id, String source, String role, String song, String baseline,
               AiLineClass lineClass, AiVoiceHint voice) {
            this.id = id; this.source = source; this.role = role; this.song = song;
            this.baseline = baseline; this.lineClass = lineClass; this.voice = voice;
        }
    }

    static final class LabFixture {
        final String name, lang, target;
        final LayerKind layer;
        final boolean refinement;
        final List<LabRow> rows = new ArrayList<>();

        LabFixture(String name, String lang, String target, LayerKind layer,
                   boolean refinement) {
            this.name = name; this.lang = lang; this.target = target; this.layer = layer;
            this.refinement = refinement;
        }
    }

    /** Raw usage JSON of the last successful wire response, for reasoning-token extraction. */
    static final class RawUsage {
        JsonObject usage;
    }

    static final class LabCallLog {
        final List<JsonObject> calls = new ArrayList<>();
        final Map<String, String> rowFailures = new LinkedHashMap<>();
        int wireCalls;
        boolean stopped;
        String stopReason = "";
        int structuralCalls;
    }

    static LabOptions labOptions(String[] args) throws IOException {
        LabOptions options = new LabOptions();
        if (args.length < 2 || (!args[1].equals("live") && !args[1].equals("offline"))) {
            throw new IllegalArgumentException("lab needs live or offline");
        }
        options.live = args[1].equals("live");
        String instructionText = null, instructionFile = null;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            if (flag.equals("--fixtures")) options.fixtures.add(labValue(args, ++i, flag));
            else if (flag.equals("--instructions")) instructionText = labValue(args, ++i, flag);
            else if (flag.equals("--instructions-file")) instructionFile = labValue(args, ++i, flag);
            else if (flag.equals("--arm")) options.arm = labValue(args, ++i, flag);
            else if (flag.equals("--model")) options.model = labValue(args, ++i, flag);
            else if (flag.equals("--provider-version")) options.providerVersion = labValue(args, ++i, flag);
            else if (flag.equals("--max-calls")) options.maxCalls = Integer.parseInt(labValue(args, ++i, flag));
            else if (flag.equals("--max-output-tokens")) options.maxOutputTokens = Integer.parseInt(labValue(args, ++i, flag));
            else if (flag.equals("--emit-text")) options.emitText = labValue(args, ++i, flag);
            else if (flag.equals("--report")) options.report = labValue(args, ++i, flag);
            else throw new IllegalArgumentException("unknown lab option: " + flag);
        }
        if (instructionText != null && instructionFile != null) {
            throw new IllegalArgumentException("use --instructions or --instructions-file, not both");
        }
        if (instructionFile != null) {
            instructionText = new String(Files.readAllBytes(Paths.get(instructionFile)),
                    StandardCharsets.UTF_8);
        }
        options.instructions = instructionText == null ? "" : instructionText;
        if (options.instructions.length() > 65536) {
            throw new IllegalArgumentException("instructions are too large");
        }
        if (options.model.isEmpty()) throw new IllegalArgumentException("lab needs --model");
        if (options.fixtures.isEmpty()) throw new IllegalArgumentException("lab needs --fixtures");
        if (options.report == null) throw new IllegalArgumentException("lab needs --report");
        if (!options.arm.matches("[A-Za-z0-9._-]{1,40}")) {
            throw new IllegalArgumentException("arm name must be safe for a report");
        }
        if (options.maxCalls < 0 || options.maxOutputTokens < 0) {
            throw new IllegalArgumentException("caps must not be negative");
        }
        if (options.maxOutputTokens > AiContract.MAX_CONFIGURED_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("max output tokens exceeds the contract ceiling");
        }
        return options;
    }

    private static String labValue(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    static Path labPrivatePath(String value, String flag) throws IOException {
        Path raw = Paths.get(value).toAbsolutePath().normalize();
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path resolved = raw;
        try {
            resolved = raw.toRealPath();
        } catch (IOException missing) {
            Path parent = raw.getParent();
            if (parent != null && Files.exists(parent)) {
                resolved = parent.toRealPath().resolve(raw.getFileName());
            }
        }
        if (resolved.startsWith(root) || raw.startsWith(root)) {
            throw new IllegalArgumentException(flag + " must be outside the repository worktree");
        }
        return raw;
    }

    static List<LabFixture> readLabFixtures(List<String> paths) throws IOException {
        List<LabFixture> fixtures = new ArrayList<>();
        for (String path : paths) {
            JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.UTF_8));
            JsonArray array;
            if (root.isJsonArray()) array = root.getAsJsonArray();
            else if (root.isJsonObject() && root.getAsJsonObject().has("fixtures")) {
                array = root.getAsJsonObject().getAsJsonArray("fixtures");
            } else {
                throw new IllegalArgumentException("fixture file must be a list or an object with fixtures");
            }
            for (JsonElement element : array) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("fixture must be an object");
                fixtures.add(readLabFixture(element.getAsJsonObject(), fixtures.size()));
            }
        }
        return fixtures;
    }

    private static LabFixture readLabFixture(JsonObject value, int ordinal) {
        String name = labString(value, "name", "fixture-" + ordinal);
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("fixture name must be safe for a report");
        }
        String layerToken = labString(value, "layer", "meaning").toLowerCase(Locale.ROOT);
        LayerKind layer = layerToken.equals("sound") ? LayerKind.SOUND : LayerKind.MEANING;
        if (!layerToken.equals("sound") && !layerToken.equals("meaning")) {
            throw new IllegalArgumentException("fixture layer must be meaning or sound");
        }
        String target = labString(value, "target", layer == LayerKind.SOUND ? "" : "en");
        if (target.isEmpty()) throw new IllegalArgumentException("fixture target is required");
        String lang = labString(value, "lang", "");
        if (!lang.matches("[a-z-]{0,16}")) {
            throw new IllegalArgumentException("fixture lang must be a short tag");
        }
        boolean refinement = value.has("refine") && value.get("refine").getAsBoolean();
        LabFixture fixture = new LabFixture(name, lang, target, layer, refinement);
        JsonArray rows = value.has("rows") ? value.getAsJsonArray("rows")
                : value.has("items") ? value.getAsJsonArray("items") : null;
        if (rows == null || rows.size() == 0) {
            throw new IllegalArgumentException("fixture needs rows");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonElement element = rows.get(i);
            if (!element.isJsonObject()) throw new IllegalArgumentException("row must be an object");
            JsonObject row = element.getAsJsonObject();
            String source = labString(row, "source", labString(row, "s", null));
            if (source == null) throw new IllegalArgumentException("row needs source");
            String id = labString(row, "id", CanonicalRow.rowId(i, source));
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate row id in fixture");
            String classToken = labString(row, "class", labString(row, "c",
                    AiLineClassifier.classify(source).token)).toLowerCase(Locale.ROOT);
            AiLineClass lineClass = null;
            for (AiLineClass candidate : AiLineClass.values()) {
                if (candidate.token.equals(classToken)) lineClass = candidate;
            }
            if (lineClass == null) throw new IllegalArgumentException("row class is unknown");
            String voiceToken = labString(row, "voice", labString(row, "v", null));
            AiVoiceHint voice = null;
            if (voiceToken != null && !voiceToken.isEmpty()) {
                for (AiVoiceHint candidate : AiVoiceHint.values()) {
                    if (candidate.token.equals(voiceToken)) voice = candidate;
                }
                if (voice == null) throw new IllegalArgumentException("row voice is unknown");
            }
            String baseline = labString(row, "baseline", labString(row, "p", null));
            String role = labString(row, "role", "target");
            if (!role.matches("[a-z]{1,16}")) {
                throw new IllegalArgumentException("row role must be a short token");
            }
            String song = labString(row, "song", "");
            if (!song.matches("[A-Za-z0-9._-]{0,40}")) {
                throw new IllegalArgumentException("row song must be a short token");
            }
            fixture.rows.add(new LabRow(id, source, role, song, baseline, lineClass, voice));
        }
        return fixture;
    }

    private static String labString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }

    private static List<AiLine> labLines(LabFixture fixture) {
        List<AiLine> lines = new ArrayList<>();
        for (LabRow row : fixture.rows) {
            AiSendDisposition disposition = row.lineClass == AiLineClass.STRUCTURAL
                    ? AiSendDisposition.STRUCTURAL : AiSendDisposition.SENT;
            lines.add(new AiLine(row.id, row.lineClass, disposition, row.source, row.voice,
                    false, row.baseline, row.baseline == null ? null : "google"));
        }
        return lines;
    }

    private static int labPromptVersion(LabFixture fixture) {
        if (fixture.layer == LayerKind.SOUND) return AiContract.SOUND_PROMPT_VERSION;
        return fixture.refinement ? AiContract.GOOGLE_REFINEMENT_PROMPT_VERSION
                : AiContract.PROMPT_VERSION;
    }

    private static String labConfigId(LabFixture fixture, LabOptions options, int promptVersion) {
        // Mirrors AiMeaningRun.configIdFor: the same fields, so a lab row and a production row
        // under the same instructions have the same configuration identity.
        AiIdentity.Config identity = new AiIdentity.Config();
        identity.layer = fixture.layer;
        identity.provider = options.live ? AiOpenAiProvider.ID : "fake";
        identity.providerVersion = options.providerVersion;
        identity.modelName = options.model;
        identity.targetLang = fixture.target;
        identity.instructions = options.instructions;
        identity.promptVersion = promptVersion;
        return AiIdentity.buildConfigId(identity);
    }

    private static AiProviderConfig labProviderConfig(LabFixture fixture, LabOptions options,
                                                     int promptVersion) {
        AiModelDescriptor descriptor = new AiModelDescriptor(options.model, "", 32768, 8192,
                Collections.singletonList("chat.completions"));
        AiProviderConfig config = new AiProviderConfig(fixture.layer,
                options.live ? options.endpoint : "https://example.invalid/v1",
                options.providerVersion, descriptor, fixture.target, promptVersion, false,
                fixture.refinement);
        if (options.maxOutputTokens > 0) {
            // The runtime asks for this as the absolute per-call ceiling. A cap is a ceiling, not
            // a charge: the call still bills only what the model produced.
            config = config.forCall(false, options.maxOutputTokens);
        }
        return config;
    }

    private static final class LabProvider implements AiProvider {
        private final AiProvider delegate;
        private final LabCallLog log;
        private final int maxCalls;
        private final RawUsage raw;

        LabProvider(AiProvider delegate, LabCallLog log, int maxCalls, RawUsage raw) {
            this.delegate = delegate; this.log = log; this.maxCalls = maxCalls; this.raw = raw;
        }

        @Override public String id() {
            return delegate.id();
        }

        @Override public AiModelListResult listModels(AiSignal signal) {
            throw new AssertionError("No discovery in lab mode");
        }

        @Override
        public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                             AiSignal signal) {
            if (maxCalls > 0 && log.calls.size() >= maxCalls) {
                log.stopped = true;
                log.stopReason = "max_calls";
                // Terminal, and deliberately not counted as a call: the cap stops dispatch.
                return AiProviderResult.failed(AiProviderFailure.deliveryUnknown(
                        AiProviderFailure.Cause.NONE, 0));
            }
            if (raw != null) raw.usage = null;
            AiProviderResult result = delegate.generateChunk(request, config, signal);
            log.calls.add(labCallRecord(log, request, config, result,
                    raw == null ? null : raw.usage));
            return result;
        }

        @Override
        public String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
            return delegate.monitorPayload(request, config);
        }
    }

    private static JsonObject labCallRecord(LabCallLog log, AiProviderRequest request,
                                            AiProviderConfig config, AiProviderResult result,
                                            JsonObject rawUsage) {
        JsonObject call = new JsonObject();
        call.addProperty("n", log.calls.size() + 1);
        call.addProperty("items", request.items.size());
        call.addProperty("requestBytes", AiText.utf8Bytes(request.toJson()));
        call.addProperty("instructionsBytes", AiText.utf8Bytes(request.instructions));
        call.addProperty("outputTokenCap", config.callOutputTokens());
        call.addProperty("rawBytes", result.rawBytes);
        call.addProperty("ok", result.ok);
        call.addProperty("finish", result.finish == null ? "none" : result.finish.name());
        if (result.failure == null) call.addProperty("failure", "none");
        else call.addProperty("failure", result.failure.kind.name().toLowerCase(Locale.ROOT));
        if (result.usage == null || result.usage.input == null) {
            call.addProperty("inputTokens", "unknown");
        } else {
            call.addProperty("inputTokens", result.usage.input);
        }
        if (result.usage == null || result.usage.output == null) {
            call.addProperty("outputTokens", "unknown");
        } else {
            call.addProperty("outputTokens", result.usage.output);
        }
        call.add("reasoningTokens", labReasoningTokens(rawUsage));
        int accepted = 0, failed = 0;
        String structural = "";
        Map<String, Integer> categories = new LinkedHashMap<>();
        if (result.ok && result.rawText != null) {
            try {
                AiResponseValidator.Partition partition = AiResponseValidator.partition(
                        AiResponseReader.readItems(result.rawText), request.items,
                        config.layer, config.targetLang);
                accepted = partition.accepted.size();
                failed = partition.failed.size();
                structural = partition.structural;
                for (AiResponseValidator.RowFailure failure : partition.failed) {
                    categories.merge(failure.token.split(":")[0], 1, Integer::sum);
                    log.rowFailures.put(failure.rowId, failure.token.split(":")[0]);
                }
                for (AiResponseItem item : partition.accepted) log.rowFailures.remove(item.id);
            } catch (RuntimeException rejected) {
                structural = "invalid_json";
            }
        }
        if (!structural.isEmpty()) log.structuralCalls++;
        call.addProperty("acceptedRows", accepted);
        call.addProperty("failedRows", failed);
        call.addProperty("structural", structural.isEmpty() ? "none" : structural.split(":")[0]);
        call.add("failureCategories", new Gson().toJsonTree(categories));
        return call;
    }

    static JsonElement labReasoningTokens(JsonObject usage) {
        if (usage == null) return new JsonPrimitive("unknown");
        Integer value = null;
        for (String detailKey : new String[]{"completion_tokens_details", "output_tokens_details"}) {
            if (!usage.has(detailKey) || !usage.get(detailKey).isJsonObject()) continue;
            value = labInteger(usage.getAsJsonObject(detailKey), "reasoning_tokens");
            if (value != null) break;
        }
        if (value == null) value = labInteger(usage, "reasoning_tokens");
        return value == null ? new JsonPrimitive("unknown") : new JsonPrimitive(value);
    }

    private static Integer labInteger(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static FakeAiProvider.Step labFakeStep() {
        return (AiProviderRequest request, AiProviderConfig config, AiSignal signal) -> {
            List<String> texts = new ArrayList<>();
            for (AiRequestItem item : request.items) {
                if (config.layer == LayerKind.SOUND) texts.add("reading");
                else if (item.lineClass == AiLineClass.ADLIB) texts.add(item.source);
                else {
                    // Deliberately synthetic, and varied enough that a dry run shows a non-zero
                    // person-class column. It never stands in for real evidence.
                    String[] canned = {"I will stay here.", "We will stay here.",
                            "You will stay here.", "They will stay here.",
                            "The light stays here."};
                    texts.add(canned[(item.source.hashCode() & 0x7fffffff) % canned.length]);
                }
            }
            String raw = FakeAiProvider.itemsJson(request, texts);
            int input = 64 + request.items.size() * 12;
            int output = Math.max(1, raw.length() / 4);
            return AiProviderResult.ok(raw, AiUsage.of(input, output), AiFinishReason.STOP,
                    raw.length());
        };
    }

    static void labMain(String[] args) throws Exception {
        LabOptions options = labOptions(args);
        List<LabFixture> fixtures = readLabFixtures(options.fixtures);
        Path emitPath = options.emitText == null ? null
                : labPrivatePath(options.emitText, "--emit-text");
        Path reportPath = labPrivatePath(options.report, "--report");
        String credentials = new BufferedReader(new InputStreamReader(System.in,
                StandardCharsets.UTF_8)).readLine();
        if (credentials != null && !credentials.trim().isEmpty()) {
            JsonObject access = JsonParser.parseString(credentials).getAsJsonObject();
            options.endpoint = labString(access, "endpoint", "");
            options.key = labString(access, "key", "");
        }
        if (options.live && (options.endpoint.isEmpty() || options.key.isEmpty())) {
            throw new IllegalArgumentException("live lab needs endpoint and key on stdin");
        }

        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).build();
        RawUsage raw = new RawUsage();
        AiProvider delegate;
        if (options.live) {
            delegate = new AiOpenAiProvider(options.endpoint, () -> options.key,
                    labTransport(options.endpoint, client, raw));
        } else {
            delegate = new FakeAiProvider(labFakeStep());
        }

        BufferedWriter emit = null;
        if (emitPath != null) {
            Path parent = emitPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            emit = Files.newBufferedWriter(emitPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
        JsonArray reports = new JsonArray();
        int callsUsed = 0;
        int skipped = 0;
        boolean stopped = false;
        String stopReason = "";
        try {
            for (LabFixture fixture : fixtures) {
                if (options.maxCalls > 0 && callsUsed >= options.maxCalls) {
                    skipped++;
                    stopped = true;
                    stopReason = "max_calls";
                    continue;
                }
                int remaining = options.maxCalls > 0 ? options.maxCalls - callsUsed : 0;
                LabExecution execution = labRunFixture(fixture, options, delegate, raw, remaining);
                callsUsed += execution.log.calls.size();
                if (execution.log.stopped) {
                    stopped = true;
                    stopReason = execution.log.stopReason;
                }
                reports.add(execution.report);
                System.out.println(execution.report);
                System.out.flush();
                if (emit != null) {
                    emit.write(execution.emit.toString());
                    emit.newLine();
                    emit.flush();
                }
                if (execution.log.stopped) break;
            }
        } finally {
            if (emit != null) emit.close();
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", LAB_SCHEMA);
        report.addProperty("mode", options.live ? "live" : "offline");
        report.addProperty("arm", options.arm);
        report.addProperty("model", options.model);
        report.addProperty("providerVersion", options.providerVersion);
        report.addProperty("instructionsInputBytes", AiText.utf8Bytes(options.instructions));
        report.addProperty("instructionsBytes",
                AiText.utf8Bytes(AiContract.normalizeSteering(options.instructions)));
        report.addProperty("instructionsSha256", com.eza.spicyex.lyrics.session.Digests.sha256(
                AiContract.normalizeSteering(options.instructions)));
        report.addProperty("maxCalls", options.maxCalls);
        report.addProperty("maxOutputTokens", options.maxOutputTokens);
        report.addProperty("stopped", stopped);
        report.addProperty("stopReason", stopReason);
        report.addProperty("skippedFixtures", skipped);
        report.addProperty("emitText", emitPath == null ? "none" : "private-file");
        report.add("fixtures", reports);
        JsonObject totals = new JsonObject();
        int totalRows = 0, totalSent = 0, totalAccepted = 0, totalFailed = 0;
        int totalCalls = 0, totalWire = 0, totalInput = 0, totalOutput = 0;
        boolean anyReported = false, anyUnknown = false;
        int reasoningTotal = 0;
        for (JsonElement element : reports) {
            JsonObject fixture = element.getAsJsonObject();
            totalRows += fixture.get("rows").getAsInt();
            totalSent += fixture.get("sentRows").getAsInt();
            totalAccepted += fixture.get("acceptedRows").getAsInt();
            totalFailed += fixture.get("failedRows").getAsInt();
            totalCalls += fixture.get("providerCalls").getAsInt();
            totalWire += fixture.get("wireCalls").getAsInt();
            totalInput += fixture.get("callInputTokens").getAsInt();
            totalOutput += fixture.get("callOutputTokens").getAsInt();
            JsonElement reasoning = fixture.get("reasoningTokens");
            if (reasoning.isJsonPrimitive() && reasoning.getAsJsonPrimitive().isNumber()) {
                anyReported = true;
                reasoningTotal += reasoning.getAsInt();
            } else {
                anyUnknown = true;
            }
        }
        totals.addProperty("fixtures", reports.size());
        totals.addProperty("rows", totalRows);
        totals.addProperty("sentRows", totalSent);
        totals.addProperty("acceptedRows", totalAccepted);
        totals.addProperty("failedRows", totalFailed);
        totals.addProperty("providerCalls", totalCalls);
        totals.addProperty("wireCalls", totalWire);
        totals.addProperty("inputTokens", totalInput);
        totals.addProperty("outputTokens", totalOutput);
        if (anyReported && !anyUnknown) totals.addProperty("reasoningTokens", reasoningTotal);
        else totals.addProperty("reasoningTokens", "unknown");
        totals.addProperty("stopped", stopped);
        totals.addProperty("stopReason", stopReason);
        totals.addProperty("skippedFixtures", skipped);
        report.add("totals", totals);
        Path reportParent = reportPath.getParent();
        if (reportParent != null) Files.createDirectories(reportParent);
        Files.write(reportPath, new Gson().toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    static final class LabExecution {
        final JsonObject report, emit;
        final LabCallLog log;

        LabExecution(JsonObject report, JsonObject emit, LabCallLog log) {
            this.report = report; this.emit = emit; this.log = log;
        }
    }

    static LabExecution labRunFixture(LabFixture fixture, LabOptions options, AiProvider delegate,
                                      RawUsage raw, int remainingCalls) {
        LabCallLog log = new LabCallLog();
        LabProvider provider = new LabProvider(delegate, log, remainingCalls, raw);
        long start = System.nanoTime();
        List<AiLine> rows = labLines(fixture);
        int promptVersion = labPromptVersion(fixture);
        String configId = labConfigId(fixture, options, promptVersion);
        String docDigest = AiIdentity.buildDocDigest(rows, AiLyricContext.EMPTY,
                fixture.refinement);
        AiRunConfig config = new AiRunConfig(fixture.layer, docDigest, configId,
                provider.id(), AiLyricContext.EMPTY,
                labProviderConfig(fixture, options, promptVersion), options.instructions,
                null, null);
        AiLayerRunner.Args run = new AiLayerRunner.Args();
        run.config = config;
        run.rows = rows;
        run.provider = provider;
        run.store = new FakeAiRecordStore();
        run.useBaseline = fixture.refinement;
        run.signal = new AiSignal();
        AiRunOutcome outcome = AiLayerRunner.run(run);
        JsonObject report = labFixtureReport(fixture, options, rows, outcome, log,
                configId, docDigest, promptVersion, (System.nanoTime() - start) / 1000000L);
        return new LabExecution(report, labEmitRecord(fixture, options, outcome, log), log);
    }

    private static JsonObject labFixtureReport(LabFixture fixture, LabOptions options,
                                               List<AiLine> rows, AiRunOutcome outcome,
                                               LabCallLog log, String configId, String docDigest,
                                               int promptVersion, long elapsedMs) {
        JsonObject report = new JsonObject();
        report.addProperty("schema", LAB_SCHEMA);
        report.addProperty("mode", options.live ? "live" : "offline");
        report.addProperty("arm", options.arm);
        report.addProperty("model", options.model);
        report.addProperty("providerVersion", options.providerVersion);
        report.addProperty("fixture", fixture.name);
        report.addProperty("lang", fixture.lang);
        report.addProperty("target", fixture.target);
        report.addProperty("layer", fixture.layer.name().toLowerCase(Locale.ROOT));
        report.addProperty("instructionsInputBytes", AiText.utf8Bytes(options.instructions));
        report.addProperty("instructionsBytes",
                AiText.utf8Bytes(AiContract.normalizeSteering(options.instructions)));
        report.addProperty("instructionsSha256", com.eza.spicyex.lyrics.session.Digests.sha256(
                AiContract.normalizeSteering(options.instructions)));
        report.addProperty("configId", configId);
        report.addProperty("docDigest", docDigest);
        report.addProperty("promptVersion", promptVersion);
        report.addProperty("rows", rows.size());
        int sentRows = 0;
        for (AiLine row : rows) if (row.isSent()) sentRows++;
        report.addProperty("sentRows", sentRows);
        report.addProperty("expectedRows", sentRows);
        report.addProperty("providerCalls", log.calls.size());
        report.addProperty("wireCalls", log.wireCalls);
        report.add("calls", new Gson().toJsonTree(log.calls));
        report.addProperty("kind", outcome.kind.name());
        report.addProperty("failureToken", outcome.failureToken);
        report.addProperty("failureCategory", outcome.failureDetail.split(":")[0]);
        report.addProperty("durable", outcome.durable);
        AiPaidRecord record = outcome.record;
        int accepted = 0, missing = 0;
        for (LabRow row : fixture.rows) {
            if (row.lineClass == AiLineClass.STRUCTURAL) continue;
            String text = record == null ? null : record.item(row.id);
            if (text == null || text.isEmpty()) {
                missing++;
            } else {
                accepted++;
            }
        }
        int failed = Math.max(0, sentRows - accepted);
        report.addProperty("acceptedRows", accepted);
        report.addProperty("failedRows", failed);
        report.addProperty("missingRows", missing);
        report.addProperty("structuralCalls", log.structuralCalls);
        report.addProperty("complete", outcome.hasOutput() && record.isComplete());
        report.addProperty("partial", outcome.hasOutput() && !record.isComplete());
        report.addProperty("inputTokens", record == null ? 0 : record.inputTokens);
        report.addProperty("outputTokens", record == null ? 0 : record.outputTokens);
        report.addProperty("estimatedUsage", record != null && record.usageEstimated);
        int callInput = 0, callOutput = 0;
        for (JsonObject call : log.calls) {
            JsonElement input = call.get("inputTokens");
            JsonElement output = call.get("outputTokens");
            if (input.isJsonPrimitive() && input.getAsJsonPrimitive().isNumber()) {
                callInput += input.getAsInt();
            }
            if (output.isJsonPrimitive() && output.getAsJsonPrimitive().isNumber()) {
                callOutput += output.getAsInt();
            }
        }
        report.addProperty("callInputTokens", callInput);
        report.addProperty("callOutputTokens", callOutput);
        boolean anyReported = false, anyUnknown = false;
        int reasoningTotal = 0;
        Set<String> finishes = new LinkedHashSet<>();
        for (JsonObject call : log.calls) {
            finishes.add(call.get("finish").getAsString());
            JsonElement reasoning = call.get("reasoningTokens");
            if (reasoning.isJsonPrimitive() && reasoning.getAsJsonPrimitive().isNumber()) {
                anyReported = true;
                reasoningTotal += reasoning.getAsInt();
            } else {
                anyUnknown = true;
            }
        }
        if (anyReported && !anyUnknown) report.addProperty("reasoningTokens", reasoningTotal);
        else report.addProperty("reasoningTokens", "unknown");
        report.add("finishReasons", new Gson().toJsonTree(finishes));
        report.addProperty("stopped", log.stopped);
        report.addProperty("stopReason", log.stopReason);
        report.addProperty("elapsedMs", elapsedMs);
        return report;
    }

    private static JsonObject labEmitRecord(LabFixture fixture, LabOptions options,
                                            AiRunOutcome outcome, LabCallLog log) {
        JsonObject record = new JsonObject();
        record.addProperty("schema", LAB_SCHEMA);
        record.addProperty("arm", options.arm);
        record.addProperty("model", options.model);
        record.addProperty("providerVersion", options.providerVersion);
        record.addProperty("fixture", fixture.name);
        record.addProperty("lang", fixture.lang);
        record.addProperty("target", fixture.target);
        record.addProperty("layer", fixture.layer.name().toLowerCase(Locale.ROOT));
        record.addProperty("instructionsSha256", com.eza.spicyex.lyrics.session.Digests.sha256(
                AiContract.normalizeSteering(options.instructions)));
        JsonArray rows = new JsonArray();
        for (LabRow row : fixture.rows) {
            JsonObject value = new JsonObject();
            value.addProperty("id", row.id);
            value.addProperty("s", row.source);
            value.addProperty("c", row.lineClass.token);
            if (row.voice == null) value.add("v", JsonNull.INSTANCE);
            else value.addProperty("v", row.voice.token);
            value.addProperty("role", row.role);
            value.addProperty("song", row.song);
            boolean sent = row.lineClass != AiLineClass.STRUCTURAL;
            value.addProperty("sent", sent);
            String text = outcome.record == null ? null : outcome.record.item(row.id);
            if (text == null) value.add("o", JsonNull.INSTANCE);
            else value.addProperty("o", text);
            value.addProperty("accepted", !sent || (text != null && !text.isEmpty()));
            if (!sent) value.addProperty("failure", "not_sent");
            else if (text != null && !text.isEmpty()) value.addProperty("failure", "none");
            else if (log.rowFailures.containsKey(row.id)) {
                value.addProperty("failure", log.rowFailures.get(row.id));
            } else {
                value.addProperty("failure", "missing");
            }
            value.addProperty("sameAsSource", text != null && text.equals(row.source));
            rows.add(value);
        }
        record.add("rows", rows);
        return record;
    }

    private static AiGeminiProvider.Transport labTransport(String endpoint, OkHttpClient client,
                                                           RawUsage raw) {
        return new AiGeminiProvider.Transport() {
            public AiHttp.Result get(String url, Map<String, String> headers, AiSignal signal,
                                     int max) {
                throw new AssertionError("No discovery from lab provider");
            }

            public AiHttp.Result postJson(String url, Map<String, String> headers, String json,
                                          AiSignal signal, int max) {
                if (!url.equals(endpoint + "/chat/completions")) {
                    throw new AssertionError("Unexpected endpoint");
                }
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
                            bytes = source.readByteArray(Math.min(source.getBuffer().size(),
                                    (long) max + 1));
                        }
                        if (bytes.length > max) {
                            return AiHttp.Result.failed(AiProviderFailure.oversized(bytes.length));
                        }
                        String body = new String(bytes, StandardCharsets.UTF_8);
                        if (response.code() < 400) {
                            try {
                                JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
                                if (parsed.has("usage") && parsed.get("usage").isJsonObject()) {
                                    raw.usage = parsed.getAsJsonObject("usage");
                                }
                            } catch (RuntimeException ignored) {
                                // A body without parseable usage reports "unknown", never zero.
                            }
                        }
                        return AiHttp.Result.of(response.code(), body, bytes.length);
                    }
                } catch (IOException failure) {
                    return AiHttp.Result.failed(AiProviderFailure.deliveryUnknown(
                            AiProviderFailure.Cause.NETWORK, 0));
                }
            }
        };
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
