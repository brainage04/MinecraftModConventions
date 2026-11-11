package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestStagesTest {
    private static final long MILLIS = 1_794_398_400_000L;

    @Test
    void theMarkerLineIsWhatTheTrimScriptsParse() {
        // scripts/trim_stages.py matches "STAGE_START (\d+) (\S+)" and "STAGE_END (\d+) (\S+)".
        assertEquals(
                "STAGE_START " + MILLIS + " iceskates.spawn",
                ClientGameTestStages.markerLine("STAGE_START", MILLIS, "iceskates.spawn"));
        assertEquals(
                "STAGE_END " + MILLIS + " telekinesis.tree",
                ClientGameTestStages.markerLine("STAGE_END", MILLIS, "telekinesis.tree"));
    }

    @Test
    void markStagePrintsOneLineWithTheCurrentTime() {
        String printed = capture(() -> ClientGameTestStages.markStage("iceskates.spawn"));

        assertTrue(
                printed.matches("STAGE_START \\d{13} iceskates\\.spawn\\R"),
                "unexpected stage marker: " + printed);
    }

    @Test
    void endStagePrintsOneLineWithTheCurrentTime() {
        String printed = capture(() -> ClientGameTestStages.endStage("iceskates.spawn"));

        assertTrue(
                printed.matches("STAGE_END \\d{13} iceskates\\.spawn\\R"),
                "unexpected stage marker: " + printed);
    }

    @Test
    void endStageHoldsTheSceneForItsTicks() {
        RecordingTestContext context = new RecordingTestContext();

        ClientGameTestStages.endStage(context, "iceskates.spawn", 6);

        assertEquals(6, context.waitedTicks());
    }

    @Test
    void aStageIdIsOneNonBlankToken() {
        // The trim scripts split the marker line on spaces, so a spaced id would lose its tail.
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestStages.markerLine("STAGE_START", MILLIS, "ice skates"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestStages.markerLine("STAGE_START", MILLIS, "  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestStages.markerLine("STAGE_START", MILLIS, "ice\nskates"));
    }

    @Test
    void aStageIdIsTrimmedButKeptWhole() {
        assertEquals(
                "STAGE_START " + MILLIS + " iceskates.spawn",
                ClientGameTestStages.markerLine("STAGE_START", MILLIS, "  iceskates.spawn  "));
    }

    @Test
    void aNegativeHoldIsRejected() {
        RecordingTestContext context = new RecordingTestContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestStages.endStage(context, "iceskates.spawn", -1));
    }

    private static String capture(Runnable marker) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            marker.run();
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
