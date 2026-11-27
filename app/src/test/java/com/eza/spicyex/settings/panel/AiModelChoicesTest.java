package com.eza.spicyex.settings.panel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class AiModelChoicesTest {
    @Test public void currentModelLeadsAndIsNotRepeated() {
        assertEquals(Arrays.asList("b", "a", "c"),
                AiModelChoices.ordered(Arrays.asList("a", "b", "c", "b", " "), "b"));
    }

    @Test public void currentModelMissingFromDiscoveryStillLeads() {
        assertEquals(Arrays.asList("typed/model", "a"),
                AiModelChoices.ordered(Collections.singletonList("a"), "typed/model"));
        assertEquals(Collections.singletonList("a"),
                AiModelChoices.ordered(Collections.singletonList("a"), ""));
    }

    @Test public void typedIdIsOfferedOnlyWhenNoListedModelMatchesExactly() {
        assertEquals("deepseek/deepseek-v4.1-flash", AiModelChoices.typedChoice(
                "  deepseek/deepseek-v4.1-flash ", Collections.singletonList("deepseek-v4-flash")));
        assertEquals("", AiModelChoices.typedChoice("deepseek-v4-flash",
                Collections.singletonList("deepseek-v4-flash")));
        assertEquals("", AiModelChoices.typedChoice("   ", Collections.<String>emptyList()));
    }

    @Test public void multiWordSearchIsNotAModelId() {
        assertEquals("", AiModelChoices.typedChoice("opencode deepseek",
                Collections.<String>emptyList()));
    }
}
