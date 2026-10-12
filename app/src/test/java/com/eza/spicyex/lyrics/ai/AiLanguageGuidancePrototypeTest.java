package com.eza.spicyex.lyrics.ai;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the isolated selector; language quality needs separate Vietnamese review. */
public class AiLanguageGuidancePrototypeTest {
    private static JsonObject rules() {
        return JsonParser.parseString("{\"rules\":["
                + "{\"id\":\"vi-target\",\"sourceLanguages\":[\"*\"],\"targetLanguages\":[\"vi\"],"
                + "\"instructions\":\"Keep pronouns consistent.\"},"
                + "{\"id\":\"vi-source\",\"sourceLanguages\":[\"vi\"],\"targetLanguages\":[\"*\"],"
                + "\"instructions\":\"Preserve forms of address.\"}]}").getAsJsonObject();
    }

    @Test public void targetGuidanceWorksWithoutAssumingUnknownSourceLanguage() {
        JsonObject result = AiHarnessModelComparisonTool.languagePreview("und", "vi-VN", rules(), "");
        assertEquals("[\"vi-target\"]", result.get("selectedRules").toString());
        assertEquals(0, result.get("providerCalls").getAsInt());
    }

    @Test public void sourceGuidanceIsDistinctFromTargetGuidance() {
        JsonObject result = AiHarnessModelComparisonTool.languagePreview("vi", "en", rules(), "");
        assertEquals("[\"vi-source\"]", result.get("selectedRules").toString());
    }

    @Test public void unrecognizedLanguagesReceiveNoInventedLanguagePolicy() {
        JsonObject result = AiHarnessModelComparisonTool.languagePreview("und", "ja", rules(), "");
        assertEquals(0, result.getAsJsonArray("selectedRules").size());
        assertEquals("", result.get("instructions").getAsString());
    }

    @Test public void ownerInstructionsFollowBothLanguageRules() {
        JsonObject result = AiHarnessModelComparisonTool.languagePreview("vi", "vi", rules(), "  Preserve names.  ");
        assertEquals(2, result.getAsJsonArray("selectedRules").size());
        assertEquals("Keep pronouns consistent.\nPreserve forms of address.\nPreserve names.",
                result.get("instructions").getAsString());
    }

    @Test public void addingGuidanceChangesConfigurationIdentityInsteadOfRebindingOutput() {
        JsonObject plainRules = JsonParser.parseString("{\"rules\":[]}").getAsJsonObject();
        JsonObject plain = AiHarnessModelComparisonTool.languagePreview("en", "vi", plainRules, "");
        JsonObject guided = AiHarnessModelComparisonTool.languagePreview("en", "vi", rules(), "");
        assertNotEquals(plain.get("configId"), guided.get("configId"));
        assertEquals(plain.get("systemPrompt"), guided.get("systemPrompt"));
    }
}
