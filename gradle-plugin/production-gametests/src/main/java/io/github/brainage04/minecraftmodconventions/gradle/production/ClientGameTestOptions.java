package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.GradleException;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Minecraft {@code options.txt} every automated client launch starts with: client GameTests, production client
 * GameTests and recordings. The values are the same for every mod; only the profile is chosen per launch. The file
 * is written again before every launch, so settings the client saves during a run never carry over to the next one.
 */
public final class ClientGameTestOptions {
    /** Environment variable naming the recorder's private PulseAudio sink; the recorder sets it for its build. */
    public static final String RECORDER_AUDIO_DEVICE_ENV = "GTR_RECORDING_AUDIO_DEVICE";
    /** Environment variable with the recorder's Xvfb screen ({@code WIDTHxHEIGHTxDEPTH}); the recorder sets it. */
    public static final String RECORDER_SCREEN_ENV = "GTR_RECORDING_XVFB_SCREEN";
    /** The recorder's screen when {@value #RECORDER_SCREEN_ENV} is not set. */
    public static final String DEFAULT_RECORDER_SCREEN = "1920x1080x24";
    /** Environment variable with the rate the recorder captures at, in frames a second; the recorder reads it too. */
    public static final String CAPTURE_FPS_ENV = "GTR_RECORDING_FPS";
    /** The capture rate when {@value #CAPTURE_FPS_ENV} is not set. */
    public static final int DEFAULT_CAPTURE_FPS = 30;
    /**
     * The highest {@code maxFps} a client accepts, which vanilla treats as no limit
     * ({@code Options.UNLIMITED_FRAMERATE_CUTOFF}, {@code Options.java:119} in the 26.3 sources).
     */
    static final int UNLIMITED_FRAME_CAP = 260;
    /** The GUI scale of a recording: 1920x1080 at scale 3 is a 640x360 GUI, the shared overlay standard. */
    public static final String SHOWCASE_GUI_SCALE = "3";
    /** The GUI scale of a test run: an 854x480 window at scale 2 still fits the caption panel. */
    public static final String TEST_GUI_SCALE = "2";
    /**
     * The options format version: Minecraft 26.3's data version. A client of a later Minecraft version upgrades
     * the file with its own data fixers.
     */
    public static final String OPTIONS_VERSION = "5023";
    private static final List<String> SOUND_CATEGORIES = List.of(
            "master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui"
    );

    public enum Profile {
        /** Speed and determinism: every unattended client GameTest run. */
        TEST,
        /** Recordings: {@link #TEST} with a longer view, fancy graphics, smooth lighting and sound. */
        SHOWCASE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Profile named(String id) {
            for (Profile profile : values()) {
                if (profile.id().equals(id)) {
                    return profile;
                }
            }
            throw new GradleException("Unknown client options profile '" + id + "'; expected test or showcase.");
        }
    }

    private ClientGameTestOptions() {
    }

    /**
     * {@code showcase} when the client GameTest recorder has given the run its private audio sink, otherwise
     * {@code test}. Only the recorder sets a sink, so other runs, including ones that bring their own display, stay
     * on the test profile.
     */
    public static Provider<String> profile(Provider<String> recordingAudioDevice) {
        return recordingAudioDevice
                .map(device -> device.isBlank() ? Profile.TEST.id() : Profile.SHOWCASE.id())
                .orElse(Profile.TEST.id());
    }

    /**
     * The recorder's screen ({@code WIDTHxHEIGHT[xDEPTH]}) from {@value #RECORDER_SCREEN_ENV}; a showcase window
     * covers it. Only the showcase profile reads it, so a test run never fails on an odd value.
     */
    public static Provider<String> recorderScreen(ProviderFactory providers) {
        return providers.environmentVariable(RECORDER_SCREEN_ENV)
                .filter(screen -> !screen.isBlank())
                .orElse(DEFAULT_RECORDER_SCREEN);
    }

    /** The recorder's capture rate from {@value #CAPTURE_FPS_ENV}, {@value #DEFAULT_CAPTURE_FPS} when unset. */
    public static Provider<Integer> captureFrameRate(ProviderFactory providers) {
        return providers.environmentVariable(CAPTURE_FPS_ENV)
                .filter(rate -> !rate.isBlank())
                .map(ClientGameTestOptions::parseCaptureFrameRate)
                .orElse(DEFAULT_CAPTURE_FPS);
    }

    static int parseCaptureFrameRate(String rate) {
        int parsed;
        try {
            parsed = Integer.parseInt(rate.strip());
        } catch (NumberFormatException exception) {
            parsed = 0;
        }
        if (parsed <= 0) {
            throw new GradleException("Expected " + CAPTURE_FPS_ENV + " to be a positive whole number, not " + rate);
        }
        return parsed;
    }

    /**
     * The client's frame cap. A client on the card may present twice as often as the capture samples the window,
     * so a captured image is a fresh one whenever the client keeps up. The software rasterizer cannot present
     * faster than the default capture rate, so its cap stays there.
     */
    public static int frameCap(boolean gpuRendering, int captureFrameRate) {
        return gpuRendering ? Math.min(UNLIMITED_FRAME_CAP, 2 * captureFrameRate) : DEFAULT_CAPTURE_FPS;
    }

    /** The width and height of a recorder screen such as {@code 1920x1080x24}. */
    static String[] windowOfScreen(String screen) {
        String[] parts = screen.split("x");
        if (parts.length < 2 || !parts[0].matches("[1-9][0-9]*") || !parts[1].matches("[1-9][0-9]*")) {
            throw new GradleException("Expected " + RECORDER_SCREEN_ENV + " to look like 1920x1080x24, not " + screen);
        }
        return new String[] {parts[0], parts[1]};
    }

    /**
     * The options of a profile in file order. {@code soundDevice} and {@code recorderScreen} (see
     * {@link #recorderScreen}) are only used by {@link Profile#SHOWCASE}: the test profile is silent, keeps the
     * default device and has an 854x480 window. {@code joinedFirstServer} suppresses the social interactions
     * toast. {@code gpuRendering} is a recording that renders on the machine's graphics card (see
     * {@code GTR_RECORDING_DISPLAY} in the client GameTest recorder): its frame cap follows
     * {@code captureFrameRate} (see {@link #frameCap}).
     */
    public static Map<String, String> options(
            Profile profile,
            String soundDevice,
            boolean joinedFirstServer,
            String recorderScreen,
            boolean gpuRendering,
            int captureFrameRate
    ) {
        boolean showcase = profile == Profile.SHOWCASE;
        String[] window = showcase ? windowOfScreen(recorderScreen) : new String[] {"854", "480"};
        Map<String, String> options = new LinkedHashMap<>();
        options.put("version", OPTIONS_VERSION);
        options.put("lang", "en_us");
        // Window: GameTests see this size whatever the display. A showcase window covers the recorder's whole
        // display at its top left; it is not fullscreen, because SDL3 (26.3) cannot make a window fullscreen
        // without a window manager, which Xvfb does not have.
        options.put("fullscreen", "false");
        options.put("overrideWidth", window[0]);
        options.put("overrideHeight", window[1]);
        // Every recording shares one overlay standard: the same GUI scale, and a caption panel that still fits.
        options.put("guiScale", showcase ? SHOWCASE_GUI_SCALE : TEST_GUI_SCALE);
        // Performance: a fixed frame cap, and no throttling when no input arrives (GameTest input does not count).
        options.put("renderDistance", showcase ? "12" : "5");
        options.put("simulationDistance", "5");
        options.put("maxFps", Integer.toString(frameCap(gpuRendering, captureFrameRate)));
        options.put("enableVsync", "false");
        options.put("inactivityFpsLimit", "\"minimized\"");
        options.put("graphicsPreset", showcase ? "\"fancy\"" : "\"fast\"");
        options.put("ao", Boolean.toString(showcase));
        options.put("cutoutLeaves", Boolean.toString(showcase));
        options.put("entityShadows", Boolean.toString(showcase));
        options.put("improvedTransparency", "false");
        options.put("renderClouds", "\"false\"");
        // ParticleStatus ids (ParticleStatus.java:12-14 in the 26.3 sources): a recording shows every particle (ALL,
        // 0). A test run keeps MINIMAL (2), which drops almost every particle (ClientLevel.java:959-987).
        options.put("particles", showcase ? "0" : "2");
        options.put("biomeBlendRadius", "0");
        options.put("chunkSectionFadeInTime", "0.0");
        options.put("entityDistanceScaling", "0.5");
        options.put("mipmapLevels", "0");
        // The lowest valid value (1 to 3); it only matters with anisotropic texture filtering, which is off:
        // textureFiltering is not written, and its 26.3 default is NONE.
        options.put("maxAnisotropyBit", "1");
        options.put("menuBackgroundBlurriness", "0");
        options.put("prioritizeChunkUpdates", "0");
        options.put("weatherRadius", "5");
        options.put("soundDevice", "\"" + (showcase ? soundDevice : "") + "\"");
        for (String category : SOUND_CATEGORIES) {
            options.put("soundCategory_" + category, showcase ? showcaseVolume(category) : "0.0");
        }
        options.put("narrator", "0");
        options.put("narratorHotkey", "false");
        options.put("tutorialStep", "none");
        options.put("onboardAccessibility", "false");
        options.put("joinedFirstServer", Boolean.toString(joinedFirstServer));
        options.put("skipMultiplayerWarning", "true");
        options.put("realmsNotifications", "false");
        options.put("telemetryOptInExtra", "false");
        options.put("musicToast", "\"never\"");
        options.put("hideSplashTexts", "true");
        options.put("showAutosaveIndicator", "false");
        options.put("pauseOnLostFocus", "false");
        options.put("autoJump", "false");
        options.put("toggleSprint", "false");
        options.put("toggleCrouch", "false");
        return options;
    }

    public static void write(
            Path file,
            Profile profile,
            String soundDevice,
            boolean joinedFirstServer,
            String recorderScreen,
            boolean gpuRendering,
            int captureFrameRate
    ) {
        String contents = options(profile, soundDevice, joinedFirstServer, recorderScreen, gpuRendering, captureFrameRate)
                .entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("\n", "", "\n"));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write client GameTest options: " + file, exception);
        }
    }

    private static String showcaseVolume(String category) {
        return switch (category) {
            // Headroom for the recorder's AAC encoding.
            case "master" -> "0.7";
            // Background music starts at random and would differ between recordings.
            case "music" -> "0.0";
            default -> "1.0";
        };
    }
}
