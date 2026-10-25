package com.eza.spicyex.ambient;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Source contracts for the parts that need an API 33 GPU runtime: AGSL construction, the offscreen
 * snapshot, palette uniform updates and the frame gate. The JVM cannot compile AGSL or mount a
 * {@code RuntimeShader}, so these assertions pin the wiring the runtime boundary cannot exercise.
 */
public class AmbientShaderViewContractTest {
    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated " + signature);
    }

    @Test
    public void theSnapshotBuildsTheSelectedEffectAndItsPalette() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String snapshot = method(view, "public Bitmap snapshot(int width, int height)");
        assertTrue(snapshot.contains("new RuntimeShader(shaderSource(effect))"));
        assertTrue(snapshot.contains("applyMeshPalette(copy)"));
        assertTrue(snapshot.contains("meshTransition.advance(System.nanoTime())"));
        assertTrue(snapshot.contains("copy.setFloatUniform(\"u_phase\", meshTransition.phase())"));
        assertTrue(snapshot.contains("copy.setFloatUniform(\"u_motion\", meshTransition.speed())"));
        assertTrue(snapshot.contains("copy.setFloatUniform(\"brightness\", baseBrightness)"));
        assertTrue(snapshot.contains("copy.setFloatUniform(\"dark\", 0f)"));
    }

    @Test
    public void aMeshSnapshotDoesNotWaitForUnrelatedArtwork() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String snapshot = method(view, "public Bitmap snapshot(int width, int height)");
        assertTrue(snapshot.contains(
                "if (effect.requiresArtworkTexture() && (source == null || source.isRecycled())) return null;"));
    }

    @Test
    public void paletteChangesReachTheRunningMesh() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String palette = method(view, "public void setPaletteColors(int[] colors)");
        assertTrue(palette.contains("paletteColors = colors.clone();"));
        assertTrue(palette.contains("setMeshPaletteAndMotion(colors"));
        assertTrue(view.contains("meshTransition.advance(System.nanoTime())"));
    }

    @Test
    public void aRejectedVendorShaderKeepsThePreviousOneWithoutRetrying() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String select = method(view, "public boolean setEffect(AmbientShaderEffect next)");
        assertTrue(select.contains("catch (Throwable t)"));
        assertTrue(select.contains("rejectedEffect = next.argument();"));
        assertTrue(select.contains("XpLog.log(TAG + \" ambient effect rejected effect=\" + next.argument());"));
        assertFalse("vendor text must not leave the process", select.contains("getMessage()"));
        assertFalse("the failure must not retry", select.contains("while ("));
        // The installed shader/effect assignment happens only after the try succeeds.
        assertTrue(select.indexOf("effect = next;") > select.indexOf("catch (Throwable t)"));
    }

    @Test
    public void theFrameGateKeepsItsPauseConditions() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String animating = method(view, "private boolean animating()");
        assertTrue(animating.contains("enabled && moving && playing && !thermalThrottled"));
        assertTrue(animating.contains("renderable()"));
        assertTrue(animating.contains("isAttachedToWindow()"));
        assertTrue(animating.contains("getWindowVisibility() == VISIBLE"));
        assertTrue(animating.contains("isShown()"));
        assertTrue(animating.contains("Motion.animationsEnabled()"));
    }

    @Test
    public void theControllerForwardsTheSelectionAndSkipsArtworkForMesh() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String select = method(controller, "public void setEffect(AmbientShaderEffect next)");
        assertFalse("debug selection must not persist a setting", select.contains("Settings"));
        assertFalse(select.contains("put("));
        assertTrue(select.contains("animatedBackground.setEffect(next)"));
        assertTrue(select.contains("if (!next.requiresArtworkTexture()) {"));
        assertTrue(select.contains("resumeRenderer();"));
        assertTrue(controller.contains("if (!background.usesArtworkTexture()) {"));
        assertTrue(controller.contains("generation != trackGeneration"));
        assertTrue(controller.contains("updateForDocument(String trackUri, LyricsDocument document)"));
        assertTrue(controller.contains("effect.usesArtworkPalette(textureEnabled)"));
    }

    @Test
    public void theDebugChannelExposesOnlyTheTwoEffectsAndStatus() throws Exception {
        String channel = read("src/main/java/com/eza/spicyex/hooks/AgentCommandChannel.java");
        assertTrue(channel.contains("case \"ambient-effect\":"));
        assertTrue(channel.contains("expected kawarp, mesh, or status"));
        assertTrue(channel.contains("shell.agentAmbientEffect(value)"));
        String shell = read("src/main/java/com/eza/spicyex/hooks/NativeSpicyShellView.java");
        assertTrue(shell.contains("String agentAmbientEffect(String argument)"));
    }

    @Test
    public void paperEffectsBindCatalogDefaultsNoiseImageSizingAndSharedGrade() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String build = method(view, "private RuntimeShader buildShader(AmbientShaderEffect candidate)");
        assertTrue(build.contains("PaperShaderBinding.applyDefaultsAndPalette(next, spec, paletteColors)"));
        assertTrue(build.contains("applyPaperGlobals(next, spec, lowWidth, lowHeight, livePixelRatio())"));
        assertTrue(build.contains("applyPaperGrade(next, forceDark, baseBrightness)"));
        assertTrue(build.contains("bindPaperInputs(next, spec)"));
        String globals = method(view,
                "private void applyPaperGlobals(RuntimeShader target, PaperShaderCatalog.Spec spec,");
        assertTrue(globals.contains("PaperShaderBinding.applyGlobals(target, spec, width, height,"
                + " pixelRatio, elapsedSeconds)"));
        String inputs = method(view,
                "private void bindPaperInputs(RuntimeShader target, PaperShaderCatalog.Spec spec)");
        assertTrue(inputs.contains("spec.usesNoiseTexture"));
        assertTrue(inputs.contains("ensureNoise()"));
        assertTrue(inputs.contains("PaperShaderBinding.bindNoise(target, noiseTexture)"));
        assertTrue(inputs.contains("spec.usesImageTexture"));
        assertTrue(inputs.contains("PaperShaderBinding.bindArtwork(target, original)"));
        String grade = method(view,
                "private static void applyPaperGrade(RuntimeShader target, boolean dark, float brightness)");
        assertTrue(grade.contains("ambient_dark"));
        assertTrue(grade.contains("ambient_brightness"));
    }

    @Test
    public void pulsingBorderUsesTheFullAmbientOutputSurface() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        assertTrue(view.contains("selection == AmbientShaderEffect.PULSING_BORDER"));
        assertTrue(view.contains("fragCoord.x / u_resolution.x - 0.5"));
        assertTrue(view.contains("u_resolution.x / max(u_resolution.y, 1.0)"));
    }

    @Test
    public void paperSizingTracksTheOffscreenPixelRatioNotTheDensityAlone() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String ratio = method(view, "private float livePixelRatio()");
        assertTrue(ratio.contains("getResources().getDisplayMetrics().density * renderScale"));
        String sized = method(view, "protected void onSizeChanged(int w,int h,int oldw,int oldh)");
        assertTrue(sized.contains("PaperShaderBinding.applyGlobals(shader, paperSpec, lowW, lowH,"));
    }

    @Test
    public void staticPaperEffectsDoNotPostFrames() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String motion = method(view, "private boolean effectAnimates()");
        assertTrue(motion.contains("paperSpec.animated && paperSpec.speed != 0f"));
        String animating = method(view, "private boolean animating()");
        assertTrue(animating.contains("effectAnimates()"));
    }

    @Test
    public void paperImageEffectsWaitForTheOriginalCover() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String renderable = method(view, "private boolean renderable()");
        assertTrue(renderable.contains("paperSpec != null"));
        assertTrue(renderable.contains("!paperSpec.usesImageTexture || hasOriginal()"));
        String original = method(view, "public boolean usesOriginalArtwork()");
        assertTrue(original.contains("paperSpec.usesImageTexture"));
        String update = method(view, "public void updateOriginalImage(Bitmap art)");
        assertTrue(update.contains("PaperShaderBinding.bindArtwork(shader, art)"));
    }

    @Test
    public void paperSnapshotsUseTheSameCatalogInputs() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String snapshot = method(view, "public Bitmap snapshot(int width, int height)");
        assertTrue(snapshot.contains(
                "PaperShaderBinding.applyDefaultsAndPalette(copy, paperSpec, paletteColors)"));
        assertTrue(snapshot.contains("PaperShaderBinding.bindNoise(copy, noiseTexture)"));
        assertTrue(snapshot.contains("PaperShaderBinding.bindArtwork(copy, source)"));
        assertTrue(snapshot.contains("applyPaperGrade(copy, false, baseBrightness)"));
    }

    @Test
    public void releaseDropsBothArtworkRepresentationsAndPaperInputs() throws Exception {
        String view = read("src/main/java/com/eza/spicyex/ambient/AmbientArtworkBackgroundView.java");
        String release = method(view, "public void release()");
        assertTrue(release.contains("originalTexture = null;"));
        assertTrue(release.contains("noiseTexture = null;"));
        assertTrue(release.contains("shader.setInputShader(\"u_image\", blank)"));
    }

    @Test
    public void theControllerPreparesTheOriginalCoverWhenTheEffectNeedsIt() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String art = method(controller,
                "private void updateAnimatedBackgroundArt(String imageId, RunningState runningState)");
        assertTrue(art.contains("background.usesOriginalArtwork()"));
        assertTrue(art.contains("AmbientArtworkTexture.prepareOriginal(local, ART_DECODE_TARGET_PX)"));
        // One owned bitmap feeds the texture, from Spotify's cover or the shared download.
        assertTrue(art.contains("ArtworkNetworkCache.copyOrFetch(imageId, http)"));
        assertTrue(art.contains("background.updateOriginalImage(result)"));
        assertTrue(art.contains("background.usesOriginalArtwork() != original"));
        assertTrue(art.contains("background.updateImage(result, resultProfile)"));
    }
}
