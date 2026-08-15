package com.eza.spicyex.lyrics.session;

import java.util.LinkedHashSet;
import java.util.Set;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/**
 * Migration adapter: folds session layers into the legacy mutable {@link LyricsDocument} shape at
 * the publication boundary.
 *
 * <p>This is not the final state boundary. New processing code emits layer artifacts and deltas;
 * only this class turns them into legacy fields, and it always works on a copy so the canonical
 * base and the caller's document stay unmutated.
 */
public final class LegacyDocumentComposer {
    private LegacyDocumentComposer() {
    }

    /**
     * Projects {@code session}'s derived layers onto a copy of {@code canonicalDocument}.
     *
     * @param canonicalDocument the parsed document that produced the session's base; never modified
     * @return a new document carrying original text and timing plus whichever layers are displayed
     */
    public static LyricsDocument compose(LyricsDocument canonicalDocument, LyricSession session) {
        LyricsDocument projected = LyricsDocument.copyOf(canonicalDocument);
        if (projected == null || session == null) return projected;
        applyLayer(projected, session, LayerKind.SOUND);
        applyLayer(projected, session, LayerKind.MEANING);
        // An artifact retained across a refresh is still on screen, so it still counts as included
        // even while the layer's status says the lane is running again.
        projected.includesRomanization = session.sound.artifact != null && !session.sound.artifact.isEmpty();
        projected.includesTranslation = session.meaning.artifact != null && !session.meaning.artifact.isEmpty();
        projected.romanizationPending = session.sound.status == LayerStatus.PROCESSING;
        projected.translationPending = session.meaning.status == LayerStatus.PROCESSING;
        projected.processingPending = projected.romanizationPending || projected.translationPending;
        return projected;
    }

    /**
     * Applies only the rows named by {@code event} onto {@code target} in place, for the migration
     * window where a renderer can update rows but the document object must stay identical.
     *
     * @return the document line indices that changed
     */
    public static Set<Integer> applyRowScoped(LyricsDocument target, SessionEvent event) {
        Set<Integer> touched = new LinkedHashSet<>();
        if (target == null || event == null || !event.isRowScoped()) return touched;
        LayerKind kind = event.kind == SessionEvent.Kind.MEANING_CHANGED ? LayerKind.MEANING : LayerKind.SOUND;
        LyricSession session = event.session;
        LayerState state = session.layer(kind);
        if (state.artifact == null) return touched;
        for (String rowId : event.changedRowIds) {
            int index = session.base.indexOfRow(rowId);
            if (index < 0 || index >= target.lines.size()) continue;
            if (applyEntry(target.lines.get(index), state.artifact.entry(rowId), kind)) touched.add(index);
        }
        return touched;
    }

    private static void applyLayer(LyricsDocument target, LyricSession session, LayerKind kind) {
        LayerState state = session.layer(kind);
        // Whatever the layer holds is on screen, including a partial delta from a lane still
        // working and an artifact retained across a refresh. Status says whether more is coming,
        // not whether what is here should be shown.
        if (state.artifact == null) return;
        DerivedLayerArtifact artifact = state.artifact;
        // Row IDs are the only accepted address. An artifact that does not fully apply to the base
        // is not projected at all — no positional or count-only fallback.
        if (!artifact.appliesTo(session.base)) return;
        for (LayerEntry entry : artifact.allEntries()) {
            int index = session.base.indexOfRow(entry.rowId());
            if (index < 0 || index >= target.lines.size()) continue;
            applyEntry(target.lines.get(index), entry, kind);
        }
    }

    private static boolean applyEntry(LyricsLine line, LayerEntry entry, LayerKind kind) {
        if (line == null || entry == null) return false;
        if (kind == LayerKind.MEANING) {
            if (!(entry instanceof MeaningEntry)) return false;
            line.translatedText = ((MeaningEntry) entry).text;
            return true;
        }
        if (!(entry instanceof SoundEntry)) return false;
        SoundEntry sound = (SoundEntry) entry;
        if (sound.renderPlan != null) {
            line.readingRenderPlan = sound.renderPlan;
            // A valid plan owns displayed reading text; never leave a competing legacy string beside it.
            line.romanizedText = "";
        } else {
            line.readingRenderPlan = null;
            line.romanizedText = sound.displayText;
        }
        if (sound.japaneseReading != null) line.japaneseReading = sound.japaneseReading;
        if (!sound.mode.isEmpty()) line.chineseMode = sound.mode;
        applySpanReadings(line, sound);
        return true;
    }

    private static void applySpanReadings(LyricsLine line, SoundEntry sound) {
        if (sound.spanReadings.isEmpty() || line.syllables == null || line.syllables.isEmpty()) return;
        for (SoundEntry.SpanReading reading : sound.spanReadings) {
            SyllableSegment target = null;
            for (SyllableSegment segment : line.syllables) {
                if (segment != null && !reading.spanId.isEmpty() && reading.spanId.equals(segment.spanId)) {
                    target = segment;
                    break;
                }
            }
            // Providers that supply no span IDs get derived ones, which the segments do not carry;
            // the recorded position resolves those.
            if (target == null && reading.spanIndex >= 0 && reading.spanIndex < line.syllables.size()) {
                target = line.syllables.get(reading.spanIndex);
            }
            if (target != null) target.romanizedText = reading.text;
        }
    }
}
