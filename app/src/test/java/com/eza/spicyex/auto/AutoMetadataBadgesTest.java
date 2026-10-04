package com.eza.spicyex.auto;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoMetadataBadgesTest {
    @Test public void removesOnlyExplicitAndPreservesNativeState() {
        List<Integer> nativeBadges = Arrays.asList(12, 34, 12, 56);
        assertEquals(Arrays.asList(34, 56), AutoMetadataBadges.withoutExplicit(nativeBadges, 12));
        assertEquals(Arrays.asList(12, 34, 12, 56), nativeBadges);
        assertSame(nativeBadges, AutoMetadataBadges.withoutExplicit(nativeBadges, 99));
        assertSame(nativeBadges, AutoMetadataBadges.withoutExplicit(nativeBadges, 0));
    }
}
