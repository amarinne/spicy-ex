package com.eza.spicyex;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class References {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    public static WeakReference<Object> playerState = new WeakReference<>(null);
    public static WeakReference<Object> playerStateWrapper = new WeakReference<>(null);
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
    private static Method hasTrackMethod;
    private static Method getContextTrack;

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

    public static SpotifyTrack getTrackTitle(ClassLoader classLoader, DexKitBridge bridge) {
        Object strongState = playerStateStrong;
        Object weakState = playerState == null ? null : playerState.get();
        if(strongState == null && weakState == null) {
            XpLog.log("[SpotifyPlus] playerState is null");
            return null;
        }

        Object state = strongState != null ? strongState : weakState;

        try {
            Object wrapper = XpReflect.callMethod(state, "track");

            var className = wrapper.getClass().getName();
            if(hasTrackMethod == null) {
                var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
                hasTrackMethod = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).returnType(boolean.class).paramCount(0))).get(0).getMethodInstance(classLoader);
            }

            boolean hasTrack = (Boolean) XpReflect.callMethod(wrapper, hasTrackMethod.getName());
            if(hasTrack) {
                if(getContextTrack == null) {
                    var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
                    getContextTrack = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).paramCount(0).returnType(Object.class))).get(0).getMethodInstance(classLoader);
                }

                Object ct = XpReflect.callMethod(wrapper, getContextTrack.getName());
                Class<?> contextClass = XpReflect.findClass("com.spotify.player.model.ContextTrack", classLoader);
                if(contextClass.isInstance(ct)) {
                    Object track = contextClass.cast(ct);

                    String uri = (String) XpReflect.callMethod(track, "uri");

                    @SuppressWarnings("unchecked")
                    Map<String, String> md = (Map<String, String>) XpReflect.callMethod(track, "metadata");

                    String title = md.get("title");
                    String artist = md.get("artist_name");
                    String album = md.get("album_title");
                    String color = md.get("extracted_color");
                    String imageId = md.get("image_large_url");
                    long duration = 0;
                    try {
                        String durationValue = md.get("duration_ms");
                        if (durationValue == null) durationValue = md.get("duration");
                        if (durationValue != null && !durationValue.isEmpty()) {
                            duration = Long.parseLong(durationValue.replaceAll("[^0-9]", ""));
                            if (duration > 0 && duration < 10000) duration *= 1000;
                        }
                    } catch (Throwable ignored) {
                    }
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

    private static long previousMs;
    public static long getCurrentPlaybackPosition(DexKitBridge bridge, ClassLoader classLoader) {
        Object wrapper = playerStateWrapperStrong != null
                ? playerStateWrapperStrong
                : (References.playerStateWrapper == null ? null : References.playerStateWrapper.get());
        if (wrapper == null) return -1;

        Object state;
        try {
            state = XpReflect.callMethod(wrapper, "getState");

            if (state == null) return -1;
        } catch (Throwable t) {
            return -1;
        }

        try {
            var progressList = bridge.findField(FindField.create().searchInClass(Arrays.asList(bridge.getClassData(state.getClass()))).matcher(FieldMatcher.create().type(long.class)));
            if(progressList.isEmpty()) {
                XpLog.log("[SpotifyPlus] Failed to get progress: " + state.getClass().getName());
                return -1;
            }

            return progressList.get(0).getFieldInstance(classLoader).getLong(state);
        } catch(Exception e) {
            XpLog.log(e);
        }

        return -1;
    }

    public static SharedPreferences getPreferences() {
        Activity activity = currentActivity();
        if(activity == null) return null;

        return activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
    }

    public static SharedPreferences getScriptPreferences(String name, Context activity) {
        if(activity == null) {
            XpLog.log("[SpotifyPlus] No activity found");
            return null;
        }

        return activity.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

}
