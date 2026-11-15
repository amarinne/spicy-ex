package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.AcquisitionPlanner;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogResolver;
import com.eza.spicyex.lyrics.catalog.CatalogState;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * F7: the session treats an unreadable catalog snapshot as unavailable storage, never as a durable
 * empty catalog. It keeps the current seat and uses the designed fallback acquisition walk.
 */
public class LyricsSessionCatalogReadFailureTest {
    private static final String URI = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";

    @Test public void unreadableViewCannotApplyOrAdvanceThePublishedOrder() throws Exception {
        LyricsSessionManager manager = manager();
        CatalogPolicy policy = new CatalogPolicy(Collections.emptyList(), false);

        assertFalse(applyView(manager, view(CatalogState.empty("test"), policy, false, 2L)));
        assertEquals(0L, get(manager, "appliedViewSequence"));
        assertNull(get(manager, "loadedPolicy"));

        assertTrue(applyView(manager, view(CatalogState.empty("test"), policy, true, 3L)));
        assertEquals(3L, get(manager, "appliedViewSequence"));
        assertSame(policy, get(manager, "loadedPolicy"));
    }

    @Test public void unreadableViewUsesTheFallbackWalkInsteadOfAnEmptyStatePlan() throws Exception {
        LyricsSessionManager manager = manager();
        CatalogPolicy policy = new CatalogPolicy(Collections.emptyList(), false);

        call(manager, "schedule", new Class<?>[]{LyricsCatalog.View.class, boolean.class},
                view(CatalogState.empty("test"), policy, false, 1L), false);
        AcquisitionPlanner.Plan fallback = (AcquisitionPlanner.Plan) get(manager, "pendingPlan");
        assertNotNull(fallback);
        assertTrue(fallback.fetches());
        assertEquals("owner-refresh-ordered", fallback.reason);
        assertTrue(fallback.scope.sourceOrderMode);
        assertEquals(3, fallback.scope.sources.size());

        call(manager, "schedule", new Class<?>[]{LyricsCatalog.View.class, boolean.class},
                view(CatalogState.empty("test"), CatalogPolicy.read(managerContext(manager)), true, 2L),
                false);
        AcquisitionPlanner.Plan empty = (AcquisitionPlanner.Plan) get(manager, "pendingPlan");
        assertNotNull(empty);
        assertTrue(empty.fetches());
        assertEquals("no-seat", empty.reason);
        assertEquals(1, empty.scope.sources.size());
    }

    @Test public void unreadableSeatCannotReplaceTheDisplayedDocument() throws Exception {
        LyricsSessionManager manager = manager();
        LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
        policy.adoptTrack(URI);
        LyricsDocument displayed = document();
        set(manager, "document", displayed);

        manager.acceptProviderView(null, URI, policy.generation(), new LyricsDocument(), null,
                false, view(CatalogState.empty("test"),
                        new CatalogPolicy(Collections.emptyList(), false), false, 2L),
                false, 0L);

        assertSame(displayed, get(manager, "document"));
        assertEquals(0L, get(manager, "appliedViewSequence"));
        assertNull(get(manager, "loadedPolicy"));
    }

    private static LyricsSessionManager manager() {
        return new LyricsSessionManager(new FakeAndroidContext(), null, null, null, () -> 0L);
    }

    private static FakeAndroidContext managerContext(LyricsSessionManager manager) throws Exception {
        return (FakeAndroidContext) get(manager, "context");
    }

    private static LyricsDocument document() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "aaaaaaaaaaaaaaaaaaaaaa";
        doc.fetchSource = "spotify_native";
        doc.provider = "Spotify";
        doc.type = "Line";
        LyricsLine line = new LyricsLine();
        line.text = "Hello";
        line.startMs = 1000;
        line.endMs = 2000;
        doc.lines.add(line);
        return doc;
    }

    private static LyricsCatalog.View view(CatalogState state, CatalogPolicy policy,
                                           boolean readable, long sequence) throws Exception {
        Constructor<LyricsCatalog.View> constructor = LyricsCatalog.View.class.getDeclaredConstructor(
                String.class, CatalogState.class, CatalogPolicy.class,
                CatalogResolver.Resolution.class, AcquisitionPlanner.Plan.class,
                LyricsDocument.class, int.class, boolean.class, boolean.class, long.class,
                String.class);
        constructor.setAccessible(true);
        AcquisitionPlanner.Plan plan = readable
                ? AcquisitionPlanner.plan(state, policy, null, System.currentTimeMillis(), true)
                : null;
        return constructor.newInstance("test", state, policy, null, plan, null, 1, readable,
                readable, sequence, "");
    }

    private static boolean applyView(LyricsSessionManager manager, LyricsCatalog.View view)
            throws Exception {
        Method method = LyricsSessionManager.class.getDeclaredMethod("applyView",
                LyricsCatalog.View.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(manager, view);
    }

    private static Object call(Object owner, String name, Class<?>[] parameters, Object... values)
            throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(owner, values);
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
