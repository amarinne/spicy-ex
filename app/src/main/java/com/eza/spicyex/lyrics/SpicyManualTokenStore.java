package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;

/** Keystore-backed owner of the experimental desktop Spotify token used by Spicy. */
public final class SpicyManualTokenStore {
    private static final String SCOPE = "spicy_desktop_spotify_token";

    private SpicyManualTokenStore() {
    }

    public static String load(Context context) {
        return context == null ? "" : AiCredentialStore.create(context).load(SCOPE);
    }

    public static boolean save(Context context, String token) {
        return context != null && AiCredentialStore.create(context).save(SCOPE, token);
    }

    public static void delete(Context context) {
        if (context != null) AiCredentialStore.create(context).delete(SCOPE);
    }

    public static String masked(Context context) {
        String token = load(context);
        return token.isEmpty() ? "" : AiCredentialStore.mask(token);
    }
}
