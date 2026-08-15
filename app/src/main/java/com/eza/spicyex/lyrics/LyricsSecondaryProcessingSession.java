package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.LayerKind;

import de.robv.android.xposed.XposedBridge;

/**
 * Reads layer settings and starts the derived lanes for one document.
 *
 * <p>Each layer persists its own artifact when it completes, under its own cache identity. A Sound
 * completion never writes Meaning state and vice versa, so closing the screen mid-translation keeps
 * the readings that already finished.
 */
public final class LyricsSecondaryProcessingSession {
    private final Context context;
    private final SpotifyPlusConfig config;
    private final LyricsSecondaryProcessor processor;
    private final int processingVersion;
    private final String logTag;

    public LyricsSecondaryProcessingSession(
            Context context,
            SpotifyPlusConfig config,
            LyricsSecondaryProcessor processor,
            int processingVersion,
            String logTag
    ) {
        this.context = context;
        this.config = config;
        this.processor = processor;
        this.processingVersion = processingVersion;
        this.logTag = logTag;
    }

    /** Retires both lanes and aborts their in-flight provider calls. */
    public void cancelActive() {
        processor.cancelActive();
    }

    public java.util.Set<LayerKind> start(
            String id,
            int generation,
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions options,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            Callback callback
    ) {
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty()) {
            return java.util.EnumSet.noneOf(LayerKind.class);
        }
        // No shared readiness gate: each lane decides for itself. A document that needs only a
        // translation still starts, and so does one that needs only readings.
        if (!snapshot.romanizationPending && !snapshot.translationPending) {
            return java.util.EnumSet.noneOf(LayerKind.class);
        }

        if (callback != null) callback.status("Enhancing " + label(snapshot) + "…");
        final String targetLang = config.get(Settings.TRANSLATION_TARGET);
        final String backend = config.get(Settings.TRANSLATION_BACKEND);
        final String sourceLanguage = "manual".equalsIgnoreCase(config.get(Settings.SOURCE_LANGUAGE_MODE))
                ? config.get(Settings.SOURCE_LANGUAGE)
                : snapshot.language;
        final String effectiveSourceLang = effectiveGoogleSourceLanguage(
                config.get(Settings.SOURCE_LANGUAGE_MODE),
                config.get(Settings.SOURCE_LANGUAGE));
        Diagnostics.event("secondary_processing", "branch_started",
                Diagnostics.context(
                        "branch", label(snapshot),
                        "language", effectiveSourceLang,
                        "status", targetLang));

        XposedBridge.log(logTag + " derived lanes start backend=" + backend
                + " target=" + targetLang + " source=" + sourceLanguage);
        return processor.start(id, generation, snapshot, showRomanization, options, backend, targetLang,
                sourceLanguage, effectiveSourceLang,
                currentGuard,
                new LyricsSecondaryProcessor.Callback() {
                    @Override
                    public void rerender(LayerKind layer, DerivedLayerArtifact partial, String message) {
                        if (callback != null) callback.rerender(layer, partial, snapshot, message);
                    }

                    @Override
                    public void progress(String message) {
                        if (callback != null) callback.progress(snapshot, message);
                    }

                    @Override
                    public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                         String message, int changed) {
                        Diagnostics.event("secondary_processing", "branch_completed",
                                Diagnostics.context("result", "success", "branch", layer.name()));
                        if (callback != null) callback.complete(layer, artifact, snapshot, message, changed);
                        persist(layer, artifact, snapshot, options);
                        XposedBridge.log(logTag + " " + layer.name().toLowerCase(java.util.Locale.ROOT)
                                + " lane complete changed=" + changed + " lines=" + snapshot.lines.size());
                    }
                });
    }

    /**
     * Writes the completed layer to its own store from the artifact, not from the document.
     *
     * <p>The artifact carries the canonical digest and configuration it was produced under, so the
     * record lands under that identity rather than one recomputed at write time — and the document
     * stops being what the cache is built from, which is what lets the lanes stop writing to it.
     */
    private void persist(LayerKind layer, DerivedLayerArtifact artifact, LyricsDocument snapshot,
                         RomanizationOptions options) {
        if (artifact == null || artifact.isEmpty()) return;
        CanonicalBase base = LyricsDocumentProcessor.canonicalBaseOf(snapshot);
        if (layer == LayerKind.SOUND && artifact instanceof SoundArtifact) {
            LyricsDocumentProcessor.saveSoundArtifact(context, base, (SoundArtifact) artifact);
        } else if (layer == LayerKind.MEANING && artifact instanceof MeaningArtifact) {
            LyricsDocumentProcessor.saveMeaningArtifact(context, base, (MeaningArtifact) artifact);
        }
    }

    private static String label(LyricsDocument snapshot) {
        String label = (snapshot.romanizationPending ? "readings" : "")
                + (snapshot.romanizationPending && snapshot.translationPending ? " + " : "")
                + (snapshot.translationPending ? "translation" : "");
        return label.isEmpty() ? "lyrics" : label;
    }

    static String effectiveGoogleSourceLanguage(String sourceMode, String sourceLanguage) {
        return "manual".equalsIgnoreCase(sourceMode) ? sourceLanguage : "auto";
    }

    public interface Callback {
        void status(String message);
        void rerender(LayerKind layer, DerivedLayerArtifact partial, LyricsDocument snapshot,
                      String message);
        void progress(LyricsDocument snapshot, String message);
        /** Fires once per layer, in whichever order the two lanes settle. */
        void complete(LayerKind layer, DerivedLayerArtifact artifact, LyricsDocument snapshot,
                      String message, int changed);
    }
}
