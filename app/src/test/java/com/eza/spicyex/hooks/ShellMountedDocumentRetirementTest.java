package com.eza.spicyex.hooks;

import android.widget.LinearLayout;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLineViewState;
import com.eza.spicyex.lyrics.SpicyAnimatedTextView;
import com.eza.spicyex.lyrics.ai.AiRequestFeedbackState;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryRowUpdater;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The shell's per-line render state is static and a row's main view captures its AppliedLine, so
 * any document the shell drops with rows still registered keeps those views reachable. These
 * scenarios drive the shell's real lifecycle methods on an allocated shell with fake row views.
 */
public class ShellMountedDocumentRetirementTest {
    @Test public void sessionRetractionReleasesTheMountedRows() throws Exception {
        Fixture f = new Fixture();
        LyricsDocument mounted = f.mount(3);
        f.set("document", mounted);
        f.set("editorLiveSnapshot", mounted);

        f.call("retireSessionDocument", new Class<?>[]{LyricsSessionManager.Snapshot.class},
                new LyricsSessionManager.Snapshot(null, "spotify:track:next", 1, "loading",
                        true, 0L, 0L, 1.0));

        assertNull(f.get("document"));
        assertNull(f.get("editorLiveSnapshot"));
        assertReleased(mounted);
        assertEquals(mounted.appliedLines, f.invalidated);
    }

    @Test public void replacingTheDocumentReleasesOnlyTheOutgoingRows() throws Exception {
        Fixture f = new Fixture();
        LyricsDocument outgoing = f.mount(2);
        LyricsDocument incoming = f.mount(2);
        f.set("document", outgoing);
        f.set("editorLiveSnapshot", outgoing);

        f.replace(incoming, incoming);

        assertReleased(outgoing);
        assertMounted(incoming);
        assertSame(incoming, f.get("document"));
        assertSame(incoming, f.get("editorLiveSnapshot"));
    }

    @Test public void republishingTheMountedDocumentKeepsItsRows() throws Exception {
        Fixture f = new Fixture();
        LyricsDocument mounted = f.mount(3);
        f.set("document", mounted);
        f.set("editorLiveSnapshot", mounted);

        f.replace(mounted, mounted);

        assertMounted(mounted);
        assertTrue(f.invalidated.isEmpty());
    }

    @Test public void demoModeKeepsTheLiveSnapshotUntilItIsDropped() throws Exception {
        Fixture f = new Fixture();
        LyricsDocument real = f.mount(2);
        f.set("document", real);
        f.set("editorLiveSnapshot", real);

        // enableDemoMode(): the demo document mounts; the editor's Live preview keeps the real one.
        LyricsDocument demo = f.mount(2);
        f.replace(demo, real);
        assertMounted(real);

        // disableDemoMode(): both documents leave the shell.
        f.replace(null, null);
        assertReleased(demo);
        assertReleased(real);
    }

    @Test public void retirementReleasesRowsReplacedByAnInPlaceRebuild() throws Exception {
        Fixture f = new Fixture();
        LyricsDocument mounted = f.mount(2);
        List<AppliedLine> replaced = new ArrayList<>(mounted.appliedLines);
        // applySyncedRows() swaps the document's AppliedLine objects while the old rows are shown.
        f.replacedLines().addAll(replaced);
        mounted.appliedLines.clear();
        mounted.appliedLines.addAll(f.mount(2).appliedLines);
        f.set("document", mounted);
        f.set("editorLiveSnapshot", mounted);

        f.replace(null, null);

        for (AppliedLine line : replaced) assertReleased(line);
        assertReleased(mounted);
        assertTrue(f.replacedLines().isEmpty());
    }

    private static void assertReleased(LyricsDocument doc) {
        for (AppliedLine line : doc.appliedLines) assertReleased(line);
    }

    private static void assertReleased(AppliedLine line) {
        assertNull(LyricsLineViewState.rowView(line));
        assertNull(LyricsLineViewState.mainViewOf(line));
        assertTrue(LyricsLineViewState.secondaryViews(line).isEmpty());
        assertFalse(LyricsLineViewState.hasDotViews(line));
    }

    private static void assertMounted(LyricsDocument doc) {
        for (AppliedLine line : doc.appliedLines) {
            assertTrue(LyricsLineViewState.mainViewOf(line) instanceof FakeText);
            assertEquals(1, LyricsLineViewState.secondaryViews(line).size());
            assertEquals(1, LyricsLineViewState.dotViews(line).size());
        }
    }

    private static final class Fixture {
        final NativeSpicyShellViewImpl shell = allocate(NativeSpicyShellViewImpl.class);
        final FakeHost host = allocate(FakeHost.class);
        final List<AppliedLine> invalidated = new ArrayList<>();

        Fixture() throws Exception {
            set("secondaryRowUpdater", new LyricsSecondaryRowUpdater(host, invalidated::add));
            set("replacedLines", new ArrayList<AppliedLine>());
            set("documentGate", new LyricsSurfaceDocumentGate());
            set("soundAiFeedback", new AiRequestFeedbackState());
            set("meaningAiFeedback", new AiRequestFeedbackState());
            // The loading skeleton is already up, so beginLoadingTransition() keeps it.
            set("songChangeHadSkeleton", true);
        }

        /**
         * Builds a document whose text views are registered the way LyricsRowViewFactory registers
         * them. The row container is left out: Android's JVM stub cannot answer View#getParent(),
         * which the release path asks of a row before it detaches it from the host.
         */
        LyricsDocument mount(int rows) {
            LyricsDocument doc = new LyricsDocument();
            for (int i = 0; i < rows; i++) {
                AppliedLine line = new AppliedLine();
                line.text = "line " + i;
                LyricsLineViewState.setMainView(line, allocate(FakeText.class));
                LyricsLineViewState.setTranslationView(line, allocate(FakeText.class));
                LyricsLineViewState.beginDotViews(line);
                LyricsLineViewState.addDotView(line, allocate(FakeText.class));
                doc.appliedLines.add(line);
            }
            return doc;
        }

        void replace(LyricsDocument next, LyricsDocument snapshot) throws Exception {
            call("replaceMountedDocument", new Class<?>[]{LyricsDocument.class, LyricsDocument.class},
                    next, snapshot);
        }

        @SuppressWarnings("unchecked")
        List<AppliedLine> replacedLines() throws Exception {
            return (List<AppliedLine>) get("replacedLines");
        }

        void call(String name, Class<?>[] types, Object... args) throws Exception {
            Method method = NativeSpicyShellViewImpl.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(shell, args);
        }

        void set(String name, Object value) throws Exception {
            field(name).set(shell, value);
        }

        Object get(String name) throws Exception {
            return field(name).get(shell);
        }

        private static Field field(String name) throws Exception {
            Field field = NativeSpicyShellViewImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    public static class FakeHost extends LinearLayout {
        public FakeHost() {
            super(null);
        }
    }

    public static class FakeText extends SpicyAnimatedTextView {
        public FakeText() {
            super(null);
        }

        @Override public void setPivotX(float pivotX) {
        }

        @Override public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {
        }

        /** JUnit names a retained view in its failure message; the JVM stub cannot. */
        @Override public String toString() {
            return "FakeText";
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            // Avoid Android constructors without changing the platform mock or production constructors.
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field field = unsafe.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
