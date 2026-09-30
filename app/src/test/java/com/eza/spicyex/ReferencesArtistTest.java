package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ReferencesArtistTest {
    @Test
    public void indexedArtistsJoinWithComma() {
        Map<String, String> md = new HashMap<>();
        md.put("artist_name:1", "Guest One");
        md.put("artist_name:2", "Guest Two");
        assertEquals("Main, Guest One, Guest Two", References.joinArtistNames("Main", md));
    }

    @Test
    public void singleArtistPassesThrough() {
        assertEquals("Main", References.joinArtistNames("Main", new HashMap<String, String>()));
    }

    @Test
    public void missingMetadataKeepsPrimary() {
        assertEquals("Main", References.joinArtistNames("Main", null));
        assertNull(References.joinArtistNames(null, null));
    }

    @Test
    public void indexedListStopsAtFirstGap() {
        Map<String, String> md = new HashMap<>();
        md.put("artist_name:1", "Guest One");
        md.put("artist_name:3", "Guest Three");
        assertEquals("Main, Guest One", References.joinArtistNames("Main", md));
    }

    @Test
    public void duplicatedPrimaryIsNotRepeated() {
        Map<String, String> md = new HashMap<>();
        md.put("artist_name:1", "Main");
        md.put("artist_name:2", "Guest");
        assertEquals("Main, Guest", References.joinArtistNames("Main", md));
    }
}
