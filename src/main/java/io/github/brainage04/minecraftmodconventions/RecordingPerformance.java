package io.github.brainage04.minecraftmodconventions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * The numbers the recording's performance overlay shows and its metadata keeps. The server is the
 * singleplayer one, or the dedicated server {@link ClientGameTestServers} runs in the same JVM; a client on
 * any other server has no server numbers to read.
 */
public final class RecordingPerformance {
    private static volatile MinecraftServer dedicatedServer;

    private RecordingPerformance() {
    }

    /** The dedicated server whose ticks the overlay reports while the client plays on it. */
    public static void trackServer(MinecraftServer server) {
        dedicatedServer = server;
    }

    public static void untrackServer() {
        dedicatedServer = null;
    }

    /** Samples the current frame for the metadata and returns the overlay's lines. */
    static List<String> sample(Minecraft client) {
        IntegratedServer singleplayer = client.getSingleplayerServer();
        MinecraftServer server = singleplayer != null ? singleplayer : dedicatedServer;
        double mspt = server == null ? Double.NaN : server.getAverageTickTimeNanos() / 1_000_000.0;
        double tickRate = server == null ? Double.NaN : server.tickRateManager().tickrate();
        double frameTimeMs = client.getFrameTimeNs() / 1_000_000.0;
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        ClientGameTestRecordingSession.recordPerformance(
                client.getFps(), frameTimeMs, mspt, tickRate, used, runtime.maxMemory());
        return RecordingPerformanceStats.overlayLines(
                client.getFps(), frameTimeMs, mspt, tickRate, used, runtime.maxMemory());
    }
}
