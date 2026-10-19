package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import me.modmuss50.mpp.ReleaseType;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/** Validates one release and every enabled destination without contacting a network service. */
@DisableCachingByDefault(because = "Validation tasks have no reusable output")
public abstract class ValidateModPublicationTask extends DefaultTask {
    private static final Pattern MODRINTH_ID = Pattern.compile("[0-9A-Za-z]{8}");
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("[^/\\s]+/[^/\\s]+");

    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getReleaseTag();

    @Input
    public abstract Property<String> getDisplayName();

    @Input
    public abstract Property<String> getChangelog();

    @Input
    public abstract Property<ReleaseType> getReleaseType();

    @Input
    public abstract ListProperty<String> getMinecraftVersions();

    @Input
    public abstract ListProperty<String> getModLoaders();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getReleaseJar();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getFabricModJson();

    @Input
    public abstract Property<Boolean> getDryRun();

    @Input
    public abstract Property<Integer> getMaxRetries();

    @Input
    public abstract Property<Boolean> getGithubEnabled();

    @Input
    @Optional
    public abstract Property<String> getGithubRepository();

    @Input
    @Optional
    public abstract Property<String> getGithubCommitish();

    @Internal
    public abstract Property<String> getGithubToken();

    @Input
    public abstract Property<Boolean> getModrinthEnabled();

    @Input
    @Optional
    public abstract Property<String> getModrinthProjectId();

    @Input
    @Optional
    public abstract Property<String> getModrinthProjectSlug();

    @Internal
    public abstract Property<String> getModrinthToken();

    @Input
    public abstract Property<Boolean> getCurseforgeEnabled();

    @Input
    @Optional
    public abstract Property<String> getCurseforgeProjectId();

    @Internal
    public abstract Property<String> getCurseforgeToken();

    @TaskAction
    void validatePublication() {
        boolean github = getGithubEnabled().get();
        boolean modrinth = getModrinthEnabled().get();
        boolean curseforge = getCurseforgeEnabled().get();
        if (!github && !modrinth && !curseforge) {
            throw new GradleException("No mod publication destination is enabled.");
        }

        if (getMaxRetries().get() < 0) {
            throw new GradleException("modPublishing.maxRetries must be zero or greater.");
        }

        required(getVersion(), "modPublishing.version");
        required(getReleaseTag(), "modPublishing.releaseTag");
        required(getDisplayName(), "modPublishing.displayName");
        nonEmpty(getMinecraftVersions().get(), "modPublishing.minecraftVersions");
        nonEmpty(getModLoaders().get(), "modPublishing.modLoaders");

        File releaseJar = getReleaseJar().get().getAsFile();
        if (!releaseJar.getName().endsWith(".jar")) {
            throw new GradleException("Configured release artifact is not a JAR: " + releaseJar);
        }

        boolean requireTokens = !getDryRun().get();
        if (github) {
            String repository = required(getGithubRepository(), "modPublishing.github.repository");
            if (!GITHUB_REPOSITORY.matcher(repository).matches()) {
                throw new GradleException("GitHub repository must use owner/name form: " + repository);
            }
            required(getGithubCommitish(), "modPublishing.github.commitish");
            if (requireTokens) {
                required(getGithubToken(), "modPublishing.github.token or GITHUB_TOKEN");
            }
        }
        if (modrinth) {
            required(getModrinthProjectSlug(), "modPublishing.modrinth.projectSlug");
            if (!getDryRun().get()) {
                String projectId = required(
                        getModrinthProjectId(),
                        "modPublishing.modrinth.projectId or MODRINTH_PROJECT_ID; run syncModrinthProject first"
                );
                if (!MODRINTH_ID.matcher(projectId).matches()) {
                    throw new GradleException("Modrinth project ID must contain exactly eight letters or digits: " + projectId);
                }
            }
            if (requireTokens) {
                required(getModrinthToken(), "modPublishing.modrinth.token or MODRINTH_TOKEN");
            }
        }
        if (curseforge) {
            required(getCurseforgeProjectId(), "modPublishing.curseforge.projectId");
            if (requireTokens) {
                required(getCurseforgeToken(), "modPublishing.curseforge.token or CURSEFORGE_TOKEN");
            }
        }
    }

    private static String required(Property<String> property, String name) {
        String value = property.getOrElse("").trim();
        if (value.isEmpty()) {
            throw new GradleException("Missing required publication setting: " + name);
        }
        return value;
    }

    private static void nonEmpty(List<String> values, String name) {
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new GradleException(name + " must contain at least one non-blank value.");
        }
    }
}
