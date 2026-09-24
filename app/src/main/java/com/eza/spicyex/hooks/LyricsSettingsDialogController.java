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
    private final java.util.function.IntConsumer onOpenLayoutEditor;
    private final Runnable onResyncTiming;
    private final String logTag;

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

    void show() {
        try {
            Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
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
                        applySize(window);
                    }, () -> Motion.exitCardThen(panelRef[0], dialog::isShowing, dialog::dismiss),
                    mode -> openLayoutEditorPending[0] = mode, host::clearLyricsCache, onResyncTiming);
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
            // Re-fit when the window changes shape while open (rotation, fold/unfold, split
            // screen); the dialog outlives those changes, and a size fitted to the old shape
            // is wrong in the new one.
            android.widget.FrameLayout root = new android.widget.FrameLayout(activity) {
                @Override
                protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    applySize(window);
                }
            };
            root.addView(panelView, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            dialog.setContentView(root);
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                applySize(window);
            }
            dialog.setOnDismissListener(d -> {
                frameScheduler.start();
                onClosed.run();
                if (openLayoutEditorPending[0] != 0) {
                    int mode = openLayoutEditorPending[0];
                    openLayoutEditorPending[0] = 0;
                    if (onOpenLayoutEditor != null) onOpenLayoutEditor.accept(mode);
                }
            });
            dialog.show();
            Motion.enterCard(panelView);
        } catch (Throwable t) {
            XpLog.log(logTag + " settings dialog failed: " + t);
        }
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
    private void applySize(Window window) {
        if (window == null) return;
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float density = dm.density;
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int maxW = Math.round(PANEL_MAX_WIDTH_DP * density);
        boolean sideSheet = halfMode && screenW / density >= SIDE_SHEET_MIN_WIDTH_DP;
        int w;
        int h;
        int gravity;
        if (sideSheet) {
            w = Math.min(maxW, Math.round(screenW * 0.48f));
            h = Math.round(screenH * 0.94f);
            gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        } else if (halfMode) {
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
        window.setLayout(w, h);
        window.setGravity(gravity);
        window.setDimAmount(halfMode ? 0.1f : 0.5f);
    }
}
