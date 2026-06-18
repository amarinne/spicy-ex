package com.eza.spicyex;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

public final class CurrentLyricState {
    public final String trackUri;
    public final String title;
    public final String artist;
    public final String backend;
    public final String language;
    public final String originalLine;
    public final String romanizedLine;
    public final String translatedLine;
    public final long positionMs;
    public final long durationMs;
    public final int lineIndex;
    public final boolean playing;
    public final long updatedAtMs;
    public final String status;
    public final String error;

    private static volatile CurrentLyricState current = empty();

    private CurrentLyricState(
            String trackUri,
            String title,
            String artist,
            String backend,
            String language,
            String originalLine,
            String romanizedLine,
            String translatedLine,
            long positionMs,
            long durationMs,
            int lineIndex,
            boolean playing,
            long updatedAtMs,
            String status,
            String error
    ) {
        this.trackUri = trackUri;
        this.title = title;
        this.artist = artist;
        this.backend = backend;
        this.language = language;
        this.originalLine = originalLine;
        this.romanizedLine = romanizedLine;
        this.translatedLine = translatedLine;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.lineIndex = lineIndex;
        this.playing = playing;
        this.updatedAtMs = updatedAtMs;
        this.status = status;
        this.error = error;
    }

    public static CurrentLyricState get() {
        return current;
    }

    public static void updateLine(
            SpotifyTrack track,
            String backend,
            String language,
            String originalLine,
            String romanizedLine,
            String translatedLine,
            long positionMs,
            int lineIndex,
            boolean playing,
            String status
    ) {
        if (track == null) return;
        current = new CurrentLyricState(
                track.uri,
                track.title,
                track.artist,
                backend,
                language,
                safe(originalLine),
                safe(romanizedLine),
                safe(translatedLine),
                positionMs,
                track.duration,
                lineIndex,
                playing,
                System.currentTimeMillis(),
                safe(status),
                ""
        );
    }

    public static void updatePosition(long positionMs, boolean playing) {
        CurrentLyricState old = current;
        current = new CurrentLyricState(
                old.trackUri,
                old.title,
                old.artist,
                old.backend,
                old.language,
                old.originalLine,
                old.romanizedLine,
                old.translatedLine,
                positionMs,
                old.durationMs,
                old.lineIndex,
                playing,
                System.currentTimeMillis(),
                old.status,
                old.error
        );
    }

    public static void updateStatus(SpotifyTrack track, String backend, String status, String error) {
        current = new CurrentLyricState(
                track == null ? "" : track.uri,
                track == null ? "" : track.title,
                track == null ? "" : track.artist,
                safe(backend),
                "",
                "",
                "",
                "",
                track == null ? 0 : track.position,
                track == null ? 0 : track.duration,
                -1,
                false,
                System.currentTimeMillis(),
                safe(status),
                safe(error)
        );
    }

    private static CurrentLyricState empty() {
        return new CurrentLyricState("", "", "", "", "", "", "", "", 0, 0, -1, false, 0, "idle", "");
    }

}
