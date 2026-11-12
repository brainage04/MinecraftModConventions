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
    /** The tick window of the server sampled last, or null once the client left that server's world. */
    private static volatile TrackedWindow tracked;

    private RecordingPerformance() {
    }

    /** The dedicated server whose ticks the overlay reports while the client plays on it. */
    public static void trackServer(MinecraftServer server) {
        dedicatedServer = server;
    }

    public static void untrackServer() {
        dedicatedServer = null;
        forgetServer();
    }

    /** Lets go of the last sampled server, so a stopped server is not kept reachable; call when the world is left. */
    public static void forgetServer() {
        tracked = null;
    }

    /** Samples the current frame for the metadata and returns the overlay's lines. Call on the client thread. */
    static List<String> sample(Minecraft client) {
        IntegratedServer singleplayer = client.getSingleplayerServer();
        MinecraftServer server = singleplayer != null ? singleplayer : dedicatedServer;
        long now = System.nanoTime();
        // The singleplayer server stops counting ticks while a pause screen is open (IntegratedServer.java:130-140
        // in the 26.3 client sources); that is not a stall.
        boolean paused = singleplayer != null && singleplayer.isPaused();
        ServerTickWindow.Reading ticks = null;
        if (paused) {
            windowFor(server).paused(now);
        } else if (server != null) {
            ticks = windowFor(server).sample(now, server.getTickCount(), server.getTickTimesNanos(),
                    server.tickRateManager().tickrate());
        }
        double frameTimeMs = client.getFrameTimeNs() / 1_000_000.0;
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        ClientGameTestRecordingSession.recordPerformance(client.getFps(), frameTimeMs, ticks, used, runtime.maxMemory());
        return RecordingPerformanceStats.overlayLines(
                client.getFps(), frameTimeMs, ticks, paused, used, runtime.maxMemory());
    }

    /** The tick window of {@code server}, a fresh one when the last sampled server was another one or none. */
    static ServerTickWindow windowFor(Object server) {
        TrackedWindow current = tracked;
        if (current == null || current.server() != server) {
            current = new TrackedWindow(server, new ServerTickWindow());
            tracked = current;
        }
        return current.window();
    }

    static boolean holdsServer() {
        return tracked != null;
    }

    private record TrackedWindow(Object server, ServerTickWindow window) {
    }
}
