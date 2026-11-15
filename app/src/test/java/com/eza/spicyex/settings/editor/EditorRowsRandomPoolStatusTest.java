package com.eza.spicyex.settings.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The random-pool status line keeps session state, pending picks, and the saved pool apart. */
public class EditorRowsRandomPoolStatusTest {
    @Test public void statusKeepsPendingChoicesSeparateFromThePersistedPool() {
        assertEquals("open=true query=mesh pending=kawarp,god-rays persisted=kawarp,mesh-gradient shown=2",
                EditorRows.randomPoolStatus(true, "mesh", "kawarp,god-rays",
                        "kawarp,mesh-gradient", 2));
        assertEquals("open=false query=- pending=none persisted=kawarp shown=0",
                EditorRows.randomPoolStatus(false, "", "none", "kawarp", 0));
    }
}
