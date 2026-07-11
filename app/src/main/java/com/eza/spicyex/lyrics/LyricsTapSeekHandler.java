package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/** Handles lyric scroll touch hold and optional tap-to-seek gestures. */
public final class LyricsTapSeekHandler implements View.OnTouchListener {
    private final Context context;
    private final SpotifyPlusConfig config;
    private final HoldCallback holdCallback;
    private final TouchCallback touchCallback;
    private final SeekCallback seekCallback;
    private float scrollDownY;
    private long scrollDownAtMs;
    private long lastTapAtMs;
    private float lastTapY;

    public LyricsTapSeekHandler(
            Context context,
            SpotifyPlusConfig config,
            HoldCallback holdCallback,
            TouchCallback touchCallback,
            SeekCallback seekCallback
    ) {
        this.context = context;
        this.config = config;
        this.holdCallback = holdCallback;
        this.touchCallback = touchCallback;
        this.seekCallback = seekCallback;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scrollDownY = event.getY();
            scrollDownAtMs = SystemClock.elapsedRealtime();
            if (touchCallback != null) touchCallback.touching(true);
            hold();
        } else if (action == MotionEvent.ACTION_MOVE) {
            hold();
        } else if (action == MotionEvent.ACTION_UP) {
            if (touchCallback != null) touchCallback.touching(false);
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
            if (touchCallback != null) touchCallback.touching(false);
        }
        return false;
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
}
