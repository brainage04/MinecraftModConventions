package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

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
    private static final int CAP_COLOR = 0xFF5A5A5A;
    private static final int CAP_PRESSED_COLOR = 0xFFFFFFFF;
    private static final int CAP_TEXT_COLOR = 0xFFE0E0E0;
    private static final int CAP_TEXT_PRESSED_COLOR = 0xFF000000;
    private static final int MOUSE_BODY_COLOR = 0xFF5A5A5A;
    private static final int MOUSE_LIT_COLOR = 0xFFFFFFFF;
    private static final int MOUSE_OUTLINE_COLOR = 0xFFB0B0B0;
    private static final int TYPED_COLOR = 0xFFFFD54F;
    private static final int CURSOR_FILL_COLOR = 0xFFFFFFFF;
    private static final int CURSOR_OUTLINE_COLOR = 0xFF000000;
    /** The hotbar's top edge is 22 pixels above the bottom of the GUI ({@code Hud.java:575}). */
    static final int HOTBAR_HEIGHT = 22;
    /**
     * The widest the chat column gets, in GUI pixels. {@code ChatComponent.getWidth} is
     * {@code floor(pct * 280 + 40)} with a maximum of 320 ({@code ChatComponent.java:416-419}), and the
     * background is drawn from {@code -4} to {@code maxWidth + 8} after a 4-pixel translate
     * ({@code ChatComponent.java:161-167}), so its right edge is 332 at chat scale 1.
     */
    static final int CHAT_COLUMN_WIDTH = 332;

    private static final InputOverlayState STATE = new InputOverlayState();
    private static volatile boolean enabled = true;

    private InputOverlay() {
    }

    /** Reads the run's switch; called once at client start. */
    public static void initialize() {
        enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /**
     * Whether the overlay draws at all: a recording, with the switch on. A plain client GameTest run
     * draws nothing, so a mod's screenshot comparison sees the same picture it did before.
     */
    public static boolean isEnabled() {
        return enabled && ClientGameTestRecordingSession.isEnabled();
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

    /**
     * Draws the overlay and, with a screen open, the cursor. Call on the client thread, after the screen.
     * A title screen or a world-creation screen has no player and still has a cursor, so the player is
     * not required.
     */
    public static void render(GuiGraphicsExtractor graphics, Minecraft client) {
        if (!isEnabled()) {
            return;
        }
        long now = Util.getMillis();
        // Read once a frame, whether or not the panel draws, so a frame that skipped the HUD leaves
        // nothing behind for the next one.
        int performanceTop = performancePanelTop(client);
        boolean input = !STATE.isEmpty(now);
        boolean cursor = hasFreeCursor(client);
        if (!input && !cursor) {
            return;
        }
        graphics.nextStratum();
        if (input) {
            renderInput(graphics, client, now, performanceTop);
        }
        if (cursor) {
            renderCursor(graphics, client);
        }
    }

    /** Whether a screen has the mouse free, so the overlay draws the cursor. */
    private static boolean hasFreeCursor(Minecraft client) {
        return client.gui.screen() != null && !client.mouseHandler.isMouseGrabbed();
    }

    private static void renderInput(
            GuiGraphicsExtractor graphics, Minecraft client, long now, int performanceTop) {
        Font font = client.font;
        int maxContentWidth = Math.max(
                MOUSE_WIDTH + CAP_GAP,
                graphics.guiWidth() - SCREEN_MARGIN * 2 - PANEL_PADDING * 2 - CHAT_COLUMN_WIDTH);
        List<InputOverlayState.Chip> chips = fitChips(font::width, STATE.chips(now), maxContentWidth);
        String typed = fitText(font::width, STATE.typedText(now), maxContentWidth);
        int capsWidth = 0;
        for (InputOverlayState.Chip chip : chips) {
            capsWidth += capWidth(font::width, chip.label()) + CAP_GAP;
        }
        capsWidth += MOUSE_WIDTH + CAP_GAP;
        int typedWidth = typed.isEmpty() ? 0 : font.width("> " + typed);
        int contentWidth = Math.max(capsWidth, typedWidth);
        int rowHeight = Math.max(CAP_HEIGHT, MOUSE_HEIGHT);
        int contentHeight = rowHeight + (typed.isEmpty() ? 0 : font.lineHeight + LINE_GAP);
        int panelWidth = contentWidth + PANEL_PADDING * 2;
        int panelHeight = contentHeight + PANEL_PADDING * 2;

        int[] origin = panelOrigin(
                graphics.guiWidth(),
                graphics.guiHeight(),
                panelWidth,
                panelHeight,
                performanceTop);
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
            int width = capWidth(font::width, chip.label());
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
        renderMouse(graphics, x, textY, now);
    }

    /**
     * The mouse glyph: a rounded body whose top half is split into a left and a right button, each lit
     * while it is down, with the wheel between them.
     */
    private static void renderMouse(GuiGraphicsExtractor graphics, int x, int y, long now) {
        int half = MOUSE_WIDTH / 2;
        int buttonBottom = y + MOUSE_HEIGHT / 2;
        graphics.fill(x, y, x + MOUSE_WIDTH, y + MOUSE_HEIGHT, MOUSE_BODY_COLOR);
        // The split between the buttons and the line under them, so the body reads as a mouse.
        graphics.fill(x + half, y, x + half + 1, buttonBottom, MOUSE_OUTLINE_COLOR);
        graphics.fill(x, buttonBottom, x + MOUSE_WIDTH, buttonBottom + 1, MOUSE_OUTLINE_COLOR);
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_LEFT, now)) {
            graphics.fill(x, y, x + half, buttonBottom, MOUSE_LIT_COLOR);
        }
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_RIGHT, now)) {
            graphics.fill(x + half + 1, y, x + MOUSE_WIDTH, buttonBottom, MOUSE_LIT_COLOR);
        }
        if (STATE.mouseLit(InputConstants.MOUSE_BUTTON_MIDDLE, now)) {
            graphics.fill(x + half - 1, y, x + half + 2, y + 3, MOUSE_LIT_COLOR);
        }
        graphics.outline(x, y, MOUSE_WIDTH, MOUSE_HEIGHT, MOUSE_OUTLINE_COLOR);
    }

    /**
     * The cursor, at the game's own mouse position, whenever a screen has the mouse free. The capture
     * holds no operating-system cursor, so without this a click on a chat link or an inventory slot
     * shows nothing moving.
     */
    private static void renderCursor(GuiGraphicsExtractor graphics, Minecraft client) {
        int x = (int) client.mouseHandler.getScaledXPos(client.getWindow());
        int y = (int) client.mouseHandler.getScaledYPos(client.getWindow());
        // A pointer: a white arrow with a black edge, its tip at (x, y).
        for (int row = 0; row < CURSOR_HEIGHT; row++) {
            int left = cursorRowLeft(row);
            int right = left + cursorRowWidth(row);
            graphics.fill(x + left, y + row, x + right, y + row + 1, CURSOR_FILL_COLOR);
            graphics.fill(x + left - 1, y + row, x + left, y + row + 1, CURSOR_OUTLINE_COLOR);
            graphics.fill(x + right, y + row, x + right + 1, y + row + 1, CURSOR_OUTLINE_COLOR);
        }
        graphics.fill(x - 1, y - 1, x + cursorRowWidth(0) + 1, y, CURSOR_OUTLINE_COLOR);
        int tailLeft = cursorRowLeft(CURSOR_HEIGHT - 1);
        graphics.fill(x + tailLeft - 1, y + CURSOR_HEIGHT, x + tailLeft + cursorRowWidth(CURSOR_HEIGHT - 1) + 1,
                y + CURSOR_HEIGHT + 1, CURSOR_OUTLINE_COLOR);
    }

    /** The cursor's height in GUI pixels. */
    static final int CURSOR_HEIGHT = 12;
    /** The rows of the cursor's head, the triangle from its tip down to its base. */
    static final int CURSOR_HEAD_ROWS = 9;

    /**
     * How wide the cursor's arrow is on {@code row}, from its tip. The head widens by one pixel every
     * two rows, so it is taller than it is wide and reads as a pointer rather than a triangle, and the
     * tail below it is two pixels wide.
     */
    static int cursorRowWidth(int row) {
        if (row < 0 || row >= CURSOR_HEIGHT) {
            return 0;
        }
        return row < CURSOR_HEAD_ROWS ? row / 2 + 1 : 2;
    }

    /** How far right of the tip the cursor's arrow starts on {@code row}: the tail leans right. */
    static int cursorRowLeft(int row) {
        if (row < 0 || row >= CURSOR_HEIGHT) {
            return 0;
        }
        return row < CURSOR_HEAD_ROWS ? 0 : row - CURSOR_HEAD_ROWS + 3;
    }

    /**
     * The panel's top-left corner: the bottom-right of the GUI, above the hotbar, and above the
     * performance overlay when a test put that in the bottom-right corner too.
     */
    static int[] panelOrigin(int guiWidth, int guiHeight, int panelWidth, int panelHeight, int performanceTop) {
        int x = Math.max(SCREEN_MARGIN, guiWidth - panelWidth - SCREEN_MARGIN);
        int y = guiHeight - panelHeight - SCREEN_MARGIN - HOTBAR_HEIGHT;
        if (performanceTop != Integer.MIN_VALUE) {
            y = Math.max(SCREEN_MARGIN, Math.min(y, performanceTop - panelHeight - LINE_GAP));
        }
        return new int[] {x, y};
    }

    /**
     * The performance overlay's top edge when it is in the bottom-right corner, else
     * {@link Integer#MIN_VALUE}. The panel's own rectangle comes from the frame it was drawn in, so the
     * input panel is placed against the real panel rather than against a second guess at its size.
     */
    private static int performancePanelTop(Minecraft client) {
        if (!ClientGameTestRecordingSession.isEnabled()) {
            return Integer.MIN_VALUE;
        }
        int[] performance = ClientGameTestRecordingHud.consumePerformancePanelRect();
        if (performance == null) {
            return Integer.MIN_VALUE;
        }
        boolean bottomRight = performance[0] > client.getWindow().getGuiScaledWidth() / 2
                && performance[1] > client.getWindow().getGuiScaledHeight() / 2;
        return bottomRight ? performance[1] : Integer.MIN_VALUE;
    }

    private static int capWidth(ToIntFunction<String> textWidth, String label) {
        return textWidth.applyAsInt(label) + CAP_PADDING * 2;
    }

    /**
     * The caps that fit beside the mouse glyph in {@code maxWidth}, in press order. A cap that does not
     * fit is skipped rather than ending the row, so one wide label does not hide the narrow keys after it.
     * {@code textWidth} is the font's width of a string, so the rule is tested without a client.
     */
    static List<InputOverlayState.Chip> fitChips(
            ToIntFunction<String> textWidth, List<InputOverlayState.Chip> chips, int maxWidth) {
        List<InputOverlayState.Chip> fitted = new ArrayList<>();
        int width = MOUSE_WIDTH + CAP_GAP;
        for (InputOverlayState.Chip chip : chips) {
            int capWidth = capWidth(textWidth, chip.label()) + CAP_GAP;
            if (width + capWidth > maxWidth) {
                continue;
            }
            fitted.add(chip);
            width += capWidth;
        }
        return fitted;
    }

    /** The typed text cut to {@code maxWidth}, with an ellipsis when it does not fit. */
    static String fitText(ToIntFunction<String> textWidth, String text, int maxWidth) {
        if (text.isEmpty() || textWidth.applyAsInt("> " + text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 0 && textWidth.applyAsInt("> " + text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end == 0 ? "" : text.substring(0, end) + suffix;
    }
}
