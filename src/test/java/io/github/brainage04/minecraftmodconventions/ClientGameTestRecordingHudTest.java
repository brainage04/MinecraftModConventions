package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestRecordingHudTest {
    /** 1920x1080 at GUI scale 3, the overlay standard for every recording. */
    private static final int SHOWCASE_GUI_WIDTH = 640;
    private static final int SHOWCASE_GUI_HEIGHT = 360;
    /** 854x480 at GUI scale 2, the diagnostic profile of an unrecorded client GameTest run. */
    private static final int TEST_GUI_WIDTH = 427;
    private static final int TEST_GUI_HEIGHT = 240;
    /** The widest text the panel draws and the lines a diagnostic step fills it with. */
    private static final int WIDEST_TEXT = 360;
    private static final int SHOWCASE_LINES = 4;
    private static final int DIAGNOSTIC_LINES = 10;

    /** The overlay's three lines in a panel about as wide as their text. */
    private static final int OVERLAY_WIDTH = 130;
    private static final int OVERLAY_HEIGHT = ClientGameTestRecordingHud.panelHeight(3);
    private static final int SHOWCASE_CAPTION_BOTTOM =
            5 + ClientGameTestRecordingHud.panelHeight(ClientGameTestRecordingHud.captionLinesAtMost(true));

    @AfterEach
    void resetPlacement() {
        ClientGameTestRecordingHud.clear();
    }

    @Test
    void theOverlaySitsUnderTheCaptionPanelInTheTopRightByDefault() {
        assertEquals(PerformanceOverlayPlacement.TOP_RIGHT, ClientGameTestRecordingHud.performanceOverlayPlacement());
        // A title card holds at most a header, a title and a subtitle: 39 pixels from y 5, so the overlay starts at 46.
        assertArrayEquals(new int[] {SHOWCASE_GUI_WIDTH - OVERLAY_WIDTH - 5, 46}, panel(PerformanceOverlayPlacement.TOP_RIGHT, false));
    }

    @Test
    void theOverlayFollowsTheCornerATestChooses() {
        RecordingTestContext context = new RecordingTestContext();
        int bottom = SHOWCASE_GUI_HEIGHT - OVERLAY_HEIGHT - 5;

        ClientGameTestRecorder.placePerformanceOverlay(context, PerformanceOverlayPlacement.BOTTOM_LEFT);
        assertEquals(PerformanceOverlayPlacement.BOTTOM_LEFT, ClientGameTestRecordingHud.performanceOverlayPlacement());
        assertArrayEquals(new int[] {5, bottom}, panel(ClientGameTestRecordingHud.performanceOverlayPlacement(), false));
        assertArrayEquals(new int[] {SHOWCASE_GUI_WIDTH - OVERLAY_WIDTH - 5, bottom},
                panel(PerformanceOverlayPlacement.BOTTOM_RIGHT, false));
        assertArrayEquals(new int[] {5, 5}, panel(PerformanceOverlayPlacement.TOP_LEFT, false));

        ClientGameTestRecorder.placePerformanceOverlay(context, PerformanceOverlayPlacement.HIDDEN);
        assertNull(panel(ClientGameTestRecordingHud.performanceOverlayPlacement(), false));

        // The next recording starts with the overlay back in its default place.
        ClientGameTestRecordingHud.clear();
        assertEquals(PerformanceOverlayPlacement.TOP_RIGHT, ClientGameTestRecordingHud.performanceOverlayPlacement());
    }

    @Test
    void aBottomOverlayRisesAboveTheChatInputWhileChatIsOpen() {
        int[] open = panel(PerformanceOverlayPlacement.BOTTOM_RIGHT, true);
        assertEquals(SHOWCASE_GUI_HEIGHT - ClientGameTestRecordingHud.CHAT_INPUT_HEIGHT - 5, open[1] + OVERLAY_HEIGHT);
        assertEquals(panel(PerformanceOverlayPlacement.TOP_RIGHT, false)[1], panel(PerformanceOverlayPlacement.TOP_RIGHT, true)[1]);
    }

    @Test
    void noOverlayIsDrawnOutsideARecording() {
        for (PerformanceOverlayPlacement placement : PerformanceOverlayPlacement.values()) {
            assertNull(ClientGameTestRecordingHud.performancePanel(false, placement, SHOWCASE_GUI_WIDTH,
                    SHOWCASE_GUI_HEIGHT, SHOWCASE_CAPTION_BOTTOM, OVERLAY_WIDTH, OVERLAY_HEIGHT, false), placement.name());
        }
    }

    private static int[] panel(PerformanceOverlayPlacement placement, boolean chatOpen) {
        return ClientGameTestRecordingHud.performancePanel(true, placement, SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT,
                SHOWCASE_CAPTION_BOTTOM, OVERLAY_WIDTH, OVERLAY_HEIGHT, chatOpen);
    }

    @Test
    void theCaptionPanelFitsA1920x1080RecordingAtScaleThree() {
        assertTrue(ClientGameTestRecordingHud.fits(SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, WIDEST_TEXT, SHOWCASE_LINES));
        // Even a diagnostic step's log lines fit, so nothing is ever cut off in a recording.
        assertTrue(ClientGameTestRecordingHud.fits(SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, WIDEST_TEXT, DIAGNOSTIC_LINES));
    }

    @Test
    void theCaptionPanelFitsAnUnscaledTestWindow() {
        assertTrue(ClientGameTestRecordingHud.fits(TEST_GUI_WIDTH, TEST_GUI_HEIGHT, WIDEST_TEXT, DIAGNOSTIC_LINES));
    }

    @Test
    void aNarrowerGuiWouldNotFitThePanel() {
        // The panel is 368 pixels wide with a 5 pixel margin, so a GUI narrower than 373 cannot hold it.
        assertFalse(ClientGameTestRecordingHud.fits(360, 270, WIDEST_TEXT, SHOWCASE_LINES));
        // A GUI too short for the panel's lines.
        assertFalse(ClientGameTestRecordingHud.fits(SHOWCASE_GUI_WIDTH, 60, WIDEST_TEXT, DIAGNOSTIC_LINES));
    }

    @Test
    void thePanelGrowsWithItsTextAndLinesButStopsAtItsMaximumWidth() {
        assertTrue(ClientGameTestRecordingHud.panelWidth(100) < ClientGameTestRecordingHud.panelWidth(300));
        // Beyond the maximum the text is shortened with an ellipsis, so the panel stops at 360 plus padding.
        assertTrue(ClientGameTestRecordingHud.panelWidth(1200) == ClientGameTestRecordingHud.panelWidth(WIDEST_TEXT));
        assertTrue(ClientGameTestRecordingHud.panelHeight(6) > ClientGameTestRecordingHud.panelHeight(2));
    }
}
