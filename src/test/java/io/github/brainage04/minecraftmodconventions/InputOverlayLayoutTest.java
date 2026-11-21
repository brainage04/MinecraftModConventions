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
        int[] origin = InputOverlay.panelOrigin(
                SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, 20, Integer.MIN_VALUE, 0);

        assertEquals(SHOWCASE_GUI_WIDTH - 100 - 5, origin[0]);
        assertEquals(SHOWCASE_GUI_HEIGHT - 20 - 5 - InputOverlay.HOTBAR_HEIGHT, origin[1]);
    }

    @Test
    void thePanelRisesAboveAPerformanceOverlayInTheSameCorner() {
        int performanceTop = 300;

        int[] origin = InputOverlay.panelOrigin(
                SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, 20, performanceTop, 0);

        assertEquals(performanceTop - 20 - 2, origin[1]);
    }

    @Test
    void thePanelIsPushedToTheLeftEdgeWhenItIsWiderThanTheGui() {
        // A panel wider than the GUI cannot fit; the clamp keeps its left edge on screen rather than
        // letting it start off the left side.
        int[] origin = InputOverlay.panelOrigin(320, 240, 400, 20, Integer.MIN_VALUE, 0);

        assertEquals(5, origin[0]);
    }

    @Test
    void thePanelRisesAboveThePerformanceOverlayAndStaysOnScreen() {
        int panelHeight = 20;
        for (int performanceTop = 0; performanceTop < SHOWCASE_GUI_HEIGHT; performanceTop += 10) {
            int[] origin = InputOverlay.panelOrigin(
                    SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, 100, panelHeight, performanceTop, 0);

            assertTrue(origin[1] >= 5, "panel top " + origin[1] + " is off screen");
            if (performanceTop - panelHeight - 2 >= 5) {
                assertTrue(origin[1] + panelHeight <= performanceTop,
                        "panel bottom " + (origin[1] + panelHeight) + " over overlay top " + performanceTop);
            }
        }
    }

    @Test
    void thePanelKeepsClearOfTheChatColumnAndOnScreen() {
        // The real panel, at the GUI a recording uses and at the narrowest a client can have. The
        // chat column is 332 wide, so a GUI narrower than 401 cannot hold both: there the panel
        // stays on screen and covers part of the chat.
        int panelWidth = InputOverlay.panelWidth(InputOverlay.caps());
        int chatColumn = InputOverlay.chatColumnWidth(1.0, 1.0);
        for (int guiWidth : new int[] {SHOWCASE_GUI_WIDTH, 401, 400, 360, 320}) {
            int[] origin = InputOverlay.panelOrigin(
                    guiWidth, SHOWCASE_GUI_HEIGHT, panelWidth, 20, Integer.MIN_VALUE, chatColumn);

            assertTrue(origin[0] + panelWidth <= guiWidth,
                    "panel right " + (origin[0] + panelWidth) + " off GUI " + guiWidth);
            if (InputOverlay.fitsBesideChat(guiWidth, panelWidth, chatColumn)) {
                assertTrue(origin[0] >= chatColumn + 5,
                        "panel left " + origin[0] + " over chat " + chatColumn + " at GUI " + guiWidth);
            }
        }
    }

    @Test
    void thePanelFitsBesideChatOnlyWhenTheGuiIsWideEnough() {
        int panelWidth = InputOverlay.panelWidth(InputOverlay.caps());
        int chatColumn = InputOverlay.chatColumnWidth(1.0, 1.0);

        // 332 + 5 + 64 + 5 = 406, so 406 is the narrowest GUI that holds both.
        assertTrue(InputOverlay.fitsBesideChat(406, panelWidth, chatColumn));
        assertTrue(!InputOverlay.fitsBesideChat(405, panelWidth, chatColumn));
    }

    @Test
    void theTypedLineKeepsClearOfTheChatColumn() {
        int chatColumn = InputOverlay.chatColumnWidth(1.0, 1.0);
        int widest = InputOverlay.maxContentWidth(SHOWCASE_GUI_WIDTH, chatColumn);
        assertEquals(SHOWCASE_GUI_WIDTH - 2 * 5 - chatColumn, widest);

        int[] origin = InputOverlay.panelOrigin(
                SHOWCASE_GUI_WIDTH, SHOWCASE_GUI_HEIGHT, widest, 20, Integer.MIN_VALUE, chatColumn);

        assertTrue(origin[0] >= chatColumn, "typed line left " + origin[0] + " over chat " + chatColumn);
    }

    @Test
    void theChatColumnIsAsWideAsTheChatBackgroundReaches() {
        // getWidth is at most 320 and the background runs 12 pixels wider (ChatComponent.java:160-167).
        assertEquals(332, InputOverlay.chatColumnWidth(1.0, 1.0));
        // A narrower chat is narrower: getWidth(0.5) is floor(0.5 * 280 + 40) = 180, plus 12.
        assertEquals(192, InputOverlay.chatColumnWidth(0.5, 1.0));
        // chatScale is a UnitDouble, so it cannot pass 1 (OptionInstance.java:583-585). The widest
        // reachable column is 333, which a scale just under 1 reaches.
        assertEquals(333, InputOverlay.chatColumnWidth(1.0, 0.99));
        // A small chat at a small scale rounds up: the game's two float steps, not one multiply.
        assertEquals(52, InputOverlay.chatColumnWidth(0.0, 0.85));
    }

    @Test
    void everyKeyTheFilmShowsHasACap() {
        List<String> ids = InputOverlay.caps().stream().map(InputOverlay.Cap::id).toList();

        assertEquals(List.of("forward", "left", "back", "right", "jump", "attack", "use", "sneak", "sprint"), ids);
        assertEquals(
                List.of("W", "A", "S", "D", "Space", "LMB", "RMB", "Shift", "Sprint"),
                InputOverlay.caps().stream().map(InputOverlay.Cap::label).toList());
    }

    @Test
    void theCapsSitInBrainageHudsKeystrokesLayout() {
        // KeystrokesHud: 20-pixel caps two pixels apart, W over S, A and D beside it, a space bar
        // three caps wide, then LMB and RMB, then Shift and Sprint (KeystrokesHud.java:45-129).
        int size = InputOverlay.KEY_SIZE;
        int pad = InputOverlay.KEY_PADDING;
        int half = (int) (size * 1.5 + (double) pad / 2);
        List<InputOverlay.Cap> caps = InputOverlay.caps();

        assertEquals(new InputOverlay.Cap("forward", "W", size + pad, 0, size, size), caps.get(0));
        assertEquals(new InputOverlay.Cap("left", "A", 0, size + pad, size, size), caps.get(1));
        assertEquals(new InputOverlay.Cap("back", "S", size + pad, size + pad, size, size), caps.get(2));
        assertEquals(new InputOverlay.Cap("right", "D", (size + pad) * 2, size + pad, size, size), caps.get(3));
        assertEquals(new InputOverlay.Cap("jump", "Space", 0, (size + pad) * 2, size * 3 + pad * 2, size),
                caps.get(4));
        assertEquals(new InputOverlay.Cap("attack", "LMB", 0, (size + pad) * 3, half, size), caps.get(5));
        assertEquals(new InputOverlay.Cap("use", "RMB", half + pad, (size + pad) * 3, half, size), caps.get(6));
        assertEquals(new InputOverlay.Cap("sneak", "Shift", 0, (size + pad) * 4, half, size), caps.get(7));
        assertEquals(new InputOverlay.Cap("sprint", "Sprint", half + pad, (size + pad) * 4, half, size),
                caps.get(8));
    }

    @Test
    void thePanelIsAsBigAsItsCaps() {
        int size = InputOverlay.KEY_SIZE;
        int pad = InputOverlay.KEY_PADDING;
        int half = (int) (size * 1.5 + (double) pad / 2);

        // The space bar is the widest cap, and the last row is the lowest.
        assertEquals(size * 3 + pad * 2, InputOverlay.panelWidth(InputOverlay.caps()));
        assertEquals((size + pad) * 4 + size, InputOverlay.panelHeight(InputOverlay.caps()));
        assertTrue(half + pad + half <= size * 3 + pad * 2, "LMB and RMB must fit the space bar's width");
    }

    @Test
    void aCapLightsInAHundredMillisecondsAndDimsInTheSameTime() {
        // KeystrokesHud lights a cap over 100 ms (KeystrokesHud.java:22, :154-165).
        assertEquals(0.5F, InputOverlay.advance(0.0F, true, 50_000_000L), 1.0E-6F);
        assertEquals(1.0F, InputOverlay.advance(0.0F, true, 100_000_000L), 1.0E-6F);
        assertEquals(1.0F, InputOverlay.advance(0.9F, true, 100_000_000L), 1.0E-6F);
        assertEquals(0.5F, InputOverlay.advance(1.0F, false, 50_000_000L), 1.0E-6F);
        assertEquals(0.0F, InputOverlay.advance(0.1F, false, 100_000_000L), 1.0E-6F);
        // A frame that took longer than the transition finishes it rather than overshooting.
        assertEquals(1.0F, InputOverlay.advance(0.0F, true, 5_000_000_000L), 1.0E-6F);
        assertEquals(0.0F, InputOverlay.advance(1.0F, false, 5_000_000_000L), 1.0E-6F);
    }

    @Test
    void anUnlitCapIsBlackAndALitOneIsWhite() {
        // BrainageHUD's keystrokes HUD: a black backdrop at 100/255 with white text, lit to a white
        // backdrop at the same opacity with black text (KeystrokesHud.java:188-210).
        assertEquals(0x64000000, InputOverlay.backdropColour(0.0F));
        assertEquals(0x64FFFFFF, InputOverlay.backdropColour(1.0F));
        assertEquals(0xFFFFFFFF, InputOverlay.textColour(0.0F));
        assertEquals(0xFF000000, InputOverlay.textColour(1.0F));
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
