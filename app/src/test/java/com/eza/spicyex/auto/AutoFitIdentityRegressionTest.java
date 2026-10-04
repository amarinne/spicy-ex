package com.eza.spicyex.auto;

import android.os.Bundle;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoFitIdentityRegressionTest {
    @Test public void correctedWordTimingRetriesRejectedSurface() {
        Bundle original = packet();
        Bundle corrected = packet();
        corrected.<Bundle>getParcelableArrayList("words").get(0).putLong("endMs", 3500);
        assertRetries(original, corrected);
    }

    @Test public void changedWordGroupingRetriesRejectedSurface() {
        Bundle original = packet();
        Bundle corrected = packet();
        corrected.<Bundle>getParcelableArrayList("words").get(0).putBoolean("boundaryAfter", true);
        assertRetries(original, corrected);
    }

    @Test public void lineLevelFallbackRetriesRejectedSurface() {
        Bundle original = packet();
        Bundle corrected = packet(); corrected.putBoolean("lineLevelSync", true);
        assertRetries(original, corrected);
    }

    @Test public void changedLayoutGroupsRetryRejectedSurface() {
        Bundle original = packet();
        Bundle corrected = packet(); corrected.putIntArray("layoutGroups", new int[]{0, 2, 2, 4});
        assertRetries(original, corrected);
    }

    @Test public void predictiveAndOrdinaryRowsHaveIdenticalFitIdentity() {
        Bundle future = AutoPrototypeProvider.sanitizeRow(packet());
        Bundle predictive = packet();
        predictive.putString("line", "previous");
        predictive.putLong("positionMs", 3000); predictive.putLong("nextAtMs", 2000);
        predictive.putBundle("next", future);
        Bundle ordinary = packet(); ordinary.putAll(future);
        assertEquals(AutoLyricsView.sampleKey(ordinary), AutoLyricsView.sampleKey(predictive));
        assertEquals(AutoLyricsView.sampleKey(ordinary),
                AutoLyricsView.sampleKey(AutoLyricsView.selectNext(predictive, future)));
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("weather", AutoLyricsView.sampleKey(predictive));
        assertFalse(surfaces.allows("weather", AutoLyricsView.sampleKey(ordinary)));
    }

    private static void assertRetries(Bundle original, Bundle corrected) {
        AutoSurfaceReadiness surfaces = new AutoSurfaceReadiness();
        surfaces.reject("weather", AutoLyricsView.sampleKey(original));
        assertFalse(surfaces.allows("weather", AutoLyricsView.sampleKey(packet())));
        assertTrue("A changed render representation must retry fitting",
                surfaces.allows("weather", AutoLyricsView.sampleKey(corrected)));
    }

    private static Bundle packet() {
        Bundle value = new Bundle(); value.putInt("generation", 7);
        value.putString("trackUri", "spotify:track:test");
        value.putString("line", "abcd"); value.putString("presentation", "lyric");
        value.putInt("lineIndex", 2); value.putLong("lineStartMs", 2000); value.putLong("lineEndMs", 4000);
        value.putIntArray("layoutGroups", new int[]{0, 4});
        ArrayList<Bundle> words = new ArrayList<>();
        Bundle word = new Bundle(); word.putString("text", "abcd");
        word.putInt("sourceStart", 0); word.putInt("sourceEnd", 4);
        word.putLong("startMs", 2000); word.putLong("endMs", 4000);
        words.add(word); value.putParcelableArrayList("words", words);
        return value;
    }
}
