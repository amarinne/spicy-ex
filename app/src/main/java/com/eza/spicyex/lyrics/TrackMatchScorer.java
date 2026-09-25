package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * Decides how well a lyric-provider search hit matches the track that is actually playing.
 *
 * <p>Ported from Lyricify-Lyrics-Helper's {@code CompareHelper}
 * (github.com/WXRIW/Lyricify-Lyrics-Helper), whose matching is markedly better than comparing a
 * couple of fields by hand. Each field is graded into a {@link Tier}, the tiers are scored and
 * weighted, and fields the response does not carry are dropped from the total with the remaining
 * weights renormalised - so a hit with no album information is not quietly penalised for lacking
 * it. Their score constants and thresholds are kept verbatim so the tuning that makes it work
 * survives the port.
 *
 * <p>Provider-agnostic on purpose: QQ Music and NetEase return completely different JSON but the
 * question being asked of them ("is this the same recording?") is identical, and getting it wrong
 * has the same consequence either way - a confidently displayed wrong song.
 *
 * @see QqSongRanker
 * @see NeteaseSongRanker
 */
public final class TrackMatchScorer {

    private TrackMatchScorer() {
    }

    /** Graded agreement on one field. Scores are Lyricify's {@code GetMatchScore} values. */
    public enum Tier {
        NONE(0), LOW(2), MEDIUM(4), HIGH(5), VERY_HIGH(6), PERFECT(7);

        public final int score;

        Tier(int score) {
            this.score = score;
        }
    }

    // Lyricify's CompareTrack weights. albumArtist is never available from these providers' search
    // responses, so it never contributes - which is exactly what renormalising unavailable fields
    // already does, leaving the accept threshold below valid as written.
    private static final double WEIGHT_TITLE = 1.0;
    private static final double WEIGHT_ARTIST = 1.0;
    private static final double WEIGHT_ALBUM = 0.4;
    private static final double WEIGHT_ALBUM_ARTIST = 0.2;
    private static final double WEIGHT_DURATION = 1.0;
    private static final double FULL_SCORE =
            (WEIGHT_TITLE + WEIGHT_ARTIST + WEIGHT_ALBUM + WEIGHT_ALBUM_ARTIST + WEIGHT_DURATION)
                    * Tier.PERFECT.score;
    /** Lyricify's "Medium" tier. Below this a hit is not the same recording. */
    public static final double ACCEPT_SCORE = 11d;

    /** The playing track, normalised once so every candidate is compared against the same form. */
    public static final class Target {
        public final String title;
        public final String album;
        public final List<String> artists;
        public final long durationMs;

        public Target(String title, String artist, String album, long durationMs) {
            this.title = title == null ? "" : normalizeName(title);
            this.album = album == null ? "" : normalizeName(album);
            this.artists = splitArtists(artist);
            this.durationMs = Math.max(0, durationMs);
        }

        public boolean artistUnknown() {
            return artists.isEmpty();
        }
    }

    /** One candidate's graded agreement, plus the two totals ranking is done on. */
    public static final class Score {
        public final Tier title;
        public final Tier artist;
        public final Tier album;
        public final Tier duration;
        /** Full weighted total, used for the accept threshold. */
        public final double total;
        /** Total with the runtime term left out - see {@link #compareForRanking}. */
        public final double identity;

        Score(Tier title, Tier artist, Tier album, Tier duration, double total, double identity) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.total = total;
            this.identity = identity;
        }

        public boolean accepted() {
            return accept(title, artist, total);
        }
    }

    /** Grades one candidate against {@code target}. Blank/absent fields are excluded, not zeroed. */
    public static Score score(Target target, String candidateTitle, List<String> candidateArtists,
                              String candidateAlbum, long candidateDurationMs) {
        Tier title = compareName(candidateTitle, target.title);
        Tier artist = target.artistUnknown() || candidateArtists == null || candidateArtists.isEmpty()
                ? null : compareArtists(target.artists, candidateArtists);
        Tier album = target.album.isEmpty() ? null : compareName(candidateAlbum, target.album);
        Tier duration = compareDuration(target.durationMs, candidateDurationMs);
        return new Score(title, artist, album, duration,
                weightedScore(title, artist, album, duration),
                weightedScore(title, artist, album, null));
    }

    /**
     * Lyricify's weighted total, renormalised over the fields actually comparable. {@code null}
     * means "not comparable" and is excluded from both the numerator and the denominator rather
     * than scoring zero, which would otherwise punish a provider for omitting optional metadata.
     */
    public static double weightedScore(Tier title, Tier artist, Tier album, Tier duration) {
        double total = 0d;
        total += (title == null ? Tier.NONE : title).score * WEIGHT_TITLE;
        total += (artist == null ? Tier.NONE : artist).score * WEIGHT_ARTIST;
        if (album != null) total += album.score * WEIGHT_ALBUM;
        if (duration != null) total += duration.score * WEIGHT_DURATION;

        double available = (WEIGHT_TITLE + WEIGHT_ARTIST) * Tier.PERFECT.score;
        if (album != null) available += WEIGHT_ALBUM * Tier.PERFECT.score;
        if (duration != null) available += WEIGHT_DURATION * Tier.PERFECT.score;
        if (available <= 0d) return 0d;
        return total * FULL_SCORE / available;
    }

    /**
     * The identity gate. A passing weighted score is necessary but not sufficient: artist and
     * runtime alone can carry a hit over the line with the title contributing nothing, which is how
     * a different song by the same artist gets picked. A candidate must independently agree on
     * title, and on artist whenever the playing track's artist is known, before it is eligible at
     * all - so a provider simply not having the track falls through to the next source instead of
     * confidently showing someone else's lyrics.
     */
    public static boolean accept(Tier title, Tier artist, double total) {
        if (total < ACCEPT_SCORE) return false;
        if (title == null || title.score < Tier.MEDIUM.score) return false;
        // artist == null means the playing track had no artist to compare against, not that the
        // comparison failed; score() only leaves it null in that case.
        return artist == null || artist.score >= Tier.LOW.score;
    }

    /**
     * Orders two candidates: identity first, then whether word-level lyrics are even obtainable,
     * then runtime closeness.
     *
     * <p>Title, artist and album answer "which song is this"; runtime answers "which pressing", and
     * for lyrics purposes pressings of the same song are interchangeable - word-level availability
     * is not. Ranking on the full score instead lets a few seconds of runtime difference (a whole
     * duration tier, worth more than three points) outweigh the difference between a hit that can
     * be word-synced and one that can only ever come back line-synced, which is exactly what this
     * ranking exists to get right.
     */
    public static int compareForRanking(Score aScore, boolean aWord, long aDurationDiff,
                                        Score bScore, boolean bWord, long bDurationDiff) {
        if (Math.abs(aScore.identity - bScore.identity) > 0.5d) {
            return Double.compare(bScore.identity, aScore.identity);
        }
        if (aWord != bWord) return aWord ? -1 : 1;
        if (aDurationDiff != bDurationDiff) {
            if (aDurationDiff < 0) return 1;
            if (bDurationDiff < 0) return -1;
            return Long.compare(aDurationDiff, bDurationDiff);
        }
        return Double.compare(bScore.total, aScore.total);
    }

    /** Absolute runtime difference, or -1 when either side is unknown. */
    public static long durationDiff(long targetMs, long candidateMs) {
        return targetMs > 0 && candidateMs > 0 ? Math.abs(targetMs - candidateMs) : -1;
    }

    // --- field comparisons --------------------------------------------------

    /** Graded title/album agreement. {@code normalizedTarget} must already be normalised. */
    public static Tier compareName(String rawName, String normalizedTarget) {
        if (isBlank(rawName) || isBlank(normalizedTarget)) return null;
        String name = normalizeName(rawName);
        String target = normalizedTarget;
        if (name.isEmpty() || target.isEmpty()) return null;
        if (name.equals(target)) return Tier.PERFECT;

        name = name.replace("acoustic version", "acoustic");
        target = target.replace("acoustic version", "acoustic");

        // "Song - Remastered" and "Song (Remastered)" are the same recording spelled two ways.
        if (removeSpaces(name.replace(" - ", " (").trim() + ")")
                .equals(removeSpaces(target.replace(" - ", " (").trim() + ")"))) {
            return Tier.VERY_HIGH;
        }

        for (String marker : new String[]{"deluxe", "explicit", "special edition", "bonus track",
                "feat", "with"}) {
            if (suffixOnlyOnOneSide(name, target, marker)) return Tier.VERY_HIGH;
        }
        if (sameStemBeforeMarkers(name, target, "feat", "explicit")
                || sameStemBeforeMarkers(name, target, "with", "explicit")
                || sameStemBeforeMarkers(name, target, "feat", "feat")
                || sameStemBeforeMarkers(name, target, "with", "with")) {
            return Tier.HIGH;
        }
        if (sameStemBeforeBracket(name, target)) return Tier.MEDIUM;

        // Same length: catches variant CJK characters (異體字) differing in a few glyphs only.
        if (name.length() == target.length()) {
            int same = 0;
            for (int i = 0; i < name.length(); i++) {
                if (name.charAt(i) == target.charAt(i)) same++;
            }
            double ratio = same / (double) name.length();
            if (ratio >= 0.8d && name.length() >= 4
                    || ratio >= 0.5d && name.length() >= 2 && name.length() <= 3) {
                return Tier.HIGH;
            }
        }

        double similarity = textSimilarity(name, target);
        if (similarity > 90d) return Tier.VERY_HIGH;
        if (similarity > 80d) return Tier.HIGH;
        if (similarity > 68d) return Tier.MEDIUM;
        if (similarity > 55d) return Tier.LOW;
        return Tier.NONE;
    }

    /** Graded artist agreement over the two credited-name lists, both already lowercased. */
    public static Tier compareArtists(List<String> target, List<String> found) {
        if (target == null || found == null || target.isEmpty() || found.isEmpty()) return null;
        int shared = 0;
        for (String artist : found) {
            if (target.contains(artist)) shared++;
        }
        if (shared == target.size() && target.size() == found.size()) return Tier.PERFECT;
        if (shared + 1 >= target.size() && target.size() >= 2
                || target.size() > 6 && shared / (double) target.size() > 0.8d) {
            return Tier.VERY_HIGH;
        }
        if (shared == 1 && target.size() == 1 && found.size() == 2) return Tier.HIGH;
        if (target.size() > 5 && (found.get(0).contains("various") || found.get(0).contains("群星"))) {
            return Tier.VERY_HIGH;
        }
        if (target.size() > 7 && found.size() > 7 && shared / (double) target.size() > 0.66d) {
            return Tier.HIGH;
        }
        if (target.size() == 1 && found.size() > 1 && target.get(0).startsWith(found.get(0))) {
            return Tier.HIGH;
        }
        if (target.size() == 1 && found.size() > 1 && found.get(0).length() > 3
                && target.get(0).contains(found.get(0))) {
            return Tier.HIGH;
        }
        if (target.size() == 1 && found.size() > 1 && found.get(0).length() > 1
                && target.get(0).contains(found.get(0))) {
            return Tier.MEDIUM;
        }
        if (shared == 1 && target.size() == 1 && found.size() >= 3) return Tier.MEDIUM;
        if (shared >= 2) return Tier.LOW;
        // A single credited artist that neither list-matched nor prefix-matched can still be the
        // same person spelled differently ("Post Malone" vs "postmalone").
        if (target.size() == 1 && found.size() == 1) {
            String a = removeSpaces(target.get(0));
            String b = removeSpaces(found.get(0));
            if (!a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a))) return Tier.MEDIUM;
        }
        return Tier.NONE;
    }

    /** Graded runtime agreement, on Lyricify's millisecond thresholds. */
    public static Tier compareDuration(long targetMs, long foundMs) {
        if (targetMs <= 0 || foundMs <= 0) return null;
        long diff = Math.abs(targetMs - foundMs);
        if (diff == 0) return Tier.PERFECT;
        if (diff < 300) return Tier.VERY_HIGH;
        if (diff < 700) return Tier.HIGH;
        if (diff < 1500) return Tier.MEDIUM;
        if (diff < 3500) return Tier.LOW;
        return Tier.NONE;
    }

    /**
     * Longest-common-subsequence agreement as a percentage of the longer string - Lyricify's
     * {@code ComputeTextSame}. Order-sensitive but insertion-tolerant, which is what distinguishes
     * "same title with an extra qualifier" from "different title that shares some words".
     */
    public static double textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0d;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int i = 0; i < a.length(); i++) {
            for (int j = 0; j < b.length(); j++) {
                current[j + 1] = a.charAt(i) == b.charAt(j)
                        ? previous[j] + 1
                        : Math.max(previous[j + 1], current[j]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] / (double) Math.max(a.length(), b.length()) * 100d;
    }

    private static boolean suffixOnlyOnOneSide(String a, String b, String marker) {
        String needle = "(" + marker;
        boolean inA = a.contains(needle);
        boolean inB = b.contains(needle);
        if (inA && !inB) return a.substring(0, a.indexOf(needle)).trim().equals(b);
        if (inB && !inA) return b.substring(0, b.indexOf(needle)).trim().equals(a);
        return false;
    }

    private static boolean sameStemBeforeMarkers(String a, String b, String markerA, String markerB) {
        String needleA = "(" + markerA;
        String needleB = "(" + markerB;
        if (a.contains(needleA) && b.contains(needleB)
                && a.substring(0, a.indexOf(needleA)).trim()
                        .equals(b.substring(0, b.indexOf(needleB)).trim())) {
            return true;
        }
        return a.contains(needleB) && b.contains(needleA)
                && a.substring(0, a.indexOf(needleB)).trim()
                        .equals(b.substring(0, b.indexOf(needleA)).trim());
    }

    private static boolean sameStemBeforeBracket(String a, String b) {
        if (a.contains("(") && !b.contains("(")) {
            return a.substring(0, a.indexOf('(')).trim().equals(b);
        }
        if (b.contains("(") && !a.contains("(")) {
            return b.substring(0, b.indexOf('(')).trim().equals(a);
        }
        return false;
    }

    /**
     * Lowercases and regularises the punctuation titles differ in across catalogues, while keeping
     * the words themselves intact. Deliberately does NOT strip spaces or drop bracketed sections:
     * the graded comparisons above need to see them to tell "same song, reformatted" from
     * "different song sharing a word".
     */
    public static String normalizeName(String value) {
        if (isBlank(value)) return "";
        String lower = value.toLowerCase(Locale.ROOT).trim()
                .replace('’', '\'')
                .replace('，', ',')
                .replace('（', '(')
                .replace('）', ')')
                .replace('[', '(')
                .replace(']', ')');
        lower = lower.replaceAll("\\s+", " ");
        return lower.replace(" (", "(").replace("( ", "(").replace(" )", ")");
    }

    private static String removeSpaces(String value) {
        return value == null ? "" : value.replace(" ", "");
    }

    /** Splits a single credited string ("A, B & C", "A feat. B") into comparable names. */
    public static List<String> splitArtists(String value) {
        List<String> names = new ArrayList<>();
        if (isBlank(value)) return names;
        // "feat." must be consumed including its period: a \b placed after an optional "\." never
        // matches, because there is no word boundary between "." and the following space, which
        // would leave a stray "." glued to the next credited name.
        for (String part : value.toLowerCase(Locale.ROOT)
                .split(",|&|/|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bwith\\b|\\bx\\b")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        if (names.isEmpty()) names.add(value.toLowerCase(Locale.ROOT).trim());
        return names;
    }
}
