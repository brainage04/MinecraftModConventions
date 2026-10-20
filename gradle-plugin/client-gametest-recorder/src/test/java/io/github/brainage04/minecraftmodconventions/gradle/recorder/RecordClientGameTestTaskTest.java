package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void limitedJobCgroupIsProtectedUpToItsLimitAndRestored(@TempDir Path root) throws IOException {
        Path procSelfCgroup = Files.writeString(root.resolve("cgroup"), "0::/heavy.slice/heavy-1-42.scope\n");
        Path cgroup = Files.createDirectories(root.resolve("fs/heavy.slice/heavy-1-42.scope"));
        Files.writeString(cgroup.resolve("memory.max"), "9663676416\n");
        Files.writeString(cgroup.resolve("memory.low"), "0\n");

        assertEquals(cgroup, RecordClientGameTestTask.ownCgroup(procSelfCgroup, root.resolve("fs")));
        String previous = RecordClientGameTestTask.protectMemory(cgroup);
        assertEquals("0", previous);
        assertEquals("9663676416", Files.readString(cgroup.resolve("memory.low")));

        RecordClientGameTestTask.restoreMemoryLow(cgroup, previous);
        assertEquals("0", Files.readString(cgroup.resolve("memory.low")));
    }

    @Test
    void unlimitedCgroupIsLeftAlone(@TempDir Path cgroup) throws IOException {
        Files.writeString(cgroup.resolve("memory.max"), "max\n");
        Files.writeString(cgroup.resolve("memory.low"), "0\n");

        assertNull(RecordClientGameTestTask.protectMemory(cgroup));
        assertEquals("0\n", Files.readString(cgroup.resolve("memory.low")));
        assertNull(RecordClientGameTestTask.protectMemory(cgroup.resolve("missing")));
    }
}
