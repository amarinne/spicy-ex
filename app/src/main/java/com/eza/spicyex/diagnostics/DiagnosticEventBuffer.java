package com.eza.spicyex.diagnostics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure privacy filter and bounded JSONL buffer used only during explicit capture. */
public final class DiagnosticEventBuffer {
    public static final int MAX_BYTES = 96 * 1024;
    public static final int MAX_EVENTS = 256;
    private static final Gson GSON = new Gson();
    private static final Set<String> CONTEXT_KEYS = new HashSet<>(Arrays.asList(
            "result", "source", "provider", "status", "language", "timingType",
            "surface", "connected", "process", "flavor", "branch", "reason",
            "mounted", "cache", "enabled"
    ));

    private DiagnosticEventBuffer() {
    }

    public static Event event(long timestampMs, String component, String operation,
                              Throwable error, Map<String, String> context) {
        return new Event(
                Math.max(0L, timestampMs),
                safeToken(component, 48),
                safeToken(operation, 64),
                error == null ? "" : safeClassName(error.getClass().getName()),
                sanitizeContext(context)
        );
    }

    public static Result append(String existing, Event event) {
        List<String> lines = new ArrayList<>();
        if (existing != null && !existing.isEmpty()) {
            Collections.addAll(lines, existing.split("\\n"));
            lines.removeIf(String::isEmpty);
        }
        lines.add(encode(event));
        boolean truncated = false;
        while (lines.size() > MAX_EVENTS || utf8Bytes(join(lines)) > MAX_BYTES) {
            if (lines.isEmpty()) break;
            lines.remove(0);
            truncated = true;
        }
        String jsonl = join(lines);
        if (utf8Bytes(jsonl) > MAX_BYTES) {
            jsonl = "";
            truncated = true;
        }
        return new Result(jsonl, lines.size(), truncated);
    }

    public static Map<String, String> context(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) return Collections.emptyMap();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    public static String safeToken(String value, int maxChars) {
        if (value == null || maxChars <= 0) return "";
        String safe = value.replaceAll("[^0-9A-Za-z._:+,-]", "_");
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    private static String safeClassName(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[^0-9A-Za-z_.$]", "_");
        return safe.length() <= 128 ? safe : safe.substring(0, 128);
    }

    private static Map<String, String> sanitizeContext(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, String> output = new LinkedHashMap<>();
        for (String key : CONTEXT_KEYS) {
            if (!input.containsKey(key)) continue;
            String value = safeToken(input.get(key), 64);
            if (!value.isEmpty()) output.put(key, value);
            if (output.size() >= 12) break;
        }
        return output;
    }

    private static String encode(Event event) {
        JsonObject object = new JsonObject();
        object.addProperty("timestamp", event.timestampMs);
        object.addProperty("component", event.component);
        object.addProperty("operation", event.operation);
        if (!event.exceptionClass.isEmpty()) object.addProperty("exceptionClass", event.exceptionClass);
        JsonObject context = new JsonObject();
        for (Map.Entry<String, String> entry : event.context.entrySet()) {
            context.addProperty(entry.getKey(), entry.getValue());
        }
        object.add("context", context);
        return GSON.toJson(object);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static final class Event {
        public final long timestampMs;
        public final String component;
        public final String operation;
        public final String exceptionClass;
        public final Map<String, String> context;

        Event(long timestampMs, String component, String operation, String exceptionClass,
              Map<String, String> context) {
            this.timestampMs = timestampMs;
            this.component = component;
            this.operation = operation;
            this.exceptionClass = exceptionClass;
            this.context = context;
        }
    }

    public static final class Result {
        public final String jsonl;
        public final int eventCount;
        public final boolean truncated;

        Result(String jsonl, int eventCount, boolean truncated) {
            this.jsonl = jsonl;
            this.eventCount = eventCount;
            this.truncated = truncated;
        }
    }
}
