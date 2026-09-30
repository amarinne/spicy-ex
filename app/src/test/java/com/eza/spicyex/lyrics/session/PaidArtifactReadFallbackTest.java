package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PaidArtifactReadFallbackTest {
    @Test public void postMigrationReadFailureCannotResurrectLegacyOutput() {
        AtomicInteger legacyReads = new AtomicInteger();
        assertNull(AIPaidArtifactCache.read(() -> true,
                () -> { throw new IllegalStateException("read failed after clear"); },
                () -> {
                    legacyReads.incrementAndGet();
                    return new AIPaidArtifactCache.Read("deleted answer", "", "", -1, -1, -1);
                }));
        assertEquals(0, legacyReads.get());
    }

    @Test public void unknownMigrationStateCannotAuthorizeLegacyReuse() {
        assertNull(AIPaidArtifactCache.read(
                () -> { throw new IllegalStateException("database unavailable"); },
                () -> { throw new AssertionError("must not read current storage"); },
                () -> { throw new AssertionError("must not read legacy storage"); }));
    }

    @Test public void confirmedIncompleteMigrationPreservesReadOnlyLegacyReuse() {
        AIPaidArtifactCache.Read legacy = new AIPaidArtifactCache.Read("paid answer", "", "", -1, -1, -1);
        assertSame(legacy, AIPaidArtifactCache.read(() -> false,
                () -> { throw new AssertionError("migration incomplete"); }, () -> legacy));
        assertFalse(legacy.matches("key", "MEANING", 0, 0, 0));
    }
}
