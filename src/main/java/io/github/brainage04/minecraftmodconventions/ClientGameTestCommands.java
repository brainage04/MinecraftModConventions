package io.github.brainage04.minecraftmodconventions;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

/**
 * Runs commands as the client player and reads what the mod told them, for the client GameTests of
 * mods with commands.
 *
 * <p>A command is sent the way the client sends a typed one, so the server applies its permissions
 * and the reply arrives as a chat message; {@link #awaitChat} then waits for that reply. The chat
 * lines come from the chat component's own message list, which has no accessor.
 */
public final class ClientGameTestCommands {
    /** Ticks {@link #awaitChat} waits for a server reply by default. */
    public static final int DEFAULT_CHAT_TIMEOUT_TICKS = 200;
    private static final String ALL_MESSAGES_FIELD = "allMessages";

    private ClientGameTestCommands() {
    }

    /** Fails unless the connected client's command tree has every one of these root commands. */
    public static void assertCommandTree(ClientGameTestContext context, String... commands) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(commands, "commands");
        List<String> expected = List.of(commands);
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("At least one command name is required");
        }
        context.computeOnClient(client -> {
            if (client.getConnection() == null) {
                throw new AssertionError("Expected a connected client.");
            }
            var root = client.getConnection().getCommands().getRoot();
            for (String command : expected) {
                if (root.getChild(commandName(command)) == null) {
                    throw new AssertionError("Expected /" + command + " in the client command tree.");
                }
            }
            return null;
        });
    }

    /** Sends a command as the client player; the caller waits for its effect. */
    public static void runCommand(ClientGameTestContext context, String command) {
        Objects.requireNonNull(context, "context");
        String line = commandWithoutSlash(command);
        context.runOnClient(client -> {
            if (client.getConnection() == null) {
                throw new AssertionError("Expected a connected client to run /" + line + '.');
            }
            client.getConnection().sendCommand(line);
        });
    }

    /**
     * Opens the chat screen, types a command whole and submits it, the way a player does. A command
     * is typed whole rather than character by character, because the partial command shows a parse
     * error above it and that error would be filmed.
     */
    public static void typeCommand(ClientGameTestContext context, String command, int visibleTicks) {
        Objects.requireNonNull(context, "context");
        if (visibleTicks < 0) {
            throw new IllegalArgumentException("visibleTicks must not be negative: " + visibleTicks);
        }
        String line = "/" + commandWithoutSlash(command);
        context.setScreen(() -> new ChatScreen("", false));
        context.getInput().typeChars(line);
        context.waitTicks(visibleTicks);
        context.getInput().pressKey(InputConstants.KEY_RETURN);
    }

    /** Clears the chat panel, so the next stage's messages are the only ones on screen. */
    public static void clearChat(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
    }

    /** The text of every message the chat panel currently holds, oldest first. */
    public static List<String> chatLines(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        return context.computeOnClient(client -> {
            try {
                Field field = ChatComponent.class.getDeclaredField(ALL_MESSAGES_FIELD);
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<GuiMessage> messages = (List<GuiMessage>) field.get(client.gui.hud.getChat());
                return messages.stream().map(message -> message.content().getString()).toList();
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot read the client's chat messages", exception);
            }
        });
    }

    /** Waits until each of these texts appears in some chat message, then fails naming what is there. */
    public static void awaitChat(ClientGameTestContext context, String... expected) {
        awaitChat(context, DEFAULT_CHAT_TIMEOUT_TICKS, expected);
    }

    /** Waits up to {@code timeoutTicks} for each of these texts to appear in some chat message. */
    public static void awaitChat(ClientGameTestContext context, int timeoutTicks, String... expected) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expected, "expected");
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive: " + timeoutTicks);
        }
        List<String> wanted = List.of(expected);
        for (int tick = 0; tick < timeoutTicks; tick++) {
            List<String> messages = chatLines(context);
            if (containsAll(messages, wanted)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Missing chat " + wanted + "; received " + chatLines(context));
    }

    /**
     * Clicks a chat line's run-command link, such as the {@code [ACCEPT]} of a teleport request.
     * The click is a real mouse click at the link's centre. {@code settleTicks} is how long the
     * cursor rests over the link before the click, which a film uses to show the hover.
     */
    public static void clickChatCommand(ClientGameTestContext context, String command, int settleTicks) {
        Objects.requireNonNull(context, "context");
        if (settleTicks < 0) {
            throw new IllegalArgumentException("settleTicks must not be negative: " + settleTicks);
        }
        double[] point = chatCommandScreenPoint(context, command);
        context.getInput().setCursorPos(point[0], point[1]);
        context.waitTicks(settleTicks);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }

    /** The window position, in pixels, of the centre of the chat text that runs this command. */
    public static double[] chatCommandScreenPoint(ClientGameTestContext context, String command) {
        Objects.requireNonNull(context, "context");
        String line = commandWithoutSlash(command);
        return context.computeOnClient(client -> clickableCommandPoint(client, line));
    }

    static double[] clickableCommandPoint(Minecraft client, String command) {
        Window window = client.getWindow();
        ChatComponent chat = client.gui.hud.getChat();
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();
        long sumX = 0;
        long sumY = 0;
        int hits = 0;
        for (int y = guiHeight / 2; y < guiHeight; y++) {
            for (int x = 0; x < guiWidth; x++) {
                ActiveTextCollector.ClickableStyleFinder finder =
                        new ActiveTextCollector.ClickableStyleFinder(client.font, x, y);
                chat.captureClickableText(
                        finder, guiHeight, client.gui.hud.getGuiTicks(), ChatComponent.DisplayMode.FOREGROUND);
                Style style = finder.result();
                if (style != null
                        && style.getClickEvent() instanceof ClickEvent.RunCommand(String run)
                        && trimmedCommand(run).equals(command)) {
                    sumX += x;
                    sumY += y;
                    hits++;
                }
            }
        }
        if (hits == 0) {
            throw new AssertionError("No chat text runs " + command);
        }
        return screenPoint(
                sumX / (double) hits,
                sumY / (double) hits,
                guiWidth,
                guiHeight,
                window.getScreenWidth(),
                window.getScreenHeight());
    }

    static double[] screenPoint(
            double guiX, double guiY, int guiWidth, int guiHeight, int screenWidth, int screenHeight) {
        if (guiWidth <= 0 || guiHeight <= 0) {
            throw new IllegalArgumentException("The GUI is empty: " + guiWidth + "x" + guiHeight);
        }
        return new double[] {
            (guiX + 0.5D) * screenWidth / guiWidth, (guiY + 0.5D) * screenHeight / guiHeight
        };
    }

    static boolean containsAll(List<String> messages, List<String> expected) {
        return expected.stream().allMatch(text -> messages.stream().anyMatch(line -> line.contains(text)));
    }

    static String commandWithoutSlash(String command) {
        String line = trimmedCommand(command);
        if (line.isEmpty()) {
            throw new IllegalArgumentException("An empty command cannot be run");
        }
        return line;
    }

    /** A command without its slash and surrounding space, which may be empty in a chat link. */
    static String trimmedCommand(String command) {
        Objects.requireNonNull(command, "command");
        String line = command.strip();
        return line.startsWith("/") ? line.substring(1).strip() : line;
    }

    static String commandName(String command) {
        String line = commandWithoutSlash(command);
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }
}
