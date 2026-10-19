package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public interface ClientGameTestRecordingHandshake {
    boolean isEnabled();

    void signalClientReady();

    void awaitRecorderReady(ClientGameTestContext context);

    default void signalRecordingStopped() {
    }
}
