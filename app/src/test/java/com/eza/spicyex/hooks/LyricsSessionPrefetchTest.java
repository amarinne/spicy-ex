package com.eza.spicyex.hooks;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.LyricsDetectionSession;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;

import org.junit.Test;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LyricsSessionPrefetchTest {
    private static final String URI = "spotify:track:1234567890123456789012";

    @Test public void joinsInFlightMeaningAndPublishesItsCompletion() throws Exception {
        Fixture fixture = new Fixture("歌詞", "歌詞");
        assertEquals(EnumSet.of(LayerKind.MEANING), invoke(fixture.live, "joinUpcoming"));
        assertEquals(LayerStatus.PROCESSING, fixture.liveSession().meaning.status);
        assertTrue(fixture.liveDocument.romanizationPending);

        LyricSession pending = (LyricSession) field("session").get(fixture.background);
        LayerProvenance provenance = new LayerProvenance(LayerAuthority.AI, "fixture", "meaning", 0L);
        MeaningArtifact result = new MeaningArtifact(pending.base.digest, "meaning", provenance,
                Collections.singletonList(new MeaningEntry(pending.base.rowAt(0).rowId, "Lyrics", "en")),
                false);
        field("session").set(fixture.background, pending.withLayer(LayerKind.MEANING,
                pending.meaning.withArtifact(LayerStatus.READY, result, "")));
        invoke(fixture.live, "copyUpcomingUpdates", LyricsSessionManager.class, fixture.background);

        assertEquals(LayerStatus.READY, fixture.liveSession().meaning.status);
        LyricsDocument published = (LyricsDocument) invoke(fixture.live, "publishedProjection",
                LyricsDocument.class, fixture.liveDocument);
        assertEquals("Lyrics", published.lines.get(0).translatedText);
        assertNull(field("upcoming").get(fixture.live));
    }

    @Test public void rejectsAChangedSourceOrSettings() throws Exception {
        Fixture changedSource = new Fixture("歌詞", "別の歌詞");
        assertEquals(EnumSet.noneOf(LayerKind.class), invoke(changedSource.live, "joinUpcoming"));
        assertEquals(LayerStatus.ABSENT, changedSource.liveSession().meaning.status);

        Fixture changedSettings = new Fixture("歌詞", "歌詞");
        field("preparedInputs").set(changedSettings.background, "previous-settings");
        assertEquals(EnumSet.noneOf(LayerKind.class), invoke(changedSettings.live, "joinUpcoming"));
        assertNull(field("upcoming").get(changedSettings.live));
    }

    private static final class Fixture {
        final FakeAndroidContext context = new FakeAndroidContext();
        final LyricsSessionManager live = manager();
        final LyricsSessionManager background = manager();
        final LyricsDocument liveDocument;

        Fixture(String liveText, String backgroundText) throws Exception {
            liveDocument = installSession(live, liveText, false);
            LyricsDocument backgroundDocument = installSession(background, backgroundText, true);
            field("preparedInputs").set(background, invoke(background, "preparationInputs",
                    LyricsDocument.class, backgroundDocument));
            field("upcoming").set(live, background);
        }

        private LyricsSessionManager manager() {
            LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(context, new OkHttpClient(),
                    null, null, null, null, null, 1);
            LyricsSecondaryProcessingSession processing = new LyricsSecondaryProcessingSession(
                    context, SpotifyPlusConfig.from(context), processor, 1, "test");
            return new LyricsSessionManager(context, null, processing,
                    new LyricsDetectionSession(context, Runnable::run), () -> 0L);
        }

        private LyricsDocument installSession(LyricsSessionManager owner, String text, boolean processing)
                throws Exception {
            LyricsSessionPolicy policy = (LyricsSessionPolicy) field("policy").get(owner);
            policy.adoptTrack(URI);
            LyricsDocument doc = new LyricsDocument();
            doc.trackId = URI;
            doc.language = "ja";
            doc.romanizationPending = true;
            LyricsLine line = new LyricsLine();
            line.text = text;
            doc.lines.add(line);
            LyricSession session = LyricSession.of(CanonicalBase.fromDocument(URI, doc),
                    policy.generation(), 1);
            if (processing) {
                session = session.withLayer(LayerKind.MEANING, session.meaning.processing(
                        LayerAuthority.AI, "meaning", "", "prefetched-run"));
            }
            field("document").set(owner, doc);
            field("canonicalSource").set(owner, LyricsDocument.copyOf(doc));
            field("session").set(owner, session);
            return doc;
        }

        LyricSession liveSession() throws Exception {
            return (LyricSession) field("session").get(live);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = LyricsSessionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(LyricsSessionManager owner, String name) throws Exception {
        Method method = LyricsSessionManager.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object invoke(LyricsSessionManager owner, String name, Class<?> type, Object argument)
            throws Exception {
        Method method = LyricsSessionManager.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(owner, argument);
    }
}
