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
 * Reports lines of mod sources that compare with a vanilla dimension identity instead of asking the game's data.
 * The hits go to the report file and to a warning; a place that is about the dimension by name keeps its comparison.
 */
@DisableCachingByDefault(because = "It scans source lines and rewrites a small report; a cache entry saves nothing.")
public abstract class CheckVanillaIdentityTask extends DefaultTask {
    static final String GUIDANCE = """
            A comparison with a vanilla dimension identity is right when the feature is about that dimension by name, \
            and wrong when it stands in for a property the game reads from data. Ask: would a datapack dimension, or \
            another mod's dimension, that behaves like this one need the same treatment? If yes, prepare the code to \
            ask the game:
              - environment attributes: level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos), \
            RESPAWN_ANCHOR_WORKS, WATER_EVAPORATES, ...
              - level.dimensionType() properties, such as hasCeiling() and hasSkyLight()
              - tags and game rules
            A Nether kill score or a dungeon theme that targets one vanilla dimension is about that place, so the \
            identity check is right there. On the client, confirm the attribute is synced \
            (EnvironmentAttribute.isSyncable()) or decide it server-side: an attribute that is not synced, such as \
            BED_RULE, reads its default value on the client.
            `// vanilla-identity: <reason>` silences this warning for a line or the line above it; it is optional.""";

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
        VanillaIdentityScanner scanner = VanillaIdentityScanner.load();
        Path root = Path.of(getRootDirectory().get());
        List<File> files = getSources().getAsFileTree().getFiles().stream()
                .filter(file -> file.getName().endsWith(".java"))
                .sorted(Comparator.comparing(File::getPath))
                .toList();
        List<String> hits = new ArrayList<>();
        for (File file : files) {
            String path = root.relativize(file.toPath().toAbsolutePath()).toString();
            for (VanillaIdentityScanner.Finding finding
                    : scanner.scan(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))) {
                hits.add("  " + path + ":" + finding.line() + ": " + finding.source()
                        + (finding.missingReason() ? "  (the vanilla-identity opt-out needs a reason)" : ""));
            }
        }
        Files.writeString(getReport().get().getAsFile().toPath(),
                hits.isEmpty() ? "" : String.join("\n", hits) + "\n");
        if (!hits.isEmpty()) {
            getLogger().warn("Vanilla dimension identity in mod sources (check: place or property?):\n"
                    + String.join("\n", hits) + "\n\n" + GUIDANCE);
        }
    }
}
