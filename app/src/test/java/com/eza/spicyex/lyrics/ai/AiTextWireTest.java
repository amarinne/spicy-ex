package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The model writes numbered text; the harness owns which row each line belongs to. */
public class AiTextWireTest {

    private static final List<AiRequestItem> ITEMS = Arrays.asList(
            new AiRequestItem("r0#a", AiLineClass.ORDINARY, null, "故事的小黃花", null),
            new AiRequestItem("r1#b", AiLineClass.ORDINARY, null, "從出生那年就飄著", null),
            new AiRequestItem("r2#c", AiLineClass.ADLIB, null, "oh oh", null));

    private static Map<String, String> read(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object item : AiTextWire.readItems(raw, ITEMS, LayerKind.MEANING)) {
            Map<?, ?> map = (Map<?, ?>) item;
            out.put((String) map.get("id"), (String) map.get("t"));
        }
        return out;
    }

    @Test
    public void numbersMapToRowsInRequestOrder() {
        Map<String, String> rows = read("1. The little yellow flower\n2. Drifting since I was born\n3. oh oh");
        assertEquals("The little yellow flower", rows.get("r0#a"));
        assertEquals("Drifting since I was born", rows.get("r1#b"));
        assertEquals("oh oh", rows.get("r2#c"));
    }

    @Test
    public void preambleFencesBoldNumbersAndWrappedLinesAreTolerated() {
        Map<String, String> rows = read("Here is the translation:\n```\n**1.** The little\n"
                + "   yellow flower\n\n2) Drifting\n```");
        assertEquals("The little yellow flower", rows.get("r0#a"));
        assertEquals("Drifting", rows.get("r1#b"));
        assertFalse(rows.containsKey("r2#c"));
    }

    @Test
    public void unknownNumbersAreIgnoredAndRepeatedNumbersDropTheirRow() {
        Map<String, String> rows = read("1. one\n1. again\n2. two\n9. stray");
        assertFalse("an ambiguous row is re-asked, not guessed", rows.containsKey("r0#a"));
        assertEquals("two", rows.get("r1#b"));
        assertEquals(1, rows.size());
    }

    @Test
    public void anEchoedDraftLineIsNotAppendedToTheTranslation() {
        assertEquals("one", read("1. one\n   draft: uno").get("r0#a"));
    }

    @Test
    public void aJsonReplyIsStillRead() {
        assertEquals("one", read("{\"items\":[{\"id\":\"r0#a\",\"t\":\"one\"}]}").get("r0#a"));
    }

    @Test
    public void aReplyWithoutNumberedLinesIsAProtocolFailure() {
        try {
            read("I cannot help with that.");
            fail();
        } catch (AiProtocolException expected) {
            assertEquals("no_numbered_lines", expected.getMessage());
        }
    }

    @Test
    public void theRequestIsPlainNumberedTextWithSongDetailsAndDrafts() {
        AiProviderRequest request = new AiProviderRequest(
                new AiLyricContext("晴天", Arrays.asList("Jay Chou"), "葉惠美"), "en", "Keep it close.",
                Arrays.asList(ITEMS.get(0),
                        new AiRequestItem("r1#b", AiLineClass.ORDINARY, null, "line\ntwo", "draft")));
        String text = AiTextWire.userTurn(request, LayerKind.MEANING);
        assertEquals("Song: 晴天 — Jay Chou (葉惠美)\nTarget language: en\n\nInstructions:\n"
                + "Keep it close.\n\nLines:\n1. 故事的小黃花\n2. line two\n   draft: draft\n", text);
        assertTrue(AiTextWire.userTurn(request, LayerKind.SOUND).startsWith("{\"context\""));
    }
}
