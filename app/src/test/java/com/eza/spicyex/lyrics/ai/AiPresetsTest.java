package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiPresetsTest {
    @Test
    public void meaningAndSoundCatalogsStaySeparate() {
        assertTrue(AiPresets.names(LayerKind.MEANING).length > AiPresets.names(LayerKind.SOUND).length);
        assertTrue(AiPresets.instructions(LayerKind.MEANING, "Faithful").contains("source meaning"));
        assertTrue(AiPresets.instructions(LayerKind.SOUND, "Readable pronunciation").contains("target orthography"));
        assertFalse(AiPresets.instructions(LayerKind.SOUND, "Readable pronunciation").contains("source meaning"));
    }

    @Test
    public void editedPresetIsCustom() {
        String faithful = AiPresets.instructions(LayerKind.MEANING, "Faithful");
        assertEquals("Faithful", AiPresets.matchingName(LayerKind.MEANING, faithful));
        assertNull(AiPresets.matchingName(LayerKind.MEANING, faithful + " More literal."));
    }
}
