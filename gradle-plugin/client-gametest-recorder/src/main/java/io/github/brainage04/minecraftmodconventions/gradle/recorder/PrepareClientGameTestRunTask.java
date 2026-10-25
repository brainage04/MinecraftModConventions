package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import io.github.brainage04.minecraftmodconventions.gradle.production.ClientGameTestOptions;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Writes a client GameTest run's {@code options.txt} from a {@link ClientGameTestOptions} profile. */
@DisableCachingByDefault(because = "The task is cheap and writes a local Minecraft options file.")
public abstract class PrepareClientGameTestRunTask extends DefaultTask {
    public PrepareClientGameTestRunTask() {
        // Also for instances a build script registers itself (a NeoForge client GameTest run).
        getShowcaseWindow().convention(ClientGameTestOptions.showcaseWindow(getProject().getProviders()));
    }

    /** {@code test} or {@code showcase}; see {@link ClientGameTestOptions.Profile}. */
    @Input
    public abstract Property<String> getProfile();

    /** The OpenAL device of the {@code showcase} profile: the recorder's private sink. */
    @Input
    public abstract Property<String> getRecordingAudioDevice();

    /** The {@code showcase} window, {@code WIDTHxHEIGHT}: the recorder's whole display. */
    @Input
    public abstract Property<String> getShowcaseWindow();

    @Input
    public abstract Property<Boolean> getDisableSocialInteractionsToast();

    @OutputFile
    public abstract RegularFileProperty getOptionsFile();

    @TaskAction
    public void writeOptions() {
        ClientGameTestOptions.write(
                getOptionsFile().get().getAsFile().toPath(),
                ClientGameTestOptions.Profile.named(getProfile().get()),
                getRecordingAudioDevice().get(),
                getDisableSocialInteractionsToast().get(),
                getShowcaseWindow().get()
        );
    }
}
