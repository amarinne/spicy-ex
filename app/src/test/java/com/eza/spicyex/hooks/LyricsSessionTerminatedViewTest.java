package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.*;
import com.eza.spicyex.lyrics.providers.SpicyOrgAccessState;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.*;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricsSessionTerminatedViewTest {
    @Test public void cachedLoadCannotApplyAnAccessSnapshotTakenBeforeTermination() throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsSessionManager manager = new LyricsSessionManager(context, null, null, null, () -> 0L);
        Constructor<?> constructor = LyricsCatalog.View.class.getDeclaredConstructor(String.class,
                CatalogState.class, CatalogPolicy.class, CatalogResolver.Resolution.class,
                AcquisitionPlanner.Plan.class, LyricsDocument.class, int.class, boolean.class,
                long.class, String.class);
        constructor.setAccessible(true);
        CatalogPolicy oldPolicy = new CatalogPolicy(Collections.emptyList(), false);
        LyricsCatalog.View old = (LyricsCatalog.View) constructor.newInstance("track",
                CatalogState.empty("track"), oldPolicy, null, null, null, 1, true, 1L, "");
        assertTrue(SpicyOrgAccessState.terminate(context,
                SpicyOrgAccessState.beginRequest(context, false), "key_revoked"));
        Method apply = LyricsSessionManager.class.getDeclaredMethod("applyView", LyricsCatalog.View.class);
        apply.setAccessible(true);
        assertEquals(false, apply.invoke(manager, old));
        Field sequence = LyricsSessionManager.class.getDeclaredField("appliedViewSequence");
        sequence.setAccessible(true);
        assertEquals(0L, sequence.getLong(manager));
        assertFalse(oldPolicy.hasCurrentOrgAccess(context));
    }
}
