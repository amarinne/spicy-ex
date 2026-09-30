package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.session.LayerKind;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import org.junit.Test;

/**
 * F1: restoring one layer must cancel and settle only that layer. Cancelling Sound retires
 * the Sound lane (sequence advances, tag cleared) while the Meaning lane keeps its run
 * (sequence and tag untouched), and vice versa.
 */
public class LyricsSecondaryProcessorCancelLayerTest {
    private LyricsSecondaryProcessor processor() {
        java.util.concurrent.ExecutorService direct =
                new java.util.concurrent.AbstractExecutorService() {
                    @Override public void execute(Runnable command) {
                        command.run();
                    }

                    @Override public java.util.List<Runnable> shutdownNow() {
                        return java.util.Collections.emptyList();
                    }

                    @Override public void shutdown() {
                    }

                    @Override public boolean isShutdown() {
                        return false;
                    }

                    @Override public boolean isTerminated() {
                        return false;
                    }

                    @Override public boolean awaitTermination(long timeout,
                            java.util.concurrent.TimeUnit unit) {
                        return true;
                    }
                };
        return new LyricsSecondaryProcessor(null, new OkHttpClient(), direct, direct, direct,
                direct, null, 0);
    }

    private Object lane(LyricsSecondaryProcessor processor, String field) throws Exception {
        Field lane = LyricsSecondaryProcessor.class.getDeclaredField(field);
        lane.setAccessible(true);
        return lane.get(processor);
    }

    private long sequence(Object lane) throws Exception {
        Field sequence = lane.getClass().getDeclaredField("laneSequence");
        sequence.setAccessible(true);
        return ((AtomicLong) sequence.get(lane)).get();
    }

    private String tag(Object lane) throws Exception {
        Field activeTag = lane.getClass().getDeclaredField("activeTag");
        activeTag.setAccessible(true);
        return (String) activeTag.get(lane);
    }

    private void tag(Object lane, String value) throws Exception {
        Field activeTag = lane.getClass().getDeclaredField("activeTag");
        activeTag.setAccessible(true);
        activeTag.set(lane, value);
    }

    @Test
    public void cancellingSoundLeavesMeaningRunning() throws Exception {
        LyricsSecondaryProcessor processor = processor();
        Object sound = lane(processor, "soundLane");
        Object meaning = lane(processor, "meaningLane");
        tag(sound, "SOUND#1");
        tag(meaning, "MEANING#1");
        long soundSequence = sequence(sound);
        long meaningSequence = sequence(meaning);

        processor.cancelLayer(LayerKind.SOUND);

        assertEquals("", tag(sound));
        assertEquals(soundSequence + 1, sequence(sound));
        assertEquals("MEANING#1", tag(meaning));
        assertEquals(meaningSequence, sequence(meaning));
    }

    @Test
    public void cancellingMeaningLeavesSoundRunning() throws Exception {
        LyricsSecondaryProcessor processor = processor();
        Object sound = lane(processor, "soundLane");
        Object meaning = lane(processor, "meaningLane");
        tag(sound, "SOUND#1");
        tag(meaning, "MEANING#1");
        long soundSequence = sequence(sound);
        long meaningSequence = sequence(meaning);

        processor.cancelLayer(LayerKind.MEANING);

        assertEquals("", tag(meaning));
        assertEquals(meaningSequence + 1, sequence(meaning));
        assertEquals("SOUND#1", tag(sound));
        assertEquals(soundSequence, sequence(sound));
    }

    @Test
    public void nullLayerStillRetiresBoth() throws Exception {
        LyricsSecondaryProcessor processor = processor();
        Object sound = lane(processor, "soundLane");
        Object meaning = lane(processor, "meaningLane");
        long soundSequence = sequence(sound);
        long meaningSequence = sequence(meaning);

        processor.cancelLayer(null);

        assertEquals(soundSequence + 1, sequence(sound));
        assertEquals(meaningSequence + 1, sequence(meaning));
    }
}
