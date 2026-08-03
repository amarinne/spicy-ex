package com.eza.spicyex.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiagnosticJsonPreviewFormatterTest {
    @Test
    public void expandsMultilineDiagnosticsWithoutMutatingPayload() {
        JsonObject report = new JsonObject();
        report.addProperty("reportId", "R1-00000000000000000000000002");
        JsonObject raw = new JsonObject();
        raw.addProperty("diagnosticEventsAndLogs", "first event\nsecond event");
        raw.addProperty("crashExcerpt", "");
        raw.addProperty("lsposedModuleLines", "module line");
        JsonObject settings = new JsonObject();
        settings.addProperty("lyricsProvider", "automatic");
        raw.add("runtimeSettings", settings);
        report.add("rawDiagnostics", raw);
        String payload = report.toString();

        DiagnosticJsonPreviewFormatter.Preview preview =
                DiagnosticJsonPreviewFormatter.format(payload);

        assertNotNull(preview);
        assertEquals("first event\nsecond event", preview.diagnosticEventsAndLogs);
        assertEquals("module line", preview.lsposedModuleLines);
        assertFalse(preview.reportJson.contains("rawDiagnostics"));
        assertTrue(preview.runtimeSettingsJson.contains("\n"));
        assertEquals(report, JsonParser.parseString(payload).getAsJsonObject());
    }

    @Test
    public void malformedPayloadFallsBackSafely() {
        assertNull(DiagnosticJsonPreviewFormatter.format("not-json"));
    }
}
