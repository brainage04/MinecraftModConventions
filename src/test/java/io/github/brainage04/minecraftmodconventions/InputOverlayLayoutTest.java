package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

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
    void thePanelIsPushedToTheLeftEdgeWhenItIsWiderThanTheGui() {
        // A panel wider than the GUI cannot fit; the clamp keeps its left edge on screen rather than
        // letting it start off the left side.
        int[] origin = InputOverlay.panelOrigin(320, 240, 400, 20, Integer.MIN_VALUE);

        assertEquals(5, origin[0]);
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
    void aWideCapIsSkippedAndTheNarrowOnesAfterItStillShow() {
        // Every character is 6 pixels wide, so "Shift" is 30 and "W" is 6.
        ToIntFunction<String> width = text -> text.length() * 6;
        List<InputOverlayState.Chip> chips = List.of(
                new InputOverlayState.Chip("a", "Shift", true),
                new InputOverlayState.Chip("b", "W", true));

        // The glyph is 9 wide plus a 2-pixel gap, and a cap is its text plus 3 pixels of padding each
        // side plus a 2-pixel gap: "W" needs 14, so 25 holds the glyph and "W" but not "Shift" (38).
        List<InputOverlayState.Chip> fitted = InputOverlay.fitChips(width, chips, 25);

        assertEquals(List.of("b"), fitted.stream().map(InputOverlayState.Chip::id).toList());
    }

    @Test
    void typedTextIsCutWithAnEllipsisWhenItDoesNotFit() {
        ToIntFunction<String> width = text -> text.length() * 6;

        // The caller adds the "> " prefix, so the cut text is what is returned.
        assertEquals("magic", InputOverlay.fitText(width, "magic", 100));
        assertEquals("ma…", InputOverlay.fitText(width, "magic carpet", 30));
        assertEquals("", InputOverlay.fitText(width, "magic", 6));
    }

    @Test
    void theCursorIsTallerThanItIsWide() {
        int widest = 0;
        for (int row = 0; row < InputOverlay.CURSOR_HEIGHT; row++) {
            widest = Math.max(widest, InputOverlay.cursorRowWidth(row));
        }

        assertTrue(widest < InputOverlay.CURSOR_HEIGHT, "widest row " + widest);
        assertEquals(1, InputOverlay.cursorRowWidth(0));
        assertEquals(2, InputOverlay.cursorRowWidth(InputOverlay.CURSOR_HEIGHT - 1));
        assertEquals(0, InputOverlay.cursorRowWidth(-1));
        assertEquals(0, InputOverlay.cursorRowWidth(InputOverlay.CURSOR_HEIGHT));
    }

    @Test
    void theCursorTailLeansRightOfItsTip() {
        assertEquals(0, InputOverlay.cursorRowLeft(0));
        assertTrue(InputOverlay.cursorRowLeft(InputOverlay.CURSOR_HEIGHT - 1) > 0);
        assertEquals(0, InputOverlay.cursorRowLeft(-1));
    }
}
