package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Source contracts for the Google preview slice of the Meaning lane. The lane is Android-coupled
 * (executors, handler, provider HTTP), so the race table is tested behaviorally in
 * {@link com.eza.spicyex.lyrics.MeaningPreviewRaceTest}; what a pure test cannot see — which
 * executor runs which child, what request input preview sends, and where the coalescer is
 * released — is pinned here.
 */
public final class LyricsMeaningPreviewContractTest {

    /** Preview must be its own branch, never folded into the refinement path. */
    @Test
    public void previewRoutesToItsOwnRunBeforeTheOtherFlows() throws Exception {
        String compact = meaningLane().replaceAll("\\s+", " ");

        int route = compact.indexOf(
                "if (meaningFlow == AiSettings.MeaningFlow.GOOGLE_PREVIEW) {");
        assertTrue("the flow decision must be explicit", route >= 0);
        assertTrue(compact.contains("return startPreviewRun(run, id, generation, snapshot, "
                + "workerSnapshot, work, backend, targetLang, sourceLang, effectiveSourceLang, "
                + "explicitAiRequest, explicitAiRequest || aiAutomaticRequest, currentGuard, "
                + "callback);"));
    }

    private static String previewBody(String compactLane) {
        int begin = compactLane.indexOf("private boolean startPreviewRun(");
        int end = compactLane.indexOf("One allowlisted diagnostic event", begin);
        assertTrue(begin >= 0);
        assertTrue(end > begin);
        return compactLane.substring(begin, end);
    }

    /** Google display work belongs on the lane executor; only the AI job uses the AI executor. */
    @Test
    public void googleRunsOnTheLaneExecutorAndNeverBehindTheAiJob() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains("laneExecutor.execute(new Runnable() {"));
        assertTrue(preview.contains("aiExecutor.execute(new Runnable() {"));
    }

    /**
     * Preview AI semantics are AI-only semantics: no baseline row, plain prompt identity. This is
     * what makes preview and AI-only share one paid-record identity.
     */
    @Test
    public void previewSendsRawLyricsWithThePlainMeaningIdentity() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains("workerSnapshot, null, false, targetLang, "
                + "allowProviderRequest, signal, monitor);"));
        assertFalse("preview must not attach a Google baseline to the paid answer",
                preview.contains("withGoogleBaseline"));
    }

    /** Google preview must not let a paid AI cache hit bypass its selected display flow. */
    @Test
    public void previewAlwaysResolvesGoogleBeforeAi() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains("GoogleFallbackResult googleResult = googleFallback(run, id, workerSnapshot,"));
        assertFalse(preview.contains("AiMeaningRun.Result warm"));
    }

    /** Google publication releases the cached/new AI task; AI cannot run before preview. */
    @Test
    public void googlePreviewFinishesBeforeTheSiblingAiCanRun() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains(
                "final CountDownLatch previewReadyForAi = new CountDownLatch(1);"));
        int google = preview.indexOf("GoogleFallbackResult googleResult = googleFallback(");
        int previewPost = preview.indexOf("callback.rerender(LayerKind.MEANING, preliminary,", google);
        int release = preview.indexOf("previewReadyForAi.countDown();", previewPost);
        int await = preview.indexOf("previewReadyForAi.await();", release);
        int paidRun = preview.indexOf("result = AiMeaningRun.run(context", await);
        assertTrue(google >= 0);
        assertTrue(previewPost > google);
        assertTrue(release > previewPost);
        assertTrue(await > release);
        assertTrue(paidRun > await);
    }

    /** Exactly-once accounting: the AI side owns the coalescer key and every release path. */
    @Test
    public void rejectedDispatchesSettleWithoutLeavingTheLayerBusy() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains("COALESCER.finish(runIdentity);"));
        assertTrue(preview.contains("race.abandonAi();"));
        assertTrue("AI abandonment must suppress Google before releasing deferred runs",
                preview.contains("catch (RuntimeException notDispatched) { "
                        + "race.abandonAi(); COALESCER.finish(runIdentity);"));
        assertTrue("a refused AI dispatch also aborts the Google child's calls",
                preview.contains("provider.cancel(http, run.tag);"));
    }

    /** Retirement before AI dispatch releases the parent run and wakes no paid request. */
    @Test
    public void retiredGoogleChildReleasesTheSequentialPreviewRun() throws Exception {
        String preview = previewBody(meaningLane().replaceAll("\\s+", " "));

        assertTrue(preview.contains("if (!run.accepts(currentGuard, id, generation, snapshot)) { "
                + "race.abandonAi(); previewReadyForAi.countDown();"));
        assertTrue(preview.contains("AiRequestLiveState.cancel(LayerKind.MEANING, "
                + "run.canonicalDigest(), run.tag); COALESCER.finish(runIdentity); return;"));
    }

    /** Legacy boolean migration maps to draft/AI-only only; an old install never gets preview. */
    @Test
    public void legacyMigrationNeverSelectsPreview() throws Exception {
        String compact = aiSettings().replaceAll("\\s+", " ");

        assertTrue(compact.contains(
                "? MeaningFlow.GOOGLE_DRAFT : MeaningFlow.AI_ONLY;"));
        String legacyBranch = compact.substring(
                compact.indexOf("if (store.contains(Settings.AI_TRANSLATION_REFINE_GOOGLE))"),
                compact.indexOf("return MeaningFlow.GOOGLE_DRAFT;",
                        compact.indexOf("AI_TRANSLATION_REFINE_GOOGLE")));
        assertFalse(legacyBranch.contains("GOOGLE_PREVIEW"));
    }

    /** The binary predicate survives for callers outside the lane and agrees with the flow. */
    @Test
    public void meaningUsesGoogleBaselineDelegatesToTheFlow() throws Exception {
        String compact = aiSettings().replaceAll("\\s+", " ");

        assertTrue(compact.contains(
                "public boolean meaningUsesGoogleBaseline() { "
                        + "return meaningFlow() == MeaningFlow.GOOGLE_DRAFT; }"));
    }

    private static String meaningLane() throws Exception {
        return read("src/main/java/com/eza/spicyex/lyrics/LyricsMeaningLane.java");
    }

    private static String aiSettings() throws Exception {
        return read("src/main/java/com/eza/spicyex/lyrics/ai/AiSettings.java");
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
