package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Small source contracts for Android-coupled wiring that pure runner tests cannot instantiate. */
public final class AiLaneWiringContractTest {

    @Test
    public void soundNoWorkPathKeepsDisplayedBaselineForExactPaidReuse() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/lyrics/LyricsSoundLane.java");
        String compact = source.replaceAll("\\s+", " ");

        assertTrue(compact.contains("startAiGapFill(run, id, generation, snapshot, "
                + "displayedSound, displayedSound, settings,"));
    }

    @Test
    public void apiKeyRevealIsTransientAndNeverReplacesTheMaskedRow() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/AiSettingsRows.java");

        assertTrue(source.contains("revealKeySecurely()"));
        assertTrue(source.contains("AiCredentialStore.mask("));
        assertTrue(source.contains(".secure()"));
        assertFalse(source.contains("revealKey ?"));
    }


    /**
     * The AI path must replay a deferred run, not drop it.
     *
     * <p>Issue #5: with the automatic trigger on, every song load claims the coalescer key, so a
     * user tapping Translate during that window collided with it. The Google path had always
     * re-entered {@code start(...)} from its continuation; the AI path only counted a metric, so
     * the tap was discarded and reported as "did not start. Lyrics or AI configuration may not be
     * ready" — naming a cause that was not true.
     */
    @Test
    public void aDeferredAiRunReplaysInsteadOfBeingDropped() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/lyrics/LyricsMeaningLane.java")
                .replaceAll("\\s+", " ");

        int deferBlock = compact.indexOf("COALESCED_RUN_JOINED); // Re-enter rather than drop");
        assertTrue("the AI continuation must re-enter start(...)", deferBlock >= 0);
        assertTrue(compact.indexOf("start(id, generation, snapshot, backend, targetLang, "
                + "sourceLang, effectiveSourceLang, explicitAiRequest, currentGuard, callback);",
                deferBlock) > deferBlock);
    }

    /** A deferred explicit request is queued work, so the surface may honestly call it running. */
    @Test
    public void anExplicitRequestThatWasDeferredStillCountsAsStarted() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/lyrics/LyricsMeaningLane.java")
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains("return explicitAiRequest; }"));
    }

    /** Automatic Meaning must not bill for source lyrics already in the translation target. */
    @Test
    public void automaticMeaningRequestRequiresTranslationWork() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/lyrics/LyricsMeaningLane.java")
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains("boolean aiAutomaticRequest = aiAutomatic "
                + "&& snapshot.translationPending;"));
        assertTrue(compact.contains("explicitAiRequest || aiAutomaticRequest, currentGuard, callback"));
        assertFalse(compact.contains("explicitAiRequest || aiAutomatic, currentGuard, callback"));
    }

    /** Generate-then-toggle must reveal the eventual Meaning artifact instead of hiding it. */
    @Test
    public void requestedMeaningOutputKeepsTranslationVisible() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/hooks/NativeSpicyShellViewImpl.java")
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains("boolean wasVisible = showTranslation(); "
                + "boolean hasDisplayedMeaning = hasLayerOutput("));
        assertTrue(compact.contains("keepVisibleForRequestedOutput( requestedOutput, wasVisible, "
                + "hasDisplayedMeaning)"));
        assertTrue(compact.contains("else if (layer == "
                + "com.eza.spicyex.lyrics.session.LayerKind.MEANING && !showTranslation()) { "
                + "showTranslation = true;"));
    }

    /**
     * The coalescer key is claimed on the calling thread, before dispatch. A refused execution
     * would otherwise leave it held with no owner to release it, and every later request for that
     * song would be told the work was already in flight — permanently.
     */
    @Test
    public void aRefusedAiDispatchReleasesTheCoalescerKey() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/lyrics/LyricsMeaningLane.java")
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains("} catch (RuntimeException notDispatched) { "
                + "COALESCER.finish(runIdentity);"));
    }

    /**
     * Selecting a provider must never inherit another provider's model.
     *
     * <p>The unscoped {@code AI_MODEL} exists only to carry a pre-scoping install forward, and the
     * default provider is the only one such an install could have been pointed at. Reading it for
     * every provider made a newly added one report a model it had never been given — a Gemini name
     * offered as an OpenRouter selection, counted as ready, and sent to an endpoint that has never
     * heard of it.
     */
    @Test
    public void anUnchosenProviderNeverInheritsAnotherProvidersModel() throws Exception {
        String compact = read("src/main/java/com/eza/spicyex/lyrics/ai/AiSettings.java")
                .replaceAll("\\s+", " ");

        assertTrue(compact.contains("if (!scoped.isEmpty()) return scoped; "
                + "return PROVIDER_GEMINI.equals(providerChoice()) "
                + "? AiText.nz(store.get(Settings.AI_MODEL)) : \"\";"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
