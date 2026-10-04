package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.providers.LyricsParser;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceRenderRoutingTest {
    @Test public void orgLineAndSyllableRowsKeepSourceTextAndSweepTheFirstVisualLine() throws Exception {
        String id = "1QV6tiMFM6fSOKOGLMHYYg";
        SpotifyTrack track = new SpotifyTrack("Song", "Artist", "Album", "spotify:track:" + id,
                0, "", 0, null, 5000, false);
        String[] bodies = {
                "\"Type\":\"Line\",\"Content\":[{\"Type\":\"Vocal\",\"Text\":\"ab cd\",\"StartTime\":1,\"EndTime\":5}]",
                "\"Type\":\"Syllable\",\"Content\":[{\"Type\":\"Vocal\",\"Lead\":{\"StartTime\":1,\"EndTime\":5,"
                        + "\"Syllables\":[{\"Text\":\"ab\",\"StartTime\":1,\"EndTime\":3,\"IsPartOfWord\":false},"
                        + "{\"Text\":\"cd\",\"StartTime\":3,\"EndTime\":5,\"IsPartOfWord\":false}]}}]"
        };
        LyricsRenderConfig config = config("Left to right (sentence)");
        for (String body : bodies) {
            LyricsDocument document = new LyricsParser(null).parseSpicyOrgLyrics(null, track,
                    "{\"Status\":200,\"Type\":\"object\",\"Body\":{\"id\":\"" + id
                            + "\",\"source\":\"apple_music\"," + body + "}}");
            LyricTimeline.applySyncedRows(document);
            AppliedLine line = document.appliedLines.get(0);
            LyricsSurfaceRowPlanner.RowPlan row = LyricsSurfaceRowPlanner.plan(line, document,
                    LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(config, false, false, "off"));
            assertEquals("ab cd", row.line.text);
            assertEquals("ab cd", row.line.sourceLine.text);
            assertTrue(row.options.continuousSentenceFill);
            float gradient = SentenceGradientPlanner.gradientPosition(row.line, 2000);
            assertTrue(gradient > LyricAnimations.GRADIENT_UNSUNG);
            assertTrue(gradient < LyricAnimations.gradientPosition(.5f));
            assertEquals(LyricAnimations.GRADIENT_UNSUNG,
                    SentenceFill.lineGradient(gradient, 200, 100, 100), .001f);
        }
    }

    @Test public void appleLineSentenceSelectionDoesNotLightTheWholeBlock() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.type = "Line";
        LyricsRenderConfig config = config("Left to right (sentence)");
        assertFalse(LyricsFrameRenderer.lightsAppleLineAsWhole(config, document));
        AppliedLine line = new AppliedLine();
        line.text = "first visual line second visual line";
        line.startMs = 1000;
        line.endMs = 5000;
        LyricsSurfaceRowPlanner.RowPlan row = LyricsSurfaceRowPlanner.plan(line, document,
                LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(config, false, false, "off"));
        assertTrue(row.options.continuousSentenceFill);
        assertTrue(row.options.sequentialLineFill);
        float gradient = SentenceGradientPlanner.gradientPosition(row.line, 2000);
        assertEquals(LyricAnimations.gradientPosition(.5f),
                SentenceFill.lineGradient(gradient, 200, 0, 100), .001f);
        assertEquals(LyricAnimations.GRADIENT_UNSUNG,
                SentenceFill.lineGradient(gradient, 200, 100, 100), .001f);
    }

    @Test public void otherAppleLineModesKeepWholeLineLighting() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.type = "Line";
        for (String mode : new String[]{"Top to bottom", "Left to right (word)", "Left to right (block)"}) {
            LyricsRenderConfig config = config(mode);
            assertTrue(mode, LyricsFrameRenderer.lightsAppleLineAsWhole(config, document));
            LyricsSurfaceRowPlanner.RowPlan row = LyricsSurfaceRowPlanner.plan(new AppliedLine(), document,
                    LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(config, false, false, "off"));
            assertFalse(mode, row.options.continuousSentenceFill);
            assertFalse(mode, row.options.sequentialLineFill);
        }
    }

    @Test public void disabledWashAndSpotlightKeepTheirExistingAppleLighting() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.type = "Line";
        LyricsRenderConfig config = config("Left to right (sentence)");
        Field wash = LyricsRenderConfig.class.getDeclaredField("lineGradientEnabled");
        wash.setAccessible(true);
        wash.setBoolean(config, false);
        assertTrue(LyricsFrameRenderer.lightsAppleLineAsWhole(config, document));
        wash.setBoolean(config, true);
        Field spotlight = LyricsRenderConfig.class.getDeclaredField("spotlight");
        spotlight.setAccessible(true);
        spotlight.setBoolean(config, true);
        assertTrue(LyricsFrameRenderer.lightsAppleLineAsWhole(config, document));
    }

    @Test public void sentenceRowPlanningCoversEverySharedSurfaceAndRealWords() throws Exception {
        LyricsRenderConfig config = config("Left to right (sentence)");
        Field cardMode = LyricsRenderConfig.class.getDeclaredField("liveCardLineSyncFillMode");
        cardMode.setAccessible(true);
        cardMode.set(config, "Left to right (sentence)");
        LyricsDocument document = new LyricsDocument();
        document.type = "Syllable";
        for (LyricsSurfaceRowPlanner.SurfacePolicy policy : new LyricsSurfaceRowPlanner.SurfacePolicy[]{
                LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(config, false, false, "off"),
                LyricsSurfaceRowPlanner.SurfacePolicy.liveCard(config),
                LyricsSurfaceRowPlanner.SurfacePolicy.artwork(config)}) {
            AppliedLine line = new AppliedLine();
            line.text = "hello world";
            SyllableSegment word = new SyllableSegment();
            word.text = line.text; word.startMs = 1000; word.endMs = 5000;
            line.words.add(word);
            LyricsSurfaceRowPlanner.RowPlan row = LyricsSurfaceRowPlanner.plan(line, document, policy);
            assertFalse(row.line.syntheticWords);
            assertTrue(row.options.continuousSentenceFill);
            assertTrue(row.options.sequentialLineFill);
        }
    }

    private static LyricsRenderConfig config(String mode) throws Exception {
        LyricsRenderConfig config = LyricsRenderConfig.read(null, null);
        for (String name : new String[]{"appleStyle", "lineGradientEnabled"}) {
            Field field = LyricsRenderConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(config, true);
        }
        Field field = LyricsRenderConfig.class.getDeclaredField("lineSyncFillMode");
        field.setAccessible(true);
        field.set(config, mode);
        return config;
    }
}
