package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;

import java.util.concurrent.ExecutorService;

import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.SoundArtifact;

import okhttp3.OkHttpClient;

/**
 * Owner of the two independent derived lanes.
 *
 * <p>This class only starts work; it holds no shared readiness flag, no shared job, and no shared
 * cache identity. {@link LyricsSoundLane} and {@link LyricsMeaningLane} each decide for themselves
 * whether they have work, run on their own executors, and report their own completion. Neither
 * blocks the other, and a settings change for one never invalidates the other.
 */
public final class LyricsSecondaryProcessor {
    private final LyricsSoundLane soundLane;
    private final LyricsMeaningLane meaningLane;

    public LyricsSecondaryProcessor(
            Context context,
            OkHttpClient http,
            ExecutorService soundExecutor,
            ExecutorService soundNetworkWorkers,
            ExecutorService meaningExecutor,
            ExecutorService aiExecutor,
            Handler handler,
            int processingVersion
    ) {
        this.soundLane = new LyricsSoundLane(context, http, soundExecutor, soundNetworkWorkers,
                aiExecutor, handler, processingVersion);
        this.meaningLane = new LyricsMeaningLane(context, http, meaningExecutor, aiExecutor,
                handler, processingVersion);
    }

    /**
     * Starts both lanes. Each returns immediately; completion arrives per layer.
     *
     * @return the layers that actually began work. A layer absent from this set had nothing to do
     *         and will still report completion, so the caller can tell "running" from "idle" —
     *         which is what a progress indicator needs and a completion callback cannot express.
     */
    public java.util.Set<LayerKind> start(
            String id,
            int generation,
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            SoundArtifact displayedSound,
            String translationBackend,
            String targetLang,
            String sourceLang,
            String effectiveSourceLang,
            java.util.Set<LayerKind> explicitAiRequests,
            CurrentGuard currentGuard,
            Callback callback
    ) {
        java.util.Set<LayerKind> started = java.util.EnumSet.noneOf(LayerKind.class);
        if (snapshot == null || snapshot.lines.isEmpty()) return started;
        java.util.Set<LayerKind> explicit = explicitAiRequests == null
                ? java.util.Collections.<LayerKind>emptySet() : explicitAiRequests;
        if (soundLane.start(id, generation, snapshot, showRomanization, opts, displayedSound,
                effectiveSourceLang,
                explicit.contains(LayerKind.SOUND), currentGuard, callback)) {
            started.add(LayerKind.SOUND);
        }
        if (meaningLane.start(id, generation, snapshot, translationBackend, targetLang, sourceLang,
                effectiveSourceLang, explicit.contains(LayerKind.MEANING), currentGuard, callback)) {
            started.add(LayerKind.MEANING);
        }
        return started;
    }

    /** Retires both lanes and aborts their in-flight provider calls. */
    public void cancelActive() {
        soundLane.cancelActive();
        meaningLane.cancelActive();
    }

    public void reprocessLocal(
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            String reason,
            CurrentGuard currentGuard,
            LocalCallback callback
    ) {
        soundLane.reprocessLocal(snapshot, showRomanization, opts, reason, currentGuard, callback);
    }

    public interface CurrentGuard {
        boolean isCurrent(String id, int generation, LyricsDocument snapshot);
    }

    public interface Callback {
        /**
         * A layer has partial output worth showing already.
         *
         * @param partial what is ready so far, addressed by canonical row ID. The session folds it
         *                in, so a later composed projection cannot drop what was already rendered.
         */
        void rerender(LayerKind layer, DerivedLayerArtifact partial, String message);
        void progress(String message);
        /**
         * One layer finished. Fires once per layer, in whichever order the lanes settle.
         *
         * @param artifact what the lane produced, addressed by canonical row ID; null when the
         *                 layer had nothing to show
         */
        void complete(LayerKind layer, DerivedLayerArtifact artifact, LayerFailure failure,
                      String message, int changed);
    }

    public interface LocalCallback {
        void complete(String reason, int changed, boolean current);
    }
}
