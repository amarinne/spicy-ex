package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import java.util.regex.Pattern;

/** Rejects Spotify layout references captured by unrelated native model hooks. */
public final class NativeLyricsContent {
    private static final Pattern REFERENCES = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:,[A-Za-z][A-Za-z0-9_]*)*");

    private NativeLyricsContent() { }

    /** Requires multiple known layout references; ordinary prose and identifier-like lyrics pass. */
    public static boolean isLayoutDocument(LyricsDocument document) {
        if (document == null) return false;
        int references = 0;
        for (LyricsLine line : document.lines) {
            String text = line == null || line.text == null ? "" : line.text.trim();
            if (!REFERENCES.matcher(text).matches()) continue;
            for (String name : text.split(",")) {
                if (name.startsWith("now_playing_view_") || name.startsWith("fragment_container_")
                        || name.equals("ime_window_insets_space")
                        || name.equals("navigation_bar_window_insets_space")
                        || name.equals("status_bar_placeholder")
                        || name.equals("unobstructed_main_content_top_barrier")) {
                    if (++references >= 2) return true;
                }
            }
        }
        return false;
    }
}
