package io.github.brainage04.minecraftmodconventions;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;

/** Loader-independent recording protocol. Tick and presentation callbacks come from Minecraft, not tests. */
public final class ClientGameTestRecordingSession {
    private static final String PREFIX = "CLIENT_GAMETEST_RECORDING_";
    private static final boolean ENABLED = path("START_SIGNAL") != null;
    private static boolean armed;
    private static boolean ready;
    private static boolean finished;
    private static long firstTickUs;
    private static long lastTickStartUs;
    private static long lastTickEndUs;
    private static long firstPresentBeforeUs;
    private static long firstPresentAfterUs;
    private static long lastPresentBeforeUs;
    private static long lastPresentAfterUs;
    private static long tickCount;
    private static long presentedFrames;
    private static long worldExitUs;
    private static long clockEpochUs;
    private static long clockNano;

    private ClientGameTestRecordingSession() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static synchronized boolean isStarted() {
        return armed;
    }

    /** Call outside a world. The waiter must keep the client rendering while capture becomes ready. */
    public static void start(Runnable waitForClient) {
        if (!ENABLED) {
            return;
        }
        synchronized (ClientGameTestRecordingSession.class) {
            if (ready && !finished) {
                return;
            }
            if (armed) {
                throw new IllegalStateException("A recording session is already starting or finished.");
            }
            armed = true;
            clockNano = System.nanoTime();
            clockEpochUs = epochMicros();
        }
        write(path("START_SIGNAL"), Long.toString(epochMicros()));
        await("READY_SIGNAL", waitForClient);
        synchronized (ClientGameTestRecordingSession.class) {
            ready = true;
        }
        System.out.println("[CLIENT_GAMETEST_BOUNDARY] CAPTURE_READY before world entry");
    }

    public static synchronized void beforeWorldTick() {
        if (!ENABLED) {
            return;
        }
        if (!ready || finished) {
            throw new IllegalStateException("World tick outside the acknowledged recording lifecycle.");
        }
        lastTickStartUs = epochMicros();
        if (firstTickUs == 0) {
            firstTickUs = lastTickStartUs;
            System.out.println("[CLIENT_GAMETEST_BOUNDARY] FIRST_WORLD_TICK epochUs=" + firstTickUs);
        }
        tickCount++;
    }

    public static synchronized void afterWorldTick() {
        if (ENABLED && ready && !finished) {
            lastTickEndUs = epochMicros();
        }
    }

    public static synchronized void beforePresent(boolean worldLoaded) {
        if (ENABLED && ready && !finished && worldLoaded) {
            lastPresentBeforeUs = epochMicros();
            if (firstPresentBeforeUs == 0) {
                firstPresentBeforeUs = lastPresentBeforeUs;
            }
        }
    }

    public static synchronized void afterPresent(boolean worldLoaded) {
        if (ENABLED && ready && !finished && worldLoaded) {
            lastPresentAfterUs = epochMicros();
            if (firstPresentAfterUs == 0) {
                firstPresentAfterUs = lastPresentAfterUs;
                System.out.println("[CLIENT_GAMETEST_BOUNDARY] FIRST_WORLD_PRESENT epochUs=" + firstPresentAfterUs);
            }
            presentedFrames++;
        }
    }

    public static synchronized void worldCleared() {
        if (ENABLED && ready && !finished && firstTickUs != 0) {
            worldExitUs = epochMicros();
            System.out.println("[CLIENT_GAMETEST_BOUNDARY] WORLD_CLEARED epochUs=" + worldExitUs
                    + " lastTickStartUs=" + lastTickStartUs + " lastTickEndUs=" + lastTickEndUs
                    + " lastPresentAfterUs=" + lastPresentAfterUs);
        }
    }

    /** Call after actual disconnection, keeping the client alive until the capture file is finalized. */
    public static void finish(Runnable waitForClient) {
        if (!ENABLED) {
            return;
        }
        Properties evidence = new Properties();
        synchronized (ClientGameTestRecordingSession.class) {
            if (finished) {
                return;
            }
            if (!ready || firstTickUs == 0 || lastTickEndUs == 0 || worldExitUs == 0 || presentedFrames == 0) {
                throw new IllegalStateException("Recording must contain world ticks, presentations and an observed world clear before completion.");
            }
            finished = true;
            evidence.setProperty("schemaVersion", "1");
            evidence.setProperty("clock", "unix-epoch-microseconds");
            evidence.setProperty("tickDefinition", "ClientLevel.tick invocation start/completion on the Minecraft client thread");
            evidence.setProperty("presentationDefinition", "CPU timestamps bracketing GpuSurface.present while Minecraft.level is non-null; may include loading/GUI frames, not proof of visible world pixels or GPU completion");
            evidence.setProperty("firstWorldTickStartUs", Long.toString(firstTickUs));
            evidence.setProperty("lastWorldTickStartUs", Long.toString(lastTickStartUs));
            evidence.setProperty("lastWorldTickEndUs", Long.toString(lastTickEndUs));
            evidence.setProperty("firstWorldPresentBeforeUs", Long.toString(firstPresentBeforeUs));
            evidence.setProperty("firstWorldPresentAfterUs", Long.toString(firstPresentAfterUs));
            evidence.setProperty("lastWorldPresentBeforeUs", Long.toString(lastPresentBeforeUs));
            evidence.setProperty("lastWorldPresentAfterUs", Long.toString(lastPresentAfterUs));
            evidence.setProperty("worldClearedUs", Long.toString(worldExitUs));
            evidence.setProperty("worldTickCount", Long.toString(tickCount));
            evidence.setProperty("worldPresentedFrameCount", Long.toString(presentedFrames));
            evidence.setProperty("clockDriftUs", Long.toString(epochMicros() - clockEpochUs
                    - (System.nanoTime() - clockNano) / 1000L));
        }
        try {
            StringWriter text = new StringWriter();
            evidence.store(text, "Actual Minecraft world tick and GPU presentation boundaries");
            write(path("BOUNDARIES"), text.toString());
        } catch (IOException exception) {
            throw new AssertionError("Could not serialize recording boundaries", exception);
        }
        write(path("STOP_SIGNAL"), Long.toString(epochMicros()));
        await("COMPLETE_SIGNAL", waitForClient);
        System.out.println("[CLIENT_GAMETEST_BOUNDARY] CAPTURE_COMPLETE acknowledged; client may close");
    }

    private static void await(String signal, Runnable waitForClient) {
        Path file = path(signal);
        if (file == null) {
            throw new IllegalStateException("Missing recording protocol path: " + PREFIX + signal);
        }
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!Files.isRegularFile(file)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for " + signal);
            }
            waitForClient.run();
        }
    }

    private static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000L;
    }

    private static Path path(String suffix) {
        String value = System.getenv(PREFIX + suffix);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static void write(Path path, String value) {
        if (path == null) {
            throw new IllegalStateException("Missing recording signal path");
        }
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, value);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new AssertionError("Could not write recording signal " + path, exception);
        }
    }
}
