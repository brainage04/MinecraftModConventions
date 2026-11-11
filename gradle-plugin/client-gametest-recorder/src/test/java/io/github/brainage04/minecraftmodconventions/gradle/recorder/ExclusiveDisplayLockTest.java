package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void theLockFileIsNamedAfterTheDisplay() {
        assertEquals(".gtr-recording-display-1.lock", ExclusiveDisplayLock.lockFile(":1").getName());
        assertEquals(".gtr-recording-display-1.lock", ExclusiveDisplayLock.lockFile("1").getName());
        assertEquals(".gtr-recording-display-1.0.lock", ExclusiveDisplayLock.lockFile(":1.0").getName());
        assertEquals(".gtr-recording-display-default.lock", ExclusiveDisplayLock.lockFile(" ").getName());
    }
}
