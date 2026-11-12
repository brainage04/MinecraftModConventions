package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
                "30", "1920x1080", ":90", "sink.monitor", "progress.txt", "audio.txt", "raw.mkv", 0, 0,
                RecordClientGameTestTask.videoEncoder("libx264"));

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
    void theGameCaptureHoldsFewRawFramesSoItsInputQueueStaysSmall() {
        List<String> command = RecordClientGameTestTask.gameCaptureCommand(30, "1920x1080", 53_834_985_352L,
                "video.fifo", "audio.fifo", "progress.txt", "audio.txt", "raw.mkv",
                RecordClientGameTestTask.videoEncoder("libx264"));

        // One rawvideo packet is one whole frame, so the video input's queue bounds what a slow encoder holds:
        // four 1920x1080 RGBA frames are 33 MB, where ffmpeg's 64 would be over 500 MB.
        int videoInput = command.indexOf("-i");
        int audioInput = command.lastIndexOf("-i");
        int videoQueue = command.subList(0, videoInput).lastIndexOf("-thread_queue_size");
        assertEquals(4, Integer.parseInt(command.get(videoQueue + 1)));
        // The sound input keeps its queue: one packet there is a few milliseconds of samples.
        int audioQueue = command.subList(videoInput, audioInput).lastIndexOf("-thread_queue_size");
        assertEquals(1024, Integer.parseInt(command.get(audioQueue + videoInput + 1)));
    }

    @Test
    void theScreenGrabHasNoVideoInputQueueToGrow() {
        List<String> command = RecordClientGameTestTask.captureCommand(
                "30", "1920x1080", ":90", "sink.monitor", "progress.txt", "audio.txt", "raw.mkv", 0, 0,
                RecordClientGameTestTask.videoEncoder("libx264"));

        assertFalse(command.subList(0, command.indexOf("-i")).contains("-thread_queue_size"),
                "x11grab's own queue must stay at ffmpeg's default: " + command);
    }

    @Test
    void theCaptureStartsAtTheClientsWindowRatherThanTheDisplayOrigin() {
        List<String> command = RecordClientGameTestTask.captureCommand(
                "30", "1920x1080", ":1", "sink.monitor", "progress.txt", "audio.txt", "raw.mkv", 320, 180,
                RecordClientGameTestTask.videoEncoder("libx264"));

        assertEquals(":1.0+320,180", command.get(command.indexOf("-i") + 1));
        assertEquals("1920x1080", command.get(command.indexOf("-video_size") + 1));
    }

    @Test
    void aRecordingCapturesTheGameUnlessAskedForAScreenGrab() {
        assertTrue(RecordClientGameTestTask.capturesGame("game"));
        assertFalse(RecordClientGameTestTask.capturesGame("x11grab"));
        assertThrows(GradleException.class, () -> RecordClientGameTestTask.capturesGame("pipewire"));
    }

    @Test
    void theGameCaptureStartsOnAWholeFrameOfTheEpoch() {
        assertEquals(53_834_985_352L, RecordClientGameTestTask.captureStartFrames(1_794_499_511_700_001L, 30));
        assertEquals(53_834_985_351L, RecordClientGameTestTask.captureStartFrames(1_794_499_511_700_000L, 30));
    }

    @Test
    void theGameCaptureShiftsBothPipesToTheEpochInWholeFramesAndSamples() {
        List<String> command = RecordClientGameTestTask.gameCaptureCommand(30, "1920x1080", 53_834_985_352L,
                "video.fifo", "audio.fifo", "progress.txt", "audio.txt", "raw.mkv",
                RecordClientGameTestTask.videoEncoder("h264_nvenc"));

        assertEquals("video.fifo", command.get(command.indexOf("-i") + 1));
        assertEquals("audio.fifo", command.get(command.lastIndexOf("-i") + 1));
        assertEquals("rgba", command.get(command.indexOf("-pix_fmt") + 1));
        // The GPU reads a frame back bottom row first.
        assertEquals("vflip,setpts=PTS+53834985352", command.get(command.indexOf("-vf") + 1));
        // 48 000 / 30 = 1600 samples a frame.
        assertEquals("asetpts=PTS+" + 53_834_985_352L * 1600, command.get(command.indexOf("-af") + 1));
        assertEquals("h264_nvenc", command.get(command.indexOf("-c:v") + 1));
        assertEquals("raw.mkv", command.getLast());
    }

    @Test
    void theCaptureEncodesOnTheCardsEncoderWhenAskedAndOnTheCpuOtherwise() {
        List<String> nvenc = RecordClientGameTestTask.captureCommand(
                "60", "1920x1080", ":1", "sink.monitor", "progress.txt", "audio.txt", "raw.mkv", 0, 0,
                RecordClientGameTestTask.videoEncoder("h264_nvenc"));

        assertEquals("h264_nvenc", nvenc.get(nvenc.indexOf("-c:v") + 1));
        // NVENC converts x11grab's BGR frames itself, so the CPU does no pixel format conversion.
        assertFalse(nvenc.contains("-pix_fmt"));
        assertEquals("libx264", RecordClientGameTestTask.videoEncoder("libx264").get(1));
        assertThrows(GradleException.class, () -> RecordClientGameTestTask.videoEncoder("hevc_vaapi"));
    }

    @Test
    void theEncoderProbeEncodesOneFrameWithTheCapturesOwnOptions() {
        List<String> encoder = RecordClientGameTestTask.videoEncoder("h264_nvenc");
        List<String> probe = RecordClientGameTestTask.encoderProbeCommand(encoder);

        assertEquals("ffmpeg", probe.getFirst());
        assertEquals("1", probe.get(probe.indexOf("-frames:v") + 1));
        assertEquals(encoder, probe.subList(probe.indexOf("-c:v"), probe.indexOf("-c:v") + encoder.size()));
        assertEquals(List.of("-f", "null", "-"), probe.subList(probe.size() - 3, probe.size()));
    }

    @Test
    void aHostWithoutTheEncoderFailsNamingItBeforeTheClientStarts() {
        assertNull(RecordClientGameTestTask.encoderProbeFailure("h264_nvenc", 0, ""));
        String failure = RecordClientGameTestTask.encoderProbeFailure("h264_nvenc", 187,
                "[h264_nvenc @ 0x5581] Cannot load libcuda.so.1\n");

        assertTrue(failure.contains("cannot encode with h264_nvenc"), failure);
        assertTrue(failure.contains("Cannot load libcuda.so.1"), failure);
        assertTrue(failure.contains("the client was not started"), failure);
        assertTrue(failure.contains("Unset GTR_RECORDING_VIDEO_ENCODER"), failure);
    }

    @Test
    void theProbeFailsOnAnEncoderFfmpegLacksAndPassesOnLibx264() throws IOException, InterruptedException {
        assumeTrue(TornFramesTest.ffmpegAvailable(), "ffmpeg is not installed");
        Process missing = new ProcessBuilder(RecordClientGameTestTask.encoderProbeCommand(
                List.of("-c:v", "no_such_encoder"))).redirectErrorStream(true).start();
        String output = new String(missing.getInputStream().readAllBytes());
        int status = missing.waitFor();
        String failure = RecordClientGameTestTask.encoderProbeFailure("no_such_encoder", status, output);
        assertTrue(failure != null && failure.contains("no_such_encoder"), output);

        Process libx264 = new ProcessBuilder(RecordClientGameTestTask.encoderProbeCommand(
                RecordClientGameTestTask.videoEncoder("libx264"))).redirectErrorStream(true).start();
        String libx264Output = new String(libx264.getInputStream().readAllBytes());
        assertEquals(0, libx264.waitFor(), libx264Output);
    }

    @Test
    void aCaptureAbove30FramesASecondFailsOnTheVirtualDisplayBeforeTheClientStarts() {
        String failure = RecordClientGameTestTask.captureRateFailure(60, null, "display :1 is in use by another recording");

        assertTrue(failure.contains("GTR_RECORDING_FPS=60 needs a recording display on the card"), failure);
        assertTrue(failure.contains("display :1 is in use by another recording"), failure);
        assertTrue(failure.contains("The client was not started"), failure);
        assertNull(RecordClientGameTestTask.captureRateFailure(60, ":1", null));
        assertNull(RecordClientGameTestTask.captureRateFailure(30, null, null));
    }

    @Test
    void onlyACardTakeIsCheckedForTornFrames() {
        AtomicBoolean checked = new AtomicBoolean();
        assertEquals(List.of(), RecordClientGameTestTask.tornFramesToReplace(false, 10, 100, () -> {
            checked.set(true);
            return List.of(15);
        }));
        assertFalse(checked.get(), "the virtual display's take must not run the mask pass");

        // Raw frames 5 and 120 lie outside the kept range [10, 100); raw frame 15 is frame 5 of the saved video.
        assertEquals(List.of(5, 89), RecordClientGameTestTask.tornFramesToReplace(true, 10, 100,
                () -> List.of(5, 15, 99, 120)));
    }

    @Test
    void aTornFrameLeftAfterTheRepairFailsUnlessTheCheckIsSetToWarn() {
        assertTrue(RecordClientGameTestTask.failsOnTornFrames("fail"));
        assertFalse(RecordClientGameTestTask.failsOnTornFrames("warn"));
        assertThrows(GradleException.class, () -> RecordClientGameTestTask.failsOnTornFrames("off"));
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
