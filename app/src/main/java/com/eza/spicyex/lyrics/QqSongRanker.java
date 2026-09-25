package com.eza.spicyex.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * Picks which QQ Music search hits to pull lyrics from, in preference order.
 *
 * <p>Grading and ordering live in {@link TrackMatchScorer} (a port of Lyricify-Lyrics-Helper's
 * matching); this class only knows QQ's JSON shape and which of its hits can be asked for
 * word-level lyrics.
 */
public final class QqSongRanker {

    /** How many distinct hits the caller should be willing to try word-level lyrics on. */
    public static final int MAX_WORD_LYRIC_ATTEMPTS = 3;

    private QqSongRanker() {
    }

    public static final class Candidate {
        public final String mid;
        public final long id;
        public final String title;
        public final TrackMatchScorer.Score score;
        public final long durationMs;
        /** Absolute runtime difference, or -1 when either side's runtime is unknown. */
        public final long durationDiffMs;

        Candidate(String mid, long id, String title, TrackMatchScorer.Score score,
                  long durationMs, long durationDiffMs) {
            this.mid = mid;
            this.id = id;
            this.title = title;
            this.score = score;
            this.durationMs = durationMs;
            this.durationDiffMs = durationDiffMs;
        }

        /** True when word-level ("QRC") lyrics can be requested at all for this hit. */
        public boolean supportsWordLyrics() {
            return id > 0;
        }

        @Override
        public String toString() {
            return "QqCandidate{" + mid + ",id=" + id
                    + ",score=" + Math.round(score.total * 10) / 10d
                    + "," + score.title + ",artist=" + score.artist + ",dt=" + durationDiffMs + "}";
        }
    }

    /**
     * Ranks every acceptable hit in the search response, best first.
     *
     * <p>Sub-entries under a hit's {@code grp} ("same-version tracks") array are flattened in
     * alongside their parent. QQ groups a song's other pressings there - the album cut, the single,
     * a regional release - and word-level lyrics are attached per entry, so the grouped siblings are
     * frequently the only ones that have any. Reading only the top-level list is why tracks that do
     * have karaoke timing kept coming back line-synced.
     *
     * @return an ordered, possibly empty list; never null.
     */
    public static List<Candidate> rank(JsonArray list, String trackTitle, String trackArtist,
                                       String trackAlbum, long trackDurationMs) {
        if (list == null || list.size() == 0) return Collections.emptyList();
        TrackMatchScorer.Target target =
                new TrackMatchScorer.Target(trackTitle, trackArtist, trackAlbum, trackDurationMs);
        List<Candidate> accepted = new ArrayList<>();
        for (JsonElement element : list) {
            collect(element, accepted, target, true);
        }
        Collections.sort(accepted, (a, b) -> TrackMatchScorer.compareForRanking(
                a.score, a.supportsWordLyrics(), a.durationDiffMs,
                b.score, b.supportsWordLyrics(), b.durationDiffMs));
        return accepted;
    }

    private static void collect(JsonElement element, List<Candidate> out,
                                TrackMatchScorer.Target target, boolean descend) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject song = element.getAsJsonObject();
        Candidate candidate = evaluate(song, target);
        if (candidate != null) out.add(candidate);
        if (!descend) return;
        JsonArray group = Json.optArray(song, "grp", "group");
        if (group == null) return;
        for (JsonElement sub : group) {
            // One level only: QQ never nests groups inside groups, and descending blindly would
            // risk looping on a malformed response.
            collect(sub, out, target, false);
        }
    }

    private static Candidate evaluate(JsonObject song, TrackMatchScorer.Target target) {
        String mid = Json.optString(song, "mid", "songmid", "songMid");
        if (isBlank(mid)) return null;

        String name = firstNonBlank(Json.optString(song, "name"), Json.optString(song, "title"));
        JsonObject albumObject = Json.optObject(song, "album");
        String albumName = albumObject == null ? "" : firstNonBlank(
                Json.optString(albumObject, "name"), Json.optString(albumObject, "title"));
        long durationMs = (long) (Json.optDouble(song, 0d, "interval", "duration") * 1000d);

        TrackMatchScorer.Score score = TrackMatchScorer.score(
                target, name, artistNames(song), albumName, durationMs);
        if (!score.accepted()) return null;
        return new Candidate(mid, numericSongId(song), name, score, durationMs,
                TrackMatchScorer.durationDiff(target.durationMs, durationMs));
    }

    /** Best hit only, or null when nothing in the response is acceptable. */
    public static Candidate best(JsonArray list, String trackTitle, String trackArtist,
                                 String trackAlbum, long trackDurationMs) {
        List<Candidate> ranked = rank(list, trackTitle, trackArtist, trackAlbum, trackDurationMs);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * QQ spells the word-level lyric id differently depending on which search surface answered
     * ("id" on the desktop search cgi, "songid"/"numid"/"musicid" elsewhere). Reading only "id"
     * meant those responses looked like they had no word-level lyrics at all, and the track was
     * quietly fetched line-synced.
     */
    static long numericSongId(JsonObject song) {
        double value = Json.optDouble(song, -1d, "id", "songid", "songId", "numid", "musicid", "song_id");
        if (value > 0) return (long) value;
        JsonObject nested = Json.optObject(song, "track_info", "trackInfo", "songInfo");
        if (nested != null) {
            double inner = Json.optDouble(nested, -1d, "id", "songid", "songId", "numid", "musicid");
            if (inner > 0) return (long) inner;
        }
        return -1;
    }

    /** Credited names from a QQ hit, lowercased. */
    static List<String> artistNames(JsonObject song) {
        List<String> names = new ArrayList<>();
        JsonArray singers = Json.optArray(song, "singer", "singers", "artist");
        if (singers == null) return names;
        for (JsonElement element : singers) {
            if (element == null || !element.isJsonObject()) continue;
            String name = Json.optString(element.getAsJsonObject(), "name", "title")
                    .toLowerCase(Locale.ROOT).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }
}
