package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

import java.util.List;

/**
 * The recording's input overlay: the keys and mouse buttons the game received, and a cursor wherever a
 * screen has the mouse free.
 *
 * <p>A recording is watched to see what a player did, and the capture holds no operating-system cursor,
 * so a right click on a block or a click on a chat link is invisible without this. The overlay draws
 * each press as a key cap, lights the mouse glyph's pressed button, and keeps a press on screen for
 * {@link InputOverlayState#MIN_VISIBLE_MILLIS} after its release, because a client GameTest presses a
 * key for one tick, which is a single frame of a 30 fps recording.
 *
 * <p>Input arrives where the game receives it, not where a test sends it: {@code KeyboardHandler.keyPress}
 * ({@code KeyboardHandler.java:436} in the 26.3 sources), {@code KeyboardHandler.charTyped}
 * ({@code KeyboardHandler.java:578}) and {@code MouseHandler.onButton} ({@code MouseHandler.java:63}).
 * Fabric's client GameTest input calls exactly those three methods
 * ({@code TestInputImpl.pressOrReleaseKey}, {@code typeChar}, {@code pressOrReleaseKey}'s mouse branch),
 * so a test's presses and a player's presses look the same.
 *
 * <p>The overlay is drawn at the end of {@code Gui.extractRenderState} ({@code Gui.java:144-211}), after
 * the screen and its tooltips, so it stays on top of an open screen and its cursor sits over the
 * screen's widgets. It is on for every recording; {@code -PinputOverlay=false} or the
 * {@code clientGameTestRecorder { inputOverlay = false }} switch turns it off.
 */
public final class InputOverlay {
    /** The switch that turns the overlay off for a run; the Gradle plugin sets it from its extension. */
    public static final String ENABLED_PROPERTY = "minecraftmodconventions.clientGameTestRecorder.inputOverlay";

    private static final int SCREEN_MARGIN = 5;
    private static final int PANEL_PADDING = 3;
    private static final int CAP_HEIGHT = 11;
    private static final int CAP_PADDING = 3;
    private static final int CAP_GAP = 2;
    private static final int LINE_GAP = 2;
    private static final int MOUSE_WIDTH = 9;
    private static final int MOUSE_HEIGHT = 13;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int CAP_COLOR = 0xFF303030;
    private static final int CAP_PRESSED_COLOR = 0xFFFFFFFF;
    private static final int CAP_TEXT_COLOR = 0xFFE0E0E0;
    private static final int CAP_TEXT_PRESSED_COLOR = 0xFF000000;
    private static final int MOUSE_BODY_COLOR = 0xFF404040;
    private static final int MOUSE_LIT_COLOR = 0xFFFFFFFF;
    private static final int MOUSE_OUTLINE_COLOR = 0xFFB0B0B0;
    private static final int TYPED_COLOR = 0xFFFFD54F;
    private static final int CURSOR_FILL_COLOR = 0xFFFFFFFF;
    private static final int CURSOR_OUTLINE_COLOR = 0xFF000000;
    /** The hotbar's top edge is 22 pixels above the bottom of the GUI ({@code Hud.java:575}). */
    static final int HOTBAR_HEIGHT = 22;

    private static final InputOverlayState STATE = new InputOverlayState();
    private static volatile boolean enabled = true;

    private InputOverlay() {
    }

    /** Reads the run's switch; called once at client start. */
    public static void initialize() {
        enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /** Whether the overlay draws at all: a recording, with the switch on. */
    public static boolean isEnabled() {
        return enabled && GameTestRecorderEnvironment.isEnabled();
    }

    /** Turns the overlay on or off for the rest of the run. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Forgets every key, button and typed character, for the start of a recording. */
    public static void clear() {
        STATE.clear();
    }

    /** A key the game received: {@code action} is {@code InputConstants.PRESS}, {@code RELEASE} or {@code REPEAT}. */
    public static void onKey(InputConstants.Key key, int action) {
        if (!isEnabled() || key == null) {
            return;
        }
        long now = Util.getMillis();
        String id = key.getName();
        if (action == InputConstants.RELEASE) {
            STATE.keyUp(id, now);
        } else {
            STATE.keyDown(id, InputOverlayLabels.label(id), now);
        }
    }

    /** A mouse button the game received, with the same actions as {@link #onKey}. */
    public static void onMouseButton(int button, int action) {
        if (!isEnabled()) {
            return;
        }
        if (action == InputConstants.RELEASE) {
            STATE.mouseUp(button, Util.getMillis());
        } else {
            STATE.mouseDown(button);
        }
    }

    /** A character typed into a screen. */
    public static void onChar(int codepoint) {
        if (!isEnabled()) {
            return;
        }
        STATE.typed(Character.toString(codepoint), Util.getMillis());
    }

    /** Draws the overlay and, with a screen open, the cursor. Call on the client thread, after the screen. */
    public static void render(GuiGraphicsExtractor graphics, Minecraft client) {
        if (!isEnabled() || client.player == null) {
            return;
        }
        long now = Util.getMillis();
        graphics.nextStratum();
        renderInput(graphics, client, now);
        renderCursor(graphics, client);
    }

    private static void renderInput(GuiGraphicsExtractor graphics, Minecraft client, long now) {
        Font font = client.font;
        List<InputOverlayState.Chip> chips = STATE.chips(now);
        String typed = STATE.typedText(now);
        if (chips.isEmpty() && typed.isEmpty()) {
            return;
        }

        int capsWidth = 0;
        for (InputOverlayState.Chip chip : chips) {
            capsWidth += capWidth(font, chip.label()) + CAP_GAP;
        }
        if (!chips.isEmpty()) {
            capsWidth += MOUSE_WIDTH + CAP_GAP;
        }
        int typedWidth = typed.isEmpty() ? 0 : font.width("> " + typed);
        int contentWidth = Math.max(capsWidth, typedWidth);
        int contentHeight = CAP_HEIGHT + (typed.isEmpty() ? 0 : font.lineHeight + LINE_GAP);
        int panelWidth = contentWidth + PANEL_PADDING * 2;
        int panelHeight = contentHeight + PANEL_PADDING * 2;

        int[] origin = panelOrigin(
                graphics.guiWidth(),
                graphics.guiHeight(),
                panelWidth,
                panelHeight,
                performancePanelTop(client, panelWidth, panelHeight));
        int panelX = origin[0];
        int panelY = origin[1];
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BACKGROUND_COLOR);

        int textY = panelY + PANEL_PADDING;
        if (!typed.isEmpty()) {
            graphics.text(font, "> " + typed, panelX + PANEL_PADDING, textY, TYPED_COLOR, true);
            textY += font.lineHeight + LINE_GAP;
        }

        int x = panelX + PANEL_PADDING;
        for (InputOverlayState.Chip chip : chips) {
            int width = capWidth(font, chip.label());
            graphics.fill(x, textY, x + width, textY + CAP_HEIGHT, chip.pressed() ? CAP_PRESSED_COLOR : CAP_COLOR);
            graphics.text(
                    font,
                    chip.label(),
                    x + CAP_PADDING,
                    textY + (CAP_HEIGHT - font.lineHeight) / 2 + 1,
                    chip.pressed() ? CAP_TEXT_PRESSED_COLOR : CAP_TEXT_COLOR,
                    false);
            x += width + CAP_GAP;
        }
        if (!chips.isEmpty()) {
            renderMouse(graphics, x, textY, now);
        }
    }

    /** The mouse glyph: a body split into a left and a right half, each lit while its button is down. */
    private static void renderMouse(GuiGraphicsExtractor graphics, int x, int y, long now) {
        int half = MOUSE_WIDTH / 2;
        graphics.fill(x, y, x + MOUSE_WIDTH, y + MOUSE_HEIGHT, MOUSE_BODY_COLOR);
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_LEFT, now)) {
            graphics.fill(x, y, x + half, y + MOUSE_HEIGHT / 2, MOUSE_LIT_COLOR);
        }
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_RIGHT, now)) {
            graphics.fill(x + half + 1, y, x + MOUSE_WIDTH, y + MOUSE_HEIGHT / 2, MOUSE_LIT_COLOR);
        }
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_MIDDLE, now)) {
            graphics.fill(x + half - 1, y, x + half + 1, y + MOUSE_HEIGHT / 2, MOUSE_LIT_COLOR);
        }
        graphics.outline(x, y, MOUSE_WIDTH, MOUSE_HEIGHT, MOUSE_OUTLINE_COLOR);
    }

    /**
     * The cursor, at the game's own mouse position, whenever a screen has the mouse free. The capture
     * holds no operating-system cursor, so without this a click on a chat link or an inventory slot
     * shows nothing moving.
     */
    private static void renderCursor(GuiGraphicsExtractor graphics, Minecraft client) {
        if (client.gui.screen() == null || client.mouseHandler.isMouseGrabbed()) {
            return;
        }
        int x = (int) client.mouseHandler.getScaledXPos(client.getWindow());
        int y = (int) client.mouseHandler.getScaledYPos(client.getWindow());
        // A pointer: a filled triangle with a black edge, drawn from the tip at (x, y).
        for (int row = 0; row < CURSOR_HEIGHT; row++) {
            int width = cursorRowWidth(row);
            graphics.fill(x, y + row, x + width, y + row + 1, CURSOR_FILL_COLOR);
            graphics.fill(x - 1, y + row, x, y + row + 1, CURSOR_OUTLINE_COLOR);
            graphics.fill(x + width, y + row, x + width + 1, y + row + 1, CURSOR_OUTLINE_COLOR);
        }
        graphics.fill(x - 1, y - 1, x + cursorRowWidth(0) + 1, y, CURSOR_OUTLINE_COLOR);
        graphics.fill(x - 1, y + CURSOR_HEIGHT, x + cursorRowWidth(CURSOR_HEIGHT - 1) + 1, y + CURSOR_HEIGHT + 1,
                CURSOR_OUTLINE_COLOR);
    }

    /** The cursor's height in GUI pixels. */
    static final int CURSOR_HEIGHT = 11;

    /** How wide the cursor's arrow is on {@code row}, from its tip. */
    static int cursorRowWidth(int row) {
        if (row < 0 || row >= CURSOR_HEIGHT) {
            return 0;
        }
        // A 1-pixel tip that widens by one every row to 6, then narrows to the tail.
        int widest = 6;
        int peak = 5;
        return row <= peak ? row + 1 : Math.max(1, widest - (row - peak));
    }

    /**
     * The panel's top-left corner: the bottom-right of the GUI, above the hotbar, and above the
     * performance overlay when a test put that in the bottom-right corner too.
     */
    static int[] panelOrigin(int guiWidth, int guiHeight, int panelWidth, int panelHeight, int performanceTop) {
        int x = guiWidth - panelWidth - SCREEN_MARGIN;
        int y = guiHeight - panelHeight - SCREEN_MARGIN - HOTBAR_HEIGHT;
        if (performanceTop != Integer.MIN_VALUE) {
            y = Math.max(SCREEN_MARGIN, Math.min(y, performanceTop - panelHeight - LINE_GAP));
        }
        return new int[] {x, y};
    }

    /** The performance overlay's top edge when it is in the bottom-right corner, else {@link Integer#MIN_VALUE}. */
    private static int performancePanelTop(Minecraft client, int panelWidth, int panelHeight) {
        if (!ClientGameTestRecordingSession.isEnabled()) {
            return Integer.MIN_VALUE;
        }
        int[] performance = ClientGameTestRecordingHud.performancePanel(
                true,
                ClientGameTestRecordingHud.performanceOverlayPlacement(),
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(),
                SCREEN_MARGIN + ClientGameTestRecordingHud.panelHeight(
                        ClientGameTestRecordingHud.captionLinesAtMost(
                                "showcase".equals(System.getenv(GameTestRecorderEnvironment.RECORDING_PROFILE_ENV)))),
                panelWidth,
                panelHeight,
                client.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen);
        if (performance == null) {
            return Integer.MIN_VALUE;
        }
        boolean bottomRight = performance[0] > client.getWindow().getGuiScaledWidth() / 2
                && performance[1] > client.getWindow().getGuiScaledHeight() / 2;
        return bottomRight ? performance[1] : Integer.MIN_VALUE;
    }

    private static int capWidth(Font font, String label) {
        return font.width(label) + CAP_PADDING * 2;
    }
}
