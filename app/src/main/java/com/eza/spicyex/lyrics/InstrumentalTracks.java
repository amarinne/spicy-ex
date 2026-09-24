package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Which tracks are instrumentals, so the lyrics screen can say so instead of "No lyrics found".
 *
 * <p>No source says it outright for every song, so this collects what the sources do say:
 * <ul>
 *   <li>LRCLIB marks a record {@code "instrumental": true};</li>
 *   <li>NetEase and QQ Music publish a placeholder in place of lyrics ("纯音乐，请欣赏",
 *       "此歌曲为没有填词的纯音乐，请您欣赏"), usually after a few credit lines;</li>
 *   <li>failing both, a title that names itself an instrumental, karaoke or off-vocal version.</li>
 * </ul>
 * It only ever changes how "no lyrics" is presented: a track any source has real lyrics for
 * shows those lyrics.
 */
public final class InstrumentalTracks {
    private static final Set<String> MARKED = ConcurrentHashMap.newKeySet();

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "纯音乐|純音樂|純音楽|没有填词|沒有填詞|请欣赏|請欣賞|请您欣赏"
                    + "|^[(\\[]?\\s*instrumental\\s*[)\\]]?$|^[♪♫\\s]+$",
            Pattern.CASE_INSENSITIVE);
    /** Credit lines ("作词 : ...", "Composer: ...") that providers prepend to the placeholder. */
    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|制作|製作|混音|母带|和声|吉他|贝斯|鼓|弦乐"
                    + "|演唱|原唱|监制|監製|出品|lyrics?|lyricist|composer|arranger|producer"
                    + "|written by|music by)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "\\binstrumental\\b|[(\\[\\-]\\s*inst\\.?\\s*[)\\]]?$|\\(inst\\b|off[ -]?vocal|\\bkaraoke\\b"
                    + "|伴奏|カラオケ|インスト|纯音乐|純音楽",
            Pattern.CASE_INSENSITIVE);

    private InstrumentalTracks() {
    }

    public static void mark(String trackId) {
        if (trackId != null && !trackId.isEmpty()) MARKED.add(trackId);
    }

    /** True when every line that is not a credit is an "instrumental, enjoy" placeholder. */
    public static boolean isPlaceholderOnly(List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) return false;
        int content = 0;
        for (LyricsLine line : lines) {
            String text = line == null || line.text == null ? "" : line.text.trim();
            if (text.isEmpty() || CREDIT.matcher(text).find()) continue;
            if (!PLACEHOLDER.matcher(text).find()) return false;
            content++;
        }
        return content > 0;
    }

    public static boolean titleSuggests(String title) {
        return title != null && TITLE.matcher(title).find();
    }

    public static boolean isInstrumental(SpotifyTrack track) {
        if (track == null) return false;
        return MARKED.contains(LyricUtils.trackIdFromUri(track.uri == null ? "" : track.uri))
                || titleSuggests(track.title);
    }
}
