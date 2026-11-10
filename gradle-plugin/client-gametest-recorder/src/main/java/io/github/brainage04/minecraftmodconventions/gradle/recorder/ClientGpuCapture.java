package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The opt-in GPU render path of a client GameTest recording.
 *
 * <p>By default the client renders into the recorder's own {@code Xvfb} display with Mesa's software
 * rasterizer, and x11grab captures that display. A busy scene then presents a handful of frames a
 * second: the capture repeats images, and a mod that films an animation has to slow the game down and
 * time-lapse the result to keep the video smooth.
 *
 * <p>With this path VirtualGL's faker library replaces the client's GLX calls, so the frame is
 * rendered on the machine's own card through EGL and copied into the same {@code Xvfb} display. The
 * capture, the world-boundary trimming and the isolated audio route stay exactly as they are, and the
 * client presents as fast as the card does.
 *
 * <p>{@value #DEVICE_ENV} asks for it: unset or a false value keeps the software path, {@code 1} (or
 * {@code true}) selects VirtualGL's {@code egl} device, and any other value is passed to
 * {@code vglrun -d} as it stands (for example {@code /dev/dri/renderD128}).
 */
final class ClientGpuCapture {
    /** Environment variable that asks for GPU rendering; the software path otherwise. */
    static final String DEVICE_ENV = "GTR_RECORDING_GPU";
    /** Environment variable naming the VirtualGL launcher; {@code vglrun} on the {@code PATH} otherwise. */
    static final String LAUNCHER_ENV = "GTR_RECORDING_VGLRUN";
    /** The VirtualGL device value that means "the EGL device the driver offers". */
    static final String EGL_DEVICE = "egl";

    private static final List<String> SOFTWARE_VALUES = List.of("", "0", "false", "no", "off", "disabled");
    private static final List<String> EGL_VALUES = List.of("1", "true", "yes", "on", "egl");

    /**
     * Minecraft's own record of what rendered the world: {@code Using graphics device: Name (Vendor)}
     * names the adapter, and the OpenGL renderer line is the fallback for a log without it.
     */
    private static final Pattern GPU_LINE = Pattern.compile(
            "(?im)^.*?Using graphics device\\s*:\\s*(.+?)\\s*(?:\\([^)]*\\))?\\s*$");
    private static final Pattern RENDERER_LINE = Pattern.compile(
            "(?im)^.*?(?:OpenGL renderer string|Backend renderer|GL_RENDERER)\\s*[:=]\\s*(.+?)\\s*$");
    /** The video driver SDL picked; a headless recording must read {@code x11}. */
    private static final Pattern SDL_DRIVER_LINE = Pattern.compile(
            "(?im)^.*?Created window using SDL video driver\\s*[: ]\\s*(\\S+)");
    /** Mesa's and Microsoft's software rasterizers; anything else is a card. */
    private static final Pattern SOFTWARE_RENDERER = Pattern.compile(
            "(?i)llvmpipe|softpipe|swrast|software rasterizer|microsoft basic render");

    private final String requested;
    private final String device;
    private final String launcher;
    private final Map<String, String> environment;

    ClientGpuCapture(String requested, String device, String launcher, Map<String, String> environment) {
        this.requested = requested;
        this.device = device;
        this.launcher = launcher;
        this.environment = Map.copyOf(environment);
    }

    /**
     * The VirtualGL device to render on, or null when the software path was asked for. Disabling
     * values return null so that a job can unset the request with a parameter override.
     */
    static String device(String value) {
        String requested = value == null ? "" : value.strip();
        String normalized = requested.toLowerCase(Locale.ROOT);
        if (SOFTWARE_VALUES.contains(normalized)) {
            return null;
        }
        return EGL_VALUES.contains(normalized) ? EGL_DEVICE : requested;
    }

    /** The value the request came in as, for the metadata. */
    String requested() {
        return requested;
    }

    String device() {
        return device;
    }

    String launcher() {
        return launcher;
    }

    /**
     * The variables the VirtualGL launcher adds for this device ({@code LD_PRELOAD},
     * {@code LD_LIBRARY_PATH}, {@code VGL_*}). They are read from the launcher itself rather than
     * restated here, so a new VirtualGL release keeps working.
     */
    Map<String, String> environment() {
        return environment;
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

    /**
     * True when the host exposes a graphics device: a DRM render node, or an NVIDIA device node on a
     * driver that predates one. A host without one records on the software path instead of failing
     * the run; a host whose card exists but cannot render fails loudly in the client's own log.
     */
    static boolean hasGraphicsDevice() {
        return hasGraphicsDevice(new File("/dev/dri"), new File("/dev"));
    }

    static boolean hasGraphicsDevice(File renderNodes, File deviceNodes) {
        return lists(renderNodes, "renderD") || lists(deviceNodes, "nvidia");
    }

    private static boolean lists(File directory, String prefix) {
        String[] names = directory.list();
        return names != null && Stream.of(names).anyMatch(name -> name.startsWith(prefix));
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

    /** The variables of interest in a {@code printf 'NAME=value'} report from inside the launcher. */
    static Map<String, String> environmentOf(String report) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String line : (report == null ? "" : report).split("\\R")) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (!value.isEmpty() && (name.startsWith("VGL_") || name.equals("LD_PRELOAD") || name.equals("LD_LIBRARY_PATH"))) {
                environment.put(name, value);
            }
        }
        return environment;
    }

    /**
     * The variables of a client environment that must not reach the capture process: the capture
     * reads the display's pixels and never loads OpenGL itself.
     */
    static Map<String, String> withoutGpuEnvironment(Map<String, String> environment) {
        Map<String, String> copy = new LinkedHashMap<>(environment);
        copy.remove("LD_PRELOAD");
        copy.keySet().removeIf(name -> name.startsWith("VGL_"));
        return copy;
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
