package com.eza.spicyex.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class JapaneseRomajiProjectionRunner {
    private static final String DIR = "/japanese-romaji-projection/";

    static final class Run {
        final JsonObject projection;
        final JsonObject manifest;
        final JsonObject product;
        final List<String> problems;

        Run(JsonObject projection, JsonObject manifest, JsonObject product, List<String> problems) {
            this.projection = projection;
            this.manifest = manifest;
            this.product = product;
            this.problems = problems;
        }
    }

    private JapaneseRomajiProjectionRunner() {
    }

    static Run run(String implementationVersion) throws Exception {
        String projectionRaw = resource("japanese-romaji-projection-v1.json");
        JsonObject projection = JsonParser.parseString(projectionRaw).getAsJsonObject();
        JsonObject manifest = JsonParser.parseString(resource("manifest.json")).getAsJsonObject();
        ArrayList<String> problems = new ArrayList<>();
        if (implementationVersion == null || implementationVersion.trim().isEmpty()) {
            problems.add("missing implementationVersion");
        }
        if (!sha256(projectionRaw).equals(manifest.get("projectionSha256").getAsString())) {
            problems.add("projection hash mismatch");
        }
        if (!projection.get("projectionVersion").getAsString()
                .equals(manifest.get("projectionVersion").getAsString())) {
            problems.add("projection version mismatch");
        }

        Set<String> seen = new LinkedHashSet<>();
        JsonArray results = new JsonArray();
        for (JsonElement element : projection.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            String id = item.get("id").getAsString();
            if (!seen.add(id)) problems.add("duplicate case " + id);
            String input = item.get("inputKana").getAsString();
            ArrayList<String> parts = new ArrayList<>();
            if (item.has("tokenParts")) {
                for (JsonElement part : item.getAsJsonArray("tokenParts")) parts.add(part.getAsString());
            } else {
                parts.add(input);
            }
            ArrayList<JapaneseReadingPolicyModels.BoundaryEvidence> boundaries = new ArrayList<>();
            if (item.has("boundaries")) {
                for (JsonElement boundaryElement : item.getAsJsonArray("boundaries")) {
                    JsonObject boundary = boundaryElement.getAsJsonObject();
                    boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                            boundary.get("offset").getAsInt(), boundary.get("kind").getAsString(),
                            boundary.get("strength").getAsString(), null));
                }
            }
            SpicyJapaneseChineseProcessor.JapaneseReading reading =
                    SpicyJapaneseChineseProcessor.debugJapaneseProjectionForTest(input, parts, boundaries);
            ArrayList<String> diagnostics = new ArrayList<>(new LinkedHashSet<>(reading.diagnostics));
            Collections.sort(diagnostics);
            JsonObject result = new JsonObject();
            result.addProperty("caseId", id);
            result.addProperty("romaji", reading.romaji);
            JsonArray diagnosticJson = new JsonArray();
            for (String diagnostic : diagnostics) diagnosticJson.add(diagnostic);
            result.add("diagnostics", diagnosticJson);
            results.add(result);

            if (!reading.romaji.equals(item.get("expectedRomaji").getAsString())) {
                problems.add(id + ": romaji mismatch");
            }
            Set<String> expectedDiagnostics = new LinkedHashSet<>();
            if (item.has("expectedDiagnostics")) {
                for (JsonElement diagnostic : item.getAsJsonArray("expectedDiagnostics")) {
                    expectedDiagnostics.add(diagnostic.getAsString());
                }
            }
            if (!new LinkedHashSet<>(diagnostics).equals(expectedDiagnostics)) {
                problems.add(id + ": diagnostics mismatch");
            }
        }

        JsonObject product = new JsonObject();
        product.addProperty("schema", "lyrics-language-lab-japanese-romaji-projection-product-results");
        product.addProperty("schemaVersion", 1);
        product.addProperty("product", "spotifyplus-mobilelyrics");
        product.addProperty("implementationVersion", implementationVersion);
        product.addProperty("projectionVersion", manifest.get("projectionVersion").getAsString());
        product.addProperty("projectionSha256", manifest.get("projectionSha256").getAsString());
        product.add("results", results);
        return new Run(projection, manifest, product, problems);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = JapaneseRomajiProjectionRunner.class.getResourceAsStream(DIR + name)) {
            if (in == null) throw new IllegalStateException("missing vendored resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
}
