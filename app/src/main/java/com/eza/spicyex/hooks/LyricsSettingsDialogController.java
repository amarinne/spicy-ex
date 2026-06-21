package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;

import com.eza.spicyex.SettingsPanel;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.beautifullyrics.entities.VsyncFrameScheduler;
import com.eza.spicyex.lyrics.LyricsAmbientController;

import de.robv.android.xposed.XposedBridge;

/** Owns the in-Spotify settings modal lifecycle and render-loop pause/resume. */
final class LyricsSettingsDialogController {
    private final Activity activity;
    private final VsyncFrameScheduler frameScheduler;
    private final LyricsAmbientController ambientController;
    private final Runnable onClosed;
    private final String logTag;

    LyricsSettingsDialogController(
            Activity activity,
            VsyncFrameScheduler frameScheduler,
            LyricsAmbientController ambientController,
            Runnable onClosed,
            String logTag
    ) {
        this.activity = activity;
        this.frameScheduler = frameScheduler;
        this.ambientController = ambientController;
        this.onClosed = onClosed;
        this.logTag = logTag;
    }

    void show() {
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(new SettingsPanel(activity, new SettingsStore(activity), dialog::dismiss).build());
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                int h = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.84f);
                window.setLayout(w, h);
                window.setDimAmount(0.6f);
            }
            frameScheduler.stop();
            ambientController.pauseAnimation();
            dialog.setOnDismissListener(d -> {
                frameScheduler.start();
                onClosed.run();
            });
            dialog.show();
        } catch (Throwable t) {
            XposedBridge.log(logTag + " settings dialog failed: " + t);
        }
    }
}
