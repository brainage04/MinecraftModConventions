package io.github.brainage04.minecraftmodconventions.gradle.recorder;

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

    private static final List<String> SOFTWARE_VALUES = List.of("", "0", "false", "no", "off", "disabled");

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
