package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The probe's whole value is that a caller reading text can tell a real layout fault from a clean
 * one, so every rule gets a case that must fire and one that must not. A rule that never fires is
 * indistinguishable from a rule that is broken, and a rule that always fires is worse.
 */
public class LayoutProbeReportTest {
    @Test public void responseCreditBelongsToTheScrollingFooter() {
        LayoutProbeReport report = new LayoutProbeReport().flag("credit_present", true)
                .flag("credit_in_footer", false);
        assertViolations(report, "R13 credit_outside_footer");
        report.flag("credit_in_footer", true);
        assertViolations(report);
        report.flag("credit_present", false).flag("credit_in_footer", false);
        assertViolations(report);
    }

    /** A clean lyrics screen with the editor closed: no rule may complain about it. */
    private static LayoutProbeReport clean() {
        LayoutProbeReport report = new LayoutProbeReport();
        report.rect("screen", 0, 0, 1080, 2400);
        report.rect("artwork", 40, 120, 340, 420);
        report.rect("track_text", 40, 440, 1040, 560);
        report.rect("dock", 700, 60, 1040, 140);
        report.rect("back", 16, 60, 64, 108);
        report.rect("lyrics_frame", 40, 600, 1040, 2200);
        report.number("chrome_alpha", 1.0f);
        report.flag("editor_open", false);
        report.lyricRow(new int[]{40, 900, 1040, 980});
        report.lyricRow(new int[]{40, 1000, 1040, 1080});
        return report;
    }

    /** Nothing in argument means "no rule fired", which is the answer an agent is looking for. */
    private static void assertViolations(LayoutProbeReport report, String... expected) {
        List<String> violations = report.violations();
        assertEquals("violations", Arrays.asList(expected), violations);
    }

    // R1 ---------------------------------------------------------------------

    @Test
    public void cardModeOnlyChecksTheCaptionUnderTheOpaqueStage() {
        LayoutProbeReport report = clean();
        report.flag("card_mode", true);
        report.rect("toolbar.done", 40, 120, 88, 168);
        report.rect("card_caption", 60, 150, 300, 180);
        assertViolations(report, "R1 toolbar_overlap toolbar.done card_caption");
    }

    @Test
    public void cardModeIgnoresLyricsUnderChrome() {
        LayoutProbeReport report = clean();
        report.flag("card_mode", true);
        report.lyricRow(new int[]{700, 80, 1040, 130});
        assertViolations(report);
    }

    @Test
    public void sheetOverTheToolbarFires() {
        LayoutProbeReport report = clean();
        report.rect("toolbar.done", 16, 60, 64, 108);
        report.rect("sheet", 10, 90, 500, 900);
        assertViolations(report, "R8 sheet_over_toolbar toolbar.done");
    }

    @Test
    public void sheetBelowTheToolbarStaysQuiet() {
        LayoutProbeReport report = clean();
        report.rect("toolbar.done", 16, 60, 64, 108);
        report.rect("sheet", 10, 116, 500, 900);
        assertViolations(report);
    }

    @Test
    public void toolbarOverAnElementFires() {
        LayoutProbeReport report = clean();
        report.rect("toolbar.reset", 320, 100, 368, 148);
        assertViolations(report, "R1 toolbar_overlap toolbar.reset artwork");
    }

    @Test
    public void doneOverTheRealBackButtonStaysQuiet() {
        LayoutProbeReport report = clean();
        report.rect("toolbar.done", 16, 60, 64, 108);
        assertViolations(report);
    }

    @Test
    public void toolbarClearOfEveryElementStaysQuiet() {
        LayoutProbeReport report = clean();
        report.rect("toolbar.done", 300, 60, 348, 108);
        report.rect("toolbar.reset", 360, 60, 408, 108);
        report.rect("card_caption", 400, 1600, 700, 1640);
        assertViolations(report);
    }

    // R2 ---------------------------------------------------------------------

    @Test
    public void captureOffItsRealViewFires() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.text("selected", "artwork");
        report.rect("capture.artwork", 43, 120, 340, 420);
        assertViolations(report, "R2 capture_drift artwork 3 0");
    }

    @Test
    public void captureWithinTwoPixelsStaysQuiet() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.text("selected", "artwork");
        report.rect("capture.artwork", 42, 118, 342, 422);
        assertViolations(report);
    }

    // R3 ---------------------------------------------------------------------

    @Test
    public void focusLineOffTheScrolledAnchorFires() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.number("focus_anchor_y", 1400);
        report.rect("focus_line", 40, 1300, 1040, 1344);
        assertViolations(report, "R3 focus_drift -78");
    }

    @Test
    public void focusLineOnTheScrolledAnchorStaysQuiet() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        // The 44dp band is 48px tall here, so the drawn line is its center at 1400 - 24px below the
        // band's own top edge. Reading the top edge instead would report a 24px drift.
        report.number("focus_anchor_y", 1400);
        report.rect("focus_line", 40, 1376, 1040, 1424);
        assertViolations(report);
    }

    // R4 ---------------------------------------------------------------------

    @Test
    public void chipAboveTheEditorOverlayFires() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.number("z.overlay", 0f);
        report.rect("chip.skip", 900, 2000, 1040, 2080);
        report.number("z.chip.skip", 8f);
        report.flag("chip.skip.sibling", true);
        assertViolations(report, "R4 chip_above_editor skip");
    }

    @Test
    public void chipUnderTheOverlayStaysQuiet() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.number("z.overlay", 8f);
        report.rect("chip.skip", 900, 2000, 1040, 2080);
        report.number("z.chip.skip", 0f);
        report.flag("chip.skip.sibling", true);
        assertViolations(report);
    }

    // R5 ---------------------------------------------------------------------

    @Test
    public void chipOverTheOptionsSheetFires() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.number("z.overlay", 0f);
        report.number("z.chip.follow", 4f);
        report.flag("chip.follow.sibling", true);
        report.rect("chip.follow", 700, 1900, 1040, 1980);
        report.rect("sheet", 0, 1800, 1080, 2400);
        // R5 is conditional on R4: a chip that cannot out-draw the overlay cannot land on the
        // sheet through the overlay either.
        assertViolations(report, "R4 chip_above_editor follow", "R5 chip_over_sheet follow");
    }

    @Test
    public void chipBesideTheOptionsSheetStaysQuiet() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.number("z.overlay", 0f);
        report.number("z.chip.follow", 4f);
        report.flag("chip.follow.sibling", true);
        // The chip's bottom edge sits exactly on the sheet's top edge: touching, not overlapping.
        report.rect("chip.follow", 700, 1700, 1040, 1800);
        report.rect("sheet", 0, 1800, 1080, 2400);
        assertViolations(report, "R4 chip_above_editor follow");
    }

    // R6 ---------------------------------------------------------------------

    @Test
    public void sheetBuriedUnderTheSelectedElementFires() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.text("selected", "artwork");
        // 160 of the artwork's 300px, 48000 of 90000: past the halfway line.
        report.rect("sheet", 0, 260, 1080, 2400);
        assertViolations(report, "R6 sheet_covers_target artwork");
    }

    @Test
    public void sheetOverExactlyHalfTheElementStaysQuiet() {
        LayoutProbeReport report = clean();
        report.flag("editor_open", true);
        report.text("selected", "artwork");
        // Exactly 150 of the artwork's 300px: "more than half" is not "half".
        report.rect("sheet", 0, 270, 1080, 2400);
        assertViolations(report);
    }

    // R7 ---------------------------------------------------------------------

    @Test
    public void opaqueChromeOverLyricTextFires() {
        LayoutProbeReport report = clean();
        report.number("chrome_alpha", 1.0f);
        report.rect("dock", 40, 940, 1040, 1000);
        assertViolations(report, "R7 lyrics_under_chrome 1");
    }

    @Test
    public void fadedChromeOverLyricTextStaysQuiet() {
        LayoutProbeReport report = clean();
        report.number("chrome_alpha", 0.25f);
        report.rect("dock", 40, 940, 1040, 1000);
        assertViolations(report);
    }

    // R9 / R10 ---------------------------------------------------------------

    /** Fold, landscape two-column, B907: the rail sat 72dp in while Follow sat 16dp in. */
    private static LayoutProbeReport foldLandscape(int dockRight) {
        LayoutProbeReport report = new LayoutProbeReport();
        report.rect("screen", 0, 0, 2208, 1768);
        report.rect("dock", dockRight - 132, 156, dockRight, 588);
        report.rect("chip.follow", 2028, 1564, 2160, 1696);
        report.number("chrome_alpha", 0f);
        return report;
    }

    @Test
    public void controlsInsetFurtherThanFollowFire() {
        assertViolations(foldLandscape(1992), "R9 chrome_edge dock=216 follow=48");
    }

    @Test
    public void controlsOnFollowsEdgeGapPass() {
        assertViolations(foldLandscape(2160));
    }

    @Test
    public void followOnTheOtherHalfIsNotCompared() {
        LayoutProbeReport report = foldLandscape(1992);
        report.rect("chip.follow", 48, 1564, 180, 1696);
        assertViolations(report);
    }

    private static LayoutProbeReport row(int dockTop) {
        LayoutProbeReport report = foldLandscape(2160);
        report.rect("dock", 1700, dockTop, 2160, dockTop + 132);
        report.flag("chrome_row", true);
        report.number("edge_margin", 48);
        report.number("chrome_top_floor", 0);
        return report;
    }

    @Test
    public void rowBelowTheCornerFires() {
        assertViolations(row(156), "R10 chrome_corner top=156 margin=48");
    }

    @Test
    public void rowInTheCornerPasses() {
        assertViolations(row(48));
    }

    @Test
    public void railBelowTheCornerFiresToo() {
        LayoutProbeReport report = row(156);
        report.flag("chrome_row", false);
        assertViolations(report, "R10 chrome_corner top=156 margin=48");
    }

    /** Xiaomi landscape, B908: a five-button rail from 88dp ran onto the Follow chip. */
    @Test
    public void railReachingTheFollowChipFires() {
        LayoutProbeReport report = row(48);
        report.rect("dock", 2028, 48, 2160, 1600);
        assertViolations(report, "R11 chrome_over_chip follow");
    }

    @Test
    public void railClearOfTheChipsPasses() {
        LayoutProbeReport report = row(48);
        report.rect("dock", 2028, 48, 2160, 760);
        report.rect("chip.skip", 2028, 1408, 2160, 1540);
        assertViolations(report);
    }
}
