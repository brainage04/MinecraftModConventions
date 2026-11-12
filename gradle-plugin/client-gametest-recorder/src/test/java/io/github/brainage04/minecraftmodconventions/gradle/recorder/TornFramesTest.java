package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TornFramesTest {
    /** Frames 170-178 of the AutoSleep rec3 take at 480x270, lossless; its frames 173 and 174 are torn. */
    private static final String FIXTURE = "/torn-frames-autosleep-rec3-170-178.mp4";

    /**
     * Frames 1650-1660 of the MagicCarpet carpetsound3 raw capture at 480x270, lossless, in the raw capture's 1/1000
     * time base at 60 frames a second; its frame 1655 has a low-contrast tear near the bottom (row 234 of 270).
     */
    private static final String CARPET_FIXTURE = "/torn-frames-magiccarpet-carpetsound3-1650-1660.mp4";

    private static byte[] mask(int tearRow, int topValue, int bottomValue) {
        byte[] mask = new byte[TornFrames.WIDTH * TornFrames.ROWS];
        for (int y = 40; y < 200; y++) {
            Arrays.fill(mask, y * TornFrames.WIDTH, y * TornFrames.WIDTH + 100,
                    (byte) (y < tearRow ? topValue : bottomValue));
        }
        return mask;
    }

    @Test
    void aFrameWhoseTopShowsThePreviousFrameAndWhoseBottomShowsTheNextIsTorn() {
        assertEquals(120, TornFrames.tearRow(mask(120, TornFrames.PREVIOUS, TornFrames.NEXT)));
    }

    @Test
    void theOtherOrderIsNotATearBecauseTheCaptureCopiesFromTheTopDown() {
        assertEquals(-1, TornFrames.tearRow(mask(120, TornFrames.NEXT, TornFrames.PREVIOUS)));
    }

    @Test
    void aFrameWithContentOfItsOwnIsNotTorn() {
        byte[] mask = mask(120, TornFrames.PREVIOUS, TornFrames.NEXT);
        // An object that moved up the screen: the frame shows it where neither neighbour does.
        for (int y = 115; y < 125; y++) {
            Arrays.fill(mask, y * TornFrames.WIDTH + 200, y * TornFrames.WIDTH + 300, (byte) TornFrames.OWN);
        }
        assertEquals(-1, TornFrames.tearRow(mask));
    }

    @Test
    void aStillOrRepeatedFrameIsNotTorn() {
        assertEquals(-1, TornFrames.tearRow(new byte[TornFrames.WIDTH * TornFrames.ROWS]));
        assertEquals(-1, TornFrames.tearRow(mask(300, TornFrames.PREVIOUS, TornFrames.PREVIOUS)));
    }

    @Test
    void eachRunOfTornFramesTakesTheFrameBeforeIt() {
        assertEquals("[in]null[out]", TornFrames.repairFilter("in", "out", List.of()));
        assertEquals("[in]split=3[repair0][source1][source2];"
                        + "[repair0][source1]freezeframes=first=3:last=4:replace=2[repair1];"
                        + "[repair1][source2]freezeframes=first=9:last=9:replace=8[out]",
                TornFrames.repairFilter("in", "out", List.of(3, 4, 9)));
        assertEquals("[in]split=2[repair0][source1];[repair0][source1]freezeframes=first=0:last=1:replace=2[out]",
                TornFrames.repairFilter("in", "out", List.of(0, 1)));
    }

    @Test
    void theAutoSleepTakeIsTornAtFrames173And174AndTheRepairLeavesNone(@TempDir Path directory)
            throws IOException, InterruptedException, URISyntaxException {
        assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        String fixture = Path.of(TornFramesTest.class.getResource(FIXTURE).toURI()).toString();

        List<Integer> torn = torn(fixture);
        assertEquals(List.of(3, 4), torn, "rec3 frames 173 and 174");

        Path repaired = directory.resolve("repaired.mkv");
        run(List.of("ffmpeg", "-v", "error", "-i", fixture, "-filter_complex",
                TornFrames.repairFilter("0:v", "video", torn), "-map", "[video]", "-c:v", "ffv1",
                repaired.toString()));
        assertEquals(List.of(), torn(repaired.toString()));
    }

    @Test
    void aTearInAOneMillisecondTimeBaseIsFoundAtItsOwnFrame() throws IOException, InterruptedException, URISyntaxException {
        assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        String fixture = Path.of(TornFramesTest.class.getResource(CARPET_FIXTURE).toURI()).toString();

        assertEquals(List.of(5), torn(fixture), "carpetsound3 raw frame 1655");
    }

    private static List<Integer> torn(String video) throws IOException, InterruptedException {
        Process ffmpeg = new ProcessBuilder(TornFrames.maskCommand(video))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        List<Integer> torn = TornFrames.find(ffmpeg.getInputStream());
        assertEquals(0, ffmpeg.waitFor());
        return torn;
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor(), String.join(" ", command));
    }

    static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder(new ArrayList<>(List.of("ffmpeg", "-version")))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }
}
