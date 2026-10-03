package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElasticScrollPointerTest {
    @Test
    public void rawForIndexAddsSharedRawLocalGap() {
        // Pointer 0 defines the event's screen gap; pointer 1 shares it (minSdk 27 has no getRawY(int)).
        assertEquals(1200f, ElasticScrollView.rawForIndex(300f, 1000f, 100f), 0.001f);
        assertEquals(1000f, ElasticScrollView.rawForIndex(100f, 1000f, 100f), 0.001f);
    }

    @Test
    public void survivorMatchesScrollViewRule() {
        assertEquals(1, ElasticScrollView.survivorIndexForPointerUp(0, 2));
        assertEquals(0, ElasticScrollView.survivorIndexForPointerUp(1, 2));
        assertEquals(0, ElasticScrollView.survivorIndexForPointerUp(2, 3));
        assertEquals(0, ElasticScrollView.survivorIndexForPointerUp(0, 1));
    }

    @Test
    public void pointerZeroTranslationPreservesGapAndIgnoresInactiveMove() {
        float localMinusRaw = 100f - 1000f; // Captured at DOWN: local0 - raw0.
        float withheld = 20f;
        // Two fingers 200px apart locally; setLocation pins pointer 0 and shifts the rest.
        float local0 = 100f, local1 = 300f, raw0 = 1000f;
        float compensated0 = ElasticScrollView.compensatedPointerZeroY(raw0, localMinusRaw, withheld);
        assertEquals(80f, compensated0, 0.001f);
        float compensated1 = local1 + compensated0 - local0;
        assertEquals(200f, compensated1 - compensated0, 0.001f);
        // Active finger (pointer 1) holds still while inactive pointer 0 slides +50px in both
        // raw and local (shared raw/local gap stays 900): the active compensated local must
        // not move, and its screen Y rebuilt from either event agrees.
        assertEquals(1200f, ElasticScrollView.rawForIndex(local1, raw0, local0), 0.001f);
        float movedRaw0 = 1050f, movedLocal0 = 150f;
        assertEquals(1200f, ElasticScrollView.rawForIndex(local1, movedRaw0, movedLocal0), 0.001f);
        float movedCompensated0 =
                ElasticScrollView.compensatedPointerZeroY(movedRaw0, localMinusRaw, withheld);
        assertEquals(compensated1, local1 + movedCompensated0 - movedLocal0, 0.001f);
    }
}
