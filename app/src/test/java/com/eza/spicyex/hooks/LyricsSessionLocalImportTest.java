package com.eza.spicyex.hooks;

import android.content.ContentResolver;
import android.net.Uri;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LyricsSessionLocalImportTest {
    private static final String URI = "spotify:track:track1";
    private static final Uri FILE = Uri.parse("content://test/lyrics");

    @After public void resetResolver() { ContentResolver.testInputOpener = null; }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static LyricsSessionManager session() throws Exception {
        LyricsSessionManager manager = new LyricsSessionManager(new FakeAndroidContext(), new android.os.Handler(),
                null, null, () -> 0L);
        set(manager, "track", new SpotifyTrack("Song", "Artist", "", URI,
                0, "", 0, "", 10000, false));
        ((LyricsSessionPolicy) get(manager, "policy")).adoptTrack(URI);
        return manager;
    }

    @Test public void filePickerResultForAnotherSongNeverOpensFile() throws Exception {
        LyricsSessionManager manager = session();
        AtomicBoolean opened = new AtomicBoolean();
        ContentResolver.testInputOpener = uri -> { opened.set(true); return null; };
        AtomicBoolean success = new AtomicBoolean(true);
        manager.importLocalLyrics("spotify:track:old", FILE, (ok, detail) -> success.set(ok));
        assertFalse(success.get());
        assertFalse(opened.get());
    }

    @Test public void trackChangeDuringReadCannotCommitOrReplaceDisplayedLyrics() throws Exception {
        LyricsSessionManager manager = session();
        LyricsDocument displayed = new LyricsDocument();
        set(manager, "document", displayed);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<String> result = new AtomicReference<>();
        ContentResolver.testInputOpener = uri -> new ByteArrayInputStream(
                "<tt><p begin=\"1s\" end=\"2s\">Hello</p></tt>".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                reading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Read not released");
                } catch (InterruptedException error) { throw new AssertionError(error); }
                return super.read(bytes, offset, count);
            }
            @Override public void close() { closed.set(true); }
        };
        try {
            manager.importLocalLyrics(URI, FILE, (ok, detail) -> {
                result.set(ok ? "unexpected success" : detail);
                finished.countDown();
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            AtomicBoolean overlapping = new AtomicBoolean(true);
            manager.importLocalLyrics(URI, FILE, (ok, detail) -> overlapping.set(ok));
            assertFalse(overlapping.get());
            LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
            policy.adoptTrack("spotify:track:other");
            policy.adoptTrack(URI); // A → B → A must also invalidate the original read.
        } finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals("Track changed", result.get());
        assertTrue(closed.get());
        assertSame(displayed, get(manager, "document"));
        assertFalse((Boolean) get(manager, "importingLocalLyrics"));
    }
}
