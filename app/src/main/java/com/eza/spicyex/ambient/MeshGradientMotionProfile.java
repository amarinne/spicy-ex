package com.eza.spicyex.ambient;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/** Pure, deterministic motion inputs for the Mesh Gradient effect. */
public final class MeshGradientMotionProfile {
    public static final float MIN_SPEED = 0.85f;
    public static final float MAX_SPEED = 1.25f;
    public static final float NEUTRAL_SPEED = 1f;
    public static final float EMA_ALPHA = 0.35f;
    public static final float TWO_PI = 6.283185307179586f;

    public final long seed;
    public final float phase;
    public final float speed;
    public final boolean hasTiming;

    private MeshGradientMotionProfile(long seed, float phase, float speed, boolean hasTiming) {
        this.seed = seed;
        this.phase = phase;
        this.speed = speed;
        this.hasTiming = hasTiming;
    }

    public static MeshGradientMotionProfile forTrack(String uri, String imageId,
                                                       LyricsDocument document) {
        String identity = clean(uri);
        if (identity.isEmpty()) identity = clean(imageId);
        long seed = stableSeed(identity);
        float phase = (float) (((seed >>> 11) & 0xFFFFFFL) / (double) 0x1000000L * TWO_PI);
        float speed = timingSpeed(document);
        return new MeshGradientMotionProfile(seed, phase, speed, hasUsefulTiming(document));
    }

    /** Applies a small EMA so successive document publications cannot jerk the motion rate. */
    public MeshGradientMotionProfile smoothFrom(MeshGradientMotionProfile previous) {
        if (previous == null) return this;
        if (!hasTiming) {
            return new MeshGradientMotionProfile(seed, phase, NEUTRAL_SPEED, false);
        }
        float smoothed = previous.speed + (speed - previous.speed) * EMA_ALPHA;
        return new MeshGradientMotionProfile(seed, phase, clamp(smoothed, MIN_SPEED, MAX_SPEED), hasTiming);
    }

    static long stableSeed(String identity) {
        long hash = 0xcbf29ce484222325L;
        String value = clean(identity);
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 0x9e3779b97f4a7c15L : hash;
    }

    static float timingSpeed(LyricsDocument document) {
        TimingStats stats = timingStats(document);
        if (!stats.useful) return NEUTRAL_SPEED;
        // Activity and event density are intentionally conservative. Interlude rows are excluded
        // from active time, so a long pause slows the profile without requiring playback position.
        float activity = clamp01(stats.activeMs / (float) Math.max(1L, stats.durationMs));
        float density = clamp01(stats.timedUnits * 60000f
                / (float) Math.max(1L, stats.durationMs) / 30f);
        float score = clamp01(activity * 0.7f + density * 0.3f);
        return MIN_SPEED + (MAX_SPEED - MIN_SPEED) * score;
    }

    static boolean hasUsefulTiming(LyricsDocument document) {
        return timingStats(document).useful;
    }

    private static TimingStats timingStats(LyricsDocument document) {
        if (document == null || document.lines == null || document.lines.isEmpty()) {
            return TimingStats.EMPTY;
        }
        long duration = Math.max(0L, document.durationMs);
        long activeMs = 0L;
        int timedUnits = 0;
        long maxEnd = 0L;
        for (LyricsLine line : document.lines) {
            if (line == null) continue;
            maxEnd = Math.max(maxEnd, Math.max(0L, line.endMs));
            if (line.interlude) continue;
            long lineDuration = Math.max(0L, line.endMs - line.startMs);
            if (lineDuration > 0L) {
                activeMs += lineDuration;
                timedUnits++;
            }
            long syllableDuration = 0L;
            if (line.syllables != null) {
                for (SyllableSegment syllable : line.syllables) {
                    if (syllable == null || syllable.endMs <= syllable.startMs) continue;
                    timedUnits++;
                    syllableDuration += syllable.endMs - syllable.startMs;
                    maxEnd = Math.max(maxEnd, syllable.endMs);
                }
            }
            if (lineDuration <= 0L) activeMs += syllableDuration;
        }
        if (duration <= 0L) duration = maxEnd;
        boolean useful = duration > 0L && timedUnits > 0 && activeMs > 0L;
        return useful ? new TimingStats(duration, activeMs, timedUnits, true) : TimingStats.EMPTY;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class TimingStats {
        static final TimingStats EMPTY = new TimingStats(0L, 0L, 0, false);
        final long durationMs;
        final long activeMs;
        final int timedUnits;
        final boolean useful;

        TimingStats(long durationMs, long activeMs, int timedUnits, boolean useful) {
            this.durationMs = durationMs;
            this.activeMs = activeMs;
            this.timedUnits = timedUnits;
            this.useful = useful;
        }
    }
}
