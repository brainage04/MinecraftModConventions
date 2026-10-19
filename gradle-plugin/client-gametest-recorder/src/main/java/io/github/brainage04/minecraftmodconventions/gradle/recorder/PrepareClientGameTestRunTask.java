package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Collectors;

@DisableCachingByDefault(because = "The task is cheap and writes a local Minecraft options file.")
public abstract class PrepareClientGameTestRunTask extends DefaultTask {
    @Input
    public abstract Property<String> getRecordingAudioDevice();

    @Input
    public abstract Property<String> getMinecraftOptionsVersion();

    @Input
    public abstract Property<String> getMaxFps();

    @Input
    public abstract Property<String> getRenderDistance();

    @Input
    public abstract Property<String> getSimulationDistance();

    @Input
    public abstract Property<String> getGuiScale();

    @Input
    public abstract Property<String> getFullscreen();

    @Input
    public abstract Property<Boolean> getDisableSocialInteractionsToast();

    @OutputFile
    public abstract RegularFileProperty getOptionsFile();

    @TaskAction
    public void writeOptions() {
        String soundDevice = getRecordingAudioDevice().get();
        // Reduce game-mix transients before the recorder applies AAC encoding headroom.
        String masterVolume = soundDevice.isBlank() ? "0.0" : "0.7";
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("version", getMinecraftOptionsVersion().get());
        settings.put("ao", "false");
        settings.put("autoJump", "false");
        settings.put("biomeBlendRadius", "0");
        settings.put("chunkSectionFadeInTime", "0.0");
        settings.put("enableVsync", "false");
        settings.put("entityDistanceScaling", "0.5");
        settings.put("entityShadows", "false");
        settings.put("fullscreen", getFullscreen().get());
        settings.put("graphicsPreset", "\"fast\"");
        settings.put("guiScale", getGuiScale().get());
        settings.put("improvedTransparency", "false");
        // Programmatic GameTest input does not reset Minecraft's ten-minute AFK timer.
        settings.put("inactivityFpsLimit", "\"minimized\"");
        settings.put("maxAnisotropyBit", "1");
        settings.put("maxFps", getMaxFps().get());
        settings.put("menuBackgroundBlurriness", "0");
        settings.put("mipmapLevels", "0");
        settings.put("joinedFirstServer", getDisableSocialInteractionsToast().get().toString());
        settings.put("narrator", "0");
        settings.put("narratorHotkey", "false");
        settings.put("particles", "2");
        settings.put("prioritizeChunkUpdates", "0");
        settings.put("renderClouds", "\"false\"");
        settings.put("renderDistance", getRenderDistance().get());
        settings.put("simulationDistance", getSimulationDistance().get());
        settings.put("soundDevice", "\"" + soundDevice + "\"");
        settings.put("soundCategory_master", masterVolume);
        settings.put("soundCategory_music", "0.0");
        settings.put("toggleSprint", "false");
        settings.put("weatherRadius", "5");

        var file = getOptionsFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, settings.entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue())
                    .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator())));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write client GameTest options: " + file, exception);
        }
    }
}
