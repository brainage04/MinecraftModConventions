package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reports lines of mod sources that use a literal build limit of the vanilla overworld instead of the height of the
 * level the code runs in. The hits go to the report file and to a warning.
 */
@DisableCachingByDefault(because = "It scans source lines and rewrites a small report; a cache entry saves nothing.")
public abstract class CheckWorldHeightTask extends DefaultTask {
    static final String GUIDANCE = """
            A world's build limits come from its dimension type, not from the overworld: a datapack dimension can be \
            256 blocks tall, start at 0, or end well above 320, and the Nether and the End run from 0 to 255 while \
            the overworld runs from -64 to 319. Ask the level the code runs in:
              - level.getMinY() for the lowest block, level.getMaxY() for the highest
              - level.getHeight() for the number of blocks
              - level.dimensionType().minY() and height() when no level is at hand
            `// world-height: <reason>` silences this warning for a line or the line above it; it is optional.""";

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @Internal
    public abstract Property<String> getRootDirectory();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        WorldHeightScanner scanner = WorldHeightScanner.load();
        Path root = Path.of(getRootDirectory().get());
        List<File> files = getSources().getAsFileTree().getFiles().stream()
                .filter(file -> file.getName().endsWith(".java"))
                .sorted(Comparator.comparing(File::getPath))
                .toList();
        List<String> hits = new ArrayList<>();
        for (File file : files) {
            String path = root.relativize(file.toPath().toAbsolutePath()).toString();
            for (WorldHeightScanner.Finding finding
                    : scanner.scan(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))) {
                hits.add("  " + path + ":" + finding.line() + ": " + finding.source()
                        + (finding.missingReason() ? "  (the world-height opt-out needs a reason)" : ""));
            }
        }
        Files.writeString(getReport().get().getAsFile().toPath(),
                hits.isEmpty() ? "" : String.join("\n", hits) + "\n");
        if (!hits.isEmpty()) {
            getLogger().warn("Hardcoded world height in mod sources:\n" + String.join("\n", hits)
                    + "\n\n" + GUIDANCE);
        }
    }
}
