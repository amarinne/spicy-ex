package com.eza.spicyex.auto;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoDashboardCardsTest {
    @Test public void preservesNativeListAndOtherCardsInOrder() {
        Object suggestion = new Object(), media = new Object(), weather = new Object(), guidance = new Object();
        List<?> nativeCards = Collections.unmodifiableList(Arrays.asList(suggestion, media, weather, guidance, suggestion));
        assertEquals(Arrays.asList(media, weather, guidance), AutoDashboardCards.withoutSuggestion(nativeCards, suggestion));
        assertEquals(Arrays.asList(suggestion, media, weather, guidance, suggestion), nativeCards);
        assertSame(nativeCards, AutoDashboardCards.withoutSuggestion(nativeCards, null));
        assertSame(nativeCards, AutoDashboardCards.withoutSuggestion(nativeCards, new Object()));
    }

    @Test public void usesSingletonIdentityEvenWhenAnotherCardComparesEqual() {
        Object suggestion = new String("card"), other = new String("card");
        List<?> result = AutoDashboardCards.withoutSuggestion(Arrays.asList(other, suggestion), suggestion);
        assertEquals(1, result.size());
        assertSame(other, result.get(0));
    }
}
