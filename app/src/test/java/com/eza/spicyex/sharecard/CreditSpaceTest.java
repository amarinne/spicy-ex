package com.eza.spicyex.sharecard;

import org.junit.Test;
import static org.junit.Assert.*;

public class CreditSpaceTest {
    @Test public void rowsSplitProviderFromContributors() {
        assertArrayEquals(new String[]{"Lyrics from Spicy Lyrics", "uploaded by A, made by B"},
                CreditSpace.rows("Lyrics from Spicy Lyrics\nuploaded by A, made by B"));
        assertArrayEquals(new String[]{"Lyrics from Apple Music", ""},
                CreditSpace.rows("Lyrics from Apple Music"));
        assertArrayEquals(new String[]{"", ""}, CreditSpace.rows(""));
    }

    @Test public void onlyContributorNamesAreSpanned() {
        String both = "uploaded by spikerko, made by gc";
        assertSpans(both, both.indexOf("spikerko"), both.indexOf(","), both.indexOf("gc"), both.length());
        String uploader = "uploaded by spikerko";
        assertSpans(uploader, 12, uploader.length());
        String maker = "made by gc";
        assertSpans(maker, 8, maker.length());
        String names = "uploaded by Ann, Bo, made by Cy, Di";
        assertSpans(names, 12, names.indexOf(", made"), names.indexOf("Cy"), names.length());
        assertEquals(0, CreditSpace.nameSpans("").length);
        assertEquals(0, CreditSpace.nameSpans(null).length);
        assertEquals(0, CreditSpace.nameSpans("uploaded by ").length);
        assertEquals(0, CreditSpace.nameSpans("something else").length);
    }

    private static void assertSpans(String text, int... bounds) {
        int[][] spans = CreditSpace.nameSpans(text);
        assertEquals(bounds.length / 2, spans.length);
        for (int i = 0; i < spans.length; i++) {
            assertArrayEquals(new int[]{bounds[2 * i], bounds[2 * i + 1]}, spans[i]);
        }
    }

    @Test public void bottomBandRisesByTheBlockPlusGapAndAnyOverhang() {
        // Minimal: the band ended where the credit ends, so it rises by block + gap only.
        assertEquals(128f, CreditSpace.lift(100f, 28f, 1287f, 1287f), 0f);
        // Polaroid: the footer row hung 11 below the credit's last line, so it rises 11 more.
        assertEquals(139f, CreditSpace.lift(100f, 28f, 1241f, 1230f), 0f);
        // Ticket: the stub's foot is above the credit's end; no overhang to make up.
        assertEquals(128f, CreditSpace.lift(100f, 28f, 1186f, 1192f), 0f);
        assertEquals(0f, CreditSpace.lift(0f, 28f, 1241f, 1230f), 0f);
    }

    @Test public void creditStaysBelowTheRisenBandAndTheLyricAboveIt() {
        // Polaroid with a 100 tall credit: band top 1119, foot 1241, credit ends 1230, caption box top 802.
        float lift = CreditSpace.lift(100f, 28f, 1241f, 1230f);
        float creditTop = CreditSpace.blockTop(1230f, 100f);
        assertTrue(1241f - lift + 28f <= creditTop);
        float limit = 1119f - lift - 36f;
        assertEquals(142f, CreditSpace.lyricHeight(802f, 288f, limit, 0f, 0f, 120f), 0f);
        // The tallest credit that still leaves the caption 120: the guard agrees with the geometry.
        float room = CreditSpace.maxBlockHeight(802f, 1119f - 11f, 28f + 36f, 120f);
        assertEquals(122f, room, 0f);
        float limitAtRoom = 1119f - CreditSpace.lift(room, 28f, 1241f, 1230f) - 36f;
        assertEquals(120f, CreditSpace.lyricHeight(802f, 288f, limitAtRoom, 0f, 0f, 120f), 0f);
    }

    @Test public void lyricBoxKeepsItsHeightWhenTheCreditFitsBelowIt() {
        // Box 110..1030; a 100 tall credit hanging from 1200 starts at 1100, 70 below the box.
        assertEquals(920f, CreditSpace.lyricHeight(110f, 920f, 1200f, 100f, 28f, 120f), 0f);
        // Minimal's own anchor: the box gives up only the 23 it would otherwise overlap.
        assertEquals(897f, CreditSpace.lyricHeight(110f, 920f, 1135f, 100f, 28f, 120f), 0f);
    }

    @Test public void lyricBoxEndsAboveTheCreditWhenItWouldOverlap() {
        // Polaroid: caption box 802..1090, credit 100 tall hanging from 1092.
        float height = CreditSpace.lyricHeight(802f, 288f, 1092f, 100f, 28f, 120f);
        assertEquals(162f, height, 0f);
        assertTrue(802f + height + 28f <= CreditSpace.blockTop(1092f, 100f));
    }

    @Test public void lyricBoxNeverCollapsesBelowItsMinimumOrGrows() {
        assertEquals(120f, CreditSpace.lyricHeight(802f, 288f, 1092f, 400f, 28f, 120f), 0f);
        assertEquals(100f, CreditSpace.lyricHeight(802f, 100f, 1092f, 400f, 28f, 120f), 0f);
    }

    @Test public void oversizedCreditCannotBecomeAnImageAtTheFontFloor() {
        float room = CreditSpace.maxBlockHeight(802f, 1092f, 28f, 120f);
        assertTrue(CreditSpace.fits(room, room));
        assertFalse(CreditSpace.fits(room + 1, room));
        assertFalse(CreditSpace.fits(400, room));
    }

    @Test public void maxBlockHeightLeavesTheMinimumLyricRoom() {
        float room = CreditSpace.maxBlockHeight(802f, 1092f, 28f, 120f);
        assertEquals(142f, room, 0f);
        assertEquals(120f, CreditSpace.lyricHeight(802f, 288f, 1092f, room, 28f, 120f), 0f);
        assertEquals(0f, CreditSpace.maxBlockHeight(1000f, 1010f, 28f, 120f), 0f);
    }
}
