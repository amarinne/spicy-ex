package com.eza.spicyex.diagnostics;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiagnosticReportContractTest {
    @Test
    public void reportIdUsesCrockfordEncodingOf128Bits() {
        byte[] bytes = new byte[16];
        bytes[15] = 1;
        String reportId = DiagnosticReportContract.reportIdFromBytes(bytes);
        assertEquals("R1-00000000000000000000000001", reportId);
        assertTrue(DiagnosticReportContract.validReportId(reportId));
        assertFalse(DiagnosticReportContract.validReportId("R1-0000000000000000000000000I"));
    }

    @Test
    public void utf8DescriptionBoundNeverSplitsCodePoint() {
        String value = "a🙂b";
        assertEquals("a🙂", DiagnosticReportContract.utf8Prefix(value, 5));
        assertEquals(5, DiagnosticReportContract.utf8Prefix(value, 5)
                .getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void draftRejectsLegacyStateAndTokenFields() {
        JsonObject root = minimalDraft();
        root.getAsJsonObject("productMetadata").addProperty("currentLyricState", "forbidden");
        assertTrue(SpicyDiagnosticReportFactory.containsForbiddenFields(root));
        assertNull(SpicyDiagnosticReportFactory.fromJson(root.toString()));

        root.getAsJsonObject("productMetadata").remove("currentLyricState");
        root.getAsJsonObject("rawDiagnostics").addProperty("token", "secret");
        assertNull(SpicyDiagnosticReportFactory.fromJson(root.toString()));
    }

    @Test
    public void githubDraftContainsPublicSummaryButNotPrivateDiagnostics() {
        JsonObject root = minimalDraft();
        root.getAsJsonObject("rawDiagnostics")
                .addProperty("diagnosticEventsAndLogs", "PRIVATE_CAPTURE_MARKER");
        SpicyDiagnosticReportFactory.Draft draft =
                SpicyDiagnosticReportFactory.fromJson(root.toString());
        assertNotNull(draft);
        assertTrue(draft.issueBody.contains("## Description"));
        assertTrue(draft.issueBody.contains("## Report details"));
        assertTrue(draft.issueBody.contains("## Diagnostic data"));
        assertTrue(draft.issueBody.contains("**Report ID:** `R1-00000000000000000000000002`"));
        assertTrue(draft.issueBody.contains(DiagnosticReportContract.DATA_POLICY_URL));
        assertTrue(draft.issueBody.contains("Test song"));
        assertTrue(draft.issueBody.contains("spotify:track:test"));
        assertFalse(draft.issueBody.contains("PRIVATE_CAPTURE_MARKER"));
        assertFalse(draft.issueBody.contains("PRIVATE LYRIC"));
        assertFalse(draft.issueBody.contains("diagnosticEventsAndLogs"));
    }

    @Test
    public void setupMetadataStatesRootIsNotRequired() {
        SpicySetupCheckPolicy.Result checks = SpicySetupCheckPolicy.resolve(
                new SpicySetupCheckPolicy.Input(
                        true, true, "lspatch", true, true,
                        "not_visible_or_missing", true, true, false, "disabled"));

        JsonObject json = checks.toJson();

        assertEquals("not_required", json.get("rootRequirement").getAsString());
        assertEquals("lspatch", json.get("installationMode").getAsString());
        assertTrue(json.get("runtimeHookActive").getAsBoolean());
        assertFalse(json.has("rootAccessStatus"));
    }

    @Test
    public void jsonPreviewIsPrettyPrintedAndEquivalent() {
        JsonObject root = minimalDraft();
        String pretty = SpicyDiagnosticReportFactory.prettyJson(root.toString());

        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  \"reportId\""));
        assertEquals(root, com.google.gson.JsonParser.parseString(pretty).getAsJsonObject());
    }

    private static JsonObject minimalDraft() {
        JsonObject root = new JsonObject();
        root.addProperty("envelopeVersion", 1);
        root.addProperty("reportId", "R1-00000000000000000000000002");
        root.addProperty("product", "spicy_ex");
        root.addProperty("productReportVersion", 2);
        root.addProperty("createdAtUtc", "2026-08-02T00:00:00Z");
        root.addProperty("category", "timing");
        root.addProperty("description", "Timing is late.");
        root.add("commonMetadata", new JsonObject());
        JsonObject product = new JsonObject();
        product.addProperty("flavor", "full");
        product.addProperty("runtimeStatus", "active");
        product.addProperty("runtimeBackend", "spicy");
        product.addProperty("hyperGlowBridgeStatus", "connected");
        JsonObject media = new JsonObject();
        media.addProperty("present", true);
        media.addProperty("trackUri", "spotify:track:test");
        media.addProperty("title", "Test song");
        media.addProperty("artist", "Test artist");
        media.addProperty("album", "Test album");
        media.addProperty("source", "network");
        media.addProperty("provider", "Spicy Lyrics");
        media.addProperty("language", "ja");
        media.addProperty("timingType", "Syllable");
        media.addProperty("lineIndex", 3);
        media.addProperty("originalLine", "PRIVATE LYRIC");
        media.addProperty("romanizedLine", "PRIVATE READING");
        media.addProperty("translatedLine", "PRIVATE TRANSLATION");
        media.addProperty("stateAgeMs", 50);
        product.add("currentMediaEvidence", media);
        root.add("productMetadata", product);
        root.add("capture", new JsonObject());
        JsonObject raw = new JsonObject();
        raw.addProperty("diagnosticEventsAndLogs", "");
        root.add("rawDiagnostics", raw);
        return root;
    }
}
