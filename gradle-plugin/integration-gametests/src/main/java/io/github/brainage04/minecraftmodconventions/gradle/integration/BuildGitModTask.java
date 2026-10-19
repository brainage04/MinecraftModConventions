package io.github.brainage04.minecraftmodconventions.gradle.integration;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Checks out one pinned commit of a multi-loader mod repository and builds its release JARs with the repository's
 * own Gradle wrapper ({@code ./gradlew collectReleaseArtifacts}), so both loaders' JARs end up in the checkout's
 * {@code build/libs}. The checkout is kept between builds; the task is up to date while those JARs are.
 */
@DisableCachingByDefault(because = "Builds another repository with its own Gradle wrapper.")
public abstract class BuildGitModTask extends DefaultTask {
    static final String BUILD_TASK = "collectReleaseArtifacts";

    @Input
    public abstract Property<String> getRepository();

    @Input
    public abstract Property<String> getCommit();

    @Internal
    public abstract DirectoryProperty getCheckoutDirectory();

    /** The checkout's {@code build/libs}: the Fabric and NeoForge release JARs. */
    @OutputDirectory
    public abstract DirectoryProperty getReleaseJarsDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void build() {
        File checkout = getCheckoutDirectory().get().getAsFile();
        String commit = getCommit().get();
        if (!new File(checkout, ".git").isDirectory()) {
            if (!checkout.mkdirs() && !checkout.isDirectory()) {
                throw new GradleException("Cannot create " + checkout + ".");
            }
            git(checkout, "init", "--quiet");
        }
        if (!commit.equals(head(checkout))) {
            getLogger().lifecycle("Fetching {} at {}", getRepository().get(), commit);
            git(checkout, "fetch", "--quiet", "--depth", "1", getRepository().get(), commit);
            git(checkout, "checkout", "--quiet", "--force", "--detach", commit);
        }
        getLogger().lifecycle("Building {} at {}", getRepository().get(), commit);
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(checkout);
            spec.setExecutable(new File(checkout, "gradlew").getAbsolutePath());
            spec.setArgs(List.of("--no-daemon", "--console=plain", BUILD_TASK));
            // The wrapper runs on the JVM running this build rather than whichever JAVA_HOME the shell has.
            spec.environment("JAVA_HOME", System.getProperty("java.home"));
        });
    }

    private String head(File checkout) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var result = getExecOperations().exec(spec -> {
            spec.setWorkingDir(checkout);
            spec.commandLine("git", "rev-parse", "--verify", "--quiet", "HEAD");
            spec.setStandardOutput(output);
            spec.setIgnoreExitValue(true);
        });
        return result.getExitValue() == 0 ? output.toString(StandardCharsets.UTF_8).strip() : "";
    }

    private void git(File checkout, String... arguments) {
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(checkout);
            spec.setExecutable("git");
            spec.setArgs(List.of(arguments));
        });
    }
}
