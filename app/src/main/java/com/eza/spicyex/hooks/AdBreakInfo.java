package com.eza.spicyex.hooks;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.eza.spicyex.References;
import com.eza.spicyex.xposed.XpLog;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the current ad sits in its break ("Advertisement · 1 of 3").
 *
 * <p>Read from the ad track's own metadata when Spotify puts it there, otherwise from the label
 * Spotify shows on screen. Knowing the last ad of a break is what lets the replacement music end
 * with the break instead of running on into the song.
 */
public final class AdBreakInfo {
    private static final String TAG = "[SpicyAdBreak]";
    /** "1 of 3", "1/3", "3개 중 1", "3件中1" - optionally after an ad word. */
    private static final Pattern INDEX_OF_COUNT = Pattern.compile(
            "(?i)(?:advertisement|ad|광고|広告|annonce|anuncio|werbung)?\\D{0,6}?(\\d{1,2})\\s*(?:of|/|de|von)\\s*(\\d{1,2})\\b");
    private static final Pattern COUNT_THEN_INDEX = Pattern.compile("(\\d{1,2})\\s*(?:개\\s*중|件中|個中)\\s*(\\d{1,2})");
    private static final Pattern AD_WORD = Pattern.compile("(?i)advertisement|\\bad\\b|광고|広告|annonce|anuncio|werbung");
    private static final long UI_SCAN_INTERVAL_MS = 1000L;

    /** 1-based position in the break; 0 when unknown. */
    final int index;
    /** Ads in the break; 0 when unknown. */
    final int count;

    private AdBreakInfo(int index, int count) {
        this.index = index;
        this.count = count;
    }

    boolean known() {
        return index > 0 && count > 0 && index <= count;
    }

    boolean isLast() {
        return known() && index == count;
    }

    private static volatile String metadataUri = "";
    private static volatile AdBreakInfo fromMetadata;
    private static String uiUri = "";
    private static AdBreakInfo fromUi;
    private static long uiScannedAt;

    /** Called by References for every ad track it reads. Logs the fields once per ad. */
    public static void noteMetadata(String uri, Map<String, String> metadata) {
        if (uri == null || metadata == null || uri.equals(metadataUri)) return;
        metadataUri = uri;
        fromMetadata = parseMetadata(metadata);
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            if (value.length() > 60) value = value.substring(0, 60) + "…";
            keys.append(e.getKey()).append('=').append(value).append("; ");
        }
        XpLog.log(TAG + " ad metadata " + uri + ": " + keys);
    }

    /** Best current reading for the ad {@code uri}, or null when nothing says where it is. */
    static AdBreakInfo current(String uri) {
        if (uri == null) return null;
        AdBreakInfo meta = uri.equals(metadataUri) ? fromMetadata : null;
        if (meta != null && meta.known()) return meta;
        long now = SystemClock.uptimeMillis();
        if (!uri.equals(uiUri) || now - uiScannedAt >= UI_SCAN_INTERVAL_MS) {
            uiScannedAt = now;
            AdBreakInfo scanned = scanUi(References.currentActivity());
            if (scanned != null || !uri.equals(uiUri)) fromUi = scanned;
            uiUri = uri;
        }
        return fromUi;
    }

    static AdBreakInfo parseMetadata(Map<String, String> metadata) {
        int index = -1;
        int count = -1;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? "" : e.getValue().trim();
            AdBreakInfo text = parseText(value);
            if (text != null) return text;
            if (!key.contains("ad") && !key.contains("break") && !key.contains("slot")) continue;
            int number = parseInt(value);
            if (number < 0) continue;
            if (key.contains("count") || key.contains("total") || key.contains("size")
                    || key.contains("length") && !key.contains("ms")) {
                count = number;
            } else if (key.contains("index") || key.contains("position") && !key.contains("ms")
                    || key.contains("number") || key.contains("ordinal")) {
                index = number;
            }
        }
        if (count <= 0 || index < 0) return null;
        // Whether a numeric index counts from 0 or 1 is not known up front; 0 can only mean the
        // former. (The logged metadata above is what confirms which one Spotify uses.)
        return valid(index == 0 ? 1 : index, count);
    }

    static AdBreakInfo parseText(String text) {
        if (text == null || text.isEmpty() || text.length() > 80) return null;
        Matcher m = COUNT_THEN_INDEX.matcher(text);
        if (m.find()) return valid(parseInt(m.group(2)), parseInt(m.group(1)));
        m = INDEX_OF_COUNT.matcher(text);
        if (m.find() && AD_WORD.matcher(text).find()) return valid(parseInt(m.group(1)), parseInt(m.group(2)));
        return null;
    }

    private static AdBreakInfo valid(int index, int count) {
        if (index < 1 || count < 1 || index > count || count > 20) return null;
        return new AdBreakInfo(index, count);
    }

    private static AdBreakInfo scanUi(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return null;
            return scan(activity.getWindow().getDecorView(), 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static AdBreakInfo scan(View view, int depth) {
        if (view == null || depth > 60 || !view.isAttachedToWindow()) return null;
        if (view instanceof TextView) {
            AdBreakInfo info = parseText(String.valueOf(((TextView) view).getText()));
            if (info != null) return info;
        }
        CharSequence description = view.getContentDescription();
        if (description != null) {
            AdBreakInfo info = parseText(description.toString());
            if (info != null) return info;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                AdBreakInfo info = scan(group.getChildAt(i), depth + 1);
                if (info != null) return info;
            }
        }
        return null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
