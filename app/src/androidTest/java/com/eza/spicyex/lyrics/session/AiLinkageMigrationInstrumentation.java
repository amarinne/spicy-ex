package com.eza.spicyex.lyrics.session;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiContract;
import com.eza.spicyex.lyrics.ai.AiIdentity;
import com.eza.spicyex.lyrics.ai.AiReplayIdentity;
import com.eza.spicyex.lyrics.ai.AiSourceBinding;
import com.eza.spicyex.lyrics.catalog.CatalogSchema;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Real SQLite migrations use disposable module-cache fixtures, never Spotify databases. */
public final class AiLinkageMigrationInstrumentation {
    private static final int CASES = 8;
    private static final long LIMIT = 32L * 1024 * 1024;
    private AiLinkageMigrationInstrumentation() { }

    public static void run(Instrumentation instrumentation) {
        Bundle result = new Bundle();
        int completed = 0;
        String failure = "";
        String[] names = {"v1 paid byte preservation", "wrapper identity exclusions", "large blob migration",
                "entry deletion fence", "layer deletion fence", "all deletion fence",
                "catalog v2 router migration", "org route retention"};
        for (int index = 0; index < names.length; index++) {
            Bundle status = new Bundle();
            status.putString("class", AiLinkageMigrationInstrumentation.class.getName());
            status.putString("test", names[index]);
            status.putInt("numtests", CASES);
            status.putInt("current", index + 1);
            instrumentation.sendStatus(1, status);
            try (Fixture context = new Fixture(instrumentation.getTargetContext())) {
                if (index == 0) preserved(context);
                else if (index == 1) excluded(context);
                else if (index == 2) large(context);
                else if (index < 6) deletion(context, index - 3);
                else catalog(context, index == 7);
                completed++;
                instrumentation.sendStatus(0, status);
            } catch (Throwable error) {
                failure += names[index] + ": " + error + "\n";
                status.putString("stack", error.toString());
                instrumentation.sendStatus(-2, status);
            }
        }
        result.putInt("expectedCases", CASES);
        result.putInt("completedCases", completed);
        result.putString("stream", failure.isEmpty() ? "PASS: 8 native AI linkage cases\n" : "FAIL: " + failure);
        instrumentation.finish(failure.isEmpty() ? -1 : 0, result);
    }

    private static void preserved(Fixture context) {
        PaidArtifactIdentity identity = identity(LayerKind.MEANING, "legacy");
        byte[] valid = wrapper(identity);
        Map<String, byte[]> rows = new HashMap<>();
        rows.put(AIPaidArtifactCache.entryKey(identity), valid);
        rows.put("unsupported", "{\"schema\":99,\"payload\":\"old purchased output\"}".getBytes(StandardCharsets.UTF_8));
        rows.put("corrupt", new byte[]{0, 1, 2, (byte) 0xff});
        seedPaid(context, rows);
        try (AIPaidArtifactDatabase helper = new AIPaidArtifactDatabase(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            eq(2, db.getVersion(), "paid version");
            eq(3, count(db, "paid_entries"), "all paid entries");
            for (Map.Entry<String, byte[]> row : rows.entrySet()) bytes(row.getValue(), helper.get(row.getKey()));
            eq(1, count(db, "paid_replay"), "only valid replay indexed");
            eq(1, count(db, "reservations"), "reservation preserved");
            eq("sentinel", scalar(db, "SELECT meta_value FROM meta WHERE meta_key='fixture'"), "meta preserved");
            JsonObject index = AiReplayIdentity.index(AIPaidArtifactCache.entryKey(identity), new String(valid, StandardCharsets.UTF_8));
            check(index != null, "legacy request descriptor reconstructed");
            String payload = helper.replay("MEANING", "legacy", index.get("digest").getAsString(), false);
            eq(JsonParser.parseString(new String(valid, StandardCharsets.UTF_8)).getAsJsonObject().get("payload").getAsString(), payload, "replay output");
            eq("ok", scalar(db, "PRAGMA integrity_check"), "paid integrity");
            PaidArtifactIdentity newer = new PaidArtifactIdentity(identity.layerKind, "newer-doc",
                    identity.providerId, identity.modelId, identity.promptContractId);
            AIPaidArtifactCache.Read newerRead = helper.read(AIPaidArtifactCache.entryKey(newer), "MEANING", newer);
            check(helper.put(AIPaidArtifactCache.entryKey(newer), "MEANING", wrapper(newer), null,
                    newerRead, LIMIT).durable, "newer compatible purchase");
            ContentValues corrupt = new ContentValues();
            corrupt.put("raw_value", new byte[]{0, 1}); corrupt.put("raw_bytes", 2);
            corrupt.put("updated_at_ms", Long.MAX_VALUE);
            db.update("paid_entries", corrupt, "entry_key = ?", new String[]{AIPaidArtifactCache.entryKey(newer)});
            eq(payload, helper.replay("MEANING", "legacy", index.get("digest").getAsString(), false),
                    "newest corruption cannot hide older paid output");
            helper.remove(AIPaidArtifactCache.entryKey(identity));
            boolean blocked = false;
            try { helper.replay("MEANING", "legacy", index.get("digest").getAsString(), false); }
            catch (IllegalStateException unresolved) { blocked = true; }
            check(blocked, "unreadable compatible candidate must block repurchase");
        }
    }

    private static void excluded(Fixture context) {
        Map<String, byte[]> rows = new HashMap<>();
        String[] fields = {"schema", "layer", "canonicalDigest", "providerId", "modelId", "promptContractId"};
        for (String field : fields) {
            PaidArtifactIdentity id = identity(LayerKind.MEANING, field);
            JsonObject value = JsonParser.parseString(new String(wrapper(id), StandardCharsets.UTF_8)).getAsJsonObject();
            if (field.equals("schema")) value.addProperty(field, 999);
            else value.addProperty(field, "foreign");
            rows.put(AIPaidArtifactCache.entryKey(id), value.toString().getBytes(StandardCharsets.UTF_8));
        }
        rows.put("wrong-entry-key", wrapper(identity(LayerKind.MEANING, "wrong-key")));
        seedPaid(context, rows);
        try (AIPaidArtifactDatabase helper = new AIPaidArtifactDatabase(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            eq(0, count(db, "paid_replay"), "foreign headers and key excluded");
            eq(7, count(db, "paid_entries"), "foreign paid records retained");
            for (Map.Entry<String, byte[]> row : rows.entrySet()) bytes(row.getValue(), helper.get(row.getKey()));
            PaidArtifactIdentity corrupt = identity(LayerKind.MEANING, "schema");
            check(!helper.read(AIPaidArtifactCache.entryKey(corrupt), "MEANING", corrupt).admissionSafe,
                    "corrupt wrapper cannot authorize repurchase");
            ContentValues brokenSize = new ContentValues();
            brokenSize.put("raw_bytes", Long.MAX_VALUE);
            db.update("paid_entries", brokenSize, "entry_key = ?",
                    new String[]{AIPaidArtifactCache.entryKey(corrupt)});
            AIPaidArtifactCache.Read unreadable = helper.read(AIPaidArtifactCache.entryKey(corrupt), "MEANING", corrupt);
            check(!unreadable.admissionSafe, "unreadable existing row is not absence");
            check(!helper.reserve(AIPaidArtifactCache.entryKey(corrupt), "MEANING", 4096, null,
                    unreadable, LIMIT).accepted(), "unreadable size cannot authorize storage admission");
            brokenSize.put("raw_bytes", 1);
            db.update("paid_entries", brokenSize, "entry_key = ?",
                    new String[]{AIPaidArtifactCache.entryKey(corrupt)});
            check(!helper.read(AIPaidArtifactCache.entryKey(corrupt), "MEANING", corrupt).admissionSafe,
                    "short recorded size cannot authorize repurchase");
        }
    }

    private static void large(Fixture context) {
        byte[] value = new byte[4 * 1024 * 1024 + 17];
        for (int i = 0; i < value.length; i++) value[i] = (byte) (i % 251);
        Map<String, byte[]> rows = new HashMap<>(); rows.put("large-unsupported", value);
        seedPaid(context, rows);
        try (AIPaidArtifactDatabase helper = new AIPaidArtifactDatabase(context)) {
            helper.getWritableDatabase();
            bytes(value, helper.get("large-unsupported"));
            eq(value.length, helper.usageBytes(), "large blob retained outside CursorWindow");
        }
    }

    private static void deletion(Fixture context, int kind) {
        PaidArtifactIdentity meaning = identity(LayerKind.MEANING, "delete");
        PaidArtifactIdentity sound = identity(LayerKind.SOUND, "other");
        String key = AIPaidArtifactCache.entryKey(meaning);
        byte[] raw = wrapper(meaning);
        Map<String, byte[]> rows = new HashMap<>(); rows.put(key, raw);
        rows.put(AIPaidArtifactCache.entryKey(sound), wrapper(sound));
        seedPaid(context, rows);
        try (AIPaidArtifactDatabase helper = new AIPaidArtifactDatabase(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            AIPaidArtifactCache.Read read = helper.read(key, "MEANING", meaning);
            AIPaidArtifactCache.Reservation reservation = helper.reserve(key, "MEANING", 4096, null, read, LIMIT);
            check(reservation.accepted(), "fixture reservation");
            String digest = AiReplayIdentity.index(key, new String(raw, StandardCharsets.UTF_8)).get("digest").getAsString();
            LyricsDocument document = document("spicy_org", "apple_music");
            document.catalogCandidateId = "candidate";
            AiSourceBinding binding = new AiSourceBinding(CanonicalBase.fromDocument("spotify:track:fixture", document), document);
            helper.bind(key, binding, "MEANING", "delete", digest);
            eq(1, count(db, "paid_bindings"), "binding inserted");
            eq("spicy_org", scalar(db, "SELECT route FROM paid_bindings"), "route retained");
            eq("apple_music", scalar(db, "SELECT upstream_source FROM paid_bindings"), "upstream retained");
            if (kind == 0) helper.remove(key);
            else if (kind == 1) helper.clearLayer("MEANING");
            else helper.clear();
            eq(0, count(db, "paid_bindings"), "binding deleted");
            eq(kind == 2 ? 0 : 1, count(db, "paid_replay"), "replay delete scope");
            eq(kind == 2 ? 0 : 1, count(db, "paid_entries"), "paid delete scope");
            check(!helper.put(key, "MEANING", raw, reservation, read, LIMIT).durable, "stale write fenced");
            check(!helper.reserve(key, "MEANING", 4096, reservation, read, LIMIT).accepted(), "stale reserve fenced");
            helper.bind(key, binding, "MEANING", "delete", digest);
            eq(0, count(db, "paid_bindings"), "binding cannot recreate cleared entry");
            check(helper.replay("MEANING", "delete", digest, false) == null, "deleted replay unavailable");
        }
    }

    private static void catalog(Fixture context, boolean expiry) throws Exception {
        long now = System.currentTimeMillis();
        SQLiteDatabase legacy = context.openOrCreateDatabase(CatalogSchema.DATABASE, 0, null);
        for (String sql : CatalogSchema.createAll()) {
            if (sql.contains("idx_candidates_upstream")) continue;
            legacy.execSQL(sql.replace("upstream_source TEXT NOT NULL DEFAULT 'unknown',", ""));
        }
        List<String> upstreams = Arrays.asList("apple_music", "spicy_lyrics", "spotify");
        for (String upstream : upstreams) seedCandidate(legacy, "org-" + upstream, "spicy_org", upstream, now);
        seedCandidate(legacy, "direct-apple", "apple", "", now);
        seedCandidate(legacy, "direct-spotify", "spotify_native", "", now);
        legacy.execSQL("INSERT INTO selection VALUES ('fixture','MANUAL','org-apple_music','spicy_org','fixture','old-digest')");
        legacy.setVersion(2); legacy.close();
        Class<?> helperClass = Class.forName("com.eza.spicyex.lyrics.catalog.CatalogStore$Helper");
        Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        try (SQLiteOpenHelper helper = (SQLiteOpenHelper) constructor.newInstance(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            eq(3, db.getVersion(), "catalog version");
            eq(5, count(db, "candidates"), "all candidate IDs retained");
            for (String upstream : upstreams) {
                String id = "org-" + upstream;
                eq(upstream, scalar(db, "SELECT upstream_source FROM candidates WHERE candidate_id='" + id + "'"), "upstream backfill");
                try (Cursor row = db.rawQuery("SELECT raw_payload,canonical_digest,normalized_document FROM candidates WHERE candidate_id=?", new String[]{id})) {
                    check(row.moveToFirst(), "historical candidate ID retained");
                    bytes(("raw-" + id).getBytes(StandardCharsets.UTF_8), row.getBlob(0));
                    check(!"old-digest".equals(row.getString(1)), "org digest updates");
                    eq(row.getString(1), JsonParser.parseString(row.getString(2)).getAsJsonObject().get("canonicalDigest").getAsString(), "normalized digest agrees");
                }
            }
            eq("MANUAL", scalar(db, "SELECT mode FROM selection"), "manual pin mode");
            eq("org-apple_music", scalar(db, "SELECT candidate_id FROM selection"), "historical manual pin");
            eq(scalar(db, "SELECT canonical_digest FROM candidates WHERE candidate_id='org-apple_music'"),
                    scalar(db, "SELECT last_accepted_digest FROM selection"), "accepted pin digest repaired");
            eq("old-digest", scalar(db, "SELECT canonical_digest FROM candidates WHERE candidate_id='direct-apple'"), "direct digest unchanged");
            eq("apple_music", scalar(db, "SELECT upstream_source FROM candidates WHERE candidate_id='direct-apple'"), "direct Apple upstream");
            eq("spotify", scalar(db, "SELECT upstream_source FROM candidates WHERE candidate_id='direct-spotify'"), "direct Spotify upstream");
            if (expiry) {
                db.execSQL("UPDATE candidates SET fetched_at_ms=1");
                Class<?> store = Class.forName("com.eza.spicyex.lyrics.catalog.CatalogStore");
                Method purge = store.getDeclaredMethod("purgeExpiredSpicy", SQLiteDatabase.class, long.class);
                purge.setAccessible(true);
                eq(3, ((Number) purge.invoke(null, db, now)).longValue(), "all routed upstreams expire");
                eq(2, count(db, "candidates"), "direct providers retained");
                eq(0, DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM candidates WHERE source_id='spicy_org'", null), "org route removed");
            }
            eq("ok", scalar(db, "PRAGMA integrity_check"), "catalog integrity");
        }
    }

    private static void seedCandidate(SQLiteDatabase db, String id, String route, String upstream, long fetchedAt) {
        LyricsDocument document = document(route, upstream);
        document.spicyOrgFetchedAtMs = fetchedAt;
        ContentValues row = new ContentValues();
        row.put("candidate_id", id); row.put("track_id", "fixture"); row.put("source_id", route);
        row.put("provider_item_id", "fixture"); row.put("match_method", "EXACT_ID"); row.put("match_confidence", 1.0);
        row.put("duration_delta_ms", 0); row.put("timing_level", "UNSYNCED"); row.put("complete", 1); row.put("timing_healthy", 1);
        for (String flag : new String[]{"has_provider_translation", "has_provider_transliteration", "has_background_vocals", "has_duet", "has_credits"}) row.put(flag, 0);
        row.put("canonical_digest", "old-digest"); row.put("normalized_document", CanonicalSourceCodec.encode(document, 1, "old-digest", fetchedAt));
        row.put("provider_transliteration", ""); row.put("raw_payload", ("raw-" + id).getBytes(StandardCharsets.UTF_8));
        row.put("parser_revision", 1); row.put("adapter_revision", 1); row.put("fetched_at_ms", fetchedAt);
        db.insertOrThrow("candidates", null, row);
    }

    private static LyricsDocument document(String route, String upstream) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "fixture"; document.fetchSource = route; document.provider = route;
        document.spicyOrgSource = upstream;
        LyricsLine line = new LyricsLine(); line.text = "fixture original"; document.lines.add(line);
        return document;
    }

    private static PaidArtifactIdentity identity(LayerKind layer, String config) {
        return AiIdentity.recordIdentity(layer, "doc-" + config, "fixture-provider", "fixture-model", config);
    }

    /** An existing paid record with retained legacy request JSON and no new replay descriptor. */
    private static byte[] wrapper(PaidArtifactIdentity identity) {
        String config = identity.promptContractId.substring(identity.promptContractId.lastIndexOf("|cfg=") + 5);
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1); payload.addProperty("layer", identity.layerKind.name());
        payload.addProperty("docDigest", identity.canonicalDigest); payload.addProperty("configId", config);
        payload.addProperty("providerId", identity.providerId); payload.addProperty("modelName", identity.modelId);
        payload.addProperty("targetLang", "en"); payload.addProperty("schemaVersion", AiContract.schemaFor(identity.layerKind));
        payload.addProperty("chunkPlanVersion", AiContract.CHUNK_PLAN_VERSION); payload.addProperty("status", "COMPLETE");
        payload.add("items", JsonParser.parseString("{\"r1\":\"fixture accepted output\"}"));
        if (identity.layerKind == LayerKind.SOUND) {
            JsonObject replay = JsonParser.parseString("{\"version\":1,\"baseline\":false,\"ids\":[\"r1\"],\"rows\":[{\"class\":\"ordinary\",\"voice\":null}]}").getAsJsonObject();
            replay.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("textHash", Digests.sha256("fixture original"));
            payload.add("replay", replay);
        }
        JsonObject chunk = new JsonObject();
        chunk.add("ids", JsonParser.parseString("[\"r1\"]")); chunk.addProperty("status", "COMPLETE");
        chunk.addProperty("requestJson", "{\"context\":{},\"items\":[{\"id\":\"r1\",\"s\":\"fixture original\",\"c\":\"ordinary\",\"v\":null}]}");
        JsonObject chunks = new JsonObject(); chunks.add("chunk", chunk); payload.add("chunks", chunks);
        JsonObject wrapper = AIPaidArtifactCache.header(identity); wrapper.addProperty("payload", payload.toString());
        return wrapper.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void seedPaid(Fixture context, Map<String, byte[]> rows) {
        try (SQLiteDatabase db = context.openOrCreateDatabase("SpicyPaidAiLedger.db", 0, null)) {
            db.execSQL("CREATE TABLE paid_entries (entry_key TEXT PRIMARY KEY NOT NULL,layer TEXT NOT NULL DEFAULT '',raw_value BLOB NOT NULL,raw_bytes INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE reservations (token TEXT PRIMARY KEY NOT NULL,entry_key TEXT UNIQUE NOT NULL,layer TEXT NOT NULL DEFAULT '',reserved_bytes INTEGER NOT NULL,touched_at_ms INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE meta (meta_key TEXT PRIMARY KEY NOT NULL,meta_value TEXT NOT NULL)");
            for (Map.Entry<String, byte[]> entry : rows.entrySet()) {
                ContentValues row = new ContentValues(); row.put("entry_key", entry.getKey());
                row.put("layer", entry.getKey().contains("sound") ? "SOUND" : "MEANING");
                try { row.put("layer", JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject().get("layer").getAsString()); }
                catch (RuntimeException ignored) { }
                row.put("raw_value", entry.getValue()); row.put("raw_bytes", entry.getValue().length); row.put("updated_at_ms", 123);
                db.insertOrThrow("paid_entries", null, row);
            }
            db.execSQL("INSERT INTO reservations VALUES ('fixture-reservation','unrelated','SOUND',4096,?)", new Object[]{System.currentTimeMillis()});
            db.execSQL("INSERT INTO meta VALUES ('fixture','sentinel')"); db.setVersion(1);
        }
    }

    private static long count(SQLiteDatabase db, String table) { return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM " + table, null); }
    private static String scalar(SQLiteDatabase db, String sql) { return DatabaseUtils.stringForQuery(db, sql, null); }
    private static void bytes(byte[] expected, byte[] actual) { check(Arrays.equals(expected, actual), "raw bytes changed"); }
    private static void eq(long expected, long actual, String message) { check(expected == actual, message + ": " + expected + " != " + actual); }
    private static void eq(String expected, String actual, String message) { check(expected.equals(actual), message + ": " + expected + " != " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** SQLiteOpenHelper must never unwrap this context or resolve a production database name. */
    private static final class Fixture extends ContextWrapper implements AutoCloseable {
        private final File root;
        private final Map<String, SharedPreferences> preferences = new HashMap<>();
        Fixture(Context module) {
            super(module);
            check(module.getPackageName().startsWith("com.eza.spicyex"), "fixture requires module context");
            root = new File(module.getCacheDir(), "ai-linkage-fixture-" + UUID.randomUUID());
            check(root.mkdirs(), "fixture root creation");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getCacheDir() { return root; }
        @Override public File getFilesDir() { return root; }
        @Override public File getDatabasePath(String name) {
            check(name != null && !name.contains("/") && !name.contains("\\") && !name.equals(".."), "scoped database name");
            return new File(root, name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                            android.database.DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getAbsolutePath(), factory, handler);
        }
        @Override public boolean deleteDatabase(String name) { return SQLiteDatabase.deleteDatabase(getDatabasePath(name)); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences.computeIfAbsent(name, key -> new MemoryPreferences());
        }
        @Override public Object getSystemService(String name) { return null; }
        @Override public void close() {
            for (SharedPreferences prefs : preferences.values()) prefs.edit().clear().commit();
            remove(root);
        }
        private static void remove(File file) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
            check(file.delete(), "fixture cleanup: " + file.getName());
        }
    }
    /** No preference access escapes the fixture, including through legacy migration. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value == null ? fallback : new HashSet<>((Set<String>) value);
        }
        @Override public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        @Override public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> changes = new HashMap<>();
                private boolean clear;
                @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { changes.put(key, value == null ? null : new HashSet<>(value)); return this; }
                @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
                @Override public Editor remove(String key) { changes.put(key, null); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() {
                    if (clear) values.clear();
                    for (Map.Entry<String, Object> entry : changes.entrySet()) {
                        if (entry.getValue() == null) values.remove(entry.getKey());
                        else values.put(entry.getKey(), entry.getValue());
                    }
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }
    }

}
