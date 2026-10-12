package com.eza.spicyex.lyrics.ai;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Runs production recovery and replay with synthetic answers and disposable module storage. */
public final class AiRecoveryInstrumentation {
    private static final int CASES = 5;

    private AiRecoveryInstrumentation() { }

    public static void run(Instrumentation runner) {
        int completed = 0;
        List<String> failures = new ArrayList<>();
        String[] names = {"mixed segment acceptance", "row atomic LENGTH recovery",
                "credentialless partial display", "corrupt stored output rejection",
                "selective deletion fence"};
        for (int index = 0; index < CASES; index++) {
            Bundle status = new Bundle();
            status.putString("test", names[index]);
            status.putInt("numtests", CASES); status.putInt("current", index + 1);
            runner.sendStatus(1, status);
            try (Fixture context = new Fixture(runner.getTargetContext())) {
                if (index < 2) recovery(context, index == 1);
                else if (index == 2) partialDisplay(context);
                else if (index == 3) corrupt(context);
                else selectiveDeletion(context);
                completed++;
                runner.sendStatus(0, status);
            } catch (Throwable failure) {
                failures.add(names[index] + ": " + failure);
                status.putString("stack", failure.toString()); runner.sendStatus(-2, status);
            }
        }
        Bundle result = new Bundle();
        result.putInt("expectedCases", CASES); result.putInt("completedCases", completed);
        result.putString("stream", failures.isEmpty() ? "PASS: 5 native AI recovery cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        runner.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private static LyricsDocument document(String... texts) {
        LyricsDocument document = new LyricsDocument();
        document.language = "th"; document.provider = "fixture";
        for (String text : texts) {
            LyricsLine line = new LyricsLine(); line.text = text;
            line.startMs = document.lines.size() * 1000L; line.endMs = line.startMs + 900;
            document.lines.add(line);
        }
        return document;
    }

    private static AiSettings settings(Fixture context) {
        SettingsStore store = new SettingsStore(context);
        store.put(Settings.AI_ENABLED, true); store.put(Settings.AI_MODEL_GEMINI, "fixture-model");
        AiSettings settings = new AiSettings(store, null);
        require(!settings.canRequest(), "fixture has no credential");
        return settings;
    }

    // Seed the same exact identities consumed by the high-level production run owners.
    private static AiLayerRunner.Args args(Fixture context, AiSettings settings,
                                           CanonicalBase base, List<AiLine> rows, LayerKind layer) {
        String target = layer == LayerKind.SOUND ? "Latin" : "en";
        AiProviderConfig provider = settings.providerConfig(layer, target);
        AiIdentity.Config identity = new AiIdentity.Config();
        identity.layer = layer; identity.provider = settings.providerId();
        identity.providerVersion = provider.providerVersion; identity.modelName = provider.model.name;
        identity.targetLang = target; identity.promptVersion = provider.promptVersion;
        identity.instructions = settings.instructions(layer);
        if (layer == LayerKind.SOUND) {
            identity.endpoint = provider.endpoint; identity.targetOrthography = target;
            identity.sourceLanguage = base.language; identity.soundMode = "whole_line_v1";
            identity.soundBaselineMode = "raw_source_v1";
        }
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = new AiRunConfig(layer, AiIdentity.buildDocDigest(rows, AiLyricContext.EMPTY, false),
                AiIdentity.buildConfigId(identity), settings.providerId(), AiLyricContext.EMPTY,
                provider, identity.instructions, layer == LayerKind.SOUND ? base.language : null, null);
        args.rows = rows; args.store = new AiPaidRecords(context); args.useBaseline = false;
        return args;
    }

    private static void recovery(Fixture context, boolean length) {
        LyricsDocument document = document("สวัสดี / you", "รัก");
        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:fixture", document);
        AiLayerRunner.Args args = args(context, settings(context), base,
                AiRows.forSound(base, document, null, "Latin", false), LayerKind.SOUND);
        List<List<String>> requests = new ArrayList<>();
        args.provider = provider((request, config, signal) -> {
            List<String> sources = new ArrayList<>();
            for (AiRequestItem item : request.items) sources.add(item.source);
            requests.add(sources);
            if (length && requests.size() == 1) return answer("", AiFinishReason.LENGTH);
            JsonArray items = new JsonArray();
            for (AiRequestItem item : request.items) {
                String text = item.source.equals("สวัสดี") ? "sawatdi"
                        : item.source.equals("รัก") ? "rak" : "you";
                JsonObject output = new JsonObject(); output.addProperty("id", item.id);
                output.addProperty("t", text); items.add(output);
            }
            JsonObject output = new JsonObject(); output.add("items", items);
            return answer(output.toString(), AiFinishReason.STOP);
        });
        AiRunOutcome outcome = AiLayerRunner.run(args);
        require(outcome.kind == AiRunOutcome.Kind.COMPLETED && outcome.durable,
                "durable completion: " + outcome.kind + ":" + outcome.failureToken
                        + ":" + outcome.failureDetail + " requests=" + requests.size());
        require("sawatdi / you".equals(outcome.record.item(base.rows.get(0).rowId)), "mixed row output");
        require("rak".equals(outcome.record.item(base.rows.get(1).rowId)), "second row output");
        require(requests.size() == (length ? 3 : 1), "bounded attempt count");
        for (List<String> request : requests) {
            require(request.contains("สวัสดี") == request.contains("you"), "segments stay together");
        }
        args.store = new AiPaidRecords(context); args.allowProviderRequest = false;
        args.provider = noCalls();
        require(AiLayerRunner.run(args).kind == AiRunOutcome.Kind.REUSED, "SQLite reopen replay");
    }

    private static AiRunConfig seedPartial(Fixture context, AiSettings settings, CanonicalBase base,
                                           LyricsDocument document, SoundArtifact local, LayerKind layer) {
        List<AiLine> rows = layer == LayerKind.SOUND
                ? AiRows.forSound(base, document, local, "Latin", false)
                : AiRows.forMeaning(base, document, null, false);
        AiLayerRunner.Args args = args(context, settings, base, rows, layer);
        AiChunkPlanner.Input input = new AiChunkPlanner.Input(); input.rows = rows;
        input.layer = layer; input.target = args.config.targetLang; input.model = args.config.provider.model;
        AiPlannedChunk planned = AiChunkPlanner.plan(input).chunks.get(0);
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        record.planning = AiPlanningSnapshot.capture(input.model);
        AiChunkRecord chunk = AiChunkRecord.forChunk(planned);
        chunk.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
        chunk.fallbackRowIds.add(base.rows.get(2).rowId); record.putChunk(planned.id, chunk);
        for (AiLine row : rows) if (row.isSent() && !row.id.equals(base.rows.get(2).rowId)) {
            record.putItem(row.id, layer == LayerKind.SOUND ? "rak" : "love");
        }
        require(args.store.read(args.config) == null, "fresh identity");
        require(args.store.commit(args.config, record), "seed durable partial");
        return args.config;
    }

    private static void partialDisplay(Fixture context) {
        LyricsDocument document = document("สวัสดี", "รัก", "โลก");
        document.lines.get(2).providerTranslatedText = "world";
        document.lines.get(2).providerTranslationLanguage = "en";
        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:fixture", document);
        SoundEntry deterministic = new SoundEntry(base.rows.get(0).rowId, "sawatdi", "Latin", null,
                Collections.singletonList(new SoundEntry.SpanReading("local-span", 0, "sawatdi")));
        SoundArtifact local = new SoundArtifact(base.digest, "local",
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "fixture", "local", 1),
                Collections.singletonList(deterministic), true);
        AiSettings settings = settings(context);
        seedPartial(context, settings, base, document, local, LayerKind.SOUND);
        seedPartial(context, settings, base, document, local, LayerKind.MEANING);
        AiSoundRun.Result sound = AiSoundRun.run(context, settings, base, document, local,
                "Latin", false, true, new AiSignal(), null);
        AiMeaningRun.Result meaning = AiMeaningRun.run(context, settings, base, document, null,
                false, "en", true, new AiSignal(), null);
        require(sound.outcome.kind == AiRunOutcome.Kind.REUSED && sound.hasArtifact(), "Sound partial replay");
        require(meaning.outcome.kind == AiRunOutcome.Kind.REUSED && meaning.hasArtifact(), "Meaning partial replay");
        require(sound.artifact.partial && !meaning.outcome.record.isComplete(), "partial purchase stays explicit");
        require(com.eza.spicyex.lyrics.processing.AiMeaningDisplayFixture.select(base, document,
                meaning.artifact) == meaning.artifact, "required-row selection preserves usable Meaning");
        require(sound.artifact.sound(base.rows.get(0).rowId) == null, "local row excluded");
        require(sound.artifact.sound(base.rows.get(2).rowId) == null, "missing reading not invented");
        List<SoundEntry> composed = AiSoundOverlay.compose(Collections.singletonList(deterministic),
                Collections.singletonList(sound.artifact.sound(base.rows.get(1).rowId)));
        require(composed.get(0) == deterministic, "deterministic reading retained");
        SoundArtifact combined = new SoundArtifact(base.digest, sound.artifact.configId,
                sound.artifact.provenance, composed, true);
        LyricSession session = LyricSession.of(base, 1);
        session = session.withSound(session.sound.settled(combined, LayerFailure.NONE));
        session = session.withMeaning(session.meaning.settled(meaning.artifact, LayerFailure.NONE));
        LyricsDocument displayed = LegacyDocumentComposer.compose(document, session);
        require("sawatdi".equals(displayed.lines.get(0).romanizedText), "local display retained");
        require("rak".equals(displayed.lines.get(1).romanizedText), "accepted reading displayed");
        require("love".equals(displayed.lines.get(1).translatedText), "accepted Meaning displayed");
        require(displayed.lines.get(2).romanizedText.isEmpty()
                && displayed.lines.get(2).translatedText.isEmpty(), "omitted output stays absent");
        for (int index = 0; index < document.lines.size(); index++) {
            LyricsLine before = document.lines.get(index), after = displayed.lines.get(index);
            require(before.text.equals(after.text) && before.startMs == after.startMs
                    && before.endMs == after.endMs, "source and timing preserved");
            require(before.romanizedText.isEmpty() && before.translatedText.isEmpty(), "input remains unchanged");
        }
    }

    private static void corrupt(Fixture context) {
        AiSettings settings = settings(context);
        LyricsDocument document = document("สวัสดี / you");
        CanonicalBase base = CanonicalBase.fromDocument("spotify:track:fixture", document);
        AiLayerRunner.Args args = args(context, settings, base,
                AiRows.forSound(base, document, null, "Latin", false), LayerKind.SOUND);
        AiPaidRecord record = completeRecord(args);
        require(args.store.read(args.config) == null, "fresh corrupt fixture");
        for (String invalid : Arrays.asList("sawatdi", "สวัสดี / you")) {
            record.putItem(base.rows.get(0).rowId, invalid);
            require(args.store.commit(args.config, record), "corrupt fixture stored");
            require(new AiPaidRecords(context).read(args.config) != null, "corrupt fixture is readable");
            String before = AIPaidArtifactCache.read(context, args.config.recordIdentity()).payload;
            AiSoundRun.Result output = AiSoundRun.run(context, settings, base, document, null,
                    "Latin", false, true, new AiSignal(), null);
            require(!output.hasArtifact() && output.outcome.kind == AiRunOutcome.Kind.FAILED,
                    "corrupt stored output rejected: " + output.outcome.kind + ":"
                            + output.outcome.failureToken + ":" + output.outcome.failureDetail);
            require(before.equals(AIPaidArtifactCache.read(context, args.config.recordIdentity()).payload),
                    "rejection makes no replacement write");
        }
        AiLayerRunner.Args meaning = args(context, settings, base,
                AiRows.forMeaning(base, document), LayerKind.MEANING);
        AiPaidRecord missingSegment = completeRecord(meaning);
        missingSegment.putItem(base.rows.get(0).rowId, "hello");
        require(meaning.store.read(meaning.config) == null
                && meaning.store.commit(meaning.config, missingSegment), "seed incomplete Meaning");
        AiMeaningRun.Result translation = AiMeaningRun.run(context, settings, base, document, null,
                false, "en", true, new AiSignal(), null);
        require(!translation.hasArtifact() && translation.outcome.kind == AiRunOutcome.Kind.FAILED,
                "missing Meaning segment rejected");
        boolean rejected = false;
        try { AiResponseReader.readItems("{\"items\":[],\"items\":[]}"); }
        catch (AiProtocolException expected) { rejected = true; }
        require(rejected, "duplicate keys rejected on Android");
    }

    private static AiPaidRecord completeRecord(AiLayerRunner.Args args) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = args.rows; input.layer = args.config.layer;
        input.target = args.config.targetLang; input.model = args.config.provider.model;
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        record.planning = AiPlanningSnapshot.capture(input.model);
        for (AiPlannedChunk planned : AiChunkPlanner.plan(input).chunks) {
            AiChunkRecord chunk = AiChunkRecord.forChunk(planned);
            chunk.status = AiChunkRecord.Status.COMPLETE;
            record.putChunk(planned.id, chunk);
        }
        record.status = AiPaidRecord.Status.COMPLETE;
        return record;
    }

    private static void selectiveDeletion(Fixture context) {
        AiRunConfig[] configs = new AiRunConfig[2];
        AiSettings settings = settings(context);
        for (int index = 0; index < 2; index++) {
            LyricsDocument document = document(index == 0 ? "สวัสดี" : "รัก");
            CanonicalBase base = CanonicalBase.fromDocument("spotify:track:fixture", document);
            AiLayerRunner.Args args = args(context, settings, base,
                    AiRows.forMeaning(base, document), LayerKind.MEANING);
            configs[index] = args.config;
            AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
            require(args.store.read(args.config) == null && args.store.commit(args.config, record), "seed identity");
        }
        AiPaidRecords stale = new AiPaidRecords(context);
        AiPaidRecord prior = stale.read(configs[0]);
        String sentinel = AIPaidArtifactCache.read(context, configs[1].recordIdentity()).payload;
        String entryKey = AIPaidArtifactCache.entryKey(configs[0].recordIdentity());
        require(AIPaidArtifactCache.entryList(context).contains(entryKey), "selected key is discoverable");
        require(!AIPaidArtifactCache.removeEntry(context, "paid-v1|*"), "broad key rejected");
        require(AIPaidArtifactCache.removeEntry(context, entryKey), "exact entry deletion succeeds");
        require(new AiPaidRecords(context).read(configs[0]) == null, "selected identity removed");
        require(sentinel.equals(AIPaidArtifactCache.read(context, configs[1].recordIdentity()).payload),
                "other identity unchanged");
        require(!stale.commit(configs[0], prior), "stale writer cannot recreate deleted record");
    }

    private interface Answer { AiProviderResult generate(AiProviderRequest request, AiProviderConfig config, AiSignal signal); }
    private static AiProvider provider(Answer answer) {
        return new AiProvider() {
            public String id() { return "fixture"; }
            public AiModelListResult listModels(AiSignal signal) { throw new AssertionError("no discovery"); }
            public AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config, AiSignal signal) {
                return answer.generate(request, config, signal);
            }
        };
    }
    private static AiProvider noCalls() { return provider((request, config, signal) -> { throw new AssertionError("no request"); }); }
    private static AiProviderResult answer(String json, AiFinishReason finish) {
        return AiProviderResult.ok(json, AiUsage.of(7, 3), finish, AiText.utf8Bytes(json));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** No database, preference migration, or settings access may escape the disposable context. */
    private static final class Fixture extends ContextWrapper implements AutoCloseable {
        private final String prefix = "ai-recovery-" + UUID.randomUUID();
        private final File root;
        private final Set<String> preferenceNames = new HashSet<>();
        private final Field database;
        Fixture(Context module) throws Exception {
            super(module);
            require(module.getPackageName().equals("com.eza.spicyex"), "requires module test process");
            database = AIPaidArtifactCache.class.getDeclaredField("database"); database.setAccessible(true);
            require(database.get(null) == null, "fixture cannot replace an active ledger");
            root = new File(module.getCacheDir(), prefix); require(root.mkdirs(), "fixture directory");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getCacheDir() { return root; }
        @Override public File getFilesDir() { return root; }
        @Override public File getDatabasePath(String name) {
            require(name != null && !name.contains("/") && !name.contains("\\") && !name.equals(".."), "scoped name");
            return new File(root, name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                android.database.DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getAbsolutePath(), factory, handler);
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            String fixtureName = prefix + "-" + name;
            preferenceNames.add(fixtureName);
            return getBaseContext().getSharedPreferences(fixtureName, mode);
        }
        @Override public Object getSystemService(String name) { return null; }
        @Override public void close() throws Exception {
            SQLiteOpenHelper helper = (SQLiteOpenHelper) database.get(null);
            if (helper != null) helper.close(); database.set(null, null);
            for (String name : preferenceNames) {
                require(getBaseContext().deleteSharedPreferences(name), "fixture preference cleanup");
            }
            remove(root);
        }
        private static void remove(File file) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
            require(file.delete(), "fixture cleanup");
        }
    }
}
