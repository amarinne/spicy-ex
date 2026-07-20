package com.eza.spicyex.lyrics;

import com.google.gson.Gson;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class JapaneseReadingPolicyRunner {
    private static final String DIR = "/japanese-reading-policy/";

    static final class Run {
        final JsonObject policy;
        final JsonObject conformance;
        final JsonObject manifest;
        final JsonObject product;
        final List<String> problems;

        Run(JsonObject policy, JsonObject conformance, JsonObject manifest,
            JsonObject product, List<String> problems) {
            this.policy = policy;
            this.conformance = conformance;
            this.manifest = manifest;
            this.product = product;
            this.problems = problems;
        }
    }

    private JapaneseReadingPolicyRunner() {
    }

    static Run run(String implementationVersion) throws Exception {
        String policyRaw = resource("japanese-policy-v1.json");
        String conformanceRaw = resource("japanese-conformance-v1.json");
        JsonObject policy = JsonParser.parseString(policyRaw).getAsJsonObject();
        JsonObject conformance = JsonParser.parseString(conformanceRaw).getAsJsonObject();
        JsonObject manifest = JsonParser.parseString(resource("manifest.json")).getAsJsonObject();
        ArrayList<String> problems = new ArrayList<>();
        if (implementationVersion == null || implementationVersion.trim().isEmpty()) {
            problems.add("missing implementationVersion");
        }
        if (!sha256(policyRaw).equals(manifest.get("policySha256").getAsString())) problems.add("policy hash mismatch");
        if (!sha256(conformanceRaw).equals(manifest.get("conformanceSha256").getAsString())) problems.add("conformance hash mismatch");
        if (!policy.get("policyVersion").getAsString().equals(manifest.get("policyVersion").getAsString())) problems.add("policy version mismatch");
        if (!conformance.get("corpusVersion").getAsString().equals(manifest.get("corpusVersion").getAsString())) problems.add("corpus version mismatch");

        Map<String, Integer> ruleVersions = new TreeMap<>();
        for (JsonElement element : policy.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            ruleVersions.put(rule.get("id").getAsString(), rule.get("version").getAsInt());
        }

        JsonArray results = new JsonArray();
        Set<String> seenCases = new LinkedHashSet<>();
        for (JsonElement caseElement : conformance.getAsJsonArray("cases")) {
            JsonObject item = caseElement.getAsJsonObject();
            String id = item.get("id").getAsString();
            if (!seenCases.add(id)) problems.add("duplicate case " + id);
            String input = item.get("input").getAsString();
            ArrayList<JapaneseReadingPolicyModels.BoundaryEvidence> boundaries = new ArrayList<>();
            if (item.has("boundaries")) {
                for (JsonElement boundaryElement : item.getAsJsonArray("boundaries")) {
                    JsonObject boundary = boundaryElement.getAsJsonObject();
                    boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                            boundary.get("offset").getAsInt(), boundary.get("kind").getAsString(),
                            boundary.get("strength").getAsString(), null));
                }
            }
            SpicyJapaneseChineseProcessor.JapaneseDebugSnapshot snapshot =
                    SpicyJapaneseChineseProcessor.debugJapaneseSnapshot(input, null, boundaries);
            JsonArray observedReadings = new JsonArray();
            ArrayList<int[]> focusRanges = new ArrayList<>();
            for (JsonElement expectedElement : item.getAsJsonArray("expected")) {
                JsonObject expected = expectedElement.getAsJsonObject();
                String surface = expected.get("surface").getAsString();
                int start = uniqueSurfaceStart(snapshot.displayText, surface, id, problems);
                int end = start < 0 ? start : start + surface.length();
                if (start >= 0) focusRanges.add(new int[]{
                        snapshot.displayText.codePointCount(0, start),
                        snapshot.displayText.codePointCount(0, end)});
                StringBuilder kana = new StringBuilder();
                StringBuilder romaji = new StringBuilder();
                for (SpicyJapaneseChineseProcessor.JapaneseDebugToken token : snapshot.tokens) {
                    if (start < 0 || token.displayEnd <= start || token.displayStart >= end) continue;
                    kana.append(token.selectedReading);
                }
                for (SpicyJapaneseChineseProcessor.ReadingGroup group : snapshot.groups) {
                    if (start < 0 || group.end <= start || group.start >= end || group.start < start) continue;
                    if (romaji.length() > 0) romaji.append(' ');
                    romaji.append(group.romaji);
                }
                JsonObject reading = new JsonObject();
                reading.addProperty("surface", surface);
                reading.addProperty("kana", kana.toString());
                reading.addProperty("romaji", romaji.toString());
                observedReadings.add(reading);
            }

            Set<String> firedRules = new LinkedHashSet<>();
            String action = "keep";
            for (JapaneseReadingPolicyModels.ReadingDecision decision : snapshot.readingDecisions) {
                boolean overlaps = false;
                if (decision.targetRange != null) {
                    for (int[] range : focusRanges) {
                        if (decision.targetRange.end > range[0] && decision.targetRange.start < range[1]) {
                            overlaps = true;
                            break;
                        }
                    }
                }
                if (!overlaps) continue;
                if ("select".equals(decision.action)) action = "select";
                else if (!"select".equals(action) && "abstain".equals(decision.action)) action = "abstain";
                if (decision.ruleId != null && !decision.ruleId.isEmpty()) {
                    firedRules.add(decision.ruleId);
                    Integer expectedVersion = ruleVersions.get(decision.ruleId);
                    if (expectedVersion == null || !expectedVersion.equals(decision.ruleVersion)) {
                        problems.add(id + ": rule version mismatch for " + decision.ruleId);
                    }
                }
            }

            ArrayList<String> sortedRules = new ArrayList<>(firedRules);
            Collections.sort(sortedRules);
            JsonArray ruleIds = new JsonArray();
            for (String ruleId : sortedRules) ruleIds.add(ruleId);
            JsonArray diagnostics = new JsonArray();
            for (String diagnostic : snapshot.diagnostics) diagnostics.add(diagnostic);

            JsonObject result = new JsonObject();
            result.addProperty("caseId", id);
            result.addProperty("action", action);
            result.add("ruleIds", ruleIds);
            result.add("readings", observedReadings);
            result.add("diagnostics", diagnostics);
            results.add(result);

            if (!action.equals(item.get("action").getAsString())) problems.add(id + ": action mismatch");
            if (!stringSet(ruleIds).equals(stringSet(item.getAsJsonArray("ruleIds")))) problems.add(id + ": rule IDs mismatch");
            if (!selectedReadings(observedReadings).equals(selectedReadings(item.getAsJsonArray("expected")))) problems.add(id + ": selected readings mismatch");
            JsonArray expectedDiagnostics = item.has("expectedDiagnostics")
                    ? item.getAsJsonArray("expectedDiagnostics") : new JsonArray();
            if (!stringSet(diagnostics).equals(stringSet(expectedDiagnostics))) {
                problems.add(id + ": diagnostics mismatch");
            }
        }

        JsonObject product = new JsonObject();
        product.addProperty("schema", "lyrics-language-lab-japanese-reading-product-results");
        product.addProperty("schemaVersion", 1);
        product.addProperty("product", "spotifyplus-mobilelyrics");
        product.addProperty("implementationVersion", implementationVersion);
        product.addProperty("policyVersion", manifest.get("policyVersion").getAsString());
        product.addProperty("corpusVersion", manifest.get("corpusVersion").getAsString());
        product.addProperty("policySha256", manifest.get("policySha256").getAsString());
        product.addProperty("conformanceSha256", manifest.get("conformanceSha256").getAsString());
        product.add("results", results);
        return new Run(policy, conformance, manifest, product, problems);
    }

    static String canonicalJson(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return new Gson().toJson(value);
        if (value.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (JsonElement item : value.getAsJsonArray()) {
                if (!first) out.append(',');
                first = false;
                out.append(canonicalJson(item));
            }
            return out.append(']').toString();
        }
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) sorted.put(entry.getKey(), entry.getValue());
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(new Gson().toJson(entry.getKey())).append(':').append(canonicalJson(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = JapaneseReadingPolicyRunner.class.getResourceAsStream(DIR + name)) {
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

    private static int uniqueSurfaceStart(String text, String surface, String caseId, List<String> problems) {
        int first = text.indexOf(surface);
        int second = first < 0 ? -1 : text.indexOf(surface, first + Math.max(1, surface.length()));
        if (first < 0 || second >= 0) {
            problems.add(caseId + ": expected exactly one " + surface + " occurrence");
            return -1;
        }
        return first;
    }

    private static Set<String> stringSet(JsonArray values) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement value : values) out.add(value.getAsString());
        return out;
    }

    private static List<String> selectedReadings(JsonArray values) {
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject item = value.getAsJsonObject();
            out.add(item.get("surface").getAsString() + " " + item.get("kana").getAsString());
        }
        return out;
    }
}
