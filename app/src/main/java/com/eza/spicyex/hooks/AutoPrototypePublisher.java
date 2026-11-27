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
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Shares the existing session's current timed row; it does not fetch or process another document. */
final class AutoPrototypePublisher implements LyricsSessionManager.Listener {
    private final AutoPrototypeClient client;
    private final Context context;
    private final com.eza.spicyex.SettingsStore settings;
    private final LyricsSessionManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker;
    private LyricsSessionManager.SessionSubscription subscription;
    private LyricsSessionManager.PollingDemandLease lease;
    private LyricsDocument document;
    private LyricsSessionManager.Snapshot lastSnapshot;
    private int generation = -1;
    private String trackUri = "";
    private volatile Bundle latest;
    private boolean enabled;
    private boolean observingHost;
    private final SharedPreferences preferences;
    private ScheduledFuture<?> heartbeat;
    private volatile int lifecycle;
    private volatile int configurationRevision;
    private int sentConfigurationRevision = -1;
    private final android.content.BroadcastReceiver hostObserver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) { beginPublication(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> {
                if (com.eza.spicyex.Settings.AUTO_ENABLED.key.equals(key))
                    main.post(this::readEnabled);
                if (com.eza.spicyex.Settings.CHINESE_CHARACTER_FORM.key.equals(key))
                    main.post(this::refreshCharacterForm);
                if (key != null && key.startsWith("auto_")) main.post(() -> configurationRevision++);
                // The enabled heartbeat reads the secondary-text choice before publication.
            };

    AutoPrototypePublisher(Context context, LyricsSessionManager manager) {
        this(context, manager, new AutoPrototypeClient(context), Executors.newSingleThreadScheduledExecutor(
                task -> new Thread(task, "SpicyAutoPublisher")));
    }
    AutoPrototypePublisher(Context context, LyricsSessionManager manager, AutoPrototypeClient client,
            ScheduledExecutorService worker) {
        this.client = client;
        this.worker = worker;
        this.context = context;
        this.manager = manager;
        this.settings = new com.eza.spicyex.SettingsStore(context);
        preferences = context.getSharedPreferences(com.eza.spicyex.SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }
    void start() {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        main.post(this::readEnabled);
    }
    private void readEnabled() { setEnabled(settings.get(com.eza.spicyex.Settings.AUTO_ENABLED)); }
    private ChineseCharacterForm characterForm() {
        return ChineseCharacterForm.fromSetting(settings.get(com.eza.spicyex.Settings.CHINESE_CHARACTER_FORM));
    }
    private void refreshCharacterForm() {
        if (!enabled || document == null || lastSnapshot == null) return;
        ChineseCharacterForm form = characterForm();
        if (document.chineseCharacterForm == form) return;
        document.chineseCharacterForm = form;
        LyricTimeline.applySyncedRows(document);
        publish(lastSnapshot);
    }
    static LyricsDocument projectDocument(LyricsDocument source, ChineseCharacterForm form) {
        LyricsDocument copy = LyricsDocument.copyOf(source);
        if (copy == null) return null;
        copy.chineseCharacterForm = form;
        LyricTimeline.applySyncedRows(copy);
        return copy;
    }
    private Bundle configuration(boolean on) {
        Bundle desired = new Bundle();
        desired.putBoolean("enabled", on);
        com.eza.spicyex.auto.AutoDisplaySettings.write(desired, settings);
        return desired;
    }
    private void beginPublication() {
        if (!enabled || heartbeat != null) return;
        int version = ++lifecycle;
        worker.execute(() -> sentConfigurationRevision = -1);
        long deadline = SystemClock.elapsedRealtime() + 4000;
        heartbeat = worker.scheduleWithFixedDelay(() -> heartbeat(version, deadline), 0, 100, TimeUnit.MILLISECONDS);
    }
    private void heartbeat(int version, long deadline) {
        if (version != lifecycle) return;
        try {
            Bundle reply;
            int revision = configurationRevision;
            Bundle value = latest;
            if (sentConfigurationRevision != revision) {
                reply = client.call("configure", configuration(true));
                if (reply != null) sentConfigurationRevision = revision;
            } else if (value != null) {
                Bundle packet = new Bundle(value);
                com.eza.spicyex.auto.AutoDisplaySettings.write(packet, settings);
                reply = client.call("publish", packet);
            } else reply = client.call("status", null);
            if (reply != null && reply.getBoolean("hostActive")) {
                main.post(() -> {
                    if (version != lifecycle || lease != null) return;
                    lease = manager.acquirePollingDemand();
                    subscription = manager.subscribe(this);
                });
            } else if (reply != null || SystemClock.elapsedRealtime() >= deadline)
                main.post(() -> { if (version == lifecycle) stopPublication(); });
        } catch (Throwable error) {
            // Fail closed once. A fresh host notification is the only reconnect demand.
            main.post(() -> { if (version == lifecycle) stopPublication(); });
        }
    }
    private void stopPublication() {
        ++lifecycle;
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = null;
        if (subscription != null) subscription.close();
        if (lease != null) lease.close();
        subscription = null; lease = null; document = null; lastSnapshot = null; latest = null; generation = -1; trackUri = "";
        worker.execute(client::close);
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
        if (on) {
            observeHost();
        } else {
            if (observingHost) context.unregisterReceiver(hostObserver);
            observingHost = false;
            stopPublication();
            int version = lifecycle;
            long deadline = SystemClock.elapsedRealtime() + 4000;
            worker.execute(() -> disable(version, deadline));
        }
    }
    private boolean observeHost() {
        if (observingHost) return true;
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    com.eza.spicyex.auto.AutoPrototypeProvider.HOST_ACTION);
            filter.addDataScheme("content");
            // The module's signature permission authenticates the sender. The URI grant in its
            // notice makes the provider visible before Spotify binds, even on a cold start.
            if (android.os.Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(hostObserver, filter,
                        com.eza.spicyex.auto.AutoPrototypeProvider.HOST_PERMISSION, main, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(hostObserver, filter,
                    com.eza.spicyex.auto.AutoPrototypeProvider.HOST_PERMISSION, main);
            observingHost = true;
        } catch (RuntimeException unavailable) {
            // Auto is optional. Receiver registration must never crash Spotify.
        }
        return observingHost;
    }
    @Override public void onSessionChanged(LyricsSessionManager.Snapshot s) {
        if (!enabled || lease == null || s.generation < generation) return;
        if (!AutoPrototypePolicy.sameSession(generation, trackUri, s.generation, s.trackUri)) { generation = s.generation; document = null; }
        trackUri = s.trackUri;
        lastSnapshot = s;
        publish(s);
    }
    @Override public void onDocumentChanged(LyricsSessionManager.Snapshot s, LyricsDocument d) {
        if (!enabled || lease == null || !AutoPrototypePolicy.sameSession(generation, trackUri, s.generation, s.trackUri)) return;
        document = com.eza.spicyex.auto.AutoResponseCredit.documentAllowed(d, System.currentTimeMillis())
                ? projectDocument(d, characterForm()) : null;
        lastSnapshot = s;
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
        if (document != null && index >= 0) {
            int duet = com.eza.spicyex.auto.AutoDuetLayout.concurrentRowAt(
                    document.appliedLines, index, s.positionMs);
            if (duet >= 0) {
                Bundle companion = new Bundle();
                encodeRow(companion, document.appliedLines.get(duet), duet);
                companion.putString("presentation", AutoLyricPresentation.mode(document, duet));
                b.putBundle("duet", companion);
            }
        }
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
                    int nextDuet = com.eza.spicyex.auto.AutoDuetLayout.concurrentRowAt(
                            document.appliedLines, nextIndex, nextAt);
                    if (nextDuet >= 0) {
                        Bundle companion = new Bundle();
                        encodeRow(companion, document.appliedLines.get(nextDuet), nextDuet);
                        companion.putString("presentation", AutoLyricPresentation.mode(document, nextDuet));
                        next.putBundle("duet", companion);
                    }
                    b.putBundle("next", next); b.putLong("nextAtMs", nextAt);
                }
            }
        }
        // Every row update carries the current selection; the provider keeps the old one when a
        // packet omits it, but sending it keeps the display settings current without the heartbeat.
        com.eza.spicyex.auto.AutoDisplaySettings.write(b, settings);
        latest = b;
    }
    private static void encodeRow(Bundle b, AppliedLine line, int index) {
        b.putInt("lineIndex", index);
        b.putString("line", line == null || line.dotLine ? "" : line.text);
        b.putString("secondary", com.eza.spicyex.lyrics.LyricsRowViewFactory.displayTranslation(line));
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
