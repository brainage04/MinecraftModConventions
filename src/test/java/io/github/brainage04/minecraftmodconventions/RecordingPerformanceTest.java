package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingPerformanceTest {
    @AfterEach
    void forget() {
        RecordingPerformance.forgetServer();
    }

    @Test
    void oneServerKeepsItsWindowAndAnotherGetsAFreshOne() {
        Object server = new Object();
        ServerTickWindow window = RecordingPerformance.windowFor(server);

        assertSame(window, RecordingPerformance.windowFor(server));
        assertNotSame(window, RecordingPerformance.windowFor(new Object()));
    }

    @Test
    void untrackingTheDedicatedServerLetsGoOfIt() {
        RecordingPerformance.windowFor(new Object());
        assertTrue(RecordingPerformance.holdsServer());

        RecordingPerformance.untrackServer();
        assertFalse(RecordingPerformance.holdsServer());
    }

    @Test
    void leavingTheWorldLetsGoOfTheServer() {
        Object server = new Object();
        ServerTickWindow window = RecordingPerformance.windowFor(server);

        RecordingPerformance.forgetServer();
        assertFalse(RecordingPerformance.holdsServer());
        // Back in a world on the same server, the window starts over instead of counting the time away as a stall.
        assertNotSame(window, RecordingPerformance.windowFor(server));
    }
}
