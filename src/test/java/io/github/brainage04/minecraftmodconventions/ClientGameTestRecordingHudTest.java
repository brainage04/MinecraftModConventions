package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aNarrowScreenWouldNotFitThePanel() {
        // One GUI scale higher on 1920x1080 is a 480x270 GUI, too narrow for the panel's 368 pixels.
        assertFalse(ClientGameTestRecordingHud.fits(480, 270, WIDEST_TEXT, SHOWCASE_LINES));
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
