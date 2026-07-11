package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Per-renderer frame style deduplication. Batching these high-frequency property writes keeps the
 * vsync path from repeatedly setting the same values, and owning the queues per shell prevents one
 * destroyed lyrics view from pinning another shell's views through static strong references.
 */
public final class FrameStyleBatcher {
    private final WeakHashMap<View, AppliedStyleState> styleCache = new WeakHashMap<>();
    private final ArrayList<AppliedStyleState> styleQueue = new ArrayList<>();
    private final float density;

    public FrameStyleBatcher(Context context) {
        density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
    }

    public void applyAlphaIfChanged(View view, float alpha) {
        queueFloatStyle(view, StyleField.ALPHA, alpha, 0.01f);
    }

    public void applyScaleIfChanged(View view, float scaleX, float scaleY) {
        queueFloatStyle(view, StyleField.SCALE_X, scaleX, 0.002f);
        queueFloatStyle(view, StyleField.SCALE_Y, scaleY, 0.002f);
    }

    public void applyTranslationYIfChanged(View view, float translationY) {
        queueFloatStyle(view, StyleField.TRANSLATION_Y, translationY, 0.5f);
    }

    public void queueBlurIfChanged(View view, float blurPx, float epsilon) {
        queueFloatStyle(view, StyleField.BLUR, blurPx, epsilon);
    }

    public void clearPendingWrites() {
        for (AppliedStyleState state : styleQueue) state.clearPending();
        styleQueue.clear();
    }

    public void flush() {
        if (styleQueue.isEmpty()) return;
        for (AppliedStyleState state : styleQueue) {
            View view = state.view.get();
            if (view != null && view.isAttachedToWindow()) {
                if ((state.pendingFields & ALPHA_BIT) != 0) view.setAlpha(state.alpha);
                if ((state.pendingFields & SCALE_X_BIT) != 0) view.setScaleX(state.scaleX);
                if ((state.pendingFields & SCALE_Y_BIT) != 0) view.setScaleY(state.scaleY);
                if ((state.pendingFields & TRANSLATION_Y_BIT) != 0) view.setTranslationY(state.translationY);
                if ((state.pendingFields & BLUR_BIT) != 0) applyBlurEffectImmediate(view, state.blurPx);
            }
            state.clearPending();
        }
        styleQueue.clear();
    }

    public void invalidateRecursive(View view) {
        if (view == null) return;
        AppliedStyleState state = styleCache.remove(view);
        if (state != null && state.queued) {
            styleQueue.remove(state);
            state.clearPending();
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            invalidateRecursive(group.getChildAt(i));
        }
    }

    private void queueFloatStyle(View view, StyleField field, float value, float epsilon) {
        if (view == null) return;
        AppliedStyleState applied = styleCache.get(view);
        if (applied == null) {
            applied = new AppliedStyleState(view);
            styleCache.put(view, applied);
        }
        if (applied.isUnchanged(field, value, epsilon)) return;
        applied.set(field, value);
        if (!applied.queued) {
            applied.queued = true;
            styleQueue.add(applied);
        }
    }

    private void applyBlurEffectImmediate(View view, float blurPx) {
        if (view == null || Build.VERSION.SDK_INT < 31) return;
        if (blurPx <= 0.05f) {
            view.setRenderEffect(null);
        } else {
            float radius = blurPx * density;
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    private enum StyleField { ALPHA, SCALE_X, SCALE_Y, TRANSLATION_Y, BLUR }

    private static final int ALPHA_BIT = 1;
    private static final int SCALE_X_BIT = 1 << 1;
    private static final int SCALE_Y_BIT = 1 << 2;
    private static final int TRANSLATION_Y_BIT = 1 << 3;
    private static final int BLUR_BIT = 1 << 4;

    private static class AppliedStyleState {
        final WeakReference<View> view;
        float alpha;
        float scaleX;
        float scaleY;
        float translationY;
        float blurPx;
        int initializedFields;
        int pendingFields;
        boolean queued;

        AppliedStyleState(View view) {
            this.view = new WeakReference<>(view);
        }

        boolean isUnchanged(StyleField field, float value, float epsilon) {
            int bit = bit(field);
            return (initializedFields & bit) != 0 && Math.abs(get(field) - value) <= epsilon;
        }

        float get(StyleField field) {
            switch (field) {
                case ALPHA: return alpha;
                case SCALE_X: return scaleX;
                case SCALE_Y: return scaleY;
                case TRANSLATION_Y: return translationY;
                case BLUR: return blurPx;
            }
            return 0f;
        }

        void set(StyleField field, float value) {
            switch (field) {
                case ALPHA: alpha = value; break;
                case SCALE_X: scaleX = value; break;
                case SCALE_Y: scaleY = value; break;
                case TRANSLATION_Y: translationY = value; break;
                case BLUR: blurPx = value; break;
            }
            int bit = bit(field);
            initializedFields |= bit;
            pendingFields |= bit;
        }

        void clearPending() {
            pendingFields = 0;
            queued = false;
        }

        private static int bit(StyleField field) {
            switch (field) {
                case ALPHA: return ALPHA_BIT;
                case SCALE_X: return SCALE_X_BIT;
                case SCALE_Y: return SCALE_Y_BIT;
                case TRANSLATION_Y: return TRANSLATION_Y_BIT;
                case BLUR: return BLUR_BIT;
            }
            return 0;
        }
    }
}
