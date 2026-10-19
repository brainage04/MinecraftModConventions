package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the EULA files required by production server and embedded client GameTest servers. */
@CacheableTask
public abstract class PrepareProductionGameTestRunsTask extends DefaultTask {
    @OutputFile
    public abstract RegularFileProperty getClientEulaFile();


    @OutputFile
    public abstract RegularFileProperty getClientOptionsFile();
    @OutputFile
    public abstract RegularFileProperty getServerEulaFile();

    @TaskAction
    public void prepare() {
        writeEula(getClientEulaFile().get().getAsFile().toPath());
        writeClientOptions(getClientOptionsFile().get().getAsFile().toPath());
        writeEula(getServerEulaFile().get().getAsFile().toPath());
    }

    private static void writeEula(Path path) {
        write(path, "eula=true\n", "production GameTest EULA");
    }

    private static void writeClientOptions(Path path) {
        write(
                path,
                "soundCategory_master:0.0\nsoundCategory_music:0.0\n",
                "silent production client GameTest options"
        );
    }

    private static void write(Path path, String contents, String description) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write " + description + ": " + path, exception);
        }
    }
}
