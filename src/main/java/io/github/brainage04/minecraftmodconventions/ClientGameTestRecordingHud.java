package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ClientGameTestRecordingHud {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(MinecraftModConventions.MOD_ID, "gametest_recording_feedback");
    private static final boolean SHOWCASE_PROFILE =
            "showcase".equals(System.getenv(GameTestRecorderEnvironment.RECORDING_PROFILE_ENV));
    private static final int SCREEN_MARGIN = 5;
    private static final int PANEL_PADDING = 4;
    private static final int LINE_GAP = 2;
    /**
     * The height of the chat screen's input box above the bottom of the GUI: {@code ChatScreen.java:275} in the 26.3
     * sources fills it from {@code height - 14}, and vanilla exposes no constant for it.
     */
    static final int CHAT_INPUT_HEIGHT = 14;
    /** Minecraft's default font line height, which the panel's height is computed from. */
    static final int FONT_LINE_HEIGHT = 9;
    private static final int MAX_WIDTH = 360;
    private static final int MAX_LOG_LINES = 6;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int HEADER_COLOR = 0xFF55FFFF;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFF90EE90;
    private static final int LOG_COLOR = 0xFFFFD54F;
    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>(MAX_LOG_LINES);
    private static volatile Step currentStep;
    /** The step whose caption the last HUD extraction drew, on the render thread only; see {@link #takeDrawnStep}. */
    private static Step drawnStep;
    private static volatile PerformanceOverlayPlacement overlayPlacement = PerformanceOverlayPlacement.TOP_RIGHT;
    private static boolean registered;

    private ClientGameTestRecordingHud() {
    }

    public static void initialize() {
        if (registered || !isEnabledForEnvironment()) {
            return;
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SCOREBOARD, HUD_ID, ClientGameTestRecordingHud::render);
        registered = true;
    }

    public static boolean isInitialized() {
        return registered;
    }

    public static boolean isEnabledForEnvironment() {
        return GameTestRecorderEnvironment.isEnabled();
    }

    public static void showStep(String id, String title, String subtitle) {
        CaptionWording.requireDescribesTheMod(title);
        CaptionWording.requireDescribesTheMod(subtitle);
        if (!isEnabledForEnvironment()) {
            return;
        }

        Step step = new Step(clean(id), clean(title), clean(subtitle));
        currentStep = step;
        log(step.id().isBlank() ? step.title() : step.id() + " - " + step.title());
    }

    public static void log(String message) {
        if (!isEnabledForEnvironment()) {
            return;
        }

        String cleaned = clean(message);
        if (cleaned.isBlank()) {
            return;
        }

        synchronized (LOG_LOCK) {
            while (LOG_LINES.size() >= MAX_LOG_LINES) {
                LOG_LINES.removeFirst();
            }
            LOG_LINES.addLast(cleaned);
        }
    }

    /** Puts the performance overlay at {@code placement} until the next call or the next recording. */
    public static void placePerformanceOverlay(PerformanceOverlayPlacement placement) {
        overlayPlacement = Objects.requireNonNull(placement, "placement");
    }

    static PerformanceOverlayPlacement performanceOverlayPlacement() {
        return overlayPlacement;
    }

    public static void clear() {
        currentStep = null;
        overlayPlacement = PerformanceOverlayPlacement.TOP_RIGHT;
        synchronized (LOG_LOCK) {
            LOG_LINES.clear();
        }
    }

    /**
     * The step whose caption is in the frame about to be presented, or null when no caption panel was drawn into it,
     * and forgets it. {@code Minecraft.renderFrame} extracts the HUD, this panel included, in
     * {@code GameRenderer.extract}, draws it into the main target in {@code GameRenderer.render} and then presents
     * ({@code Minecraft.java:1282,1289,1317}, {@code GameRenderer.java:452,507} in the 26.3 sources), so read right
     * before the present this names the caption the frame shows.
     */
    static Step takeDrawnStep() {
        Step step = drawnStep;
        drawnStep = null;
        return step;
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isEnabledForEnvironment()) {
            return;
        }

        Step step = currentStep;
        List<HudLine> lines = lines(step);
        if (lines.isEmpty()) {
            return;
        }

        Font font = client.font;
        int width = 0;
        for (HudLine line : lines) {
            width = Math.max(width, Math.min(MAX_WIDTH, font.width(line.text())));
        }

        int lineHeight = FONT_LINE_HEIGHT + LINE_GAP;
        int panelWidth = panelWidth(width);
        int panelHeight = panelHeight(lines.size());
        int panelX = client.getWindow().getGuiScaledWidth() - panelWidth - SCREEN_MARGIN;
        int panelY = SCREEN_MARGIN;
        graphics.fill(
                panelX, panelY, panelX + panelWidth, panelY + panelHeight, BACKGROUND_COLOR);

        int textY = panelY + PANEL_PADDING;
        for (HudLine line : lines) {
            graphics.text(
                    font,
                    fit(font, line.text(), MAX_WIDTH),
                    panelX + PANEL_PADDING,
                    textY,
                    line.color(),
                    true);
            textY += lineHeight;
        }
        drawnStep = step;
        renderPerformance(graphics, font, client, SCREEN_MARGIN + panelHeight(captionLinesAtMost(SHOWCASE_PROFILE)));
    }

    /**
     * The most lines the caption panel holds: a title card has a header, a title and a subtitle, and a diagnostic
     * panel adds the scenario id and its log lines. The overlay sits under that height, so it does not move when a
     * caption has fewer lines.
     */
    static int captionLinesAtMost(boolean showcase) {
        return showcase ? 3 : 4 + MAX_LOG_LINES;
    }

    private static void renderPerformance(GuiGraphicsExtractor graphics, Font font, Minecraft client, int captionBottom) {
        boolean recording = ClientGameTestRecordingSession.isEnabled();
        List<String> lines = recording ? RecordingPerformance.sample(client) : List.of();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int panelWidth = panelWidth(width);
        int panelHeight = panelHeight(lines.size());
        int[] position = performancePanel(recording, overlayPlacement, client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(), captionBottom, panelWidth, panelHeight,
                client.gui.screen() instanceof ChatScreen);
        if (position == null) {
            return;
        }
        int panelX = position[0];
        int panelY = position[1];
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BACKGROUND_COLOR);
        int textY = panelY + PANEL_PADDING;
        for (String line : lines) {
            graphics.text(font, line, panelX + PANEL_PADDING, textY, LABEL_COLOR, true);
            textY += FONT_LINE_HEIGHT + LINE_GAP;
        }
    }

    /**
     * The performance overlay's top-left corner, or null when it is not drawn: outside a recording, or hidden.
     * {@link PerformanceOverlayPlacement#TOP_RIGHT} sits right under the caption panel at its tallest, whose bottom
     * edge is {@code captionBottom}. A bottom placement rises above the chat screen's input box while chat is open.
     */
    static int[] performancePanel(boolean recording, PerformanceOverlayPlacement placement, int guiWidth,
            int guiHeight, int captionBottom, int panelWidth, int panelHeight, boolean chatOpen) {
        if (!recording) {
            return null;
        }
        int left = SCREEN_MARGIN;
        int right = guiWidth - panelWidth - SCREEN_MARGIN;
        int bottom = guiHeight - panelHeight - SCREEN_MARGIN - (chatOpen ? CHAT_INPUT_HEIGHT : 0);
        return switch (placement) {
            case TOP_RIGHT -> new int[] {right, captionBottom + LINE_GAP};
            case TOP_LEFT -> new int[] {left, SCREEN_MARGIN};
            case BOTTOM_LEFT -> new int[] {left, bottom};
            case BOTTOM_RIGHT -> new int[] {right, bottom};
            case HIDDEN -> null;
        };
    }

    private static List<HudLine> lines(Step step) {
        ArrayList<HudLine> lines = new ArrayList<>();
        lines.add(new HudLine(SHOWCASE_PROFILE ? "Feature showcase" : "GameTest Recording", HEADER_COLOR));

        if (step != null) {
            if (!SHOWCASE_PROFILE && !step.id().isBlank()) {
                lines.add(new HudLine("Scenario: " + step.id(), LABEL_COLOR));
            }
            if (!step.title().isBlank()) {
                lines.add(new HudLine(step.title(), TITLE_COLOR));
            }
            if (!step.subtitle().isBlank()) {
                lines.add(new HudLine(step.subtitle(), SUBTITLE_COLOR));
            }
        }

        if (SHOWCASE_PROFILE) {
            return lines;
        }

        List<String> logs;
        synchronized (LOG_LOCK) {
            logs = List.copyOf(LOG_LINES);
        }
        for (String log : logs) {
            lines.add(new HudLine("› " + log, LOG_COLOR));
        }

        return lines;
    }

    /**
     * Whether the caption panel fits a GUI of this size. The overlay standard is 1920x1080 at GUI scale 3,
     * a 640x360 GUI; a test run uses 854x480 at scale 2, a 427x240 GUI. The widest text the panel draws is
     * {@link #MAX_WIDTH} pixels and a diagnostic step fills {@link #MAX_LOG_LINES} log lines, so this is
     * the worst case the panel has to fit.
     */
    static boolean fits(int guiWidth, int guiHeight, int textWidth, int lineCount) {
        return panelWidth(textWidth) + SCREEN_MARGIN <= guiWidth
                && panelHeight(lineCount) + SCREEN_MARGIN <= guiHeight;
    }

    static int panelWidth(int textWidth) {
        return Math.min(MAX_WIDTH, textWidth) + PANEL_PADDING * 2;
    }

    static int panelHeight(int lineCount) {
        int lineHeight = FONT_LINE_HEIGHT + LINE_GAP;
        return lineCount * lineHeight + PANEL_PADDING * 2 - LINE_GAP;
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        String suffix = "…";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end == 0 ? suffix : text.substring(0, end).stripTrailing() + suffix;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip().replace('\n', ' ');
    }

    public static String enumLabel(Enum<?> value) {
        if (value == null) {
            return "";
        }
        String[] parts = value.name().toLowerCase(Locale.ROOT).split("_");
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].isEmpty()) {
                parts[index] = Character.toUpperCase(parts[index].charAt(0)) + parts[index].substring(1);
            }
        }
        return String.join(" ", parts);
    }

    record Step(String id, String title, String subtitle) {
    }

    private record HudLine(String text, int color) {
    }
}
