package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOverlayLayoutTest {
    /** 1920x1080 at GUI scale 3, the overlay standard for every recording. */
    private static final int SHOWCASE_GUI_WIDTH = 640;
    private static final int SHOWCASE_GUI_HEIGHT = 360;

    /**
     * The client font's widths for the labels the layout measures: the sum of the advances of the
     * glyphs in {@code ascii.png}, each its ink width plus one ({@code BitmapProvider.java}), which
     * {@code Font.width} rounds up ({@code Font.java:121-123}). A space advances 4, a clicks-per-second
     * label is its digits at 6 each plus 32 for the {@code " | "} and {@code " CPS"} around them.
     */
    private static final ToIntFunction<String> FONT_WIDTH = label -> switch (label) {
        case "CTRL" -> 24;
        case "Shift" -> 23;
        case "Space" -> 30;
        case "LMB", "RMB" -> 18;
        default -> label.endsWith(" CPS")
                ? 6 * label.replaceAll("\\D", "").length() + 32
                : 6 * label.length();
    };

    /** The side of a glyph cell in {@code ascii.png}. */
    private static final int ATLAS_CELL = 8;
    /** A space's advance ({@code include/space.json} in the client jar). */
    private static final int SPACE_ADVANCE = 4;
    /** A glyph's advance is its last ink column plus this: the ink's width, one pixel for the gap. */
    private static final int INK_TO_ADVANCE = 2;
    /** The drop shadow's pixel offset from the ink ({@code GlyphInfo.java:14-15}). */
    private static final int SHADOW_OFFSET = 1;
    /** How far the margin left of a label's ink may differ from the margin right of its shadow. */
    private static final int MAX_MARGIN_DIFFERENCE = 1;

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
        // chat column is 332 wide, so a GUI narrower than 406 cannot hold both: there the panel
        // stays on screen and covers part of the chat.
        int panelWidth = InputOverlay.panelWidth(InputOverlay.caps(FONT_WIDTH));
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
        int panelWidth = InputOverlay.panelWidth(InputOverlay.caps(FONT_WIDTH));
        int chatColumn = InputOverlay.chatColumnWidth(1.0, 1.0);

        // The chat column is 332 wide and the panel 64, so 332 + 5 + 64 + 5 = 406 is the narrowest
        // GUI that holds both, and 405 is one short.
        assertEquals(332, chatColumn);
        assertEquals(64, panelWidth);
        assertTrue(InputOverlay.fitsBesideChat(406, panelWidth, chatColumn));
        assertFalse(InputOverlay.fitsBesideChat(405, panelWidth, chatColumn));
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
        List<String> ids = InputOverlay.caps(FONT_WIDTH).stream().map(InputOverlay.Cap::id).toList();

        assertEquals(
                List.of("forward", "left", "back", "right", "jump", "attack", "use", "cps", "sneak", "sprint"),
                ids);
        assertEquals(
                List.of("W", "A", "S", "D", "Space", "LMB", "RMB", "0 | 0 CPS", "Shift", "CTRL"),
                InputOverlay.caps(FONT_WIDTH).stream().map(InputOverlay.Cap::label).toList());
    }

    @Test
    void theSprintCapIsInCapitalsLikeLmbAndRmb() {
        assertEquals("CTRL", InputOverlay.SPRINT_LABEL);
        assertEquals("CTRL", cap(InputOverlay.caps(FONT_WIDTH), "sprint").label());
        assertEquals("Shift", cap(InputOverlay.caps(FONT_WIDTH), "sneak").label());
    }

    @Test
    void theCapsSitInBrainageHudsKeystrokesLayout() {
        // KeystrokesHud: 20-pixel caps two pixels apart, W over S, A and D beside it, a space bar
        // three caps wide, then LMB and RMB, then the clicks-per-second row (KeystrokesHud.java:45-148).
        // Shift and CTRL follow, as wide as LMB and RMB. Shift is 23 pixels wide, ink and shadow
        // included, so the four buttons keep the half-cap's 31 and every row ends at 64.
        int size = InputOverlay.KEY_SIZE;
        int pad = InputOverlay.KEY_PADDING;
        int space = size * 3 + pad * 2;
        int button = 31;
        List<InputOverlay.Cap> caps = InputOverlay.caps(FONT_WIDTH);

        assertEquals(new InputOverlay.Cap("forward", "W", size + pad, 0, size, size), caps.get(0));
        assertEquals(new InputOverlay.Cap("left", "A", 0, size + pad, size, size), caps.get(1));
        assertEquals(new InputOverlay.Cap("back", "S", size + pad, size + pad, size, size), caps.get(2));
        assertEquals(new InputOverlay.Cap("right", "D", (size + pad) * 2, size + pad, size, size), caps.get(3));
        assertEquals(new InputOverlay.Cap("jump", "Space", 0, (size + pad) * 2, space, size),
                caps.get(4));
        assertEquals(new InputOverlay.Cap("attack", "LMB", 0, (size + pad) * 3, button, size), caps.get(5));
        assertEquals(new InputOverlay.Cap("use", "RMB", button + pad, (size + pad) * 3, button, size),
                caps.get(6));
        assertEquals(new InputOverlay.Cap("cps", "0 | 0 CPS", 0, (size + pad) * 4, space, size), caps.get(7));
        assertEquals(new InputOverlay.Cap("sneak", "Shift", 0, (size + pad) * 5, button, size), caps.get(8));
        assertEquals(new InputOverlay.Cap("sprint", "CTRL", button + pad, (size + pad) * 5, button, size),
                caps.get(9));
    }

    @Test
    void shiftAndSprintAreAsWideAsLmbAndRmbAndLineUpWithThem() {
        List<InputOverlay.Cap> caps = InputOverlay.caps(FONT_WIDTH);
        InputOverlay.Cap lmb = cap(caps, "attack");
        InputOverlay.Cap rmb = cap(caps, "use");
        InputOverlay.Cap shift = cap(caps, "sneak");
        InputOverlay.Cap sprint = cap(caps, "sprint");

        assertEquals(lmb.width(), rmb.width());
        assertEquals(lmb.width(), shift.width());
        assertEquals(lmb.width(), sprint.width());
        assertEquals(lmb.x(), shift.x());
        assertEquals(rmb.x(), sprint.x());
    }

    @Test
    void theFourButtonsStayEquallyWideInAWideFont() {
        // The widest label of the four sets the width of all four, so Shift and CTRL never drift
        // from LMB and RMB.
        ToIntFunction<String> wide = label -> label.equals("CTRL") ? 50 : 18;
        List<InputOverlay.Cap> caps = InputOverlay.caps(wide);

        int width = cap(caps, "sprint").width();
        assertEquals(51, width);
        assertEquals(width, cap(caps, "attack").width());
        assertEquals(width, cap(caps, "use").width());
        assertEquals(width, cap(caps, "sneak").width());
    }

    @Test
    void everyRowUnderWasdSharesBothEdges() {
        // BrainageHUD's buttons are 31 wide, so two buttons and the gap are as wide as the space bar
        // and the clicks-per-second row (KeystrokesHud.java:107-148). A font that widens a button
        // widens the whole column with it, so the right edge stays straight.
        ToIntFunction<String> wide = label -> label.equals("CTRL") ? 50 : 18;
        for (ToIntFunction<String> font : List.<ToIntFunction<String>>of(FONT_WIDTH, wide)) {
            List<InputOverlay.Cap> caps = InputOverlay.caps(font);
            int rightEdge = InputOverlay.panelWidth(caps);
            for (String id : List.of("jump", "cps")) {
                assertEquals(0, cap(caps, id).x());
                assertEquals(rightEdge, cap(caps, id).x() + cap(caps, id).width(), id + " right edge");
            }
            for (String id : List.of("use", "sprint")) {
                assertEquals(rightEdge, cap(caps, id).x() + cap(caps, id).width(), id + " right edge");
            }
        }
    }

    @Test
    void theClicksPerSecondRowSitsUnderLmbAndRmbAndIsAsWideAsTheSpaceBar() {
        // BrainageHUD's row is keySize * 3 + padding * 2 wide and one cap below the mouse buttons
        // (KeystrokesHud.java:147-148).
        List<InputOverlay.Cap> caps = InputOverlay.caps(FONT_WIDTH);
        InputOverlay.Cap lmb = cap(caps, "attack");
        InputOverlay.Cap cps = cap(caps, "cps");

        assertEquals(0, cps.x());
        assertEquals(lmb.y() + InputOverlay.KEY_SIZE + InputOverlay.KEY_PADDING, cps.y());
        assertEquals(InputOverlay.KEY_SIZE * 3 + InputOverlay.KEY_PADDING * 2, cps.width());
        assertEquals(cap(caps, "jump").width(), cps.width());
    }

    @Test
    void theClicksPerSecondLabelIsBrainageHudsBothFormat() {
        // KeystrokesHud.java:139: "%d | %d CPS", with 0 shown as 0.
        assertEquals("0 | 0 CPS", InputOverlay.cpsLabel(0, 0));
        assertEquals("7 | 12 CPS", InputOverlay.cpsLabel(7, 12));
    }

    @Test
    void aLabelsInkAndShadowAreCentredAsOneBoxRoundedTowardsTheLeft() {
        // The label's ink is labelWidth - 1 wide and its drop shadow takes the next column, so the
        // box is labelWidth wide. A 5-pixel W in a 20-pixel cap has 7 pixels each side; BrainageHUD's
        // width / 2 - labelWidth / 2 (KeystrokesHud.java:224-225) gives the same 7.
        assertEquals(7, InputOverlay.labelX(20, 6));
        assertEquals(20 / 2 - 6 / 2, InputOverlay.labelX(20, 6));
        // CTRL (24), Shift (23) and LMB (18) in a 31-pixel button: the odd pixel goes to the right.
        assertEquals(3, InputOverlay.labelX(31, 24));
        assertEquals(4, InputOverlay.labelX(31, 23));
        assertEquals(6, InputOverlay.labelX(31, 18));
        // A label wider than its cap spills evenly, rounding toward the left.
        assertEquals(-2, InputOverlay.labelX(64, 68));
        assertEquals(-2, InputOverlay.labelX(64, 67));
    }

    @Test
    void everyCapsInkAndShadowBoxHasTheSameMarginOnBothSidesToOnePixel() throws IOException {
        // Measured from ascii.png (the default font's glyphs): the ink of the last glyph ends one
        // column before its advance, and the shadow sits in the column after the ink.
        BufferedImage atlas;
        try (InputStream in = InputOverlayLayoutTest.class.getClassLoader()
                .getResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
            assertNotNull(in, "ascii.png is not on the test classpath");
            atlas = ImageIO.read(in);
        }
        for (InputOverlay.Cap cap : InputOverlay.caps(FONT_WIDTH)) {
            String label = cap.id().equals(InputOverlay.CPS_ID) ? InputOverlay.cpsLabel(3, 5) : cap.label();
            int advance = 0;
            int firstInk = Integer.MAX_VALUE;
            int lastInk = -1;
            for (char ch : label.toCharArray()) {
                int[] ink = glyphInk(atlas, ch);
                if (ink != null) {
                    firstInk = Math.min(firstInk, advance + ink[0]);
                    lastInk = Math.max(lastInk, advance + ink[1]);
                }
                advance += ink == null ? SPACE_ADVANCE : ink[1] + INK_TO_ADVANCE;
            }
            assertEquals(FONT_WIDTH.applyAsInt(label), advance, label + " advance in the test's font widths");
            int start = InputOverlay.labelX(cap.width(), advance);
            int left = start + firstInk;
            int right = cap.width() - 1 - (start + lastInk + SHADOW_OFFSET);
            assertTrue(left >= 0, label + " ink starts left of its cap");
            assertTrue(right >= 0, label + " shadow ends right of its cap");
            assertTrue(
                    left <= right && right - left <= MAX_MARGIN_DIFFERENCE,
                    label + " in a " + cap.width() + "-pixel cap has " + left + " pixels left and "
                            + right + " right of its ink and shadow");
        }
    }

    /** The first and last column of a glyph's ink in {@code ascii.png}, or null for a space. */
    private static int[] glyphInk(BufferedImage atlas, char ch) {
        String[] rows = {"", "", " !\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO",
                "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~ "};
        if (ch == ' ') {
            return null;
        }
        for (int row = 0; row < rows.length; row++) {
            int column = rows[row].indexOf(ch);
            if (column >= 0) {
                int first = ATLAS_CELL;
                int last = -1;
                for (int y = 0; y < ATLAS_CELL; y++) {
                    for (int x = 0; x < ATLAS_CELL; x++) {
                        if ((atlas.getRGB(column * ATLAS_CELL + x, row * ATLAS_CELL + y) >>> 24) != 0) {
                            first = Math.min(first, x);
                            last = Math.max(last, x);
                        }
                    }
                }
                return new int[] {first, last};
            }
        }
        throw new IllegalArgumentException("no glyph for " + ch);
    }

    @Test
    void aLabelSitsOnBrainageHudsBaseline() {
        // KeystrokesHud.java:227-229: height / 2 - lineHeight / 2. For a 20-pixel cap and the
        // 9-pixel line that is 6, so a capital's 7 rows of ink plus its shadow row (8 rows) have 6
        // pixels above and 6 below.
        assertEquals(6, InputOverlay.labelY(20, 9));
    }

    @Test
    void everyLabelAndItsShadowStayInsideItsCap() {
        // The shadow is one pixel right of the ink, and the label's advance already ends one pixel
        // after its ink, so the label's advance is the room the ink and its shadow need.
        ToIntFunction<String> wide = label -> label.length() == 1 ? 6 : 80;
        for (ToIntFunction<String> font : List.<ToIntFunction<String>>of(FONT_WIDTH, wide)) {
            for (InputOverlay.Cap cap : InputOverlay.caps(font)) {
                int left = InputOverlay.labelX(cap.width(), font.applyAsInt(cap.label()));
                assertTrue(left >= 0, cap.label() + " starts left of its cap");
                assertTrue(
                        left + font.applyAsInt(cap.label()) <= cap.width(),
                        cap.label() + " ends at " + (left + font.applyAsInt(cap.label())) + " in a "
                                + cap.width() + "-pixel cap");
            }
        }
    }

    @Test
    void thePanelIsAsBigAsItsCaps() {
        int size = InputOverlay.KEY_SIZE;
        int pad = InputOverlay.KEY_PADDING;
        int half = (int) (size * 1.5 + (double) pad / 2);

        // Every row under W/A/S/D ends at the same edge, three caps wide: two buttons and the gap.
        int button = 31;
        assertEquals(button * 2 + pad, InputOverlay.panelWidth(InputOverlay.caps(FONT_WIDTH)));
        assertEquals(size * 3 + pad * 2, InputOverlay.panelWidth(InputOverlay.caps(FONT_WIDTH)));
        assertEquals((size + pad) * 5 + size, InputOverlay.panelHeight(InputOverlay.caps(FONT_WIDTH)));
        assertTrue(half + pad + half <= size * 3 + pad * 2, "LMB and RMB at BrainageHUD's width fit the space bar");
    }

    @Test
    void aWideFontWidensEveryRowThatNeedsIt() {
        // A resource pack's font can widen any label, so every cap that carries a word is measured,
        // not only the button rows. The single letters keep BrainageHUD's 20-pixel cap.
        ToIntFunction<String> wide = label -> label.length() == 1 ? 6 : 80;
        for (InputOverlay.Cap cap : InputOverlay.caps(wide)) {
            // The spare pixel: the label's ink and shadow already fit its advance, and the cap keeps
            // one column more.
            assertTrue(
                    wide.applyAsInt(cap.label()) + InputOverlay.SPARE_PIXEL <= cap.width(),
                    cap.label() + " is " + wide.applyAsInt(cap.label()) + " wide in a "
                            + cap.width() + "-pixel cap");
        }
    }

    @Test
    void everyLabelFitsInsideItsCap() {
        // A cap narrower than its label draws the label past the panel's right edge.
        for (InputOverlay.Cap cap : InputOverlay.caps(FONT_WIDTH)) {
            assertTrue(
                    FONT_WIDTH.applyAsInt(cap.label()) <= cap.width(),
                    cap.label() + " is " + FONT_WIDTH.applyAsInt(cap.label()) + " wide in a "
                            + cap.width() + "-pixel cap");
        }
    }

    @Test
    void theClicksPerSecondRowFitsTwoDigitCountsOnBothSides() {
        InputOverlay.Cap cps = cap(InputOverlay.caps(FONT_WIDTH), "cps");

        assertTrue(FONT_WIDTH.applyAsInt(InputOverlay.cpsLabel(99, 99)) + InputOverlay.SPARE_PIXEL <= cps.width());
    }

    private static InputOverlay.Cap cap(List<InputOverlay.Cap> caps, String id) {
        return caps.stream().filter(cap -> cap.id().equals(id)).findFirst().orElseThrow();
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
        assertEquals(InputOverlay.HELD_TEXT_COLOUR, InputOverlay.textColour(1.0F));
    }

    @Test
    void aLitCapsTextHasNoShadowFromHalfwayThroughTheFade() {
        // Black text over a lit cap: its shadow is black too and doubles every stroke, so a held key's
        // text draws without one. An unlit cap's white text keeps the vanilla shadow.
        assertTrue(InputOverlay.textShadow(0.0F));
        assertTrue(InputOverlay.textShadow(Math.nextDown(InputOverlay.HELD_SHADOW_OFF_PROGRESS)));
        assertFalse(InputOverlay.textShadow(InputOverlay.HELD_SHADOW_OFF_PROGRESS));
        assertFalse(InputOverlay.textShadow(1.0F));
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
