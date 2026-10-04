package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import com.eza.spicyex.settings.SettingsPanel;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.ui.VsyncFrameScheduler;
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
    private final java.util.function.IntConsumer onOpenLayoutEditor;
    private final Runnable onResyncTiming;
    private final String logTag;
    private Dialog currentDialog;
    private PanelSurface currentSurface;
    private SettingsPanel currentPanel;

    LyricsSettingsDialogController(
            Activity activity,
            VsyncFrameScheduler frameScheduler,
            LyricsAmbientController ambientController,
            LyricsHost host,
            Runnable onClosed,
            java.util.function.IntConsumer onOpenLayoutEditor,
            Runnable onResyncTiming,
            String logTag
    ) {
        this.activity = activity;
        this.frameScheduler = frameScheduler;
        this.ambientController = ambientController;
        this.host = host;
        this.onClosed = onClosed;
        this.onOpenLayoutEditor = onOpenLayoutEditor;
        this.onResyncTiming = onResyncTiming;
        this.logTag = logTag;
    }

    // Sticky across opens: half mode anchors the panel to the top so lyrics preview underneath.
    private static boolean halfMode;

    boolean show() {
        if (isShowing()) return true;
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            PanelSurface.configureWindow(dialog.getWindow());

            final PanelSurface[] surfaceRef = new PanelSurface[1];
            final View[] panelRef = new View[1];
            // The dialog is its own window, layered above the activity's by the platform
            // regardless of view z-order inside either one - the layout editor's overlay lives
            // in the activity's hierarchy (see LyricsLayoutEditController), so opening it while
            // this dialog's window is still up leaves it added but invisible underneath. Record
            // the request instead of acting on it immediately, and run it from the dismiss
            // listener below, once this window is actually gone.
            int[] openLayoutEditorPending = {0};
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
                    mode -> openLayoutEditorPending[0] = mode,
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

            // Re-fit when the window changes shape while open (rotation, fold/unfold, split
            // screen); the dialog outlives those changes, and a size fitted to the old shape
            // is wrong in the new one.
            surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if ((r - l) != (or - ol) || (b - t) != (ob - ot)) applyCardSize(panelView, halfMode);
            });

            dialog.setOnDismissListener(d -> {
                if (currentDialog == dialog) {
                    currentDialog = null;
                    currentSurface = null;
                    currentPanel = null;
                }
                frameScheduler.start();
                // Source toggles and order are saved inside the panel; the session re-seats the
                // current track from stored candidates instead of waiting for the next track.
                host.reconcileLyricsSources();
                onClosed.run();
                if (openLayoutEditorPending[0] != 0) {
                    int mode = openLayoutEditorPending[0];
                    openLayoutEditorPending[0] = 0;
                    if (onOpenLayoutEditor != null) onOpenLayoutEditor.accept(mode);
                }
            });

            dialog.show();
            currentDialog = dialog;
            currentSurface = surface;
            currentPanel = panel;
            PanelSurface.configureWindow(dialog.getWindow());
            surface.enter(halfMode ? 0.1f : 0.5f);
            return true;
        } catch (Throwable t) {
            XpLog.log(logTag + " settings dialog failed: " + t);
            if (currentDialog != null) currentDialog.dismiss();
            return false;
        }
    }

    boolean showSection(String id) {
        return show() && currentPanel != null && currentPanel.openSection(id);
    }

    boolean isShowing() {
        return currentDialog != null && currentDialog.isShowing();
    }

    boolean close() {
        if (!isShowing() || currentSurface == null) return false;
        currentSurface.exit(null);
        return true;
    }

    /** A settings list reads best at phone width; wider just makes every row a long empty bar. */
    private static final int PANEL_MAX_WIDTH_DP = 560;
    private static final int PANEL_MAX_HEIGHT_DP = 860;
    /** From this width the half mode becomes a side sheet instead of a top strip. */
    private static final int SIDE_SHEET_MIN_WIDTH_DP = 600;

    /**
     * Fits the panel to the window it is in. Always a centred card of at most phone width, so a
     * landscape phone, an unfolded foldable or a tablet gets a readable panel rather than one
     * stretched edge to edge. Half mode keeps the lyrics visible: on a phone the panel docks to
     * the top, and wherever there is room beside it it becomes a side sheet, since a top strip on
     * a short landscape screen leaves space for neither.
     */
    private void applyCardSize(View card, boolean half) {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float density = dm.density;
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int maxW = Math.round(PANEL_MAX_WIDTH_DP * density);
        boolean sideSheet = half && screenW / density >= SIDE_SHEET_MIN_WIDTH_DP;
        int w;
        int h;
        int gravity;
        if (sideSheet) {
            w = Math.min(maxW, Math.round(screenW * 0.48f));
            h = Math.round(screenH * 0.94f);
            gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        } else if (half) {
            w = Math.min(maxW, Math.round(screenW * 0.94f));
            h = Math.round(screenH * 0.45f);
            gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        } else {
            w = Math.min(maxW, Math.round(screenW * 0.92f));
            boolean shortScreen = screenH < screenW;
            h = Math.min(Math.round(PANEL_MAX_HEIGHT_DP * density),
                    Math.round(screenH * (shortScreen ? 0.92f : 0.84f)));
            gravity = android.view.Gravity.CENTER;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(w, h);
        } else {
            lp.width = w;
            lp.height = h;
        }
        lp.gravity = gravity;
        card.setLayoutParams(lp);
    }
}
