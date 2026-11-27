package com.eza.spicyex;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.SpotifySymbolResolver;
import com.eza.spicyex.xposed.XpReflect;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class References {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    // Volatile: Spotify can build PlayerState off the main thread (9.1.88's
    // observe_player_state_on_computation), while the lyrics frame loop reads it on the UI thread.
    public static volatile WeakReference<Object> playerState = new WeakReference<>(null);
    public static volatile WeakReference<Object> playerStateWrapper = new WeakReference<>(null);
    /** Strong playback snapshots keep background track detection alive while Spotify UI is idle. */
    public static volatile Object playerStateStrong;
    public static volatile Object playerStateWrapperStrong;
    /**
     * Legacy compatibility mirror of the currently captured Spotify access token. Never
     * authoritative: the process-wide token lifecycle state (token text, captured timestamp,
     * observed expiry, generation, tombstoning) is owned by the package-private
     * SpotifyTokenStore/SpotifyTokenState seam in com.eza.spicyex.hooks, and this mirror is
     * refreshed from it after every capture, restore, and invalidation. It is blank whenever no
     * usable (present, non-tombstoned, fresh) token exists. Read-only consumers must keep deciding
     * via their own settings (SEND_TOKEN) whether this value may be sent; capture itself never
     * depends on that choice. Do not assign this field outside the token store.
     */
    public static String accessToken = "";
    public static WeakReference<Typeface> beautifulFont = new WeakReference<>(null);
    public static Resources modResources = null;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static volatile Method hasTrackMethod;
    private static volatile Method getContextTrack;
    private static final java.util.Set<Class<?>> TRACKLESS_WRAPPERS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static Activity currentActivity() {
        return currentActivity.get();
    }

    public static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static void clearCurrentActivity(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity.clear();
    }

    public static SpotifyTrack getTrackTitle(ClassLoader classLoader, SpotifySymbolResolver symbols) {
        Object strongState = playerStateStrong;
        Object weakState = playerState == null ? null : playerState.get();
        if(strongState == null && weakState == null) {
            XpLog.log("[SpotifyPlus] playerState is null");
            return null;
        }

        Object state = strongState != null ? strongState : weakState;

        try {
            Object wrapper = XpReflect.callMethod(state, "track");

            // Spotify's Optional has a present and an absent class; the absent one (a bare
            // singleton) declares no accessor at all. That is simply "no track right now" - and
            // looking for its accessor again on every call used to log a stack trace each time.
            if (wrapper == null || TRACKLESS_WRAPPERS.contains(wrapper.getClass())) return null;
            Method hasTrackAccessor = hasTrackMethod;
            if(hasTrackAccessor == null || hasTrackAccessor.getDeclaringClass() != wrapper.getClass()) {
                try {
                    hasTrackAccessor = symbols.trackMethod(wrapper.getClass(), boolean.class);
                } catch (NoSuchMethodException absent) {
                    TRACKLESS_WRAPPERS.add(wrapper.getClass());
                    XpLog.log("[SpotifyPlus] " + wrapper.getClass().getName() + " has no track accessor, read as no track");
                    return null;
                }
                hasTrackMethod = hasTrackAccessor;
            }

            boolean hasTrack = (Boolean) XpReflect.callMethod(wrapper, hasTrackAccessor.getName());
            if(hasTrack) {
                Method contextTrackAccessor = getContextTrack;
                if(contextTrackAccessor == null || contextTrackAccessor.getDeclaringClass() != wrapper.getClass()) {
                    contextTrackAccessor = symbols.trackMethod(wrapper.getClass(), Object.class);
                    getContextTrack = contextTrackAccessor;
                }

                Object ct = XpReflect.callMethod(wrapper, contextTrackAccessor.getName());
                Class<?> contextClass = XpReflect.findClass("com.spotify.player.model.ContextTrack", classLoader);
                if(contextClass.isInstance(ct)) {
                    Object track = contextClass.cast(ct);

                    String uri = (String) XpReflect.callMethod(track, "uri");

                    @SuppressWarnings("unchecked")
                    Map<String, String> md = (Map<String, String>) XpReflect.callMethod(track, "metadata");

                    String title = md.get("title");
                    String artist = joinArtistNames(md.get("artist_name"), md);
                    String album = md.get("album_title");
                    String color = md.get("extracted_color");
                    String imageId = md.get("image_large_url");
                    long duration = trackDuration(md);
                    try {
                        if (duration <= 0) duration = (Long) XpReflect.callMethod(state, "duration");
                    } catch (Throwable ignored) {
                    }
                    long position = 0;
                    long timestamp = 0;

                    Object posOpt = XpReflect.callMethod(state, "positionAsOfTimestamp");
                    Matcher m = DIGITS.matcher(posOpt.toString());
                    if(m.find()) {
                        long basePos = Long.parseLong(m.group());
                        timestamp = (Long) XpReflect.callMethod(state, "timestamp");
                        position = basePos + (System.currentTimeMillis() - timestamp);
                    }

                    Map<?, ?> metadata = (Map<?, ?>) XpReflect.getObjectField(track, "metadata");
                    boolean saved = false;

                    if(metadata.containsKey("collection.in_collection")) {
                        String savedValue = (String) metadata.get("collection.in_collection");
                        saved = Boolean.parseBoolean(savedValue);
                    }

                    return new SpotifyTrack(title, artist, album, uri, position, color, timestamp, imageId, duration, saved);
                } else {
                    XpLog.log("[SpotifyPlus] ContextTrack not found!");
                    return null;
                }
            } else {
                XpLog.log("[SpotifyPlus] No track found");
                return null;
            }
        } catch(Exception e) {
            Log.e("SpotifyPlus", "Error getting track information", e);
            return null;
        }
    }

    /** Reads only the queue head from the same immutable state the playback hook captured. */
    public static SpotifyTrack nextQueuedTrack(Object state) {
        if (state == null) return null;
        try {
            Object options = XpReflect.callMethod(state, "options");
            if (Boolean.TRUE.equals(XpReflect.callMethod(options, "repeatingTrack"))) return null;
            Object list = XpReflect.callMethod(state, "nextTracks");
            if (!(list instanceof Iterable<?>)) return null;
            java.util.Iterator<?> tracks = ((Iterable<?>) list).iterator();
            if (!tracks.hasNext()) return null;
            Object next = tracks.next();
            if (next == null) return null;
            Object uri = XpReflect.callMethod(next, "uri");
            if (!(uri instanceof String) || !((String) uri).matches("spotify:track:[A-Za-z0-9]{22}")) {
                return null;
            }
            Object metadata = XpReflect.callMethod(next, "metadata");
            if (!(metadata instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, String> md = (Map<String, String>) metadata;
            return new SpotifyTrack(md.get("title"), joinArtistNames(md.get("artist_name"), md),
                    md.get("album_title"), (String) uri, 0L, md.get("extracted_color"), 0L,
                    md.get("image_large_url"), trackDuration(md), false);
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    private static long trackDuration(Map<String, String> metadata) {
        try {
            String value = metadata.get("duration_ms");
            if (value == null) value = metadata.get("duration");
            if (value == null || value.isEmpty()) return 0L;
            long duration = Long.parseLong(value.replaceAll("[^0-9]", ""));
            return duration > 0L && duration < 10000L ? duration * 1000L : duration;
        } catch (RuntimeException invalid) {
            return 0L;
        }
    }

    /**
     * Full artist credit line. Spotify's player metadata carries the main artist in
     * "artist_name" and any further credited artists in 1-based indexed keys
     * ("artist_name:1", "artist_name:2", ...); Spotify's own client iterates the same
     * keys when it builds the artist list. Joined with ", " to match the Now Playing
     * credit line; downstream matching already splits on commas.
     */
    static String joinArtistNames(String primary, Map<String, String> md) {
        List<String> names = new ArrayList<>();
        if (primary != null && !primary.trim().isEmpty()) names.add(primary.trim());
        if (md != null) {
            for (int index = 1; index <= MAX_INDEXED_ARTISTS; index++) {
                String extra = md.get("artist_name:" + index);
                if (extra == null || extra.trim().isEmpty()) break;
                String name = extra.trim();
                boolean duplicate = false;
                for (String known : names) {
                    if (known.equalsIgnoreCase(name)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) names.add(name);
            }
        }
        if (names.isEmpty()) return primary;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(names.get(i));
        }
        return joined.toString();
    }

    private static final int MAX_INDEXED_ARTISTS = 32;

}
