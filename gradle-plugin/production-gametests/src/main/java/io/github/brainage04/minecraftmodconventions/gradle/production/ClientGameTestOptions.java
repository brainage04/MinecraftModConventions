package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.GradleException;
import org.gradle.api.provider.Provider;

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
    /**
     * The options format version: Minecraft 26.2's data version. A client of a later Minecraft version upgrades
     * the file with its own data fixers.
     */
    public static final String OPTIONS_VERSION = "4903";
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
     * The options of a profile in file order. {@code soundDevice} is only used by {@link Profile#SHOWCASE}: the
     * test profile is silent and keeps the default device. {@code joinedFirstServer} suppresses the social
     * interactions toast.
     */
    public static Map<String, String> options(Profile profile, String soundDevice, boolean joinedFirstServer) {
        boolean showcase = profile == Profile.SHOWCASE;
        Map<String, String> options = new LinkedHashMap<>();
        options.put("version", OPTIONS_VERSION);
        options.put("lang", "en_us");
        // Window: GameTests see this size whatever the display; the recorder fills its display with it.
        options.put("fullscreen", Boolean.toString(showcase));
        options.put("overrideWidth", "854");
        options.put("overrideHeight", "480");
        options.put("guiScale", "2");
        // Performance: a fixed frame cap, and no throttling when no input arrives (GameTest input does not count).
        options.put("renderDistance", showcase ? "12" : "5");
        options.put("simulationDistance", "5");
        options.put("maxFps", "30");
        options.put("enableVsync", "false");
        options.put("inactivityFpsLimit", "\"minimized\"");
        // Graphics.
        options.put("graphicsPreset", showcase ? "\"fancy\"" : "\"fast\"");
        options.put("ao", Boolean.toString(showcase));
        options.put("cutoutLeaves", Boolean.toString(showcase));
        options.put("entityShadows", Boolean.toString(showcase));
        options.put("improvedTransparency", "false");
        options.put("renderClouds", "\"false\"");
        options.put("particles", "2");
        options.put("biomeBlendRadius", "0");
        options.put("chunkSectionFadeInTime", "0.0");
        options.put("entityDistanceScaling", "0.5");
        options.put("mipmapLevels", "0");
        options.put("maxAnisotropyBit", "0");
        options.put("menuBackgroundBlurriness", "0");
        options.put("prioritizeChunkUpdates", "0");
        options.put("weatherRadius", "5");
        // Sound: silent for tests; for recordings everything but music, on the recorder's private sink.
        options.put("soundDevice", "\"" + (showcase ? soundDevice : "") + "\"");
        for (String category : SOUND_CATEGORIES) {
            options.put("soundCategory_" + category, showcase ? showcaseVolume(category) : "0.0");
        }
        // Screens, toasts and prompts a person would otherwise have to dismiss.
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
        // Input.
        options.put("autoJump", "false");
        options.put("toggleSprint", "false");
        options.put("toggleCrouch", "false");
        return options;
    }

    public static void write(Path file, Profile profile, String soundDevice, boolean joinedFirstServer) {
        String contents = options(profile, soundDevice, joinedFirstServer).entrySet().stream()
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
