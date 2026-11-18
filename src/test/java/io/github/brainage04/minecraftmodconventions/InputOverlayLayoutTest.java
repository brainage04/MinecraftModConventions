package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayLayoutTest {
    /** 1920x1080 at GUI scale 3, the overlay standard for every recording. */
    private static final int SHOWCASE_GUI_WIDTH = 640;
    private static final int SHOWCASE_GUI_HEIGHT = 360;

    @Test
    void thePanelSitsAboveTheHotbarInTheBottomRight() {
        int[] origin = InputOverlay.panelOrigin(SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, 20, Integer.MIN_VALUE);

        assertEquals(SHOWCASE_GUI_WIDTH - 100 - 5, origin[0]);
        assertEquals(SHOWCASE_GUI_HEIGHT - 20 - 5 - InputOverlay.HOTBAR_HEIGHT, origin[1]);
    }

    @Test
    void thePanelRisesAboveAPerformanceOverlayInTheSameCorner() {
        int performanceTop = 300;

        int[] origin = InputOverlay.panelOrigin(SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, 20, performanceTop);

        assertEquals(performanceTop - 20 - 2, origin[1]);
    }

    @Test
    void thePanelRisesAboveThePerformanceOverlayAndStaysOnScreen() {
        int panelHeight = 20;
        for (int performanceTop = 0; performanceTop < SHOWCASE_GUI_HEIGHT; performanceTop += 10) {
            int[] origin = InputOverlay.panelOrigin(
                    SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, panelHeight, performanceTop);

            assertTrue(origin[1] >= 5, "panel top " + origin[1] + " is off screen");
            if (performanceTop - panelHeight - 2 >= 5) {
                assertTrue(origin[1] + panelHeight <= performanceTop,
                        "panel bottom " + (origin[1] + panelHeight) + " over overlay top " + performanceTop);
            }
        }
    }

    @Test
    void theCursorIsAnArrowThatWidensThenNarrows() {
        assertEquals(1, InputOverlay.cursorRowWidth(0));
        assertTrue(InputOverlay.cursorRowWidth(3) > InputOverlay.cursorRowWidth(1));
        assertTrue(InputOverlay.cursorRowWidth(InputOverlay.CURSOR_HEIGHT - 1) < InputOverlay.cursorRowWidth(3));
        assertEquals(0, InputOverlay.cursorRowWidth(-1));
        assertEquals(0, InputOverlay.cursorRowWidth(InputOverlay.CURSOR_HEIGHT));
    }
}
