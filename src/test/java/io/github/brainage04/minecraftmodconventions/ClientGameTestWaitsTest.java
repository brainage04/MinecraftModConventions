package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.minecraft.server.MinecraftServer;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.function.Predicate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestWaitsTest {
    @Test
    void theChunkWindowIsTheCircleAroundThePlayersChunk() {
        Set<Long> visited = new HashSet<>();

        boolean loaded = ClientGameTestWaits.chunkWindowLoaded(0, 0, 1, (chunkX, chunkZ) -> {
            visited.add(key(chunkX, chunkZ));
            return true;
        });

        assertTrue(loaded);
        assertEquals(Set.of(key(0, 0), key(1, 0), key(-1, 0), key(0, 1), key(0, -1)), visited);
    }

    @Test
    void aMissingChunkInsideTheCircleFailsTheWindow() {
        Set<Long> loaded = Set.of(key(0, 0), key(1, 0), key(-1, 0), key(0, 1));

        assertFalse(ClientGameTestWaits.chunkWindowLoaded(0, 0, 1, (chunkX, chunkZ) -> loaded.contains(key(chunkX, chunkZ))));
    }

    @Test
    void aCornerOutsideTheCircleDoesNotCount() {
        // (1, 1) is outside a radius of 1, which only reaches the four neighbours.
        Set<Long> loaded = Set.of(key(0, 0), key(1, 0), key(-1, 0), key(0, 1), key(0, -1));

        assertTrue(ClientGameTestWaits.chunkWindowLoaded(0, 0, 1, (chunkX, chunkZ) -> loaded.contains(key(chunkX, chunkZ))));
    }

    @Test
    void aZeroRadiusWindowIsThePlayersChunkAlone() {
        assertEquals(
                1, countVisited(0, 0, 0));
    }

    @Test
    void theWindowFollowsThePlayersChunk() {
        Set<Long> visited = new HashSet<>();
        ClientGameTestWaits.chunkWindowLoaded(100, -50, 0, (chunkX, chunkZ) -> {
            visited.add(key(chunkX, chunkZ));
            return true;
        });

        assertEquals(Set.of(key(100, -50)), visited);
    }

    @Test
    void holdForWaitsByTheClockRatherThanByTicks() {
        RecordingTestContext context = new RecordingTestContext();

        long start = System.currentTimeMillis();
        ClientGameTestWaits.holdFor(context, 20);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 20, "holdFor returned after " + elapsed + " ms");
    }

    @Test
    void aNegativeHoldIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestWaits.holdFor(new RecordingTestContext(), -1));
    }

    @Test
    void waitForServerPollsUntilTheConditionHolds() {
        RecordingTestContext context = new RecordingTestContext();
        int[] polls = {0};

        ClientGameTestWaits.waitForServer(
                context,
                new FakeServerContext(),
                _ -> ++polls[0] >= 3,
                10,
                "three polls");

        assertEquals(3, polls[0]);
        assertEquals(2, context.waitedTicks());
    }

    @Test
    void waitForServerFailsNamingTheExpectedState() {
        RecordingTestContext context = new RecordingTestContext();

        AssertionError error = assertThrows(
                AssertionError.class,
                () -> ClientGameTestWaits.waitForServer(
                        context, new FakeServerContext(), _ -> false, 4, "the drop count"));

        assertTrue(error.getMessage().contains("the drop count"), error.getMessage());
        assertEquals(4, context.waitedTicks());
    }

    @Test
    void everyTimeoutIsPositive() {
        RecordingTestContext context = new RecordingTestContext();

        assertThrows(IllegalArgumentException.class, () -> ClientGameTestWaits.waitForChunkWindow(context, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestWaits.waitForChunkWindow(context, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestWaits.waitForRenderSettled(context, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ClientGameTestWaits.waitForRenderSettled(context, 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestWaits.waitForServer(context, new FakeServerContext(), _ -> true, 0, "state"));
    }

    @Test
    void anEmptyRegionIsRejected() {
        RecordingTestContext context = new RecordingTestContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestWaits.waitForRegionLoaded(context, 1, 0, 0, 1, 10));
    }

    private static int countVisited(int centreChunkX, int centreChunkZ, int radius) {
        int[] visits = {0};
        ClientGameTestWaits.chunkWindowLoaded(centreChunkX, centreChunkZ, radius, (chunkX, chunkZ) -> {
            visits[0]++;
            return true;
        });
        return visits[0];
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** A dedicated server context whose conditions run against a null server. */
    private static final class FakeServerContext implements TestDedicatedServerContext {
        @Override
        public TestDedicatedServerConnection connect() {
            throw new UnsupportedOperationException("connect needs a server");
        }

        @Override
        public void close() {
        }

        @Override
        public void runCommand(String command) {
            throw new UnsupportedOperationException("runCommand needs a server");
        }

        @Override
        public int waitFor(Predicate<MinecraftServer> predicate) {
            throw new UnsupportedOperationException("waitFor needs a server");
        }

        @Override
        public int waitFor(Predicate<MinecraftServer> predicate, int timeoutTicks) {
            throw new UnsupportedOperationException("waitFor needs a server");
        }

        @Override
        public <E extends Throwable> void runOnServer(FailableConsumer<MinecraftServer, E> action) throws E {
            action.accept(null);
        }

        @Override
        public <T, E extends Throwable> T computeOnServer(FailableFunction<MinecraftServer, T, E> action) throws E {
            return action.apply(null);
        }
    }
}
