package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves the auto Chinese modes per song. {@code auto} becomes Pinyin or Jyutping and
 * {@code auto-eza} becomes the matching eza式 mode. A per-song manual choice wins over the guess;
 * otherwise {@link ChineseSongGuess} decides, and an undecided song reads as Pinyin.
 */
public final class ChineseAutoMode {
    public static final String AUTO = "auto";
    public static final String AUTO_EZA = "auto-eza";
    private static final int CACHE_SIZE = 64;
    private static final int OVERRIDE_SIZE = 256;

    private static final Map<String, ChineseSongGuess.Result> GUESSES =
            new LinkedHashMap<String, ChineseSongGuess.Result>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ChineseSongGuess.Result> eldest) {
                    return size() > CACHE_SIZE;
                }
            };
    /** Render-time fast path: a document object maps to its guess without rehashing its text. */
    private static final Map<LyricsDocument, ChineseSongGuess.Result> BY_DOCUMENT = new WeakHashMap<>();
    /** Track id to the base reading ({@code pinyin} or {@code jyutping}) the user chose. */
    private static final Map<String, String> OVERRIDES =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > OVERRIDE_SIZE;
                }
            };

    private ChineseAutoMode() {}

    public static boolean isAuto(String mode) {
        return AUTO.equalsIgnoreCase(mode) || AUTO_EZA.equalsIgnoreCase(mode);
    }

    /** The concrete mode for {@code doc}; a mode that is not auto is returned unchanged. */
    public static String resolve(String mode, LyricsDocument doc) {
        if (!isAuto(mode)) return mode;
        String trackId = doc == null ? "" : safe(doc.trackId);
        String chosen = override(trackId);
        String language = chosen != null
                ? (SpotifyPlusConfig.CHINESE_MODE_JYUTPING.equals(chosen) ? ChineseSongGuess.YUE : ChineseSongGuess.CMN)
                : guess(doc).language;
        return resolve(mode, language);
    }

    /** Pure mapping: {@code yue} reads as Jyutping, anything else as Pinyin. */
    static String resolve(String mode, String language) {
        String base = ChineseSongGuess.YUE.equals(language)
                ? SpotifyPlusConfig.CHINESE_MODE_JYUTPING : SpotifyPlusConfig.CHINESE_MODE_PINYIN;
        return AUTO_EZA.equalsIgnoreCase(mode) ? base + "-eza" : base;
    }

    /** Records the user's reading for one song; {@code null} returns the song to the guess. */
    public static void setOverride(String trackId, String baseMode) {
        if (trackId == null || trackId.isEmpty()) return;
        synchronized (OVERRIDES) {
            if (baseMode == null) OVERRIDES.remove(trackId);
            else OVERRIDES.put(trackId, baseMode.toLowerCase(Locale.ROOT).startsWith("jyutping")
                    ? SpotifyPlusConfig.CHINESE_MODE_JYUTPING : SpotifyPlusConfig.CHINESE_MODE_PINYIN);
        }
    }

    static String override(String trackId) {
        if (trackId == null || trackId.isEmpty()) return null;
        synchronized (OVERRIDES) {
            return OVERRIDES.get(trackId);
        }
    }

    /** The document's guess, computed once per track, lyrics text and guess revision. */
    public static ChineseSongGuess.Result guess(LyricsDocument doc) {
        if (doc != null) {
            synchronized (BY_DOCUMENT) {
                ChineseSongGuess.Result known = BY_DOCUMENT.get(doc);
                if (known != null) return known;
            }
        }
        ChineseSongGuess.Result result = guessByText(doc);
        if (doc != null) {
            synchronized (BY_DOCUMENT) {
                BY_DOCUMENT.put(doc, result);
            }
        }
        return result;
    }

    private static ChineseSongGuess.Result guessByText(LyricsDocument doc) {
        List<String> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (doc != null) {
            for (LyricsLine line : doc.lines) {
                if (line == null || line.text == null) continue;
                lines.add(line.text);
                text.append(line.text).append('\n');
            }
        }
        String key = (doc == null ? "" : safe(doc.trackId)) + '|' + text.length() + ':'
                + Integer.toHexString(text.toString().hashCode()) + '|' + ChineseSongGuess.REVISION;
        synchronized (GUESSES) {
            ChineseSongGuess.Result cached = GUESSES.get(key);
            if (cached != null) return cached;
        }
        ChineseSongGuess.Result result = ChineseSongGuess.guess(lines);
        synchronized (GUESSES) {
            GUESSES.put(key, result);
        }
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
