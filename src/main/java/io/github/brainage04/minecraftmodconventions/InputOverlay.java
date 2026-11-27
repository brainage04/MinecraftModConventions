package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The recording's keystrokes overlay: the nine keys a film is watched for, always on screen in the
 * bottom-right corner, each lit while it is down, and the clicks per second of LMB and RMB.
 *
 * <p>The layout and colours follow BrainageHUD's keystrokes HUD ({@code KeystrokesHud}): 20-pixel
 * caps two pixels apart, W over S, A and D beside it, a space bar three caps wide, then LMB and RMB
 * with the clicks-per-second row under them, black at 100/255 with white text, lit to white at the
 * same opacity with black text, drawn without a shadow, over 100 ms. The row reads {@code 3 | 5 CPS}
 * (LMB, RMB), counting the presses of the last second as that HUD does. Shift and Ctrl come last,
 * as wide as LMB and RMB, and the space bar is labelled {@code Space} rather than drawn as a bar.
 *
 * <p>A recording is watched to see what a player did, and a client GameTest presses a key for one
 * tick, shorter than two frames of a 30 fps recording. A cap therefore stays lit for
 * {@link InputOverlayState#MIN_VISIBLE_MILLIS} after its release, and a key the game still holds
 * down stays lit for as long as it is held.
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

    /** A cap's side in GUI pixels, and the gap between caps: BrainageHUD's keystrokes HUD. */
    static final int KEY_SIZE = 20;

    static final int KEY_PADDING = 2;

    /**
     * One spare pixel a widened cap keeps to the right of its label. The label's ink and its drop
     * shadow already sit inside the label's advance (the ink is one pixel narrower, the shadow takes
     * that pixel, {@link #labelX}), so this pixel is not needed to avoid clipping: it keeps a cap
     * from being exactly as wide as its text.
     */
    static final int SPARE_PIXEL = 1;

    /** The hotbar's top edge is 22 pixels above the bottom of the GUI ({@code Hud.java:575}). */
    static final int HOTBAR_HEIGHT = 22;

    private static final int SCREEN_MARGIN = 5;
    private static final int LINE_GAP = 2;

    /** A cap's backdrop: black at 100/255, as BrainageHUD's keystrokes HUD draws it. */
    static final int BACKDROP_OPACITY = 100;

    /** The text on an unlit cap; a lit cap's backdrop takes its RGB at {@link #BACKDROP_OPACITY}. */
    static final int TEXT_COLOUR = 0xFFFFFFFF;

    /**
     * The text colour a fully lit cap fades to over {@link #TRANSITION_NANOS}. BrainageHUD fades to
     * black too ({@code KeystrokesHud.java:209-210} at BrainageHUD 88d8cc0). Changing it is this one
     * line.
     */
    static final int HELD_TEXT_COLOUR = 0xFF000000;

    /**
     * The progress from which a lit cap's text draws no shadow: halfway through the fade, so the
     * shadow is gone while the cap is still lit and the glyph is thin as soon as the key reads as held.
     */
    static final float HELD_SHADOW_OFF_PROGRESS = 0.5F;

    static final String SNEAK_LABEL = "Shift";

    static final String SPRINT_LABEL = "Ctrl";

    /** How long a cap takes to light or dim, as BrainageHUD's keystrokes HUD does. */
    static final long TRANSITION_NANOS = 100_000_000L;

    private static final int TYPED_COLOUR = 0xFFFFD54F;
    private static final int CURSOR_FILL_COLOUR = 0xFFFFFFFF;
    private static final int CURSOR_OUTLINE_COLOUR = 0xFF000000;

    private static final InputOverlayState STATE = new InputOverlayState();
    private static volatile boolean enabled = true;

    /**
     * Ten, because {@link #caps} needs a text width and the client's font does not exist at
     * class-init; {@code everyKeyTheFilmShowsHasACap} pins the count.
     */
    private static final int CAP_COUNT = 10;

    /** How lit each cap is, 0 to 1, in {@link #caps} order. */
    private static final float[] PROGRESS = new float[CAP_COUNT];

    private static long lastNanos;

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
        Arrays.fill(PROGRESS, 0.0F);
        lastNanos = 0L;
    }

    /**
     * A key the game received: {@code action} is {@code InputConstants.PRESS}, {@code RELEASE} or
     * {@code REPEAT}.
     *
     * <p>Every press is kept, not only the ones the game sets on a key mapping: a press with a
     * screen open does not reach the mapping ({@code KeyboardHandler.java:568-570} in 26.3), and a
     * one-tick press is dark on the next frame either way, so the press is what the afterglow is
     * measured from.
     */
    public static void onKey(InputConstants.Key key, int action) {
        if (!enabled || key == null) {
            return;
        }
        long now = Util.getMillis();
        if (action == InputConstants.RELEASE) {
            STATE.keyUp(key.getName(), now);
        } else {
            STATE.keyDown(key.getName(), now);
        }
    }

    /**
     * Releases every key that is down, for a screen opening, each with the afterglow of a normal
     * release. The game releases every mapping there without sending a release event
     * ({@code Gui.java:263} in 26.3), so a key held at that moment would otherwise stay lit for the
     * rest of the recording.
     */
    public static void releaseAll() {
        STATE.releaseAll(Util.getMillis());
    }

    /** A mouse button the game received, with the same actions as {@link #onKey}. */
    public static void onMouseButton(int button, int action) {
        onKey(InputConstants.Type.MOUSE.getOrCreate(button), action);
    }

    /** A character typed into a screen. */
    public static void onChar(int codepoint) {
        if (!enabled) {
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
        long nowNanos = System.nanoTime();
        int performanceTop = performancePanelTop(client);
        List<Cap> caps = caps(client.font::width);
        int[] origin = panelOrigin(
                graphics.guiWidth(),
                graphics.guiHeight(),
                panelWidth(caps),
                panelHeight(caps),
                performanceTop,
                chatColumnWidth(client));
        graphics.nextStratum();
        renderCaps(graphics, client, caps, origin, now, nowNanos);
        renderTyped(graphics, client, caps, origin, now);
        if (hasFreeCursor(client)) {
            renderCursor(graphics, client);
        }
    }

    /** A key cap: the key it shows, its label, and where it sits in the panel. */
    record Cap(String id, String label, int x, int y, int width, int height) {
    }

    /** The id of the clicks-per-second row, the one cap that shows no key. */
    static final String CPS_ID = "cps";

    /**
     * The ten caps, in BrainageHUD's keystrokes layout: W over S, A and D beside it, a space bar
     * three caps wide, LMB and RMB, the clicks-per-second row under them (three caps wide, as
     * {@code KeystrokesHud.java:147-148} draws it), then Shift and Ctrl. The ids select the
     * client's key mapping in {@link #mapping}, so a rebound key still lights its cap.
     *
     * <p>LMB, RMB, Shift and Ctrl are one width, so Shift and Ctrl sit exactly under LMB and RMB:
     * BrainageHUD's half-cap (31), or the widest of their labels plus {@link #SPARE_PIXEL} when that
     * is wider. Two buttons and the gap are then as wide as the space bar and the clicks row (64), so
     * every row under W/A/S/D shares both edges; a font that widens a button widens the space bar
     * and the clicks row to the same span. Every cap that carries a word is at least as wide as its
     * label plus {@link #SPARE_PIXEL}, so a label and its shadow stay inside the cap with a pixel to
     * spare. The single letters keep BrainageHUD's 20-pixel cap.
     */
    static List<Cap> caps(ToIntFunction<String> textWidth) {
        int size = KEY_SIZE;
        int pad = KEY_PADDING;
        int half = (int) (size * 1.5 + (double) pad / 2);
        int widestButton = Math.max(
                Math.max(textWidth.applyAsInt("LMB"), textWidth.applyAsInt("RMB")),
                Math.max(textWidth.applyAsInt(SNEAK_LABEL), textWidth.applyAsInt(SPRINT_LABEL)));
        int button = Math.max(half, widestButton + SPARE_PIXEL);
        int span = Math.max(
                Math.max(size * 3 + pad * 2, button * 2 + pad),
                Math.max(
                        textWidth.applyAsInt("Space") + SPARE_PIXEL,
                        textWidth.applyAsInt(cpsLabel(99, 99)) + SPARE_PIXEL));
        List<Cap> caps = new ArrayList<>(CAP_COUNT);
        caps.add(new Cap("forward", "W", size + pad, 0, size, size));
        int row = size + pad;
        caps.add(new Cap("left", "A", 0, row, size, size));
        caps.add(new Cap("back", "S", size + pad, row, size, size));
        caps.add(new Cap("right", "D", (size + pad) * 2, row, size, size));
        row += size + pad;
        caps.add(new Cap("jump", "Space", 0, row, span, size));
        row += size + pad;
        caps.add(new Cap("attack", "LMB", 0, row, button, size));
        caps.add(new Cap("use", "RMB", span - button, row, button, size));
        row += size + pad;
        caps.add(new Cap(CPS_ID, cpsLabel(0, 0), 0, row, span, size));
        row += size + pad;
        caps.add(new Cap("sneak", SNEAK_LABEL, 0, row, button, size));
        caps.add(new Cap("sprint", SPRINT_LABEL, span - button, row, button, size));
        return caps;
    }

    /** The panel's width: the right edge of its widest cap. */
    static int panelWidth(List<Cap> caps) {
        int width = 0;
        for (Cap cap : caps) {
            width = Math.max(width, cap.x() + cap.width());
        }
        return width;
    }

    /** The panel's height: the bottom edge of its lowest cap. */
    static int panelHeight(List<Cap> caps) {
        int height = 0;
        for (Cap cap : caps) {
            height = Math.max(height, cap.y() + cap.height());
        }
        return height;
    }

    /** The key mapping a cap shows, from the client's own options, so a rebound key lights its cap. */
    private static KeyMapping mapping(Minecraft client, String id) {
        return switch (id) {
            case "forward" -> client.options.keyUp;
            case "left" -> client.options.keyLeft;
            case "back" -> client.options.keyDown;
            case "right" -> client.options.keyRight;
            case "jump" -> client.options.keyJump;
            case "sneak" -> client.options.keyShift;
            case "sprint" -> client.options.keySprint;
            case "attack" -> client.options.keyAttack;
            case "use" -> client.options.keyUse;
            default -> throw new IllegalArgumentException("No key mapping for cap " + id);
        };
    }

    /**
     * Whether a cap is lit: the game holds its key down, the game received the key and has not
     * received its release, or the key was released within {@link InputOverlayState#MIN_VISIBLE_MILLIS}.
     * The mapping is read as well as the events, so a toggle key such as sneak stays lit while it is
     * toggled on, and a key pressed with a screen open, which the game does not set on its mapping,
     * still lights.
     */
    private static boolean isLit(Minecraft client, Cap cap, long now) {
        KeyMapping mapping = mapping(client, cap.id());
        return mapping.isDown() || keyLit(mapping.saveString(), now);
    }

    /**
     * Whether the events the game received light a key: it is down, or it was released within
     * {@link InputOverlayState#MIN_VISIBLE_MILLIS}. The mapping is read separately, because a toggle
     * key such as sneak stays down while it is toggled on and no event says so.
     */
    static boolean keyLit(String id, long now) {
        return STATE.isDown(id) || STATE.keyLit(id, now);
    }

    private static void renderCaps(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            List<Cap> caps,
            int[] origin,
            long now,
            long nowNanos) {
        Font font = client.font;
        long elapsed = lastNanos == 0L ? 0L : nowNanos - lastNanos;
        lastNanos = nowNanos;
        for (int index = 0; index < caps.size(); index++) {
            Cap cap = caps.get(index);
            boolean counter = cap.id().equals(CPS_ID);
            // The clicks-per-second row has no key: it never lights, as BrainageHUD's (key null).
            PROGRESS[index] = counter ? 0.0F : advance(PROGRESS[index], isLit(client, cap, now), elapsed);
            float progress = PROGRESS[index];
            String label = counter
                    ? cpsLabel(
                            clicksPerSecond(client.options.keyAttack, now),
                            clicksPerSecond(client.options.keyUse, now))
                    : cap.label();
            int x = origin[0] + cap.x();
            int y = origin[1] + cap.y();
            graphics.fill(x, y, x + cap.width(), y + cap.height(), backdropColour(progress));
            graphics.text(
                    font,
                    label,
                    x + labelX(cap.width(), font.width(label)),
                    y + labelY(cap.height(), font.lineHeight),
                    textColour(progress),
                    textShadow(progress));
        }
    }

    /**
     * Where a label starts inside its cap, from the cap's left edge: it centres the label's ink and
     * its drop shadow as one box and rounds toward the left. With the default font's glyphs, which
     * advance by their ink width plus one ({@code BitmapProvider.java}, {@code (int) (0.5 +
     * inkWidth) + 1}), the ink is {@code labelWidth - 1} pixels wide and its shadow
     * ({@code GlyphInfo.java:14-15}, one pixel right and down) takes the next column, so the box is
     * {@code labelWidth} wide and starts at {@code labelX}. A 5-pixel {@code W} in a 20-pixel cap
     * starts at 7 (7 pixels each side of the box), the same x as BrainageHUD's
     * {@code width / 2 - labelWidth / 2} ({@code KeystrokesHud.java:224-225}); an odd leftover pixel
     * goes to the right. The box ends at {@code labelX + labelWidth}, inside the cap whenever the cap is
     * at least that wide, so a cap sized by {@link #caps} never clips the shadow.
     */
    static int labelX(int capWidth, int labelWidth) {
        return Math.floorDiv(capWidth - labelWidth, 2);
    }

    /**
     * Where a label's line starts inside its cap, from the cap's top edge: BrainageHUD's
     * {@code height / 2 - lineHeight / 2} ({@code KeystrokesHud.java:228-229}), 6 for a 20-pixel cap
     * and the 9-pixel line. A capital's 7 rows of ink and the shadow's row below them are then 6
     * pixels from the top and 6 from the bottom of the cap.
     */
    static int labelY(int capHeight, int lineHeight) {
        return capHeight / 2 - lineHeight / 2;
    }

    /** The clicks-per-second row's text: BrainageHUD's {@code BOTH} format ({@code KeystrokesHud.java:139}). */
    static String cpsLabel(int attackClicks, int useClicks) {
        return "%d | %d CPS".formatted(attackClicks, useClicks);
    }

    /** How many presses the mapping's key had in the last second. */
    private static int clicksPerSecond(KeyMapping mapping, long now) {
        return STATE.clicksPerSecond(mapping.saveString(), now);
    }

    /**
     * How lit a cap is after {@code elapsedNanos}: it moves a hundredth of the way per millisecond,
     * so a press lights in {@link #TRANSITION_NANOS} and a release dims in the same time.
     */
    static float advance(float progress, boolean down, long elapsedNanos) {
        float amount = Math.min(1.0F, (float) Math.max(0L, elapsedNanos) / TRANSITION_NANOS);
        return down ? Math.min(1.0F, progress + amount) : Math.max(0.0F, progress - amount);
    }

    /** A cap's backdrop: black at {@link #BACKDROP_OPACITY}, lit to white at the same opacity. */
    static int backdropColour(float progress) {
        return ARGB.srgbLerp(
                progress,
                ARGB.color(BACKDROP_OPACITY, 0, 0, 0),
                ARGB.color(BACKDROP_OPACITY, 255, 255, 255));
    }

    /** A cap's text: {@link #TEXT_COLOUR}, lit to {@link #HELD_TEXT_COLOUR}. */
    static int textColour(float progress) {
        return ARGB.srgbLerp(progress, TEXT_COLOUR, HELD_TEXT_COLOUR);
    }

    /**
     * Whether a cap's text draws its drop shadow. The shadow of black text is black, one pixel right
     * and down ({@code Font.java:357}, {@code GlyphInfo.java:14-15}), which doubles every stroke, so
     * the text drops it from {@link #HELD_SHADOW_OFF_PROGRESS} on.
     */
    static boolean textShadow(float progress) {
        return progress < HELD_SHADOW_OFF_PROGRESS;
    }

    /** The text typed into a screen, on a line above the caps, right-aligned to the panel. */
    private static void renderTyped(
            GuiGraphicsExtractor graphics, Minecraft client, List<Cap> caps, int[] origin, long now) {
        String typed = STATE.typedText(now);
        if (typed.isEmpty()) {
            return;
        }
        Font font = client.font;
        int maxWidth = maxContentWidth(graphics.guiWidth(), chatColumnWidth(client));
        String fitted = fitText(font::width, typed, maxWidth);
        if (fitted.isEmpty()) {
            return;
        }
        String line = "> " + fitted;
        int x = origin[0] + panelWidth(caps) - font.width(line);
        int y = Math.max(SCREEN_MARGIN, origin[1] - font.lineHeight - LINE_GAP);
        graphics.text(font, line, x, y, TYPED_COLOUR, true);
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
            graphics.fill(x + left, y + row, x + right, y + row + 1, CURSOR_FILL_COLOUR);
            graphics.fill(x + left - 1, y + row, x + left, y + row + 1, CURSOR_OUTLINE_COLOUR);
            graphics.fill(x + right, y + row, x + right + 1, y + row + 1, CURSOR_OUTLINE_COLOUR);
        }
        graphics.fill(x - 1, y - 1, x + cursorRowWidth(0) + 1, y, CURSOR_OUTLINE_COLOUR);
        int tailLeft = cursorRowLeft(CURSOR_HEIGHT - 1);
        graphics.fill(x + tailLeft - 1, y + CURSOR_HEIGHT, x + tailLeft + cursorRowWidth(CURSOR_HEIGHT - 1) + 1,
                y + CURSOR_HEIGHT + 1, CURSOR_OUTLINE_COLOUR);
    }

    /** Whether a screen has the mouse free, so the overlay draws the cursor. */
    private static boolean hasFreeCursor(Minecraft client) {
        return client.gui.screen() != null && !client.mouseHandler.isMouseGrabbed();
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
     *
     * <p>The panel keeps its left edge clear of the chat column, which is the only thing in that
     * corner a film must not cover, whenever the GUI is wide enough for both. A GUI narrower than
     * the chat column plus the panel cannot hold both, and there the panel stays on screen and
     * covers part of the chat: the keys are what a viewer watches the film for.
     */
    static int[] panelOrigin(
            int guiWidth, int guiHeight, int panelWidth, int panelHeight, int performanceTop, int chatColumn) {
        int x = Math.max(SCREEN_MARGIN, guiWidth - panelWidth - SCREEN_MARGIN);
        if (fitsBesideChat(guiWidth, panelWidth, chatColumn)) {
            x = Math.max(x, chatColumn + SCREEN_MARGIN);
        }
        int y = guiHeight - panelHeight - SCREEN_MARGIN - HOTBAR_HEIGHT;
        if (performanceTop != Integer.MIN_VALUE) {
            y = Math.max(SCREEN_MARGIN, Math.min(y, performanceTop - panelHeight - LINE_GAP));
        }
        return new int[] {x, y};
    }

    /** Whether the panel fits between the chat column and the GUI's right edge. */
    static boolean fitsBesideChat(int guiWidth, int panelWidth, int chatColumn) {
        return chatColumn + SCREEN_MARGIN + panelWidth + SCREEN_MARGIN <= guiWidth;
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

    /**
     * The chat column's right edge in GUI pixels, from the game's own options. The chat scales its pose by
     * {@code chatScale} and translates 4 pixels, then fills from {@code -4} to {@code maxWidth + 8}
     * ({@code ChatComponent.java:160-167}), where {@code maxWidth} is {@code ceil(getWidth(pct) / scale)}
     * and {@code getWidth} is {@code floor(pct * 280 + 40)} ({@code ChatComponent.java:416-419}).
     */
    static int chatColumnWidth(double chatWidthPct, double chatScale) {
        // The game divides in float (ChatComponent.java:147) and scales the pose before translating it 4
        // pixels (ChatComponent.java:160-162), so the right edge is two float steps, not one multiply.
        float scale = chatScale <= 0.0 ? 1.0F : (float) chatScale;
        int maxWidth = Mth.ceil(ChatComponent.getWidth(chatWidthPct) / scale);
        return Mth.ceil(scale * (maxWidth + 8) + scale * 4.0F);
    }

    /** The chat column's right edge for the client's own chat width and scale. */
    private static int chatColumnWidth(Minecraft client) {
        return chatColumnWidth(client.options.chatWidth().get(), client.options.chatScale().get());
    }

    /** The widest the typed line may be on a GUI {@code guiWidth} wide, so it keeps clear of chat. */
    static int maxContentWidth(int guiWidth, int chatColumnWidth) {
        return Math.max(0, guiWidth - SCREEN_MARGIN * 2 - chatColumnWidth);
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
