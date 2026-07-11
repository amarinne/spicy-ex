package com.eza.spicyex;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
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
    public static String accessToken = "";
    public static WeakReference<Typeface> beautifulFont = new WeakReference<>(null);
    public static XModuleResources modResources = null;
    public static XResources xresources = null;

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

    public static SpotifyTrack getTrackTitle(XC_LoadPackage.LoadPackageParam lpparam, DexKitBridge bridge) {
        if(playerState == null || playerState.get() == null) {
            XposedBridge.log("[SpotifyPlus] playerState is null");
            return null;
        }

        Object state = playerState.get();

        try {
            Object wrapper = XposedHelpers.callMethod(state, "track");

            var className = wrapper.getClass().getName();
            if(hasTrackMethod == null) {
                var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
                hasTrackMethod = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).returnType(boolean.class).paramCount(0))).get(0).getMethodInstance(lpparam.classLoader);
            }

            boolean hasTrack = (Boolean) XposedHelpers.callMethod(wrapper, hasTrackMethod.getName());
            if(hasTrack) {
                if(getContextTrack == null) {
                    var clazz = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().className(className)));
                    getContextTrack = bridge.findMethod(FindMethod.create().searchInClass(clazz).matcher(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).paramCount(0).returnType(Object.class))).get(0).getMethodInstance(lpparam.classLoader);
                }

                Object ct = XposedHelpers.callMethod(wrapper, getContextTrack.getName());
                Class<?> contextClass = XposedHelpers.findClass("com.spotify.player.model.ContextTrack", lpparam.classLoader);
                if(contextClass.isInstance(ct)) {
                    Object track = contextClass.cast(ct);

                    String uri = (String) XposedHelpers.callMethod(track, "uri");

                    @SuppressWarnings("unchecked")
                    Map<String, String> md = (Map<String, String>) XposedHelpers.callMethod(track, "metadata");

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
                        if (duration <= 0) duration = (Long) XposedHelpers.callMethod(state, "duration");
                    } catch (Throwable ignored) {
                    }
                    long position = 0;
                    long timestamp = 0;

                    Object posOpt = XposedHelpers.callMethod(state, "positionAsOfTimestamp");
                    Matcher m = DIGITS.matcher(posOpt.toString());
                    if(m.find()) {
                        long basePos = Long.parseLong(m.group());
                        timestamp = (Long) XposedHelpers.callMethod(state, "timestamp");
                        position = basePos + (System.currentTimeMillis() - timestamp);
                    }

                    Map<?, ?> metadata = (Map<?, ?>) XposedHelpers.getObjectField(track, "metadata");
                    boolean saved = false;

                    if(metadata.containsKey("collection.in_collection")) {
                        String savedValue = (String) metadata.get("collection.in_collection");
                        saved = Boolean.parseBoolean(savedValue);
                    }

                    return new SpotifyTrack(title, artist, album, uri, position, color, timestamp, imageId, duration, saved);
                } else {
                    XposedBridge.log("[SpotifyPlus] ContextTrack not found!");
                    return null;
                }
            } else {
                XposedBridge.log("[SpotifyPlus] No track found");
                return null;
            }
        } catch(Exception e) {
            Log.e("SpotifyPlus", "Error getting track information", e);
            return null;
        }
    }

    private static long previousMs;
    public static long getCurrentPlaybackPosition(DexKitBridge bridge, XC_LoadPackage.LoadPackageParam lpparam) {
        Object wrapper = References.playerStateWrapper == null ? null : References.playerStateWrapper.get();
        if (wrapper == null) return -1;

        Object state;
        try {
            state = XposedHelpers.callMethod(wrapper, "getState");

            if (state == null) return -1;
        } catch (Throwable t) {
            return -1;
        }

        try {
            var progressList = bridge.findField(FindField.create().searchInClass(Arrays.asList(bridge.getClassData(state.getClass()))).matcher(FieldMatcher.create().type(long.class)));
            if(progressList.isEmpty()) {
                XposedBridge.log("[SpotifyPlus] Failed to get progress: " + state.getClass().getName());
                return -1;
            }

            return progressList.get(0).getFieldInstance(lpparam.classLoader).getLong(state);
        } catch(Exception e) {
            XposedBridge.log(e);
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
            XposedBridge.log("[SpotifyPlus] No activity found");
            return null;
        }

        return activity.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

}
