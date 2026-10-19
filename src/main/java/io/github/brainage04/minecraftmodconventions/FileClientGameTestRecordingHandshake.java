package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class FileClientGameTestRecordingHandshake implements ClientGameTestRecordingHandshake {
    public static final int DEFAULT_READY_TIMEOUT_TICKS = 200;

    private final Path startSignal;
    private final Path readySignal;
    private final Path stopSignal;
    private final int readyTimeoutTicks;

    private FileClientGameTestRecordingHandshake(
            Path startSignal, Path readySignal, Path stopSignal, int readyTimeoutTicks) {
        if (readyTimeoutTicks <= 0) {
            throw new IllegalArgumentException("readyTimeoutTicks must be positive");
        }
        this.startSignal = startSignal;
        this.readySignal = readySignal;
        this.stopSignal = stopSignal;
        this.readyTimeoutTicks = readyTimeoutTicks;
    }

    public static ClientGameTestRecordingHandshake fromEnvironment() {
        return fromEnvironment(
                GameTestRecorderEnvironment.START_SIGNAL_ENV,
                GameTestRecorderEnvironment.READY_SIGNAL_ENV,
                GameTestRecorderEnvironment.STOP_SIGNAL_ENV);
    }

    public static ClientGameTestRecordingHandshake fromEnvironment(
            String startSignalEnv, String readySignalEnv) {
        return fromEnvironment(startSignalEnv, readySignalEnv, null);
    }

    public static ClientGameTestRecordingHandshake fromEnvironment(
            String startSignalEnv, String readySignalEnv, String stopSignalEnv) {
        Objects.requireNonNull(startSignalEnv, "startSignalEnv");
        Objects.requireNonNull(readySignalEnv, "readySignalEnv");
        String startSignal = System.getenv(startSignalEnv);
        if (startSignal == null || startSignal.isBlank()) {
            return disabled();
        }
        String readySignal = System.getenv(readySignalEnv);
        String stopSignal = stopSignalEnv == null ? null : System.getenv(stopSignalEnv);
        return new FileClientGameTestRecordingHandshake(
                Path.of(startSignal),
                readySignal == null || readySignal.isBlank() ? null : Path.of(readySignal),
                stopSignal == null || stopSignal.isBlank() ? null : Path.of(stopSignal),
                DEFAULT_READY_TIMEOUT_TICKS
        );
    }

    public static ClientGameTestRecordingHandshake disabled() {
        return DisabledHandshake.INSTANCE;
    }

    public static FileClientGameTestRecordingHandshake of(Path startSignal, Path readySignal) {
        return of(startSignal, readySignal, null, DEFAULT_READY_TIMEOUT_TICKS);
    }

    public static FileClientGameTestRecordingHandshake of(
            Path startSignal, Path readySignal, int readyTimeoutTicks) {
        return of(startSignal, readySignal, null, readyTimeoutTicks);
    }

    public static FileClientGameTestRecordingHandshake of(
            Path startSignal, Path readySignal, Path stopSignal) {
        return of(startSignal, readySignal, stopSignal, DEFAULT_READY_TIMEOUT_TICKS);
    }

    public static FileClientGameTestRecordingHandshake of(
            Path startSignal, Path readySignal, Path stopSignal, int readyTimeoutTicks) {
        return new FileClientGameTestRecordingHandshake(
                Objects.requireNonNull(startSignal, "startSignal"),
                readySignal,
                stopSignal,
                readyTimeoutTicks
        );
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public Path startSignal() {
        return startSignal;
    }

    public Optional<Path> readySignal() {
        return Optional.ofNullable(readySignal);
    }

    public Optional<Path> stopSignal() {
        return Optional.ofNullable(stopSignal);
    }

    public int readyTimeoutTicks() {
        return readyTimeoutTicks;
    }

    @Override
    public void signalClientReady() {
        writeSignal(startSignal);
    }

    @Override
    public void awaitRecorderReady(ClientGameTestContext context) {
        Objects.requireNonNull(context, "context");
        signalClientReady();
        if (readySignal != null) {
            context.waitFor(_ -> Files.exists(readySignal), readyTimeoutTicks);
        }
    }

    @Override
    public void signalRecordingStopped() {
        if (stopSignal != null) {
            writeSignal(stopSignal);
        }
    }

    private static void writeSignal(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Long.toString(System.currentTimeMillis()));
        } catch (IOException exception) {
            throw new AssertionError("Expected to write client GameTest recording signal: " + path, exception);
        }
    }

    private enum DisabledHandshake implements ClientGameTestRecordingHandshake {
        INSTANCE;

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void signalClientReady() {
        }

        @Override
        public void awaitRecorderReady(ClientGameTestContext context) {
            Objects.requireNonNull(context, "context");
        }

        @Override
        public void signalRecordingStopped() {
        }
    }
}
