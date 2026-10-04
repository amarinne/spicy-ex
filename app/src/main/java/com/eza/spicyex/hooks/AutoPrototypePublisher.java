package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.DisplayLayoutGroup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.eza.spicyex.auto.AutoPrototypeClient;
import com.eza.spicyex.auto.AutoPrototypePolicy;
import com.eza.spicyex.auto.AutoLyricPresentation;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Shares the existing session's current timed row; it does not fetch or process another document. */
final class AutoPrototypePublisher implements LyricsSessionManager.Listener {
    private final AutoPrototypeClient client;
    private final com.eza.spicyex.SettingsStore settings;
    private final LyricsSessionManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private LyricsSessionManager.SessionSubscription subscription;
    private LyricsSessionManager.PollingDemandLease lease;
    private LyricsDocument document;
    private int generation = -1;
    private String trackUri = "";
    private volatile Bundle latest;
    private boolean enabled;
    private final SharedPreferences preferences;
    private ScheduledFuture<?> heartbeat;
    private volatile int lifecycle;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> {
                if (com.eza.spicyex.Settings.AUTO_ENABLED.key.equals(key))
                    main.post(this::readEnabled);
                // The enabled heartbeat reads the secondary-text choice before publication.
            };

    AutoPrototypePublisher(Context context, LyricsSessionManager manager) {
        this.client = new AutoPrototypeClient(context);
        this.manager = manager;
        this.settings = new com.eza.spicyex.SettingsStore(context);
        preferences = context.getSharedPreferences(com.eza.spicyex.SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }
    void start() {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        main.post(this::readEnabled);
    }
    private void readEnabled() { setEnabled(settings.get(com.eza.spicyex.Settings.AUTO_ENABLED)); }
    private Bundle configuration(boolean on) {
        Bundle desired = new Bundle();
        desired.putBoolean("enabled", on);
        com.eza.spicyex.auto.AutoDisplaySettings.write(desired, settings);
        return desired;
    }
    private void heartbeat(int version) {
        if (version != lifecycle) return;
        try {
            Bundle config = client.call("configure", configuration(true));
            Bundle value = latest;
            if (version == lifecycle && config != null && config.getBoolean("enabled") && value != null) {
                Bundle packet = new Bundle(value);
                com.eza.spicyex.auto.AutoDisplaySettings.write(packet, settings);
                client.call("publish", packet);
            }
        } catch (Throwable error) {
            // Keep the current session while the next heartbeat reconnects the transport.
            com.eza.spicyex.xposed.XpLog.log("[SpicyAuto] publish " + error.getClass().getSimpleName());
        }
    }
    private void disable(int version, long deadline) {
        if (version != lifecycle) return;
        boolean delivered = false;
        try { delivered = client.call("configure", configuration(false)) != null; }
        catch (Throwable error) {
            com.eza.spicyex.xposed.XpLog.log("[SpicyAuto] disable " + error.getClass().getSimpleName());
        }
        if (version != lifecycle) return;
        if (delivered || SystemClock.elapsedRealtime() >= deadline) client.close();
        else worker.schedule(() -> disable(version, deadline), 100, TimeUnit.MILLISECONDS);
    }
    private void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        int version = ++lifecycle;
        if (on) {
            lease = manager.acquirePollingDemand();
            subscription = manager.subscribe(this);
            heartbeat = worker.scheduleWithFixedDelay(() -> heartbeat(version), 0, 100, TimeUnit.MILLISECONDS);
        } else {
            if (heartbeat != null) heartbeat.cancel(false);
            heartbeat = null;
            if (subscription != null) subscription.close();
            if (lease != null) lease.close();
            subscription = null; lease = null; document = null; latest = null; generation = -1; trackUri = "";
            long deadline = SystemClock.elapsedRealtime() + 4000;
            worker.execute(() -> disable(version, deadline));
        }
    }
    @Override public void onSessionChanged(LyricsSessionManager.Snapshot s) {
        if (!enabled || s.generation < generation) return;
        if (!AutoPrototypePolicy.sameSession(generation, trackUri, s.generation, s.trackUri)) { generation = s.generation; document = null; }
        trackUri = s.trackUri;
        publish(s);
    }
    @Override public void onDocumentChanged(LyricsSessionManager.Snapshot s, LyricsDocument d) {
        if (!enabled || !AutoPrototypePolicy.sameSession(generation, trackUri, s.generation, s.trackUri)) return;
        document = com.eza.spicyex.auto.AutoResponseCredit.documentAllowed(d, System.currentTimeMillis()) ? d : null;
        if (document != null) LyricTimeline.applySyncedRows(document);
        publish(s);
    }
    private void publish(LyricsSessionManager.Snapshot s) {
        if (!com.eza.spicyex.auto.AutoResponseCredit.documentAllowed(document, System.currentTimeMillis())) document = null;
        Bundle b = new Bundle();
        b.putString("trackUri", s.trackUri);
        b.putString("title", s.track == null ? "" : s.track.title);
        b.putString("artist", s.track == null ? "" : s.track.artist);
        b.putInt("generation", s.generation); b.putBoolean("playing", s.playing);
        b.putDouble("playbackRate", s.playbackRate);
        b.putLong("sampledAt", s.sampledAtMs); b.putLong("positionMs", s.positionMs);
        b.putBoolean("documentResolved", document != null);
        boolean org = com.eza.spicyex.lyrics.providers.SpicyOrgPolicy.isRestricted(document);
        b.putBoolean("orgSource", org);
        b.putLong("creditFetchedAtMs", org ? document.spicyOrgFetchedAtMs : 0);
        b.putString("creditWriters", document == null || document.songWriters == null ? "" : document.songWriters);
        ArrayList<String> creditLabels = new ArrayList<>(), creditUrls = new ArrayList<>();
        if (org) for (com.eza.spicyex.lyrics.SpicyOrgAttribution.Credit credit
                : com.eza.spicyex.lyrics.SpicyOrgAttribution.credits(document)) {
            creditLabels.add(credit.label); creditUrls.add(credit.url);
        }
        b.putStringArrayList("creditLabels", creditLabels);
        b.putStringArrayList("creditUrls", creditUrls);
        long nextVocal = Long.MAX_VALUE, firstVocal = Long.MAX_VALUE, lastVocal = 0;
        if (document != null) for (AppliedLine row : document.appliedLines)
            if (!row.dotLine && !row.bgLine && row.text != null && !row.text.trim().isEmpty()) {
                firstVocal = Math.min(firstVocal, row.startMs);
                lastVocal = Math.max(lastVocal, LyricTimeline.fillEndMs(row));
                if (row.startMs > s.positionMs) nextVocal = Math.min(nextVocal, row.startMs);
            }
        b.putLong("nextVocalStartMs", nextVocal);
        b.putLong("firstVocalStartMs", firstVocal);
        b.putLong("lastVocalEndMs", lastVocal);
        b.putBoolean("hasSyncedLyrics", AutoLyricPresentation.hasSyncedLyrics(document));
        int index = AutoLyricPresentation.rowAt(document, s.positionMs);
        AppliedLine line = index < 0 ? null : document.appliedLines.get(index);
        encodeRow(b, line, index);
        b.putBoolean("vocalActive", AutoLyricPresentation.vocalActiveAt(document, s.positionMs));
        b.putString("presentation", AutoLyricPresentation.mode(document, index));
        if (document != null) {
            long nextAt = Long.MAX_VALUE;
            for (AppliedLine row : document.appliedLines)
                if (row.startMs > s.positionMs) nextAt = Math.min(nextAt, row.startMs);
            if (nextAt != Long.MAX_VALUE) {
                int nextIndex = AutoLyricPresentation.rowAt(document, nextAt);
                if (nextIndex >= 0 && nextIndex != index) {
                    Bundle next = new Bundle();
                    encodeRow(next, document.appliedLines.get(nextIndex), nextIndex);
                    next.putBoolean("vocalActive", AutoLyricPresentation.vocalActiveAt(document, nextAt));
                    next.putString("presentation", AutoLyricPresentation.mode(document, nextIndex));
                    b.putBundle("next", next); b.putLong("nextAtMs", nextAt);
                }
            }
        }
        latest = b;
    }
    private static void encodeRow(Bundle b, AppliedLine line, int index) {
        b.putInt("lineIndex", index);
        b.putString("line", line == null || line.dotLine ? "" : line.text);
        b.putString("secondary", line == null ? "" : line.translatedText);
        b.putString("romanized", com.eza.spicyex.lyrics.LyricsRowViewFactory.displayReading(line));
        b.putLong("lineStartMs", line == null ? 0 : line.startMs);
        b.putLong("lineEndMs", line == null ? 0 : LyricTimeline.fillEndMs(line));
        b.putBoolean("lineLevelSync", line != null && (line.syntheticWords || line.words.isEmpty()));
        java.util.List<DisplayLayoutGroup> groups =
                line == null ? java.util.Collections.emptyList()
                        : line.japaneseReading != null ? DisplayLayoutGroup.forLine(line)
                        : com.eza.spicyex.lyrics.language.SpicyTextDetection.hasKana(line.text)
                        ? com.eza.spicyex.lyrics.language.JapaneseScriptRunGrouping.forText(line.text)
                        : java.util.Collections.emptyList();
        int[] ranges = new int[Math.min(512, groups.size()) * 2];
        for (int i = 0; i < ranges.length / 2; i++) {
            ranges[i * 2] = groups.get(i).start; ranges[i * 2 + 1] = groups.get(i).end;
        }
        b.putIntArray("layoutGroups", ranges);
        ArrayList<Bundle> words = new ArrayList<>();
        if (line != null && !line.syntheticWords) for (SyllableSegment word : line.words) {
            if (words.size() >= 128) break;
            Bundle encoded = new Bundle();
            encoded.putString("text", word.text); encoded.putString("romanized", word.romanizedText);
            encoded.putLong("startMs", word.startMs); encoded.putLong("endMs", word.endMs);
            encoded.putBoolean("boundaryAfter", word.boundaryAfter);
            if (word.canonicalStartCp >= 0 && word.canonicalEndCp > word.canonicalStartCp
                    && word.canonicalEndCp <= line.text.codePointCount(0, line.text.length())) {
                encoded.putInt("sourceStart", line.text.offsetByCodePoints(0, word.canonicalStartCp));
                encoded.putInt("sourceEnd", line.text.offsetByCodePoints(0, word.canonicalEndCp));
            }
            words.add(encoded);
        }
        b.putParcelableArrayList("words", words);
    }

}
