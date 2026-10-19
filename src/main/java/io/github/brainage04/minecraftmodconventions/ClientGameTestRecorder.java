package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.Objects;

public final class ClientGameTestRecorder {

    private ClientGameTestRecorder() {
    }

    public static void startRecording(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(client -> {
            if (ClientGameTestRecordingSession.isEnabled()
                    && client.level != null && !ClientGameTestRecordingSession.isStarted()) {
                throw new IllegalStateException("Start recording before connecting to a world.");
            }
            ClientGameTestRecordingHud.clear();
        });
        ClientGameTestRecordingSession.start(context::waitTick);
    }

    public static void signalReadyToRecord(ClientGameTestContext context) {
        startRecording(context);
    }

    public static void signalReadyToRecord(ClientGameTestContext context, String startSignalEnv, String readySignalEnv) {
        signalReadyToRecord(context, FileClientGameTestRecordingHandshake.fromEnvironment(startSignalEnv, readySignalEnv));
    }

    public static void signalReadyToRecord(ClientGameTestContext context, ClientGameTestRecordingHandshake handshake) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(handshake, "handshake");
        handshake.awaitRecorderReady(context);
    }

    public static void stopRecording(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(client -> {
            if (ClientGameTestRecordingSession.isEnabled() && client.level != null) {
                throw new IllegalStateException("Stop recording after disconnecting from the world.");
            }
        });
        ClientGameTestRecordingSession.finish(context::waitTick);
    }

    public static void showStep(ClientGameTestContext context, String id, String title, String subtitle) {
        Objects.requireNonNull(context, "context");
        String message = "[CLIENT_GAMETEST_RECORDER] " + clean(id) + " | " + clean(title)
                + (clean(subtitle).isBlank() ? "" : " | " + clean(subtitle));
        System.out.println(message);
        context.runOnClient(_ -> ClientGameTestRecordingHud.showStep(id, title, subtitle));
    }

    public static void log(ClientGameTestContext context, String message) {
        Objects.requireNonNull(context, "context");
        context.runOnClient(_ -> ClientGameTestRecordingHud.log(message));
    }


    private static String clean(String value) {
        return value == null ? "" : value.strip().replace('\n', ' ');
    }
}
