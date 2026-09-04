package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;

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
    }
}
