package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the production client's options from a {@link ClientGameTestOptions} profile and the EULA files required by
 * production server and embedded client GameTest servers.
 */
@CacheableTask
public abstract class PrepareProductionGameTestRunsTask extends DefaultTask {
    @OutputFile
    public abstract RegularFileProperty getClientEulaFile();

    @OutputFile
    public abstract RegularFileProperty getClientOptionsFile();

    @OutputFile
    public abstract RegularFileProperty getServerEulaFile();

    /** {@code test} or {@code showcase}; see {@link ClientGameTestOptions.Profile}. */
    @Input
    public abstract Property<String> getClientOptionsProfile();

    /** The OpenAL device of the {@code showcase} profile. */
    @Input
    public abstract Property<String> getClientSoundDevice();

    @Input
    public abstract Property<Boolean> getDisableSocialInteractionsToast();

    @TaskAction
    public void prepare() {
        writeEula(getClientEulaFile().get().getAsFile().toPath());
        ClientGameTestOptions.write(
                getClientOptionsFile().get().getAsFile().toPath(),
                ClientGameTestOptions.Profile.named(getClientOptionsProfile().get()),
                getClientSoundDevice().get(),
                getDisableSocialInteractionsToast().get()
        );
        writeEula(getServerEulaFile().get().getAsFile().toPath());
    }

    private static void writeEula(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "eula=true\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write production GameTest EULA: " + path, exception);
        }
    }
}
