package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileClientGameTestRecordingHandshakeTest {
    @TempDir
    Path tempDir;

    @Test
    void disabledHandshakeDoesNotInteractWithClientContext() {
        ClientGameTestRecordingHandshake handshake = FileClientGameTestRecordingHandshake.disabled();

        assertFalse(handshake.isEnabled());
        handshake.signalClientReady();
        handshake.signalRecordingStopped();
        handshake.awaitRecorderReady(contextThatFailsOnInteraction());
    }

    @Test
    void signalClientReadyCreatesParentDirectoriesAndWritesTimestampSignal() throws Exception {
        Path startSignal = tempDir.resolve("signals/client/ready/start.signal");
        FileClientGameTestRecordingHandshake handshake = FileClientGameTestRecordingHandshake.of(startSignal, null);

        long beforeSignal = System.currentTimeMillis();
        handshake.signalClientReady();
        long afterSignal = System.currentTimeMillis();

        assertTrue(Files.isRegularFile(startSignal));
        long timestamp = assertDoesNotThrow(() -> Long.parseLong(Files.readString(startSignal)));
        assertTrue(
                timestamp >= beforeSignal && timestamp <= afterSignal,
                () -> "Expected signal timestamp between " + beforeSignal + " and " + afterSignal + " but was " + timestamp
        );
    }

    @Test
    void signalRecordingStoppedWritesTimestampSignal() throws Exception {
        Path startSignal = tempDir.resolve("start.signal");
        Path stopSignal = tempDir.resolve("signals/client/stop.signal");
        FileClientGameTestRecordingHandshake handshake =
                FileClientGameTestRecordingHandshake.of(startSignal, null, stopSignal);

        long beforeSignal = System.currentTimeMillis();
        handshake.signalRecordingStopped();
        long afterSignal = System.currentTimeMillis();

        assertTrue(Files.isRegularFile(stopSignal));
        long timestamp = assertDoesNotThrow(() -> Long.parseLong(Files.readString(stopSignal)));
        assertTrue(timestamp >= beforeSignal && timestamp <= afterSignal);
    }

    @Test
    void factoryRejectsNonPositiveReadyTimeout() {
        Path startSignal = tempDir.resolve("start.signal");

        IllegalArgumentException zeroTimeout = assertThrows(
                IllegalArgumentException.class,
                () -> FileClientGameTestRecordingHandshake.of(startSignal, null, 0)
        );
        IllegalArgumentException negativeTimeout = assertThrows(
                IllegalArgumentException.class,
                () -> FileClientGameTestRecordingHandshake.of(startSignal, null, -1)
        );

        assertEquals("readyTimeoutTicks must be positive", zeroTimeout.getMessage());
        assertEquals("readyTimeoutTicks must be positive", negativeTimeout.getMessage());
    }

    private static ClientGameTestContext contextThatFailsOnInteraction() {
        return (ClientGameTestContext) Proxy.newProxyInstance(
                ClientGameTestContext.class.getClassLoader(),
                new Class<?>[]{ClientGameTestContext.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "ClientGameTestContext that fails on interaction";
                            default -> throw new AssertionError("Unexpected Object method: " + method.getName());
                        };
                    }
                    throw new AssertionError("Disabled handshake should not call ClientGameTestContext." + method.getName());
                }
        );
    }
}
