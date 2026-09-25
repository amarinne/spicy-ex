package com.eza.spicyex;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiSettings;

/**
 * The API key row's value, split from the panel that draws it.
 *
 * <p>The row only ever holds a mask. Plaintext is loaded for one secure, non-selectable,
 * accessibility-hidden dialog and goes nowhere else, so keeping the two reads here is what makes
 * that promise checkable instead of a matter of where the view happened to call.
 */
final class ApiKeyRowState {

    private final AiSettings settings;

    ApiKeyRowState(AiSettings settings) {
        this.settings = settings;
    }

    boolean hasCredential() {
        return settings != null && settings.hasCredential();
    }

    /** What the row shows: a mask, or empty when no key is stored. */
    String displayed() {
        if (settings == null || !settings.hasCredential()) return "";
        return AiCredentialStore.mask(secret());
    }

    /** The plaintext, for the secure dialog only. Reading it changes nothing that is persisted. */
    String revealedSecret() {
        return settings == null ? "" : secret();
    }

    private String secret() {
        return settings.credentials().load(settings.credentialScope());
    }
}
