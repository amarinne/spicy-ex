package com.eza.spicyex.lyrics;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Map;

/**
 * SQLite backing for the lyric caches, replacing one SharedPreferences file per cache.
 *
 * <p>SharedPreferences is a settings store: the whole file is parsed into a HashMap on first
 * access and that map is held, per process, for as long as the process lives. Used for settings
 * that is fine. Used for lyric payloads it meant the caches were permanently resident in the same
 * 512MB-capped heap the fullscreen renderer allocates from - measured on a real device at 50.3MB
 * across five files, against a default budget that allows a good deal more. The paid-AI cache had
 * already been moved to SQLite for exactly this reason (see AIPaidArtifactCache, "v515"); this is
 * the rest of them.
 *
 * <p>Here a read touches one row and holds nothing afterwards, so the resident cost of a cache is
 * no longer a function of how much is in it.
 *
 * <p>One table, namespaced, so the five caches share a connection and an eviction implementation
 * rather than repeating both. Eviction is least-recently-updated within a namespace, which also
 * retires the hand-maintained "__cache_order" index strings the preferences version had to parse
 * and rewrite on every single write.
 */
public final class SpicyCacheStore {
    private static final String DATABASE = "SpicyLyricCaches.db";
    private static final int VERSION = 1;
    private static final String TABLE = "entries";
    private static final String TABLE_META = "meta";

    /** Namespaces. The values double as the legacy SharedPreferences file names to import from. */
    public static final String GOOGLE = "SpotifyPlusNativeSpicyGoogleCache";
    public static final String PROCESSED = "SpotifyPlusNativeSpicyProcessedCache";
    public static final String SOUND = "SpotifyPlusSoundArtifactCache";
    public static final String MEANING = "SpotifyPlusMeaningArtifactCache";
    public static final String DETECTION = "SpotifyPlusDetectionArtifactCache";
    public static final String CANONICAL = "SpotifyPlusCanonicalSourceCache";
    public static final String RESPONSES = "SpotifyPlusLyricsResponseCache";

    /** Bookkeeping keys the preferences format kept inline with real entries. Never imported. */
    private static final String[] LEGACY_INDEX_KEYS = {"__cache_order", "__paid_index"};

    private static volatile Helper helper;

    private SpicyCacheStore() {
    }

    private static Helper helper(Context context) {
        Helper local = helper;
        if (local != null) return local;
        synchronized (SpicyCacheStore.class) {
            if (helper == null) helper = new Helper(context.getApplicationContext());
            return helper;
        }
    }

    public static String get(Context context, String namespace, String key) {
        if (context == null || key == null) return null;
        try {
            Helper db = helper(context);
            db.importLegacyOnce(namespace);
            try (Cursor cursor = db.getReadableDatabase().query(TABLE, new String[]{"value"},
                    "namespace = ? AND entry_key = ?", new String[]{namespace, key},
                    null, null, null, "1")) {
                return cursor.moveToFirst() ? cursor.getString(0) : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes one entry, evicting least-recently-updated entries in the same namespace until it
     * fits. Returns false when the value cannot fit the quota even after evicting everything else,
     * matching the preferences implementation's refusal rather than blowing the budget.
     */
    public static boolean put(Context context, String namespace, String key, String value,
                              long quotaBytes) {
        if (context == null || key == null || value == null) return false;
        try {
            Helper db = helper(context);
            db.importLegacyOnce(namespace);
            long bytes = value.length();
            SQLiteDatabase writable = db.getWritableDatabase();
            synchronized (SpicyCacheStore.class) {
                if (quotaBytes != CacheStoragePolicy.UNLIMITED) {
                    if (bytes > quotaBytes) return false;
                    writable.delete(TABLE, "namespace = ? AND entry_key = ?",
                            new String[]{namespace, key});
                    evictUntilFits(writable, namespace, bytes, quotaBytes);
                }
                ContentValues values = new ContentValues();
                values.put("namespace", namespace);
                values.put("entry_key", key);
                values.put("value", value);
                values.put("value_bytes", bytes);
                values.put("updated_at_ms", System.currentTimeMillis());
                writable.replaceOrThrow(TABLE, null, values);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void evictUntilFits(SQLiteDatabase db, String namespace, long incomingBytes,
                                       long quotaBytes) {
        long used = usedBytes(db, namespace);
        while (used + incomingBytes > quotaBytes) {
            String oldest = null;
            long oldestBytes = 0;
            try (Cursor cursor = db.query(TABLE, new String[]{"entry_key", "value_bytes"},
                    "namespace = ?", new String[]{namespace}, null, null,
                    "updated_at_ms ASC, entry_key ASC", "1")) {
                if (!cursor.moveToFirst()) return;
                oldest = cursor.getString(0);
                oldestBytes = cursor.getLong(1);
            }
            db.delete(TABLE, "namespace = ? AND entry_key = ?", new String[]{namespace, oldest});
            used -= oldestBytes;
        }
    }

    public static void remove(Context context, String namespace, String key) {
        if (context == null || key == null) return;
        try {
            helper(context).getWritableDatabase().delete(TABLE,
                    "namespace = ? AND entry_key = ?", new String[]{namespace, key});
        } catch (Throwable ignored) {
        }
    }

    public static void clear(Context context, String namespace) {
        if (context == null) return;
        try {
            Helper db = helper(context);
            // Marks the legacy import done as well, so clearing does not invite the old XML back.
            db.markImported(namespace);
            db.getWritableDatabase().delete(TABLE, "namespace = ?", new String[]{namespace});
            context.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    public static long usageBytes(Context context, String namespace) {
        if (context == null) return 0L;
        try {
            Helper db = helper(context);
            db.importLegacyOnce(namespace);
            return usedBytes(db.getReadableDatabase(), namespace);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static int entryCount(Context context, String namespace) {
        if (context == null) return 0;
        try {
            Helper db = helper(context);
            db.importLegacyOnce(namespace);
            return (int) DatabaseUtils.longForQuery(db.getReadableDatabase(),
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE namespace = ?",
                    new String[]{namespace});
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long usedBytes(SQLiteDatabase db, String namespace) {
        try {
            return DatabaseUtils.longForQuery(db,
                    "SELECT COALESCE(SUM(value_bytes), 0) FROM " + TABLE + " WHERE namespace = ?",
                    new String[]{namespace});
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        private final Context context;

        Helper(Context context) {
            super(context, DATABASE, null, VERSION);
            this.context = context;
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "namespace TEXT NOT NULL,"
                    + "entry_key TEXT NOT NULL,"
                    + "value TEXT NOT NULL,"
                    + "value_bytes INTEGER NOT NULL,"
                    + "updated_at_ms INTEGER NOT NULL,"
                    + "PRIMARY KEY (namespace, entry_key))");
            // Eviction always reads oldest-first within one namespace; without this that is a
            // full scan of every cache's rows on every write that needs room.
            db.execSQL("CREATE INDEX idx_entries_lru ON " + TABLE + " (namespace, updated_at_ms)");
            db.execSQL("CREATE TABLE " + TABLE_META + " ("
                    + "meta_key TEXT PRIMARY KEY NOT NULL,"
                    + "meta_value TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Caches are rebuildable, so a schema change drops them rather than risking a
            // half-translated import.
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_META);
            onCreate(db);
        }

        /**
         * Moves one namespace's existing SharedPreferences file in, once, then deletes it. Reading
         * the old file costs exactly the resident memory this change exists to avoid, so it
         * happens once and the file does not survive to be loaded again.
         */
        void importLegacyOnce(String namespace) {
            SQLiteDatabase db = getWritableDatabase();
            if (hasMeta(db, importKey(namespace))) return;
            synchronized (SpicyCacheStore.class) {
                if (hasMeta(db, importKey(namespace))) return;
                int imported = 0;
                try {
                    SharedPreferences legacy =
                            context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
                    Map<String, ?> all = legacy.getAll();
                    db.beginTransaction();
                    try {
                        long now = System.currentTimeMillis();
                        for (Map.Entry<String, ?> entry : all.entrySet()) {
                            if (isLegacyIndexKey(entry.getKey())) continue;
                            Object value = entry.getValue();
                            if (!(value instanceof String)) continue;
                            ContentValues row = new ContentValues();
                            row.put("namespace", namespace);
                            row.put("entry_key", entry.getKey());
                            row.put("value", (String) value);
                            row.put("value_bytes", ((String) value).length());
                            row.put("updated_at_ms", now);
                            db.replaceOrThrow(TABLE, null, row);
                            imported++;
                        }
                        markImported(db, namespace, imported);
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                    legacy.edit().clear().apply();
                } catch (Throwable t) {
                    // A cache that fails to import is a cache that refills itself. Mark it done so
                    // a broken file cannot make every future read pay for reparsing it.
                    try {
                        markImported(db, namespace, imported);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        void markImported(String namespace) {
            try {
                markImported(getWritableDatabase(), namespace, 0);
            } catch (Throwable ignored) {
            }
        }

        private static void markImported(SQLiteDatabase db, String namespace, int count) {
            ContentValues marker = new ContentValues();
            marker.put("meta_key", importKey(namespace));
            marker.put("meta_value", String.valueOf(count));
            db.replaceOrThrow(TABLE_META, null, marker);
        }

        private static String importKey(String namespace) {
            return "imported:" + namespace;
        }

        private static boolean hasMeta(SQLiteDatabase db, String key) {
            try {
                return DatabaseUtils.longForQuery(db,
                        "SELECT COUNT(*) FROM " + TABLE_META + " WHERE meta_key = ?",
                        new String[]{key}) > 0;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    static boolean isLegacyIndexKey(String key) {
        for (String reserved : LEGACY_INDEX_KEYS) {
            if (reserved.equals(key)) return true;
        }
        return false;
    }
}
