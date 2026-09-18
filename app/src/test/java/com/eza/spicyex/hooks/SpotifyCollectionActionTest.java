package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class SpotifyCollectionActionTest {
    private static SpotifyTrack track(String uri, boolean saved) {
        return new SpotifyTrack("t", "a", "al", uri, 0, "", 0, null, 0, saved);
    }

    private static final class FakeSession implements SpotifyCollectionAction.Session {
        String packageName = "com.spotify.music";
        String trackUri;
        List<SpotifyCollectionAction.AdvertisedAction> actions;
        final AtomicReference<String> dispatched = new AtomicReference<>();

        @Override public String packageName() {
            return packageName;
        }

        @Override public String trackUri() {
            return trackUri;
        }

        @Override public List<SpotifyCollectionAction.AdvertisedAction> advertisedActions() {
            return actions;
        }

        @Override public boolean dispatch(String actionId) {
            dispatched.set(actionId);
            return true;
        }
    }

    private static SpotifyCollectionAction.AdvertisedAction action(String id, String label) {
        return new SpotifyCollectionAction.AdvertisedAction(id, label);
    }

    @Test
    public void onlyNonOffModesAreEnabled() {
        assertFalse(SpotifyCollectionAction.enabled("Off"));
        assertFalse(SpotifyCollectionAction.enabled("Plus"));
        assertTrue(SpotifyCollectionAction.enabled("Heart"));
        assertTrue(SpotifyCollectionAction.enabled("Star"));
        assertFalse(SpotifyCollectionAction.enabled("bogus"));
        assertFalse(SpotifyCollectionAction.enabled(null));
    }

    @Test
    public void offModeNeverDispatches() {
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "Add to collection"));
        SpotifyTrack expected = track("spotify:track:abc", false);
        assertFalse(SpotifyCollectionAction.dispatch("Off", expected, () -> expected, () -> session));
        assertNull(session.dispatched.get());
    }

    @Test
    public void rejectsNonSongsAndMismatches() {
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "Add to collection"));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", track("spotify:episode:1", false),
                () -> track("spotify:episode:1", false), () -> session));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", null, () -> null, () -> session));
        SpotifyTrack expected = track("spotify:track:abc", false);
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected,
                () -> track("spotify:track:other", false), () -> session));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> null));
        session.packageName = "com.other.app";
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
    }

    @Test
    public void requiresAdvertisedCollectionAction() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("play", "Play"));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
        session.actions = null;
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
    }

    @Test
    public void dispatchesCollectionActionMatchedByIdOrLabel() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession byLabel = new FakeSession();
        byLabel.trackUri = "spotify:track:abc";
        byLabel.actions = Arrays.asList(action("play", "Play"),
                action("custom.add", "Add to Collection"));
        assertTrue(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> byLabel));
        assertEquals("custom.add", byLabel.dispatched.get());

        FakeSession byId = new FakeSession();
        byId.trackUri = "spotify:track:abc";
        byId.actions = Collections.singletonList(action("com.spotify.ADD_TO_COLLECTION", null));
        assertTrue(SpotifyCollectionAction.dispatch("Star", expected, () -> expected, () -> byId));
        assertEquals("com.spotify.ADD_TO_COLLECTION", byId.dispatched.get());
    }

    @Test
    public void abortsWhenTrackChangesBeforeDispatch() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "collection"));
        Supplier<SpotifyTrack> flapping = new Supplier<SpotifyTrack>() {
            boolean first = true;

            @Override public SpotifyTrack get() {
                if (first) {
                    first = false;
                    return expected;
                }
                return track("spotify:track:abc", true);
            }
        };
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, flapping, () -> session));
        assertNull(session.dispatched.get());
    }
}
