package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordClientGameTestTaskTest {
    @Test
    void completeCaptureHasNoRecordingFailures() {
        assertTrue(RecordClientGameTestTask.recordingFailureMessages(
                0,
                true,
                true,
                false,
                255,
                true,
                true,
                true,
                "build/run/clientGameTest"
        ).isEmpty());
    }

    @Test
    void captureQueuesEncodedPacketsWhileRawCaptureWritesBlock() {
        List<String> command = RecordClientGameTestTask.captureCommand(
                "30", "1920x1080", ":90", "sink.monitor", "progress.txt", "audio.txt", "raw.mkv");

        // An option after the last input and before the output file applies to the output (the muxer queue).
        int output = command.size() - 1;
        assertEquals("raw.mkv", command.get(output));
        int lastInput = command.lastIndexOf("-i");
        int queue = command.subList(lastInput, output).lastIndexOf("-thread_queue_size") + lastInput;
        assertTrue(queue > lastInput, "the raw capture output must set its own -thread_queue_size");
        int packets = Integer.parseInt(command.get(queue + 1));
        // A one-second journal wait (about 50 packets) already froze x11grab with ffmpeg's default of 8;
        // the worst observed wait was about 12 s.
        assertTrue(packets >= 50 * 60, "muxer queue must absorb at least a minute of blocked writes: " + packets);
    }
}
