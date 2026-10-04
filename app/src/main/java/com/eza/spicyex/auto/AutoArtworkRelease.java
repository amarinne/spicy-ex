package com.eza.spicyex.auto;

/** Fades the existing lyric slot before restoring native content in the same slot. */
final class AutoArtworkRelease {
    static final long HALF_MS = 175;
    private String session;
    private boolean wanted;
    private long started = -1;

    void update(String session, boolean desired, boolean eligible, boolean animate, long now) {
        if (!java.util.Objects.equals(this.session, session) || !eligible || !animate) {
            this.session = session;
            wanted = desired && eligible;
            started = -1;
            return;
        }
        if (desired) started = -1;
        else if (wanted) started = now;
        wanted = desired;
        if (started >= 0 && now - started >= HALF_MS * 2) started = -1;
    }
    boolean running() { return started >= 0; }
    boolean retainLyrics(long now) { return started >= 0 && now - started < HALF_MS; }
    float lyricAlpha(long now) {
        return started < 0 ? 1f : Math.max(0f, 1f - Math.max(0, now - started) / (float) HALF_MS);
    }
    float nativeAlpha(long now) {
        return started < 0 ? 1f : Math.min(1f, Math.max(0f, (now - started - HALF_MS) / (float) HALF_MS));
    }
}
