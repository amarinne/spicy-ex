package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class QqSongRankerTest {

    // --- candidate collection ------------------------------------------------

    @Test
    public void groupedAlternateVersionsAreRankedToo() {
        // QQ hangs a song's other pressings off "grp". Word-level lyrics are attached per entry,
        // so ignoring the group is what left word-synced tracks coming back line-synced.
        JsonObject parent = song("Pompeii", "Bastille", "Bad Blood", 214, 0, "parent");
        JsonArray group = new JsonArray();
        group.add(song("Pompeii", "Bastille", "Bad Blood", 215, 9001, "grouped"));
        parent.add("grp", group);

        JsonArray list = new JsonArray();
        list.add(parent);
        List<QqSongRanker.Candidate> ranked =
                QqSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertEquals(2, ranked.size());
        // The grouped sibling is the only one that can be word-synced, so it leads.
        assertEquals("grouped", ranked.get(0).mid);
        assertTrue(ranked.get(0).supportsWordLyrics());
    }

    @Test
    public void groupsAreNotDescendedRecursively() {
        JsonObject parent = song("Pompeii", "Bastille", "Bad Blood", 214, 1, "parent");
        JsonObject child = song("Pompeii", "Bastille", "Bad Blood", 214, 2, "child");
        JsonArray inner = new JsonArray();
        inner.add(song("Pompeii", "Bastille", "Bad Blood", 214, 3, "grandchild"));
        child.add("grp", inner);
        JsonArray group = new JsonArray();
        group.add(child);
        parent.add("grp", group);

        JsonArray list = new JsonArray();
        list.add(parent);
        List<QqSongRanker.Candidate> ranked =
                QqSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertEquals(2, ranked.size());
    }

    // --- ranking -------------------------------------------------------------

    @Test
    public void exactTitleAndArtistWinsOverCloserRuntimeWithWrongTitle() {
        JsonArray list = new JsonArray();
        list.add(song("Talking to the Moon", "Bruno Mars", "Doo-Wops", 217, 111, "wrong"));
        list.add(song("That's What I Like", "Bruno Mars", "24K Magic", 320, 222, "right"));
        QqSongRanker.Candidate best = QqSongRanker.best(
                list, "That's What I Like", "Bruno Mars", "24K Magic", 217_000);
        assertNotNull(best);
        assertEquals("right", best.mid);
    }

    @Test
    public void wrongTitleHitNeverSuppressesACorrectOne() {
        JsonArray list = new JsonArray();
        list.add(song("Completely Different Song", "Bruno Mars", "24K Magic", 200, 1, "wrong"));
        list.add(song("That's What I Like", "Bruno Mars", "24K Magic", 600, 2, "right"));
        QqSongRanker.Candidate best = QqSongRanker.best(
                list, "That's What I Like", "Bruno Mars", "24K Magic", 200_000);
        assertNotNull(best);
        assertEquals("right", best.mid);
    }

    @Test
    public void wordCapableHitOutranksLineOnlyTwinOnRuntime() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214, 0, "line-only"));
        list.add(song("Pompeii", "Bastille", "Bad Blood", 215, 900, "word-capable"));
        QqSongRanker.Candidate best =
                QqSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertNotNull(best);
        assertEquals("word-capable", best.mid);
        assertTrue(best.supportsWordLyrics());
    }

    @Test
    public void rankingKeepsEveryAcceptableHitSoWordLyricsCanBeRetried() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214, 901, "a"));
        list.add(song("Pompeii", "Bastille", "Bad Blood", 215, 902, "b"));
        list.add(song("Unrelated", "Someone Else", "Other", 214, 903, "c"));
        List<QqSongRanker.Candidate> ranked =
                QqSongRanker.rank(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertEquals(2, ranked.size());
        assertEquals("a", ranked.get(0).mid);
        assertEquals("b", ranked.get(1).mid);
    }

    // --- the identity gate ---------------------------------------------------

    @Test
    public void aDifferentSongByTheSameArtistIsRejected() {
        // Artist Perfect + duration Perfect alone scores well past the accept threshold; only the
        // title gate stops it. This is the "완전 다른 노래" case.
        JsonArray list = new JsonArray();
        list.add(song("Locked Out of Heaven", "Bruno Mars", "Unorthodox Jukebox", 233, 5, "nope"));
        assertNull(QqSongRanker.best(
                list, "When I Was Your Man", "Bruno Mars", "Unorthodox Jukebox", 233_000));
    }

    @Test
    public void shortTitleIsNotMatchedByAnUnrelatedLongerOne() {
        JsonArray list = new JsonArray();
        list.add(song("For Okinawa", "Another Artist", "Some Album", 200, 1, "nope"));
        assertNull(QqSongRanker.best(list, "Okinawa", "Real Artist", "Real Album", 200_000));
    }

    @Test
    public void rightSongByTheWrongArtistIsRejected() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "A Tribute Band", "Covers", 214, 1, "cover"));
        assertNull(QqSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000));
    }

    // --- graded field comparisons -------------------------------------------

    @Test
    public void remasterAndDashSuffixesStillMatch() {
        assertEquals(TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.compareName(
                "Bohemian Rhapsody", TrackMatchScorer.normalizeName("bohemian rhapsody")));
        // "Song - Remastered" vs "Song (Remastered)" is the same recording spelled two ways.
        assertTrue(TrackMatchScorer.compareName("Bohemian Rhapsody - 2011 Remaster",
                TrackMatchScorer.normalizeName("Bohemian Rhapsody (2011 Remaster)")).score
                >= TrackMatchScorer.Tier.VERY_HIGH.score);
    }

    @Test
    public void bracketedQualifierOnOneSideOnlyStillMatches() {
        assertTrue(TrackMatchScorer.compareName("Sunflower (feat. Swae Lee)",
                TrackMatchScorer.normalizeName("Sunflower")).score
                >= TrackMatchScorer.Tier.MEDIUM.score);
        assertTrue(TrackMatchScorer.compareName("Levitating (Deluxe)",
                TrackMatchScorer.normalizeName("Levitating")).score
                >= TrackMatchScorer.Tier.MEDIUM.score);
    }

    @Test
    public void unrelatedTitlesScoreNothing() {
        assertEquals(TrackMatchScorer.Tier.NONE,
                TrackMatchScorer.compareName("Zebra", TrackMatchScorer.normalizeName("Pompeii")));
    }

    @Test
    public void durationTiersFollowLyricifyThresholds() {
        assertEquals(TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.compareDuration(200_000, 200_000));
        assertEquals(TrackMatchScorer.Tier.VERY_HIGH, TrackMatchScorer.compareDuration(200_000, 200_200));
        assertEquals(TrackMatchScorer.Tier.HIGH, TrackMatchScorer.compareDuration(200_000, 200_500));
        assertEquals(TrackMatchScorer.Tier.MEDIUM, TrackMatchScorer.compareDuration(200_000, 201_000));
        assertEquals(TrackMatchScorer.Tier.LOW, TrackMatchScorer.compareDuration(200_000, 203_000));
        assertEquals(TrackMatchScorer.Tier.NONE, TrackMatchScorer.compareDuration(200_000, 260_000));
        assertNull(TrackMatchScorer.compareDuration(0, 200_000));
    }

    @Test
    public void artistTiersHandleMultiArtistCredits() {
        assertEquals(TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.compareArtists(Arrays.asList("bastille"), Arrays.asList("bastille")));
        // One credited on our side, two on theirs.
        assertEquals(TrackMatchScorer.Tier.HIGH, TrackMatchScorer.compareArtists(
                Arrays.asList("post malone"), Arrays.asList("post malone", "swae lee")));
        assertEquals(TrackMatchScorer.Tier.NONE, TrackMatchScorer.compareArtists(
                Arrays.asList("bastille"), Arrays.asList("a tribute band")));
        assertNull(TrackMatchScorer.compareArtists(Arrays.asList("bastille"), Arrays.asList()));
    }

    @Test
    public void creditedArtistStringIsSplitIntoComparableNames() {
        assertEquals(Arrays.asList("post malone", "swae lee"),
                TrackMatchScorer.splitArtists("Post Malone, Swae Lee"));
        assertEquals(Arrays.asList("calvin harris", "dua lipa"),
                TrackMatchScorer.splitArtists("Calvin Harris feat. Dua Lipa"));
    }

    @Test
    public void missingFieldsAreExcludedRatherThanScoredZero() {
        // Title + artist both Perfect with nothing else available must still reach a full score,
        // not be dragged down by the album/duration terms it could not compare.
        double renormalized = TrackMatchScorer.weightedScore(
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT, null, null);
        double withAll = TrackMatchScorer.weightedScore(TrackMatchScorer.Tier.PERFECT,
                TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT, TrackMatchScorer.Tier.PERFECT);
        assertEquals(withAll, renormalized, 0.0001d);
        assertTrue(renormalized > TrackMatchScorer.ACCEPT_SCORE);
    }

    @Test
    public void unknownRuntimeDoesNotPenaliseAHit() {
        JsonArray list = new JsonArray();
        JsonObject noInterval = song("Pompeii", "Bastille", "Bad Blood", 0, 700, "no-runtime");
        noInterval.remove("interval");
        list.add(noInterval);
        QqSongRanker.Candidate best =
                QqSongRanker.best(list, "Pompeii", "Bastille", "Bad Blood", 214_000);
        assertNotNull(best);
        assertEquals(-1, best.durationDiffMs);
        assertTrue(best.supportsWordLyrics());
    }

    @Test
    public void textSimilarityIsLongestCommonSubsequenceRatio() {
        assertEquals(100d, TrackMatchScorer.textSimilarity("abc", "abc"), 0.001d);
        assertEquals(75d, TrackMatchScorer.textSimilarity("abc", "abxc"), 0.001d);
        assertEquals(0d, TrackMatchScorer.textSimilarity("abc", ""), 0.001d);
        assertEquals(0d, TrackMatchScorer.textSimilarity("abc", "xyz"), 0.001d);
    }

    // --- plumbing ------------------------------------------------------------

    @Test
    public void alternateNumericIdSpellingsStillEnableWordLyrics() {
        JsonObject song = song("Pompeii", "Bastille", "Bad Blood", 214, 0, "alt");
        song.remove("id");
        song.addProperty("songid", 4242);
        assertEquals(4242L, QqSongRanker.numericSongId(song));
    }

    @Test
    public void missingArtistOnTrackFallsBackToTitleOnly() {
        JsonArray list = new JsonArray();
        list.add(song("Pompeii", "Bastille", "Bad Blood", 214, 9, "ok"));
        QqSongRanker.Candidate best = QqSongRanker.best(list, "Pompeii", "", "", 214_000);
        assertNotNull(best);
        assertEquals("ok", best.mid);
    }

    @Test
    public void emptyOrMalformedResponsesRankNothing() {
        assertTrue(QqSongRanker.rank(null, "x", "y", "z", 1).isEmpty());
        assertTrue(QqSongRanker.rank(new JsonArray(), "x", "y", "z", 1).isEmpty());
        JsonArray noMid = new JsonArray();
        JsonObject song = song("Pompeii", "Bastille", "Bad Blood", 214, 1, "");
        song.remove("mid");
        noMid.add(song);
        assertTrue(QqSongRanker.rank(noMid, "Pompeii", "Bastille", "Bad Blood", 214_000).isEmpty());
    }

    private static JsonObject song(String name, String artist, String album,
                                   int intervalSeconds, long id, String mid) {
        JsonObject song = new JsonObject();
        song.addProperty("mid", mid);
        song.addProperty("name", name);
        song.addProperty("interval", intervalSeconds);
        song.addProperty("id", id);
        JsonArray singers = new JsonArray();
        JsonObject singer = new JsonObject();
        singer.addProperty("name", artist);
        singers.add(singer);
        song.add("singer", singers);
        JsonObject albumObject = new JsonObject();
        albumObject.addProperty("name", album);
        song.add("album", albumObject);
        return song;
    }
}
