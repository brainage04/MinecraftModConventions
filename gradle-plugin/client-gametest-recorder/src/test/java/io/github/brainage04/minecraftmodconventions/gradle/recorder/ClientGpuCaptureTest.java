package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            "[21:15:15] [Render thread/ERROR]: Failed to create backend OpenGL",
            "[21:15:15] [Render thread/INFO]: Using graphics backend Vulkan, using drivers: 1.4.312 NVIDIA 580.178.04",
            "[21:15:15] [Render thread/INFO]: Using graphics device: NVIDIA GeForce GTX 1060 (NVIDIA)",
            "[21:15:15] [Render thread/INFO]: Created window using SDL video driver: wayland",
            "");

    @Test
    void requestIsOffUnlessItNamesADevice() {
        for (String off : new String[] {"", "  ", "0", "false", "no", "OFF", null}) {
            assertNull(ClientGpuCapture.device(off), "value " + off);
        }
        for (String egl : new String[] {"1", "true", "yes", "on", "egl", " EGL "}) {
            assertEquals(ClientGpuCapture.EGL_DEVICE, ClientGpuCapture.device(egl), "value " + egl);
        }
        assertEquals("/dev/dri/renderD128", ClientGpuCapture.device(" /dev/dri/renderD128 "));
    }

    @Test
    void graphicsDeviceAndVideoDriverComeFromTheClientLog() {
        assertEquals("NVIDIA GeForce GTX 1060", ClientGpuCapture.rendererIn(CLIENT_LOG));
        assertEquals("wayland", ClientGpuCapture.sdlVideoDriverIn(CLIENT_LOG));
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
    void aHostWithoutADeviceNodeIsNotACardHost(@TempDir Path root) throws IOException {
        Path renderNodes = Files.createDirectories(root.resolve("dri"));
        Path deviceNodes = Files.createDirectories(root.resolve("dev"));
        assertFalse(ClientGpuCapture.hasGraphicsDevice(renderNodes.toFile(), deviceNodes.toFile()));

        Files.createFile(deviceNodes.resolve("nvidia0"));
        assertTrue(ClientGpuCapture.hasGraphicsDevice(renderNodes.toFile(), deviceNodes.toFile()));

        Files.delete(deviceNodes.resolve("nvidia0"));
        Files.createFile(renderNodes.resolve("card1"));
        assertFalse(
                ClientGpuCapture.hasGraphicsDevice(renderNodes.toFile(), deviceNodes.toFile()),
                "a card node alone cannot render");
        Files.createFile(renderNodes.resolve("renderD128"));
        assertTrue(ClientGpuCapture.hasGraphicsDevice(renderNodes.toFile(), deviceNodes.toFile()));

        assertFalse(ClientGpuCapture.hasGraphicsDevice(root.resolve("missing").toFile(), root.resolve("also-missing").toFile()));
    }

    @Test
    void virtualGlEnvironmentIsReadFromTheLauncher() {
        Map<String, String> environment = ClientGpuCapture.environmentOf(String.join("\n",
                "[VGL] NOTICE: Added /vgl/lib to LD_LIBRARY_PATH",
                "LD_PRELOAD=/vgl/lib/libdlfaker.so:/vgl/lib/libvglfaker.so",
                "VGL_DISPLAY=egl",
                "LD_LIBRARY_PATH=/vgl/lib:/run/opengl-driver/lib",
                "PATH=/usr/bin",
                ""));

        assertEquals(Map.of(
                "LD_PRELOAD", "/vgl/lib/libdlfaker.so:/vgl/lib/libvglfaker.so",
                "VGL_DISPLAY", "egl",
                "LD_LIBRARY_PATH", "/vgl/lib:/run/opengl-driver/lib"), environment);
    }

    @Test
    void captureEnvironmentKeepsTheInterposerOut() {
        Map<String, String> environment = new LinkedHashMap<>(Map.of(
                "LD_PRELOAD", "/vgl/lib/libvglfaker.so",
                "VGL_DISPLAY", "egl",
                "LD_LIBRARY_PATH", "/vgl/lib",
                "PULSE_SINK", "gametest_recorder_1"));
        environment.put("LD_PRELOAD", "/vgl/lib/libvglfaker.so");

        Map<String, String> capture = ClientGpuCapture.withoutGpuEnvironment(environment);

        assertEquals("/vgl/lib", capture.get("LD_LIBRARY_PATH"));
        assertEquals("gametest_recorder_1", capture.get("PULSE_SINK"));
        assertFalse(capture.containsKey("LD_PRELOAD"));
        assertFalse(capture.containsKey("VGL_DISPLAY"));
        assertEquals("/vgl/lib/libvglfaker.so", environment.get("LD_PRELOAD"));
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
