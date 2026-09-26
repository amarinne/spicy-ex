package com.eza.spicyex.hooks;

import android.os.SystemClock;
import android.widget.TextView;

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

    /**
     * Best current reading for the ad {@code uri}, or null when nothing says where it is.
     *
     * <p>Nothing is scanned: Spotify's own "2 of 3" and "37s left in the break" are caught as
     * Spotify posts them - the media notification, the media session, and a label's text being
     * set while an ad plays (see {@link #installHooks}). A label on a hidden screen may still
     * stop updating, so a position is only trusted when its text changes: an ad that starts
     * while the text still reads what it read for the previous ad is taken to be the next one
     * in the same break.
     */
    static synchronized AdBreakInfo current(String uri) {
        if (uri == null) return null;
        long now = SystemClock.uptimeMillis();
        // Asked about continuously through a break (ad card, ad music): a long silence means a
        // song played in between, so this is a new break even if nobody said the last one ended.
        if (lastAskedAt > 0L && now - lastAskedAt > 20_000L) noteBreakOver();
        lastAskedAt = now;
        adPlaying = true;
        AdBreakInfo meta = uri.equals(metadataUri) ? fromMetadata : null;
        if (meta != null && meta.known()) {
            remember(uri, meta, null);
        } else {
            remember(uri, heardPosition, heardPositionText);
        }
        return breakUri.equals(uri) ? breakPosition : null;
    }

    /**
     * Milliseconds left in the whole ad break (every ad still to come, not just this one), or -1
     * when Spotify has not said. Projected from the last "37s left in the break" it showed.
     */
    static synchronized long breakRemainingMs() {
        if (breakEndsAt <= 0L) return -1L;
        return Math.max(0L, breakEndsAt - SystemClock.uptimeMillis());
    }

    /** True when the break ends with this ad: it is the last one, or the break has no more
     *  time left than this ad does. */
    static synchronized boolean endsWithThisAd(AdBreakInfo info, long adRemainingMs) {
        if (info != null && info.isLast()) return true;
        long left = breakRemainingMs();
        return left >= 0 && adRemainingMs >= 0 && left <= adRemainingMs + 1500L;
    }

    /** Called when the ad pauses or resumes: the break's end moves with the pause. */
    static synchronized void notePaused(boolean paused) {
        long now = SystemClock.uptimeMillis();
        if (paused) {
            if (pausedAt == 0L) pausedAt = now;
        } else if (pausedAt != 0L) {
            if (breakEndsAt > 0L) breakEndsAt += now - pausedAt;
            pausedAt = 0L;
        }
    }

    /** A song is playing: the break is over. */
    static synchronized void noteBreakOver() {
        adPlaying = false;
        // The session and notification announce the next break's first ad a moment before the
        // player reports the ad itself: a reading that fresh belongs to the new break.
        if (SystemClock.uptimeMillis() - heardAt > 3000L) {
            heardPosition = null;
            heardPositionText = "";
        }
        breakUri = "";
        breakPosition = null;
        lastPositionText = "";
        lastBreakText = "";
        breakEndsAt = 0L;
        pausedAt = 0L;
    }

    /** Folds one reading for {@code uri} into the break being followed. */
    private static void remember(String uri, AdBreakInfo reading, String readingText) {
        String text = readingText == null ? "" : readingText;
        boolean fresh = reading != null && (readingText == null || !text.equals(lastPositionText));
        if (fresh) {
            lastPositionText = text;
            breakUri = uri;
            breakPosition = reading;
            return;
        }
        if (uri.equals(breakUri)) return;
        // A new ad and no new reading: the next one of the break we already know, if any.
        AdBreakInfo previous = breakPosition;
        breakUri = uri;
        breakPosition = previous != null && previous.known() && previous.index < previous.count
                ? new AdBreakInfo(previous.index + 1, previous.count) : null;
    }

    private static String breakUri = "";
    private static AdBreakInfo breakPosition;
    private static String lastPositionText = "";
    private static String lastBreakText = "";
    private static long breakEndsAt;
    private static long pausedAt;
    private static long lastAskedAt;
    /** Set while an ad plays, so the text hooks cost one volatile read the rest of the time. */
    private static volatile boolean adPlaying;
    private static AdBreakInfo heardPosition;
    private static String heardPositionText = "";
    private static long heardAt;

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

    /** "37s left in the break", "1:05 left in the break", "광고 시간 37초 남음"... */
    private static final Pattern BREAK_WORD = Pattern.compile(
            "(?i)\\bbreak\\b|left|remaining|남음|남았|残り|restant|restan|verbleib");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SECONDS = Pattern.compile("(?i)(\\d{1,3})\\s*(?:s\\b|sec|초|秒)");

    /** Seconds a "time left in the break" label gives, or -1 when it is not one. */
    static int parseBreakLeft(String text) {
        if (text == null || text.isEmpty() || text.length() > 60) return -1;
        if (!BREAK_WORD.matcher(text).find()) return -1;
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) return parseInt(clock.group(1)) * 60 + parseInt(clock.group(2));
        Matcher seconds = SECONDS.matcher(text);
        if (seconds.find()) return parseInt(seconds.group(1));
        return -1;
    }

    /**
     * Where the text comes from, all event-driven: the media notification and media session
     * (which carry "Advertisement · 2 of 3" whatever screen is showing), and label text set in
     * Spotify's own UI while an ad plays (the only place "37s left in the break" appears).
     */
    static void installHooks() {
        try {
            com.eza.spicyex.xposed.XpHooks.findAfter(android.media.session.MediaSession.class,
                    "setMetadata", "adBreak:MediaSession#setMetadata",
                    param -> offerMetadata((android.media.MediaMetadata) param.args[0]),
                    android.media.MediaMetadata.class);
        } catch (Throwable t) {
            XpLog.log(TAG + " session hook unavailable: " + t.getClass().getSimpleName());
        }
        try {
            com.eza.spicyex.xposed.XpHooks.findBefore(android.app.NotificationManager.class,
                    "notify", "adBreak:NotificationManager#notify",
                    param -> offerNotification((android.app.Notification) param.args[2]),
                    String.class, int.class, android.app.Notification.class);
        } catch (Throwable t) {
            XpLog.log(TAG + " notification hook unavailable: " + t.getClass().getSimpleName());
        }
        try {
            com.eza.spicyex.xposed.XpHooks.findAfter(TextView.class, "setText", "adBreak:TextView#setText",
                    param -> {
                        if (adPlaying) offerLabel((CharSequence) param.args[0]);
                    },
                    CharSequence.class, TextView.BufferType.class, boolean.class, int.class);
        } catch (Throwable t) {
            XpLog.log(TAG + " label hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void offerMetadata(android.media.MediaMetadata metadata) {
        if (metadata == null) return;
        for (String key : new String[]{android.media.MediaMetadata.METADATA_KEY_ARTIST,
                android.media.MediaMetadata.METADATA_KEY_ALBUM,
                android.media.MediaMetadata.METADATA_KEY_TITLE,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION}) {
            try {
                CharSequence text = metadata.getText(key);
                if (text != null) offerText(text.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void offerNotification(android.app.Notification notification) {
        if (notification == null || notification.extras == null) return;
        for (String key : new String[]{android.app.Notification.EXTRA_TEXT,
                android.app.Notification.EXTRA_SUB_TEXT, android.app.Notification.EXTRA_TITLE,
                android.app.Notification.EXTRA_INFO_TEXT}) {
            CharSequence text = notification.extras.getCharSequence(key);
            if (text != null) offerText(text.toString());
        }
    }

    private static void offerLabel(CharSequence text) {
        if (text == null) return;
        int length = text.length();
        if (length == 0 || length > 60) return;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                offerText(text.toString());
                return;
            }
        }
    }

    /** One piece of text Spotify just showed; keeps what it says about the break. */
    static synchronized void offerText(String text) {
        AdBreakInfo position = parseText(text);
        if (position != null) {
            heardPosition = position;
            heardPositionText = text;
            heardAt = SystemClock.uptimeMillis();
        }
        int left = parseBreakLeft(text);
        if (left >= 0 && !text.equals(lastBreakText)) {
            lastBreakText = text;
            breakEndsAt = SystemClock.uptimeMillis() + left * 1000L;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
