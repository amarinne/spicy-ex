package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;

import com.eza.spicyex.SettingsPanel;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.beautifullyrics.entities.VsyncFrameScheduler;
import com.eza.spicyex.lyrics.LyricsAmbientController;
import com.eza.spicyex.ui.Motion;

import com.eza.spicyex.xposed.XpLog;

/** Owns the in-Spotify settings modal lifecycle and render-loop pause/resume. */
final class LyricsSettingsDialogController {
    private final Activity activity;
    private final VsyncFrameScheduler frameScheduler;
    private final LyricsAmbientController ambientController;
    private final LyricsHost host;
    private final Runnable onClosed;
    private final String logTag;

    LyricsSettingsDialogController(
            Activity activity,
            VsyncFrameScheduler frameScheduler,
            LyricsAmbientController ambientController,
            LyricsHost host,
            Runnable onClosed,
            String logTag
    ) {
        this.activity = activity;
        this.frameScheduler = frameScheduler;
        this.ambientController = ambientController;
        this.host = host;
        this.onClosed = onClosed;
        this.logTag = logTag;
    }

    // Sticky across opens: half mode anchors the panel to the top so lyrics preview underneath.
    private static boolean halfMode;
    // Sticky drag offset from the default centered/top position, in pixels, applied on top of
    // applySize()'s gravity. Integer.MIN_VALUE means "never dragged - use the plain default".
    private static int draggedOffsetX = Integer.MIN_VALUE;
    private static int draggedOffsetY = Integer.MIN_VALUE;

    void show() {
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
            final View[] panelRef = new View[1];
            SettingsPanel panel = new SettingsPanel(activity, new SettingsStore(activity),
                    () -> halfMode, () -> {
                        halfMode = !halfMode;
                        applySize(window);
                    }, () -> Motion.exitCardThen(panelRef[0], dialog::isShowing, dialog::dismiss),
                    host::clearLyricsCache);
            final View panelView = panel.build();
            panelRef[0] = panelView;
            // Back routes through the animated exit; outside-tap keeps platform behavior
            // (cancelability untouched, per motion-audit lifecycle contract).
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    Motion.exitCardThen(panelView, dialog::isShowing, dialog::dismiss);
                    return true;
                }
                return false;
            });
            dialog.setContentView(panelView);
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                applySize(window);
                setupDrag(window, panelView);
            }
            dialog.setOnDismissListener(d -> {
                frameScheduler.start();
                onClosed.run();
            });
            dialog.show();
            Motion.enterCard(panelView);
        } catch (Throwable t) {
            XpLog.log(logTag + " settings dialog failed: " + t);
        }
    }

    private void applySize(Window window) {
        if (window == null) return;
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int w = (int) (dm.widthPixels * 0.92f);
        int h = (int) (dm.heightPixels * (halfMode ? 0.45f : 0.84f));
        window.setLayout(w, h);
        window.setGravity(halfMode ? android.view.Gravity.TOP : android.view.Gravity.CENTER);
        window.setDimAmount(halfMode ? 0.1f : 0.5f);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.x = draggedOffsetX == Integer.MIN_VALUE ? 0 : draggedOffsetX;
        lp.y = draggedOffsetY == Integer.MIN_VALUE ? 0 : draggedOffsetY;
        window.setAttributes(lp);
    }

    /**
     * Drags the whole dialog window by its top ~64dp (the title/close-button strip) - x/y in
     * WindowManager.LayoutParams are plain pixel offsets from whatever the current gravity
     * resolves to, so dragging is just accumulating the raw finger delta into them; no gravity
     * switch or screen-coordinate math needed. Landscape especially can put the default centered
     * position over content the user wants visible underneath (e.g. half mode's own lyrics
     * preview), so letting people park it wherever suits them is worth more than a fixed spot.
     */
    private void setupDrag(Window window, View panelView) {
        int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        int handleHeightPx = dp(64);
        float[] downRawX = new float[1];
        float[] downRawY = new float[1];
        int[] startX = new int[1];
        int[] startY = new int[1];
        boolean[] dragging = new boolean[1];
        boolean[] eligible = new boolean[1];
        panelView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    eligible[0] = event.getY() <= handleHeightPx;
                    dragging[0] = false;
                    downRawX[0] = event.getRawX();
                    downRawY[0] = event.getRawY();
                    WindowManager.LayoutParams lp = window.getAttributes();
                    startX[0] = lp.x;
                    startY[0] = lp.y;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!eligible[0]) return false;
                    float dx = event.getRawX() - downRawX[0];
                    float dy = event.getRawY() - downRawY[0];
                    if (!dragging[0]) {
                        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) return false;
                        dragging[0] = true;
                    }
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.x = startX[0] + Math.round(dx);
                    lp.y = startY[0] + Math.round(dy);
                    try {
                        window.setAttributes(lp);
                    } catch (Throwable ignored) {
                        // Window already gone (dialog dismissed mid-drag) - nothing to update.
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging[0]) {
                        WindowManager.LayoutParams lp = window.getAttributes();
                        draggedOffsetX = lp.x;
                        draggedOffsetY = lp.y;
                    }
                    boolean wasDragging = dragging[0];
                    dragging[0] = false;
                    eligible[0] = false;
                    // Swallow the up so a drag doesn't also register as a click on whatever sits
                    // under the header strip; a plain tap (never crossed the slop) still passes
                    // through untouched via the earlier `return false`s.
                    return wasDragging;
                }
                default:
                    return false;
            }
        });
    }
}
