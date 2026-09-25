package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;

public class NeteaseSongRankerTest {

    @Test
    public void titleIsNowComparedAtAll() {
        // The old matcher scored on runtime closeness plus a flat artist penalty and never looked
        // at the title, so a different song of the right length by the right artist won outright.
        JsonArray list = new JsonArray();
        list.add(song("Locked Out of Heaven", "Bruno Mars", "Unorthodox Jukebox", 233_000, 5));
        assertNull(NeteaseSongRanker.best(
                list, "When I Was Your Man", "Bruno Mars", "Unorthodox Jukebox", 233_000));
    }

    @Test
    public void correctSongWinsOverACloserRuntime() {
        JsonArray list = new JsonArray();
        list.add(song("Talking to the Moon", "Bruno Mars", "Doo-Wops", 217_000, 11));
        list.add(song("That's What I Like", "Bruno Mars", "24K Magic", 320_000, 22));
        NeteaseSongRanker.Candidate best = NeteaseSongRanker.best(
                list, "That's What I Like", "Bruno Mars", "24K Magic", 217_000);
        assertNotNull(best);
        assertEquals(22L, best.id);
    }

    @Test
    public void runtimeBreaksTiesBetweenEquallyGoodMatches() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 217_000, 1));
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214_000, 2));
        List<NeteaseSongRanker.Candidate> ranked =
                NeteaseSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertEquals(2, ranked.size());
        assertEquals(2L, ranked.get(0).id);
        assertEquals(0L, ranked.get(0).durationDiffMs);
    }

    @Test
    public void everyAcceptableHitIsKeptSoWordLyricsCanBeRetried() {
        // NetEase only reveals whether a recording has YRC by being asked, so the caller walks the
        // ranked list - which means the list has to contain more than just the winner.
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214_000, 1));
        list.add(song("Pompeii", "Bastille", "All This Bad Blood", 215_000, 2));
        list.add(song("Unrelated", "Someone Else", "Other", 214_000, 3));
        List<NeteaseSongRanker.Candidate> ranked =
                NeteaseSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertEquals(2, ranked.size());
        for (NeteaseSongRanker.Candidate candidate : ranked) {
            assertTrue(candidate.supportsWordLyrics());
        }
    }

    @Test
    public void rightSongByTheWrongArtistIsRejected() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "A Tribute Band", "Covers", 214_000, 1));
        assertNull(NeteaseSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000));
    }

    @Test
    public void durationsAreReadAsMillisecondsNotSeconds() {
        // NetEase reports milliseconds directly, unlike QQ's seconds. Misreading the unit would
        // make every runtime comparison fail.
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214_000, 7));
        NeteaseSongRanker.Candidate best =
                NeteaseSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertNotNull(best);
        assertEquals(214_000L, best.durationMs);
        assertEquals(0L, best.durationDiffMs);
        assertEquals(TrackMatchScorer.Tier.PERFECT, best.score.duration);
    }

    @Test
    public void newerCloudsearchFieldSpellingsAreAccepted() {
        // The eapi/cloudsearch surface spells these "dt", "ar" and "al".
        JsonObject song = new JsonObject();
        song.addProperty("id", 42);
        song.addProperty("name", "Pompeii");
        song.addProperty("dt", 214_000);
        JsonArray artists = new JsonArray();
        JsonObject artist = new JsonObject();
        artist.addProperty("name", "Bastille");
        artists.add(artist);
        song.add("ar", artists);
        JsonObject album = new JsonObject();
        album.addProperty("name", "Bad Blood");
        song.add("al", album);

        JsonArray list = new JsonArray();
        list.add(song);
        NeteaseSongRanker.Candidate best =
                NeteaseSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertNotNull(best);
        assertEquals(42L, best.id);
        assertEquals(214_000L, best.durationMs);
    }

    @Test
    public void hitsWithoutAUsableIdAreDropped() {
        JsonObject song = song("Pompeii", "Bastille", "Bad Blood", 214_000, 0);
        JsonArray list = new JsonArray();
        list.add(song);
        assertTrue(NeteaseSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000)
                .isEmpty());
    }

    @Test
    public void emptyOrMalformedResponsesRankNothing() {
        assertTrue(NeteaseSongRanker.rank(null, "x", "y", "z", 1).isEmpty());
        assertTrue(NeteaseSongRanker.rank(new JsonArray(), "x", "y", "z", 1).isEmpty());
    }

    @Test
    public void neteaseAndQqShareTheSameGradingSoSourcesAreComparable() {
        JsonArray netease = new JsonArray();
        netease.add(song("Pompeii", "Bastille", "Bad Blood", 214_000, 1));
        NeteaseSongRanker.Candidate neteaseBest =
                NeteaseSongRanker.best(netease, "Pompeii", "Bastille", "Bad Blood", 214_000);

        JsonArray qq = new JsonArray();
        JsonObject qqSong = new JsonObject();
        qqSong.addProperty("mid", "m");
        qqSong.addProperty("id", 1);
        qqSong.addProperty("name", "Pompeii");
        qqSong.addProperty("interval", 214); // seconds on this side
        JsonArray singers = new JsonArray();
        JsonObject singer = new JsonObject();
        singer.addProperty("name", "Bastille");
        singers.add(singer);
        qqSong.add("singer", singers);
        JsonObject album = new JsonObject();
        album.addProperty("name", "Bad Blood");
        qqSong.add("album", album);
        qq.add(qqSong);
        QqSongRanker.Candidate qqBest =
                QqSongRanker.best(qq, "Pompeii", "Bastille", "Bad Blood", 214_000);

        assertNotNull(neteaseBest);
        assertNotNull(qqBest);
        assertEquals(qqBest.score.total, neteaseBest.score.total, 0.0001d);
    }

    private static JsonObject song(String name, String artist, String album,
                                   long durationMs, long id) {
        JsonObject song = new JsonObject();
        song.addProperty("id", id);
        song.addProperty("name", name);
        song.addProperty("duration", durationMs);
        JsonArray artists = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("name", artist);
        artists.add(entry);
        song.add("artists", artists);
        JsonObject albumObject = new JsonObject();
        albumObject.addProperty("name", album);
        song.add("album", albumObject);
        return song;
    }
}
