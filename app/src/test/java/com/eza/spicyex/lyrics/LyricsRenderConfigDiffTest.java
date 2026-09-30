package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import org.junit.Test;

/**
 * F11: now-playing reuses this shared difference calculation instead of a hand-rolled field
 * comparison. These cases pin the fields the hand-rolled version missed: a custom card size
 * change, Apple motion/blur settings, and the bounce scope must all invalidate.
 */
public class LyricsRenderConfigDiffTest {
    private LyricsRenderConfig base() {
        return LyricsRenderConfig.read(null, null);
    }

    private static Object fieldValue(LyricsRenderConfig config, String field) throws Exception {
        Field target = LyricsRenderConfig.class.getDeclaredField(field);
        target.setAccessible(true);
        return target.get(config);
    }

    private static LyricsRenderConfig withField(String field, Object value) throws Exception {
        LyricsRenderConfig config = LyricsRenderConfig.read(null, null);
        Field target = LyricsRenderConfig.class.getDeclaredField(field);
        target.setAccessible(true);
        if (target.getType() == boolean.class) {
            target.setBoolean(config, (Boolean) value);
        } else if (target.getType() == float.class) {
            target.setFloat(config, ((Number) value).floatValue());
        } else {
            target.set(config, value);
        }
        return config;
    }

    @Test
    public void identicalConfigsHaveNoChanges() {
        assertFalse(base().diff(base()).hasChanges);
    }

    @Test
    public void customFontPathInvalidatesBothSurfacesWithoutChangingFontMode() throws Exception {
        LyricsRenderConfig previous = withField("lyricsFont", "custom");
        LyricsRenderConfig next = withField("lyricsFont", "custom");
        Field path = LyricsRenderConfig.class.getDeclaredField("lyricsFontCustomPath");
        path.setAccessible(true);
        path.set(previous, "/fonts/first.ttf");
        path.set(next, "/fonts/second.ttf");

        LyricsRenderConfig.Diff diff = previous.diff(next);
        assertTrue(diff.needsRowRemount);
        assertTrue(diff.liveCardConfigChanged);
        assertTrue(diff.hasChanges);
    }

    @Test
    public void customCardSizeInvalidatesLiveCard() throws Exception {
        float current = (Float) fieldValue(base(), "liveCardTextSizeMultiplier");
        LyricsRenderConfig.Diff diff = base().diff(
                withField("liveCardTextSizeMultiplier", current + 1.0f));
        assertTrue(diff.liveCardConfigChanged);
        assertTrue(diff.hasChanges);
    }

    @Test
    public void appleMotionFlagsInvalidate() throws Exception {
        boolean current = (Boolean) fieldValue(base(), "appleLift");
        LyricsRenderConfig.Diff diff = base().diff(withField("appleLift", !current));
        assertTrue(diff.needsToggleOnly);
        assertTrue(diff.hasChanges);
    }

    @Test
    public void bounceScopeInvalidates() throws Exception {
        String current = (String) fieldValue(base(), "wordBounceScope");
        LyricsRenderConfig.Diff diff = base().diff(
                withField("wordBounceScope", current + "-other"));
        assertTrue(diff.needsToggleOnly);
        assertTrue(diff.hasChanges);
    }

    @Test
    public void blurControlsInvalidate() throws Exception {
        boolean current = (Boolean) fieldValue(base(), "lineBlurEnabled");
        LyricsRenderConfig.Diff diff = base().diff(withField("lineBlurEnabled", !current));
        assertTrue(diff.needsToggleOnly);
        assertTrue(diff.hasChanges);
    }

    @Test
    public void cardConfigKeyCoversTheCustomSizeMultiplier() throws Exception {
        LyricsRenderConfig base = LyricsRenderConfig.read(null, null);
        float current = base.liveCardTextSizeMultiplier;
        LyricsRenderConfig changed = withField("liveCardTextSizeMultiplier", current + 1.0f);
        // F11: the card remounts only when its key moves. A multiplier change that leaves
        // the key still would never rebuild the mounted row.
        assertTrue(!LiveLyricCardView.configKey(base).equals(
                LiveLyricCardView.configKey(changed)));
    }
}
