package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogTerminatedAccessTest {
    private final CatalogCandidate org = candidate("org", SourceId.SPICY_ORG);
    private final CatalogCandidate apple = candidate("apple", SourceId.APPLE);

    private CatalogPolicy policy(boolean terminated) {
        return new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG, SourceId.APPLE),
                false, false, false, 0, terminated, terminated ? 1 : 0);
    }

    @Test public void terminationBlocksAutoAndBothManualPinFormsWithoutRemovingCandidates() {
        for (CatalogSelection pin : Arrays.asList(CatalogSelection.auto("track"),
                new CatalogSelection("track", SelectionMode.MANUAL, "org", SourceId.SPICY_ORG, "item", "org"),
                new CatalogSelection("track", SelectionMode.MANUAL, "", SourceId.SPICY_ORG, "item", "org"))) {
            CatalogState state = new CatalogState("track", Arrays.asList(org, apple), null, pin, null, true);
            assertSame(org, CatalogDecisions.render(state, policy(false)).winner);
            assertSame(apple, CatalogDecisions.render(state, policy(true)).winner);
            assertEquals(2, state.candidates.size());
            assertSame(pin, state.selection);
        }
    }

    @Test public void aLateOrgDeliveryIsRefusedAndDirectAppleStillCommits() {
        CatalogState state = CatalogState.empty("track");
        assertFalse(CatalogDecisions.providerSuccess(state, policy(true), org, null,
                System.currentTimeMillis()).accepted);
        assertTrue(CatalogDecisions.providerSuccess(state, policy(true), apple, null,
                System.currentTimeMillis()).accepted);
        assertNotEquals(policy(false), policy(true));
    }

    private static CatalogCandidate candidate(String id, SourceId source) {
        return new CatalogCandidate(id, "track", source, "item", MatchMethod.EXACT_SPOTIFY_ID,
                1, 0, TimingLevel.LINE, true, true, false, false, false, false, true,
                id, "", "[]", null, 1, 1, System.currentTimeMillis());
    }
}
