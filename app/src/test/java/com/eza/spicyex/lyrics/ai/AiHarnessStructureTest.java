package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.*;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.lyrics.processing.LyricsSoundLane;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic offline evidence for atomic rows, durable fallback, and bounded repair instructions. */
public class AiHarnessStructureTest {
    @After public void restoreStore() { AiRecordStores.installFactoryForTest(null); }

    private static AiChunkPlanner.Input input(List<AiLine> rows) {
        AiChunkPlanner.Input in = new AiChunkPlanner.Input();
        in.rows = rows;
        in.model = FakeAiProvider.DEFAULT_MODEL;
        in.target = "Latin";
        in.layer = LayerKind.SOUND;
        return in;
    }

    private static AiLayerRunner.Args args(List<AiLine> rows, FakeAiProvider provider) {
        AiProviderConfig providerConfig = new AiProviderConfig(LayerKind.SOUND, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "Latin", AiContract.SOUND_PROMPT_VERSION, false);
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = new AiRunConfig(LayerKind.SOUND, "fixture-doc", "fixture-config", "fake",
                AiLyricContext.EMPTY, providerConfig, "", null, null);
        args.rows = rows;
        args.provider = provider;
        args.store = new FakeAiRecordStore();
        return args;
    }

    private static List<AiLine> rows() {
        return Arrays.asList(AiLine.of("r0#aaaa", "あ", null, false),
                AiLine.of("r1#bbbb", "い", null, false));
    }

    private static FakeAiProvider fallbackProvider() {
        return new FakeAiProvider((request, config, signal) -> AiProviderResult.ok(
                FakeAiProvider.itemsJson(request, Arrays.asList("a", "い")), AiUsage.of(7, 3),
                AiFinishReason.STOP, 1), (request, config, signal) -> AiProviderResult.ok(
                FakeAiProvider.itemsJson(request, Collections.singletonList("い")),
                AiUsage.of(5, 2), AiFinishReason.STOP, 1));
    }

    private static AiChunkFailure validate(AiPaidRecord record, List<AiLine> rows, String target)
            throws Exception {
        Method method = AiSoundRun.class.getDeclaredMethod("validateStoredOutput",
                AiPaidRecord.class, List.class, String.class);
        method.setAccessible(true);
        return (AiChunkFailure) method.invoke(null, record, rows, target);
    }

    @Test public void splitRowCrossingAnItemBoundaryMovesIntact() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 63; i++) rows.add(AiLine.of("r" + i, "a", null, false));
        rows.add(AiLine.of("split", "a / b", null, false));
        for (int i = 64; i < 130; i++) rows.add(AiLine.of("r" + i, "a", null, false));
        AiChunkPlan plan = AiChunkPlanner.plan(input(rows));
        assertEquals(63, plan.chunks.get(0).items.size());
        assertEquals(Arrays.asList("split~0", "split~1"),
                plan.chunks.get(1).itemIds().subList(0, 2));
        assertFalse(plan.chunks.get(0).itemIds().contains("split~0"));
    }

    @Test public void replanKeepsTwoMultisegmentRowsWholeAndStable() {
        AiChunkPlanner.Input in = input(Arrays.asList(AiLine.of("one", "a / b", null, false),
                AiLine.of("two", "c / d", null, false)));
        AiPlannedChunk parent = AiChunkPlanner.plan(in).chunks.get(0);
        List<AiPlannedChunk> children = AiChunkPlanner.replan(in, parent);
        assertEquals(2, children.size());
        assertEquals(Arrays.asList("one~0", "one~1"), children.get(0).itemIds());
        assertEquals(Arrays.asList("two~0", "two~1"), children.get(1).itemIds());
        assertEquals(children.get(1).requestJson, AiChunkPlanner.replan(in, parent).get(1).requestJson);
    }

    @Test public void terminalProofSurvivesCopyStrippingAndReadOnlyReplay() throws Exception {
        FakeAiProvider provider = fallbackProvider();
        AiLayerRunner.Args args = args(rows(), provider);
        AiRunOutcome live = AiLayerRunner.run(args);
        AiChunkPlan plan = AiChunkPlanner.plan(input(rows()));
        assertTrue(live.record.hasTerminalFallback(plan));
        assertEquals(Collections.singletonList("r1#bbbb"), live.record.chunk("C0").copy().fallbackRowIds);
        String stripped = AiPaidRecordCodec.encode(live.record, false);
        assertFalse(stripped.contains("requestJson"));
        AiPaidRecord reread = AiPaidRecordCodec.decode(stripped);
        assertNotNull(reread);
        assertTrue(reread.hasTerminalFallback(plan));
        assertNull(validate(reread, rows(), "Latin"));
        FakeAiRecordStore disk = (FakeAiRecordStore) args.store;
        disk.commit(args.config, reread);
        int commits = disk.commits, reservations = disk.reservations, releases = disk.releases;
        String before = AiPaidRecordCodec.encode(disk.peek(args.config));
        args.allowProviderRequest = false;
        AiRunOutcome replay = AiLayerRunner.run(args);
        assertEquals(AiRunOutcome.Kind.REUSED, replay.kind);
        assertEquals(2, provider.calls.size());
        assertEquals(commits, disk.commits);
        assertEquals(reservations, disk.reservations);
        assertEquals(releases, disk.releases);
        assertEquals(before, AiPaidRecordCodec.encode(disk.peek(args.config)));
        assertEquals(12, replay.record.inputTokens);
        assertEquals(5, replay.record.outputTokens);
        assertFalse(replay.record.isComplete());
    }

    @Test public void missingSuccessfulRowAndUnprovenOrInterruptedOmissionsAreRejected() throws Exception {
        AiPaidRecord proven = AiLayerRunner.run(args(rows(), fallbackProvider())).record;
        AiChunkPlan plan = AiChunkPlanner.plan(input(rows()));
        JsonObject damaged = JsonParser.parseString(AiPaidRecordCodec.encode(proven)).getAsJsonObject();
        // Reconstruct a retained record with successful output deleted, retaining fallback evidence.
        AiPaidRecord missingSuccess = AiPaidRecord.begin(args(rows(), new FakeAiProvider()).config, 1);
        missingSuccess.putChunk("C0", proven.chunk("C0").copy());
        assertFalse(missingSuccess.hasTerminalFallback(plan));
        assertNotNull(validate(missingSuccess, rows(), "Latin"));
        AiPaidRecord unproven = AiPaidRecordCodec.decode(damaged.toString());
        unproven.chunk("C0").fallbackRowIds.clear();
        assertFalse(unproven.hasTerminalFallback(plan));
        assertNotNull(validate(unproven, rows(), "Latin"));
        AiPaidRecord complete = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(proven));
        complete.status = AiPaidRecord.Status.COMPLETE;
        assertNotNull(validate(complete, rows(), "Latin"));
        proven.chunk("C0").status = AiChunkRecord.Status.PENDING;
        assertFalse(proven.hasTerminalFallback(plan));
        assertNotNull(validate(proven, rows(), "Latin"));
        for (AiPaidRecord rejected : Arrays.asList(missingSuccess, unproven, proven)) {
            FakeAiProvider provider = new FakeAiProvider();
            AiLayerRunner.Args readonly = args(rows(), provider);
            FakeAiRecordStore disk = (FakeAiRecordStore) readonly.store;
            disk.commit(readonly.config, rejected);
            readonly.allowProviderRequest = false;
            assertFalse(AiLayerRunner.run(readonly).hasOutput());
            assertTrue(provider.calls.isEmpty());
            assertEquals(0, disk.reservations);
            assertEquals(1, disk.commits);
        }
    }

    @Test public void fallbackEvidenceMustBelongToRequestedRowsAndTerminalFallbackStatus() {
        AiPaidRecord record = AiLayerRunner.run(args(rows(), fallbackProvider())).record;
        record.chunk("C0").fallbackRowIds.add("injected");
        assertNull(AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record)));
        record.chunk("C0").fallbackRowIds.remove("injected");
        record.chunk("C0").fallbackRowIds.add("r1#bbbb");
        assertNull(AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record)));
        record.chunk("C0").fallbackRowIds.remove(1);
        record.chunk("C0").status = AiChunkRecord.Status.COMPLETE;
        assertNull(AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record)));
    }

    @Test public void storedSoundUsesEachCorrespondingSegmentAndRejectsUnsentOutput() throws Exception {
        List<AiLine> rows = Collections.singletonList(AiLine.of("r0", "あ / OK", null, false));
        AiPaidRecord record = AiPaidRecord.begin(args(rows, new FakeAiProvider()).config, 1);
        record.status = AiPaidRecord.Status.COMPLETE;
        record.putItem("r0", "ア / OK");
        assertNull(validate(record, rows, "Kana"));
        record.putItem("r0", "OK / ア");
        assertNotNull(validate(record, rows, "Kana"));
        record.putItem("r0", "ア / OK");
        record.putItem("unrequested", "ア");
        assertNotNull(validate(record, rows, "Kana"));
    }

    @Test public void storedOrdinaryRowCannotConsistOnlyOfEmptySegments() throws Exception {
        List<AiLine> rows = Collections.singletonList(AiLine.of("r0", "alpha / beta", null, false));
        AiPaidRecord record = AiPaidRecord.begin(args(rows, new FakeAiProvider()).config, 1);
        record.status = AiPaidRecord.Status.COMPLETE;
        record.putItem("r0", " / ");
        assertNotNull(validate(record, rows, "Latin"));
        record.putItem("r0", "a / ");
        assertNull(validate(record, rows, "Latin"));
    }

    @Test public void liveAndStoredSoundAgreeOnPerSegmentByteLimit() throws Exception {
        String text = String.join("", Collections.nCopies(3000, "a"));
        List<AiLine> rows = Collections.singletonList(AiLine.of("r0", "あ / い", null, false));
        AiRunOutcome outcome = AiLayerRunner.run(args(rows, new FakeAiProvider(
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Arrays.asList(text, text)), AiUsage.of(2, 3), AiFinishReason.STOP, 1))));
        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertTrue(outcome.record.isComplete());
        assertNull(validate(outcome.record, rows, "Latin"));
        outcome.record.putItem("r0", text + text + " / " + text);
        assertNotNull(validate(outcome.record, rows, "Latin"));
    }

    @Test public void replannedParentAggregatesExactChildFallbackEvidence() {
        List<AiLine> rows = Arrays.asList(AiLine.of("one", "あ / い", null, false),
                AiLine.of("two", "う / え", null, false),
                AiLine.of("three", "あ / い", null, false),
                AiLine.of("four", "う / え", null, false));
        FakeAiProvider provider = new FakeAiProvider(
                FakeAiProvider.body("", AiUsage.of(2, 1), AiFinishReason.LENGTH),
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Arrays.asList("a", "i", "u", "e")), AiUsage.of(3, 2), AiFinishReason.STOP, 1),
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Arrays.asList("a", "i", "う", "え")), AiUsage.of(3, 2), AiFinishReason.STOP, 1),
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Arrays.asList("う", "え")), AiUsage.of(3, 2), AiFinishReason.STOP, 1));
        AiRunOutcome result = AiLayerRunner.run(args(rows, provider));
        assertEquals(result.failureDetail, AiRunOutcome.Kind.COMPLETED, result.kind);
        assertEquals(Collections.singletonList("four"), result.record.chunk("C0").fallbackRowIds);
        assertEquals(Collections.singletonList("four"), result.record.chunk("C0.1").fallbackRowIds);
        assertTrue(result.record.hasTerminalFallback(AiChunkPlanner.plan(input(rows))));
        assertEquals("a / i", result.record.item("one"));
        assertEquals("a / i", result.record.item("three"));
        assertNull(result.record.item("four"));
        assertEquals(11, result.record.inputTokens);
    }

    @Test public void duplicateJsonKeysAreRejectedEvenAfterNull() {
        for (String raw : new String[]{"{\"items\":null,\"items\":[]}",
                "{\"items\":[{\"id\":\"r0\",\"id\":\"r1\",\"t\":\"a\"}]}"}) {
            try { AiResponseReader.readItems(raw); fail("duplicate key accepted"); }
            catch (AiProtocolException expected) { assertTrue(expected.getMessage().contains("invalid_json")); }
        }
    }

    @Test public void repairNeverExceedsTheInputBoundWhenRepairPromptCannotFit() {
        AiLayerRunner.Args args = args(rows(), new FakeAiProvider());
        AiPlannedChunk planned = AiChunkPlanner.plan(input(rows())).chunks.get(0);
        int byteLimit = AiText.utf8Bytes(args.config.provider.systemPrompt())
                + AiText.utf8Bytes(planned.requestJson);
        AiModelDescriptor narrow = new AiModelDescriptor("fixture-narrow", "1",
                AiChunkPlanner.ceilHalf(byteLimit), 8192, Collections.singletonList("generateContent"));
        AiProviderConfig bounded = new AiProviderConfig(LayerKind.SOUND, null, "1", narrow,
                "Latin", AiContract.SOUND_PROMPT_VERSION, false);
        args.config = new AiRunConfig(LayerKind.SOUND, "fixture-doc", "fixture-config", "fake",
                AiLyricContext.EMPTY, bounded, "", null, null);
        FakeAiProvider provider = new FakeAiProvider(FakeAiProvider.body("invalid JSON"),
                (request, config, signal) -> {
                    int actual = AiText.utf8Bytes(config.systemPrompt()) + AiText.utf8Bytes(request.toJson());
                    assertTrue(AiChunkPlanner.ceilHalf(actual) <= narrow.inputTokenLimit);
                    assertFalse(config.repair);
                    return AiProviderResult.ok(FakeAiProvider.itemsJson(request, Arrays.asList("a", "i")),
                            AiUsage.of(3, 2), AiFinishReason.STOP, 1);
                });
        args.provider = provider;
        assertEquals(AiRunOutcome.Kind.COMPLETED, AiLayerRunner.run(args).kind);
        assertEquals(2, provider.calls.size());
    }

    @Test public void meaningAndSoundWrappersReplayPartialWithoutCredentialOrWrites() throws Exception {
        CanonicalBase base = new CanonicalBase("fixture-track", "id", "ja", "fixture", "fixture",
                "line", 1000, Arrays.asList(new CanonicalRow(0, "あ", 0, 500, false, Collections.emptyList()),
                new CanonicalRow(1, "い", 500, 750, false, Collections.emptyList()),
                new CanonicalRow(2, "う", 750, 1000, false, Collections.emptyList())));
        SoundEntry local = new SoundEntry(base.rows.get(0).rowId, "a", "Latin", null,
                Collections.singletonList(new SoundEntry.SpanReading("fixture-span", 0, "a")));
        SoundArtifact baseline = new SoundArtifact(base.digest, "local", new LayerProvenance(LayerAuthority.DETERMINISTIC, "fixture", "fixture", 1),
                Collections.singletonList(local), true);
        SettingsStore preferences = new SettingsStore(new FakeAndroidContext());
        preferences.put(Settings.AI_ENABLED, true);
        preferences.put(Settings.AI_MODEL_GEMINI, "fixture-model");
        AiSettings settings = new AiSettings(preferences, null);
        assertFalse(settings.canRequest());
        AiRecordStores.installFactoryForTest(ignored -> new AiRecordStore() {
            public AiPaidRecord read(AiRunConfig config) {
                List<AiLine> rows = config.layer == LayerKind.SOUND
                        ? AiRows.forSound(base, null, baseline, "Latin", false)
                        : AiRows.forMeaning(base, null, null, false);
                AiChunkPlanner.Input in = input(rows);
                in.layer = config.layer; in.target = config.targetLang;
                in.model = config.provider.model;
                AiPlannedChunk planned = AiChunkPlanner.plan(in).chunks.get(0);
                AiPaidRecord record = AiPaidRecord.begin(config, 1);
                AiChunkRecord chunk = AiChunkRecord.forChunk(planned);
                chunk.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
                chunk.fallbackRowIds.add(base.rows.get(2).rowId);
                record.putChunk(planned.id, chunk);
                for (AiLine row : rows) {
                    if (row.isSent() && !row.id.equals(base.rows.get(2).rowId))
                        record.putItem(row.id, config.layer == LayerKind.SOUND ? "i" : "fixture translation");
                }
                return AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record, false));
            }
            public Reservation reserve(AiRunConfig config, long max) { throw new AssertionError("reservation"); }
            public boolean commit(AiRunConfig config, AiPaidRecord record) { throw new AssertionError("write"); }
            public void release(AiRunConfig config) { throw new AssertionError("release"); }
            public void forget(AiRunConfig config) { throw new AssertionError("deletion"); }
        });
        AiMeaningRun.Result meaning = AiMeaningRun.run(null, settings, base, new com.eza.spicyex.lyrics.LyricsDocument(), null, false,
                "en", true, new AiSignal(), null);
        assertTrue(meaning.hasArtifact());
        assertFalse(meaning.artifact.partial);
        assertEquals(AiRunOutcome.Kind.REUSED, meaning.outcome.kind);
        AiSoundRun.Result sound = AiSoundRun.run(null, settings, base, new com.eza.spicyex.lyrics.LyricsDocument(), baseline, "Latin", false,
                true, new AiSignal(), null);
        assertTrue(sound.hasArtifact());
        assertTrue(sound.artifact.partial);
        assertEquals(AiRunOutcome.Kind.REUSED, sound.outcome.kind);
        assertNull(sound.artifact.sound(base.rows.get(0).rowId));
        assertNull(sound.artifact.sound(base.rows.get(2).rowId));
        List<SoundEntry> composed = AiSoundOverlay.compose(Collections.singletonList(local),
                Collections.singletonList(sound.artifact.sound(base.rows.get(1).rowId)));
        assertEquals(2, composed.size());
        assertSame(local, composed.get(0));
        assertTrue(composed.get(1).spanReadings.isEmpty());
        Method coverage = LyricsSoundLane.class.getDeclaredMethod("isPartialCoverage", List.class, Set.class);
        coverage.setAccessible(true);
        Set<String> required = new HashSet<>();
        for (CanonicalRow row : base.rows) required.add(row.rowId);
        assertTrue((Boolean) coverage.invoke(null, composed, required));
    }
}
