package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameCaptureTest {
    /** A capture start on whole frames of 30 fps: frame 53834985352 since the epoch. */
    private static final long START_US = 1_794_499_511_733_333L;

    @Test
    void theClockIsTheRecordersStartRateAndFrameSize() {
        GameCapture.Clock clock = GameCapture.Clock.parse("1794499511733333 30 1920 1080\n");

        assertEquals(new GameCapture.Clock(START_US, 30, 1920, 1080), clock);
        assertThrows(IllegalArgumentException.class, () -> GameCapture.Clock.parse("1794499511733333 30"));
    }

    @Test
    void aSlotShowsTheNewestFramePresentedBeforeIt() {
        // Slot k is at start + k / 30 s. A frame presented 10 ms after the start comes after slot 0 only.
        assertEquals(0, GameCapture.Clock.slotsBefore(START_US - 1, START_US, 30));
        assertEquals(1, GameCapture.Clock.slotsBefore(START_US, START_US, 30));
        assertEquals(1, GameCapture.Clock.slotsBefore(START_US + 10_000, START_US, 30));
        assertEquals(2, GameCapture.Clock.slotsBefore(START_US + 33_334, START_US, 30));
        // A frame after a 1 s stall: the 30 slots of the stall repeat the frame before it.
        assertEquals(31, GameCapture.Clock.slotsBefore(START_US + 1_000_000 + 10_000, START_US, 30));
    }

    @Test
    void slotsKeepComingWhileTheClientPresentsNothing() {
        GameCapture.Clock clock = new GameCapture.Clock(START_US, 30, 1920, 1080);

        // A new frame fills the slots before its present.
        assertEquals(31, clock.slotsToFill(START_US + 1_010_000, START_US + 1_200_000));
        // No frame for a second: the slots older than the hold repeat the frame shown, so the video and with it
        // ffmpeg's reading of the sound go on (1 s - 250 ms = 22.5 slots after slot 0).
        assertEquals(23, clock.slotsToFill(null, START_US + 1_000_000));
        assertEquals(0, clock.slotsToFill(null, START_US + GameCapture.HOLD_US - 1));
    }

    @Test
    void aPresentWaitsOnlyWhenThePresentBeforeItHoldsTheSameSlot() {
        GameCapture.Clock clock = new GameCapture.Clock(START_US, 30, 1920, 1080);

        // The first present, and one before the capture starts, never wait.
        assertEquals(START_US + 5_000, clock.presentAtUs(Long.MIN_VALUE, START_US + 5_000));
        assertEquals(START_US + 5_000, clock.presentAtUs(START_US - 1_000, START_US + 5_000));
        // A present 10 ms after the one before it, both between slots 0 and 1, waits for slot 1 (33 334 us: the first
        // whole microsecond past 1/30 s).
        assertEquals(START_US + 33_334, clock.presentAtUs(START_US + 5_000, START_US + 15_000));
        // A late frame, 40 ms after the one before it, goes at once instead of waiting for a slot middle.
        assertEquals(START_US + 45_000, clock.presentAtUs(START_US + 5_000, START_US + 45_000));
        // At 60 frames a second the next slot after 5 ms is at 16 667 us.
        assertEquals(START_US + 16_667, new GameCapture.Clock(START_US, 60, 1920, 1080).presentAtUs(START_US + 5_000,
                START_US + 6_000));
    }

    @Test
    void theClockIsLookedForEvenWhenFramesComeFasterThanThePollInterval() {
        // Frames every 33 ms against a 50 ms poll: the check comes due on the first frame, then every second one.
        GameCapture.Poll poll = new GameCapture.Poll(50_000_000L);
        int due = 0;
        for (int frame = 0; frame < 30; frame++) {
            if (poll.due(frame * 33_333_333L)) {
                due++;
            }
        }

        assertEquals(15, due);
    }

    @Test
    void aClockFileThatIsNotYetWholeIsNotAStart(@TempDir Path dir) throws IOException {
        Path clock = dir.resolve("take.clock");
        GameCapture.ClockReader reader = new GameCapture.ClockReader();

        // The recorder replaces the clock in one move, but a poll that catches a missing, length-0 or part-written
        // file must wait for the next poll instead of throwing out of renderFrame.
        assertNull(reader.read(clock));
        Files.writeString(clock, "");
        assertNull(reader.read(clock));
        Files.writeString(clock, "1794499511733333 30 1920");
        assertNull(reader.read(clock));
        Files.writeString(clock, "1794499511733333 3O 1920 1080");
        assertNull(reader.read(clock));
        assertNull(reader.takeProblem());
        Files.writeString(clock, "1794499511733333 30 1920 1080\n");
        assertEquals(new GameCapture.Clock(START_US, 30, 1920, 1080), reader.read(clock));
    }

    @Test
    void aClockThatNeverParsesIsReportedOnceWithWhatItReads(@TempDir Path dir) throws IOException {
        Path clock = dir.resolve("take.clock");
        Files.writeString(clock, "1794499511733333 3O 1920 1080\n");
        GameCapture.ClockReader reader = new GameCapture.ClockReader();

        for (int poll = 1; poll < GameCapture.ClockReader.PATIENCE; poll++) {
            assertNull(reader.read(clock));
            assertNull(reader.takeProblem(), "poll " + poll);
        }
        assertNull(reader.read(clock));
        String problem = reader.takeProblem();
        assertTrue(problem != null && problem.contains("\"1794499511733333 3O 1920 1080\""), problem);
        assertTrue(problem.contains("t0Us fps width height"), problem);

        // It keeps waiting, but says so only once.
        assertNull(reader.read(clock));
        assertNull(reader.takeProblem());
    }

    @Test
    void aPartialClockIsRejectedRatherThanReadAsAnotherClock() {
        assertThrows(IllegalArgumentException.class, () -> GameCapture.Clock.parse("1794499511733333 30 1920"));
        assertThrows(NumberFormatException.class, () -> GameCapture.Clock.parse("1794499511733333 3O 1920 1080"));
    }

    @Test
    void theCaptureClockDoesNotFollowAWallClockStep() {
        // The slots are anchored to one wall-clock reading and one monotonic one, so an NTP correction during a take
        // cannot move the video timeline against the sound's: two seconds of monotonic time is two seconds of slots.
        assertEquals(START_US, new GameCapture.Anchor(START_US, 0L).us(0L));
        assertEquals(START_US + 2_000_000L, new GameCapture.Anchor(START_US, 5_000_000L).us(2_005_000_000L));
        assertEquals(START_US + 1L, new GameCapture.Anchor(START_US, 0L).us(1_999L));
    }

    @Test
    void theSoundIsPlacedOnThePicturesAnchorNotOnAWallReadingOfItsOwn() {
        // The capture starts with its anchor at monotonic 100 s. The sound device opened 30 s earlier, at client
        // startup; a wall-clock step of +2 s in between would have put a wall reading taken at device open 2 s off
        // the picture's timeline. Placed on the picture's anchor, the device's first sample is exactly 30 s before it.
        GameCapture.Anchor anchor = new GameCapture.Anchor(START_US, 100_000_000_000L);

        assertEquals(-30L * GameCaptureAudio.SAMPLE_RATE,
                GameCaptureAudio.SoundClock.deviceStartIndex(anchor, START_US, 70_000_000_000L));
        // A capture whose first slot is 1 s after the anchor: the device's sample at the anchor is 1 s before it.
        assertEquals(-31L * GameCaptureAudio.SAMPLE_RATE,
                GameCaptureAudio.SoundClock.deviceStartIndex(anchor, START_US + 1_000_000L, 70_000_000_000L));
    }

    @Test
    void eachCaptionIsMarkedAtTheFirstSlotWrittenWithIt() throws IOException {
        GameCapture.Slots slots = new GameCapture.Slots(new GameCapture.Clock(START_US, 30, 1, 1), _ -> {});
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        ClientGameTestRecordingHud.Step a = new ClientGameTestRecordingHud.Step("stage.a", "A", "");
        ClientGameTestRecordingHud.Step b = new ClientGameTestRecordingHud.Step("stage.b", "B", "");
        ClientGameTestRecordingHud.Step c = new ClientGameTestRecordingHud.Step("stage.c", "C", "");

        // Before any caption: the first frame fills slot 0 and the next frame's present ends slot 1.
        slots.advance(frame(0, 10_000, null), START_US + 10_000, video);
        // Caption a is drawn from the frame presented at 40 ms, which fills slot 2 once the next frame comes.
        slots.advance(frame(1, 40_000, a), START_US + 40_000, video);
        slots.advance(frame(2, 75_000, a), START_US + 75_000, video);
        // A frame without the panel, then a again: a keeps the mark of its first slot.
        slots.advance(frame(3, 110_000, null), START_US + 110_000, video);
        slots.advance(frame(4, 140_000, a), START_US + 140_000, video);
        slots.advance(frame(5, 170_000, b), START_US + 170_000, video);
        // The client presents nothing for a second, so the slots older than the hold repeat b's frame (6 to 28).
        slots.advance(null, START_US + 1_200_000, video);
        // c's first frame, presented at 500 ms, reaches the writer only after its slots went to b's frame: c is
        // marked at the slot that shows it (29), not at the slot its present falls in (15).
        slots.advance(frame(6, 500_000, c), START_US + 1_200_000, video);
        slots.advance(frame(7, 1_250_000, c), START_US + 1_250_000, video);

        byte[] expected = new byte[38];
        byte[] firstSlots = {0, 0, 1, 2, 3, 4};
        System.arraycopy(firstSlots, 0, expected, 0, firstSlots.length);
        Arrays.fill(expected, 6, 29, (byte) 5);
        Arrays.fill(expected, 29, 38, (byte) 6);
        assertArrayEquals(expected, video.toByteArray());
        assertEquals(38, slots.written());
        assertEquals(22 + 8, slots.repeated());
        assertEquals("[{\"id\":\"stage.a\",\"slot\":2,\"presentUs\":" + (START_US + 40_000) + "},"
                + "{\"id\":\"stage.b\",\"slot\":6,\"presentUs\":" + (START_US + 170_000) + "},"
                + "{\"id\":\"stage.c\",\"slot\":29,\"presentUs\":" + (START_US + 500_000) + "}]",
                slots.captionMarks());
    }

    @Test
    void aCaptionMarkKeepsAnIdTheJsonWouldOtherwiseBreakOn() throws IOException {
        GameCapture.Slots slots = new GameCapture.Slots(new GameCapture.Clock(START_US, 30, 1, 1), _ -> {});
        ClientGameTestRecordingHud.Step odd = new ClientGameTestRecordingHud.Step("say \"hi\" \\ there", "T", "");

        slots.advance(frame(0, 10_000, odd), START_US + 10_000, new ByteArrayOutputStream());

        assertEquals("[{\"id\":\"say \\\"hi\\\" \\\\ there\",\"slot\":0,\"presentUs\":" + (START_US + 10_000) + "}]",
                slots.captionMarks());
    }

    private static GameCapture.Frame frame(int value, long presentAfterStartUs, ClientGameTestRecordingHud.Step caption) {
        return new GameCapture.Frame(new byte[] {(byte) value}, START_US + presentAfterStartUs, caption);
    }

    @Test
    void aFrameFormatOtherThanFourBytesAPixelFailsTheCapture() {
        assertNull(GameCapture.captureFormatProblem(4));

        String failure = GameCapture.captureFormatProblem(8);
        assertTrue(failure.contains("8 bytes a pixel"), failure);
        assertTrue(failure.contains("RGBA8"), failure);
    }

    @Test
    void aClientOnAnotherGraphicsBackendDoesNotStartAGameTake() {
        assertNull(GameCapture.backendProblem("OpenGL"));

        String vulkan = GameCapture.backendProblem("Vulkan");
        assertTrue(vulkan.contains("Vulkan"), vulkan);
        assertTrue(vulkan.contains("GTR_RECORDING_CAPTURE=x11grab"), vulkan);
        assertTrue(GameCapture.backendProblem(null).contains("no name"), GameCapture.backendProblem(null));
    }

    @Test
    void aMissingLoopbackDeviceFailsNamingItAndTheScreenGrab() {
        IllegalStateException failure = GameCaptureAudio.loopbackUnavailable();
        String message = failure.getMessage();

        // The recorder fails the take at once on this line, so it must name what is missing and the way out.
        assertTrue(message.contains("Failed to open OpenAL device"), message);
        assertTrue(message.contains("ALC_SOFT_loopback"), message);
        assertTrue(message.contains("GTR_RECORDING_CAPTURE=x11grab"), message);
    }

    @Test
    void everyCaptureFailureIsPrintedOnTheRecordersFailureLine() {
        PrintStream original = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            GameCaptureAudio.loopbackUnavailable();
            GameCapture.reportFailure("The client's frame is 1280x720, not the recording's 1920x1080");
        } finally {
            System.setErr(original);
        }

        String[] lines = printed.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals(2, lines.length, printed.toString());
        assertTrue(lines[0].startsWith(GameTestRecorderEnvironment.CAPTURE_FAILURE_LINE + "Failed to open OpenAL"),
                lines[0]);
        assertEquals(GameTestRecorderEnvironment.CAPTURE_FAILURE_LINE
                + "The client's frame is 1280x720, not the recording's 1920x1080", lines[1]);
    }

    @Test
    void soundSamplesFollowTheCaptureClock() {
        assertEquals(0, GameCaptureAudio.SoundClock.frameIndex(START_US, START_US));
        assertEquals(48_000, GameCaptureAudio.SoundClock.frameIndex(START_US + 1_000_000, START_US));
        // A device that started rendering 2 ms before the capture: its sample 0 lies before the recording's.
        assertEquals(-96, GameCaptureAudio.SoundClock.frameIndex(START_US - 2_000, START_US));
    }

    @Test
    void aSoundWriterThatDoesNotStopInTimeDoesNotSilenceTheNextDevice() throws InterruptedException {
        CountDownLatch stalled = new CountDownLatch(1);
        Semaphore release = new Semaphore(0);
        AtomicInteger stalledRenders = new AtomicInteger();
        AtomicReference<Thread> stalledThread = new AtomicReference<>();
        GameCaptureAudio.startRendering((buffer, frames) -> {
            stalledRenders.incrementAndGet();
            stalledThread.set(Thread.currentThread());
            stalled.countDown();
            release.acquireUninterruptibly();
        });
        try {
            assertTrue(stalled.await(5, TimeUnit.SECONDS));
            // A resource reload closes the device while its writer is stalled for longer than the one-second join,
            // then opens the next device, whose mix must still reach the recording.
            GameCaptureAudio.deviceClosing();
            assertTrue(stalledThread.get().isAlive());
            CountDownLatch nextRenders = new CountDownLatch(3);
            GameCaptureAudio.startRendering((buffer, frames) -> nextRenders.countDown());
            assertTrue(nextRenders.await(5, TimeUnit.SECONDS), "the next device's mix is rendered");
        } finally {
            release.release();
        }
        stalledThread.get().join(5_000L);
        GameCaptureAudio.deviceClosing();

        assertFalse(stalledThread.get().isAlive());
        // Once its write returns, the stalled writer renders nothing more from the device that closed under it.
        assertEquals(1, stalledRenders.get());
    }

    @Test
    void aWriteStalledOnAFullPipeKeepsBothDevicesSoundAtItsPlaceOnThePicturesClock() throws InterruptedException {
        byte firstDevice = 0x11;
        byte nextDevice = 0x22;
        ByteArrayOutputStream recording = new ByteArrayOutputStream();
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch nextWritten = new CountDownLatch(1);
        // ffmpeg stops reading for a moment: the first device's first write of its own sound blocks in out.write,
        // holding the write lock, and a resource reload opens the next device meanwhile.
        GameCaptureAudio.writeTo(() -> new OutputStream() {
            private boolean held;

            @Override
            public void write(int value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                if (!held && bytes[offset] == firstDevice) {
                    held = true;
                    stalled.countDown();
                    awaitUninterruptibly(release);
                }
                if (bytes[offset] == nextDevice) {
                    nextWritten.countDown();
                }
                recording.write(bytes, offset, length);
            }
        });
        GameCapture.Anchor anchor = GameCapture.Anchor.now();
        // The capture started 1 s before the first device opened, so its sound starts 48000 samples in.
        long captureStartUs = anchor.us(System.nanoTime()) - 1_000_000L;
        GameCaptureAudio.startWriting(captureStartUs, anchor);
        MarkedSource first = new MarkedSource(firstDevice);
        MarkedSource next = new MarkedSource(nextDevice);
        try {
            GameCaptureAudio.startRendering(first);
            assertTrue(stalled.await(5, TimeUnit.SECONDS), "the first device's sound reaches the recording");
            GameCaptureAudio.startRendering(next);
            assertTrue(next.rendered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(nextWritten.await(5, TimeUnit.SECONDS), "the next device's sound reaches the recording");
        } finally {
            release.countDown();
            GameCaptureAudio.deviceClosing();
            GameCaptureAudio.writeTo(null);
        }

        byte[] bytes = recording.toByteArray();
        int firstStart = frameOf(bytes, firstDevice);
        int nextStart = frameOf(bytes, nextDevice);
        // Within 50 ms: the device's start is inferred from its first render, so a busy host's pause between the
        // render loop's clock reading and the render moves it. A wrong anchor or stream position moves it by a second
        // or more.
        assertNear(first.expectedIndex(anchor, captureStartUs), firstStart, "where the first device's sound starts");
        assertNear(next.expectedIndex(anchor, captureStartUs), nextStart, "where the next device's sound starts");
        // The stalled write lands whole and first; the first device renders nothing more, and silence fills the time
        // up to the next device's first sample.
        int firstEnd = firstStart + first.firstFrames;
        for (int frame = 0; frame < bytes.length / 4; frame++) {
            byte expected = frame < firstStart || frame >= firstEnd && frame < nextStart ? 0
                    : frame < firstEnd ? firstDevice : nextDevice;
            assertEquals(expected, bytes[frame * 4], "frame " + frame);
        }
    }

    /** A device that renders one marker byte, and when it was first asked for samples. */
    private static final class MarkedSource implements GameCaptureAudio.SampleSource {
        private final byte marker;
        private final CountDownLatch rendered = new CountDownLatch(1);
        private long firstNanos;
        private int firstFrames;

        MarkedSource(byte marker) {
            this.marker = marker;
        }

        @Override
        public void render(ByteBuffer buffer, int frames) {
            if (rendered.getCount() > 0) {
                firstNanos = System.nanoTime();
                firstFrames = frames;
                rendered.countDown();
            }
            for (int i = 0; i < frames * 4; i++) {
                buffer.put(marker);
            }
        }

        /**
         * The recording's index of this device's first sample. Its first call asks for every sample due since the
         * device started, so the device started that many samples before it.
         */
        long expectedIndex(GameCapture.Anchor anchor, long captureStartUs) {
            long startNanos = firstNanos - firstFrames * 1_000_000_000L / GameCaptureAudio.SAMPLE_RATE;
            return GameCaptureAudio.SoundClock.frameIndex(anchor.us(startNanos), captureStartUs);
        }
    }

    private static void assertNear(long expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= 2400, what + ": sample " + actual + ", expected " + expected);
    }

    private static int frameOf(byte[] bytes, byte marker) {
        for (int frame = 0; frame < bytes.length / 4; frame++) {
            if (bytes[frame * 4] == marker) {
                return frame;
            }
        }
        throw new AssertionError("no frame of " + marker);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aLoopbackContextAsksForTheRecordingsFormatAndKeepsTheGamesAttributes() {
        // Library.createAttributes's output: output limiter on (6554 = ALC_OUTPUT_LIMITER_SOFT), zero-terminated.
        IntBuffer game = IntBuffer.wrap(new int[] {6554, 1, 0});
        IntBuffer memory = IntBuffer.allocate(GameCaptureAudio.loopbackAttributeCapacity(game));

        IntBuffer attributes = GameCaptureAudio.loopbackAttributes(game, memory);

        int[] values = new int[attributes.remaining()];
        attributes.get(values);
        // ALC_FORMAT_CHANNELS_SOFT stereo, ALC_FORMAT_TYPE_SOFT short, ALC_FREQUENCY 48 kHz.
        assertArrayEquals(new int[] {0x1990, 0x1501, 0x1991, 0x1402, 0x1007, 48_000, 6554, 1, 0}, values);
    }
}
