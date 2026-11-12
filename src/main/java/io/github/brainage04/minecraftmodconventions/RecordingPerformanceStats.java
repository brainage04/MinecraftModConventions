package io.github.brainage04.minecraftmodconventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The performance overlay's lines and the summary a recording's metadata keeps: the client's frame rate and
 * the time it spends rendering a frame, the server's ticks per second and milliseconds per tick (see
 * {@link ServerTickWindow}), and the client's Java heap.
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
    /** Per second of the recording: the lowest ticks per second and the most milliseconds a tick took. */
    private final List<double[]> seconds = new ArrayList<>();
    private double memoryMaxMb;
    private double memoryLimitMb;

    /**
     * One rendered frame's numbers, {@code second} whole seconds after the first world tick; {@code server} is null
     * when no server is in reach or it is paused.
     */
    synchronized void add(int fps, double frameTimeMs, ServerTickWindow.Reading server, long usedBytes, long maxBytes,
            int second) {
        frames++;
        fpsTotal += fps;
        fpsMin = Math.min(fpsMin, fps);
        frameTimeMaxMs = Math.max(frameTimeMaxMs, frameTimeMs);
        frameTimeBuckets[(int) Math.min(FRAME_TIME_BUCKETS, Math.max(0, Math.floor(frameTimeMs)))]++;
        if (server != null) {
            serverSamples++;
            msptTotal += server.mspt();
            msptMax = Math.max(msptMax, server.msptMax());
            tpsMin = Math.min(tpsMin, server.tps());
            if (second >= 0) {
                while (seconds.size() <= second) {
                    seconds.add(null);
                }
                double[] worst = seconds.get(second);
                if (worst == null) {
                    seconds.set(second, new double[] {server.tps(), server.msptMax()});
                } else {
                    worst[0] = Math.min(worst[0], server.tps());
                    worst[1] = Math.max(worst[1], server.msptMax());
                }
            }
        }
        memoryMaxMb = Math.max(memoryMaxMb, usedBytes / MEBIBYTE);
        memoryLimitMb = maxBytes / MEBIBYTE;
    }

    static List<String> overlayLines(int fps, double frameTimeMs, ServerTickWindow.Reading server, boolean serverPaused,
            long usedBytes, long maxBytes) {
        String serverLine;
        if (server != null) {
            serverLine = String.format(Locale.ROOT, "TPS %.1f, mspt %.1f, max %.1f", server.tps(), server.mspt(),
                    server.msptMax());
        } else {
            serverLine = serverPaused ? "TPS - (paused)" : "TPS -";
        }
        return List.of(
                String.format(Locale.ROOT, "%d fps, frame %.1f ms", fps, frameTimeMs),
                serverLine,
                String.format(Locale.ROOT, "heap %.0f/%.0f MB", usedBytes / MEBIBYTE, maxBytes / MEBIBYTE));
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
            List<String> tps = new ArrayList<>();
            List<String> mspt = new ArrayList<>();
            for (double[] second : seconds) {
                tps.add(second == null ? "-" : String.format(Locale.ROOT, "%.1f", second[0]));
                mspt.add(second == null ? "-" : String.format(Locale.ROOT, "%.1f", second[1]));
            }
            summary.setProperty("perfTpsMinPerSecond", String.join(" ", tps));
            summary.setProperty("perfMsptMaxPerSecond", String.join(" ", mspt));
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
