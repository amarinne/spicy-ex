package com.eza.spicyex.lyrics;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Source contracts for the artwork palette handoff. The controller needs a live Activity, Handler
 * and API 33 GPU surface, so JVM tests pin the acquisition, identity and lifecycle requirements
 * that the Android runtime boundary cannot exercise here.
 */
public class LyricsAmbientPaletteContractTest {
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
    public void paletteFetchesArtworkWhenSpotifyHasNoBitmap() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        // A fixed number of cache polls then silence left palette-only effects on the seed colour.
        assertFalse(controller.contains("attempt < 8"));
        String load = method(controller, "private void loadArtworkPalette(");
        assertTrue(load.contains("SpotifyArtworkCache.snapshot(imageId, trackUri)"));
        assertTrue("the palette falls back to the shared CDN download",
                load.contains("ArtworkNetworkCache.copyOrFetch(imageId, http)"));
        assertTrue("a late download retries the waiting palette",
                controller.contains("ArtworkNetworkCache.addListener(networkArtworkListener)"));
        String cache = read("src/main/java/com/eza/spicyex/lyrics/cache/ArtworkNetworkCache.java");
        assertTrue(cache.contains("https://i.scdn.co/image/"));
        assertTrue("downloads stay bounded", cache.contains("readBounded("));
        assertTrue(cache.contains("decodeBounded("));
        assertTrue(load.contains("MeshGradientDrawable.extractPalette("));
    }

    @Test
    public void paletteResultsBelongToTheCurrentTrackAndLifecycle() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String load = method(controller, "private void loadArtworkPalette(");
        assertTrue("a load belongs to the transition it started in",
                load.contains("generation != trackGeneration"));
        String accept = method(controller, "private void acceptArtworkPalette(");
        assertTrue("stop and a superseding request invalidate the result",
                accept.contains("task != paletteTaskGeneration"));
        assertTrue(accept.contains("!active"));
        assertTrue(accept.contains("trackUri.equals(currentTrackUri)"));
        assertTrue(accept.contains("imageId.equals(desiredArtImageId)"));
        assertTrue(accept.contains("effect.usesArtworkPalette(textureEnabled)"));
        assertFalse("a same-song settings refresh must not drop its in-flight cover palette",
                accept.contains("generation != trackGeneration"));
    }

    @Test
    public void aSameSongEffectSwitchReappliesTheAcceptedPalette() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String select = method(controller, "public void setEffect(AmbientShaderEffect next)");
        assertTrue(select.contains("requestCurrentArtworkPalette();"));
        String request = method(controller, "private void requestCurrentArtworkPalette()");
        assertTrue(request.contains("applyArtworkPalette();"));
        assertTrue(request.contains("loadArtworkPalette("));
    }

    @Test
    public void theTextureDecodeAlsoFeedsThePalette() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String art = method(controller,
                "private void updateAnimatedBackgroundArt(String imageId, RunningState runningState)");
        assertTrue(art.contains("extractPalette("));
        assertTrue(art.contains("acceptTextureArtworkPalette("));
    }

    @Test
    public void stoppingCancelsInFlightPaletteWork() throws Exception {
        String controller = read("src/main/java/com/eza/spicyex/lyrics/LyricsAmbientController.java");
        String stop = method(controller, "public void stop()");
        assertTrue(stop.contains("cancelArtworkPalette();"));
    }
}
