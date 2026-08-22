package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Acceptance is all-or-nothing, and it checks structure only.
 *
 * <p>The two halves matter equally. A response that reorders the rows is correct — the mapping
 * travels in the ids — and testing it as malformed would encode the opposite of the design. A
 * response that quietly drops or invents a row is not, however well the remaining rows read.
 */
public class AiResponseValidationTest {

    private static AiRequestItem item(String id, AiLineClass lineClass, String source) {
        return new AiRequestItem(id, lineClass, null, source, null);
    }

    private static List<AiResponseItem> accept(String raw, List<AiRequestItem> requested) {
        return AiResponseValidator.validate(AiResponseReader.readItems(raw), requested);
    }

    private static void reject(String raw, List<AiRequestItem> requested, String expectedToken) {
        try {
            accept(raw, requested);
            fail("expected rejection: " + expectedToken);
        } catch (AiProtocolException rejected) {
            assertTrue("expected " + expectedToken + ", got " + rejected.getMessage(),
                    rejected.getMessage().startsWith(expectedToken));
        }
    }

    // --- accepted -----------------------------------------------------------

    @Test
    public void areorderedResponseInsideOneCodeFenceIsCorrect() {
        List<AiRequestItem> requested = Arrays.asList(
                item("S0", AiLineClass.ORDINARY, "hola"),
                item("S1", AiLineClass.ADLIB, "Yeah"));
        List<AiResponseItem> accepted = accept(
                "```json\n{\"items\":[{\"id\":\"S1\",\"t\":\"Yeah\"},"
                        + "{\"id\":\"S0\",\"t\":\"hello\"}]}\n```", requested);
        assertEquals(2, accepted.size());
        assertEquals("S1", accepted.get(0).id);
        assertEquals("S0", accepted.get(1).id);
        assertEquals("hello", accepted.get(1).text);
    }

    @Test
    public void aBareFenceWithoutALanguageTagIsAlsoTolerated() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        assertEquals("hello",
                accept("```\n{\"items\":[{\"id\":\"S0\",\"t\":\"hello\"}]}\n```", requested)
                        .get(0).text);
    }

    @Test
    public void anUnchangedRowIsAcceptedWithoutRepair() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "Te quiero mucho"));
        assertEquals("Te quiero mucho",
                accept("{\"items\":[{\"id\":\"S0\",\"t\":\"Te quiero mucho\"}]}", requested)
                        .get(0).text);
    }

    @Test
    public void anAdlibMayComeBackEmpty() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ADLIB, "Yeah"));
        assertEquals("", accept("{\"items\":[{\"id\":\"S0\",\"t\":\"\"}]}", requested).get(0).text);
    }

    @Test
    public void segmentCountIsCheckedLiterallyIncludingTrailingSegments() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola / mundo"));
        assertEquals("hello / world",
                accept("{\"items\":[{\"id\":\"S0\",\"t\":\"hello / world\"}]}", requested)
                        .get(0).text);
    }

    // --- rejected -----------------------------------------------------------

    @Test
    public void theIdSetMustMatchExactly() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("{\"items\":[]}", requested, "id_set_mismatch:missing:S0");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"a\"},{\"id\":\"S0\",\"t\":\"b\"}]}", requested,
                "id_set_mismatch:duplicate:S0");
        reject("{\"items\":[{\"id\":\"extra\",\"t\":\"a\"}]}", requested,
                "id_set_mismatch:unexpected:extra");
    }

    @Test
    public void everyItemMustHaveStringIdAndText() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("{\"items\":[{\"id\":\"S0\",\"t\":3}]}", requested, "invalid_item");
        reject("{\"items\":[{\"id\":0,\"t\":\"a\"}]}", requested, "invalid_item");
        reject("{\"items\":[\"S0\"]}", requested, "invalid_item");
        reject("{\"items\":{}}", requested, "items_not_array");
        reject("{}", requested, "items_not_array");
    }

    @Test
    public void aTruncatedOrTrailingBodyIsNotJson() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("{\"items\":", requested, "invalid_json");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"a\"}]} trailing", requested, "invalid_json");
        reject("{items:[]}", requested, "invalid_json");
        reject("", requested, "invalid_json");
    }

    @Test
    public void aRowMayNotCarryLineBreaksOrControlCharacters() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\\nworld\"}]}", requested,
                "forbidden_text:S0");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\\u2028world\"}]}", requested,
                "forbidden_text:S0");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\\u0007world\"}]}", requested,
                "forbidden_text:S0");
    }

    @Test
    public void anOrdinaryRowMayNotComeBackBlank() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"   \"}]}", requested, "empty_ordinary:S0");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"\\u00a0\"}]}", requested, "empty_ordinary:S0");
    }

    @Test
    public void theSegmentCountMustSurviveTranslation() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola / mundo"));
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"hello\"}]}", requested, "delimiter_mismatch:S0");
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"a / b / c\"}]}", requested,
                "delimiter_mismatch:S0");
    }

    @Test
    public void arowPastTheItemByteCapIsRejected() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4097; i++) huge.append('x');
        reject("{\"items\":[{\"id\":\"S0\",\"t\":\"" + huge + "\"}]}", requested,
                "translated_item_oversized:S0");
    }

    // --- Sound target orthography -------------------------------------------

    private static void assertSound(boolean expected, String text, String target, String source) {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, source));
        List<Object> items = AiResponseReader.readItems(
                "{\"items\":[{\"id\":\"S0\",\"t\":" + quote(text) + "}]}");
        try {
            AiResponseValidator.validate(items, requested, LayerKind.SOUND, target);
            if (!expected) fail("expected a target orthography mismatch for: " + text);
        } catch (AiProtocolException rejected) {
            if (expected) fail("expected acceptance, got " + rejected.getMessage());
            assertTrue(rejected.getMessage().startsWith("target_orthography_mismatch"));
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder();
        com.eza.spicyex.lyrics.session.Digests.appendJsonString(out, value);
        return out.toString();
    }

    @Test
    public void latinIsAlwaysReadableSoAlreadyLatinTextPasses() {
        assertSound(true, "Hello", "Latin", "Hello");
        assertSound(true, "annyeong", "Latin", "\uc548\ub155");
    }

    @Test
    public void aResponseInTheWrongScriptIsRejected() {
        assertSound(false, "\uc548\ub155", "Latin", "Hello");
        assertSound(false, "\u0e01\u0e47\u0e44\u0e21\u0e48\u0e23\u0e39\u0e49", "Latin",
                "\u0e01\u0e47\u0e44\u0e21\u0e48\u0e23\u0e39\u0e49");
    }

    @Test
    public void arespellingTargetRequiresTheTargetScriptWhenTheSourceNeededOne() {
        // Latin is tolerated inside the answer, but a source that needed respelling must actually
        // be respelled — otherwise echoing the request back would pass.
        assertSound(true, "\uc548\ub155 Hello", "Hangul", "\uc548\ub155 Hello");
        assertSound(false, "annyeong Hello", "Hangul", "\uc548\ub155 Hello");
        assertSound(false, "annyeong Hello", "Kana", "\uc548\ub155 Hello");
        assertSound(false, "annyeong Hello", "Cyrillic", "\uc548\ub155 Hello");
    }

    @Test
    public void anUnknownTargetOrthographyAcceptsNothing() {
        assertSound(false, "Hello", "Devanagari", "Hello");
    }

    // --- fence handling -----------------------------------------------------

    @Test
    public void onlyOneFenceIsStripped() {
        assertEquals("{\"items\":[]}",
                AiResponseReader.stripSingleFence("```json\n{\"items\":[]}\n```"));
        assertEquals("{\"items\":[]}",
                AiResponseReader.stripSingleFence("```JSON\u00a0{\"items\":[]}\u00a0```"));
        assertEquals("plain", AiResponseReader.stripSingleFence("  plain  "));
        // A doubly fenced body is not a cosmetic difference; the inner fence stays and fails to
        // parse rather than being peeled until something works.
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "hola"));
        reject("```\n```json\n{\"items\":[{\"id\":\"S0\",\"t\":\"a\"}]}\n```\n```", requested,
                "invalid_json");
    }

    @Test
    public void meaningValidationIgnoresTheTargetScriptEntirely() {
        List<AiRequestItem> requested =
                Collections.singletonList(item("S0", AiLineClass.ORDINARY, "Hello"));
        List<Object> items =
                AiResponseReader.readItems("{\"items\":[{\"id\":\"S0\",\"t\":\"\uc548\ub155\"}]}");
        List<AiResponseItem> accepted =
                AiResponseValidator.validate(items, requested, LayerKind.MEANING, "ko");
        assertEquals(1, accepted.size());
    }

    @Test
    public void theFailingRowIsNamedSoARepairCanSayWhatWasWrong() {
        List<AiRequestItem> requested = new ArrayList<>(Arrays.asList(
                item("r0#aaaa", AiLineClass.ORDINARY, "hola / mundo"),
                item("r1#bbbb", AiLineClass.ORDINARY, "adios")));
        reject("{\"items\":[{\"id\":\"r0#aaaa\",\"t\":\"hello\"},"
                        + "{\"id\":\"r1#bbbb\",\"t\":\"bye\"}]}", requested,
                "delimiter_mismatch:r0#aaaa");
    }
}
