package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsRowViewFactory;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LayerState;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Covers incremental Sound JSON parsing, live row progress, Sound overlay, and surface helpers. */
public final class AiStreamingEndToEndTest {

    @After
    public void tearDown() {
        AiRequestLiveState.clearForTest();
    }

    @Test
    public void readPartialItemsExtractsOnlyClosedJsonObjectsAndIgnoresTrailingPartial() {
        String prefix = "```json\n{\"items\":[{\"id\":\"r0\",\"t\":\"an-nyeong\"},"
                + "{\"id\":\"r1\",\"t\":\"escaped \\\"quote\\\" and } brace\"},"
                + "{\"id\":\"r2\",\"t\":\"half-writ";
        List<Object> items = AiResponseReader.readPartialItems(prefix);

        assertEquals(2, items.size());
        assertEquals("r0", ((Map<?, ?>) items.get(0)).get("id"));
        assertEquals("an-nyeong", ((Map<?, ?>) items.get(0)).get("t"));
        assertEquals("r1", ((Map<?, ?>) items.get(1)).get("id"));
        assertEquals("escaped \"quote\" and } brace", ((Map<?, ?>) items.get(1)).get("t"));
    }

    @Test
    public void soundChunkStreamsPartialRowsAndReportsLiveProgress() {
        AiRequestLiveState.begin(LayerKind.SOUND, "digest-s", "run-1");
        AiLiveMonitor liveMonitor = new AiLiveMonitor(LayerKind.SOUND, "digest-s", "run-1");
        final List<Integer> partialCounts = new ArrayList<>();
        AiRunMonitor monitor = new AiRunMonitor() {
            @Override public void onAttempt(String chunkId, int attempt, String wirePayload) {
                liveMonitor.onAttempt(chunkId, attempt, wirePayload);
            }

            @Override public void onReasoning(String chunkId, int attempt, String reasoning) {
                liveMonitor.onReasoning(chunkId, attempt, reasoning);
            }

            @Override public void onStreamProgress(String chunkId, int receivedRows, int totalRows) {
                liveMonitor.onStreamProgress(chunkId, receivedRows, totalRows);
            }

            @Override public void onPartialRows(String chunkId, List<AiResponseItem> rows) {
                partialCounts.add(rows.size());
            }
        };

        AiProvider streamingSound = new AiProvider() {
            @Override public String id() { return "fake"; }
            @Override public AiModelListResult listModels(AiSignal signal) {
                return AiModelListResult.ok(Collections.emptyList());
            }
            @Override public AiProviderResult generateChunk(AiProviderRequest request,
                                                            AiProviderConfig config,
                                                            AiSignal signal) {
                return generateChunk(request, config, signal, null);
            }
            @Override public AiProviderResult generateChunk(AiProviderRequest request,
                                                            AiProviderConfig config,
                                                            AiSignal signal,
                                                            StreamListener listener) {
                String p1 = "{\"items\":[{\"id\":\"r0\",\"t\":\"sa-rang\"}";
                String p2 = p1 + ",{\"id\":\"r1\",\"t\":\"ha-neul\"}]}";
                if (listener != null) {
                    listener.onText(p1);
                    listener.onText(p2);
                }
                return AiProviderResult.ok(p2, "", AiUsage.of(10, 12), AiFinishReason.STOP, 64L);
            }
        };

        AiModelDescriptor model = new AiModelDescriptor("test-model", "1", 32_768, 4_096,
                Collections.singletonList("generateContent"));
        AiChunkPlanner.Input planInput = new AiChunkPlanner.Input();
        planInput.layer = LayerKind.SOUND;
        planInput.rows = Arrays.asList(
                new AiLine("r0", AiLineClass.ORDINARY, AiSendDisposition.SENT,
                        "사랑", null, false, null, null),
                new AiLine("r1", AiLineClass.ORDINARY, AiSendDisposition.SENT,
                        "하늘", null, false, null, null));
        planInput.target = AiContract.ORTHOGRAPHY_LATIN;
        planInput.model = model;
        planInput.instructions = "";
        AiPlannedChunk chunk = AiChunkPlanner.plan(planInput).chunks.get(0);
        AiProviderConfig providerConfig = new AiProviderConfig(LayerKind.SOUND, null, "v1",
                model, AiContract.ORTHOGRAPHY_LATIN, AiContract.SOUND_PROMPT_VERSION, false);

        AiChunkRuntime.Args args = new AiChunkRuntime.Args();
        args.config = providerConfig;
        args.provider = streamingSound;
        args.chunk = chunk;
        args.monitor = monitor;
        args.signal = new AiSignal();

        AiChunkExecution execution = AiChunkRuntime.executeChunk(args);
        assertTrue(execution.ok);
        assertEquals(Arrays.asList(1, 2), partialCounts);

        AiRequestLiveState.Snapshot snap = AiRequestLiveState.snapshot(LayerKind.SOUND, "digest-s");
        assertEquals(2, snap.current.streamedRows);
        assertEquals(2, snap.current.totalRows);
    }

    @Test
    public void layerStateOverlaysStreamedAiSoundRowsOverDeterministicBaseline() {
        SoundArtifact baseline = new SoundArtifact("base", "det-cfg",
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "local", "det-cfg", 1L),
                Collections.singletonList(new SoundEntry("r0", "local-0", "", null, null, null)),
                true);
        SoundArtifact streamedAi = new SoundArtifact("base", "ai-cfg",
                new LayerProvenance(LayerAuthority.AI, "openai", "ai-cfg", 2L),
                Collections.singletonList(new SoundEntry("r1", "ai-1", "", null, null, null)),
                true);

        LayerState state = LayerState.absent(LayerKind.SOUND)
                .withDelta(baseline, LayerStatus.PROCESSING)
                .withDelta(streamedAi, LayerStatus.PROCESSING);

        assertEquals(2, state.artifact.size());
        assertEquals("local-0", ((SoundEntry) state.artifact.entry("r0")).displayText);
        assertEquals("ai-1", ((SoundEntry) state.artifact.entry("r1")).displayText);
    }

    @Test
    public void sameCanonicalBaseMatchesIdenticalLyricsAndRejectsDifferentBase() {
        LyricsDocument a = doc("t1", "hello", "world");
        LyricsDocument b = doc("t1", "hello", "world");
        b.lines.get(0).translatedText = "xin chao";
        LyricsDocument c = doc("t1", "different", "lyrics");

        assertTrue(LyricsDocumentProcessor.sameCanonicalBase(a, b));
        assertFalse(LyricsDocumentProcessor.sameCanonicalBase(a, c));
        assertFalse(LyricsDocumentProcessor.sameCanonicalBase(a, null));
    }

    @Test
    public void pendingTranslationRowsDimBelowSettledBrightness() {
        assertTrue(LyricsRowViewFactory.translationBrightness(true, true)
                < LyricsRowViewFactory.translationBrightness(true, false));
        assertTrue(LyricsRowViewFactory.translationBrightness(false, true)
                < LyricsRowViewFactory.translationBrightness(false, false));
    }

    private static LyricsDocument doc(String id, String... lines) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = id;
        for (int i = 0; i < lines.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = lines[i];
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            doc.lines.add(line);
        }
        return doc;
    }
}
