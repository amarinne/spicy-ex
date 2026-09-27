package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import com.eza.spicyex.SettingsPanel;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.beautifullyrics.entities.VsyncFrameScheduler;
import com.eza.spicyex.lyrics.LyricsAmbientController;
import com.eza.spicyex.ui.Motion;
import com.eza.spicyex.ui.PanelSurface;

import com.eza.spicyex.xposed.XpLog;

/** Owns the in-Spotify settings modal lifecycle and render-loop pause/resume. */
final class LyricsSettingsDialogController {
    private final Activity activity;
    private final VsyncFrameScheduler frameScheduler;
    private final LyricsAmbientController ambientController;
    private final LyricsHost host;
    private final Runnable onClosed;
    private final Runnable onResyncTiming;
    private final String logTag;

    LyricsSettingsDialogController(
            Activity activity,
            VsyncFrameScheduler frameScheduler,
            LyricsAmbientController ambientController,
            LyricsHost host,
            Runnable onClosed,
            Runnable onResyncTiming,
            String logTag
    ) {
        this.activity = activity;
        this.frameScheduler = frameScheduler;
        this.ambientController = ambientController;
        this.host = host;
        this.onClosed = onClosed;
        this.onResyncTiming = onResyncTiming;
        this.logTag = logTag;
    }

    // Sticky across opens: half mode anchors the panel to the top so lyrics preview underneath.
    private static boolean halfMode;

    void show() {
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            PanelSurface.configureWindow(dialog.getWindow());

            final PanelSurface[] surfaceRef = new PanelSurface[1];
            final View[] panelRef = new View[1];
            SettingsPanel panel = new SettingsPanel(activity, new SettingsStore(activity),
                    () -> halfMode, () -> {
                        halfMode = !halfMode;
                        PanelSurface surface = surfaceRef[0];
                        View card = panelRef[0];
                        if (surface != null && card != null) {
                            float targetDim = halfMode ? 0.1f : 0.5f;
                            if (Motion.animationsEnabled()) {
                                TransitionManager.beginDelayedTransition(surface,
                                        new ChangeBounds().setDuration(Motion.dur(Motion.BASE)));
                                surface.animateDim(targetDim, Motion.dur(Motion.BASE));
                            } else {
                                surface.setDim(targetDim);
                            }
                            applyCardSize(card, halfMode);
                        }
                    }, () -> {
                        if (surfaceRef[0] != null) {
                            surfaceRef[0].exit(null);
                        }
                    },
                    host::clearLyricsCache, onResyncTiming);
            panel.setLyricsHost(host);
            final View panelView = panel.build();
            panelRef[0] = panelView;

            PanelSurface surface = new PanelSurface(activity, dialog, panelView,
                    halfMode ? 0.1f : 0.5f);
            surfaceRef[0] = surface;

            applyCardSize(panelView, halfMode);

            // Back and outside-scrim taps route through the unified animated exit.
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    if (surfaceRef[0] != null) {
                        surfaceRef[0].exit(null);
                    }
                    return true;
                }
                return false;
            });

            dialog.setContentView(surface);

            dialog.setOnDismissListener(d -> {
                frameScheduler.start();
                // Source toggles and order are saved inside the panel; the session re-seats the
                // current track from stored candidates instead of waiting for the next track.
                host.reconcileLyricsSources();
                onClosed.run();
            });

            dialog.show();
            PanelSurface.configureWindow(dialog.getWindow());
            surface.enter(halfMode ? 0.1f : 0.5f);
        } catch (Throwable t) {
            XpLog.log(logTag + " settings dialog failed: " + t);
        }
    }

    private void applyCardSize(View card, boolean half) {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int w = (int) (dm.widthPixels * 0.92f);
        int h = (int) (dm.heightPixels * (half ? 0.45f : 0.84f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(w, h);
        } else {
            lp.width = w;
            lp.height = h;
        }
        lp.gravity = half ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER;
        card.setLayoutParams(lp);
    }
}
