package io.github.brainage04.minecraftmodconventions.gradle.quality;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
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
 * Fails the build on a call to a GameTest API that the game marks for removal, and on a
 * {@code @SuppressWarnings} hiding deprecation warnings, so a mod cannot go on compiling against a
 * method that a later Minecraft version deletes.
 */
@DisableCachingByDefault(because = "It either finds nothing or fails, and it has no output to cache.")
public abstract class CheckDeprecatedGameTestApisTask extends DefaultTask {
    static final String GUIDANCE = """
            GameTestHelper#makeMockServerPlayerInLevel() is @Deprecated(forRemoval = true) in Minecraft 26.3 \
            (net/minecraft/gametest/framework/GameTestHelper.java:395) and is deleted in a later release. \
            Build the player with GameTestHelper#makeMockServerPlayer(GameType) instead: it is the same server \
            player without joining it, so it is not in the player list and chat, permissions and player-list \
            lookups do not see it (GameTestHelper.java:372). A test that needs a joined player joins one \
            through a supported API, such as the GameTestPlayer helper in BrainageLib's testSupport source set.
            A @SuppressWarnings("removal") or @SuppressWarnings("deprecation") hides such a call from javac, \
            so -Werror cannot fail on it, and it is reported here too. Keep a suppression only where the \
            deprecation is understood and the API is still present, with a reason on the annotation's line \
            or the line above it: `// deprecated-api: <reason>`.""";

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @Internal
    public abstract Property<String> getRootDirectory();

    @TaskAction
    public void check() throws IOException {
        Path root = Path.of(getRootDirectory().get());
        List<File> files = getSources().getAsFileTree().getFiles().stream()
                .filter(file -> file.getName().endsWith(".java"))
                .sorted(Comparator.comparing(File::getPath))
                .toList();
        List<String> calls = new ArrayList<>();
        List<String> suppressions = new ArrayList<>();
        for (File file : files) {
            String path = root.relativize(file.toPath().toAbsolutePath()).toString();
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (DeprecatedGameTestApiScanner.Finding finding : DeprecatedGameTestApiScanner.scan(lines)) {
                calls.add("  " + path + ":" + finding.line() + ": " + finding.source());
            }
            for (DeprecatedGameTestApiScanner.Suppression suppression
                    : DeprecatedGameTestApiScanner.scanSuppressions(lines)) {
                if (!suppression.missingReason()) {
                    continue;
                }
                suppressions.add("  " + path + ":" + suppression.line() + ": " + suppression.source()
                        + "  (the deprecated-api marker needs a reason)");
            }
        }
        if (calls.isEmpty() && suppressions.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        if (!calls.isEmpty()) {
            message.append("A GameTest API that is marked for removal is called:\n")
                    .append(String.join("\n", calls))
                    .append("\n\n");
        }
        if (!suppressions.isEmpty()) {
            message.append("A @SuppressWarnings hides deprecation warnings, which -Werror cannot see:\n")
                    .append(String.join("\n", suppressions))
                    .append("\n\n");
        }
        throw new GradleException(message.append(GUIDANCE).toString());
    }
}
