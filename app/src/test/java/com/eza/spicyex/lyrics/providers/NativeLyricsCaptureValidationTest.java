package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeLyricsCaptureValidationTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:abc123", 0, "", 0, null, 180000, false);
    static final class LayoutSlot {
        final String name;
        final long value = 0;
        LayoutSlot(String name) { this.name = name; }
    }
    static final class LayoutOwner {
        final List<LayoutSlot> slots = Arrays.asList(
                new LayoutSlot("now_playing_view_attachments_container,now_playing_view_container"),
                new LayoutSlot("ime_window_insets_space,fragment_container_bottom_overlap_touch_event_consumer"));
    }
    static final class NamedLine { final String text = "A genuine unsynced line"; }
    static final class FrameworkArgument extends File {
        final List<a> lines = Arrays.asList(new a("Unrelated first row", 0), new a("Unrelated second row", 1));
        FrameworkArgument() { super("unrelated"); }
    }
    static final class Message { final List<?> lines; Message(List<?> lines) { this.lines = lines; } }
    static final class OfflineEntity {
        final String a = "spotify:track:other123";
        final String b = "[{\"words\":\"A stored unsynced line\"}]";
    }
    static final class a {
        final String a;
        final long b;
        a(String words, long start) { a = words; b = start; }
    }
    private static LyricsDocument capture(Object owner, Object... args) {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicReference<LyricsDocument> result = new AtomicReference<>();
        source.addNativeListener((id, doc) -> result.set(doc));
        source.captureCandidate(TRACK, owner, args, "test:validation");
        return result.get();
    }
    @Test public void rejectsScreenshotLayoutIdentifiersFromStructuralList() {
        assertNull(capture(new LayoutOwner()));
    }
    @Test public void rejectsScreenshotLayoutIdentifiersFromConstructorList() {
        assertNull(capture(new Object(), new Object(), new LayoutOwner().slots));
    }
    @Test public void frameworkOwnerCannotBorrowConstructorLyrics() {
        assertNull(capture(new File("unrelated"), "unused", Arrays.asList(new NamedLine())));
    }
    @Test public void frameworkObjectsDoNotQualifyAsLineObjects() throws Exception {
        Method method = NativeLyricsSource.class.getDeclaredMethod("looksLikeLineObject", Object.class, boolean.class);
        method.setAccessible(true);
        assertEquals(false, method.invoke(null, new File("unrelated"), true));
    }
    @Test public void frameworkConstructorArgumentCannotLendStructuralLines() {
        assertNull(capture(new Object(), new FrameworkArgument()));
    }
    @Test public void structuralArgumentsStillRequireTimedLineShape() {
        assertNull(capture(new Object(), new Message(Arrays.asList(new NamedLine(), new NamedLine()))));
        assertNotNull(capture(new Object(), new Message(Arrays.asList(new a("First sung line", 1000),
                new a("Next sung line", 4000)))));
    }
    @Test public void constructorListMustContainLineObjects() {
        assertNull(capture(new Object(), "unused", Arrays.asList(new LayoutSlot("not lyrics"), new Object())));
    }
    @Test public void keepsNamedUnsyncedLineWithoutTiming() {
        LyricsDocument doc = capture(new Message(Arrays.asList(new NamedLine())));
        assertNotNull(doc);
        assertEquals("Static", doc.type);
        assertEquals("A genuine unsynced line", doc.lines.get(0).text);
    }
    @Test public void keepsObfuscatedTimedLineFields() {
        LyricsDocument doc = capture(new Message(Arrays.asList(new a("First sung line", 1000), new a("Next sung line", 4000))));
        assertNotNull(doc);
        assertEquals("Line", doc.type);
        assertEquals(2, doc.lines.size());
        assertEquals("First sung line", doc.lines.get(0).text);
        assertEquals(1000, doc.lines.get(0).startMs);
    }
    @Test public void keepsValidConstructorList() {
        assertNotNull(capture(new Object(), "unused", Arrays.asList(new NamedLine())));
    }
    @Test public void ignoresNullEntriesWithoutDiscardingRealLyrics() {
        assertNotNull(capture(new Message(Arrays.asList(null, new NamedLine()))));
        assertNull(capture(new Message(Arrays.asList(null, null))));
    }
    @Test public void keepsObfuscatedOfflineJsonAndItsTrackIdentity() {
        LyricsDocument doc = capture(new OfflineEntity());
        assertNotNull(doc);
        assertEquals("other123", doc.trackId);
        assertEquals("A stored unsynced line", doc.lines.get(0).text);
    }
    @Test public void colorLyricsFallbackCannotPublishLayoutText() {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicReference<LyricsDocument> result = new AtomicReference<>();
        source.addNativeListener((id, doc) -> result.set(doc));
        source.captureColorLyricsResponse("abc123", "{\"lines\":[{\"words\":\"now_playing_view_container,ime_window_insets_space\"}]}");
        assertNull(result.get());
    }
    @Test public void emptyResponseDoesNotPublishLyrics() { assertNull(capture(new Object())); }
}
