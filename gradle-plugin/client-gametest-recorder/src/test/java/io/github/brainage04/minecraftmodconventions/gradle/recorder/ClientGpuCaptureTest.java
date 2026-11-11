package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        for (String off : new String[] {"", "  ", "0", "false", "no", "OFF", null}) {
            assertNull(ClientGpuCapture.display(off), "value " + off);
        }
        assertEquals(":1", ClientGpuCapture.display(":1"));
        assertEquals(":1", ClientGpuCapture.display(" 1 "));
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
}
