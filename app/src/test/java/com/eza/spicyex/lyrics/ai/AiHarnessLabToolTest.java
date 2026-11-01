package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Offline checks for the lab CLI: parsing, private-text refusal, caps, and metadata-only reports. */
public class AiHarnessLabToolTest {
    private static AiHarnessModelComparisonTool.LabFixture fixture(int rows) {
        AiHarnessModelComparisonTool.LabFixture fixture = new AiHarnessModelComparisonTool.LabFixture(
                "synthetic", "ja", "en", LayerKind.MEANING, false);
        for (int i = 0; i < rows; i++) {
            fixture.rows.add(new AiHarnessModelComparisonTool.LabRow("row_" + i,
                    "synthetic source " + i, "target", "song", null, AiLineClass.ORDINARY, null));
        }
        return fixture;
    }

    private static AiHarnessModelComparisonTool.LabOptions options(String instructions) {
        AiHarnessModelComparisonTool.LabOptions options =
                new AiHarnessModelComparisonTool.LabOptions();
        options.model = "fake-model";
        options.instructions = instructions;
        options.arm = "control";
        return options;
    }

    @Test public void fixtureFileAcceptsSimpleAndLedgerRowShapes() throws Exception {
        Path file = Files.createTempFile("ai-lab-fixtures-", ".json");
        try {
            String json = "{\"fixtures\":[{\"name\":\"mixed\",\"lang\":\"ja\",\"target\":\"en\","
                    + "\"rows\":[{\"source\":\"alpha\",\"class\":\"ordinary\",\"voice\":\"primary\"},"
                    + "{\"id\":\"r1#abcd\",\"s\":\"beta\",\"c\":\"adlib\",\"v\":\"background\","
                    + "\"p\":\"baseline\"}]},{\"name\":\"items\",\"items\":["
                    + "{\"id\":\"r0#1111\",\"s\":\"gamma\",\"c\":\"ordinary\",\"v\":null}]}]}";
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            List<AiHarnessModelComparisonTool.LabFixture> fixtures =
                    AiHarnessModelComparisonTool.readLabFixtures(
                            Collections.singletonList(file.toString()));

            assertEquals(2, fixtures.size());
            AiHarnessModelComparisonTool.LabFixture mixed = fixtures.get(0);
            assertEquals("mixed", mixed.name);
            assertEquals("ja", mixed.lang);
            assertEquals(2, mixed.rows.size());
            assertEquals("alpha", mixed.rows.get(0).source);
            assertEquals("r1#abcd", mixed.rows.get(1).id);
            assertEquals(AiLineClass.ADLIB, mixed.rows.get(1).lineClass);
            assertEquals(AiVoiceHint.BACKGROUND, mixed.rows.get(1).voice);
            assertEquals("baseline", mixed.rows.get(1).baseline);
            assertEquals("gamma", fixtures.get(1).rows.get(0).source);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test public void emitTextPathInsideRepositoryIsRefused() throws Exception {
        try {
            AiHarnessModelComparisonTool.labPrivatePath("build/private-emit.jsonl", "--emit-text");
            fail("repository path accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside"));
        }
    }

    @Test public void callCapStopsTheRepairDispatchAndRecordsWhy() {
        // A malformed first answer makes the runtime ask once more; the cap refuses that dispatch.
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body("not json"));
        AiHarnessModelComparisonTool.LabExecution execution =
                AiHarnessModelComparisonTool.labRunFixture(fixture(3), options(""),
                        provider, null, 1);

        assertTrue(execution.log.stopped);
        assertEquals("max_calls", execution.log.stopReason);
        assertEquals(1, provider.calls.size());
        assertEquals(1, execution.report.get("providerCalls").getAsInt());
        assertTrue(execution.report.get("stopped").getAsBoolean());
        assertEquals("max_calls", execution.report.get("stopReason").getAsString());
    }

    @Test public void instructionsEnterTheRequestAndTheConfigurationIdentity() {
        FakeAiProvider first = new FakeAiProvider();
        FakeAiProvider second = new FakeAiProvider();
        AiHarnessModelComparisonTool.LabExecution plain =
                AiHarnessModelComparisonTool.labRunFixture(fixture(2),
                        options("Make the output natural."), first, null, 0);
        AiHarnessModelComparisonTool.LabExecution packed =
                AiHarnessModelComparisonTool.labRunFixture(fixture(2),
                        options("Make the output natural.\nKeep one person per song."),
                        second, null, 0);

        assertEquals("Make the output natural.", first.calls.get(0).request.instructions);
        assertNotEquals(plain.report.get("configId"), packed.report.get("configId"));
        assertEquals(plain.report.get("configId"),
                AiHarnessModelComparisonTool.labRunFixture(fixture(2),
                        options("Make the output natural."), new FakeAiProvider(), null, 0)
                        .report.get("configId"));
    }

    @Test public void reasoningTokensComeFromRawUsageAndStayUnknownWhenAbsent() {
        com.google.gson.JsonObject nested = com.google.gson.JsonParser.parseString(
                "{\"completion_tokens_details\":{\"reasoning_tokens\":42}}").getAsJsonObject();
        com.google.gson.JsonObject flat = com.google.gson.JsonParser.parseString(
                "{\"reasoning_tokens\":7}").getAsJsonObject();
        com.google.gson.JsonObject absent = com.google.gson.JsonParser.parseString(
                "{\"prompt_tokens\":10}").getAsJsonObject();

        assertEquals(42, AiHarnessModelComparisonTool.labReasoningTokens(nested).getAsInt());
        assertEquals(7, AiHarnessModelComparisonTool.labReasoningTokens(flat).getAsInt());
        assertEquals("unknown",
                AiHarnessModelComparisonTool.labReasoningTokens(absent).getAsString());
        assertEquals("unknown",
                AiHarnessModelComparisonTool.labReasoningTokens(null).getAsString());
    }

    @Test public void reportNeverCarriesRowText() {
        String source = "UNIQUE_SOURCE_TOKEN_9f3a";
        AiHarnessModelComparisonTool.LabFixture fixture = fixture(0);
        fixture.rows.add(new AiHarnessModelComparisonTool.LabRow("row_0", source, "target",
                "song", null, AiLineClass.ORDINARY, null));
        AiHarnessModelComparisonTool.LabExecution execution =
                AiHarnessModelComparisonTool.labRunFixture(fixture, options(""), new FakeAiProvider(),
                        null, 0);

        String report = execution.report.toString();
        assertFalse(report.contains(source));
        assertFalse(report.contains("will stay here"));
        assertTrue(execution.emit.toString().contains(source));
    }
}
