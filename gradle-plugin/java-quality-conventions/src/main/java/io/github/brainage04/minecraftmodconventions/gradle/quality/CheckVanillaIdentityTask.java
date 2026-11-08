package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fails when mod sources compare with a vanilla dimension identity instead of asking the game's data. */
public abstract class CheckVanillaIdentityTask extends DefaultTask {
    static final String GUIDANCE = """
            Vanilla decides what a dimension allows from data that datapacks and other mods change, so a check for \
            the Overworld, the Nether or the End is wrong in custom dimensions and in changed vanilla ones. Use the \
            data-driven rule the game itself reads:
              - environment attributes: level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos), \
            RESPAWN_ANCHOR_WORKS, WATER_EVAPORATES, ...
              - level.dimensionType() properties, such as hasCeiling() and hasSkyLight()
              - tags and game rules
            On the client, confirm the attribute is synced (EnvironmentAttribute.isSyncable()) or decide it \
            server-side: an attribute that is not synced, such as BED_RULE, reads its default value on the client.
            If the mod really targets one vanilla dimension, or a client mod has no synced rule to read, put \
            `// vanilla-identity: <reason>` on the line or the line above it.""";

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** Paths in the failure message are relative to this directory. */
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
        Files.writeString(getReport().get().getAsFile().toPath(), String.join("\n", hits) + "\n");
        if (!hits.isEmpty()) {
            throw new GradleException("Hardcoded vanilla dimension identity in mod sources:\n"
                    + String.join("\n", hits) + "\n\n" + GUIDANCE);
        }
    }
}
