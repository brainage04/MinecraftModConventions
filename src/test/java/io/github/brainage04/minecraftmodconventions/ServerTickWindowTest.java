package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTickWindowTest {
    private static final long MS = 1_000_000L;
    /** A frame every 1/60 of a second, as the client samples the server. */
    private static final long FRAME = 1_000_000_000L / 60;

    @Test
    void aServerThatKeepsUpReadsItsTickRateAndItsTickTime() {
        FakeServer server = new FakeServer(List.of());
        ServerTickWindow.Reading reading = run(server, new ServerTickWindow(), 3000 * MS).getLast();

        assertEquals(20.0, reading.tps(), 1e-9);
        assertEquals(5.0, reading.mspt(), 1e-9);
        assertEquals(5.0, reading.msptMax(), 1e-9);
    }

    @Test
    void aForcedLongTickShowsWhileItRunsAndForOneSecondAfter() {
        // Tick 40 starts at 2000 ms and takes 1000 ms; every other tick takes 5 ms of its 50 ms slot.
        FakeServer server = new FakeServer(List.of(40));
        ServerTickWindow window = new ServerTickWindow();

        run(server, window, 1990 * MS);
        ServerTickWindow.Reading halfway = run(server, window, 2500 * MS).getLast();
        assertTrue(halfway.tps() < 12, "half a second into the stall: " + halfway);
        assertTrue(halfway.mspt() >= 480, "the running tick's time so far: " + halfway);

        ServerTickWindow.Reading nearEnd = run(server, window, 2990 * MS).getLast();
        assertTrue(nearEnd.tps() < 1, "no tick finished in the last second: " + nearEnd);
        // The last frame is at 2983 ms, and the client saw the tick start at the first frame after 2000 ms.
        assertTrue(nearEnd.msptMax() >= 2983 - 2000 - 1000.0 / 60, nearEnd.toString());

        ServerTickWindow.Reading after = run(server, window, 3500 * MS).getLast();
        assertTrue(after.msptMax() >= 1000, "the finished stall stays as the second's peak: " + after);
        assertTrue(after.tps() < 20, after.toString());

        ServerTickWindow.Reading recovered = run(server, window, 4100 * MS).getLast();
        assertEquals(20.0, recovered.tps(), 1e-9);
        assertEquals(5.0, recovered.msptMax(), 1e-9);
    }

    @Test
    void aTickThatStartsAFewMillisecondsLateIsNotAStall() {
        FakeServer server = new FakeServer(List.of());
        server.lateBy = 8 * MS;
        ServerTickWindow window = new ServerTickWindow();
        run(server, window, 1000 * MS);
        for (ServerTickWindow.Reading reading : run(server, window, 3000 * MS)) {
            assertEquals(5.0, reading.msptMax(), 1e-9, reading.toString());
            assertEquals(20.0, reading.tps(), 1e-9, reading.toString());
        }
    }

    @Test
    void aPausedServerIsNotAStall() {
        ServerTickWindow window = new ServerTickWindow();
        long[] times = new long[100];
        java.util.Arrays.fill(times, 5 * MS);
        window.sample(0, 10, times, 20);
        window.paused(5000 * MS);
        ServerTickWindow.Reading resumed = window.sample(5000 * MS + FRAME, 10, times, 20);

        assertEquals(5.0, resumed.mspt(), 1e-9);
        assertEquals(20.0, resumed.tps(), 1e-9);
    }

    /** Samples the window every frame until {@code until} and returns every reading. */
    private static List<ServerTickWindow.Reading> run(FakeServer server, ServerTickWindow window, long until) {
        List<ServerTickWindow.Reading> readings = new ArrayList<>();
        for (long now = server.sampledUntil + FRAME; now <= until; now += FRAME) {
            server.advance(now);
            readings.add(window.sample(now, server.tickCount, server.tickTimesNanos, 20));
            server.sampledUntil = now;
        }
        return readings;
    }

    /**
     * Ticks as {@code MinecraftServer.tickServer} does: the count rises when a tick starts, and the tick's time is
     * stored at {@code count % 100} when it ends. Ticks start every 50 ms (every other one {@code lateBy} late), or
     * right after a tick that overran its slot.
     */
    private static final class FakeServer {
        final long[] tickTimesNanos = new long[100];
        final List<Integer> longTicks;
        int tickCount;
        long lateBy;
        long sampledUntil;
        private long nextSlot = 50 * MS;
        private long tickStart = -1;
        private long tickEnd;

        FakeServer(List<Integer> longTicks) {
            this.longTicks = longTicks;
        }

        void advance(long now) {
            while (true) {
                if (tickStart >= 0) {
                    if (tickEnd > now) {
                        return;
                    }
                    tickTimesNanos[tickCount % 100] = tickEnd - tickStart;
                    nextSlot = Math.max(nextSlot, tickEnd);
                    tickStart = -1;
                }
                long start = nextSlot + (tickCount % 2 == 1 ? lateBy : 0);
                if (start > now) {
                    return;
                }
                tickCount++;
                tickStart = start;
                tickEnd = start + (longTicks.contains(tickCount) ? 1000 * MS : 5 * MS);
                nextSlot += 50 * MS;
            }
        }
    }
}
