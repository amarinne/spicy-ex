package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoCompositionRecoveryTest {
    static class Scope {
        int calls;
        boolean broken;
        public void invalidate() { calls++; if (broken) throw new IllegalStateException(); }
    }
    static class Table { public Object[] slots; Table(Object... values) { slots = values; } }
    static class Composer { public Table committed, inserting; }

    private AutoCompositionRecovery recovery() throws Exception {
        return new AutoCompositionRecovery(new java.lang.reflect.Field[]{
                Composer.class.getDeclaredField("committed"), Composer.class.getDeclaredField("inserting")},
                Table.class.getDeclaredField("slots"), Scope.class.getDeclaredMethod("invalidate"));
    }

    @Test public void mediaObservationRecoversAnAlreadyComposedWeatherSiblingOnce() throws Exception {
        Scope weather = new Scope(), media = new Scope();
        Composer composer = new Composer();
        composer.committed = new Table("native data", weather, media);
        composer.inserting = new Table(media);
        AutoCompositionRecovery recovery = recovery();
        recovery.remember(composer);
        assertEquals(0, weather.calls); // Nothing invalidates during a composable hook.
        recovery.recover(); recovery.remember(composer); recovery.recover();
        assertEquals(1, weather.calls); assertEquals(1, media.calls);
        assertEquals(1, recovery.compositions); assertEquals(2, recovery.scopes);
        assertEquals(0, recovery.errors);
    }

    @Test public void aBrokenScopeDoesNotPreventOtherScopesOrLoop() throws Exception {
        Scope broken = new Scope(), weather = new Scope(); broken.broken = true;
        Composer composer = new Composer(); composer.committed = new Table(broken, weather);
        AutoCompositionRecovery recovery = recovery(); recovery.remember(composer);
        recovery.recover(); recovery.recover();
        assertEquals(1, broken.calls); assertEquals(1, weather.calls);
        assertEquals(1, recovery.errors);
    }

    @Test public void newlyObservedCompositionHasItsOwnSingleRecoveryAndWorkIsBounded() throws Exception {
        AutoCompositionRecovery recovery = recovery();
        for (int i = 0; i < 20; i++) {
            Composer composer = new Composer(); composer.committed = new Table(new Scope());
            recovery.remember(composer); recovery.recover();
        }
        assertEquals(8, recovery.compositions);
        assertEquals(8, recovery.scopes);
    }

    @Test public void oversizedTableIsReportedWithoutAnUnboundedScan() throws Exception {
        Composer composer = new Composer(); composer.committed = new Table(new Object[32769]);
        AutoCompositionRecovery recovery = recovery(); recovery.remember(composer); recovery.recover();
        assertEquals(1, recovery.errors); assertEquals(0, recovery.scopes);
    }

    @Test public void aNewConnectionGetsOneFreshAttemptForTheSameComposition() throws Exception {
        Scope weather = new Scope();
        Composer composer = new Composer(); composer.committed = new Table(weather);
        AutoCompositionRecovery recovery = recovery(); recovery.remember(composer);
        recovery.recover(); recovery.recover();
        assertEquals(1, weather.calls);
        // The first connection's attempt proves nothing about the next native composition.
        recovery.reset(); recovery.recover(); recovery.recover();
        assertEquals(2, weather.calls);
        assertEquals(2, recovery.compositions);
    }

    @Test public void aScopeThatAppearsAfterTheFirstScanIsRefreshedNextConnection() throws Exception {
        Composer composer = new Composer(); composer.committed = new Table();
        AutoCompositionRecovery recovery = recovery(); recovery.remember(composer);
        recovery.recover();
        Scope weather = new Scope();
        composer.committed = new Table(weather);
        recovery.recover();
        assertEquals(0, weather.calls);
        recovery.reset(); recovery.recover();
        assertEquals(1, weather.calls);
    }

    @Test public void aConnectionResetStaysBoundedAndDoesNotGrowTheComposerSet() throws Exception {
        AutoCompositionRecovery recovery = recovery();
        for (int i = 0; i < 20; i++) {
            Composer composer = new Composer(); composer.committed = new Table(new Scope());
            recovery.remember(composer);
        }
        for (int i = 0; i < 5; i++) { recovery.reset(); recovery.recover(); }
        assertEquals(40, recovery.compositions);
        assertEquals(40, recovery.scopes);
    }
}
