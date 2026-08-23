package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

/**
 * The prompts lead with the hard rules, short and numbered, and put semantics after. That layout
 * is the whole point of the restructure: a weak instruction-follower stops reading a long
 * paragraph before its last sentences, which is exactly where the acceptance-critical constraints
 * used to sit. These pin the shape, so a later edit cannot quietly push the rules to the back.
 */
public class AiContractPromptTest {

    @Test
    public void meaningPromptLeadsWithNumberedHardRules() {
        assertTrue(AiContract.SYSTEM_PROMPT.startsWith("Hard rules:\n1."));
        assertRulesLead(AiContract.SYSTEM_PROMPT);
        assertMeaningConstraintsPresent(AiContract.SYSTEM_PROMPT);
        assertSemanticsFollowTheRules(AiContract.SYSTEM_PROMPT, "Translate the full lyric");
    }

    @Test
    public void soundPromptLeadsWithNumberedHardRules() {
        assertTrue(AiContract.SOUND_SYSTEM_PROMPT.startsWith("Hard rules:\n1."));
        assertRulesLead(AiContract.SOUND_SYSTEM_PROMPT);
        assertSoundConstraintsPresent(AiContract.SOUND_SYSTEM_PROMPT);
        assertSemanticsFollowTheRules(AiContract.SOUND_SYSTEM_PROMPT,
                "deterministic or Google baseline");
    }

    /** Every rule sentence from the previous prompt must survive somewhere in the new one. */
    private static void assertMeaningConstraintsPresent(String prompt) {
        assertTrue(prompt.contains("Translate rather than romanize"));
        assertTrue(prompt.contains("Respect the ordinary/adlib class"));
        assertTrue(prompt.contains("Return every requested id exactly once"));
        assertTrue(prompt.contains("Do not add ids, omit ids, merge rows, split rows"));
        assertTrue(prompt.contains("A row may remain unchanged when that is source-faithful"));
        assertTrue(prompt.contains("{\"items\":[{\"id\":string,\"t\":string}]}"));
    }

    private static void assertSoundConstraintsPresent(String prompt) {
        assertTrue(prompt.contains("Never echo source-script text as pronunciation"));
        assertTrue(prompt.contains("never translate meaning"));
        assertTrue(prompt.contains("must use Latin letters"));
        assertTrue(prompt.contains("requested target orthography"));
        assertTrue(prompt.contains("Return every requested id exactly once"));
        assertTrue(prompt.contains("{\"items\":[{\"id\":string,\"t\":string}]}"));
    }

    /**
     * Rules first means first: none of the long semantic prose may appear before the numbered
     * block ends.
     */
    private static void assertRulesLead(String prompt) {
        int rulesEnd = prompt.indexOf("\n\n");
        assertTrue("the rule block must be closed by a blank line", rulesEnd > 0);
        for (int number = 2; number <= 4; number++) {
            assertTrue("rule " + number + " missing",
                    prompt.contains("\n" + number + ". "));
        }
    }

    private static void assertSemanticsFollowTheRules(String prompt, String semanticsOpening) {
        int rulesEnd = prompt.indexOf("\n\n");
        int semanticsStart = prompt.indexOf(semanticsOpening);
        assertTrue(semanticsStart > rulesEnd);
    }

    /**
     * The repair instruction still composes in front of the contract it points at, whatever the
     * contract's internal layout is.
     */
    @Test
    public void theRepairInstructionComposesAheadOfTheRules() {
        String prompt = AiContract.buildSystemPrompt(LayerKind.MEANING, "en", true, false);
        assertTrue(prompt.startsWith(AiContract.REPAIR_PROMPT));
        assertTrue(prompt.contains("Hard rules:"));
    }
}
