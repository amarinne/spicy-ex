package com.eza.spicyex.hooks;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Top-level lifecycle shell for the native Spicy lyrics renderer. */
final class NativeSpicyShellView extends FrameLayout {
    private final NativeSpicyShellViewImpl delegate;

    NativeSpicyShellView(LyricsHost host, Activity activity) {
        this(host, activity, NativeSpicyShellViewImpl.PIP_LAYOUT_NONE);
    }

    /** @param pipLayout one of NativeSpicyShellViewImpl.PIP_LAYOUT_*: how a picture-in-picture
     *  host lays the screen out (fixed for the shell's lifetime, like the two-column choice). */
    NativeSpicyShellView(LyricsHost host, Activity activity, int pipLayout) {
        super(activity);
        delegate = new NativeSpicyShellViewImpl(host, activity, pipLayout);
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

    /** Controls-free presentation for a picture-in-picture window, laid out for a full screen
     *  {@code screenHeightPx} tall whose top {@code cropTopPx} rows are not shown. */
    void setPipPresentation(int screenHeightPx, int cropTopPx) {
        delegate.setPipPresentation(screenHeightPx, cropTopPx);
    }

    /** Lyrics (or a no-lyrics state) are on screen, not just the empty shell. */
    boolean hasLyricsDocument() {
        return delegate.hasLyricsDocument();
    }

    /** Lets the layout editor take a back press first (close its sheet, then itself). */
    boolean consumeBack() {
        return delegate.consumeBack();
    }
}
