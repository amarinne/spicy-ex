package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class FullscreenControlsCodecTest {
    private final ValueCodec codec = ControlRegistry.FULLSCREEN_CONTROLS_CODEC;

    @Test public void everyNumericStopAndTheAlwaysStopRoundTrip() {
        for (int stop = 1; stop <= 31; stop++) {
            String expected = stop == 31 ? "Always" : stop == 1 ? "1 second" : stop + " seconds";
            assertEquals(expected, codec.encode(stop));
            assertEquals(stop, codec.decode(expected));
            assertEquals(stop, codec.decode(codec.encode(stop)));
        }
        ControlSpec spec = ControlRegistry.editorControls().stream()
                .filter(row -> row.setting == Settings.FULLSCREEN_CONTROLS).findFirst().get();
        assertEquals(ControlSpec.Kind.SLIDER, spec.kind);
        assertSame(codec, spec.slider.codec);
        assertEquals(1, spec.slider.min);
        assertEquals(30, spec.slider.max);
        assertEquals(1, spec.slider.step);
        assertEquals(Integer.valueOf(31), spec.slider.endValue);
        assertEquals(Integer.valueOf(31), spec.slider.defaultMarker);
    }

    @Test public void everyShippedLegacyLabelNormalizesAndRoundTrips() {
        String[] legacy = {"5 seconds", "10 seconds", "30 seconds", "Always on"};
        int[] stops = {5, 10, 30, 31};
        String[] canonical = {"5 seconds", "10 seconds", "30 seconds", "Always"};
        for (int i = 0; i < legacy.length; i++) {
            assertEquals(legacy[i], stops[i], codec.decode(legacy[i]));
            assertEquals(canonical[i], codec.encode(codec.decode(legacy[i])));
            assertEquals(stops[i], codec.decode(codec.encode(codec.decode(legacy[i]))));
        }
    }

    @Test public void normalizerFallbacksStayTheSame() {
        assertEquals(31, codec.decode(null));
        assertEquals(31, codec.decode("invalid"));
        assertEquals(31, codec.decode("always ON"));
        assertEquals(1, codec.decode("0 seconds"));
        assertEquals(30, codec.decode("99 seconds"));
        assertEquals("Always", codec.encode(0));
        assertEquals("Always", codec.encode(32));
    }
}
