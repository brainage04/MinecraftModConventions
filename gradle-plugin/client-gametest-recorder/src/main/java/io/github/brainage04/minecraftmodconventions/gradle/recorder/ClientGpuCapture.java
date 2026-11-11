package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The display a client GameTest recording renders on.
 *
 * <p>By default the recorder starts its own {@code Xvfb} display, which has no graphics driver: the
 * client renders with Mesa's software rasterizer, a busy scene presents a handful of frames a second
 * and the capture repeats images. A recording that is given an X display which already has the card's
 * GLX (an {@code Xorg} running on the machine's graphics card, or any other server whose GLX is the
 * card's) renders on the card instead, and nothing else changes: x11grab still captures the display
 * and the frames are trimmed and muxed as before.
 *
 * <p>{@value #DISPLAY_ENV} names such a display ({@code :1}, or just {@code 1}); unset or a false
 * value keeps the recorder's own virtual display.
 */
final class ClientGpuCapture {
    /** Environment variable naming a display that already has the card's GLX; the recorder's own otherwise. */
    static final String DISPLAY_ENV = "GTR_RECORDING_DISPLAY";
    /** Gradle property that does the same as the environment variable and wins over it. */
    static final String DISPLAY_PROPERTY = "recordingDisplay";
    /** The file a host writes to declare its recording display, so a run needs no environment. */
    static final String DISPLAY_FILE = "/etc/gametest-recording-display";
    /** Environment variable naming another file to read, for a test or a host without root. */
    static final String DISPLAY_FILE_ENV = "GTR_RECORDING_DISPLAY_FILE";

    private static final List<String> SOFTWARE_VALUES = List.of("", "false", "no", "off", "disabled");

    /**
     * Minecraft's own record of what rendered the world: {@code Using graphics device: Name (Vendor)}
     * names the adapter, and the OpenGL renderer line is the fallback for a log without it.
     */
    private static final Pattern GPU_LINE = Pattern.compile(
            "(?im)^.*?Using graphics device\\s*:\\s*(.+?)\\s*(?:\\([^)]*\\))?\\s*$");
    private static final Pattern RENDERER_LINE = Pattern.compile(
            "(?im)^.*?(?:OpenGL renderer string|Backend renderer|GL_RENDERER)\\s*[:=]\\s*(.+?)\\s*$");
    /** The video driver SDL picked; a headless recording of the recorder's own display reads {@code x11}. */
    private static final Pattern SDL_DRIVER_LINE = Pattern.compile(
            "(?im)^.*?Created window using SDL video driver\\s*[: ]\\s*(\\S+)");
    /** {@code xdpyinfo}'s screen size and root depth, e.g. {@code dimensions: 1920x1080 pixels}. */
    private static final Pattern DIMENSIONS = Pattern.compile("(?m)^\\s*dimensions:\\s*([0-9]+x[0-9]+) pixels");
    private static final Pattern ROOT_DEPTH = Pattern.compile("(?m)^\\s*depth of root window:\\s*([0-9]+)");
    /** Mesa's and Microsoft's software rasterizers; anything else is a card. */
    private static final Pattern SOFTWARE_RENDERER = Pattern.compile(
            "(?i)llvmpipe|softpipe|swrast|software rasterizer|microsoft basic render");

    private ClientGpuCapture() {
    }

    /**
     * The display in force: the Gradle property, else the environment variable, else the host's file.
     * A present but blank value (or {@code 0}, {@code false}, ...) forces the recorder's virtual display.
     */
    static String resolveDisplay(String propertyValue, String environmentValue, String hostFileValue) {
        if (propertyValue != null) {
            return propertyValue.strip();
        }
        if (environmentValue != null) {
            return environmentValue.strip();
        }
        return hostFileValue == null ? "" : hostFileValue.strip();
    }

    /** A host's declaration: the display to record on, and optionally the renderer it must name. */
    record HostDisplay(String display, String expectedRenderer) {
        static final HostDisplay NONE = new HostDisplay("", "");
    }

    /**
     * The host's declaration, read from the first non-blank, non-comment line: the display, then
     * optionally the renderer it must name, as in {@code :1 NVIDIA}.
     */
    static HostDisplay readHostFile(File file) {
        if (file == null || !file.isFile()) {
            return HostDisplay.NONE;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                String[] tokens = value.split("\\s+", 2);
                return new HostDisplay(tokens[0], tokens.length > 1 ? tokens[1].strip() : "");
            }
        } catch (IOException exception) {
            return HostDisplay.NONE;
        }
        return HostDisplay.NONE;
    }

    /** The display a host's file declares, or an empty string. */
    static String displayInHostFile(File file) {
        return readHostFile(file).display();
    }

    /**
     * Why a recording on a named display must fail, or null when the client rendered on the card the
     * host expects. A missing renderer is a failure too: nothing then proves the frames came from the
     * card, and a software rasterizer produces exactly the choppy take the display was chosen to avoid.
     */
    static String rendererFailure(String glRenderer, String expectedRenderer) {
        String renderer = glRenderer == null ? "" : glRenderer.strip();
        if (renderer.isEmpty()) {
            return "the client named no graphics device, so nothing proves it rendered on the card";
        }
        if (isSoftwareRenderer(renderer)) {
            return "the client rendered with " + renderer + ", a software rasterizer";
        }
        String expected = expectedRenderer == null ? "" : expectedRenderer.strip();
        if (!expected.isEmpty() && !renderer.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
            return "the client rendered with " + renderer + " instead of the " + expected
                    + " card this host records on";
        }
        return null;
    }

    /** The X display to record, or null when the recorder starts its own virtual one. */
    static String display(String value) {
        String requested = value == null ? "" : value.strip();
        if (SOFTWARE_VALUES.contains(requested.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return requested.startsWith(":") ? requested : ":" + requested;
    }

    /** The record of the client's graphics device, or an empty string when the log has none. */
    static String rendererIn(String log) {
        String adapter = lastCapture(GPU_LINE, log);
        return adapter.isEmpty() ? lastCapture(RENDERER_LINE, log) : adapter;
    }

    /** The SDL video driver the client used, or an empty string when the log has no such line. */
    static String sdlVideoDriverIn(String log) {
        return lastCapture(SDL_DRIVER_LINE, log);
    }

    /** True when the renderer names a software rasterizer rather than a graphics card. */
    static boolean isSoftwareRenderer(String renderer) {
        return renderer != null && SOFTWARE_RENDERER.matcher(renderer).find();
    }

    /** A display's {@code WIDTHxHEIGHTxDEPTH} from {@code xdpyinfo} output, or an empty string. */
    static String screen(String xdpyinfo) {
        Matcher dimensions = DIMENSIONS.matcher(xdpyinfo == null ? "" : xdpyinfo);
        Matcher depth = ROOT_DEPTH.matcher(xdpyinfo == null ? "" : xdpyinfo);
        return dimensions.find() && depth.find() ? dimensions.group(1) + "x" + depth.group(1) : "";
    }

    /**
     * Whether a display of this screen has room for a recording at {@code standard}: every recording is
     * captured at that size, so a smaller display cannot hold one, and a larger one is captured at its
     * top left, where the recorder puts the client's window.
     */
    static boolean fits(String screen, String standard) {
        int[] size = size(screen);
        int[] wanted = size(standard);
        return size != null && wanted != null && size[0] >= wanted[0] && size[1] >= wanted[1];
    }

    /** The width and height of a {@code WIDTHxHEIGHT[xDEPTH]} screen, or null when it is not one. */
    private static int[] size(String screen) {
        String[] parts = screen == null ? new String[0] : screen.strip().split("x");
        if (parts.length < 2 || !parts[0].matches("[1-9][0-9]*") || !parts[1].matches("[1-9][0-9]*")) {
            return null;
        }
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /**
     * The presented frame rate of the recorded world, from the recorder's boundary evidence, or null
     * when the run recorded no presentation times. The first and last values bracket presents, so
     * {@code frames - 1} of them fall inside the measured window.
     */
    static Double presentedFps(Map<String, Object> evidence) {
        try {
            long frames = Long.parseLong(evidence.get("worldPresentedFrameCount").toString());
            long first = Long.parseLong(evidence.get("firstWorldPresentBeforeUs").toString());
            long last = Long.parseLong(evidence.get("lastWorldPresentBeforeUs").toString());
            if (frames < 2 || last <= first) {
                return null;
            }
            return roundToTwoDecimals((frames - 1) * 1_000_000.0 / (last - first));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String lastCapture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1).strip();
        }
        return value;
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
