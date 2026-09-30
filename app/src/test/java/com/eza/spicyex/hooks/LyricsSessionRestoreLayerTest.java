package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Test;

/** Restores through the session owner, then delivers the sibling's captured completion. */
public class LyricsSessionRestoreLayerTest {
    @Test public void restoringSoundStillAcceptsAndPublishesMeaningCompletion() throws Exception {
        assertSiblingCompletes(LayerKind.SOUND);
    }

    @Test public void restoringMeaningStillAcceptsAndPublishesSoundCompletion() throws Exception {
        assertSiblingCompletes(LayerKind.MEANING);
    }

    private void assertSiblingCompletes(LayerKind restored) throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        // No work is dispatched by restore; real lanes still perform cancellation.
        com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessor processor =
                new com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessor(context,
                        new okhttp3.OkHttpClient(), null, null, null, null, null, 1);
        com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession processing =
                new com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession(context,
                        com.eza.spicyex.SpotifyPlusConfig.from(context), processor, 1, "test");
        LyricsSessionManager manager = new LyricsSessionManager(context, null, processing,
                null, () -> 0L);
        LyricsDocument input = new LyricsDocument();
        input.trackId = "restore-sibling";
        input.language = "ko";
        LyricsLine row = new LyricsLine();
        row.text = "안녕하세요";
        input.lines.add(row);
        CanonicalBase base = CanonicalBase.fromDocument(input.trackId, input);
        LayerKind sibling = restored == LayerKind.SOUND ? LayerKind.MEANING : LayerKind.SOUND;
        LyricSession session = LyricSession.of(base, 0, 1);
        session = session.withLayer(restored, session.layer(restored).withArtifact(
                LayerStatus.READY, artifact(base, restored), ""));
        session = session.withLayer(sibling, session.layer(sibling).processing(
                LayerAuthority.AI, "test-config", "", "sibling-run"));
        set(manager, "document", input);
        set(manager, "canonicalSource", LyricsDocument.copyOf(input));
        set(manager, "session", session);

        manager.restoreLayer(restored);
        assertSame("restoration must keep the sibling's captured processing input",
                input, get(manager, "document"));
        assertEquals(LayerStatus.PROCESSING, ((LyricSession) get(manager, "session")).layer(sibling).status);

        // This is the actual completion boundary called by the shared processing callback.
        Method complete = LyricsSessionManager.class.getDeclaredMethod("adoptLayerArtifact",
                LayerKind.class, DerivedLayerArtifact.class, LayerFailure.class,
                LyricsDocument.class, int.class);
        complete.setAccessible(true);
        complete.invoke(manager, sibling, artifact(base, sibling), LayerFailure.NONE, input, 0);
        LyricSession settled = (LyricSession) get(manager, "session");
        assertEquals(LayerStatus.READY, settled.layer(sibling).status);
        assertEquals(LayerStatus.ABSENT, settled.layer(restored).status);
        assertFalse(input.processingPending);

        Method project = LyricsSessionManager.class.getDeclaredMethod("publishedProjection", LyricsDocument.class);
        project.setAccessible(true);
        LyricsDocument published = (LyricsDocument) project.invoke(manager, input);
        assertEquals(sibling == LayerKind.MEANING ? "Hello" : "", published.lines.get(0).translatedText);
        assertEquals(sibling == LayerKind.SOUND ? "annyeong" : "", published.lines.get(0).romanizedText);
    }

    private DerivedLayerArtifact artifact(CanonicalBase base, LayerKind layer) {
        LayerProvenance provenance = new LayerProvenance(LayerAuthority.AI, "test", "test-config", 0L);
        String rowId = base.rowAt(0).rowId;
        return layer == LayerKind.MEANING
                ? new MeaningArtifact(base.digest, "test-config", provenance,
                        Collections.singletonList(new MeaningEntry(rowId, "Hello", "en")), false)
                : new SoundArtifact(base.digest, "test-config", provenance,
                        Collections.singletonList(SoundEntry.line(rowId, "annyeong", "latin")), false);
    }

    private Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
