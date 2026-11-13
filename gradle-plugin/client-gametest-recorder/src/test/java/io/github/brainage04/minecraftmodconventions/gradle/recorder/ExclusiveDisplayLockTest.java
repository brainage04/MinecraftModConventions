package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusiveDisplayLockTest {
    /** A display number no recording uses, so a run on this host cannot collide with the test. */
    private static final String DISPLAY = ":98765";

    @Test
    void aSecondRecordingCannotTakeADisplayThatIsBeingRecorded() {
        try (ExclusiveDisplayLock first = ExclusiveDisplayLock.tryAcquire(DISPLAY)) {
            assertNotNull(first, "the first recording takes the display");

            assertNull(ExclusiveDisplayLock.tryAcquire(DISPLAY), "the second recording falls back");
        }

        try (ExclusiveDisplayLock again = ExclusiveDisplayLock.tryAcquire(DISPLAY)) {
            assertNotNull(again, "the display is free again once the first recording has stopped");
        }
    }

    @Test
    void anotherDisplayIsNotBlocked() {
        try (ExclusiveDisplayLock first = ExclusiveDisplayLock.tryAcquire(DISPLAY);
                ExclusiveDisplayLock other = ExclusiveDisplayLock.tryAcquire(":98766")) {
            assertNotNull(first);
            assertNotNull(other);
        }
    }

    @Test
    void aRecordingThatMustUseTheCardWaitsForTheDisplayAndTakesItOnceFree() throws Exception {
        ExclusiveDisplayLock first = ExclusiveDisplayLock.tryAcquire(DISPLAY);
        assertNotNull(first);
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(1_500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            first.close();
        });
        release.start();
        long started = System.nanoTime();
        try (ExclusiveDisplayLock waited = ExclusiveDisplayLock.acquireWithin(DISPLAY, Duration.ofSeconds(30))) {
            assertNotNull(waited, "the display is taken once the first recording stops");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1_000L, "it waited for the release");
        } finally {
            release.join();
        }
    }

    @Test
    void theWaitForATakenDisplayIsBounded() throws Exception {
        try (ExclusiveDisplayLock first = ExclusiveDisplayLock.tryAcquire(DISPLAY)) {
            assertNotNull(first);
            long started = System.nanoTime();

            assertNull(ExclusiveDisplayLock.acquireWithin(DISPLAY, Duration.ofMillis(1_200)));
            long waitedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(waitedMillis >= 1_200L && waitedMillis < 10_000L, "waited " + waitedMillis + " ms");
        }
    }

    @Test
    void theLockFileIsNamedAfterTheDisplay() {
        assertEquals(".gtr-recording-display-1.lock", ExclusiveDisplayLock.lockFile(":1").getName());
        assertEquals(".gtr-recording-display-1.lock", ExclusiveDisplayLock.lockFile("1").getName());
        assertEquals(".gtr-recording-display-1.0.lock", ExclusiveDisplayLock.lockFile(":1.0").getName());
        assertEquals(".gtr-recording-display-default.lock", ExclusiveDisplayLock.lockFile(" ").getName());
    }
}
