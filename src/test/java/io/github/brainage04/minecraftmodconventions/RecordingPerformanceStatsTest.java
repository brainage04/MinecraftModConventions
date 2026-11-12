package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingPerformanceStatsTest {
    private static final long MB = 1024L * 1024L;
    private static final ServerTickWindow.Reading STEADY = new ServerTickWindow.Reading(20.0, 10.0, 12.0);

    @Test
    void theOverlayShowsFramesTicksAndHeapInThreeShortLines() {
        assertEquals(List.of("98 fps, frame 10.2 ms", "TPS 20.0, mspt 12.5, max 31.0", "heap 1400/4096 MB"),
                RecordingPerformanceStats.overlayLines(98, 10.24, new ServerTickWindow.Reading(20.0, 12.5, 31.0), false,
                        1400 * MB, 4096 * MB));
        assertEquals("TPS -", RecordingPerformanceStats.overlayLines(60, 5, null, false, MB, MB).get(1));
        assertEquals("TPS - (paused)", RecordingPerformanceStats.overlayLines(60, 5, null, true, MB, MB).get(1));
    }

    @Test
    void theSummaryKeepsMeansPercentilesAndPeaks() {
        RecordingPerformanceStats stats = new RecordingPerformanceStats();
        for (int frame = 0; frame < 95; frame++) {
            stats.add(100, 8.4, STEADY, 1000 * MB, 4096 * MB, 0);
        }
        for (int frame = 0; frame < 5; frame++) {
            stats.add(40, 30.2, new ServerTickWindow.Reading(16.7, 60.0, 60.0), 1500 * MB, 4096 * MB, 0);
        }
        Properties summary = stats.summary();

        assertEquals("97.00", summary.getProperty("perfFpsMean"));
        assertEquals("40", summary.getProperty("perfFpsMin"));
        assertEquals("9", summary.getProperty("perfFrameTimeP50Ms"));
        assertEquals("9", summary.getProperty("perfFrameTimeP95Ms"));
        assertEquals("30.20", summary.getProperty("perfFrameTimeMaxMs"));
        assertEquals("12.50", summary.getProperty("perfMsptMean"));
        assertEquals("60.00", summary.getProperty("perfMsptMax"));
        assertEquals("16.70", summary.getProperty("perfTpsMin"));
        assertEquals("1500.00", summary.getProperty("perfMemoryMaxMb"));
        assertEquals("4096.00", summary.getProperty("perfMemoryLimitMb"));
    }

    @Test
    void eachSecondKeepsItsLowestTicksPerSecondAndItsLongestTick() {
        RecordingPerformanceStats stats = new RecordingPerformanceStats();
        stats.add(60, 5, STEADY, MB, MB, -1);
        stats.add(60, 5, STEADY, MB, MB, 0);
        stats.add(60, 5, new ServerTickWindow.Reading(9.8, 512.0, 512.0), MB, MB, 2);
        stats.add(60, 5, new ServerTickWindow.Reading(1.0, 1000.0, 1000.0), MB, MB, 2);
        stats.add(60, 5, STEADY, MB, MB, 3);

        Properties summary = stats.summary();
        assertEquals("20.0 - 1.0 20.0", summary.getProperty("perfTpsMinPerSecond"));
        assertEquals("12.0 - 1000.0 12.0", summary.getProperty("perfMsptMaxPerSecond"));
        assertEquals("1000.00", summary.getProperty("perfMsptMax"));
        assertEquals("1.00", summary.getProperty("perfTpsMin"));
    }

    @Test
    void aClientWithoutAServerInReachKeepsNoServerNumbers() {
        RecordingPerformanceStats stats = new RecordingPerformanceStats();
        stats.add(60, 5.0, null, MB, MB, 0);

        Properties summary = stats.summary();
        assertTrue(summary.containsKey("perfFpsMean"));
        assertFalse(summary.containsKey("perfMsptMean"));
        assertFalse(summary.containsKey("perfTpsMinPerSecond"));
        assertTrue(new RecordingPerformanceStats().summary().isEmpty());
    }
}
