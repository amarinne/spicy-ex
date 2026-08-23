package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Surveys real OpenRouter models against the contract the app actually enforces.
 *
 * <p>Opt-in and skipped by default, because every model it touches costs a real billable request.
 * It runs only when {@code OPENROUTER_SURVEY=1} and {@code OPENROUTER_API_KEY} are both set:
 *
 * <pre>
 *   # .env:  OPENROUTER_SURVEY=1
 *   #        OPENROUTER_API_KEY=sk-or-…
 *   ./gradlew :app:testFullDebugUnitTest --rerun-tasks \
 *       --tests "com.eza.spicyex.lyrics.ai.OpenRouterModelSurveyTest" -i
 * </pre>
 *
 * <p>Both are required. The key alone is not enough on purpose: it lives in {@code .env} for other
 * tooling, and its presence must never turn a routine test run into a billed one.
 *
 * <p>What makes the result trustworthy is that nothing here re-implements the judgement.
 * {@link AiModelProbe} builds the same fixture, {@link AiOpenAiProvider} builds the same request
 * body — schema, reasoning effort, routing preference and all — and {@link AiResponseValidator}
 * decides pass or fail by the same rules a real song is held to. Only the socket is local, so a
 * verdict here is the verdict the app would reach on device.
 *
 * <p>Selection is deliberately not "every model". OpenRouter lists several hundred, most of them
 * variants of each other, and a survey that bills four hundred requests to learn the same thing
 * five ways is not a better survey. Defaults to the {@code :free} variants, which are both the
 * cheapest to ask and the weakest instruction-followers — the population the contract is actually
 * uncertain about.
 *
 * <ul>
 *   <li>{@code OPENROUTER_MODELS} — comma-separated ids, overriding discovery entirely</li>
 *   <li>{@code OPENROUTER_MODEL_FILTER} — {@code free} (default), {@code all}, or a substring</li>
 *   <li>{@code OPENROUTER_MODEL_LIMIT} — cap the count; default 24</li>
 * </ul>
 */
public class OpenRouterModelSurveyTest {

    private static final String BASE = AiSettings.OPENROUTER_BASE_URL;
    private static final File REPORT =
            new File("build/reports/openrouter-survey.tsv");
    /** Free tier allows twenty requests a minute; stay well under it. */
    private static final long PACING_MS = 3_500L;

    @Test
    public void surveyOpenRouterModelsAgainstTheContract() throws Exception {
        // Two gates, not one. The key lives in .env because other tooling needs it there, so its
        // mere presence must not turn an ordinary `./gradlew test` into a billed provider run —
        // which is exactly what happened the first time this existed.
        Assume.assumeTrue("set OPENROUTER_SURVEY=1 to run the billed model survey",
                "1".equals(env("OPENROUTER_SURVEY")));
        String key = env("OPENROUTER_API_KEY");
        Assume.assumeTrue("set OPENROUTER_API_KEY to run the model survey", !key.isEmpty());

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(AiContract.MAX_CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .callTimeout(AiContract.MAX_CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .build();

        List<String> models = selectModels(http, key);
        Assume.assumeTrue("no models matched the filter", !models.isEmpty());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            if (i > 0) Thread.sleep(PACING_MS);
            rows.add(probe(http, key, model));
            System.out.println(rows.get(rows.size() - 1).line());
        }
        write(rows);
        System.out.println("\nwrote " + REPORT.getAbsolutePath() + " (" + rows.size() + " models)");
    }

    // --- one model ----------------------------------------------------------

    private static Row probe(OkHttpClient http, String key, String model) {
        AiModelDescriptor descriptor = new AiModelDescriptor(model, "", AiContract.MAX_REQUEST_BYTES,
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                Collections.singletonList("chat.completions"));
        AiProviderConfig config = new AiProviderConfig(LayerKind.MEANING, BASE,
                AiSettings.OPENROUTER_PROVIDER_VERSION, descriptor, "en",
                AiContract.PROMPT_VERSION, false);

        AiOpenAiProvider provider = new AiOpenAiProvider(BASE, () -> key, transport(http, key));

        long startedAt = System.nanoTime();
        AiModelProbe.Result result;
        try {
            result = AiModelProbe.probe(provider, config, null);
        } catch (Throwable thrown) {
            result = null;
            return new Row(model, false, "exception:" + thrown.getClass().getSimpleName(),
                    null, null, elapsedMs(startedAt));
        }
        Integer in = result.trace.usage.input;
        Integer out = result.trace.usage.output;
        return new Row(model, result.ok, result.summary(), in, out, elapsedMs(startedAt));
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    // --- model selection ----------------------------------------------------

    private static List<String> selectModels(OkHttpClient http, String key) throws IOException {
        String explicit = env("OPENROUTER_MODELS");
        List<String> chosen = new ArrayList<>();
        if (!explicit.isEmpty()) {
            for (String id : explicit.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) chosen.add(trimmed);
            }
            return chosen;
        }

        String filter = env("OPENROUTER_MODEL_FILTER");
        if (filter.isEmpty()) filter = "free";
        int limit = 24;
        try {
            String raw = env("OPENROUTER_MODEL_LIMIT");
            if (!raw.isEmpty()) limit = Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ignoredUseDefault) {
            // A malformed limit is not worth failing a survey over.
        }

        Request request = new Request.Builder().url(BASE + "/models")
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .get().build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return chosen;
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has("data") || !object.get("data").isJsonArray()) return chosen;
            JsonArray data = object.getAsJsonArray("data");
            for (JsonElement element : data) {
                if (!element.isJsonObject()) continue;
                JsonElement id = element.getAsJsonObject().get("id");
                if (id == null || !id.isJsonPrimitive()) continue;
                String name = id.getAsString();
                boolean matches = "all".equals(filter)
                        ? true
                        : "free".equals(filter) ? name.endsWith(":free") : name.contains(filter);
                if (matches) chosen.add(name);
                if (chosen.size() >= limit) break;
            }
        }
        Collections.sort(chosen);
        return chosen;
    }

    // --- transport ----------------------------------------------------------

    /**
     * A real socket, bypassing {@link AiHttp} only because that class reaches into the Xposed
     * diagnostics buffer, which does not exist off device. The bounds it enforces are the
     * contract's own.
     */
    private static AiGeminiProvider.Transport transport(final OkHttpClient http, final String key) {
        return new AiGeminiProvider.Transport() {
            @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                               AiSignal signal, int maxBytes) {
                return send(http, new Request.Builder().url(url).get(), headers, maxBytes);
            }

            @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                    String json, AiSignal signal, int maxBytes) {
                RequestBody payload = RequestBody.create(json,
                        MediaType.parse("application/json; charset=utf-8"));
                return send(http, new Request.Builder().url(url).post(payload), headers, maxBytes);
            }
        };
    }

    private static AiHttp.Result send(OkHttpClient http, Request.Builder builder,
                                      Map<String, String> headers, int maxBytes) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (text.length() > maxBytes) {
                return AiHttp.Result.failed(AiProviderFailure.oversized(text.length()));
            }
            return AiHttp.Result.of(response.code(), text, text.length());
        } catch (IOException transfer) {
            return AiHttp.Result.failed(AiProviderFailure.deliveryUnknown(
                    AiProviderFailure.Cause.NETWORK, 0));
        }
    }

    // --- reporting ----------------------------------------------------------

    private static final class Row {
        final String model;
        final boolean ok;
        final String verdict;
        final Integer promptTokens;
        final Integer completionTokens;
        final long elapsedMs;

        Row(String model, boolean ok, String verdict, Integer promptTokens,
            Integer completionTokens, long elapsedMs) {
            this.model = model;
            this.ok = ok;
            this.verdict = verdict;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.elapsedMs = elapsedMs;
        }

        String line() {
            return String.format(Locale.ROOT, "%-52s %-5s %-34s in=%-6s out=%-6s %6d ms",
                    model, ok ? "PASS" : "FAIL", verdict,
                    promptTokens == null ? "?" : promptTokens,
                    completionTokens == null ? "?" : completionTokens,
                    elapsedMs);
        }
    }

    private static void write(List<Row> rows) throws IOException {
        File parent = REPORT.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.out.println("could not create " + parent + "; printing only");
            return;
        }
        Map<String, Integer> verdictCounts = new LinkedHashMap<>();
        try (Writer out = Files.newBufferedWriter(REPORT.toPath(), StandardCharsets.UTF_8)) {
            out.write("model\tverdict\tdetail\tpromptTokens\tcompletionTokens\telapsedMs\n");
            for (Row row : rows) {
                verdictCounts.merge(row.verdict, 1, Integer::sum);
                out.write(row.model + "\t" + (row.ok ? "pass" : "fail") + "\t" + row.verdict
                        + "\t" + (row.promptTokens == null ? "" : row.promptTokens)
                        + "\t" + (row.completionTokens == null ? "" : row.completionTokens)
                        + "\t" + row.elapsedMs + "\n");
            }
        }
        System.out.println("\nverdict tally:");
        for (Map.Entry<String, Integer> entry : verdictCounts.entrySet()) {
            System.out.println("  " + entry.getValue() + "x  " + entry.getKey());
        }
    }

    /**
     * The environment, falling back to a local {@code .env}.
     *
     * <p>Gradle forks test JVMs from a long-lived daemon, so a variable exported in the shell that
     * invoked the build is usually not visible here. The repository already keeps local credentials
     * in {@code .env} — {@code AGENTS.md} points the papercut tooling at the same file — and it is
     * gitignored, so reading it is both the convention and the safer path than passing a secret on
     * a command line that ends up in shell history.
     */
    private static String env(String name) {
        String value = System.getenv(name);
        if (value != null && !value.trim().isEmpty()) return value.trim();
        return fromDotEnv(name);
    }

    private static String fromDotEnv(String name) {
        for (String candidate : new String[] {".env", "../.env"}) {
            File file = new File(candidate);
            if (!file.isFile()) continue;
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int split = trimmed.indexOf('=');
                    if (split <= 0 || !name.equals(trimmed.substring(0, split).trim())) continue;
                    String value = trimmed.substring(split + 1).trim();
                    if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            } catch (IOException unreadable) {
                // A missing or unreadable .env just means the variable is not set here.
            }
        }
        return "";
    }
}
