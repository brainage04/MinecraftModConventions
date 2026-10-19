package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class ClientGameTestRecorderExtension {
    private final Property<String> recordingAudioDeviceProperty;
    private final Property<String> recordingAudioDeviceEnv;
    private final Property<String> managedXvfbEnv;
    private final DirectoryProperty runDir;
    private final Property<String> minecraftOptionsVersion;
    private final Property<String> maxFps;
    private final Property<String> renderDistance;
    private final Property<String> simulationDistance;
    private final Property<String> guiScale;
    private final Property<String> fullscreen;
    private final Property<Boolean> disableUnsecureChatToast;
    private final Property<Boolean> disableSocialInteractionsToast;
    private final Property<Boolean> disableRecipeToasts;
    private final Property<Boolean> disableAdvancementToasts;
    private final Property<Boolean> disableAdvancementChatMessages;

    @Inject
    public ClientGameTestRecorderExtension(ObjectFactory objects, ProjectLayout layout) {
        recordingAudioDeviceProperty = objects.property(String.class).convention("minecraftModConventionsRecordingAudioDevice");
        recordingAudioDeviceEnv = objects.property(String.class).convention("GTR_RECORDING_AUDIO_DEVICE");
        managedXvfbEnv = objects.property(String.class).convention("GTR_RECORDING_MANAGED_XVFB");
        runDir = objects.directoryProperty().convention(layout.getBuildDirectory().dir("run/clientGameTest"));
        minecraftOptionsVersion = objects.property(String.class).convention("4671");
        maxFps = objects.property(String.class).convention("30");
        renderDistance = objects.property(String.class).convention("5");
        simulationDistance = objects.property(String.class).convention("5");
        guiScale = objects.property(String.class).convention("1");
        fullscreen = objects.property(String.class).convention("true");
        disableUnsecureChatToast = objects.property(Boolean.class).convention(true);
        disableSocialInteractionsToast = objects.property(Boolean.class).convention(true);
        disableRecipeToasts = objects.property(Boolean.class).convention(true);
        disableAdvancementToasts = objects.property(Boolean.class).convention(true);
        disableAdvancementChatMessages = objects.property(Boolean.class).convention(true);
    }


    public Property<String> getRecordingAudioDeviceProperty() {
        return recordingAudioDeviceProperty;
    }

    public Property<String> getRecordingAudioDeviceEnv() {
        return recordingAudioDeviceEnv;
    }

    public Property<String> getManagedXvfbEnv() {
        return managedXvfbEnv;
    }

    public DirectoryProperty getRunDir() {
        return runDir;
    }

    public Property<String> getMinecraftOptionsVersion() {
        return minecraftOptionsVersion;
    }

    public Property<String> getMaxFps() {
        return maxFps;
    }

    public Property<String> getRenderDistance() {
        return renderDistance;
    }

    public Property<String> getSimulationDistance() {
        return simulationDistance;
    }

    public Property<String> getGuiScale() {
        return guiScale;
    }

    public Property<String> getFullscreen() {
        return fullscreen;
    }

    public Property<Boolean> getDisableUnsecureChatToast() {
        return disableUnsecureChatToast;
    }

    public Property<Boolean> getDisableSocialInteractionsToast() {
        return disableSocialInteractionsToast;
    }

    public Property<Boolean> getDisableRecipeToasts() {
        return disableRecipeToasts;
    }

    public Property<Boolean> getDisableAdvancementToasts() {
        return disableAdvancementToasts;
    }

    public Property<Boolean> getDisableAdvancementChatMessages() {
        return disableAdvancementChatMessages;
    }
}
