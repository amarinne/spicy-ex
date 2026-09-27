package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Full-screen transparent dialog surface hosting a card and its backdrop dim on a single timeline.
 *
 * <p>Replaces the platform window dim and window animations. With those, the dim kept fading after
 * the card had gone, and the window animation overlapped the card's own entrance.
 */
public final class PanelSurface extends FrameLayout {

    private final Dialog dialog;
    private final View card;
    private final ColorDrawable dimDrawable;
    private float currentDim;
    private ValueAnimator dimAnimator;
    private boolean exiting;
    private Runnable availableSizeChanged;
    private int availableWidth;
    private int availableHeight;

    public void onAvailableSizeChanged(Runnable callback) {
        availableSizeChanged = callback;
    }

    public int availableWidth() { return availableWidth; }
    public int availableHeight() { return availableHeight; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int height = Math.max(1, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
        if (width != availableWidth || height != availableHeight) {
            availableWidth = width;
            availableHeight = height;
            if (availableSizeChanged != null) availableSizeChanged.run();
        }
        // Fixed-size settings cards must also fit above the keyboard. Keep their preferred size
        // for the next measure, so hiding the keyboard restores the original panel height.
        ViewGroup.LayoutParams params = card.getLayoutParams();
        int preferredWidth = params.width;
        int preferredHeight = params.height;
        if (params.width > width) params.width = width;
        if (params.height > height) params.height = height;
        super.onMeasure(widthSpec, heightSpec);
        params.width = preferredWidth;
        params.height = preferredHeight;
    }

    public PanelSurface(Context context, Dialog dialog, View card, float initialDim) {
        super(context);
        this.dialog = dialog;
        this.card = card;
        this.currentDim = 0f;
        this.dimDrawable = new ColorDrawable(Color.BLACK);
        this.dimDrawable.setAlpha(0);
        setBackground(dimDrawable);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        dialog.setCanceledOnTouchOutside(false);
        card.setClickable(true);
        addView(card);

        setOnClickListener(v -> exit(null));
        // The dim covers the whole window, but the card must stay clear of the system bars and the
        // keyboard. Edge-to-edge host windows ignore SOFT_INPUT_ADJUST_RESIZE, so pad by insets.
        setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    public static void configureWindow(Window window) {
        if (window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setWindowAnimations(0);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    public View getCard() {
        return card;
    }

    public boolean isExiting() {
        return exiting;
    }

    public void setDim(float dim) {
        if (dimAnimator != null) {
            dimAnimator.cancel();
            dimAnimator = null;
        }
        currentDim = Math.max(0f, Math.min(1f, dim));
        dimDrawable.setAlpha((int) (currentDim * 255 + 0.5f));
        invalidate();
    }

    public float getDim() {
        return currentDim;
    }

    public void animateDim(float targetDim, int duration) {
        if (dimAnimator != null) {
            dimAnimator.cancel();
            dimAnimator = null;
        }
        float clamped = Math.max(0f, Math.min(1f, targetDim));
        if (!Motion.animationsEnabled()) {
            setDim(clamped);
            return;
        }
        float start = currentDim;
        dimAnimator = ValueAnimator.ofFloat(start, clamped);
        dimAnimator.setDuration(duration);
        dimAnimator.setInterpolator(clamped > start ? Motion.decel() : Motion.accel());
        dimAnimator.addUpdateListener(anim -> {
            currentDim = (Float) anim.getAnimatedValue();
            dimDrawable.setAlpha((int) (currentDim * 255 + 0.5f));
            invalidate();
        });
        dimAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dimAnimator = null;
            }
        });
        dimAnimator.start();
    }

    public void enter(float targetDim) {
        exiting = false;
        if (!Motion.animationsEnabled()) {
            setDim(targetDim);
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setAlpha(1f);
            return;
        }
        setDim(0f);
        animateDim(targetDim, Motion.dur(Motion.BASE));
        Motion.enterCard(card);
    }

    public void exit(Runnable afterDismiss) {
        if (exiting) return;
        exiting = true;
        Runnable dismissAction = () -> {
            try {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (Throwable ignored) {}
            if (afterDismiss != null) {
                afterDismiss.run();
            }
        };
        if (!Motion.animationsEnabled()) {
            setDim(0f);
            dismissAction.run();
            return;
        }
        animateDim(0f, Motion.dur(Motion.EXIT));
        Motion.exitCardThen(card, dialog::isShowing, dismissAction);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (dimAnimator != null) {
            dimAnimator.cancel();
            dimAnimator = null;
        }
    }
}
