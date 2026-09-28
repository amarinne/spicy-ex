package com.eza.spicyex.hooks;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LayoutEditorGeometryContractTest {
    @Test
    public void roundButtonsUseOriginalRendering() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/hooks/NativeIconButtons.java");
        assertTrue(source.contains("static GradientDrawable createRoundButtonBackground()"));
        assertTrue(!source.contains("new InsetDrawable(ring"));

        String follow = read("src/main/java/com/eza/spicyex/hooks/LyricsJumpToCurrentController.java");
        assertTrue(follow.contains("canvas.drawRoundRect(bounds, radius, radius, stroke)"));
    }

    @Test
    public void landscapeEditorUsesCompactDockAndReservedBand() throws Exception {
        String editor = read("src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java");
        assertTrue(editor.contains("button.setImageDrawable(new ActionIconDrawable(kind, color, density))"));
        assertTrue(editor.contains("button.setScaleType(ImageView.ScaleType.CENTER_INSIDE)"));
        assertTrue(editor.contains("button.setPadding(dp(12), dp(12), dp(12), dp(12))"));
        assertTrue(editor.contains("chooseToolbarSlot"));
        assertTrue(editor.contains("ViewGroup.LayoutParams.WRAP_CONTENT, buttonSize"));
        // Two-column chips share the overlay's parent with their own elevation; the editor must
        // sit above them or the real chip takes the tap meant for its outline.
        assertTrue(editor.contains("overlay.setTranslationZ(dp(EDITOR_Z_DP))"));
        // A bottom-docked panel above a bottom toolbar row: its margin is measured from the bottom
        // edge. Returning the row's y squeezed the panel into a sliver at the top of the screen.
        assertTrue(editor.contains("return Math.max(0, overlayHeightPx() - row.top) + dp(PANEL_GAP_DP);"));
        assertTrue(!editor.contains("s(\"mode_card\", \"Now playing card\")"));

        String shell = read("src/main/java/com/eza/spicyex/hooks/NativeSpicyShellViewImpl.java");
        assertFalse(shell.contains("landscapeEditorActive"));
        assertTrue(shell.contains(".landscape(isLandscape() || twoColumn)"));
        assertTrue(shell.contains("if (!isLandscape() && !twoColumn)"));
        assertFalse(shell.contains("setEditorTopClearance"));
        // The two-column column (cover + song info) is the readout's own placement now: the shell
        // only hosts it, and the editor reaches it through the readout like every other layout.
        assertTrue(shell.contains("landscapeLeftColumn.addView(trackInfoController.columnView()"));
        assertTrue(shell.contains(".artFrameSupplier(() -> trackInfoController == null"));
        assertTrue(shell.contains(".trackTextFrameSupplier(() -> trackInfoController == null"));
        assertTrue(shell.contains("trackInfoController.columnFitSidePx()"));
        assertTrue(shell.contains("FrameLayout floatingChipHost = twoColumn ? this : lyricsFrame"));

        String readout = read("src/main/java/com/eza/spicyex/hooks/TrackInfoReadoutController.java");
        assertTrue(readout.contains("topRowLp.topMargin = topInsetPx;"));
        assertFalse(readout.contains("editorTopClearancePx"));
    }

    @Test
    public void toolbarSlotSelectionPrioritizesMinimumOverlapAndEarlierSlot() {
        // Candidate slots: 0 (top-start), 1 (top-centre), 2 (bottom-start), 3 (bottom-centre)
        java.util.List<LyricsLayoutEditController.SlotRect> slots = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(12, 40, 150, 84),
                new LyricsLayoutEditController.SlotRect(200, 40, 338, 84),
                new LyricsLayoutEditController.SlotRect(12, 600, 150, 644),
                new LyricsLayoutEditController.SlotRect(200, 600, 338, 644)
        );

        // Case 1: no obstacles -> slot 0 chosen (earliest tie)
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, java.util.Collections.emptyList()));
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, null));

        // Case 2: obstacle covers slot 0 -> slot 1 chosen (0 overlap)
        java.util.List<LyricsLayoutEditController.SlotRect> obs1 = java.util.Collections.singletonList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90)
        );
        org.junit.Assert.assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(slots, obs1));

        // Case 3: obstacles cover slot 0 and 1 -> slot 2 chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs2 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90),
                new LyricsLayoutEditController.SlotRect(190, 30, 350, 90)
        );
        org.junit.Assert.assertEquals(2, LyricsLayoutEditController.chooseToolbarSlot(slots, obs2));

        // Case 4: obstacles cover slot 0, 1, 2 -> slot 3 chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs3 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90),
                new LyricsLayoutEditController.SlotRect(190, 30, 350, 90),
                new LyricsLayoutEditController.SlotRect(10, 590, 160, 650)
        );
        org.junit.Assert.assertEquals(3, LyricsLayoutEditController.chooseToolbarSlot(slots, obs3));

        // Case 5: ties with non-zero overlap -> earlier slot chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs4 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(0, 0, 200, 200), // covers slot 0 heavily
                new LyricsLayoutEditController.SlotRect(200, 40, 220, 50), // slot 1: 20x10 = 200
                new LyricsLayoutEditController.SlotRect(12, 600, 32, 610), // slot 2: 20x10 = 200
                new LyricsLayoutEditController.SlotRect(200, 600, 300, 700) // covers slot 3 heavily
        );
        // Between slot 1 and slot 2 (both 200), tie goes to slot 1
        org.junit.Assert.assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(slots, obs4));

        // Case 6: edge-touching is not overlapping (0 area)
        java.util.List<LyricsLayoutEditController.SlotRect> obsTouching = java.util.Collections.singletonList(
                new LyricsLayoutEditController.SlotRect(0, 0, 12, 40) // touches left and top of slot 0
        );
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, obsTouching));
    }

    @Test
    public void topReadoutReservesConfiguredControlEdge() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/hooks/TrackInfoReadoutController.java");
        assertTrue(source.contains("Settings.CHROME_CLUSTER_POSITION"));
        assertTrue(source.contains("sidePad + (controlsLeft ? railClearance : 0)"));
        assertTrue(source.contains("sidePad + (controlsLeft ? 0 : railClearance)"));
    }

    @Test
    public void twoColumnEditorKeepsPositionAsAnOnOffRow() throws Exception {
        String editor = read("src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java");
        // Two-column's column is a readout placement, so the position control is no longer a
        // four-way chip row whose Top/Bottom/Header values have nothing to act on: it stays, as one
        // On/Off row over the same setting, and Off is the only value that means anything there.
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_POSITION))"));
        assertTrue(editor.contains("addOption(toggleRow(strings.setting(Settings.TRACK_INFO_POSITION),"));
        assertTrue(editor.contains("on ? \"Top\" : \"Off\""));
        // The other readout-only controls still stand down in two-column: text alignment (a
        // readout row beside its art) and the background band (the column has no dock).
        assertTrue(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_ALIGN))"));
        assertTrue(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_BACKGROUND))"));
        // Everything that does act in two-column stays offered: corner radius, panel media
        // controls, adaptive landscape layout, the field toggles, the text size, and overflow.
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_ART_RADIUS))"));
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.PANEL_MEDIA_CONTROLS))"));
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(s(\"fields\", \"Fields\"))"));
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_SIZE))"));
        assertFalse(gatedOnTwoColumn(editor, "beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_OVERFLOW))"));
    }

    @Test
    public void layoutEditorOptionsAreAFloatingPanelNotADragToDismissSheet() throws Exception {
        String editor = read("src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java");

        // One surface, rounded on every corner, its own elevation, inset from every screen edge.
        assertTrue(editor.contains("panelBg.setCornerRadius(dp(20))"));
        assertTrue(editor.contains("panelContainer.setElevation(dp(16))"));
        assertTrue(editor.contains("private static final int PANEL_INSET_DP = 12;"));
        assertTrue(editor.contains("private static final int PANEL_MAX_WIDTH_DP = 360;"));
        // Height is the smaller of the overlay share and what the toolbar row leaves free; card
        // mode keeps the stage visible, so it is capped harder there.
        assertTrue(editor.contains("private static final float PANEL_MAX_HEIGHT_SHARE = 0.6f;"));
        assertTrue(editor.contains("private static final float PANEL_MAX_HEIGHT_SHARE_CARD = 0.5f;"));
        assertTrue(editor.contains("Math.min(Math.round(overlayH * share), panelFreeHeightPx())"));
        // The header moves it, and a moved panel keeps that position for the session.
        assertTrue(editor.contains("panelHeader.setOnTouchListener"));
        assertTrue(editor.contains("if (moved[0]) panelMovedByUser = true;"));
        assertTrue(editor.contains("if (panelMovedByUser) {"));
        // Header: the title on the left, a 44dp close button on the right that hides the panel.
        assertTrue(editor.contains("private static final int PANEL_CLOSE_BUTTON_DP = 44;"));
        assertTrue(editor.contains("s(\"close\", \"Close\"), () -> hidePanelSheet(true))"));
        // Fade and scale in and out; a window appears, it does not slide off an edge.
        assertTrue(editor.contains("private static final int PANEL_FADE_MS = 180;"));
        assertTrue(editor.contains("private static final float PANEL_HIDDEN_SCALE = 0.96f;"));
        assertTrue(editor.contains(".setDuration(PANEL_FADE_MS)"));
        assertFalse(editor.contains("panelContainer.setTranslationY"));
        // The drag-to-dismiss machinery is deleted, not left dormant: no sheet container, no
        // velocity tracking, no settle, no signed travel, and nothing intercepting the list.
        assertFalse(editor.contains("class SheetLayout"));
        assertFalse(editor.contains("VelocityTracker"));
        assertFalse(editor.contains("settleSheet"));
        assertFalse(editor.contains("sheetHiddenOffset"));
        assertFalse(editor.contains("clampSheetTravel"));
        assertFalse(editor.contains("sheetGrabber"));
        assertFalse(editor.contains("onInterceptTouchEvent"));
        // The probe still reports the visible panel under the same key.
        assertTrue(editor.contains("report.rect(\"sheet\", screenRectOf(panelContainer))"));
        // The list is capped in pixels, not by a share of whatever the parent hands it.
        assertTrue(editor.contains("optionsScroll.maxHeightPx = Math.max(0,"
                + " panelMaxHeightPx() - dp(PANEL_HEADER_DP));"));
        assertTrue(editor.contains("int maxHeightPx = 0;"));
    }

    /** True when {@code groupStart} opens immediately inside an "if (!twoColumn) {" block. */
    private static boolean gatedOnTwoColumn(String source, String groupStart) {
        int at = source.indexOf(groupStart);
        assertTrue(groupStart + " must exist", at > 0);
        return source.substring(Math.max(0, at - 200), at).trim().endsWith("if (!twoColumn) {");
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
