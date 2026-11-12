package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameTestRecorderStepTest {
    @Test
    void showStepAndHoldShowsTheStepAndHoldsItForItsTicks() {
        RecordingTestContext context = new RecordingTestContext();

        String printed = capture(() -> ClientGameTestRecorder.showStepAndHold(
                context, "simpletpa.request", "Teleport request", "You ask Friend", 15));

        assertTrue(
                printed.startsWith("[CLIENT_GAMETEST_RECORDER] simpletpa.request | Teleport request | You ask Friend"),
                printed);
        assertEquals(List.of(15), context.waits());
    }

    @Test
    void aNegativeHoldIsRejected() {
        RecordingTestContext context = new RecordingTestContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> ClientGameTestRecorder.showStepAndHold(context, "id", "title", "subtitle", -1));
        assertEquals(List.of(), context.waits());
    }

    @Test
    void aCaptionThatSaysWhatChangedIsRejectedBeforeItShows() {
        RecordingTestContext context = new RecordingTestContext();

        String printed = capture(() -> {
            IllegalArgumentException title = assertThrows(IllegalArgumentException.class, () ->
                    ClientGameTestRecorder.showStep(context, "id", "The 10 tick wind-up is gone", ""));
            assertTrue(title.getMessage().contains("\"is gone\""), title.getMessage());
            assertThrows(IllegalArgumentException.class, () ->
                    ClientGameTestRecorder.showStep(context, "id", "Fast damage", "Cooldowns no longer apply"));
            assertThrows(IllegalArgumentException.class, () ->
                    ClientGameTestRecordingHud.showStep("id", "The limit was removed", ""));
        });

        assertEquals("", printed);
    }

    @Test
    void aCaptionThatDescribesTheModShows() {
        RecordingTestContext context = new RecordingTestContext();

        String printed = capture(() -> ClientGameTestRecorder.showStep(
                context, "id", "Rule on: the bed explodes", "Drops go to your inventory instead of the ground"));

        assertTrue(printed.contains("Rule on: the bed explodes"), printed);
    }

    private static String capture(Runnable step) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            step.run();
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
