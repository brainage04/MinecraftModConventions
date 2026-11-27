package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;

public abstract class ClientGameTestRecorderExtension {
    /** The Gradle property that turns the recording's input overlay off for one run. */
    public static final String INPUT_OVERLAY_PROPERTY = "inputOverlay";

    private final Property<String> recordingAudioDeviceProperty;
    private final Property<String> recordingAudioDeviceEnv;
    private final Property<String> managedXvfbEnv;
    private final DirectoryProperty runDir;
    private final Property<Integer> clientStartupTimeoutSeconds;
    private final Property<Boolean> disableUnsecureChatToast;
    private final Property<Boolean> disableSocialInteractionsToast;
    private final Property<Boolean> disableRecipeToasts;
    private final Property<Boolean> disableAdvancementToasts;
    private final Property<Boolean> disableAdvancementChatMessages;
    private final Property<Boolean> inputOverlay;
    private final Provider<Boolean> effectiveInputOverlay;

    @Inject
    public ClientGameTestRecorderExtension(ObjectFactory objects, ProjectLayout layout, ProviderFactory providers) {
        recordingAudioDeviceProperty = objects.property(String.class).convention("minecraftModConventionsRecordingAudioDevice");
        recordingAudioDeviceEnv = objects.property(String.class).convention("GTR_RECORDING_AUDIO_DEVICE");
        managedXvfbEnv = objects.property(String.class).convention("GTR_RECORDING_MANAGED_XVFB");
        runDir = objects.directoryProperty().convention(layout.getBuildDirectory().dir("run/clientGameTest"));
        clientStartupTimeoutSeconds = objects.property(Integer.class).convention(providers
                .gradleProperty(ClientStartupDeadline.TIMEOUT_PROPERTY)
                .map(value -> Integer.parseInt(value.strip()))
                .orElse(ClientStartupDeadline.DEFAULT_TIMEOUT_SECONDS));
        disableUnsecureChatToast = objects.property(Boolean.class).convention(true);
        disableSocialInteractionsToast = objects.property(Boolean.class).convention(true);
        disableRecipeToasts = objects.property(Boolean.class).convention(true);
        disableAdvancementToasts = objects.property(Boolean.class).convention(true);
        disableAdvancementChatMessages = objects.property(Boolean.class).convention(true);
        inputOverlay = objects.property(Boolean.class).convention(true);
        effectiveInputOverlay = providers
                .gradleProperty(INPUT_OVERLAY_PROPERTY)
                .map(ClientGameTestRecorderPlugin::truthy)
                .orElse(inputOverlay);
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

    /**
     * How long an automated client launch may take to finish loading before it is stopped and its task fails;
     * defaults to the Gradle property {@value ClientStartupDeadline#TIMEOUT_PROPERTY}, else 300 seconds.
     */
    public Property<Integer> getClientStartupTimeoutSeconds() {
        return clientStartupTimeoutSeconds;
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

    /**
     * Whether a recording draws the input overlay: the keystrokes caps and a clicks-per-second row, and a cursor wherever a
     * screen has the mouse free. On by default.
     */
    public Property<Boolean> getInputOverlay() {
        return inputOverlay;
    }

    /**
     * {@link #getInputOverlay} unless {@code -PinputOverlay} is given, which wins for one run. A Gradle convention only
     * applies while no value is set, so reading the property through one would lose to {@code inputOverlay = true} in a
     * build script.
     */
    Provider<Boolean> effectiveInputOverlay() {
        return effectiveInputOverlay;
    }
}
