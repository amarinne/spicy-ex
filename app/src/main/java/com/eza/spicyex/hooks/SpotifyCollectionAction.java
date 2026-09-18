package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyTrack;

import java.util.List;
import java.util.function.Supplier;

final class SpotifyCollectionAction {
    static final class AdvertisedAction {
        final String id;
        final String label;

        AdvertisedAction(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    interface Session {
        String packageName();
        String trackUri();
        List<AdvertisedAction> advertisedActions();
        boolean dispatch(String actionId);
    }

    private SpotifyCollectionAction() {}

    static boolean enabled(String mode) {
        return !Settings.LIKED_SONGS_BUTTON.defaultValue.equals(Settings.LIKED_SONGS_BUTTON.coerce(mode));
    }

    static boolean dispatch(String mode, SpotifyTrack expected, Supplier<SpotifyTrack> currentTrack,
                            Supplier<Session> sessionSupplier) {
        if (!enabled(mode) || !isSong(expected)) return false;
        try {
            SpotifyTrack current = currentTrack.get();
            if (!matches(expected, current)) return false;
            Session session = sessionSupplier.get();
            if (session == null || !"com.spotify.music".equals(session.packageName())
                    || !expected.uri.equals(session.trackUri())) return false;
            String actionId = collectionActionId(session.advertisedActions());
            if (actionId == null) return false;
            if (!matches(expected, currentTrack.get()) || !expected.uri.equals(session.trackUri())) return false;
            return session.dispatch(actionId);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static String collectionActionId(List<AdvertisedAction> actions) {
        if (actions == null) return null;
        for (AdvertisedAction action : actions) {
            if (action == null || action.id == null) continue;
            if (containsCollection(action.id)
                    || (action.label != null && containsCollection(action.label))) {
                return action.id;
            }
        }
        return null;
    }

    private static boolean containsCollection(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).contains("collection");
    }

    static boolean isSong(SpotifyTrack track) {
        return track != null && track.uri != null && track.uri.startsWith("spotify:track:")
                && track.uri.length() > "spotify:track:".length();
    }

    private static boolean matches(SpotifyTrack expected, SpotifyTrack current) {
        return current != null && expected.uri.equals(current.uri) && expected.saved == current.saved;
    }
}
