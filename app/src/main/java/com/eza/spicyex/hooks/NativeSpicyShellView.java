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

    // -- agent layout probe (debug only) ---------------------------------------
    // Thin forwards so the command channel can find the shell by walking the decor view and talk
    // to it without reaching into the delegate. See AgentCommandChannel for the gate.

    boolean agentOpenEditor(boolean card) {
        return delegate.agentOpenEditor(card);
    }

    boolean agentCloseEditor() {
        return delegate.agentCloseEditor();
    }

    boolean agentSelectElement(String name) {
        return delegate.agentSelectElement(name);
    }

    /** One JSON line of live geometry plus rule violations, or null if it could not be built. */
    String agentLayoutReport() {
        return delegate.agentLayoutReport();
    }
}
