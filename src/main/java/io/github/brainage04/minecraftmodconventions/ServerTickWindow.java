package io.github.brainage04.minecraftmodconventions;

import java.util.ArrayDeque;

/**
 * The server's ticks over the last second, read from the time the server records for each tick, so one long tick
 * shows at once instead of being spread over an average.
 *
 * <p>{@code MinecraftServer.tickServer} raises its tick count when a tick starts and stores that tick's time in
 * {@code tickTimesNanos[tickCount % 100]} when it ends ({@code MinecraftServer.java:959,974-978} in the 26.3 server
 * sources), so a reader on another thread knows only the ticks before the current count are finished. A tick that
 * has run for more than {@value #STALL_SLOTS} tick slots since its count was first seen is a stall still in
 * progress, and counts with the time it has run so far.
 */
final class ServerTickWindow {
    static final long WINDOW_NANOS = 1_000_000_000L;
    /** {@code MinecraftServer.tickTimesNanos} holds this many ticks. */
    private static final int RECORDED_TICKS = 100;
    /**
     * A tick counts as stalled after this many tick slots. Between two counts the server works and then waits for
     * the next slot, so in a server that keeps up the count changes about once a slot; one slot of slack keeps a
     * tick that starts a few milliseconds late from reading as a stall.
     */
    static final int STALL_SLOTS = 2;

    /** Each finished tick in the window: when it was first seen finished, and its time. */
    private final ArrayDeque<long[]> ticks = new ArrayDeque<>();
    private int tickCount = -1;
    private long countSeenNanos;
    private long lastTickNanos;

    /**
     * The window after the server was seen at {@code now} with {@code tickCount} ticks started and its recorded
     * {@code tickTimesNanos}, at a tick rate of {@code tickRate} a second.
     */
    Reading sample(long now, int tickCount, long[] tickTimesNanos, double tickRate) {
        if (this.tickCount < 0 || tickCount < this.tickCount) {
            ticks.clear();
            this.tickCount = Math.max(0, tickCount - 1);
            countSeenNanos = now;
        }
        if (tickCount != this.tickCount) {
            for (int tick = Math.max(this.tickCount, tickCount - RECORDED_TICKS); tick < tickCount; tick++) {
                lastTickNanos = tickTimesNanos[tick % RECORDED_TICKS];
                ticks.addLast(new long[] {now, lastTickNanos});
            }
            this.tickCount = tickCount;
            countSeenNanos = now;
        }
        while (!ticks.isEmpty() && ticks.peekFirst()[0] <= now - WINDOW_NANOS) {
            ticks.removeFirst();
        }

        double slotNanos = 1_000_000_000.0 / tickRate;
        long running = now - countSeenNanos;
        long stall = running > STALL_SLOTS * slotNanos ? running : 0;
        double busyNanos = stall;
        long maxNanos = stall;
        for (long[] tick : ticks) {
            busyNanos += Math.max(slotNanos, tick[1]);
            maxNanos = Math.max(maxNanos, tick[1]);
        }
        double tps = busyNanos == 0 ? tickRate : Math.min(tickRate, ticks.size() * 1_000_000_000.0 / busyNanos);
        return new Reading(tps, millis(stall > 0 ? stall : lastTickNanos), millis(maxNanos));
    }

    /** Forgets the time since the last count, for a server that does not tick while it is paused. */
    void paused(long now) {
        countSeenNanos = now;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * Ticks per second over the last second, the last tick's milliseconds (or a stalled tick's so far), and the
     * most milliseconds any tick took in the last second.
     */
    record Reading(double tps, double mspt, double msptMax) {
    }
}
