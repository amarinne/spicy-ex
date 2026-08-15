package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/** Phase 0 contracts: canonical identity, independent layers, and stale-result guards. */
public class LyricSessionContractTest {

    private static final String SOUND_CONFIG = LayerConfigIds.sound(true, "cn=pinyin|kr=rr", "ja", 3);
    private static final String SOUND_CONFIG_KOREAN = LayerConfigIds.sound(true, "cn=pinyin|kr=mr", "ja", 3);
    private static final String MEANING_CONFIG = LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto");
    private static final String MEANING_CONFIG_ES = LayerConfigIds.meaning(true, "google_unofficial", "es", "auto", "auto");

    // --- canonical identity -------------------------------------------------

    @Test
    public void canonicalDigestCoversTextAndTimingOnly() {
        LyricsDocument plain = document("ichi", "ni");
        LyricsDocument derived = document("ichi", "ni");
        derived.lines.get(0).romanizedText = "ICHI";
        derived.lines.get(0).translatedText = "one";
        derived.lines.get(1).translatedText = "two";

        assertEquals(CanonicalBase.fromDocument("spotify:track:a", plain).digest,
                CanonicalBase.fromDocument("spotify:track:a", derived).digest);
    }

    @Test
    public void canonicalDigestChangesWhenSourceTextOrTimingChanges() {
        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni"));
        CanonicalBase otherText = CanonicalBase.fromDocument("spotify:track:a", document("ichi", "san"));
        LyricsDocument retimed = document("ichi", "ni");
        retimed.lines.get(1).startMs = 9999L;

        assertNotEquals(base.digest, otherText.digest);
        assertNotEquals(base.digest,
                CanonicalBase.fromDocument("spotify:track:a", retimed).digest);
    }

    @Test
    public void rowIdsAreStableAcrossIdenticalReparseAndDistinctPerRow() {
        CanonicalBase first = CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni"));
        CanonicalBase second = CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni"));

        assertEquals(first.rows.get(0).rowId, second.rows.get(0).rowId);
        assertNotEquals(first.rows.get(0).rowId, first.rows.get(1).rowId);
        assertEquals(1, first.indexOfRow(first.rows.get(1).rowId));
        assertEquals(-1, first.indexOfRow("r0#deadbeef"));
    }

    @Test
    public void canonicalBaseReadsProviderSpanIdsWhenPresent() {
        LyricsDocument doc = document("ichi ni");
        doc.lines.get(0).syllables.addAll(Arrays.asList(
                syllable("prov-1", "ichi", 0L, 500L), syllable("", "ni", 500L, 900L)));

        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:a", doc);

        assertEquals("prov-1", base.rows.get(0).spans.get(0).spanId);
        assertTrue(base.rows.get(0).spans.get(1).spanId.startsWith(base.rows.get(0).rowId + "/s1#"));
    }

    // --- composition --------------------------------------------------------

    @Test
    public void soundAndMeaningComposeIndependentlyOntoOneSession() {
        LyricSession session = session("ichi", "ni");
        String row0 = session.base.rows.get(0).rowId;
        String row1 = session.base.rows.get(1).rowId;

        session = session.withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")));
        assertTrue(session.sound.hasArtifact());
        assertFalse(session.meaning.hasArtifact());

        session = session.withMeaning(readyMeaning(session, new MeaningEntry(row1, "two", "en")));
        assertTrue(session.sound.hasArtifact());
        assertTrue(session.meaning.hasArtifact());
        assertEquals("ichi-r", ((SoundArtifact) session.sound.artifact).sound(row0).displayText);
        assertEquals("two", ((MeaningArtifact) session.meaning.artifact).meaning(row1).text);
        assertNull(((MeaningArtifact) session.meaning.artifact).meaning(row0));
    }

    @Test
    public void meaningConfigChangeDropsMeaningOnlyAndSoundConfigChangeDropsSoundOnly() {
        LyricSession session = session("ichi", "ni");
        String row0 = session.base.rows.get(0).rowId;
        session = session
                .withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")))
                .withMeaning(readyMeaning(session, new MeaningEntry(row0, "one", "en")));

        LyricSession afterTarget = session.withLayerConfigChanged(LayerKind.MEANING, MEANING_CONFIG_ES);
        assertTrue(afterTarget.sound.hasArtifact());
        assertFalse(afterTarget.meaning.hasArtifact());
        assertEquals(LayerStatus.ABSENT, afterTarget.meaning.status);

        LyricSession afterKorean = session.withLayerConfigChanged(LayerKind.SOUND, SOUND_CONFIG_KOREAN);
        assertFalse(afterKorean.sound.hasArtifact());
        assertTrue(afterKorean.meaning.hasArtifact());
    }

    @Test
    public void unchangedLayerConfigIsANoOp() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        session = session.withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")));

        assertSame(session, session.withLayerConfigChanged(LayerKind.SOUND, SOUND_CONFIG));
    }

    @Test
    public void layerConfigIdsDoNotShareInputs() {
        assertEquals(LayerConfigIds.sound(true, "cn=pinyin|kr=rr", "ja", 3),
                LayerConfigIds.sound(true, "cn=pinyin|kr=rr", "ja", 3));
        // Meaning inputs must not appear in a Sound key, and vice versa.
        assertFalse(SOUND_CONFIG.contains("en"));
        assertFalse(MEANING_CONFIG.contains("pinyin"));
        assertNotEquals(MEANING_CONFIG, MEANING_CONFIG_ES);
        assertNotEquals(SOUND_CONFIG, SOUND_CONFIG_KOREAN);
    }

    // --- restore / drop -----------------------------------------------------

    @Test
    public void failureKeepsThePreviouslyDisplayedArtifactUsable() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        LayerState ready = readyMeaning(session, new MeaningEntry(row0, "one", "en"));

        LayerState failed = ready.failed(LayerFailure.http(429));

        assertEquals(LayerStatus.CACHED, failed.status);
        assertEquals(LayerFailure.Reason.RATE_LIMITED, failed.failure.reason);
        assertEquals("one", ((MeaningArtifact) failed.artifact).meaning(row0).text);
        assertTrue(failed.hasArtifact());
        assertEquals("", failed.runId);
    }

    @Test
    public void failureWithNothingDisplayedIsAFailedLayer() {
        LayerState failed = LayerState.absent(LayerKind.MEANING)
                .processing(LayerAuthority.MACHINE, MEANING_CONFIG, "", "run-1")
                .failed(LayerFailure.of(LayerFailure.Reason.TIMEOUT));

        assertEquals(LayerStatus.FAILED, failed.status);
        assertNull(failed.artifact);
        assertTrue(failed.failure.isFailure());
    }

    @Test
    public void droppedLayerClearsArtifactAndRunIdentity() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        LayerState dropped = readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")).dropped();

        assertEquals(LayerStatus.ABSENT, dropped.status);
        assertNull(dropped.artifact);
        assertEquals("", dropped.runId);
        assertEquals("", dropped.artifactDigest);
    }

    // --- document / config incompatibility ----------------------------------

    @Test
    public void artifactFromAnotherDocumentDoesNotApply() {
        LyricSession session = session("ichi", "ni");
        CanonicalBase other = CanonicalBase.fromDocument("spotify:track:a", document("san", "shi"));
        SoundArtifact artifact = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line(session.base.rows.get(0).rowId, "ichi-r", "romaji")),
                false);

        assertTrue(artifact.appliesTo(session.base));
        assertFalse(artifact.appliesTo(other));
    }

    @Test
    public void artifactNamingAnUnknownRowDoesNotApply() {
        LyricSession session = session("ichi");
        SoundArtifact artifact = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line("r7#cafebabe", "ghost", "romaji")), false);

        assertFalse(artifact.appliesTo(session.base));
    }

    @Test
    public void artifactCompatibilityRequiresBothDigestAndConfig() {
        LyricSession session = session("ichi");
        SoundArtifact artifact = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line(session.base.rows.get(0).rowId, "x", "romaji")), false);

        assertTrue(artifact.isCompatibleWith(session.base.digest, SOUND_CONFIG));
        assertFalse(artifact.isCompatibleWith(session.base.digest, SOUND_CONFIG_KOREAN));
        assertFalse(artifact.isCompatibleWith("other-digest", SOUND_CONFIG));
    }

    // --- source replacement -------------------------------------------------

    @Test
    public void sourceReplacementBumpsRevisionAndInvalidatesIncompatibleArtifacts() {
        LyricSession session = session("ichi", "ni");
        String row0 = session.base.rows.get(0).rowId;
        session = session
                .withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")))
                .withMeaning(readyMeaning(session, new MeaningEntry(row0, "one", "en")));
        int revisionBefore = session.identity.sourceRevision;
        String digestBefore = session.identity.canonicalDigest;

        LyricSession replaced = session.withReplacedBase(
                CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni", "san")));

        assertEquals(revisionBefore + 1, replaced.identity.sourceRevision);
        assertNotEquals(digestBefore, replaced.identity.canonicalDigest);
        assertFalse(replaced.sound.hasArtifact());
        assertFalse(replaced.meaning.hasArtifact());
        assertEquals(3, replaced.base.rows.size());
    }

    @Test
    public void identicalSourceIsNotAReplacement() {
        LyricSession session = session("ichi", "ni");
        String row0 = session.base.rows.get(0).rowId;
        session = session.withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")));

        LyricSession same = session.withReplacedBase(
                CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni")));

        assertSame(session, same);
        assertTrue(same.sound.hasArtifact());
    }

    // --- stale-result guards ------------------------------------------------

    @Test
    public void trackChangeRejectsAResultFromThePreviousSession() {
        LyricSession first = session("ichi");
        LayerRunIdentity run = LayerRunIdentity.forSession(first, LayerKind.SOUND, SOUND_CONFIG, "", "run-1");
        first = first.withSound(first.sound.processing(LayerAuthority.DETERMINISTIC, SOUND_CONFIG, "", "run-1"));
        assertTrue(first.acceptsResult(run));

        LyricSession next = LyricSession.of(
                CanonicalBase.fromDocument("spotify:track:b", document("hana")), first.identity.generation + 1);
        next = next.withSound(next.sound.processing(LayerAuthority.DETERMINISTIC, SOUND_CONFIG, "", "run-1"));

        assertFalse(next.acceptsResult(run));
    }

    @Test
    public void lateResultFromAPreviousRunConfigOrRevisionIsRejected() {
        LyricSession session = session("ichi");
        session = session.withMeaning(
                session.meaning.processing(LayerAuthority.MACHINE, MEANING_CONFIG, "cred-1", "run-2"));
        SessionIdentity id = session.identity;

        LayerRunIdentity current = new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.MEANING, MEANING_CONFIG, "cred-1", "run-2");
        assertTrue(session.acceptsResult(current));

        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.MEANING, MEANING_CONFIG, "cred-1", "run-1")));
        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.MEANING, MEANING_CONFIG_ES, "cred-1", "run-2")));
        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision + 1,
                id.canonicalDigest, LayerKind.MEANING, MEANING_CONFIG, "cred-1", "run-2")));
        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                "stale-digest", LayerKind.MEANING, MEANING_CONFIG, "cred-1", "run-2")));
        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.MEANING, MEANING_CONFIG, "cred-2", "run-2")));
    }

    @Test
    public void aRunOnOneLayerNeverSatisfiesTheOther() {
        LyricSession session = session("ichi");
        session = session
                .withSound(session.sound.processing(LayerAuthority.DETERMINISTIC, SOUND_CONFIG, "", "run-x"))
                .withMeaning(session.meaning.processing(LayerAuthority.MACHINE, MEANING_CONFIG, "", "run-x"));
        SessionIdentity id = session.identity;

        LayerRunIdentity soundRun = new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.SOUND, SOUND_CONFIG, "", "run-x");

        assertTrue(session.acceptsResult(soundRun));
        assertFalse(session.acceptsResult(new LayerRunIdentity(id.trackUri, id.generation, id.sourceRevision,
                id.canonicalDigest, LayerKind.MEANING, SOUND_CONFIG, "", "run-x")));
    }

    @Test
    public void artifactCacheKeyCarriesOnlyDigestAndLayerConfig() {
        LyricSession session = session("ichi");
        LayerRunIdentity sound = LayerRunIdentity.forSession(session, LayerKind.SOUND, SOUND_CONFIG, "", "run-1");
        LayerRunIdentity soundOtherRun =
                LayerRunIdentity.forSession(session, LayerKind.SOUND, SOUND_CONFIG, "", "run-2");
        LayerRunIdentity meaning =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-1");

        assertEquals(sound.artifactCacheKey(), soundOtherRun.artifactCacheKey());
        assertNotEquals(sound.artifactCacheKey(), meaning.artifactCacheKey());
        assertFalse(sound.artifactCacheKey().contains("run-1"));
    }

    // --- duplicate request coalescing --------------------------------------

    @Test
    public void identicalWorkFromDifferentSurfacesCoalescesToOneRun() {
        LyricSession session = session("ichi");
        LayerRunCoalescer coalescer = new LayerRunCoalescer();
        LayerRunIdentity fullscreen =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-fullscreen");
        LayerRunIdentity nowPlaying =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-nowplaying");

        assertEquals("run-fullscreen", coalescer.beginOrJoin(fullscreen));
        assertEquals("run-fullscreen", coalescer.beginOrJoin(nowPlaying));
        assertTrue(coalescer.isOwner(fullscreen));
        assertFalse(coalescer.isOwner(nowPlaying));
        assertEquals(1, coalescer.inFlightCount());

        coalescer.finish(nowPlaying);
        assertEquals(1, coalescer.inFlightCount());
        coalescer.finish(fullscreen);
        assertEquals(0, coalescer.inFlightCount());
    }

    @Test
    public void aDeferredSurfaceRunsOnlyAfterTheOwnerFinishes() {
        LyricSession session = session("ichi");
        LayerRunCoalescer coalescer = new LayerRunCoalescer();
        LayerRunIdentity owner =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-owner");
        LayerRunIdentity second =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-second");
        List<String> ran = new ArrayList<>();

        assertTrue(coalescer.beginOrDefer(owner, () -> ran.add("owner-continuation")));
        assertFalse(coalescer.beginOrDefer(second, () -> ran.add("second")));
        assertTrue(ran.isEmpty());

        coalescer.finish(owner);

        assertEquals(Collections.singletonList("second"), ran);
        assertEquals(0, coalescer.inFlightCount());
        // The key is free again, so the deferred caller can claim it for a cache-only pass.
        assertTrue(coalescer.beginOrDefer(second, null));
    }

    @Test
    public void finishFromANonOwnerNeitherReleasesTheKeyNorDrainsTheQueue() {
        LyricSession session = session("ichi");
        LayerRunCoalescer coalescer = new LayerRunCoalescer();
        LayerRunIdentity owner =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-owner");
        LayerRunIdentity second =
                LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "run-second");
        List<String> ran = new ArrayList<>();
        coalescer.beginOrDefer(owner, null);
        coalescer.beginOrDefer(second, () -> ran.add("second"));

        coalescer.finish(second);

        assertTrue(ran.isEmpty());
        assertEquals(1, coalescer.inFlightCount());
    }

    @Test
    public void differentLayersAndConfigsAreDifferentWork() {
        LyricSession session = session("ichi");
        LayerRunCoalescer coalescer = new LayerRunCoalescer();
        coalescer.beginOrJoin(LayerRunIdentity.forSession(session, LayerKind.SOUND, SOUND_CONFIG, "", "s"));
        coalescer.beginOrJoin(LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG, "", "m"));
        coalescer.beginOrJoin(LayerRunIdentity.forSession(session, LayerKind.MEANING, MEANING_CONFIG_ES, "", "m2"));

        assertEquals(3, coalescer.inFlightCount());
    }

    // --- separate surface subscriptions -------------------------------------

    @Test
    public void everySurfaceReceivesTheSameEventsFromOneSession() {
        SessionEventBus bus = new SessionEventBus();
        List<SessionEvent> fullscreen = new ArrayList<>();
        List<SessionEvent> nowPlaying = new ArrayList<>();
        List<SessionEvent> hyperGlow = new ArrayList<>();
        bus.subscribe(fullscreen::add);
        bus.subscribe(nowPlaying::add);
        bus.subscribe(hyperGlow::add);

        LyricSession session = session("ichi");
        bus.publish(SessionEvent.baseChanged(session));
        bus.publish(SessionEvent.layerChanged(session, LayerKind.SOUND,
                Collections.singleton(session.base.rows.get(0).rowId)));

        assertEquals(2, fullscreen.size());
        assertEquals(2, nowPlaying.size());
        assertEquals(2, hyperGlow.size());
        assertEquals(SessionEvent.Kind.SOUND_CHANGED, hyperGlow.get(1).kind);
        assertTrue(hyperGlow.get(1).isRowScoped());
    }

    @Test
    public void aLateSurfaceReplaysTheCurrentStateAndUnsubscribeStopsDelivery() {
        SessionEventBus bus = new SessionEventBus();
        LyricSession session = session("ichi");
        bus.publish(SessionEvent.baseChanged(session));

        List<SessionEvent> late = new ArrayList<>();
        SessionEventBus.Subscription subscription = bus.subscribe(late::add);
        assertEquals(1, late.size());

        subscription.close();
        bus.publish(SessionEvent.stateChanged(session));
        assertEquals(1, late.size());
        assertEquals(0, bus.subscriberCount());
    }

    // --- compatibility projection ------------------------------------------

    @Test
    public void projectionFillsLegacyFieldsWithoutMutatingTheCanonicalDocument() {
        LyricsDocument canonical = document("ichi", "ni");
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", canonical), 1);
        String row0 = session.base.rows.get(0).rowId;
        String row1 = session.base.rows.get(1).rowId;
        session = session
                .withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")))
                .withMeaning(readyMeaning(session, new MeaningEntry(row1, "two", "en")));

        LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session);

        assertEquals("ichi-r", projected.lines.get(0).romanizedText);
        assertEquals("two", projected.lines.get(1).translatedText);
        assertTrue(projected.includesRomanization);
        assertTrue(projected.includesTranslation);
        assertFalse(projected.processingPending);
        // canonical document untouched
        assertEquals("", canonical.lines.get(0).romanizedText);
        assertEquals("", canonical.lines.get(1).translatedText);
        assertFalse(canonical.includesRomanization);
        assertFalse(canonical.includesTranslation);
    }

    @Test
    public void projectionSkipsAnArtifactThatDoesNotFullyApply() {
        LyricsDocument canonical = document("ichi", "ni");
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", canonical), 1);
        SoundArtifact ghost = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Arrays.asList(SoundEntry.line(session.base.rows.get(0).rowId, "ichi-r", "romaji"),
                        SoundEntry.line("r9#00000000", "ghost", "romaji")), false);
        session = session.withSound(session.sound.withArtifact(LayerStatus.READY, ghost, "run-1"));

        LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session);

        assertEquals("", projected.lines.get(0).romanizedText);
    }

    @Test
    public void projectionReportsPerLayerPendingIndependently() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        session = session
                .withSound(readySound(session, SoundEntry.line(row0, "ichi-r", "romaji")))
                .withMeaning(session.meaning.processing(LayerAuthority.MACHINE, MEANING_CONFIG, "", "run-1"));

        LyricsDocument projected = LegacyDocumentComposer.compose(document("ichi"), session);

        assertTrue(projected.includesRomanization);
        assertFalse(projected.romanizationPending);
        assertTrue(projected.translationPending);
        assertTrue(projected.processingPending);
    }

    @Test
    public void rowScopedApplicationTouchesOnlyTheNamedRows() {
        LyricsDocument target = document("ichi", "ni");
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", target), 1);
        String row1 = session.base.rows.get(1).rowId;
        session = session.withMeaning(readyMeaning(session, new MeaningEntry(row1, "two", "en")));

        java.util.Set<Integer> touched = LegacyDocumentComposer.applyRowScoped(target,
                SessionEvent.layerChanged(session, LayerKind.MEANING, Collections.singleton(row1)));

        assertEquals(Collections.singleton(1), touched);
        assertEquals("", target.lines.get(0).translatedText);
        assertEquals("two", target.lines.get(1).translatedText);
    }

    @Test
    public void artifactsComposeBackToExactlyWhatTheLaneWroteOnTheDocument() {
        // The migration guard in the session asserts this at runtime; pin it here so the round trip
        // cannot quietly lose a field and strand publication on the mutable document.
        LyricsDocument canonical = document("ichi", "ni");
        LyricsDocument written = document("ichi", "ni");
        written.lines.get(0).romanizedText = "ichi-r";
        written.lines.get(0).chineseMode = "pinyin";
        written.lines.get(0).syllables.add(syllable("s0", "i", 0L, 100L));
        written.lines.get(0).syllables.get(0).romanizedText = "i-r";
        written.lines.get(1).translatedText = "two";
        canonical.lines.get(0).syllables.add(syllable("s0", "i", 0L, 100L));

        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:a", canonical);
        LyricSession session = LyricSession.of(base, 1);
        List<SoundEntry> soundEntries = new ArrayList<>();
        List<MeaningEntry> meaningEntries = new ArrayList<>();
        for (CanonicalRow row : base.rows) {
            LyricsLine line = written.lines.get(row.index);
            SoundEntry sound = SoundEntry.fromLine(row, line);
            if (sound != null && (!sound.displayText.isEmpty() || sound.renderPlan != null)) {
                soundEntries.add(sound);
            }
            if (!line.translatedText.isEmpty()) {
                meaningEntries.add(new MeaningEntry(row.rowId, line.translatedText, "en"));
            }
        }
        session = session
                .withSound(session.sound.withArtifact(LayerStatus.READY, new SoundArtifact(
                        base.digest, SOUND_CONFIG, provenance(), soundEntries, false), "run-s"))
                .withMeaning(session.meaning.withArtifact(LayerStatus.READY, new MeaningArtifact(
                        base.digest, MEANING_CONFIG, provenance(), meaningEntries, false), "run-m"));

        LyricsDocument composed = LegacyDocumentComposer.compose(canonical, session);

        assertEquals(LyricsDocumentProcessor.publicationFingerprint(written),
                LyricsDocumentProcessor.publicationFingerprint(composed));
        assertEquals("ichi-r", composed.lines.get(0).romanizedText);
        assertEquals("i-r", composed.lines.get(0).syllables.get(0).romanizedText);
        assertEquals("pinyin", composed.lines.get(0).chineseMode);
        assertEquals("two", composed.lines.get(1).translatedText);
    }

    // --- bounded deltas -----------------------------------------------------

    @Test
    public void boundedDeltasFoldIntoTheLayerWithoutLosingEarlierRows() {
        LyricSession session = session("ichi", "ni", "san");
        String row0 = session.base.rows.get(0).rowId;
        String row2 = session.base.rows.get(2).rowId;
        MeaningArtifact first = new MeaningArtifact(session.base.digest, MEANING_CONFIG, provenance(),
                Collections.singletonList(new MeaningEntry(row0, "one", "en")), true);
        MeaningArtifact second = new MeaningArtifact(session.base.digest, MEANING_CONFIG, provenance(),
                Collections.singletonList(new MeaningEntry(row2, "three", "en")), false);

        LayerState state = LayerState.absent(LayerKind.MEANING)
                .withDelta(first, LayerStatus.PROCESSING)
                .withDelta(second, LayerStatus.READY);

        MeaningArtifact merged = (MeaningArtifact) state.artifact;
        assertEquals(2, merged.size());
        assertEquals("one", merged.meaning(row0).text);
        assertEquals("three", merged.meaning(row2).text);
        assertFalse(merged.partial);
        assertEquals(LayerStatus.READY, state.status);
    }

    @Test
    public void aDeltaForAnotherConfigIsIgnored() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        MeaningArtifact current = new MeaningArtifact(session.base.digest, MEANING_CONFIG, provenance(),
                Collections.singletonList(new MeaningEntry(row0, "one", "en")), false);
        MeaningArtifact foreign = new MeaningArtifact(session.base.digest, MEANING_CONFIG_ES, provenance(),
                Collections.singletonList(new MeaningEntry(row0, "uno", "es")), false);

        assertSame(current, current.mergedWith(foreign));
    }

    @Test
    public void artifactDigestTracksContent() {
        LyricSession session = session("ichi");
        String row0 = session.base.rows.get(0).rowId;
        SoundArtifact a = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line(row0, "ichi-r", "romaji")), false);
        SoundArtifact b = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line(row0, "ichi-r", "romaji")), false);
        SoundArtifact c = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                Collections.singletonList(SoundEntry.line(row0, "ICHI", "romaji")), false);

        assertEquals(a.artifactDigest, b.artifactDigest);
        assertNotEquals(a.artifactDigest, c.artifactDigest);
    }

    // --- helpers ------------------------------------------------------------

    private static LyricSession session(String... texts) {
        return LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", document(texts)), 1);
    }

    private static LayerState readySound(LyricSession session, SoundEntry... entries) {
        return LayerState.absent(LayerKind.SOUND).withArtifact(LayerStatus.READY,
                new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance(),
                        Arrays.asList(entries), false), "run-sound");
    }

    private static LayerState readyMeaning(LyricSession session, MeaningEntry... entries) {
        return LayerState.absent(LayerKind.MEANING).withArtifact(LayerStatus.READY,
                new MeaningArtifact(session.base.digest, MEANING_CONFIG, provenance(),
                        Arrays.asList(entries), false), "run-meaning");
    }

    private static LayerProvenance provenance() {
        return new LayerProvenance(LayerAuthority.DETERMINISTIC, "test", "c1", 0L);
    }

    private static LyricsDocument document(String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "track";
        doc.language = "ja";
        doc.type = "Line";
        long at = 0L;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = at;
            line.endMs = at + 1000L;
            at += 1000L;
            doc.lines.add(line);
        }
        return doc;
    }

    private static SyllableSegment syllable(String spanId, String text, long startMs, long endMs) {
        SyllableSegment segment = new SyllableSegment();
        segment.spanId = spanId;
        segment.text = text;
        segment.startMs = startMs;
        segment.endMs = endMs;
        return segment;
    }
}
