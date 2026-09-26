package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Karaoke, off-vocal and instrumental versions carry the original song's title plus a version
 * tag, and no lyrics of their own anywhere. Searching the text sources with the tag removed finds
 * the original's lyrics, which is exactly what a karaoke track is for. See
 * {@code Settings#KARAOKE_ORIGINAL_LYRICS}.
 *
 * <p>Karaoke labels ("Urock Karaoke", "High Frequency Karaoke") release under their own name, so
 * their artist and album say nothing about the original: the performer is taken from an "In the
 * Style of X" / "Originally Performed by X" tag in the title or album when there is one, and
 * otherwise the search goes by title alone (every scorer treats an unknown artist as "not
 * comparable" rather than as a mismatch). "(From "Zootopia")" and the like go too, since the
 * original is usually listed without them.
 */
public final class KaraokeTitles {
    private static final String VERSION_WORDS =
            "karaoke|off[ -]?vocal|instrumental|inst\\.?|backing track|伴奏|カラオケ|オフボーカル|インスト";
    /** "(Karaoke Version)", "[Off Vocal]", "（カラオケ）", "(Inst.)"... */
    private static final Pattern BRACKET_TAG = Pattern.compile(
            "\\s*[(\\[（【][^)\\]）】]*?(?:" + VERSION_WORDS + ")[^)\\]）】]*[)\\]）】]",
            Pattern.CASE_INSENSITIVE);
    /** "Song - Karaoke Version", "Song - Instrumental" */
    private static final Pattern DASH_TAG = Pattern.compile(
            "\\s+[-–—]\\s+[^-–—]*?(?:" + VERSION_WORDS + ").*$", Pattern.CASE_INSENSITIVE);
    /** "(Originally Performed by X)", "[In the Style of X]" on karaoke-label releases. */
    private static final Pattern PERFORMED_BY = Pattern.compile(
            "\\s*[(\\[]\\s*(?:originally performed by|in the style of|made famous by|as made famous by)"
                    + "\\s+([^)\\]]+)[)\\]]",
            Pattern.CASE_INSENSITIVE);

    /** "(From "Zootopia")", "[From the Motion Picture X]", "- From "X"" */
    private static final Pattern FROM_TAG = Pattern.compile(
            "\\s*(?:[(\\[（【]\\s*from\\s[^)\\]）】]*[)\\]）】]|\\s[-–—]\\s+from\\s.*$)",
            Pattern.CASE_INSENSITIVE);
    /** A performer tag without brackets, as albums often carry it: "Hits in the Style of X". */
    private static final Pattern STYLE_OF = Pattern.compile(
            "(?:originally performed by|in the style of|made famous by)\\s+(.+?)"
                    + "\\s*(?:[)\\]（【(\\[]|\\s[-–—]\\s|$)",
            Pattern.CASE_INSENSITIVE);
    /** An artist name that is a karaoke/backing-track label, not the song's performer. */
    private static final Pattern LABEL_ARTIST = Pattern.compile(
            "karaoke|backing track|sing[ -]?along|off[ -]?vocal|伴奏|カラオケ|노래방",
            Pattern.CASE_INSENSITIVE);

    private KaraokeTitles() {
    }

    public static boolean isKaraokeVersion(String title) {
        if (title == null || title.isEmpty()) return false;
        return BRACKET_TAG.matcher(title).find() || DASH_TAG.matcher(title).find();
    }

    /** The track as the text sources should search for it: unchanged unless it is a karaoke-type
     *  version, in which case the original title (and performer, when named). Same URI, so the
     *  result still belongs to the playing track. */
    public static SpotifyTrack forLyricsSearch(SpotifyTrack track) {
        if (track == null || !isKaraokeVersion(track.title)) return track;
        String performer = performer(track.title);
        if (performer == null) performer = performer(track.album);
        String artist = performer != null ? performer
                : track.artist != null && LABEL_ARTIST.matcher(track.artist).find() ? "" : track.artist;
        String title = PERFORMED_BY.matcher(track.title).replaceAll("");
        title = BRACKET_TAG.matcher(title).replaceAll("");
        title = DASH_TAG.matcher(title).replaceAll("");
        title = FROM_TAG.matcher(title).replaceAll("").trim();
        if (title.isEmpty()) return track;
        // The karaoke release's album never matches the original's, and a search that filters
        // by it (LRCLIB does) finds nothing.
        return new SpotifyTrack(title, artist, "", track.uri, track.position, track.color,
                track.lastUpdated, track.imageId, track.duration, track.saved);
    }

    /** The original performer named in a title or album, or null. */
    static String performer(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher bracketed = PERFORMED_BY.matcher(text);
        if (bracketed.find()) return clean(bracketed.group(1));
        Matcher bare = STYLE_OF.matcher(text);
        if (bare.find()) return clean(bare.group(1));
        return null;
    }

    private static String clean(String name) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? null : value;
    }
}
