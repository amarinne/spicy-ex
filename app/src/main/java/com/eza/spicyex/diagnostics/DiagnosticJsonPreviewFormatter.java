package com.eza.spicyex.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Presentation-only formatting for the diagnostic JSON review dialog. */
final class DiagnosticJsonPreviewFormatter {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private DiagnosticJsonPreviewFormatter() {
    }

    static Preview format(String json) {
        if (json == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return null;
            JsonObject report = parsed.getAsJsonObject();
            JsonElement rawElement = report.get("rawDiagnostics");
            if (rawElement == null || !rawElement.isJsonObject()) return null;

            JsonObject raw = rawElement.getAsJsonObject();
            JsonObject reportWithoutRawDiagnostics = report.deepCopy();
            reportWithoutRawDiagnostics.remove("rawDiagnostics");
            JsonElement runtimeSettings = raw.get("runtimeSettings");
            return new Preview(
                    PRETTY_GSON.toJson(reportWithoutRawDiagnostics),
                    stringValue(raw, "diagnosticEventsAndLogs"),
                    stringValue(raw, "crashExcerpt"),
                    stringValue(raw, "lsposedModuleLines"),
                    runtimeSettings != null && runtimeSettings.isJsonObject()
                            ? PRETTY_GSON.toJson(runtimeSettings)
                            : "{}"
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    static final class Preview {
        final String reportJson;
        final String diagnosticEventsAndLogs;
        final String crashExcerpt;
        final String lsposedModuleLines;
        final String runtimeSettingsJson;

        Preview(String reportJson, String diagnosticEventsAndLogs, String crashExcerpt,
                String lsposedModuleLines, String runtimeSettingsJson) {
            this.reportJson = reportJson;
            this.diagnosticEventsAndLogs = diagnosticEventsAndLogs;
            this.crashExcerpt = crashExcerpt;
            this.lsposedModuleLines = lsposedModuleLines;
            this.runtimeSettingsJson = runtimeSettingsJson;
        }
    }
}
