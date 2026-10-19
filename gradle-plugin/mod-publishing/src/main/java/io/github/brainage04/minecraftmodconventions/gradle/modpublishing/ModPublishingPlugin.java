package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import me.modmuss50.mpp.ModPublishExtension;
import me.modmuss50.mpp.ReleaseType;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github.PrepareGitHubReleaseTask;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.github.ResolveModReleaseNotesTask;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.CheckModrinthVersionTask;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.PrepareModrinthProjectMetadataTask;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.SyncModrinthIconTask;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth.SyncModrinthProjectTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Configures validated, opt-in distribution publishing through the upstream Mod Publish Plugin. */
public final class ModPublishingPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.mod-publishing";
    public static final String EXTENSION_NAME = "modPublishing";
    public static final String VALIDATE_TASK_NAME = "validateModPublication";
    public static final String RESOLVE_RELEASE_NOTES_TASK_NAME = "resolveModReleaseNotes";
    public static final String PREPARE_GITHUB_RELEASE_TASK_NAME = "prepareGitHubRelease";
    public static final String CHECK_MODRINTH_VERSION_TASK_NAME = "checkModrinthVersion";
    public static final String PREPARE_MODRINTH_TASK_NAME = "prepareModrinthProjectMetadata";
    public static final String SYNC_MODRINTH_PROJECT_TASK_NAME = "syncModrinthProject";
    public static final String SYNC_MODRINTH_ICON_TASK_NAME = "syncModrinthIcon";

    private static final String UPSTREAM_PLUGIN_ID = "me.modmuss50.mod-publish-plugin";

    @Override
    public void apply(Project project) {
        ModPublishingExtension extension = project.getExtensions().create(
                EXTENSION_NAME,
                ModPublishingExtension.class,
                project.getObjects()
        );
        configureDefaults(project, extension);

        project.getPluginManager().apply(UPSTREAM_PLUGIN_ID);
        ModPublishExtension upstream = project.getExtensions().getByType(ModPublishExtension.class);
        UpstreamModPublishingAdapter.Destinations destinations =
                UpstreamModPublishingAdapter.configure(extension, upstream);

        TaskProvider<ValidateModPublicationTask> validate = registerValidation(project, extension);
        registerReleaseNotes(project, extension);
        registerPublicationPreflights(project, extension, destinations.github(), destinations.modrinth(), validate);
        TaskProvider<PrepareModrinthProjectMetadataTask> prepareModrinth = registerMetadataPreparation(
                project,
                extension
        );
        TaskProvider<SyncModrinthProjectTask> syncProject = registerProjectSync(
                project,
                extension,
                prepareModrinth
        );
        registerIconSync(project, extension, prepareModrinth, syncProject);

        configurePublicationTask(project, "publishGithub", extension.getGithub().getEnabled(), validate);
        configurePublicationTask(project, "publishModrinth", extension.getModrinth().getEnabled(), validate);
        configurePublicationTask(project, "publishCurseforge", extension.getCurseforge().getEnabled(), validate);
        project.getTasks().named("publishMods").configure(task -> task.setDescription(
                "Publishes the validated release artifact to every enabled distribution destination."
        ));

        project.afterEvaluate(ignored -> UpstreamModPublishingAdapter.configureProjectMetadata(
                project,
                extension,
                destinations.modrinth()
        ));
    }

    private static void configureDefaults(Project project, ModPublishingExtension extension) {
        var providers = project.getProviders();
        Provider<String> version = providers.gradleProperty("mod_version")
                .orElse(providers.provider(() -> project.getVersion().toString()));
        Provider<org.gradle.api.file.RegularFile> explicitReleaseJar = project.getLayout().file(
                providers.gradleProperty("modPublishingReleaseJar")
                        .map(project::file)
                        .orElse(providers.environmentVariable("MOD_PUBLISHING_RELEASE_JAR")
                                .map(project.getRootProject()::file))
        );
        Provider<String> modName = providers.gradleProperty("mod_name")
                .orElse(providers.provider(project::getName));
        extension.getModLoaders().add("fabric");
        extension.getVersion().convention(version);
        extension.getReleaseTag().convention(
                providers.environmentVariable("RELEASE_TAG").orElse(version.map(value -> "v" + value))
        );
        extension.getDisplayName().convention(
                modName.zip(version, (name, value) -> name + " " + value)
        );
        extension.getChangelog().convention(providers.environmentVariable("RELEASE_BODY").orElse(""));
        extension.getPrerelease().convention(
                providers.environmentVariable("RELEASE_PRERELEASE")
                        .map(value -> strictBoolean(value, "RELEASE_PRERELEASE"))
                        .orElse(false)
        );
        extension.getReleaseType().convention(
                extension.getReleaseTag().zip(extension.getPrerelease(), ModPublishingPlugin::releaseType)
        );
        extension.getMinecraftVersions().addAll(
                providers.gradleProperty("minecraft_version").map(List::of).orElse(List.of())
        );
        extension.getDryRun().convention(
                providers.gradleProperty("modPublishingDryRun")
                        .map(value -> strictBoolean(value, "modPublishingDryRun"))
                        .orElse(false)
        );
        extension.getMaxRetries().convention(
                providers.gradleProperty("modPublishingMaxRetries")
                        .map(value -> nonNegativeInteger(value, "modPublishingMaxRetries"))
                        .orElse(3)
        );
        extension.getFabricModJson().convention(project.getLayout().getBuildDirectory().file("resources/main/fabric.mod.json"));
        extension.getSourceFabricModJson().convention(
                project.getLayout().getProjectDirectory().file("src/main/resources/fabric.mod.json")
        );

        Project rootProject = project.getRootProject();
        File license = firstExisting(rootProject, "LICENSE", "LICENSE.md");
        if (license != null) {
            extension.getLicenseFile().convention(
                    rootProject.getLayout().getProjectDirectory().file(license.getName())
            );
        }
        File projectConfig = rootProject.file(".modrinth/project.json");
        if (projectConfig.isFile()) {
            extension.getModrinth().getProjectConfig().convention(
                    rootProject.getLayout().getProjectDirectory().file(".modrinth/project.json")
            );
        }
        File projectBody = rootProject.file("README.md");
        if (projectBody.isFile()) {
            extension.getModrinth().getProjectBody().convention(
                    rootProject.getLayout().getProjectDirectory().file("README.md")
            );
        }

        extension.getGithub().getEnabled().convention(booleanProperty(project, "publishGithub"));
        extension.getGithub().getRepository().convention(providers.environmentVariable("GITHUB_REPOSITORY"));
        extension.getGithub().getCommitish().convention(
                providers.environmentVariable("GITHUB_SHA").orElse("main")
        );
        extension.getGithub().getToken().convention(providers.environmentVariable("GITHUB_TOKEN"));
        extension.getGithub().getApiEndpoint().convention(
                providers.gradleProperty("githubApiEndpoint").orElse("https://api.github.com")
        );

        extension.getModrinth().getEnabled().convention(booleanProperty(project, "publishModrinth"));
        extension.getModrinth().getProjectId().convention(
                providers.environmentVariable("MODRINTH_PROJECT_ID")
                        .orElse(providers.gradleProperty("modrinthProjectId"))
        );
        extension.getModrinth().getProjectSlug().convention(providers.gradleProperty("mod_id"));
        extension.getModrinth().getToken().convention(providers.environmentVariable("MODRINTH_TOKEN"));
        extension.getModrinth().getRepositoryDescription().convention(
                providers.environmentVariable("GITHUB_REPOSITORY_DESCRIPTION")
        );
        extension.getModrinth().getApiEndpoint().convention(
                providers.gradleProperty("modrinthApiEndpoint").orElse("https://api.modrinth.com/v2")
        );
        extension.getModrinth().getDiscordUrl().convention("https://discord.gg/N4zfhBx8Fm");

        extension.getCurseforge().getEnabled().convention(booleanProperty(project, "publishCurseforge"));
        extension.getCurseforge().getProjectId().convention(providers.gradleProperty("curseforgeProjectId"));
        extension.getCurseforge().getProjectSlug().convention(providers.gradleProperty("curseforgeProjectSlug"));
        extension.getCurseforge().getToken().convention(providers.environmentVariable("CURSEFORGE_TOKEN"));
        extension.getCurseforge().getApiEndpoint().convention(
                providers.gradleProperty("curseforgeApiEndpoint").orElse("https://minecraft.curseforge.com")
        );
        extension.getCurseforge().getJavaVersions().addAll(
                providers.gradleProperty("java_version").map(List::of).orElse(List.of())
        );
        extension.getCurseforge().getClient().convention(true);
        extension.getCurseforge().getServer().convention(true);

        project.getPluginManager().withPlugin("java", ignored -> {
            TaskProvider<AbstractArchiveTask> jar = project.getTasks().named("jar", AbstractArchiveTask.class);
            extension.getReleaseJar().convention(
                    explicitReleaseJar.orElse(jar.flatMap(AbstractArchiveTask::getArchiveFile))
            );
        });
        project.getPluginManager().withPlugin("fabric-loom", ignored ->
                project.getTasks()
                        .withType(AbstractArchiveTask.class)
                        .matching(task -> task.getName().equals("remapJar"))
                        .configureEach(remapJar ->
                                extension.getReleaseJar().convention(
                                        explicitReleaseJar.orElse(remapJar.getArchiveFile())
                                )
                        )
        );
    }

    private static Provider<Boolean> booleanProperty(Project project, String name) {
        return project.getProviders().gradleProperty(name)
                .map(value -> strictBoolean(value, name))
                .orElse(false);
    }


    private static TaskProvider<ValidateModPublicationTask> registerValidation(
            Project project,
            ModPublishingExtension extension
    ) {
        TaskProvider<ValidateModPublicationTask> task = project.getTasks().register(
                VALIDATE_TASK_NAME,
                ValidateModPublicationTask.class,
                validation -> {
                    validation.setGroup("verification");
                    validation.setDescription("Validates release metadata and every enabled destination without networking.");
                    validation.getVersion().convention(extension.getVersion());
                    validation.getReleaseTag().convention(extension.getReleaseTag());
                    validation.getDisplayName().convention(extension.getDisplayName());
                    validation.getChangelog().convention(extension.getChangelog());
                    validation.getReleaseType().convention(extension.getReleaseType());
                    validation.getMinecraftVersions().convention(extension.getMinecraftVersions());
                    validation.getModLoaders().convention(extension.getModLoaders());
                    validation.getReleaseJar().convention(extension.getReleaseJar());
                    validation.getFabricModJson().convention(extension.getFabricModJson());
                    validation.getDryRun().convention(extension.getDryRun());
                    validation.getMaxRetries().convention(extension.getMaxRetries());
                    validation.getGithubEnabled().convention(extension.getGithub().getEnabled());
                    validation.getGithubRepository().convention(extension.getGithub().getRepository().orElse(""));
                    validation.getGithubCommitish().convention(extension.getGithub().getCommitish().orElse(""));
                    validation.getGithubToken().convention(extension.getGithub().getToken().orElse(""));
                    validation.getModrinthEnabled().convention(extension.getModrinth().getEnabled());
                    validation.getModrinthProjectId().convention(extension.getModrinth().getProjectId().orElse(""));
                    validation.getModrinthProjectSlug().convention(extension.getModrinth().getProjectSlug().orElse(""));
                    validation.getModrinthToken().convention(extension.getModrinth().getToken().orElse(""));
                    validation.getCurseforgeEnabled().convention(extension.getCurseforge().getEnabled());
                    validation.getCurseforgeProjectId().convention(extension.getCurseforge().getProjectId().orElse(""));
                    validation.getCurseforgeToken().convention(extension.getCurseforge().getToken().orElse(""));
                    validation.dependsOn("processResources");
                    validation.dependsOn(project.getTasks().matching(candidate ->
                            candidate.getName().equals("jar") || candidate.getName().equals("remapJar")
                    ));
                }
        );
        return task;
    }

    private static void registerReleaseNotes(Project project, ModPublishingExtension extension) {
        Provider<String> userAgent = extension.getGithub().getRepository()
                .orElse("unknown")
                .map(repository -> repository + "/gradle mod-publisher");
        project.getTasks().register(
                RESOLVE_RELEASE_NOTES_TASK_NAME,
                ResolveModReleaseNotesTask.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription("Resolves explicit, annotated-tag, or GitHub-generated release notes.");
                    task.getReleaseTag().convention(extension.getReleaseTag());
                    task.getExplicitNotes().convention(extension.getChangelog());
                    task.getRepository().convention(extension.getGithub().getRepository().orElse(""));
                    task.getApiEndpoint().convention(extension.getGithub().getApiEndpoint());
                    task.getUserAgent().convention(userAgent);
                    task.getMaxRetries().convention(extension.getMaxRetries());
                    task.getToken().convention(extension.getGithub().getToken().orElse(""));
                    task.getRepositoryDirectory().convention(project.getLayout().getProjectDirectory());
                    task.getOutputFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/release-notes.md")
                    );
                    task.getOutputs().upToDateWhen(ignored -> false);
                }
        );
    }

    private static void registerPublicationPreflights(
            Project project,
            ModPublishingExtension extension,
            org.gradle.api.NamedDomainObjectProvider<me.modmuss50.mpp.platforms.github.Github> github,
            org.gradle.api.NamedDomainObjectProvider<me.modmuss50.mpp.platforms.modrinth.Modrinth> modrinth,
            TaskProvider<ValidateModPublicationTask> validate
    ) {
        Provider<String> userAgent = extension.getGithub().getRepository()
                .orElse("unknown")
                .map(repository -> repository + "/gradle mod-publisher");
        var githubPreflight = project.getTasks().register(
                PREPARE_GITHUB_RELEASE_TASK_NAME,
                PrepareGitHubReleaseTask.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription("Finds an existing GitHub release and exact release asset.");
                    task.getDestinationEnabled().convention(extension.getGithub().getEnabled());
                    task.getDryRun().convention(extension.getDryRun());
                    task.getRepository().convention(extension.getGithub().getRepository().orElse(""));
                    task.getReleaseTag().convention(extension.getReleaseTag());
                    task.getReleaseFileName().convention(
                            extension.getReleaseJar().map(file -> file.getAsFile().getName())
                    );
                    task.getApiEndpoint().convention(extension.getGithub().getApiEndpoint());
                    task.getUserAgent().convention(userAgent);
                    task.getMaxRetries().convention(extension.getMaxRetries());
                    task.getToken().convention(extension.getGithub().getToken().orElse(""));
                    task.getStateFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/github-preflight.json")
                    );
                    task.getExistingReleaseFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/github-existing-release.json")
                    );
                    task.getOutputs().upToDateWhen(ignored -> false);
                    task.dependsOn(validate);
                }
        );
        Provider<org.gradle.api.file.RegularFile> existingRelease = githubPreflight
                .flatMap(PrepareGitHubReleaseTask::getExistingReleaseFile)
                .flatMap(file -> project.getProviders().provider(
                        () -> file.getAsFile().isFile() ? file : null
                ));
        github.configure(destination -> destination.getReleaseResult().convention(existingRelease));
        Provider<org.gradle.api.file.RegularFile> githubState = githubPreflight.flatMap(
                PrepareGitHubReleaseTask::getStateFile
        );
        project.getTasks().named("publishGithub").configure(task -> {
            task.dependsOn(githubPreflight);
            task.onlyIf(ignored -> CheckModrinthVersionTask.shouldPublish(
                    githubState.get().getAsFile()
            ));
        });

        var modrinthPreflight = project.getTasks().register(
                CHECK_MODRINTH_VERSION_TASK_NAME,
                CheckModrinthVersionTask.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription("Checks whether the exact Modrinth version already exists.");
                    task.getDestinationEnabled().convention(extension.getModrinth().getEnabled());
                    task.getDryRun().convention(extension.getDryRun());
                    task.getProjectId().convention(extension.getModrinth().getProjectId().orElse(""));
                    task.getVersion().convention(extension.getVersion());
                    task.getApiEndpoint().convention(extension.getModrinth().getApiEndpoint());
                    task.getUserAgent().convention(userAgent);
                    task.getMaxRetries().convention(extension.getMaxRetries());
                    task.getToken().convention(extension.getModrinth().getToken().orElse(""));
                    task.getStateFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/modrinth-version-state.json")
                    );
                    task.getOutputs().upToDateWhen(ignored -> false);
                    task.dependsOn(validate);
                }
        );
        Provider<org.gradle.api.file.RegularFile> modrinthState = modrinthPreflight.flatMap(
                CheckModrinthVersionTask::getStateFile
        );
        project.getTasks().named("publishModrinth").configure(task -> {
            task.dependsOn(modrinthPreflight);
            task.onlyIf(ignored -> CheckModrinthVersionTask.shouldPublish(
                    modrinthState.get().getAsFile()
            ));
        });
    }

    private static TaskProvider<PrepareModrinthProjectMetadataTask> registerMetadataPreparation(
            Project project,
            ModPublishingExtension extension
    ) {
        return project.getTasks().register(
                PREPARE_MODRINTH_TASK_NAME,
                PrepareModrinthProjectMetadataTask.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription("Prepares deterministic Modrinth project create and update metadata.");
                    task.getFabricModJson().convention(extension.getFabricModJson());
                    task.getProjectConfig().convention(extension.getModrinth().getProjectConfig());
                    task.getProjectBody().convention(extension.getModrinth().getProjectBody());
                    task.getLicenseFile().convention(extension.getLicenseFile());
                    task.getIconFile().convention(extension.getModrinth().getIconFile());
                    task.getRepository().convention(extension.getGithub().getRepository().orElse(""));
                    task.getRepositoryDescription().convention(
                            extension.getModrinth().getRepositoryDescription().orElse("")
                    );
                    task.getDiscordUrl().convention(extension.getModrinth().getDiscordUrl());
                    task.getOutputFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/modrinth-project-metadata.json")
                    );
                    task.dependsOn("processResources");
                }
        );
    }

    private static TaskProvider<SyncModrinthProjectTask> registerProjectSync(
            Project project,
            ModPublishingExtension extension,
            TaskProvider<PrepareModrinthProjectMetadataTask> prepareMetadata
    ) {
        Provider<String> userAgent = extension.getGithub().getRepository()
                .orElse("unknown")
                .map(repository -> repository + "/gradle mod-publisher");
        TaskProvider<SyncModrinthProjectTask> task = project.getTasks().register(
                SYNC_MODRINTH_PROJECT_TASK_NAME,
                SyncModrinthProjectTask.class,
                sync -> {
                    sync.setGroup("publishing");
                    sync.setDescription("Creates or updates the configured Modrinth project.");
                    sync.getMetadataFile().convention(prepareMetadata.flatMap(
                            PrepareModrinthProjectMetadataTask::getOutputFile
                    ));
                    sync.getIconFile().convention(extension.getModrinth().getIconFile());
                    sync.getApiEndpoint().convention(extension.getModrinth().getApiEndpoint());
                    sync.getUserAgent().convention(userAgent);
                    sync.getMaxRetries().convention(extension.getMaxRetries());
                    sync.getDryRun().convention(extension.getDryRun());
                    sync.getToken().convention(extension.getModrinth().getToken().orElse(""));
                    sync.getProjectStateFile().convention(
                            project.getLayout().getBuildDirectory().file("mod-publishing/modrinth-project-state.json")
                    );
                    sync.dependsOn(prepareMetadata);
                    sync.onlyIf("Modrinth publication is enabled", ignored -> extension.getModrinth().getEnabled().get());
                    sync.getOutputs().upToDateWhen(ignored -> false);
                }
        );
        return task;
    }

    private static void registerIconSync(
            Project project,
            ModPublishingExtension extension,
            TaskProvider<PrepareModrinthProjectMetadataTask> prepareMetadata,
            TaskProvider<SyncModrinthProjectTask> syncProject
    ) {
        Provider<String> userAgent = extension.getGithub().getRepository()
                .orElse("unknown")
                .map(repository -> repository + "/gradle mod-publisher");
        project.getTasks().register(SYNC_MODRINTH_ICON_TASK_NAME, SyncModrinthIconTask.class, sync -> {
            sync.setGroup("publishing");
            sync.setDescription("Synchronizes the Modrinth project icon when its content changes.");
            sync.getMetadataFile().convention(prepareMetadata.flatMap(
                    PrepareModrinthProjectMetadataTask::getOutputFile
            ));
            sync.getIconFile().convention(extension.getModrinth().getIconFile());
            sync.getApiEndpoint().convention(extension.getModrinth().getApiEndpoint());
            sync.getUserAgent().convention(userAgent);
            sync.getMaxRetries().convention(extension.getMaxRetries());
            sync.getDryRun().convention(extension.getDryRun());
            sync.getToken().convention(extension.getModrinth().getToken().orElse(""));
            sync.getStateFile().convention(
                    project.getLayout().getBuildDirectory().file("mod-publishing/modrinth-icon-state.json")
            );
            sync.dependsOn(syncProject);
            sync.onlyIf("Modrinth publication is enabled", ignored -> extension.getModrinth().getEnabled().get());
            sync.getOutputs().upToDateWhen(ignored -> false);
        });
    }

    private static void configurePublicationTask(
            Project project,
            String taskName,
            Provider<Boolean> enabled,
            TaskProvider<ValidateModPublicationTask> validate
    ) {
        project.getTasks().named(taskName).configure(task -> {
            task.dependsOn(validate);
            task.onlyIf("Destination is explicitly enabled", ignored -> enabled.get());
        });
    }


    private static boolean strictBoolean(String value, String name) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new GradleException(
                    name + " must be either true or false, but was '" + value + "'."
            );
        };
    }

    private static int nonNegativeInteger(String value, String name) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 0) {
                throw new GradleException(name + " must be zero or greater, but was '" + value + "'.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new GradleException(name + " must be an integer, but was '" + value + "'.", exception);
        }
    }

    private static ReleaseType releaseType(String tag, boolean prerelease) {
        String normalized = tag.toLowerCase(Locale.ROOT);
        if (normalized.contains("alpha")) {
            return ReleaseType.ALPHA;
        }
        if (prerelease
                || normalized.contains("beta")
                || normalized.contains("rc")
                || normalized.contains("pre")) {
            return ReleaseType.BETA;
        }
        return ReleaseType.STABLE;
    }

    private static File firstExisting(Project project, String... names) {
        for (String name : names) {
            File file = project.file(name);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }
}
