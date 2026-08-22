package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.eza.spicyex.lyrics.session.Digests;
import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Golden digests taken from the desktop fork, so the two implementations can be shown to agree
 * rather than assumed to.
 *
 * <p>Each expected value below was produced by running the desktop fork's own
 * {@code identity.ts} / {@code protocol.ts} over the identical input. A digest that merely looks
 * canonical is worthless — sorted keys with a different escaping, or NFC applied in a different
 * place, gives a stable-looking hash that simply disagrees with the fork it was ported from.
 * Regenerating these values is not the fix if one fails; a difference here is a real divergence.
 */
public class AiDesktopParityTest {

    @Test
    public void canonicalSerializationMatchesTheDesktopImplementation() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", "e\u0301");
        value.put("a", Arrays.asList(2, null));
        assertEquals("a631ead91a5af75c9bcf77bebcf24b8a010b7ce237075439353b9b78f7dd36ab",
                Digests.canonicalSha256(value));
    }

    @Test
    public void theMeaningDocumentDigestMatchesTheDesktopImplementation() {
        List<AiLine> rows = new ArrayList<>();
        rows.add(new AiLine("r0#aaaa", AiLineClass.ORDINARY, AiSendDisposition.SENT,
                "\u6b4c / \u3046\u305f", null, false, null, null));
        rows.add(new AiLine("r1#bbbb", AiLineClass.STRUCTURAL, AiSendDisposition.STRUCTURAL,
                "\u266a", AiVoiceHint.BACKGROUND, true, null, null));
        AiLyricContext context = new AiLyricContext(" S\u00f3ng ",
                Collections.singletonList("Ngh\u1ec7  s\u0129"), null);

        assertEquals("dfa4f1c32a3c7fe6748be46193ead159379dfb90c50ad38e0b96647429a76857",
                AiIdentity.buildDocDigest(rows, context, false));
    }

    @Test
    public void theSoundDocumentDigestMatchesTheDesktopImplementation() {
        List<AiLine> rows = Collections.singletonList(
                new AiLine("r0#aaaa", AiLineClass.ORDINARY, AiSendDisposition.SENT,
                        "\u0e09\u0e31\u0e19", null, true, "chan", "google"));
        assertEquals("e9feb8055c872f998ea445654015d4fec6aca503483334625ce754f5d15cf5d1",
                AiIdentity.buildDocDigest(rows, null, true));
    }

    /**
     * The one deliberate divergence, recorded rather than hidden.
     *
     * <p>The contract's {@code configId} field list names {@code targetOrthography}; the desktop
     * code carries {@code pronunciationSystem} instead. Mobile keys on both, because dropping
     * either one loses a distinction that a cache key has to make — two pronunciation systems can
     * share an orthography, and the contract names the orthography. The extra field only ever makes
     * identity finer, so it can send a request that could have been answered from cache, but never
     * serve an answer to the wrong question. Paid records never travel between forks (L5), so no
     * cross-fork compatibility is owed for this value.
     */
    @Test
    public void theConfigIdDivergesFromDesktopByExactlyTheContractsOwnField() {
        AiIdentity.Config config = new AiIdentity.Config();
        config.layer = LayerKind.SOUND;
        config.provider = "openai";
        config.providerVersion = "1";
        config.endpoint = null;
        config.modelName = "m";
        config.targetLang = "Latin";
        config.targetOrthography = "Latin";
        config.sourceLanguage = "ja";
        config.pronunciationSystem = "japanese";
        config.soundMode = "whole_line_v1";
        config.instructions = "Keep\nnames";
        config.promptVersion = 5;

        String desktop = "2f087e7198c77f46126a13f6311c6563e4f6858e5ea661fd67b67fe7491e8704";
        assertNotEquals(desktop, AiIdentity.buildConfigId(config));

        // …and it is the added field that does it: with the same inputs minus that one key, the
        // payload is the desktop payload.
        Map<String, Object> desktopPayload = new LinkedHashMap<>();
        desktopPayload.put("layer", "sound");
        desktopPayload.put("provider", "openai");
        desktopPayload.put("providerVersion", "1");
        desktopPayload.put("endpoint", null);
        desktopPayload.put("modelName", "m");
        desktopPayload.put("targetLang", "Latin");
        desktopPayload.put("sourceLanguage", "ja");
        desktopPayload.put("pronunciationSystem", "japanese");
        desktopPayload.put("soundMode", "whole_line_v1");
        desktopPayload.put("soundBaselineMode", null);
        desktopPayload.put("instructions", "Keep\nnames");
        desktopPayload.put("promptVersion", 5);
        desktopPayload.put("temperature", AiContract.TEMPERATURE);
        desktopPayload.put("contextMode", AiContract.CONTEXT_MODE);
        desktopPayload.put("iterationPromptVersion", null);
        desktopPayload.put("parentRecordKey", null);
        desktopPayload.put("parentOutputDigest", null);
        desktopPayload.put("revisionInstructions", null);
        assertEquals(desktop, Digests.canonicalSha256(desktopPayload));
    }
}
