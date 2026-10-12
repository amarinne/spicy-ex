package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Offline regressions for retained terminal ownership, omissions, and publication. */
public class AiTerminalProofTest {
    private static final List<AiLine> ROWS = Arrays.asList(
            AiLine.of("r0", "あ", null, false), AiLine.of("r1", "い", null, false));

    private static AiLayerRunner.Args args() {
        AiProviderConfig provider = new AiProviderConfig(LayerKind.SOUND, null, "1",
                FakeAiProvider.DEFAULT_MODEL, "Latin", AiContract.SOUND_PROMPT_VERSION, false);
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = new AiRunConfig(LayerKind.SOUND, "doc", "config", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        args.rows = ROWS;
        args.provider = new FakeAiProvider();
        args.store = new FakeAiRecordStore();
        return args;
    }

    private static AiChunkPlan plan(List<AiLine> rows) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = rows;
        input.model = FakeAiProvider.DEFAULT_MODEL;
        input.layer = LayerKind.SOUND;
        input.target = "Latin";
        return AiChunkPlanner.plan(input);
    }

    private static AiChunkRecord terminal(List<String> ids, String... fallback) {
        AiChunkRecord chunk = new AiChunkRecord(ids, "diagnostic input");
        chunk.status = fallback.length == 0 ? AiChunkRecord.Status.COMPLETE
                : AiChunkRecord.Status.COMPLETED_FALLBACK;
        chunk.fallbackRowIds.addAll(Arrays.asList(fallback));
        return chunk;
    }

    private static AiPaidRecord fixture() {
        AiPaidRecord record = AiPaidRecord.begin(args().config, 1);
        record.putChunk("C0", terminal(Arrays.asList("r0", "r1"), "r1"));
        record.putItem("r0", "a");
        assertTrue(record.hasTerminalFallback(plan(ROWS)));
        return record;
    }

    private static AiPaidRecord stripped(AiPaidRecord record) {
        String payload = AiPaidRecordCodec.encode(record, false);
        assertFalse(payload.contains("requestJson"));
        AiPaidRecord reread = AiPaidRecordCodec.decode(payload);
        assertNotNull(reread);
        return reread;
    }

    private static AiPaidRecord tree() {
        AiPaidRecord record = fixture();
        record.putChunk("C0.0", terminal(Collections.singletonList("r0")));
        record.putChunk("C0.1", terminal(Collections.singletonList("r1"), "r1"));
        return record;
    }

    private static void refusesReadAndDispatch(AiPaidRecord record) {
        for (boolean allow : new boolean[]{false, true}) {
            AiLayerRunner.Args args = args();
            FakeAiRecordStore store = (FakeAiRecordStore) args.store;
            store.commit(args.config, stripped(record));
            String before = AiPaidRecordCodec.encode(store.peek(args.config));
            args.allowProviderRequest = allow;
            assertFalse("unproven output, provider enabled=" + allow,
                    AiLayerRunner.run(args).hasOutput());
            assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
            assertEquals(0, store.reservations);
            assertEquals(1, store.commits);
            assertEquals(before, AiPaidRecordCodec.encode(store.peek(args.config)));
        }
    }

    @Test public void contradictoryCompletedChildCannotProveParentFallback() {
        AiPaidRecord record = tree();
        record.putChunk("C0.1", terminal(Collections.singletonList("r1")));
        assertFalse(stripped(record).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(record);
    }

    @Test public void unownedTerminalDescendantCannotProveCurrentPlan() {
        AiPaidRecord record = fixture();
        record.putChunk("C9.0", terminal(Collections.singletonList("foreign-row")));
        assertFalse(stripped(record).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(record);
    }

    @Test public void missingFallbackEvidenceCannotPublishThroughEnabledProvider() {
        AiPaidRecord record = fixture();
        record.chunk("C0").fallbackRowIds.clear();
        assertFalse(stripped(record).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(record);
    }

    @Test public void childrenMustCoverEveryParentItemInOrder() {
        AiPaidRecord missing = tree();
        missing.putChunk("C0.0", terminal(Collections.singletonList("foreign-row")));
        assertFalse(stripped(missing).hasTerminalFallback(plan(ROWS)));
        AiPaidRecord reversed = tree();
        reversed.putChunk("C0.0", terminal(Collections.singletonList("r1"), "r1"));
        reversed.putChunk("C0.1", terminal(Collections.singletonList("r0")));
        assertFalse(stripped(reversed).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(missing);
        refusesReadAndDispatch(reversed);
    }

    @Test public void childIdsMustBeContiguousAndOwnedByAnExistingParent() {
        for (String childId : Arrays.asList("C0.2", "C0.01", "C0.1.0", "C0.bad")) {
            AiPaidRecord record = fixture();
            record.putChunk("C0.0", terminal(Collections.singletonList("r0")));
            record.putChunk(childId, terminal(Collections.singletonList("r1"), "r1"));
            assertFalse(childId, stripped(record).hasTerminalFallback(plan(ROWS)));
            refusesReadAndDispatch(record);
        }
    }

    @Test public void repeatedChildCoverageCannotProveParent() {
        AiPaidRecord record = tree();
        record.putChunk("C0.0", terminal(Arrays.asList("r0", "r1"), "r1"));
        assertFalse(stripped(record).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(record);
    }

    @Test public void multisegmentRowCannotBeSplitAcrossSiblings() {
        List<AiLine> rows = Arrays.asList(AiLine.of("r0", "あ / い", null, false),
                AiLine.of("r1", "う", null, false));
        AiPaidRecord record = AiPaidRecord.begin(args().config, 1);
        record.putItem("r0", "a / i");
        record.putChunk("C0", terminal(Arrays.asList("r0~0", "r0~1", "r1"), "r1"));
        record.putChunk("C0.0", terminal(Collections.singletonList("r0~0")));
        record.putChunk("C0.1", terminal(Arrays.asList("r0~1", "r1"), "r1"));
        assertFalse(stripped(record).hasTerminalFallback(plan(rows)));
    }

    @Test public void childFallbackMustAgreeWithCompleteParentAndOutput() {
        AiPaidRecord record = tree();
        record.putChunk("C0.0", terminal(Collections.singletonList("r0"), "r0"));
        assertFalse(stripped(record).hasTerminalFallback(plan(ROWS)));
        refusesReadAndDispatch(record);
    }

    @Test public void validNestedProofReplaysWithoutCallsWritesOrAccountingChanges() {
        AiPaidRecord record = tree();
        // Four rows give an actual two-level partition with strict progress at each level.
        List<AiLine> rows = Arrays.asList(ROWS.get(0), ROWS.get(1),
                AiLine.of("r2", "う", null, false), AiLine.of("r3", "え", null, false));
        record.putChunk("C0", terminal(Arrays.asList("r0", "r1", "r2", "r3"), "r1"));
        record.putChunk("C0.1", terminal(Arrays.asList("r1", "r2", "r3"), "r1"));
        record.putChunk("C0.1.0", terminal(Collections.singletonList("r1"), "r1"));
        record.putChunk("C0.1.1", terminal(Arrays.asList("r2", "r3")));
        record.putItem("r2", "u");
        record.putItem("r3", "e");
        record.inputTokens = 13;
        record.outputTokens = 7;
        assertTrue(stripped(record).hasTerminalFallback(plan(rows)));
        for (boolean allow : new boolean[]{false, true}) {
            AiLayerRunner.Args args = args();
            args.rows = rows;
            args.allowProviderRequest = allow;
            FakeAiRecordStore store = (FakeAiRecordStore) args.store;
            store.commit(args.config, stripped(record));
            String before = AiPaidRecordCodec.encode(store.peek(args.config));
            AiRunOutcome outcome = AiLayerRunner.run(args);
            assertEquals(AiRunOutcome.Kind.REUSED, outcome.kind);
            assertTrue(outcome.hasOutput());
            assertFalse(outcome.record.isComplete());
            assertEquals(13, outcome.record.inputTokens);
            assertEquals(7, outcome.record.outputTokens);
            assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
            assertEquals(0, store.reservations);
            assertEquals(1, store.commits);
            assertEquals(0, store.releases);
            assertEquals(before, AiPaidRecordCodec.encode(store.peek(args.config)));
        }
    }

    @Test public void finalPartialPublicationRequiresProofAfterChunkCommit() {
        AiLayerRunner.Args args = args();
        args.provider = new FakeAiProvider(
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Arrays.asList("a", "い")), AiUsage.of(7, 3), AiFinishReason.STOP, 1),
                (request, config, signal) -> AiProviderResult.ok(FakeAiProvider.itemsJson(request,
                        Collections.singletonList("い")), AiUsage.of(5, 2), AiFinishReason.STOP, 1));
        args.store = new AiRecordStore() {
            @Override public AiPaidRecord read(AiRunConfig config) { return null; }
            @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
                record.chunk("C0").fallbackRowIds.clear();
                return true;
            }
            @Override public Reservation reserve(AiRunConfig config, long bytes) {
                return Reservation.admitted();
            }
            @Override public void release(AiRunConfig config) { }
            @Override public void forget(AiRunConfig config) { }
        };
        AiRunOutcome outcome = AiLayerRunner.run(args);
        assertEquals(2, ((FakeAiProvider) args.provider).calls.size());
        assertFalse("publication must prove the final partial record", outcome.hasOutput());
    }

    @Test public void interruptedBeforeFinalizationPromotesProvenCompleteChunksWithoutCalls() {
        AiPaidRecord record = fixture();
        record.putChunk("C0", terminal(Arrays.asList("r0", "r1")));
        record.putItem("r1", "i");
        AiLayerRunner.Args args = args();
        FakeAiRecordStore store = (FakeAiRecordStore) args.store;
        store.commit(args.config, stripped(record));
        AiRunOutcome outcome = AiLayerRunner.run(args);
        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertTrue(outcome.record.isComplete());
        assertEquals(2, outcome.record.items().size());
        assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
        assertEquals(0, store.reservations);
    }

    @Test public void retainedAllBaselineProofDoesNotRepurchaseRows() {
        AiLayerRunner.Args args = args();
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        record.putChunk("C0", terminal(Arrays.asList("r0", "r1"), "r0", "r1"));
        ((FakeAiRecordStore) args.store).commit(args.config, stripped(record));
        AiRunOutcome first = AiLayerRunner.run(args);
        assertEquals(AiRunOutcome.Kind.COMPLETED, first.kind);
        assertFalse(first.record.isComplete());
        assertTrue(first.record.items().isEmpty());
        assertEquals(Arrays.asList("r0", "r1"), first.record.chunk("C0").fallbackRowIds);
        assertEquals(AiRunOutcome.Kind.COMPLETED, AiLayerRunner.run(args).kind);
        assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
    }

    @Test public void missingOutputInRetainedCompletedChunkStopsBeforeUnfinishedDispatch() {
        java.util.ArrayList<AiLine> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 129; i++) rows.add(AiLine.of("r" + i, "あ", null, false));
        AiChunkPlan plan = plan(rows);
        assertEquals(3, plan.chunks.size());
        AiPaidRecord record = AiPaidRecord.begin(args().config, 1);
        AiChunkRecord chunk = AiChunkRecord.forChunk(plan.chunks.get(0));
        chunk.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", chunk);
        for (int i = 1; i < 64; i++) record.putItem("r" + i, "a");
        AiLayerRunner.Args args = args();
        args.rows = rows;
        ((FakeAiRecordStore) args.store).commit(args.config, stripped(record));
        assertFalse(AiLayerRunner.run(args).hasOutput());
        assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
        assertEquals(0, ((FakeAiRecordStore) args.store).reservations);
    }
}
