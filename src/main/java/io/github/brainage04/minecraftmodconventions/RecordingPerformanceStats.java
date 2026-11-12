package io.github.brainage04.minecraftmodconventions;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The performance overlay's lines and the summary a recording's metadata keeps: the client's frame rate and
 * the time it spends on a frame, the server's ticks per second and milliseconds per tick, and the memory the
 * client's JVM uses.
 */
final class RecordingPerformanceStats {
    private static final double MEBIBYTE = 1024.0 * 1024.0;
    /** Frame times up to this many milliseconds are counted per millisecond for the percentiles. */
    private static final int FRAME_TIME_BUCKETS = 250;

    private final long[] frameTimeBuckets = new long[FRAME_TIME_BUCKETS + 1];
    private long frames;
    private long fpsTotal;
    private int fpsMin = Integer.MAX_VALUE;
    private double frameTimeMaxMs;
    private long serverSamples;
    private double msptTotal;
    private double msptMax;
    private double tpsMin = Double.MAX_VALUE;
    private double memoryMaxMb;
    private double memoryLimitMb;

    /** One rendered frame's numbers; {@code mspt} is NaN when no server is in reach. */
    synchronized void add(int fps, double frameTimeMs, double mspt, double tickRate, long usedBytes, long maxBytes) {
        frames++;
        fpsTotal += fps;
        fpsMin = Math.min(fpsMin, fps);
        frameTimeMaxMs = Math.max(frameTimeMaxMs, frameTimeMs);
        frameTimeBuckets[(int) Math.min(FRAME_TIME_BUCKETS, Math.max(0, Math.floor(frameTimeMs)))]++;
        if (!Double.isNaN(mspt)) {
            serverSamples++;
            msptTotal += mspt;
            msptMax = Math.max(msptMax, mspt);
            tpsMin = Math.min(tpsMin, tps(mspt, tickRate));
        }
        memoryMaxMb = Math.max(memoryMaxMb, usedBytes / MEBIBYTE);
        memoryLimitMb = maxBytes / MEBIBYTE;
    }

    /** The ticks the server manages a second: its tick rate, or fewer when a tick takes longer than its slot. */
    static double tps(double mspt, double tickRate) {
        return mspt <= 0 ? tickRate : Math.min(tickRate, 1000.0 / mspt);
    }

    static List<String> overlayLines(int fps, double frameTimeMs, double mspt, double tickRate, long usedBytes,
            long maxBytes) {
        String server = Double.isNaN(mspt)
                ? "TPS -"
                : String.format(Locale.ROOT, "TPS %.1f, %.1f mspt", tps(mspt, tickRate), mspt);
        return List.of(
                String.format(Locale.ROOT, "%d fps, frame %.1f ms", fps, frameTimeMs),
                server,
                String.format(Locale.ROOT, "RAM %.0f/%.0f MB", usedBytes / MEBIBYTE, maxBytes / MEBIBYTE));
    }

    /** The summary, as {@code perf*} properties, or none when no frame was sampled. */
    synchronized Properties summary() {
        Properties summary = new Properties();
        if (frames == 0) {
            return summary;
        }
        summary.setProperty("perfSampledFrames", Long.toString(frames));
        summary.setProperty("perfFpsMean", format(fpsTotal / (double) frames));
        summary.setProperty("perfFpsMin", Integer.toString(fpsMin));
        summary.setProperty("perfFrameTimeP50Ms", Integer.toString(percentile(0.5)));
        summary.setProperty("perfFrameTimeP95Ms", Integer.toString(percentile(0.95)));
        summary.setProperty("perfFrameTimeMaxMs", format(frameTimeMaxMs));
        if (serverSamples > 0) {
            summary.setProperty("perfMsptMean", format(msptTotal / serverSamples));
            summary.setProperty("perfMsptMax", format(msptMax));
            summary.setProperty("perfTpsMin", format(tpsMin));
        }
        summary.setProperty("perfMemoryMaxMb", format(memoryMaxMb));
        summary.setProperty("perfMemoryLimitMb", format(memoryLimitMb));
        return summary;
    }

    /** The lowest whole millisecond that at least {@code share} of the frames took no longer than. */
    private int percentile(double share) {
        long wanted = (long) Math.ceil(share * frames);
        long seen = 0;
        for (int millis = 0; millis <= FRAME_TIME_BUCKETS; millis++) {
            seen += frameTimeBuckets[millis];
            if (seen >= wanted) {
                return millis + 1;
            }
        }
        return FRAME_TIME_BUCKETS;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
