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
    /** {@code test} or {@code showcase}; see {@link ClientGameTestOptions.Profile}. */
    @Input
    public abstract Property<String> getProfile();

    /** The OpenAL device of the {@code showcase} profile: the recorder's private sink. */
    @Input
    public abstract Property<String> getRecordingAudioDevice();

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
                getDisableSocialInteractionsToast().get()
        );
    }
}
