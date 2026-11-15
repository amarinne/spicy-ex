package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoDuetMountRegressionTest {
    @Test public void companionNeedsTheToggleTheSurfaceAndALyricRow() {
        Bundle on = packet(true);
        assertEquals("duet line", AutoLyricsView.duetCompanion(on, false, "player-full").text);
        assertEquals("duet line", AutoLyricsView.duetCompanion(on, false, "player").text);
        assertNull("weather stays single-line", AutoLyricsView.duetCompanion(on, false, "weather"));
        assertNull("toggle off", AutoLyricsView.duetCompanion(packet(false), false, "player-full"));
        assertNull("stress packet", AutoLyricsView.duetCompanion(on, true, "player-full"));

        Bundle note = packet(true);
        note.getBundle("duet").putString("presentation", "note");
        assertNull("a music note never joins", AutoLyricsView.duetCompanion(note, false, "player-full"));

        Bundle blank = packet(true);
        blank.getBundle("duet").putString("line", "   ");
        assertNull("a blank companion never joins", AutoLyricsView.duetCompanion(blank, false, "player-full"));
    }

    @Test public void companionChangesTheFitIdentity() {
        Bundle solo = packet(false);
        Bundle duet = packet(true);
        solo.putBoolean(Settings.AUTO_DUET.key, true);
        assertNotEquals(AutoLyricsView.sampleKey(solo), AutoLyricsView.sampleKey(duet));
    }

    private static Bundle packet(boolean withDuet) {
        Bundle value = new Bundle();
        value.putInt("generation", 7);
        value.putString("trackUri", "spotify:track:test");
        value.putString("line", "primary line"); value.putString("presentation", "lyric");
        value.putInt("lineIndex", 2);
        value.putLong("lineStartMs", 2000); value.putLong("lineEndMs", 4000);
        value.putBoolean(Settings.AUTO_DUET.key, withDuet);
        if (withDuet) value.putBundle("duet", companion());
        return value;
    }

    private static Bundle companion() {
        Bundle duet = new Bundle();
        duet.putString("line", "duet line"); duet.putString("presentation", "lyric");
        duet.putInt("lineIndex", 3);
        duet.putLong("lineStartMs", 2500); duet.putLong("lineEndMs", 4500);
        return duet;
    }
}
