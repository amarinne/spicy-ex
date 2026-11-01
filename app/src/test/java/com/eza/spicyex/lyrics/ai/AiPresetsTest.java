package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AiPresetsTest {
    @Test
    public void meaningAndSoundCatalogsStaySeparate() {
        assertTrue(AiPresets.names(LayerKind.MEANING).length > AiPresets.names(LayerKind.SOUND).length);
        assertTrue(AiPresets.instructions(LayerKind.MEANING, "Faithful").contains("source meaning"));
        assertTrue(AiPresets.instructions(LayerKind.SOUND, "Readable").contains("target orthography"));
        assertFalse(AiPresets.instructions(LayerKind.SOUND, "Readable").contains("source meaning"));
    }

    @Test
    public void editedPresetIsCustom() {
        String faithful = AiPresets.instructions(LayerKind.MEANING, "Faithful");
        assertEquals("Faithful", AiPresets.matchingName(LayerKind.MEANING, faithful));
        assertNull(AiPresets.matchingName(LayerKind.MEANING, faithful + " More literal."));
    }

    /**
     * The four original Meaning presets, byte for byte as they were before the Creative preset
     * landed (main 68d4d833). A changed string would stop {@code matchingName} from recognizing
     * every already-saved selection and make each of its stored records a miss. Only the names
     * were shortened.
     */
    private static final String NATURAL_AND_CONTEXTUAL =
            "Make the complete output natural and contextual. Improve materially inaccurate, awkward, or inconsistent wording while retaining passages that already work when an alternative would not be better. Keep the song's tone, register, cultural nuance, and mixed-language phrasing.";
    private static final String FAITHFUL =
            "Stay close to the source meaning without becoming mechanical. Preserve ambiguity, repetition, imagery, and emotional intensity when the source supports them.";
    private static final String MIXED_LANGUAGE =
            "Review every phrase independently. Translate phrases that need translation, preserve intentional code-switching, and keep mixed-language lines natural as a whole.";
    private static final String CULTURAL_NUANCE =
            "Preserve cultural references, honorifics, slang, dialect, and implied meaning. Prefer an equivalent natural effect over a mechanical word-for-word rendering.";

    @Test
    public void meaningCatalogHasThreeStyleNames() {
        String[] meaning = AiPresets.names(LayerKind.MEANING);
        assertTrue(java.util.Arrays.equals(
                new String[]{"Natural", "Faithful", "Creative"},
                meaning));
        assertTrue("the Sound catalog is unchanged", java.util.Arrays.equals(
                new String[]{"Readable", "Source-close"},
                AiPresets.names(LayerKind.SOUND)));
    }

    @Test
    public void everyPresetRoundTripsThroughMatchingNameAndStaysUnique() {
        Set<String> seen = new HashSet<>();
        for (String name : AiPresets.names(LayerKind.MEANING)) {
            String instructions = AiPresets.instructions(LayerKind.MEANING, name);
            assertEquals(name, AiPresets.matchingName(LayerKind.MEANING, instructions));
            assertTrue("each instruction string is its own value", seen.add(instructions));
        }
        for (String name : AiPresets.names(LayerKind.SOUND)) {
            assertEquals(name, AiPresets.matchingName(LayerKind.SOUND,
                    AiPresets.instructions(LayerKind.SOUND, name)));
        }
    }

    @Test
    public void meaningInstructionStringsStayUnderTwoKilobytes() {
        for (String name : AiPresets.names(LayerKind.MEANING)) {
            String instructions = AiPresets.instructions(LayerKind.MEANING, name);
            int bytes = AiText.utf8Bytes(instructions);
            assertTrue(name + " is " + bytes + " UTF-8 bytes", bytes < 2048);
        }
    }

    @Test
    public void theKeptOriginalMeaningStringsAreStillTheirLiteralTextAndTheRetiredOnesAreCustom() {
        assertEquals(NATURAL_AND_CONTEXTUAL,
                AiPresets.instructions(LayerKind.MEANING, "Natural"));
        assertEquals(FAITHFUL, AiPresets.instructions(LayerKind.MEANING, "Faithful"));
        // Mixed language and Cultural nuance are concerns, not styles, so they are no presets.
        // A saved selection reads as Custom and keeps its stored records, because records are
        // keyed by the instruction text.
        assertNull(AiPresets.matchingName(LayerKind.MEANING, MIXED_LANGUAGE));
        assertNull(AiPresets.matchingName(LayerKind.MEANING, CULTURAL_NUANCE));
        // The literals above must themselves differ from the new string.
        assertFalse(java.util.Arrays.asList(NATURAL_AND_CONTEXTUAL, FAITHFUL).contains(AiPresets.instructions(LayerKind.MEANING, "Creative")));
    }
}
