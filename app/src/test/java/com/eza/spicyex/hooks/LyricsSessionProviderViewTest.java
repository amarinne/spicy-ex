package com.eza.spicyex.hooks;

import static org.junit.Assert.*;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogState;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;

public class LyricsSessionProviderViewTest {
    @Test public void staleUnseatedDeliveryCannotSettleANewerLoad() throws Exception {
        LyricsSessionManager manager = new LyricsSessionManager(
                new FakeAndroidContext(), null, null, null, () -> 0L);
        LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
        policy.adoptTrack("spotify:track:test");
        set(manager, "loadingUri", policy.trackUri());
        set(manager, "appliedViewSequence", 2L);
        CatalogPolicy disabled = new CatalogPolicy(Collections.emptyList(), false);
        set(manager, "loadedPolicy", disabled);
        Constructor<?> constructor = LyricsCatalog.View.class.getDeclaredConstructor(String.class,
                CatalogState.class, CatalogPolicy.class,
                com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution.class,
                com.eza.spicyex.lyrics.catalog.AcquisitionPlanner.Plan.class, LyricsDocument.class,
                int.class, boolean.class, long.class, String.class);
        constructor.setAccessible(true);
        LyricsCatalog.View old = (LyricsCatalog.View) constructor.newInstance("test",
                CatalogState.empty("test"), new CatalogPolicy(Collections.emptyList(), false),
                null, null, null, 1, false, 1L, "storage-failed");

        // The empty payload isolates settlement from rendering. Both use this same acceptance
        // boundary after the real provider's nonempty-payload check and dispatcher post.
        manager.acceptProviderView(null, policy.trackUri(), policy.generation(),
                new LyricsDocument(), null, false, old, false);

        assertEquals(policy.trackUri(), get(manager, "loadingUri"));
        assertEquals(2L, get(manager, "appliedViewSequence"));
        assertSame(disabled, get(manager, "loadedPolicy"));
        assertNull(get(manager, "document"));
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
