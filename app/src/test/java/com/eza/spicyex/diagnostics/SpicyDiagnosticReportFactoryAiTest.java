package com.eza.spicyex.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.ai.AiContract;
import com.eza.spicyex.lyrics.ai.AiFinishReason;
import com.eza.spicyex.lyrics.ai.AiModelProbe;
import com.eza.spicyex.lyrics.ai.AiRequestLiveState;
import com.eza.spicyex.lyrics.ai.AiUsage;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Test;

/**
 * The AI diagnostics block and its public issue-draft line. The intake drops unknown fields and the
 * issue draft is what a reporter actually shows the world, so both are pinned here: key-free by
 * construction, bounded, and honest when nothing is known.
 */
public class SpicyDiagnosticReportFactoryAiTest {

    @After
    public void resetLiveState() {
        AiRequestLiveState.clearForTest();
    }

    private static JsonObject block(String probeToken, AiModelProbe.Trace trace) {
        return SpicyDiagnosticReportFactory.aiBlock("custom", true, "api.deepseek.com",
                "deepseek-v4-flash", "ready", probeToken, trace,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING),
                AiRequestLiveState.diagnosticSnapshot(LayerKind.SOUND));
    }

    @Test
    public void theBlockCarriesSetupLabelsWithoutTheEndpointUrlOrCredential() {
        JsonObject ai = block("", null);

        assertTrue(ai.get("enabled").getAsBoolean());
        assertEquals("custom", ai.get("provider").getAsString());
        assertEquals("api.deepseek.com", ai.get("endpointHost").getAsString());
        assertFalse("host only; scheme, path, and query stay out",
                ai.get("endpointHost").getAsString().contains("://"));
        assertEquals("deepseek-v4-flash", ai.get("model").getAsString());
        assertEquals("ready", ai.get("readiness").getAsString());
        assertFalse(ai.has("probeTrace"));
        assertFalse(ai.has("meaningAttempts"));
        assertFalse(ai.has("soundAttempts"));
        assertFalse(ai.has("meaningFailure"));
        assertFalse(ai.has("soundFailure"));
    }

    @Test
    public void aGeminiProviderOmitsTheEndpointFieldEntirely() {
        JsonObject ai = SpicyDiagnosticReportFactory.aiBlock("gemini", false, "", "gemini-2.5",
                "ready", "", null,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING),
                AiRequestLiveState.diagnosticSnapshot(LayerKind.SOUND));
        assertFalse(ai.has("endpointHost"));
    }

    @Test
    public void theProbeExchangeIsAttachedVerbatimAndKeyFree() {
        String request = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\","
                + "\"content\":\"" + AiContract.SYSTEM_PROMPT.substring(0, 40) + "\"}]}";
        String response = "{\"items\":[{\"id\":\"P0\",\"t\":\"night breaks / quietly\"}]}";
        AiModelProbe.Trace trace = new AiModelProbe.Trace(request, response,
                AiFinishReason.STOP, AiUsage.of(30, 60), 0);

        JsonObject ai = block("", trace);

        assertTrue(ai.has("probeTrace"));
        JsonObject exchange = ai.getAsJsonObject("probeTrace");
        assertEquals(request, exchange.get("request").getAsString());
        assertEquals(response, exchange.get("response").getAsString());
        assertEquals("stop", exchange.get("finish").getAsString());
        assertEquals(0, exchange.get("httpStatus").getAsInt());
    }

    @Test
    public void anHttpRejectionProbeCarriesItsStatusAndItsRequest() {
        JsonObject ai = block("provider:request_rejected", new AiModelProbe.Trace(
                "{\"model\":\"m\"}", "", null, AiUsage.UNREPORTED, 400));

        assertEquals("provider:request_rejected", ai.get("probe").getAsString());
        JsonObject exchange = ai.getAsJsonObject("probeTrace");
        assertEquals(400, exchange.get("httpStatus").getAsInt());
        assertEquals("", exchange.get("response").getAsString());
        assertEquals("unknown", exchange.get("finish").getAsString());
    }

    @Test
    public void theLastPerLayerFailureIsNamedWithItsHttpStatus() {
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "run1");
        AiRequestLiveState.attempt(LayerKind.MEANING, "digest", "run1",
                "C0", 1, "{\"lyrics\":\"failed request\"}");
        AiRequestLiveState.fail(LayerKind.MEANING, "digest", "run1", "truncated", 429, "");

        JsonObject ai = block("", null);

        JsonObject meaning = ai.getAsJsonObject("meaningFailure");
        assertEquals("truncated", meaning.get("reason").getAsString());
        assertEquals(429, meaning.get("status").getAsInt());
        JsonObject attempt = ai.getAsJsonArray("meaningAttempts").get(0).getAsJsonObject();
        assertEquals("failed", attempt.get("phase").getAsString());
        assertTrue(attempt.get("payload").getAsString().contains("failed request"));
        assertFalse(ai.has("soundFailure"));
    }

    @Test
    public void recentSuccessAndEarlierFailureBothCarryBoundedPayloads() {
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "failed");
        AiRequestLiveState.attempt(LayerKind.MEANING, "digest", "failed",
                "C0", 1, "{\"lyrics\":\"bad attempt\"}");
        AiRequestLiveState.fail(LayerKind.MEANING, "digest", "failed",
                "provider:request_rejected", 400, "ignored detail");
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "success");
        AiRequestLiveState.attempt(LayerKind.MEANING, "digest", "success",
                "C0", 1, "{\"lyrics\":\"good attempt\"}");
        AiRequestLiveState.complete(LayerKind.MEANING, "digest", "success");

        JsonObject ai = block("", null);

        assertEquals(2, ai.getAsJsonArray("meaningAttempts").size());
        JsonObject success = ai.getAsJsonArray("meaningAttempts").get(0).getAsJsonObject();
        JsonObject failure = ai.getAsJsonArray("meaningAttempts").get(1).getAsJsonObject();
        assertEquals("complete", success.get("phase").getAsString());
        assertTrue(success.get("payload").getAsString().contains("good attempt"));
        assertEquals("failed", failure.get("phase").getAsString());
        assertTrue(failure.get("payload").getAsString().contains("bad attempt"));
        assertEquals("provider:request_rejected", failure.get("reason").getAsString());
        assertEquals(400, failure.get("status").getAsInt());
        assertFalse(failure.has("failureDetail"));
    }

    @Test
    public void liveRequestPayloadIsCappedPerAttempt() {
        StringBuilder oversized = new StringBuilder();
        while (oversized.length() < 30 * 1024) oversized.append('x');
        AiRequestLiveState.begin(LayerKind.SOUND, "digest", "run");
        AiRequestLiveState.attempt(LayerKind.SOUND, "digest", "run",
                "C0", 1, oversized.toString());
        AiRequestLiveState.complete(LayerKind.SOUND, "digest", "run");

        String payload = block("", null).getAsJsonArray("soundAttempts")
                .get(0).getAsJsonObject().get("payload").getAsString();

        assertEquals(24 * 1024, DiagnosticReportContract.utf8Bytes(payload));
    }

    @Test
    public void theIssueLineNamesProviderHostModelReadinessAndTheLastFailure() {
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "run1");
        AiRequestLiveState.fail(LayerKind.MEANING, "digest", "run1", "finish:length", 0, "");

        String line = SpicyDiagnosticReportFactory.aiIssueLine(block("finish:length", null));

        assertTrue(line.startsWith("- **AI:** provider=`custom`"));
        assertTrue(line.contains("host=`api.deepseek.com`"));
        assertTrue(line.contains("model=`deepseek-v4-flash`"));
        assertTrue(line.contains("ready=`ready`"));
        assertTrue(line.contains("last test=`finish:length`"));
        assertTrue(line.contains("meaning last failed: `finish:length`"));
    }

    @Test
    public void theIssueLineIsAbsentWhenThereIsNoAiBlock() {
        assertEquals("", SpicyDiagnosticReportFactory.aiIssueLine(null));
    }

    @Test
    public void aDisabledSetupStillProducesAnHonestLine() {
        JsonObject ai = SpicyDiagnosticReportFactory.aiBlock("gemini", false, "", "",
                "disabled", "", null,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING),
                AiRequestLiveState.diagnosticSnapshot(LayerKind.SOUND));
        String line = SpicyDiagnosticReportFactory.aiIssueLine(ai);
        assertTrue(line.contains("provider=`gemini`"));
        assertTrue(line.contains("ready=`disabled`"));
        assertFalse(line.contains("host="));
    }
}
