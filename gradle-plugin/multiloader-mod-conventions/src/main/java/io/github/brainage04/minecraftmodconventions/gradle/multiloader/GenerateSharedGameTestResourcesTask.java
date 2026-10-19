package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Writes the GameTest resources the conventions share with every mod into a generated resource directory of the
 * {@code gametest} source set: the 8x8x8 empty structure {@code minecraftmodconventions:empty}.
 */
@DisableCachingByDefault(because = "Copies one small bundled file; caching costs more than regenerating it")
public abstract class GenerateSharedGameTestResourcesTask extends DefaultTask {
    private static final String EMPTY_STRUCTURE_PATH = "data/minecraftmodconventions/structure/empty.nbt";
    private static final String EMPTY_STRUCTURE_RESOURCE = "empty.nbt";

    public GenerateSharedGameTestResourcesTask() {
        setDescription("Generates the shared GameTest structure minecraftmodconventions:empty (8x8x8 of air).");
    }

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /** Content hash of the bundled structure, so a plugin upgrade that changes it regenerates the output. */
    @Input
    public String getEmptyStructureHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emptyStructure()));
        } catch (NoSuchAlgorithmException exception) {
            throw new GradleException("SHA-256 is unavailable.", exception);
        }
    }

    @TaskAction
    public void generate() throws IOException {
        Path target = getOutputDirectory().get().getAsFile().toPath().resolve(EMPTY_STRUCTURE_PATH);
        Files.createDirectories(target.getParent());
        Files.write(target, emptyStructure());
    }

    private static byte[] emptyStructure() {
        try (InputStream stream = GenerateSharedGameTestResourcesTask.class.getResourceAsStream(EMPTY_STRUCTURE_RESOURCE)) {
            if (stream == null) {
                throw new GradleException("The conventions plugin is missing its " + EMPTY_STRUCTURE_RESOURCE + " resource.");
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
