package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClientGameTestRecordingHud {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(MinecraftModConventions.MOD_ID, "gametest_recording_feedback");
    private static final boolean SHOWCASE_PROFILE =
            "showcase".equals(System.getenv(GameTestRecorderEnvironment.RECORDING_PROFILE_ENV));
    private static final int SCREEN_MARGIN = 5;
    private static final int PANEL_PADDING = 4;
    private static final int LINE_GAP = 2;
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

    public static void clear() {
        currentStep = null;
        synchronized (LOG_LOCK) {
            LOG_LINES.clear();
        }
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isEnabledForEnvironment()) {
            return;
        }

        List<HudLine> lines = lines();
        if (lines.isEmpty()) {
            return;
        }

        Font font = client.font;
        int width = 0;
        for (HudLine line : lines) {
            width = Math.max(width, Math.min(MAX_WIDTH, font.width(line.text())));
        }

        int lineHeight = font.lineHeight + LINE_GAP;
        int panelWidth = width + PANEL_PADDING * 2;
        int panelHeight = lines.size() * lineHeight + PANEL_PADDING * 2 - LINE_GAP;
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
    }

    private static List<HudLine> lines() {
        ArrayList<HudLine> lines = new ArrayList<>();
        lines.add(new HudLine(SHOWCASE_PROFILE ? "Feature showcase" : "GameTest Recording", HEADER_COLOR));

        Step step = currentStep;
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

    private record Step(String id, String title, String subtitle) {
    }

    private record HudLine(String text, int color) {
    }
}
