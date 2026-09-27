package com.eza.spicyex.hooks;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Top-level lifecycle shell for the native Spicy lyrics renderer. */
final class NativeSpicyShellView extends FrameLayout {
    private final NativeSpicyShellViewImpl delegate;

    NativeSpicyShellView(LyricsHost host, Activity activity) {
        super(activity);
        delegate = new NativeSpicyShellViewImpl(host, activity);
        addView(delegate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void start() {
        delegate.start();
    }

    void stop() {
        delegate.stop();
    }

    /** Lets the layout editor take a back press first (close its sheet, then itself). */
    boolean consumeBack() {
        return delegate.consumeBack();
    }
}
