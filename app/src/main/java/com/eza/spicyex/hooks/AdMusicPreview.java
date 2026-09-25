package com.eza.spicyex.hooks;

/** Settings-panel preview of the ad replacement music: a freshly composed piece each start. */
public final class AdMusicPreview {
    private static AdMusicPlayer player;

    private AdMusicPreview() {
    }

    /** Starts a new piece, or fades out the one playing. Returns whether it is now playing. */
    public static synchronized boolean toggle(String theme) {
        if (player != null && player.isPlaying()) {
            player.fadeOutAndStop();
            return false;
        }
        player = new AdMusicPlayer();
        player.setTheme(theme);
        player.fadeIn();
        return true;
    }

    public static synchronized void stop() {
        if (player != null) player.fadeOutAndStop();
    }
}
