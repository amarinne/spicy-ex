package com.eza.spicyex.hooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.LyricsDocument;

import java.util.UUID;

import de.robv.android.xposed.XposedBridge;

/** Optional HyperGlow consumer of the process-level lyric session. */
final class SpicyLyricBridgeCoordinator implements LyricsSessionManager.Listener {
    private static final long HEARTBEAT_MS = 1000L;

    private final LyricsSessionManager sessionManager;
    private final SpicyLyricBridgePublisher publisher;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final String producerId = UUID.randomUUID().toString();

    private LyricsSessionManager.Snapshot lastSnapshot;
    private LyricsDocument document;
    private long sequence;
    private long documentRevision;
    /** What the bridge last handed the consumer; a republication matching it is skipped. */
    private String publishedFingerprint = "";
    private long lastPublishAtMs;
    private int lastLineIndex = Integer.MIN_VALUE;
    private boolean lastPlaying;
    private String lastStatus = "";
    private boolean enabled;
    private LyricsSessionManager.SessionSubscription subscription;
    private LyricsSessionManager.PollingDemandLease pollingDemand;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (!Settings.HYPERGLOW_ENABLED.key.equals(key)) return;
                handler.post(() -> setEnabled(sharedPreferences.getBoolean(
                        Settings.HYPERGLOW_ENABLED.key,
                        Settings.HYPERGLOW_ENABLED.defaultValue)));
            };

    SpicyLyricBridgeCoordinator(LyricsSessionManager sessionManager, Context context) {
        this.sessionManager = sessionManager;
        publisher = new SpicyLyricBridgePublisher(context);
        preferences = context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    void start() {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        setEnabled(preferences.getBoolean(Settings.HYPERGLOW_ENABLED.key,
                Settings.HYPERGLOW_ENABLED.defaultValue));
    }

    private void setEnabled(boolean nextEnabled) {
        if (enabled == nextEnabled) return;
        enabled = nextEnabled;
        if (enabled) {
            publisher.enable();
            subscription = sessionManager.subscribe(this);
            pollingDemand = sessionManager.acquirePollingDemand();
            return;
        }
        if (pollingDemand != null) pollingDemand.close();
        pollingDemand = null;
        if (subscription != null) subscription.close();
        subscription = null;
        int generation = lastSnapshot == null ? 0 : lastSnapshot.generation;
        publisher.clearAndDisconnect(producerId, generation);
        resetLocal();
    }

    @Override
    public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
        if (!enabled || snapshot == null) return;
        if (lastSnapshot == null || snapshot.generation != lastSnapshot.generation) {
            if (lastSnapshot != null && !lastSnapshot.trackUri.isEmpty()) {
                publisher.clear(producerId, lastSnapshot.generation);
            }
            document = null;
            sequence = 0L;
            documentRevision++;
            publishedFingerprint = "";
            lastLineIndex = Integer.MIN_VALUE;
            lastPublishAtMs = 0L;
        }
        lastSnapshot = snapshot;
        if (snapshot.track == null || snapshot.trackUri.isEmpty()) {
            publisher.clear(producerId, snapshot.generation);
            return;
        }
        publishCurrent(snapshot);
    }

    @Override
    public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot, LyricsDocument nextDocument) {
        if (!enabled || snapshot == null || nextDocument == null
                || lastSnapshot == null || snapshot.generation != lastSnapshot.generation) return;
        document = nextDocument;
        publishDocument(snapshot, nextDocument);
        lastLineIndex = Integer.MIN_VALUE;
        lastPublishAtMs = 0L;
        publishCurrent(snapshot);
    }

    private void publishCurrent(LyricsSessionManager.Snapshot snapshot) {
        long now = snapshot.sampledAtMs;
        int lineIndex = document == null || document.appliedLines == null
                ? -1 : LyricTimeline.findPrimaryActiveRow(document.appliedLines, snapshot.positionMs);
        boolean heartbeatDue = now - lastPublishAtMs >= HEARTBEAT_MS;
        if (lineIndex == lastLineIndex && snapshot.playing == lastPlaying
                && snapshot.status.equals(lastStatus) && !heartbeatDue) return;
        AppliedLine line = lineIndex >= 0 && document != null && lineIndex < document.appliedLines.size()
                ? document.appliedLines.get(lineIndex) : null;
        publish(snapshot, line, lineIndex);
        lastLineIndex = lineIndex;
        lastPlaying = snapshot.playing;
        lastStatus = snapshot.status;
        lastPublishAtMs = now;
    }

    private void publishDocument(LyricsSessionManager.Snapshot snapshot, LyricsDocument source) {
        LyricTimeline.applySyncedRows(source);
        // This boundary cannot express a delta — the consumer receives a whole serialized document
        // over IPC — so the saving available here is not republishing at all. A track produces
        // several publications while the lanes settle, and a lane that had no work changes nothing
        // a viewer would see.
        String fingerprint = LyricsDocumentProcessor.publicationFingerprint(source);
        if (fingerprint.equals(publishedFingerprint)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.LAYER_LOCAL_UPDATE);
            return;
        }
        publishedFingerprint = fingerprint;
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(source);
        if (workerSnapshot == null) return;
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DOCUMENT_REBUILD);
        int generation = snapshot.generation;
        String trackUri = snapshot.trackUri;
        long revision = ++documentRevision;
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                LyricTimeline.applySyncedRows(workerSnapshot);
                byte[] encoded = SpicyLyricBridgeDocumentSerializer.serialize(
                        workerSnapshot, producerId, generation, trackUri);
                Bundle metadata = new Bundle();
                metadata.putInt("documentVersion", SpicyLyricBridgeDocumentSerializer.DOCUMENT_VERSION);
                metadata.putString("producerId", producerId);
                metadata.putInt("generation", generation);
                metadata.putString("trackUri", trackUri);
                metadata.putInt("compressedBytes", encoded.length);
                handler.post(() -> {
                    if (!shouldPublishSerializedDocument(
                            enabled,
                            lastSnapshot == null ? null : lastSnapshot.generation,
                            generation,
                            revision,
                            documentRevision)) {
                        XposedBridge.log("[SpotifyPlusBridge] document publication superseded"
                                + " generation=" + generation + " revision=" + revision
                                + " current=" + documentRevision);
                        return;
                    }
                    publisher.publishDocument(metadata, encoded);
                });
            } catch (Exception e) {
                XposedBridge.log("[SpotifyPlusBridge] document encode failed: "
                        + e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Whether a document serialized off the main thread may still be handed to the consumer.
     *
     * <p>The revision is the ordering authority: a newer publication bumps it, so a serialization
     * it overtook is dropped here. The instance the payload was built from is deliberately not
     * compared. A later notification always replaces the coordinator's document reference — the
     * session publishes a fresh copy every time — but a notification whose content matched the last
     * publication returns at the fingerprint check without bumping the revision. Comparing
     * instances therefore discarded the only publication carrying new content whenever a lane
     * finished with no changes inside the serialization window, and the fingerprint was already
     * recorded as published, so nothing re-sent it. The consumer then ran the whole song with no
     * timed document and its keepalive expired with the song-change lease.
     */
    static boolean shouldPublishSerializedDocument(
            boolean enabled,
            Integer sessionGeneration,
            int documentGeneration,
            long revision,
            long currentRevision
    ) {
        return enabled
                && sessionGeneration != null
                && sessionGeneration == documentGeneration
                && revision == currentRevision;
    }

    private void publish(LyricsSessionManager.Snapshot snapshot, AppliedLine line, int lineIndex) {
        SpotifyTrack track = snapshot.track;
        long durationMs = Math.max(0L, track.duration);
        long positionMs = Math.max(0L, snapshot.positionMs);
        if (durationMs > 0L) positionMs = Math.min(positionMs, durationMs);
        Bundle state = new Bundle();
        state.putInt("protocolVersion", SpicyLyricBridgePublisher.PROTOCOL_VERSION);
        state.putString("producerId", producerId);
        state.putInt("generation", snapshot.generation);
        state.putLong("sequence", ++sequence);
        state.putString("status", snapshot.status);
        state.putString("trackUri", safe(track.uri, 512));
        state.putString("title", safe(track.title, 512));
        state.putString("artist", safe(track.artist, 512));
        state.putString("album", safe(track.album, 512));
        state.putString("imageId", safe(track.imageId, 512));
        state.putString("line", line == null || line.dotLine ? "" : safe(line.text, 8192));
        state.putString("romanizedLine", line == null || line.dotLine ? "" : safe(readingText(line), 8192));
        state.putString("translatedLine", line == null || line.dotLine ? "" : safe(line.translatedText, 8192));
        state.putInt("lineIndex", lineIndex);
        state.putLong("positionMs", positionMs);
        state.putLong("durationMs", durationMs);
        state.putLong("sampledAtElapsedMs", snapshot.sampledAtMs);
        state.putFloat("speed", 1f);
        state.putBoolean("playing", snapshot.playing);
        state.putString("liveCardWeight", preferences.getString(
                Settings.LIVE_CARD_WEIGHT.key, Settings.LIVE_CARD_WEIGHT.defaultValue));
        state.putString("liveCardTextSize", preferences.getString(
                Settings.LIVE_CARD_TEXT_SIZE.key, Settings.LIVE_CARD_TEXT_SIZE.defaultValue));
        state.putInt("liveCardTextSizeCustom", preferences.getInt(
                Settings.LIVE_CARD_TEXT_SIZE_CUSTOM.key, Settings.LIVE_CARD_TEXT_SIZE_CUSTOM.defaultValue));
        state.putString("liveCardSecondaryMode", preferences.getString(
                Settings.LIVE_CARD_SECONDARY_MODE.key, Settings.LIVE_CARD_SECONDARY_MODE.defaultValue));
        state.putString("liveCardAnimation", preferences.getString(
                Settings.LIVE_CARD_ANIMATION.key, Settings.LIVE_CARD_ANIMATION.defaultValue));
        state.putString("liveCardGlow", preferences.getString(
                Settings.LIVE_CARD_GLOW.key, Settings.LIVE_CARD_GLOW.defaultValue));
        state.putString("liveCardOverflow", preferences.getString(
                Settings.LIVE_CARD_OVERFLOW.key, Settings.LIVE_CARD_OVERFLOW.defaultValue));
        state.putString("liveCardTransition", preferences.getString(
                Settings.LIVE_CARD_TRANSITION.key, Settings.LIVE_CARD_TRANSITION.defaultValue));
        state.putString("liveCardLineSyncFill", preferences.getString(
                Settings.LIVE_CARD_LINE_SYNC_FILL.key, Settings.LIVE_CARD_LINE_SYNC_FILL.defaultValue));
        state.putString("lyricsFont", preferences.getString(
                Settings.LYRICS_FONT.key, Settings.LYRICS_FONT.defaultValue));
        publisher.publish(state);
    }

    private void resetLocal() {
        lastSnapshot = null;
        document = null;
        sequence = 0L;
        documentRevision++;
        publishedFingerprint = "";
        lastPublishAtMs = 0L;
        lastLineIndex = Integer.MIN_VALUE;
        lastPlaying = false;
        lastStatus = "";
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String readingText(AppliedLine line) {
        String planned = line.readingRenderPlan == null ? "" : line.readingRenderPlan.joinedDisplayText;
        return planned == null || planned.trim().isEmpty() ? line.romanizedText : planned;
    }
}
