package com.eza.spicyex.hooks;

/** Small, testable invariants shared by runtime controllers while the layout editor is attached. */
final class LyricsLayoutEditorRuntimePolicy {
    private LyricsLayoutEditorRuntimePolicy() {
    }

    static boolean chromeAutoHideAllowed(boolean editorAttached, int timeoutSeconds) {
        return !editorAttached && timeoutSeconds > 0;
    }

    static boolean chipShouldBeVisible(boolean requestedVisible, boolean editorPinnedVisible) {
        return editorPinnedVisible || requestedVisible;
    }
}
