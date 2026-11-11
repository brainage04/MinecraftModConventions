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
 * Reports lines of mod sources that compare with one vanilla block, item or biome identity where a vanilla tag also
 * lists it. The hits go to the report file and to a warning; a comparison that names a particular thing on purpose
 * keeps it.
 */
@DisableCachingByDefault(because = "It scans source lines and rewrites a small report; a cache entry saves nothing.")
public abstract class CheckVanillaTagIdentitiesTask extends DefaultTask {
    static final String GUIDANCE = """
            A comparison with one block, item or biome is right when the feature is about that particular thing by \
            name, and wrong when it stands in for a property the game reads from data. Ask: would a datapack, or \
            another mod, that adds a block or item with the same property need the same treatment? If yes, ask the \
            tag the game itself reads: `#minecraft:beds`, `#minecraft:swords`, `#minecraft:is_forest`, ... Some of \
            these have a constant (`BlockTags.BEDS`) and some moved into BlockItemTags in 26.x, so the tag id above \
            is the name that stays right.
            Placing or giving a specific block is a place, not a property, so it needs no marker. \
            `// vanilla-tag: <reason>` silences this warning for a line or the line above it; it is optional.""";

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** The Minecraft jars of the project, whose vanilla tag data names what a tag lists. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getMinecraftJars();

    @Internal
    public abstract Property<String> getRootDirectory();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        VanillaTagIndex tags = VanillaTagIndex.load(getMinecraftJars().getFiles());
        getLogger().info("Read {} tagged identities from {}", tags.size(), getMinecraftJars().getFiles());
        if (tags.isEmpty()) {
            getLogger().warn("No vanilla tag data in the Minecraft jars of this project ({}), so the vanilla tag "
                    + "identity check has nothing to match against.", getMinecraftJars().getFiles());
        }
        VanillaTagIdentityScanner scanner = VanillaTagIdentityScanner.load(tags);
        Path root = Path.of(getRootDirectory().get());
        List<File> files = getSources().getAsFileTree().getFiles().stream()
                .filter(file -> file.getName().endsWith(".java"))
                .sorted(Comparator.comparing(File::getPath))
                .toList();
        List<String> hits = new ArrayList<>();
        for (File file : files) {
            String path = root.relativize(file.toPath().toAbsolutePath()).toString();
            for (VanillaTagIdentityScanner.Finding finding
                    : scanner.scan(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))) {
                hits.add("  " + path + ":" + finding.line() + ": " + finding.source()
                        + "  (" + String.join(", ", finding.tags()) + ")"
                        + (finding.missingReason() ? "  (the vanilla-tag opt-out needs a reason)" : ""));
            }
        }
        Files.writeString(getReport().get().getAsFile().toPath(),
                hits.isEmpty() ? "" : String.join("\n", hits) + "\n");
        if (!hits.isEmpty()) {
            getLogger().warn("Vanilla tag identity in mod sources (check: place or property?):\n"
                    + String.join("\n", hits) + "\n\n" + GUIDANCE);
        }
    }
}
