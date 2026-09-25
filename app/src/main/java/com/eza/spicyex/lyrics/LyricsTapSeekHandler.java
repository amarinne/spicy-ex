package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/** Handles lyric scroll touch hold, optional tap-to-seek, and long-press-to-share gestures. */
public final class LyricsTapSeekHandler implements View.OnTouchListener {
    private final Context context;
    private final SpotifyPlusConfig config;
    private final HoldCallback holdCallback;
    private final TouchCallback touchCallback;
    private final SeekCallback seekCallback;
    private final LongPressCallback longPressCallback;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = this::fireLongPress;
    private float scrollDownY;
    private long scrollDownAtMs;
    private long lastTapAtMs;
    private float lastTapY;
    private boolean longPressFired;

    public LyricsTapSeekHandler(
            Context context,
            SpotifyPlusConfig config,
            HoldCallback holdCallback,
            TouchCallback touchCallback,
            SeekCallback seekCallback
    ) {
        this(context, config, holdCallback, touchCallback, seekCallback, null);
    }

    public LyricsTapSeekHandler(
            Context context,
            SpotifyPlusConfig config,
            HoldCallback holdCallback,
            TouchCallback touchCallback,
            SeekCallback seekCallback,
            LongPressCallback longPressCallback
    ) {
        this.context = context;
        this.config = config;
        this.holdCallback = holdCallback;
        this.touchCallback = touchCallback;
        this.seekCallback = seekCallback;
        this.longPressCallback = longPressCallback;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scrollDownY = event.getY();
            scrollDownAtMs = SystemClock.elapsedRealtime();
            longPressFired = false;
            armLongPress();
            if (touchCallback != null) touchCallback.touching(true);
            hold();
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getY() - scrollDownY) >= dp(10)) cancelLongPress();
            hold();
        } else if (action == MotionEvent.ACTION_UP) {
            cancelLongPress();
            if (touchCallback != null) touchCallback.touching(false);
            if (longPressFired) return true;
            float dy = Math.abs(event.getY() - scrollDownY);
            long held = SystemClock.elapsedRealtime() - scrollDownAtMs;
            if (dy < dp(10) && held < 600) {
                String mode = config == null ? "" : config.get(Settings.TAP_SEEK_MODE);
                if ("Double tap".equalsIgnoreCase(mode)) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastTapAtMs < 350 && Math.abs(event.getY() - lastTapY) < dp(20)) {
                        seek(event.getY());
                        lastTapAtMs = 0;
                    } else {
                        lastTapAtMs = now;
                        lastTapY = event.getY();
                    }
                } else if ("Single tap".equalsIgnoreCase(mode)) {
                    seek(event.getY());
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelLongPress();
            if (touchCallback != null) touchCallback.touching(false);
        }
        return false;
    }

    /** The current gesture already fired its long press (it is still held, or just lifted). */
    public boolean longPressFired() {
        return longPressFired;
    }

    private void armLongPress() {
        if (longPressCallback == null) return;
        longPressHandler.removeCallbacks(longPressRunnable);
        longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelLongPress() {
        longPressHandler.removeCallbacks(longPressRunnable);
    }

    private void fireLongPress() {
        longPressFired = true;
        if (longPressCallback != null) longPressCallback.onLongPress(scrollDownY);
    }

    private void hold() {
        if (holdCallback != null) holdCallback.holdUntil(SystemClock.elapsedRealtime() + 3500);
    }

    private void seek(float y) {
        if (seekCallback != null) seekCallback.seekAt(y);
    }

    private int dp(int value) {
        float density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    public interface HoldCallback {
        void holdUntil(long untilMs);
    }

    public interface TouchCallback {
        void touching(boolean touching);
    }

    public interface SeekCallback {
        void seekAt(float y);
    }

    public interface LongPressCallback {
        void onLongPress(float y);
    }
}
