package com.eza.spicyex.hooks;

import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyTrack;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UpcomingQueueTrackTest {
    private static final String QUEUED = "spotify:track:1234567890123456789012";
    private static final String PLAYLIST_NEXT = "spotify:track:abcdefghijklmnopqrstuv";

    @Test public void readsUpdatedHeadAndItsOwnMetadata() {
        HostTrack queued = new HostTrack(QUEUED, Map.of("title", "Queued", "artist_name", "First",
                "artist_name:1", "Second", "album_title", "Album", "duration_ms", "200000"));
        HostTrack playlist = new HostTrack(PLAYLIST_NEXT, Map.of("title", "Playlist"));
        SpotifyTrack next = References.nextQueuedTrack(new State(false, queued, playlist));
        assertEquals(QUEUED, next.uri);
        assertEquals("Queued", next.title);
        assertEquals("First, Second", next.artist);
        assertEquals("Album", next.album);
        assertEquals(200000L, next.duration);
        assertEquals(0L, next.position);
        assertEquals(0L, References.nextQueuedTrack(new State(false, playlist)).duration);
        assertEquals(PLAYLIST_NEXT, References.nextQueuedTrack(new State(false, playlist, queued)).uri);
    }

    @Test public void repeatOneAndAnUnsupportedHeadDoNotSpeculateOnLaterItems() {
        HostTrack track = new HostTrack(QUEUED, Collections.emptyMap());
        HostTrack episode = new HostTrack("spotify:episode:1234567890123456789012",
                Collections.emptyMap());
        assertNull(References.nextQueuedTrack(new State(true, track)));
        assertNull(References.nextQueuedTrack(new State(false, episode, track)));
        assertNull(References.nextQueuedTrack(new State(false)));
        assertNull(References.nextQueuedTrack(new Object()));
        assertNull(References.nextQueuedTrack(new Object() {
            public Object options() { throw new IllegalStateException("host accessor unavailable"); }
        }));
    }

    private static final class State {
        private final boolean repeatOne;
        private final HostTrack[] queue;

        State(boolean repeatOne, HostTrack... queue) {
            this.repeatOne = repeatOne;
            this.queue = queue;
        }

        public State options() { return this; }
        public boolean repeatingTrack() { return repeatOne; }
        public Iterable<HostTrack> nextTracks() { return Arrays.asList(queue); }
    }

    private static final class HostTrack {
        private final String uri;
        private final Map<String, String> metadata;

        HostTrack(String uri, Map<String, String> metadata) {
            this.uri = uri;
            this.metadata = metadata;
        }

        public String uri() { return uri; }
        public Map<String, String> metadata() { return metadata; }
    }
}
