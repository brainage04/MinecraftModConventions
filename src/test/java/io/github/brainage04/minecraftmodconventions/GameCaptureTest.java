package io.github.brainage04.minecraftmodconventions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        // The recorder replaces the clock in one move, but a poll that catches a missing, length-0 or part-written
        // file must wait for the next poll instead of throwing out of renderFrame.
        assertNull(GameCapture.readClock(clock));
        Files.writeString(clock, "");
        assertNull(GameCapture.readClock(clock));
        Files.writeString(clock, "1794499511733333 30 1920");
        assertNull(GameCapture.readClock(clock));
        Files.writeString(clock, "1794499511733333 3O 1920 1080");
        assertNull(GameCapture.readClock(clock));
        Files.writeString(clock, "1794499511733333 30 1920 1080\n");
        assertEquals(new GameCapture.Clock(START_US, 30, 1920, 1080), GameCapture.readClock(clock));
    }

    @Test
    void aPartialClockIsRejectedRatherThanReadAsAnotherClock() {
        assertThrows(IllegalArgumentException.class, () -> GameCapture.Clock.parse("1794499511733333 30 1920"));
        assertThrows(NumberFormatException.class, () -> GameCapture.Clock.parse("1794499511733333 3O 1920 1080"));
    }

    @Test
    void soundSamplesFollowTheCaptureClock() {
        assertEquals(0, GameCaptureAudio.SoundClock.frameIndex(START_US, START_US));
        assertEquals(48_000, GameCaptureAudio.SoundClock.frameIndex(START_US + 1_000_000, START_US));
        // A device that started rendering 2 ms before the capture: its sample 0 lies before the recording's.
        assertEquals(-96, GameCaptureAudio.SoundClock.frameIndex(START_US - 2_000, START_US));
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
