package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoPrefetchMetadataRegressionTest {
    @Test public void rowSanitationCannotCreateSessionDefaults() {
        Bundle row = AutoPrototypeProvider.sanitizeRow(next());
        assertEquals("next lyric", row.getString("line"));
        assertFalse(row.containsKey("creditWriters"));
        assertFalse(row.containsKey("documentResolved"));
        assertFalse(row.containsKey(Settings.AUTO_SECONDARY_TEXT.key));
    }

    @Test public void localAdvanceAndOrdinaryPacketHaveIdenticalMetadataAndRejectionKey() {
        Bundle original = session();
        Bundle next = AutoPrototypeProvider.sanitizeRow(next());
        Bundle selected = AutoLyricsView.selectNext(original, next);
        Bundle ordinary = session(); ordinary.putAll(next);
        assertEquals("Writer", selected.getString("creditWriters"));
        assertTrue(selected.getBoolean("documentResolved"));
        assertEquals("Both", selected.getString(Settings.AUTO_SECONDARY_TEXT.key));
        assertEquals(AutoLyricsView.sampleKey(ordinary), AutoLyricsView.sampleKey(selected));
        assertEquals(AutoResponseCredit.text(ordinary.getString("creditWriters"), null, " · ").toString(),
                AutoResponseCredit.text(selected.getString("creditWriters"), null, " · ").toString());
    }

    @Test public void legacyFullDefaultNextCannotOverwriteSessionState() {
        Bundle next = next(); next.putString("creditWriters", "");
        next.putString("trackUri", ""); next.putInt("generation", -1);
        next.putBoolean("documentResolved", false);
        Bundle selected = AutoLyricsView.selectNext(session(), next);
        assertEquals("Writer", selected.getString("creditWriters"));
        assertEquals("spotify:track:test", selected.getString("trackUri"));
        assertEquals(7, selected.getInt("generation"));
        assertTrue(selected.getBoolean("documentResolved"));
    }

    private static Bundle session() {
        Bundle value = new Bundle(); value.putString("creditWriters", "Writer");
        value.putString("trackUri", "spotify:track:test"); value.putInt("generation", 7);
        value.putBoolean("documentResolved", true);
        value.putString(Settings.AUTO_SECONDARY_TEXT.key, "Both");
        return value;
    }
    private static Bundle next() {
        Bundle value = new Bundle(); value.putString("line", "next lyric");
        value.putString("presentation", "lyric"); value.putInt("lineIndex", 2);
        value.putLong("lineStartMs", 2000); value.putLong("lineEndMs", 4000);
        value.putBoolean("lineLevelSync", true); return value;
    }
}
