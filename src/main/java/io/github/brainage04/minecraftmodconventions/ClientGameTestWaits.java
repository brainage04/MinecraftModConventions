package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The waits a filmed client GameTest needs before its first frame: the chunks around the player, the
 * meshes that draw them, a region a camera looks at, and a server-side condition.
 *
 * <p>The chunk waits poll the client's chunk cache rather than {@code ClientLevel#hasChunk}, which is
 * true for every position, and the mesh wait needs a run of idle ticks because the renderer builds a
 * chunk's mesh over several ticks. Each wait fails the test with a message naming what was still
 * missing instead of running on into a frame that is not ready.
 */
public final class ClientGameTestWaits {
    private ClientGameTestWaits() {
    }

    /**
     * Waits until every chunk inside a circle of {@code radius} chunks around the player's chunk is
     * fully loaded. The server sends chunks in a circle, not a square, and the ring at the edge may
     * still be on its way, so pass the server's view distance minus two.
     */
    public static void waitForChunkWindow(ClientGameTestContext context, int radius, int timeoutTicks) {
        Objects.requireNonNull(context, "context");
        requirePositive(radius, "radius");
        requirePositive(timeoutTicks, "timeoutTicks");
        for (int tick = 0; tick < timeoutTicks; tick++) {
            boolean loaded = context.computeOnClient(client -> chunkWindowLoaded(
                    SectionPos.blockToSectionCoord(client.player.getX()),
                    SectionPos.blockToSectionCoord(client.player.getZ()),
                    radius,
                    (chunkX, chunkZ) -> client.level.getChunkSource()
                            .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null));
            if (loaded) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(
                "The client did not load every chunk within " + radius + " chunks of the player");
    }

    /**
     * Waits until the renderer has finished every chunk mesh for {@code idleTicks} consecutive ticks,
     * so a frame that is filmed does not show a half-built chunk.
     */
    public static void waitForRenderSettled(ClientGameTestContext context, int idleTicks, int timeoutTicks) {
        Objects.requireNonNull(context, "context");
        requirePositive(idleTicks, "idleTicks");
        requirePositive(timeoutTicks, "timeoutTicks");
        int consecutiveIdleTicks = 0;
        for (int tick = 0; tick < timeoutTicks; tick++) {
            boolean idle = context.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
            consecutiveIdleTicks = idle ? consecutiveIdleTicks + 1 : 0;
            if (consecutiveIdleTicks >= idleTicks) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("The client did not finish building the chunk meshes around the player");
    }

    /** Waits until every chunk in an inclusive chunk-coordinate rectangle is fully loaded. */
    public static void waitForRegionLoaded(
            ClientGameTestContext context,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            int timeoutTicks) {
        Objects.requireNonNull(context, "context");
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException(
                    "The region is empty: " + minChunkX + "," + minChunkZ + " to " + maxChunkX + "," + maxChunkZ);
        }
        requirePositive(timeoutTicks, "timeoutTicks");
        for (int tick = 0; tick < timeoutTicks; tick++) {
            boolean loaded = context.computeOnClient(client -> {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        if (client.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                            return false;
                        }
                    }
                }
                return true;
            });
            if (loaded) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(
                "The client did not load the chunks " + minChunkX + "," + minChunkZ + " to "
                        + maxChunkX + "," + maxChunkZ);
    }

    /**
     * Waits until the server satisfies {@code condition}, polling once per client tick. The
     * condition may read server state that the client's own copy does not prove, such as the drops
     * the server hands out after a break.
     */
    public static void waitForServer(
            ClientGameTestContext context,
            TestServerContext server,
            Predicate<MinecraftServer> condition,
            int timeoutTicks,
            String description) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(condition, "condition");
        requirePositive(timeoutTicks, "timeoutTicks");
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (server.computeOnServer(condition::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("The server never reached the stage's expected state: " + description);
    }

    /** Waits by the clock, as the film runs, rather than by ticks, which run slower under load. */
    public static void holdFor(ClientGameTestContext context, long millis) {
        Objects.requireNonNull(context, "context");
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative: " + millis);
        }
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            context.waitTick();
        }
    }

    static boolean chunkWindowLoaded(int centreChunkX, int centreChunkZ, int radius, ChunkLoaded loaded) {
        for (int chunkX = centreChunkX - radius; chunkX <= centreChunkX + radius; chunkX++) {
            for (int chunkZ = centreChunkZ - radius; chunkZ <= centreChunkZ + radius; chunkZ++) {
                int dx = chunkX - centreChunkX;
                int dz = chunkZ - centreChunkZ;
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                if (!loaded.isLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    public interface ChunkLoaded {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
