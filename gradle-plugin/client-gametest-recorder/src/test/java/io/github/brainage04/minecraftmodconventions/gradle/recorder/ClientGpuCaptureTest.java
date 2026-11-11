package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGpuCaptureTest {
    /** The lines a 26.3 client writes before its window exists, trimmed to what the recorder reads. */
    private static final String CLIENT_LOG = String.join("\n",
            "[21:15:14] [Render thread/INFO]: Backend library: LWJGL version 3.4.3-snapshot",
            "[21:15:15] [Render thread/INFO]: Using graphics backend OpenGL, using drivers: 3.3.0 NVIDIA 580.178.04",
            "[21:15:15] [Render thread/INFO]: Using graphics device: NVIDIA GeForce GTX 1060 (NVIDIA)",
            "[21:15:15] [Render thread/INFO]: Created window using SDL video driver: x11",
            "");

    @Test
    void aDisplayIsOnlyUsedWhenOneIsNamed() {
        for (String off : new String[] {"", "  ", "false", "no", "OFF", null}) {
            assertNull(ClientGpuCapture.display(off), "value " + off);
        }
        assertEquals(":1", ClientGpuCapture.display(":1"));
        assertEquals(":1", ClientGpuCapture.display(" 1 "));
        // :0 is the machine's own desktop display, and a runner may leave a host file line reading "0".
        assertEquals(":0", ClientGpuCapture.display("0"));
        assertEquals(":0", ClientGpuCapture.display(":0"));
    }

    @Test
    void theClientsWindowComesFromTheRectItReports() {
        assertArrayEquals(new int[] {320, 180, 1920, 1080}, ClientGpuCapture.windowRect("320 180 1920 1080\n"));
        assertNull(ClientGpuCapture.windowRect(""));
        assertNull(ClientGpuCapture.windowRect("320 180 1920"));
        assertNull(ClientGpuCapture.windowRect("x 180 1920 1080"));
    }

    @Test
    void aCentredWindowOnALargerDisplayIsCapturedWhereItSits() {
        // Vanilla centres a 1920x1080 window on a 2560x1440 display at +320,+180 (Window.java:118-119).
        assertArrayEquals(
                new int[] {320, 180},
                ClientGpuCapture.grabOffset("2560x1440x24", "1920x1080x24", new int[] {320, 180, 1920, 1080}));
        assertArrayEquals(
                new int[] {0, 0},
                ClientGpuCapture.grabOffset("1920x1080x24", "1920x1080x24", new int[] {0, 0, 1920, 1080}));
    }

    @Test
    void aWindowThatSticksOutOrIsNotTheStandardSizeFailsTheRecording() {
        IllegalArgumentException off = assertThrows(IllegalArgumentException.class, () ->
                ClientGpuCapture.grabOffset("2560x1440x24", "1920x1080x24", new int[] {700, 400, 1920, 1080}));
        assertTrue(off.getMessage().contains("does not lie wholly"), off.getMessage());

        IllegalArgumentException size = assertThrows(IllegalArgumentException.class, () ->
                ClientGpuCapture.grabOffset("2560x1440x24", "1920x1080x24", new int[] {480, 270, 1600, 900}));
        assertTrue(size.getMessage().contains("not the standard"), size.getMessage());

        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () ->
                ClientGpuCapture.grabOffset("2560x1440x24", "1920x1080x24", null));
        assertTrue(absent.getMessage().contains("did not report"), absent.getMessage());
    }

    @Test
    void aCardDisplayMustHoldTheStandardRecordingSize() {
        String standard = "1920x1080x24";
        assertTrue(ClientGpuCapture.fits("1920x1080x24", standard), "the standard itself fits");
        assertTrue(ClientGpuCapture.fits("2560x1440x24", standard), "a larger display is cropped to the standard");
        assertTrue(ClientGpuCapture.fits("3840x2160x24", standard), "a 4K display is cropped too");
        assertFalse(ClientGpuCapture.fits("1280x720x24", standard), "a smaller display has no room for it");
        assertFalse(ClientGpuCapture.fits("1920x900x24", standard), "a display short of 1080 fits nothing");
        assertFalse(ClientGpuCapture.fits("", standard), "an unreadable size fits nothing");
    }

    @Test
    void graphicsDeviceAndVideoDriverComeFromTheClientLog() {
        assertEquals("NVIDIA GeForce GTX 1060", ClientGpuCapture.rendererIn(CLIENT_LOG));
        assertEquals("x11", ClientGpuCapture.sdlVideoDriverIn(CLIENT_LOG));
        assertFalse(ClientGpuCapture.isSoftwareRenderer(ClientGpuCapture.rendererIn(CLIENT_LOG)));

        String software = String.join("\n",
                "[1] [Render thread/INFO]: OpenGL renderer string: llvmpipe (LLVM 21.1.8, 256 bits)",
                "[1] [Render thread/INFO]: Created window using SDL video driver: x11",
                "");
        assertEquals("llvmpipe (LLVM 21.1.8, 256 bits)", ClientGpuCapture.rendererIn(software));
        assertTrue(ClientGpuCapture.isSoftwareRenderer(ClientGpuCapture.rendererIn(software)));
        assertEquals("x11", ClientGpuCapture.sdlVideoDriverIn(software));

        assertEquals("", ClientGpuCapture.rendererIn("no graphics lines"));
        assertEquals("", ClientGpuCapture.sdlVideoDriverIn(""));
    }

    @Test
    void softwareRasterizersAreNotCards() {
        assertTrue(ClientGpuCapture.isSoftwareRenderer("llvmpipe (LLVM 21.1.8, 256 bits)"));
        assertTrue(ClientGpuCapture.isSoftwareRenderer("softpipe (LLVM 21.1.8, 256 bits)"));
        assertTrue(ClientGpuCapture.isSoftwareRenderer("Microsoft Basic Render Driver"));
        assertFalse(ClientGpuCapture.isSoftwareRenderer("AMD Radeon RX 6600 (RADV NAVI23)"));
        assertFalse(ClientGpuCapture.isSoftwareRenderer(""));
        assertFalse(ClientGpuCapture.isSoftwareRenderer(null));
    }

    @Test
    void theScreenSizeComesFromXdpyinfo() {
        String xdpyinfo = String.join("\n",
                "name of display: :1",
                "version number: 11.0",
                "maximum request size: 16777212 bytes",
                "  dimensions:    1920x1080 pixels (508x285 millimeters)",
                "  resolution:    96x96 dots per inch",
                "depth of root window:    24 planes",
                "");
        assertEquals("1920x1080x24", ClientGpuCapture.screen(xdpyinfo));
        assertEquals("", ClientGpuCapture.screen("Error: unable to open display :1"));
        assertEquals("", ClientGpuCapture.screen(""));
    }

    @Test
    void presentedFpsCountsThePresentsBetweenTheFirstAndLastBracket() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("worldPresentedFrameCount", "851");
        evidence.put("firstWorldPresentBeforeUs", "1794230513556848");
        evidence.put("lastWorldPresentBeforeUs", "1794230566819270");
        assertEquals(15.96, ClientGpuCapture.presentedFps(evidence));

        Map<String, Object> single = Map.of(
                "worldPresentedFrameCount", "1",
                "firstWorldPresentBeforeUs", "1",
                "lastWorldPresentBeforeUs", "2");
        assertNull(ClientGpuCapture.presentedFps(single));
        assertNull(ClientGpuCapture.presentedFps(Map.of()));
    }

    @Test
    void theGradlePropertyWinsOverTheEnvironmentAndTheHostFile() {
        assertEquals(":2", ClientGpuCapture.resolveDisplay(":2", ":1", ":3\n"));
        // A present but blank value is an explicit choice of the recorder's own virtual display.
        assertEquals("", ClientGpuCapture.resolveDisplay("", ":1", ":3\n"));
        assertEquals("", ClientGpuCapture.resolveDisplay(null, "", ":3\n"));
    }

    @Test
    void theEnvironmentSpeaksWhenNoPropertyIsSet() {
        assertEquals(":1", ClientGpuCapture.resolveDisplay(null, ":1", ":3\n"));
        assertEquals("0", ClientGpuCapture.resolveDisplay(null, "0", ":3\n"));
    }

    @Test
    void theHostFileSpeaksWhenNothingElseDoes() {
        assertEquals(":1", ClientGpuCapture.resolveDisplay(null, null, ":1\n"));
        assertEquals("", ClientGpuCapture.resolveDisplay(null, null, null));
        assertEquals("", ClientGpuCapture.resolveDisplay(null, null, ""));
    }

    @Test
    void aHostFileNamesItsDisplayOnItsFirstNonCommentLine(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("gametest-recording-display");
        Files.writeString(file, "# the card's Xorg\n\n:1\n:2\n");

        assertEquals(":1", ClientGpuCapture.displayInHostFile(file.toFile()));
        assertEquals("", ClientGpuCapture.displayInHostFile(directory.resolve("missing").toFile()));
        assertEquals("", ClientGpuCapture.displayInHostFile(null));
    }

    @Test
    void aHostFileWhoseDisplayIsFalseChoosesTheVirtualDisplay() {
        assertEquals("false", ClientGpuCapture.resolveDisplay(null, null, "false\n"));
        assertNull(ClientGpuCapture.display(ClientGpuCapture.resolveDisplay(null, null, "false\n")));
    }

    @Test
    void aHostFileMayNameTheRendererItMustSee(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("gametest-recording-display");
        Files.writeString(file, "# the card's Xorg\n:1 NVIDIA\n:2\n");

        ClientGpuCapture.HostDisplay declared = ClientGpuCapture.readHostFile(file.toFile());

        assertEquals(":1", declared.display());
        assertEquals("NVIDIA", declared.expectedRenderer());
        assertEquals("", ClientGpuCapture.readHostFile(directory.resolve("missing").toFile()).display());
    }

    @Test
    void aRecordingOnADisplayFailsUnlessTheCardRenderedIt() {
        assertNull(ClientGpuCapture.rendererFailure("NVIDIA GeForce GTX 1070 (NVIDIA)", "NVIDIA"));
        assertNull(ClientGpuCapture.rendererFailure("AMD Radeon RX 6700", ""));

        assertNotNull(ClientGpuCapture.rendererFailure("llvmpipe (LLVM 18.1.8, 256 bits)", "NVIDIA"),
                "a software rasterizer is not the card");
        assertNotNull(ClientGpuCapture.rendererFailure("", "NVIDIA"), "a missing renderer proves nothing");
        assertNotNull(ClientGpuCapture.rendererFailure("NVIDIA GeForce GTX 1070 (NVIDIA)", "Intel"),
                "another card than the host declares");
    }
}
