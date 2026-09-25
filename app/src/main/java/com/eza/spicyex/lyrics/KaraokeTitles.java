package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Karaoke, off-vocal and instrumental versions carry the original song's title plus a version
 * tag, and no lyrics of their own anywhere. Searching the text sources with the tag removed (and,
 * for karaoke-label releases, the original performer as the artist) finds the original's lyrics,
 * which is exactly what a karaoke track is for. See {@code Settings#KARAOKE_ORIGINAL_LYRICS}.
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
        String artist = track.artist;
        Matcher performer = PERFORMED_BY.matcher(track.title);
        if (performer.find()) artist = performer.group(1).trim();
        String title = PERFORMED_BY.matcher(track.title).replaceAll("");
        title = BRACKET_TAG.matcher(title).replaceAll("");
        title = DASH_TAG.matcher(title).replaceAll("").trim();
        if (title.isEmpty()) return track;
        return new SpotifyTrack(title, artist, track.album, track.uri, track.position, track.color,
                track.lastUpdated, track.imageId, track.duration, track.saved);
    }
}
