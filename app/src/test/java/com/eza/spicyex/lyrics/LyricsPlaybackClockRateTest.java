package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.SpotifyTrack;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared clock must extrapolate at the real playback rate, not 1x, and freeze while
 * buffering. Uses an injected monotonic clock plus a scripted measurer: either silent after
 * the seed (pure extrapolation/anchoring) or serving live samples (transition re-measure).
 */
public class LyricsPlaybackClockRateTest {

    private static final class FakeMeasurer implements LyricsPlaybackClock.Measurer {
        long measured = -1;
        double rate = 1d;

        @Override public long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
            return measured;
        }

        @Override public double readEffectiveRate(boolean playing) {
            return rate;
        }
    }

    private static final class Harness {
        final AtomicLong now = new AtomicLong(0);
        final FakeMeasurer measurer = new FakeMeasurer();
        final LyricsPlaybackClock clock = new LyricsPlaybackClock(measurer, now::get);
        final SpotifyTrack track = new SpotifyTrack("title", "artist", "album",
                "spotify:track:rate", 0, null, 0, null, 600_000, false);

        Harness() {
            // Adopt the track first: the first getPosition on a new URI resets the clock.
            clock.reset(track.uri);
        }
    }

    @Test
    public void extrapolatesAtDoubleSpeed() {
        Harness h = new Harness();
        h.measurer.rate = 2d;
        h.clock.forcePosition(10_000, true);
        h.now.set(1_000);
        assertEquals(12_025, h.clock.getPosition(h.track, true));
    }

    @Test
    public void extrapolatesAtHalfSpeed() {
        Harness h = new Harness();
        h.measurer.rate = 0.5d;
        h.clock.forcePosition(10_000, true);
        h.now.set(1_000);
        assertEquals(10_525, h.clock.getPosition(h.track, true));
    }

    @Test
    public void freezesAtZeroRateWhileStillPlaying() {
        Harness h = new Harness();
        h.measurer.rate = 0d;
        h.clock.forcePosition(10_000, true);
        h.now.set(5_000);
        assertEquals(10_000, h.clock.getPosition(h.track, true));
        h.now.set(30_000);
        assertEquals(10_000, h.clock.getPosition(h.track, true));
    }

    @Test
    public void bufferingStopsImmediatelyAndResumeStaysAccurate() {
        Harness h = new Harness();
        h.clock.forcePosition(20_000, true);
        h.now.set(1_000);
        assertEquals(21_025, h.clock.getPosition(h.track, true));

        // Buffering keeps playing=true but reports rate 0: freeze at the transition point.
        h.measurer.rate = 0d;
        assertEquals(21_000, h.clock.getPosition(h.track, true));
        h.now.set(5_000);
        assertEquals(21_000, h.clock.getPosition(h.track, true));

        // Resume continues from the frozen point: no jump from the stalled interval.
        h.measurer.rate = 1d;
        assertEquals(21_025, h.clock.getPosition(h.track, true));
        h.now.set(6_000);
        assertEquals(22_025, h.clock.getPosition(h.track, true));
    }

    @Test
    public void rateChangeToHalfSpeedStaysContinuous() {
        Harness h = new Harness();
        h.clock.forcePosition(20_000, true);
        h.now.set(1_000);
        assertEquals(21_025, h.clock.getPosition(h.track, true));

        h.measurer.rate = 0.5d;
        assertEquals(21_025, h.clock.getPosition(h.track, true));
        h.now.set(2_000);
        assertEquals(21_525, h.clock.getPosition(h.track, true));
    }

    @Test
    public void pausedReportsFrozenPositionWithoutOffset() {
        Harness h = new Harness();
        h.measurer.rate = 0d;
        h.clock.forcePosition(10_000, false);
        h.now.set(5_000);
        assertEquals(10_000, h.clock.getPosition(h.track, false));
    }

    @Test
    public void measuredBufferTransitionBeforeResync() {
        Harness h = new Harness();
        h.measurer.measured = 20_000;
        h.measurer.rate = 1d;
        assertEquals(20_025, h.clock.getPosition(h.track, true));

        // 10ms later, well before the 50ms resync: buffering reports a fresh position the
        // baked extrapolation (20010) cannot know. The clock must trust it immediately.
        h.now.set(10);
        h.measurer.measured = 20_500;
        h.measurer.rate = 0d;
        assertEquals(20_500, h.clock.getPosition(h.track, true));

        h.now.set(5_000);
        assertEquals(20_500, h.clock.getPosition(h.track, true));
    }

    @Test
    public void measuredResumeBeforeResync() {
        Harness h = new Harness();
        h.measurer.measured = 30_000;
        h.measurer.rate = 0d;
        assertEquals(30_000, h.clock.getPosition(h.track, true));

        // Resume lands with truth ahead of the frozen anchor while no resync is due:
        // prediction must re-seat on it, not smooth up from 30000.
        h.now.set(10);
        h.measurer.measured = 30_050;
        h.measurer.rate = 1d;
        assertEquals(30_075, h.clock.getPosition(h.track, true));

        h.now.set(1_010);
        h.measurer.measured = 31_050;
        assertEquals(31_075, h.clock.getPosition(h.track, true));
    }

    @Test
    public void pausedWithPositiveMeasurerRateFreezes() {
        Harness h = new Harness();
        h.clock.forcePosition(10_000, true);
        h.now.set(1_000);
        assertEquals(11_025, h.clock.getPosition(h.track, true));

        // The measurer still reports a positive rate, but paused means zero immediately.
        assertEquals(11_000, h.clock.getPosition(h.track, false));
        h.now.set(5_000);
        assertEquals(11_000, h.clock.getPosition(h.track, false));
    }

    @Test
    public void measurerDefaultRatePreservesLambdaBehavior() {
        LyricsPlaybackClock.Measurer lambda = (track, playing) -> -1;
        assertEquals(1d, lambda.readEffectiveRate(true), 0d);
        assertEquals(0d, lambda.readEffectiveRate(false), 0d);
    }
}
