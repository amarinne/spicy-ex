package com.eza.spicyex.auto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import com.eza.spicyex.BuildConfig;

/** Caller-scoped transport for Android Auto lyrics. No lyric persistence. */
public final class AutoPrototypeProvider extends ContentProvider {
    static AutoPrototypeProvider instance;
    public static final Uri URI = Uri.parse("content://com.eza.spicyex.autoprototype/session");
    private Bundle state = new Bundle();
    private Bundle host = new Bundle();
    private boolean enabled, stress;

    @Override public boolean onCreate() {
        instance = this;
        getContext().grantUriPermission("com.spotify.music", URI, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getContext().grantUriPermission("com.google.android.projection.gearhead", URI, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        enabled = getContext().getSharedPreferences("auto-prototype", 0).getBoolean("enabled", false);
        return true;
    }

    private boolean caller(String name) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String p : packages) if (name.equals(p)) return true;
        return false;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean shell = Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0;
        boolean spotify = caller("com.spotify.music"), auto = caller("com.google.android.projection.gearhead");
        if (!shell && !spotify && !auto) throw new SecurityException("Untrusted prototype caller");
        if ("configure".equals(method)) {
            if (!spotify) throw new SecurityException("Spotify settings owner required");
            if (extras == null) throw new IllegalArgumentException("Missing configuration");
            boolean on = extras.getBoolean("enabled");
            if (enabled != on) {
                enabled = on;
                state.clear();
                getContext().getSharedPreferences("auto-prototype", 0).edit().putBoolean("enabled", enabled).apply();
            }
            AutoDisplaySettings.sanitize(extras, state);
        } else if ("enable".equals(method)) {
            if (!BuildConfig.DEBUG || !shell) throw new SecurityException("Debug owner shell required");
            enabled = "true".equals(arg);
            state.clear();
            getContext().getSharedPreferences("auto-prototype", 0).edit().putBoolean("enabled", enabled).apply();
        } else if ("stress".equals(method)) {
            if (!BuildConfig.DEBUG || !shell) throw new SecurityException("Debug owner shell required");
            stress = "true".equals(arg);
        } else if ("publish".equals(method)) {
            if (!spotify) throw new SecurityException("Spotify producer required");
            if (enabled && extras != null) {
                Bundle clean = sanitize(extras);
                Bundle next = extras.getBundle("next");
                if (next != null) {
                    clean.putBundle("next", sanitizeRow(next));
                    clean.putLong("nextAtMs", extras.getLong("nextAtMs"));
                }
                state = clean;
                state.putLong("receivedAt", SystemClock.elapsedRealtime());
            }
        } else if ("host".equals(method)) {
            if (!auto) throw new SecurityException("Android Auto host required");
            host = extras == null ? new Bundle() : new Bundle(extras);
        } else if (!"state".equals(method) && !"status".equals(method)) {
            throw new IllegalArgumentException("Unknown command");
        }
        Bundle reply = new Bundle();
        reply.putString("moduleBuild", com.eza.spicyex.BuildStamp.FULL);
        reply.putBoolean("documentResolved", state.getBoolean("documentResolved"));
        reply.putBoolean("hasSyncedLyrics", state.getBoolean("hasSyncedLyrics"));
        reply.putLong("firstVocalStartMs", state.getLong("firstVocalStartMs", Long.MAX_VALUE));
        reply.putLong("lastVocalEndMs", state.getLong("lastVocalEndMs"));
        reply.putLong("positionMs", AutoPrototypePolicy.position(state.getLong("positionMs"),
                state.getLong("sampledAt"), state.getBoolean("playing"), state.getDouble("playbackRate", 1d), SystemClock.elapsedRealtime()));
        reply.putBoolean("enabled", enabled);
        reply.putBoolean("stress", stress);
        Bundle displaySettings = new Bundle();
        AutoDisplaySettings.sanitize(state, displaySettings);
        reply.putString("displaySettings", displaySettings.toString());
        host.keySet();
        reply.putString("host", host.toString());
        boolean fresh = enabled && AutoPrototypePolicy.fresh(state.getLong("sampledAt"), SystemClock.elapsedRealtime());
        reply.putBoolean("hasLyrics", fresh && !state.getString("line", "").isEmpty());
        reply.putInt("lineIndex", state.getInt("lineIndex", -1));
        reply.putBoolean("playing", state.getBoolean("playing"));
        reply.putDouble("playbackRate", state.getDouble("playbackRate", 1d));
        reply.putBoolean("orgSource", state.getBoolean("orgSource"));
        reply.putInt("creditCount", state.getStringArrayList("creditLabels") == null ? 0 : state.getStringArrayList("creditLabels").size());
        if ("state".equals(method) && (auto || spotify) && enabled
                && fresh) reply.putBundle("state", new Bundle(state));
        return reply;
    }
    /** Prefetched rows may replace row content, never session metadata or display settings. */
    static Bundle sanitizeRow(Bundle extras) {
        Bundle clean = sanitize(extras);
        for (String key : new java.util.ArrayList<>(clean.keySet())) {
            if (!isRowKey(key)) clean.remove(key);
        }
        return clean;
    }

    static boolean isRowKey(String key) {
        switch (key) {
            case "line": case "secondary": case "romanized": case "lineIndex":
            case "lineStartMs": case "lineEndMs": case "lineLevelSync": case "words":
            case "layoutGroups": case "vocalActive": case "presentation": return true;
            default: return false;
        }
    }

    private static Bundle sanitize(Bundle extras) {
        Bundle clean = new Bundle();
        if (extras.containsKey("creditLabels")) {
            java.util.ArrayList<String> labels = extras.getStringArrayList("creditLabels");
            java.util.ArrayList<String> urls = extras.getStringArrayList("creditUrls");
            if (labels == null || urls == null || labels.size() > 3 || labels.size() != urls.size())
                throw new IllegalArgumentException("Invalid response credit");
            for (int i = 0; i < labels.size(); i++) {
                if (labels.get(i) == null || urls.get(i) == null || labels.get(i).length() > 2048 || urls.get(i).length() > 2048)
                    throw new IllegalArgumentException("Oversized response credit");
                if (!urls.get(i).isEmpty()) {
                    java.net.URI url = java.net.URI.create(urls.get(i));
                    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) || url.getHost() == null)
                        throw new IllegalArgumentException("Invalid contributor link");
                }
            }
            clean.putStringArrayList("creditLabels", new java.util.ArrayList<>(labels));
            clean.putStringArrayList("creditUrls", new java.util.ArrayList<>(urls));
            clean.putBoolean("orgSource", extras.getBoolean("orgSource"));
            clean.putLong("creditFetchedAtMs", extras.getLong("creditFetchedAtMs"));
        }
        for (String key : new String[]{"line", "secondary", "title", "artist", "trackUri", "romanized", "creditWriters"}) {
            String value = extras.getString(key, "");
            int limit = "line".equals(key) || "secondary".equals(key) || "romanized".equals(key) || "creditWriters".equals(key) ? 8192 : 512;
            if (value.length() > limit) throw new IllegalArgumentException("Oversized state");
            clean.putString(key, value);
        }
        int[] groups = extras.getIntArray("layoutGroups");
        AutoSurfaceFit.validateGroups(clean.getString("line", ""), groups);
        if (groups != null) clean.putIntArray("layoutGroups", groups.clone());
        String presentation = extras.getString("presentation", "note");
        if (!"lyric".equals(presentation) && !"dots".equals(presentation) && !"note".equals(presentation) && !"native".equals(presentation))
            throw new IllegalArgumentException("Invalid presentation");
        clean.putString("presentation", presentation);
        clean.putInt("generation", extras.getInt("generation", -1));
        clean.putInt("lineIndex", extras.getInt("lineIndex", -1));
        clean.putBoolean("playing", extras.getBoolean("playing"));
        clean.putDouble("playbackRate", AutoPrototypePolicy.playbackRate(extras.getDouble("playbackRate", 1d)));
        clean.putBoolean("hasSyncedLyrics", extras.getBoolean("hasSyncedLyrics"));
        clean.putBoolean("documentResolved", extras.getBoolean("documentResolved"));
        clean.putBoolean("vocalActive", extras.getBoolean("vocalActive"));
        clean.putLong("nextVocalStartMs", extras.getLong("nextVocalStartMs", Long.MAX_VALUE));
        clean.putLong("firstVocalStartMs", extras.getLong("firstVocalStartMs", Long.MAX_VALUE));
        clean.putLong("lastVocalEndMs", extras.getLong("lastVocalEndMs"));
        AutoDisplaySettings.sanitize(extras, clean);
        clean.putLong("sampledAt", extras.getLong("sampledAt"));
        clean.putLong("positionMs", extras.getLong("positionMs"));
        clean.putLong("lineStartMs", extras.getLong("lineStartMs"));
        clean.putLong("lineEndMs", extras.getLong("lineEndMs"));
        clean.putBoolean("lineLevelSync", extras.getBoolean("lineLevelSync"));
        java.util.ArrayList<Bundle> words = extras.getParcelableArrayList("words");
        java.util.ArrayList<Bundle> safeWords = new java.util.ArrayList<>();
        // Reject partial timing as a unit, without processing an oversized list.
        if (words != null && !AutoWordCoverage.withinLimit(words.size())) clean.putBoolean("lineLevelSync", true);
        else if (words != null) for (Bundle word : words) {
            Bundle item = new Bundle();
            for (String key : new String[]{"text", "romanized"}) {
                String value = word.getString(key, "");
                if (value.length() > 512) throw new IllegalArgumentException("Oversized word");
                item.putString(key, value);
            }
            item.putLong("startMs", word.getLong("startMs")); item.putLong("endMs", word.getLong("endMs"));
            item.putBoolean("boundaryAfter", word.getBoolean("boundaryAfter"));
            item.putInt("sourceStart", word.getInt("sourceStart", -1)); item.putInt("sourceEnd", word.getInt("sourceEnd", -1));
            safeWords.add(item);
        }
        clean.putParcelableArrayList("words", safeWords);
        return clean;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
