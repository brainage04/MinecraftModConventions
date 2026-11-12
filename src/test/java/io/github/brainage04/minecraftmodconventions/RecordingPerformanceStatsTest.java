package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingPerformanceStatsTest {
    private static final long MB = 1024L * 1024L;

    @Test
    void theOverlayShowsFramesTicksAndMemoryInThreeShortLines() {
        assertEquals(List.of("98 fps, frame 10.2 ms", "TPS 20.0, 12.5 mspt", "RAM 1400/4096 MB"),
                RecordingPerformanceStats.overlayLines(98, 10.24, 12.5, 20.0, 1400 * MB, 4096 * MB));
        assertEquals("TPS -", RecordingPerformanceStats.overlayLines(60, 5, Double.NaN, Double.NaN, MB, MB).get(1));
    }

    @Test
    void aServerTickThatOverrunsItsSlotLowersTheTicksPerSecond() {
        assertEquals(20.0, RecordingPerformanceStats.tps(12.5, 20.0));
        assertEquals(10.0, RecordingPerformanceStats.tps(100.0, 20.0));
        assertEquals(20.0, RecordingPerformanceStats.tps(0, 20.0));
    }

    @Test
    void theSummaryKeepsMeansPercentilesAndPeaks() {
        RecordingPerformanceStats stats = new RecordingPerformanceStats();
        for (int frame = 0; frame < 95; frame++) {
            stats.add(100, 8.4, 10.0, 20.0, 1000 * MB, 4096 * MB);
        }
        for (int frame = 0; frame < 5; frame++) {
            stats.add(40, 30.2, 60.0, 20.0, 1500 * MB, 4096 * MB);
        }
        Properties summary = stats.summary();

        assertEquals("97.00", summary.getProperty("perfFpsMean"));
        assertEquals("40", summary.getProperty("perfFpsMin"));
        assertEquals("9", summary.getProperty("perfFrameTimeP50Ms"));
        assertEquals("9", summary.getProperty("perfFrameTimeP95Ms"));
        assertEquals("30.20", summary.getProperty("perfFrameTimeMaxMs"));
        assertEquals("12.50", summary.getProperty("perfMsptMean"));
        assertEquals("60.00", summary.getProperty("perfMsptMax"));
        assertEquals("16.67", summary.getProperty("perfTpsMin"));
        assertEquals("1500.00", summary.getProperty("perfMemoryMaxMb"));
        assertEquals("4096.00", summary.getProperty("perfMemoryLimitMb"));
    }

    @Test
    void aClientWithoutAServerInReachKeepsNoServerNumbers() {
        RecordingPerformanceStats stats = new RecordingPerformanceStats();
        stats.add(60, 5.0, Double.NaN, Double.NaN, MB, MB);

        Properties summary = stats.summary();
        assertTrue(summary.containsKey("perfFpsMean"));
        assertFalse(summary.containsKey("perfMsptMean"));
        assertTrue(new RecordingPerformanceStats().summary().isEmpty());
    }
}
