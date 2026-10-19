package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells the Gradle task that launched the client when the client has finished loading, the point at which its
 * GameTests start: on the first client tick after the game has finished loading, the file named by
 * {@value #READY_FILE_PROPERTY} is written. The task stops a client that has not got there by its startup deadline.
 */
public final class ClientStartupSignal {
    public static final String READY_FILE_PROPERTY = "minecraftmodconventions.clientReadyFile";
    private static boolean signalled;

    private ClientStartupSignal() {
    }

    public static void initialize() {
        String readyFile = System.getProperty(READY_FILE_PROPERTY);
        if (readyFile == null || readyFile.isBlank()) {
            return;
        }
        Path file = Path.of(readyFile);
        ClientTickEvents.END_CLIENT_TICK.register(client -> signalOnceLoaded(client, file));
    }

    private static void signalOnceLoaded(Minecraft client, Path file) {
        if (signalled || !client.isGameLoadFinished()) {
            return;
        }
        signalled = true;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            MinecraftModConventions.LOGGER.error("Could not write the client startup signal {}", file, exception);
        }
    }
}
